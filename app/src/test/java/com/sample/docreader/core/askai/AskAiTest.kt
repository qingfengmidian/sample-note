package com.sample.docreader.core.askai

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * TC-UT-19（M18，F4.10，设计 REV18 §3.10）AskAi 核心逻辑（Robolectric，红测试先行）。
 *
 * 契约：前缀三态拼接（null 用资源默认 / 空串原文直发 / 非空为前缀+原文）、发送 Intent 字段
 * （ACTION_SEND + text/plain + setPackage + EXTRA_TEXT）、提示四门控、节选截断、候选枚举
 * （按包名去重 + 按 label 排序 + 排除本应用）。
 */
@RunWith(RobolectricTestRunner::class)
class AskAiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** ① 前缀三态：null → 资源默认前缀 + 原文；空串 → 原文直发；非空 → 前缀 + 原文 */
    @Test
    fun tcUt19_composePayload_threePrefixStates() {
        val text = "正文内容"
        assertEquals("请解释：正文内容", AskAi.composePayload(null, "请解释：", text))
        assertEquals("正文内容", AskAi.composePayload("", "请解释：", text))
        assertEquals("Summarize: 正文内容", AskAi.composePayload("Summarize: ", "请解释：", text))
    }

    /** ② 发送 Intent：action/type/package/EXTRA_TEXT 齐备（不弹分享面板 → 必须 setPackage） */
    @Test
    fun tcUt19_buildSendIntent_hasActionTypePackageAndPayload() {
        val intent = AskAi.buildSendIntent("com.example.target", "请解释：正文")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("com.example.target", intent.`package`)
        assertEquals("请解释：正文", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    /** ③ 提示门控：关闭 / 未选目标 / 文本空白 / 与上次相同 → 均不提示；四者齐备才提示 */
    @Test
    fun tcUt19_shouldOffer_gates() {
        assertTrue(AskAi.shouldOffer(true, "com.example.target", "文本", null))
        assertFalse("关闭时不提示", AskAi.shouldOffer(false, "com.example.target", "文本", null))
        assertFalse("未选目标不提示", AskAi.shouldOffer(true, null, "文本", null))
        assertFalse("空目标不提示", AskAi.shouldOffer(true, "", "文本", null))
        assertFalse("空白文本不提示", AskAi.shouldOffer(true, "com.example.target", "   ", null))
        assertFalse("null 文本不提示", AskAi.shouldOffer(true, "com.example.target", null, null))
        assertFalse(
            "与上次提示相同不重复提示",
            AskAi.shouldOffer(true, "com.example.target", "文本", "文本")
        )
    }

    /** ④ 节选：不超长原样；超长截断并加省略号 */
    @Test
    fun tcUt19_excerpt_truncates() {
        assertEquals("短文本", AskAi.excerpt("短文本"))
        val long = "x".repeat(30)
        val out = AskAi.excerpt(long, max = 20)
        assertTrue("应截断为 20 字符 + 省略号", out.startsWith("x".repeat(20)))
        assertEquals(21, out.length)
    }

    /**
     * ⑤ 候选枚举：注入三个接收方（同包两个 Activity + 另一包 + 本应用自身）→
     * 按包名去重、排除自身、按 label 排序。
     */
    @Test
    fun tcUt19_candidates_dedupesExcludesSelfAndSorts() {
        val pm = shadowOf(context.packageManager)
        fun add(pkg: String, activity: String, label: String) {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            val info = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = activity
                    nonLocalizedLabel = label
                }
            }
            pm.addResolveInfoForIntent(intent, info)
        }
        add("com.zeta.app", "com.zeta.app.Main", "Zeta")
        add("com.alpha.app", "com.alpha.app.A", "Alpha")
        add("com.alpha.app", "com.alpha.app.B", "Alpha") // 同包第二个 Activity → 去重
        add(context.packageName, "com.sample.docreader.X", "Self")

        val result = AskAi.candidates(context)
        assertEquals(
            "同包去重且排除自身",
            listOf("com.alpha.app", "com.zeta.app"),
            result.map { it.packageName }
        )
        assertEquals(listOf("Alpha", "Zeta"), result.map { it.label })
    }
}
