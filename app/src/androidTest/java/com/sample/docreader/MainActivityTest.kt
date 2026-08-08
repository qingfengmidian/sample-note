package com.sample.docreader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M0 示例 UI 测试：空壳首页可渲染。 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_isDisplayed() {
        composeTestRule.onNodeWithText("简易文档").assertIsDisplayed()
    }

    @Test
    fun navigate_to_settings() {
        composeTestRule.onNodeWithText("设置").assertIsDisplayed()
    }
}
