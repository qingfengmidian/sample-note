package com.sample.docreader.ui.editor

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import com.sample.docreader.core.model.ReadingSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * TC-UI-04：编辑退出提示框（dirty 状态下返回弹三选：保存/放弃/取消）。
 *
 * Robolectric 下 AlertDialog 窗口内容不进语义树，故对话框内容主体 [EditorExitDialogContent] 直接驱动
 * （与 ReaderChapterListTest 的 ChapterListContent 同法）；BackHandler 拦截触发由 EditorScreen 负责，
 * 真机/手工 TC-F3-03 覆盖"dirty 返回弹框、非 dirty 不拦截"的完整链路。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorExitDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** TC-UI-04：保存项本次渲染为 disabled（M7b 接入）并带 contentDescription */
    @Test
    fun tcUi04_saveDisabled_withContentDescription() {
        composeRule.setContent {
            EditorExitDialogContent(onSave = {}, onDiscard = {}, onCancel = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("保存").assertExists().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("放弃").assertExists().assertIsEnabled()
        composeRule.onNodeWithContentDescription("取消").assertExists().assertIsEnabled()
    }

    /** TC-UI-04：取消 → 留在编辑页（onDiscard/onSave 不触发，内容不变） */
    @Test
    fun tcUi04_cancel_staysInEditor_keepsContent() {
        var cancelCalled = false
        var discardCalled = false
        var saveCalled = false
        composeRule.setContent {
            EditorExitDialogContent(
                onSave = { saveCalled = true },
                onDiscard = { discardCalled = true },
                onCancel = { cancelCalled = true }
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("取消").performClick()
        composeRule.waitForIdle()

        assertTrue(cancelCalled)
        assertFalse(discardCalled)
        assertFalse(saveCalled)
    }

    /** TC-UI-04：放弃 → 退出编辑页且不写文件（onDiscard 触发、onSave 不触发） */
    @Test
    fun tcUi04_discard_exitsWithoutSaving() {
        var discardCalled = false
        var saveCalled = false
        composeRule.setContent {
            EditorExitDialogContent(
                onSave = { saveCalled = true },
                onDiscard = { discardCalled = true },
                onCancel = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("放弃").performClick()
        composeRule.waitForIdle()

        assertTrue(discardCalled)
        assertFalse(saveCalled)
    }

    /** TC-UI-04（补充）：顶部栏撤销/重做/保存按钮 enable 状态与 canUndo/canRedo 一致，保存恒禁用 */
    @Test
    fun editorContent_undoRedoSave_buttonsReflectState() {
        composeRule.setContent {
            EditorContent(
                content = TextFieldValue("hello"),
                canUndo = true,
                canRedo = false,
                settings = ReadingSettings(),
                displayName = "a.txt",
                loading = false,
                error = null,
                showExitDialog = false,
                onContentChange = {},
                onUndo = {},
                onRedo = {},
                onSave = {},
                onDiscard = {},
                onCancelExit = {},
                onBack = {}
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("撤销").assertIsEnabled()
        composeRule.onNodeWithContentDescription("重做").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("保存").assertIsNotEnabled()
    }
}
