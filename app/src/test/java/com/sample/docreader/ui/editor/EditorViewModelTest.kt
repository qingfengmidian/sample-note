package com.sample.docreader.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TC-UT-08：EditorViewModel 撤销/重做（M7a 先行失败测试，TDD 红 → OpenCode 实现转绿）。
 *
 * 被测 API 约定（M7a 范围，不含保存/草稿）：
 * - `EditorViewModel(initialText: String)`：初始文本即"已保存"基线
 * - `content: TextFieldValue`：当前编辑内容（含光标/选区）
 * - `dirty: Boolean`：当前文本 ≠ 初始文本（撤销回到初始文本时归 false）
 * - `canUndo / canRedo: Boolean`
 * - `onContentChange(newValue: TextFieldValue)`：文本变化时入历史栈；仅光标/选区变化（text 相同）不入栈
 * - `undo() / redo()`：历史栈上限 100 步，超出丢弃最早记录（设计§3.4）
 */
@RunWith(RobolectricTestRunner::class)
class EditorViewModelTest {

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
}
