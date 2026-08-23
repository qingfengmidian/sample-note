package com.sample.docreader.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.sample.docreader.core.io.DocumentWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * TC-UT-08：EditorViewModel 撤销/重做（M7a 先行失败测试，TDD 红 → OpenCode 实现转绿）。
 * TC-UT-09/09a：保存流程（M7b 先行失败测试，TDD 红 → OpenCode 实现转绿）。
 *
 * M7a 被测 API 约定：
 * - `EditorViewModel(initialText: String)`：初始文本即"已保存"基线
 * - `content: TextFieldValue`：当前编辑内容（含光标/选区）
 * - `dirty: Boolean`：当前文本 ≠ 基线（撤销回到基线时归 false；保存成功后基线更新）
 * - `canUndo / canRedo: Boolean`
 * - `onContentChange(newValue: TextFieldValue)`：文本变化时入历史栈；仅光标/选区变化（text 相同）不入栈
 * - `undo() / redo()`：历史栈上限 100 步，超出丢弃最早记录（设计§3.4）
 *
 * M7b 新增 API 约定（保存，设计§3.4 + S1 探针结论）：
 * - 构造新增可选参数 `documentWriter: DocumentWriter? = null`、`cacheDir: File? = null`（M7a 单参调用不受影响）
 * - `save()`：写前校验 dirty（非 dirty 空操作）；文本按 UTF-8 先写 `cacheDir` 临时文件并校验字节完整性，
 *   再经 `DocumentWriter.openOutput(uri, "wt")` 一次性写回；"wt" 抛 FileNotFoundException/
 *   UnsupportedOperationException 时回退 "w" 重写；仍失败则保留临时文件（内容不丢）并报 `SaveResult.FailedSaveAs`
 * - `saveResult: SaveResult?`：一次性结果事件，`SaveResult` 为 sealed：Success / FailedSaveAs / ProviderKilled
 *   （Provider 进程被 OEM 杀死抛 "No content provider" 时不按权限失效处理，S1 实测）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    /** 记录 openOutput 的 mode 序列与写入字节；可按 mode 注入异常（模拟 Provider 不支持 "wt" 等） */
    private class FakeDocumentWriter(
        private val errorOn: (String) -> Exception? = { null }
    ) : DocumentWriter {
        val modes = mutableListOf<String>()
        var writtenBytes: ByteArray? = null
            private set

        override fun openOutput(uri: String, mode: String): OutputStream {
            modes += mode
            errorOn(mode)?.let { throw it }
            val buf = ByteArrayOutputStream()
            return object : FilterOutputStream(buf) {
                override fun close() {
                    super.close()
                    writtenBytes = buf.toByteArray()
                }
            }
        }
    }

    /** TC-UT-08：输入→撤销→重做序列，状态与预期快照一致 */
    @Test
    fun tcUt08_undoRedo_sequence() {
        val vm = EditorViewModel("hello")

        vm.onContentChange(TextFieldValue("hello world"))
        vm.onContentChange(TextFieldValue("hello world!"))
        vm.onContentChange(TextFieldValue("hello world?"))
        assertEquals("hello world?", vm.content.text)
        assertTrue(vm.canUndo)
        assertFalse(vm.canRedo)

        vm.undo(); vm.undo(); vm.undo()
        assertEquals("hello", vm.content.text)
        assertFalse(vm.canUndo)
        assertTrue(vm.canRedo)

        vm.redo(); vm.redo()
        assertEquals("hello world!", vm.content.text)
        assertTrue(vm.canUndo)
        assertTrue(vm.canRedo)
    }

    /** TC-UT-08：dirty 以初始文本为基线，撤销回初始文本后归 false（退出拦截的依据，TC-F3-03） */
    @Test
    fun tcUt08_dirty_tracksAgainstInitialText() {
        val vm = EditorViewModel("a")
        assertFalse(vm.dirty)

        vm.onContentChange(TextFieldValue("ab"))
        assertTrue(vm.dirty)

        vm.undo()
        assertEquals("a", vm.content.text)
        assertFalse(vm.dirty)
    }

    /** TC-UT-08：仅光标/选区变化（text 相同）不产生历史、不置 dirty */
    @Test
    fun tcUt08_cursorOnlyChange_noHistory() {
        val vm = EditorViewModel("ab")

        vm.onContentChange(TextFieldValue("ab", selection = TextRange(1)))
        assertFalse(vm.canUndo)
        assertFalse(vm.dirty)
        assertEquals(TextRange(1), vm.content.selection) // 光标位置仍生效
    }

    /** TC-UT-08：历史栈满 100 步后最早的记录被丢弃 */
    @Test
    fun tcUt08_historyCap100_dropsOldest() {
        val vm = EditorViewModel("")
        repeat(101) { i -> vm.onContentChange(TextFieldValue("${i + 1}")) }
        assertEquals("101", vm.content.text)

        repeat(100) { vm.undo() }
        assertFalse("100 次撤销后栈应为空（最早的初始文本已被丢弃）", vm.canUndo)
        assertEquals("1", vm.content.text) // 只能退到第 1 次编辑后的状态，初始 "" 已丢弃

        vm.redo()
        assertEquals("2", vm.content.text)
    }

    /** TC-UT-08：撤销后发生新编辑，重做栈被清空 */
    @Test
    fun tcUt08_newEditAfterUndo_clearsRedo() {
        val vm = EditorViewModel("a")
        vm.onContentChange(TextFieldValue("ab"))
        vm.undo()
        assertTrue(vm.canRedo)

        vm.onContentChange(TextFieldValue("ac"))
        assertFalse(vm.canRedo)
        assertEquals("ac", vm.content.text)
    }

    /** TC-UT-09：保存先写缓存临时文件校验，再一次写回（"wt"）；内容为 UTF-8；成功后 dirty=false 且临时文件删除 */
    @Test
    fun tcUt09_save_tempVerifyThenWtWriteback_utf8() = runTest(mainDispatcher) {
        val writer = FakeDocumentWriter()
        val vm = EditorViewModel("原文", documentWriter = writer, cacheDir = tmpFolder.root)
        vm.onContentChange(TextFieldValue("原文 + 新增内容😀"))
        assertTrue(vm.dirty)

        vm.save()
        advanceUntilIdle()

        val expected = "原文 + 新增内容😀".toByteArray(Charsets.UTF_8)
        assertEquals("首选 \"wt\" 截断模式写回", listOf("wt"), writer.modes)
        assertArrayEquals("写回内容应为 UTF-8 字节", expected, writer.writtenBytes)
        assertFalse("保存成功后 dirty 应归 false", vm.dirty)
        assertTrue(vm.saveResult is SaveResult.Success)
        assertTrue("保存成功后临时文件应删除", tmpFolder.root.listFiles().isNullOrEmpty())

        vm.undo() // 基线已更新为新文本，撤销回旧文本应重新置脏
        assertTrue(vm.dirty)
    }

    /** TC-UT-09：非 dirty 时保存为空操作，不触发任何写入 */
    @Test
    fun tcUt09_save_notDirty_noop() = runTest(mainDispatcher) {
        val writer = FakeDocumentWriter()
        val vm = EditorViewModel("原文", documentWriter = writer, cacheDir = tmpFolder.root)

        vm.save()
        advanceUntilIdle()

        assertTrue(writer.modes.isEmpty())
        assertNull(vm.saveResult)
    }

    /** TC-UT-09a：Provider 不支持 "wt"（抛异常）时回退 "w" 重写成功（S1：Drive 两模式均可，回退为其他 Provider 兜底） */
    @Test
    fun tcUt09a_wtUnsupported_fallbackToW() = runTest(mainDispatcher) {
        val writer = FakeDocumentWriter(errorOn = { mode ->
            if (mode == "wt") FileNotFoundException("mode wt unsupported") else null
        })
        val vm = EditorViewModel("a", documentWriter = writer, cacheDir = tmpFolder.root)
        vm.onContentChange(TextFieldValue("ab"))

        vm.save()
        advanceUntilIdle()

        assertEquals("应先试 \"wt\" 再回退 \"w\"", listOf("wt", "w"), writer.modes)
        assertArrayEquals("ab".toByteArray(Charsets.UTF_8), writer.writtenBytes)
        assertFalse(vm.dirty)
        assertTrue(vm.saveResult is SaveResult.Success)
    }

    /** TC-UT-09：写回仍失败 → 临时文件保留（内容不丢）、dirty 保持、提示另存（设计§8 风险：先清后写的云盘 Provider） */
    @Test
    fun tcUt09_saveFails_keepsTempAndDirty_promptsSaveAs() = runTest(mainDispatcher) {
        val writer = FakeDocumentWriter(errorOn = { FileNotFoundException("io error mode=$it") })
        val vm = EditorViewModel("a", documentWriter = writer, cacheDir = tmpFolder.root)
        vm.onContentChange(TextFieldValue("ab 重要内容"))

        vm.save()
        advanceUntilIdle()

        assertTrue("写回失败 dirty 应保持", vm.dirty)
        assertTrue(vm.saveResult is SaveResult.FailedSaveAs)
        val temps = tmpFolder.root.listFiles().orEmpty()
        assertTrue("写回失败时临时文件应保留", temps.isNotEmpty())
        assertArrayEquals("临时文件应保住完整内容", "ab 重要内容".toByteArray(Charsets.UTF_8), temps[0].readBytes())
    }

    /** TC-UT-09（S1 探针结论）：Provider 进程被 OEM 杀死抛 "No content provider"，不按权限失效处理，单独提示 */
    @Test
    fun tcUt09_providerKilled_specificResult() = runTest(mainDispatcher) {
        val writer = FakeDocumentWriter(errorOn = {
            FileNotFoundException("No content provider: content://com.google.android.apps.docs.storage/x")
        })
        val vm = EditorViewModel("a", documentWriter = writer, cacheDir = tmpFolder.root)
        vm.onContentChange(TextFieldValue("ab"))

        vm.save()
        advanceUntilIdle()

        assertTrue(vm.saveResult is SaveResult.ProviderKilled)
        assertTrue("保存未成功 dirty 应保持", vm.dirty)
    }
}
