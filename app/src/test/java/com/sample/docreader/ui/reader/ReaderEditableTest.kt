package com.sample.docreader.ui.reader

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * TC-F3-09（UI 侧，M7b）：超大文件（>10MB）编辑入口置灰并附说明，可只读浏览。
 * ReaderContent 直驱测试，参照 ReaderMarkdownPreviewTest/ReaderChapterListTest 模式。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderEditableTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val txtDocument = Document(
        "asset:///fixtures/sample_utf8.txt", "sample_utf8.txt", DocFormat.TXT,
        listOf(Chapter("sample_utf8.txt", listOf("段落一", "段落二")))
    )

    private fun setContent(editable: Boolean) {
        composeRule.setContent {
            ReaderContent(
                state = ReaderUiState(loading = false, document = txtDocument, editable = editable),
                onBack = {},
                onFontSizeStep = { _ -> }
            )
        }
        composeRule.waitForIdle()
    }

    /** TC-F3-09：editable=false 时编辑菜单项置灰并附"超过 10MB 仅可只读"说明 */
    @Test
    fun hugeFile_editEntryDisabled_withHint() {
        setContent(editable = false)

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText(EDIT_HINT_TOO_LARGE).assertExists().assertIsNotEnabled()
    }

    /** TC-F3-09：正常小文件编辑菜单项可点，且无置灰说明 */
    @Test
    fun normalFile_editEntryEnabled() {
        setContent(editable = true)

        composeRule.onNodeWithContentDescription("菜单").performClick()
        composeRule.onNodeWithText("编辑").assertExists().assertIsEnabled()
        composeRule.onNodeWithText(EDIT_HINT_TOO_LARGE).assertDoesNotExist()
    }
}
