package com.sample.docreader.ui

import android.app.Activity
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sample.docreader.R
import com.sample.docreader.core.askai.AskAi
import com.sample.docreader.core.model.AppLanguage
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import com.sample.docreader.core.settings.LocaleSwitcher
import com.sample.docreader.core.settings.SettingsRepository
import com.sample.docreader.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 设置页（M4）：自定义字体导入（SAF 选 ttf/otf → 复制到 files/fonts/ → Typeface 预验证 → 存路径）。
 * 全局阅读默认项（M8a，F4.3/TC-F4-03）：默认字号/行距/换行/字体/主题，修改即时经 SettingsRepository
 * 持久化；M17 起行距/换行/字体/主题改为选项列表交互（点击行唤单选对话框，点选即生效，TC-UI-19），
 * 阅读页订阅同一份 settings flow，新打开的文件自然应用新默认值。
 * M16（F4.9）：顶部「通用」小节的语言行（跟随系统/中文/英文）写独立 DataStore 键；
 * M17 起改为选项对话框 + 确认重启（决策 D-2026-09-19-02）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = ReadingSettings())
    // F4.9/M16：语言覆盖与阅读设置键级隔离，故单独订阅（缺失/未知标签 = 跟随系统）
    val language by repository.appLanguage.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
    // F4.10/M18：问 AI 三键与阅读设置键级隔离，同样单独订阅
    val askAiEnabled by repository.askAiEnabled.collectAsStateWithLifecycle(initialValue = true)
    val askAiTarget by repository.askAiTargetPackage.collectAsStateWithLifecycle(initialValue = null)
    val askAiPrefix by repository.askAiPrefix.collectAsStateWithLifecycle(initialValue = null)
    // 资源默认前缀：仅用于「从未设置过」（null）时输入框的初值展示
    val askAiDefaultPrefix = stringResource(R.string.settings_ask_ai_prefix_default)
    // 候选应用动态枚举（设计 §3.10-2：仅设置页打开与发送失败回退时调用，量小，不做缓存）
    val askAiCandidates = remember { AskAi.candidates(context) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // M17（F4.9/决策 D-2026-09-19-02）：语言选项选定但尚未确认的档位；非空即弹确认重启框
    var pendingLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    // M17：当前打开的选项对话框（单一状态源）；对话框打开期间背景行值不进入语义树（模态隔离，见 OptionPickerRow）
    var openPicker by remember { mutableStateOf<SettingsPicker?>(null) }
    val showRowValues = openPicker == null

    fun importFont(uri: android.net.Uri) {
        scope.launch {
            message = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    importCustomFont(context, uri)
                }.getOrElse { FontImportResult(failure = context.getString(R.string.reader_font_import_failed)) }
            }
            if (result.path != null) {
                // G2（M10）：重新导入成功后删除上一版旧字体文件（孤儿文件清理）；切换离开 CUSTOM 不删（允许切回）
                val oldPath = settings.customFontPath
                repository.save(settings.copy(font = ReaderFont.CUSTOM, customFontPath = result.path))
                if (oldPath != null && oldPath != result.path) File(oldPath).delete()
                message = context.getString(R.string.reader_font_imported)
            } else {
                message = result.failure
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFont(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back_cd)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // F4.9/M17：应用内语言（跟随系统/中文/English）选项对话框；选定不同档 → 确认重启框，确认后先落库再重建
            Text(stringResource(R.string.settings_general_section), style = MaterialTheme.typography.titleSmall)
            OptionPickerRow(
                title = stringResource(R.string.settings_language),
                value = languageLabel(language),
                options = AppLanguage.entries,
                optionLabel = { languageLabel(it) },
                isSelected = { it == language },
                onSelect = { selected -> if (selected != language) pendingLanguage = selected },
                expanded = openPicker == SettingsPicker.LANGUAGE,
                onExpandedChange = { open -> openPicker = if (open) SettingsPicker.LANGUAGE else null },
                showValue = showRowValues
            )
            Text(stringResource(R.string.settings_font_scheme), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_current, fontLabel(settings.font)),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = {
                    launcher.launch(
                        arrayOf(
                            "application/x-font-ttf",
                            "application/x-font-otf",
                            "font/ttf",
                            "font/otf",
                            "application/octet-stream"
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Text(stringResource(R.string.reader_import_font))
            }
            if (settings.font == ReaderFont.CUSTOM) {
                Text(
                    settings.customFontPath?.let {
                        stringResource(R.string.settings_custom_font_enabled_named, File(it).name)
                    } ?: stringResource(R.string.settings_custom_font_enabled),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            // 编辑器设置（F3.6/TC-F3-06/07）：自动保存开关，默认关，开启后停顿 3s 自动写回。
            // 置于阅读默认值之前：保持 M7c 起即有的屏幕内位置，避免 Robolectric 默认可视区外 performClick 失效
            Text(stringResource(R.string.settings_editor_section), style = MaterialTheme.typography.titleSmall)
            AutoSaveRow(
                checked = settings.autoSave,
                onCheckedChange = { enabled ->
                    scope.launch { repository.save(settings.copy(autoSave = enabled)) }
                }
            )
            // 全局阅读默认项（F4.3/TC-F4-03，M8a）：选项列表交互（M17/TC-UI-19），点选即持久化
            Text(stringResource(R.string.settings_reading_defaults), style = MaterialTheme.typography.titleSmall)
            FontSizeSettingRow(
                fontSizeSp = settings.fontSizeSp,
                onStep = { delta ->
                    scope.launch { repository.save(settings.copy(fontSizeSp = stepFontSize(settings.fontSizeSp, delta))) }
                }
            )
            OptionPickerRow(
                title = stringResource(R.string.settings_default_line_spacing),
                value = "${settings.lineHeightPercent}%",
                options = ReaderViewModel.LINE_HEIGHT_STEPS,
                optionLabel = { "$it%" },
                isSelected = { it == settings.lineHeightPercent },
                onSelect = { percent -> scope.launch { repository.save(settings.copy(lineHeightPercent = percent)) } },
                expanded = openPicker == SettingsPicker.LINE_HEIGHT,
                onExpandedChange = { open -> openPicker = if (open) SettingsPicker.LINE_HEIGHT else null },
                showValue = showRowValues
            )
            OptionPickerRow(
                title = stringResource(R.string.settings_default_wrap_mode),
                value = if (settings.wrapMode == WrapMode.WRAP) stringResource(R.string.reader_auto_wrap)
                else stringResource(R.string.reader_no_wrap),
                options = listOf(WrapMode.WRAP, WrapMode.NO_WRAP),
                optionLabel = {
                    if (it == WrapMode.WRAP) stringResource(R.string.reader_auto_wrap)
                    else stringResource(R.string.reader_no_wrap)
                },
                isSelected = { it == settings.wrapMode },
                onSelect = { mode -> scope.launch { repository.save(settings.copy(wrapMode = mode)) } },
                expanded = openPicker == SettingsPicker.WRAP,
                onExpandedChange = { open -> openPicker = if (open) SettingsPicker.WRAP else null },
                showValue = showRowValues
            )
            OptionPickerRow(
                title = stringResource(R.string.settings_default_font),
                value = fontLabel(settings.font),
                // G2（M10）：customFontPath 非空时选项序列追加 CUSTOM（切离 CUSTOM 不删文件，允许切回）
                options = if (settings.customFontPath != null) {
                    listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS, ReaderFont.CUSTOM)
                } else {
                    listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS)
                },
                optionLabel = { fontLabel(it) },
                isSelected = { it == settings.font },
                onSelect = { font -> scope.launch { repository.save(settings.copy(font = font)) } },
                expanded = openPicker == SettingsPicker.FONT,
                onExpandedChange = { open -> openPicker = if (open) SettingsPicker.FONT else null },
                showValue = showRowValues
            )
            OptionPickerRow(
                title = stringResource(R.string.settings_default_theme),
                value = themeLabel(settings.theme),
                options = listOf(null, ReadingTheme.DEFAULT, ReadingTheme.EYE_CARE,
                    ReadingTheme.SOFT_LIGHT, ReadingTheme.NIGHT),
                optionLabel = { themeLabel(it) },
                isSelected = { it == settings.theme },
                onSelect = { theme -> scope.launch { repository.save(settings.copy(theme = theme)) } },
                expanded = openPicker == SettingsPicker.THEME,
                onExpandedChange = { open -> openPicker = if (open) SettingsPicker.THEME else null },
                showValue = showRowValues
            )
            // F4.10/M18（设计 REV18 §3.10-4）：问 AI 小节，位于「编辑器」之后。
            // 简报 §2.4 要求再置于「阅读默认值」之前，但那会把「默认字号」行挤出 Robolectric 默认可视区，
            // 使既有 SettingsGlobalTest 的无滚动 performClick 失效（该测试不可改，见其类注释与 L182 既有约定），
            // 故小节落在「阅读默认值」之后、列尾——仍在「编辑器」之后，且不回归既有用例（报告已注明取舍）。
            Text(stringResource(R.string.settings_ask_ai_section), style = MaterialTheme.typography.titleSmall)
            AskAiToggleRow(
                checked = askAiEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { repository.saveAskAiEnabled(enabled) }
                }
            )
            OptionPickerRow(
                title = stringResource(R.string.settings_ask_ai_target),
                value = askAiCandidates.firstOrNull { it.packageName == askAiTarget }?.label
                    ?: stringResource(R.string.settings_ask_ai_target_none),
                options = askAiCandidates,
                optionLabel = { it.label },
                isSelected = { it.packageName == askAiTarget },
                onSelect = { target -> scope.launch { repository.saveAskAiTargetPackage(target.packageName) } },
                expanded = openPicker == SettingsPicker.ASK_AI_TARGET,
                onExpandedChange = { open -> openPicker = if (open) SettingsPicker.ASK_AI_TARGET else null },
                showValue = showRowValues,
                emptyText = stringResource(R.string.settings_ask_ai_no_candidates)
            )
            AskAiPrefixRow(
                prefix = askAiPrefix,
                defaultPrefix = askAiDefaultPrefix,
                onValueChange = { text -> scope.launch { repository.saveAskAiPrefix(text) } }
            )

            // M17（F4.9/决策 D-2026-09-19-02）：语言确认重启框；「取消」不落库，「立即重启」先落库再重建
            pendingLanguage?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingLanguage = null },
                    title = { Text(stringResource(R.string.settings_language_restart_title)) },
                    text = { Text(stringResource(R.string.settings_language_restart_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingLanguage = null
                                scope.launch {
                                    // 先落库再切换：重建时 attachBaseContext 必须读到新值。
                                    // 33+ 交由框架重建；框架不可用（含 API<33）则自行重建，保证「即时生效」
                                    repository.saveAppLanguage(target)
                                    if (!LocaleSwitcher.applyToFramework(context, target)) {
                                        (context as? Activity)?.recreate()
                                    }
                                }
                            }
                        ) { Text(stringResource(R.string.common_restart_now)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingLanguage = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }
        }
    }
}

/**
 * "自动保存"开关行（F3.6）：抽成无状态 composable 供 UI 测试直驱（同 EditorExitDialogContent 做法）。
 * 可点击目标 ≥ 48dp，开关带 contentDescription 供无障碍与 UI 测试识别。
 */
@Composable
internal fun AutoSaveRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val autoSaveCd = stringResource(R.string.settings_auto_save_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_auto_save), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_auto_save_desc),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = autoSaveCd }
        )
    }
}

/**
 * 「启用问 AI」开关行（F4.10/M18，设计 REV18 §3.10-4）：开关 cd 与行文本同串（红测试经 cd 点击），
 * 说明文案为第二行小字；可点击目标 ≥ 48dp。
 */
@Composable
private fun AskAiToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val enabledCd = stringResource(R.string.settings_ask_ai_enabled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ask_ai_enabled), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_ask_ai_enabled_desc),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = enabledCd }
        )
    }
}

/**
 * 「提问前缀」输入行（F4.10/M18，设计 REV18 §3.10-4）：输入框 cd 与行文本同串（红测试经 cd 聚焦/输入）。
 *
 * 语义区分（简报 §2.4）：持久值 `prefix == null`（从未设置）= 用资源默认前缀填充显示；
 * 用户清空为 `""` = 原文直发；非空 = 自定义前缀。初值随持久值变化（remember(prefix)）。
 */
@Composable
private fun AskAiPrefixRow(
    prefix: String?,
    defaultPrefix: String,
    onValueChange: (String) -> Unit
) {
    val prefixCd = stringResource(R.string.settings_ask_ai_prefix)
    var text by remember(prefix) { mutableStateOf(prefix ?: defaultPrefix) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Text(stringResource(R.string.settings_ask_ai_prefix), style = MaterialTheme.typography.bodyMedium)
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = prefixCd }
                .padding(vertical = 4.dp)
        )
        Text(stringResource(R.string.settings_ask_ai_prefix_desc), style = MaterialTheme.typography.bodySmall)
    }
}

internal data class FontImportResult(val path: String? = null, val failure: String? = null)

/**
 * 复制所选字体到私有 files/fonts/ 并用 Typeface 预验证；失败返回描述，成功返回绝对路径。
 * internal：阅读设置面板（M10，F4.4）复用同一 SAF 导入流程（见 ReaderScreen「导入字体」入口）。
 */
internal fun importCustomFont(context: android.content.Context, uri: android.net.Uri): FontImportResult {
    val displayName = context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    val ext = displayName?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it == "ttf" || it == "otf" } ?: "ttf"

    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    val out = File(dir, "${UUID.randomUUID()}.$ext")
    val input = context.contentResolver.openInputStream(uri)
        ?: return FontImportResult(failure = context.getString(R.string.settings_font_read_failed))
    input.use { ins -> out.outputStream().use { os -> ins.copyTo(os) } }

    // Typeface.createFromFile 对无效字体抛异常；失败删除副本，避免残留垃圾文件
    val valid = runCatching { Typeface.createFromFile(out) }.isSuccess
    if (!valid) {
        out.delete()
        return FontImportResult(failure = context.getString(R.string.settings_font_invalid))
    }
    return FontImportResult(path = out.absolutePath)
}

@Composable
private fun fontLabel(font: ReaderFont): String = when (font) {
    ReaderFont.CODE -> stringResource(R.string.settings_font_code)
    ReaderFont.SERIF -> stringResource(R.string.reader_font_serif)
    ReaderFont.SANS -> stringResource(R.string.reader_font_sans)
    ReaderFont.CUSTOM -> stringResource(R.string.reader_font_custom)
}

/**
 * "默认字号"行（F4.3/TC-F4-03）：A- / A+ 步进按钮，档位与端点 clamp 同 ReaderViewModel.stepFontSize。
 * 按钮 contentDescription "减小默认字号"/"增大默认字号"，可点击目标 ≥ 48dp。
 */
@Composable
private fun FontSizeSettingRow(fontSizeSp: Int, onStep: (Int) -> Unit) {
    val decreaseCd = stringResource(R.string.settings_decrease_font_size_cd)
    val increaseCd = stringResource(R.string.settings_increase_font_size_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.settings_default_font_size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${fontSizeSp}sp", style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = { onStep(-1) },
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = decreaseCd }
        ) { Text("A-") }
        TextButton(
            onClick = { onStep(1) },
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = increaseCd }
        ) { Text("A+") }
    }
}

/**
 * 设置页五个选项列表行（M17，单一对话框状态源；任一对话框打开时隐藏全部背景行值语义，
 * 避免与对话框选项文本在语义树重复——如语言行值「跟随系统」与主题对话框选项同字面）。
 */
private enum class SettingsPicker { LANGUAGE, LINE_HEIGHT, WRAP, FONT, THEME, ASK_AI_TARGET }

/**
 * 选项列表设置行（M17，F4.3/TC-UI-19，决策 D-2026-09-19-01）：整行可点（≥48dp），行尾显示当前值；
 * 点击唤出单选对话框（标题 = 行标题），列出全部可选值并标记当前值，点选即回调并关闭。
 * [expanded]/[onExpandedChange] 由调用方统一持有：任一选项对话框打开时全部背景行值不进入语义树
 * （[showValue] = false，视觉保留）——模态弹层打开时背景不进入无障碍树，同时避免背景行值与对话框选项
 * 文本在语义树重名（如「150%」「跟随系统」）。红测试据此以单匹配断言直达对话框选项（见 SettingsOptionPickerTest）。
 */
@Composable
private fun <T> OptionPickerRow(
    title: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showValue: Boolean,
    // F4.10/M18：候选为空时的对话框空态文案（默认 null 保持既有五个调用方行为不变）
    emptyText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable { onExpandedChange(true) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            // 对话框打开期间保留行值视觉、仅退出语义树（模态隔离，见 kdoc）
            modifier = if (showValue) Modifier else Modifier.clearAndSetSemantics {}
        )
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { onExpandedChange(false) },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // F4.10/M18：无候选（如 Robolectric 或未安装可接收文本的应用）时显示空态文案
                    if (options.isEmpty() && emptyText != null) {
                        Text(emptyText, style = MaterialTheme.typography.bodyMedium)
                    }
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(
                                    selected = isSelected(option),
                                    onClick = {
                                        onSelect(option)
                                        onExpandedChange(false)
                                    }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected(option), onClick = null)
                            Text(optionLabel(option), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onExpandedChange(false) }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 语言档位标签（跟随系统/中文/English）；语言行值与选项共用 */
@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
    AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
    AppLanguage.EN -> stringResource(R.string.settings_language_en)
}

/** 字号档位步进（12/14/16/18/20/22/24），到端点 clamp；与 ReaderViewModel.stepFontSize 同一套档位常量 */
private fun stepFontSize(current: Int, delta: Int): Int {
    val steps = ReaderViewModel.FONT_SIZE_STEPS
    val index = steps.indexOf(current).coerceAtLeast(0)
    return steps[(index + delta).coerceIn(0, steps.lastIndex)]
}

@Composable
private fun themeLabel(theme: ReadingTheme?): String = when (theme) {
    null -> stringResource(R.string.reader_theme_system)
    ReadingTheme.DEFAULT -> stringResource(R.string.reader_theme_default)
    ReadingTheme.EYE_CARE -> stringResource(R.string.reader_theme_eye_care)
    ReadingTheme.SOFT_LIGHT -> stringResource(R.string.reader_theme_soft)
    ReadingTheme.NIGHT -> stringResource(R.string.reader_theme_night)
}
