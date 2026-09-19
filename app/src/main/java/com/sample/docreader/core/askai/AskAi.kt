package com.sample.docreader.core.askai

import android.content.Context
import android.content.Intent

/**
 * F4.10/M18「复制后问 AI」目标应用（设计 REV18 §3.10）。
 * [label] 为运行时解析的应用名（应用改名/切语言不影响持久化的 [packageName]）。
 */
data class AskAiTarget(val packageName: String, val label: String)

/**
 * 「复制后问 AI」核心逻辑（设计 REV18 §3.10：剪贴板文本 → 前缀拼接 → 经 `ACTION_SEND` 交给目标应用）。
 *
 * **本文件为 M18 红测试先行的接口桩**：仅签名与语义注释，实现由 OpenCode 按
 * `docs/collab/briefs/m18-ask-ai.md` §2.2 完成（红线：不联网、不采集、不新增权限/依赖）。
 */
object AskAi {

    /** 动态枚举可接收 text/plain 的已安装应用：按包名去重、按 label 排序、排除本应用自身 */
    fun candidates(context: Context): List<AskAiTarget> = TODO("M18 实现：见简报 §2.2")

    /** 前缀拼接：[prefix] 为 null → 资源默认前缀 + 原文；空串 → 原文直发；非空 → 前缀 + 原文 */
    fun composePayload(prefix: String?, defaultPrefix: String, text: String): String =
        TODO("M18 实现：见简报 §2.2")

    /** 是否给出提示：开启 + 已选目标 + 文本非空白 + 与上次提示的文本不同 */
    fun shouldOffer(
        enabled: Boolean,
        targetPackage: String?,
        text: String?,
        lastOffered: String?
    ): Boolean = TODO("M18 实现：见简报 §2.2")

    /** 提示文案节选：长度 ≤ [max] 原样，否则前 [max] 字符 + 省略号 */
    fun excerpt(text: String, max: Int = 20): String = TODO("M18 实现：见简报 §2.2")

    /** 构造发送 Intent：ACTION_SEND + text/plain + setPackage(target) + EXTRA_TEXT，不弹系统分享面板 */
    fun buildSendIntent(targetPackage: String, payload: String): Intent =
        TODO("M18 实现：见简报 §2.2")

    /** 发送；目标缺失/无 Activity（未安装、被禁用）→ false（不崩），由 UI 提示重新选择 */
    fun send(context: Context, targetPackage: String, payload: String): Boolean =
        TODO("M18 实现：见简报 §2.2")
}
