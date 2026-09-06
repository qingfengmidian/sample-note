package com.sample.docreader.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sample.docreader.core.model.DocFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * U6（M10）：首页「测试样本」入口仅 debug 构建显示（fixtures 路由亦按 BuildConfig.DEBUG 注册）。
 * Robolectric 单测跑 debug 变体 → 断言入口存在即可；release 变体入口不存在的反向断言由
 * assembleRelease 编译门控兜底（release 无入口则不会点到死路由，见 MainActivity）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenDebugEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun debugBuild_homeScreen_hasFixturesEntry() {
        composeRule.setContent {
            HomeScreen(
                onOpenSettings = {},
                onOpenFixtures = {},
                onOpenReader = { _: String, _: String, _: DocFormat -> },
                onOpenEditor = { _: String, _: String, _: DocFormat -> }
            )
        }
        composeRule.waitForIdle()

        // U6：debug 变体下测试样本入口存在（release 变体不可测，见类 KDoc）
        composeRule.onNodeWithText("测试样本").assertExists().assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertExists()
    }
}
