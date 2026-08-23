package com.sample.docreader.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * TC-UI-05（进程重建，M7c 由 OpenCode 编写）：
 * Robolectric 下无法真正重建 Activity 级 SavedState（createSavedStateHandle 依赖
 * SavedStateRegistry，进程级序列化路径在单测环境不可用），退化为 VM 级重建：
 * 新 EditorViewModel + 复用 SavedStateHandle + 草稿文件，等价验证"正文从草稿恢复"。
 * 本类额外钉两点 EditorDraftTest 未覆盖的契约：
 * 1. SavedStateHandle 只存轻量状态（uri + 光标），不存正文（正文只在草稿文件）；
 * 2. SavedStateHandle 中的 uri 与载入 uri 不一致时不采用其中的光标（防串文件）。
 * 真机进程级重建由 TC-F4-06 手工验收兜底。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorRecreateTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val uri = "content://com.example.provider/docs/note.txt"
    private val originalText = "原始内容"

    private class FakeOpener(text: String) : FileOpener {
        private val bytes = text.toByteArray(Charsets.UTF_8)
        override fun open(uri: String): InputStream = ByteArrayInputStream(bytes)
    }

    private fun newVm(store: DraftStore, handle: SavedStateHandle) = EditorViewModel(
        fileOpener = FakeOpener(originalText),
        cacheDir = tmpFolder.newFolder("cache"),
        draftStore = store,
        savedStateHandle = handle
    )

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

    /** TC-UI-05：SavedStateHandle 只存 uri + 光标轻量状态，不存正文 */
    @Test
    fun tcUi05_savedStateHandle_onlyLightState() = runTest(mainDispatcher) {
        val store = DraftStore(tmpFolder.newFolder("files"))
        val handle = SavedStateHandle()
        val vm = newVm(store, handle)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        val edited = "原始内容 + 进程死亡前的修改"
        vm.onContentChange(TextFieldValue(edited, selection = TextRange(3)))
        advanceTimeBy(1200)
        advanceUntilIdle()

        // 轻量状态契约：仅 uri + 光标起止三个键，任何值都不含正文
        val keys = handle.keys()
        assertTrue(keys.contains("draft_uri"))
        assertTrue(keys.contains("draft_cursor_start"))
        assertTrue(keys.contains("draft_cursor_end"))
        assertEquals(uri, handle.get<String>("draft_uri"))
        assertEquals(3, handle.get<Int>("draft_cursor_start"))
        keys.forEach { key ->
            val value = handle.get<Any>(key)
            assertFalse("SavedStateHandle 不应存正文（键 $key）", value is String && value.contains(edited))
        }
        // 正文只在草稿文件
        assertEquals(edited, store.read(uri))
    }

    /** TC-UI-05：SavedStateHandle 记录的 uri 与载入 uri 不一致时，光标归零不串文件 */
    @Test
    fun tcUi05_handleUriMismatch_cursorIgnored() = runTest(mainDispatcher) {
        val store = DraftStore(tmpFolder.newFolder("files"))
        val handle = SavedStateHandle()
        // 模拟另一个文件的遗留轻量状态
        handle["draft_uri"] = "content://com.example.provider/docs/other.txt"
        handle["draft_cursor_start"] = 99
        handle["draft_cursor_end"] = 99
        store.write(uri, "草稿正文")

        val vm = newVm(store, handle)
        vm.load(uri, DocFormat.TXT, "note.txt")
        awaitLoaded(vm)

        assertEquals("草稿正文", vm.content.text)
        assertEquals(TextRange.Zero, vm.content.selection)
        assertTrue(vm.dirty)
    }
}
