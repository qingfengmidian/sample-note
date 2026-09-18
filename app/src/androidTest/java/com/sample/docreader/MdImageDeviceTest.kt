package com.sample.docreader

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sample.docreader.ui.reader.READER_CONTENT_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11b 设备端验收（F2.14 epub 插图/封面/占位，真机/模拟器 `connectedDebugAndroidTest`）：
 * 打开含图 epub（走真实 ReaderViewModel + EpubCache 解压路径），以 Compose UI **语义断言**
 * 验证：位图 contentDescription=alt 且实际渲染显示、svg 处占位、封面图置顶、损坏/缺失图占位、
 * 正文文本仍在（TC-F2-17/17c/17d，协同规范 §验收）。
 *
 * 语义断言与 M11a 同坑注意：图片/占位若与正文同名，一律经 contentDescription 区分（见各用例注释）。
 */
@RunWith(AndroidJUnit4::class)
class MdImageDeviceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val uiContext: Context get() = ApplicationProvider.getApplicationContext()

    /** 首页顶栏「测试样本」→ 夹具列表逐页上滑直到目标 epub 行可见并点击 */
    private fun openEpubFixture(name: String) {
        composeTestRule.onNodeWithText(uiContext.getString(R.string.home_fixtures_cd)).performClick()
        composeTestRule.waitForIdle()
        // M12 真机复盘（2026-09-12）：swipeUp 的 fling 会过冲跳过目标条目且只上滑不回退，
        // 尾部夹具（sample_*/styled）间歇性「未找到」；改 performScrollToNode 语义确定性滚动
        // （与 M11b 对正文滚动的同款处理）。
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        composeTestRule.onNodeWithText(name).performClick()
    }

    private fun waitForContentDescription(desc: String) {
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 让正文区滚动到带指定 contentDescription 的节点可见（performScrollToNode 走 LazyColumn 的
     * ScrollToIndex 语义动作，确定性滚动——触摸 swipe 的 fling 距离不稳定，长文档下 25 次上滑可能
     * 一直没到目标，见 sampleImages 首次实跑失败）。 */
    private fun scrollUntilContentDescription(desc: String) {
        composeTestRule.onNodeWithTag(READER_CONTENT_TAG)
            .performScrollToNode(hasContentDescription(desc))
        composeTestRule.onNodeWithContentDescription(desc).assertIsDisplayed()
    }

    /** 让正文区滚动到带指定文本的节点可见。 */
    private fun scrollUntilText(text: String) {
        composeTestRule.onNodeWithTag(READER_CONTENT_TAG).performScrollToNode(hasText(text))
        composeTestRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 20_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * TC-F2-17 设备端：sample_images.epub——含 alt 位图（PNG/JPG）节点 contentDescription=alt 且实际显示；
     * svg 处出现占位（默认「图片无法显示」占位语义）；正文文本完整。
     */
    @Test
    fun sampleImages_bitmapsDisplayed_svgPlaceholder() {
        openEpubFixture("sample_images.epub")
        waitForText("第一段：前言文字。")

        // PNG 位图：contentDescription = alt（"示例PNG插图"）且渲染显示
        waitForContentDescription("示例PNG插图")
        scrollUntilContentDescription("示例PNG插图")
        composeTestRule.onNodeWithContentDescription("示例PNG插图").assertIsDisplayed()

        // JPG 位图（第二段行内）：同上按 alt 断言
        scrollUntilContentDescription("示例JPG插图")
        composeTestRule.onNodeWithContentDescription("示例JPG插图").assertIsDisplayed()

        // svg 一律不直接渲染 → 占位（无 alt，默认占位文案 + 语义），不崩溃
        scrollUntilContentDescription(uiContext.getString(R.string.reader_image_unavailable))
        composeTestRule.onNodeWithContentDescription(uiContext.getString(R.string.reader_image_unavailable)).assertIsDisplayed()

        // L3：文件名含空格、src 用 %20 编码的位图 → URLDecoder 查找后正常解码显示（contentDescription=alt）
        scrollUntilContentDescription("空格文件名图")
        composeTestRule.onNodeWithContentDescription("空格文件名图").assertIsDisplayed()

        // 正文完整：末尾段滚动到可视后仍在（含图段落文字不受图片影响）
        scrollUntilText("尾段文字。")
        composeTestRule.onNodeWithText("尾段文字。").assertIsDisplayed()
    }

    /**
     * TC-F2-17c 设备端：sample_cover.epub——OPF meta name=cover 封面图置顶渲染于正文前
     * （封面无 alt，语义给「封面」；正文段落仍在）。
     */
    @Test
    fun sampleCover_coverShownAtTop() {
        openEpubFixture("sample_cover.epub")
        waitForContentDescription(uiContext.getString(R.string.reader_image_cover))
        scrollUntilContentDescription(uiContext.getString(R.string.reader_image_cover))
        val cover = composeTestRule.onNodeWithContentDescription(uiContext.getString(R.string.reader_image_cover))
        cover.assertIsDisplayed()
        // 封面位于正文之上（首段文字可见，未被吞掉）
        waitForText("封面之后的第一段文字。")
    }

    /**
     * TC-F2-17d 设备端：broken_image.epub——缺失条目（alt 非空）与截断位图（无 alt）各出占位，
     * 正文文本仍在、不崩溃。
     */
    @Test
    fun brokenImage_bothPlaceholdersAndTextIntact() {
        openEpubFixture("broken_image.epub")
        waitForText("正文仍在的首段。")

        // 缺失条目图（带 alt）：占位语义取 alt
        waitForContentDescription("失联配图")
        scrollUntilContentDescription("失联配图")
        composeTestRule.onNodeWithContentDescription("失联配图").assertIsDisplayed()

        // 截断位图（无 alt）：默认占位文案语义
        scrollUntilContentDescription(uiContext.getString(R.string.reader_image_unavailable))
        composeTestRule.onNodeWithContentDescription(uiContext.getString(R.string.reader_image_unavailable)).assertIsDisplayed()

        // 正文完整可读：末尾段滚动到可视后仍在
        scrollUntilText("正文结尾段。")
        composeTestRule.onNodeWithText("正文结尾段。").assertIsDisplayed()
    }
}
