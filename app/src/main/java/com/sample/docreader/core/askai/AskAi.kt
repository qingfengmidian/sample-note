package com.sample.docreader.core.askai

import android.app.Activity
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
 * 红线：不联网、不采集、不新增权限/依赖；文本只在用户点按后交给第三方应用，本应用不内置任何问答能力。
 * 实现见 `docs/collab/briefs/m18-ask-ai.md` §2.2。
 */
object AskAi {

    /**
     * 内置预设目标（设计 REV18 §3.10）：`<queries>` 声明后即使应用未注册 `ACTION_SEND`
     * 也能被 `getApplicationInfo` 感知。真机走查发现 DeepSeek 等应用未出现在动态枚举中，
     * 故以「预设中已安装者 ∪ 动态枚举结果」补齐。
     */
    val PRESET_TARGETS: List<AskAiTarget> = listOf(
        AskAiTarget("com.deepseek.chat", "DeepSeek"),
        AskAiTarget("com.larus.nova", "豆包"),
    )

    /**
     * 可接收 text/plain 的候选应用：预设中已安装者（不要求注册 `ACTION_SEND`）∪ 动态枚举结果，
     * 按包名去重（同包保留一条，label 优先 `loadLabel`、失败回落预设展示名）、按 label 排序、
     * 排除本应用自身。
     */
    fun candidates(context: Context): List<AskAiTarget> {
        val pm = context.packageManager
        val seen = LinkedHashSet<String>()
        val targets = ArrayList<AskAiTarget>()

        // 预设：仅取已安装者（getApplicationInfo 包 try/catch 判定）
        PRESET_TARGETS.forEach { preset ->
            val pkg = preset.packageName
            if (pkg == context.packageName) return@forEach
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@forEach
            if (!seen.add(pkg)) return@forEach
            val label = runCatching { appInfo.loadLabel(pm).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: preset.label
            targets.add(AskAiTarget(pkg, label))
        }

        // 动态枚举可接收 text/plain 的已安装应用
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        resolved.forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            // 排除本应用（防御性：本应用未注册 ACTION_SEND）+ 同包多 Activity 取首个
            if (pkg == context.packageName || !seen.add(pkg)) return@forEach
            val label = runCatching { info.loadLabel(pm).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            targets.add(AskAiTarget(pkg, label))
        }
        return targets.sortedBy { it.label }
    }

    /** 前缀拼接：[prefix] 为 null → 资源默认前缀 + 原文；空串 → 原文直发；非空 → 前缀 + 原文 */
    fun composePayload(prefix: String?, defaultPrefix: String, text: String): String = when {
        prefix == null -> defaultPrefix + text
        prefix.isEmpty() -> text
        else -> prefix + text
    }

    /** 是否给出提示：开启 + 已选目标 + 文本非空白 + 与上次提示的文本不同 */
    fun shouldOffer(
        enabled: Boolean,
        targetPackage: String?,
        text: String?,
        lastOffered: String?
    ): Boolean = enabled &&
        !targetPackage.isNullOrBlank() &&
        !text.isNullOrBlank() &&
        text != lastOffered

    /** 提示文案节选：长度 ≤ [max] 原样，否则前 [max] 字符 + 省略号 */
    fun excerpt(text: String, max: Int = 20): String =
        if (text.length <= max) text else text.take(max) + "…"

    /** 构造发送 Intent：ACTION_SEND + text/plain + setPackage(target) + EXTRA_TEXT，不弹系统分享面板 */
    fun buildSendIntent(targetPackage: String, payload: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(targetPackage)
            putExtra(Intent.EXTRA_TEXT, payload)
        }

    /** 发送；目标缺失/无 Activity（未安装、被禁用）→ false（不崩），由 UI 提示重新选择 */
    fun send(context: Context, targetPackage: String, payload: String): Boolean = runCatching {
        val intent = buildSendIntent(targetPackage, payload)
        // 非 Activity Context（如 Application）启动 Activity 需 NEW_TASK，否则抛 AndroidRuntimeException
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
}
