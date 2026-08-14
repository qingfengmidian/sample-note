package com.sample.docreader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ReadingSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** 阅读界面空态/错误态显示（TC-F2-24、ParseException 差异化文案） */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(state: ReaderUiState) {
        var settings by mutableStateOf(state.settings)
        composeRule.setContent {
            ReaderContent(
                state = state.copy(settings = settings),
                onBack = {},
                onToggleWrap = {},
                onFontSizeStep = { _ -> },
                onCycleLineHeight = {},
                onCycleFont = {},
                onCycleTheme = {}
            )
        }
        composeRule.waitForIdle()
    }

    /** TC-F2-24：空文件显示空态提示，不崩溃 */
    @Test
    fun emptyFile_showsEmptyHint() {
        val document = Document("asset:///fixtures/empty.txt", "empty.txt", DocFormat.TXT,
            listOf(Chapter(null, listOf(""))))
        setContent(ReaderUiState(loading = false, document = document))
        composeRule.onNodeWithText("文件为空").assertIsDisplayed()
    }

    @Test
    fun errorState_showsDifferentialMessage() {
        setContent(ReaderUiState(loading = false, error = "epub 缺少 OPF 描述文件，无法解析"))
        composeRule.onNodeWithText("epub 缺少 OPF 描述文件，无法解析").assertIsDisplayed()
    }
}
