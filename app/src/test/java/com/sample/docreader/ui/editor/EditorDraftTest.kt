package com.sample.docreader.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.sample.docreader.core.io.DocumentWriter
import com.sample.docreader.core.io.DraftStore
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * TC-UT-09b：草稿恢复与清理（M7c 先行失败测试，TDD 红 → OpenCode 实现转绿）。
 * TC-F3-06/07（VM 侧）：自动保存默认关闭 / 开启后停顿 3s 自动落盘。
 *
 * 被测 API 约定（M7c，设计§3.4）：
 * - `core/io/DraftStore.kt`：`class DraftStore(filesDir: File)`，草稿写 `filesDir/edit_recovery/<md5(uri) 十六进制前 16 字符>.draft`；
 *   `write(uri, text)` / `read(uri): String?` / `delete(uri)` / `draftFileFor(uri): File` /
 *   `cleanupOrphans(olderThanMs: Long = 7 天, now: Long = …): Int`（返回删除数量）
 * - `EditorViewModel` 构造新增可选参数 `draftStore: DraftStore? = null`、`savedStateHandle: SavedStateHandle? = null`
 * - 内容变化防抖 1s 写草稿（仅 dirty 时；内容退回基线则删除草稿）；uri/光标存 SavedStateHandle（轻量，不存正文）
 * - `load()` 载入原文为基线后，若草稿存在则恢复草稿文本 + 光标 + dirty
 * - `save()` 成功 / `discardDraft()`（用户选"放弃"）→ 删除对应草稿
 * - 自动保存：`settings.autoSave`（默认 false）开启时，内容变化 debounce 3s 自动调 `save()`；关闭则不落盘
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorDraftTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val uri = "content://com.example.provider/docs/note.txt"
    private val originalText = "原始内容第一行"

    private class FakeOpener(text: String) : FileOpener {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        override fun open(uri: String): InputStream = ByteArrayInputStream(bytes)
    }

    private class FakeWriter : DocumentWriter {
        val modes = mutableListOf<String>()
        var writtenBytes: ByteArray? = null
            private set

        override fun openOutput(uri: String, mode: String): OutputStream {
            modes += mode
            val buf = ByteArrayOutputStream()
            return object : FilterOutputStream(buf) {
                override fun close() {
                    super.close()
                    writtenBytes = buf.toByteArray()
                }
            }
        }
    }

    private fun newDraftStore(): DraftStore = DraftStore(tmpFolder.newFolder("files"))

    private fun newVm(
        draftStore: DraftStore,
        handle: SavedStateHandle? = null,
        writer: DocumentWriter? = null
    ) = EditorViewModel(
        fileOpener = FakeOpener(originalText),
        documentWriter = writer,
        cacheDir = tmpFolder.newFolder("cache"),
        draftStore = draftStore,
        savedStateHandle = handle
    )

    /** 解析内部走真实 IO 线程，测试调度器覆盖不到，需轮询等待加载结束（同 ReaderViewModelTest） */
    private fun TestScope.awaitLoaded(vm: EditorViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.loading) {
            advanceUntilIdle()
            if (!vm.loading) break
            check(System.currentTimeMillis() < deadline) { "加载超时" }
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    private fun md5Hex16(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    /** TC-UT-09b：内容变化防抖 1s 写草稿，文件名为 uri 的 MD5 前 16 字符 + .draft */
    @Test
    fun tcUt09b_draftWrittenAfterDebounce_md5Name() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val vm = newVm(store)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        vm.onContentChange(TextFieldValue("原始内容第一行 + 修改"))
        advanceTimeBy(400)
        assertNull("防抖窗口内不应写草稿", store.read(uri))

        advanceTimeBy(800) // 累计 1.2s，超过 1s 防抖
        advanceUntilIdle()
        val draft = store.read(uri)
        assertEquals("原始内容第一行 + 修改", draft)

        // 文件名契约：edit_recovery/<md5(uri) 前 16>.draft
        val expected = File(File(store.draftFileFor(uri).parent!!), "")
        assertEquals("md5 前 16 字符命名", md5Hex16(uri) + ".draft", store.draftFileFor(uri).name)
        assertEquals("edit_recovery", expected.name)
    }

    /** TC-UT-09b：模拟进程重建（新 VM + 同一 SavedStateHandle + 草稿文件），恢复文本/光标/dirty */
    @Test
    fun tcUt09b_recreate_restoresTextCursorDirty() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val handle = SavedStateHandle()
        val vm1 = newVm(store, handle)
        vm1.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm1)

        val edited = "原始内容第一行 + 进程死亡前的修改"
        vm1.onContentChange(TextFieldValue(edited, selection = TextRange(5)))
        advanceTimeBy(1200)
        advanceUntilIdle()

        // 进程被杀 → 新 VM 拿同一 SavedStateHandle（轻量状态）+ 草稿文件（正文）
        val vm2 = newVm(store, handle)
        vm2.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm2)

        assertEquals("正文应从草稿恢复", edited, vm2.content.text)
        assertEquals("光标应恢复", TextRange(5), vm2.content.selection)
        assertTrue("草稿 ≠ 原文，dirty 应为 true", vm2.dirty)
        assertTrue("恢复后可撤销回原文", vm2.canUndo.not()) // 草稿恢复不伪造历史，undo 栈为空
    }

    /** TC-UT-09b：保存成功后草稿删除（TC-F3-01"草稿被删除"条在 M7c 落地） */
    @Test
    fun tcUt09b_saveSuccess_deletesDraft() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val writer = FakeWriter()
        val vm = newVm(store, writer = writer)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        vm.onContentChange(TextFieldValue("原始内容第一行 + 待保存"))
        advanceTimeBy(1200)
        advanceUntilIdle()
        assertTrue(store.read(uri) != null)

        vm.save()
        advanceUntilIdle()
        assertTrue(vm.saveResult is SaveResult.Success)
        assertNull("保存成功后草稿应删除", store.read(uri))
        assertArrayEquals("落盘内容应为编辑后文本", "原始内容第一行 + 待保存".toByteArray(Charsets.UTF_8), writer.writtenBytes)
    }

    /** TC-UT-09b：用户选"放弃"（discardDraft）后草稿删除 */
    @Test
    fun tcUt09b_discard_deletesDraft() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val vm = newVm(store)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        vm.onContentChange(TextFieldValue("原始内容第一行 + 放弃的修改"))
        advanceTimeBy(1200)
        advanceUntilIdle()
        assertTrue(store.read(uri) != null)

        vm.discardDraft()
        advanceUntilIdle()
        assertNull("放弃后草稿应删除", store.read(uri))
    }

    /** TC-UT-09b：内容退回基线（不脏）时草稿删除，不残留 */
    @Test
    fun tcUt09b_backToBaseline_deletesDraft() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val vm = newVm(store)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        vm.onContentChange(TextFieldValue("原始内容第一行 + 临时修改"))
        advanceTimeBy(1200)
        advanceUntilIdle()
        assertTrue(store.read(uri) != null)

        vm.undo() // 退回基线，dirty=false
        advanceTimeBy(1200)
        advanceUntilIdle()
        assertNull("退回基线后草稿应删除", store.read(uri))
    }

    /** TC-UT-09b：启动清理删除 7 天前孤儿草稿，7 天内保留（TC-F4-06a ③） */
    @Test
    fun tcUt09b_cleanupOrphans_deletesOlderThan7Days() {
        val store = newDraftStore()
        store.write("content://a", "草稿A")
        store.write("content://b", "草稿B")
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        store.draftFileFor("content://a").setLastModified(now - 8 * day)
        store.draftFileFor("content://b").setLastModified(now - 1 * day)

        val deleted = store.cleanupOrphans(now = now)

        assertEquals(1, deleted)
        assertFalse(store.draftFileFor("content://a").exists())
        assertTrue(store.draftFileFor("content://b").exists())
    }

    /** TC-F3-06（VM 侧）：自动保存默认关闭——编辑后停留 5s 不落盘（草稿仍存在，见 TC-F3-06 手工） */
    @Test
    fun tcF306_autoSaveOffByDefault_noWriteback() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val writer = FakeWriter()
        val vm = newVm(store, writer = writer)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        vm.onContentChange(TextFieldValue("原始内容第一行 + 自动保存关闭"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        assertTrue("自动保存默认关闭，不应有任何写回", writer.modes.isEmpty())
        assertTrue("草稿应已写入（进程死亡恢复用）", store.read(uri) != null)
        assertTrue(vm.dirty)
    }

    /** TC-F3-07（VM 侧）：设置开启自动保存后，内容变化停顿 > 3s 自动落盘 */
    @Test
    fun tcF307_autoSaveOn_writesAfter3sDebounce() = runTest(mainDispatcher) {
        val store = newDraftStore()
        val writer = FakeWriter()
        val vm = newVm(store, writer = writer)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)
        vm.setAutoSave(true) // 经 SettingsRepository 持久化由 UI 层负责；VM 侧只钉行为开关
        advanceUntilIdle()

        vm.onContentChange(TextFieldValue("原始内容第一行 + 自动保存"))
        advanceTimeBy(2000)
        advanceUntilIdle()
        assertTrue("3s 防抖窗口内不应落盘", writer.modes.isEmpty())

        advanceTimeBy(1500) // 累计 3.5s
        advanceUntilIdle()
        assertEquals(listOf("wt"), writer.modes)
        assertArrayEquals("原始内容第一行 + 自动保存".toByteArray(Charsets.UTF_8), writer.writtenBytes)
        assertFalse("自动保存成功后 dirty 归 false", vm.dirty)
        assertNull("自动保存成功后草稿应删除", store.read(uri))
    }
}
