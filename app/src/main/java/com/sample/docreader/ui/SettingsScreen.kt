package com.sample.docreader.ui

import android.app.Activity
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sample.docreader.R
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
 * 持久化；档位序列与阅读页 ReaderViewModel（stepFontSize/cycleLineHeight/cycleFont/cycleTheme）完全一致，
 * 阅读页订阅同一份 settings flow，新打开的文件自然应用新默认值。
 * M16（F4.9）：顶部「通用」小节的语言行（跟随系统/中文/英文）写独立 DataStore 键并即时重建生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = ReadingSettings())
    // F4.9/M16：语言覆盖与阅读设置键级隔离，故单独订阅（缺失/未知标签 = 跟随系统）
    val language by repository.appLanguage.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

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
            // F4.9/M16：应用内语言（跟随系统/中文/English），选中即时持久化；<33 自行重建，33+ 由框架重建
            Text(stringResource(R.string.settings_general_section), style = MaterialTheme.typography.titleSmall)
            CycleSettingRow(
                title = stringResource(R.string.settings_language),
                value = when (language) {
                    AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
                    AppLanguage.ZH -> stringResource(R.string.settings_language_zh)
                    AppLanguage.EN -> stringResource(R.string.settings_language_en)
                },
                onClick = {
                    val next = LocaleSwitcher.next(language)
                    scope.launch {
                        // 先落库再切换：重建时 attachBaseContext 必须读到新值。
                        // 33+ 交由框架重建；框架不可用（含 API<33）则自行重建，保证「即时生效」
                        repository.saveAppLanguage(next)
                        if (!LocaleSwitcher.applyToFramework(context, next)) {
                            (context as? Activity)?.recreate()
                        }
                    }
                }
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
            // 全局阅读默认项（F4.3/TC-F4-03，M8a）：档位逻辑与 ReaderViewModel 完全一致，即时持久化
            Text(stringResource(R.string.settings_reading_defaults), style = MaterialTheme.typography.titleSmall)
            FontSizeSettingRow(
                fontSizeSp = settings.fontSizeSp,
                onStep = { delta ->
                    scope.launch { repository.save(settings.copy(fontSizeSp = stepFontSize(settings.fontSizeSp, delta))) }
                }
            )
            CycleSettingRow(
                title = stringResource(R.string.settings_default_line_spacing),
                value = "${settings.lineHeightPercent}%",
                onClick = {
                    scope.launch {
                        repository.save(settings.copy(lineHeightPercent = nextLineHeight(settings.lineHeightPercent)))
                    }
                }
            )
            CycleSettingRow(
                title = stringResource(R.string.settings_default_wrap_mode),
                value = if (settings.wrapMode == WrapMode.WRAP) stringResource(R.string.reader_auto_wrap)
                else stringResource(R.string.reader_no_wrap),
                onClick = {
                    scope.launch {
                        repository.save(
                            settings.copy(
                                wrapMode = if (settings.wrapMode == WrapMode.WRAP) WrapMode.NO_WRAP else WrapMode.WRAP
                            )
                        )
                    }
                }
            )
            CycleSettingRow(
                title = stringResource(R.string.settings_default_font),
                value = fontLabel(settings.font),
                onClick = {
                    // G2（M10）：customFontPath 非空时循环序列纳入 CUSTOM（与 ReaderViewModel.cycleFont 一致）
                    scope.launch { repository.save(settings.copy(font = nextFont(settings.font, settings.customFontPath))) }
                }
            )
            CycleSettingRow(
                title = stringResource(R.string.settings_default_theme),
                value = themeLabel(settings.theme),
                onClick = { scope.launch { repository.save(settings.copy(theme = nextTheme(settings.theme))) } }
            )
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
 * 循环切换设置行（F4.3/TC-F4-03）：行距/换行模式/字体/主题通用。
 * 切换按钮 contentDescription 与行文本同名（约定见 SettingsGlobalTest），可点击目标 ≥ 48dp。
 */
@Composable
private fun CycleSettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = title }
        ) { Text(stringResource(R.string.common_switch)) }
    }
}

/** 字号档位步进（12/14/16/18/20/22/24），到端点 clamp；与 ReaderViewModel.stepFontSize 同一套档位常量 */
private fun stepFontSize(current: Int, delta: Int): Int {
    val steps = ReaderViewModel.FONT_SIZE_STEPS
    val index = steps.indexOf(current).coerceAtLeast(0)
    return steps[(index + delta).coerceIn(0, steps.lastIndex)]
}

/** 行距档位循环：120→150→180→200（与 ReaderViewModel.cycleLineHeight 一致） */
private fun nextLineHeight(current: Int): Int {
    val steps = ReaderViewModel.LINE_HEIGHT_STEPS
    val index = steps.indexOf(current)
    return steps[(index + 1).mod(steps.size)]
}

/**
 * 字体循环：代码→衬线→无衬线；G2（M10）：customFontPath 非空时序列追加 CUSTOM。
 * 与 ReaderViewModel.cycleFont 一致。
 */
private fun nextFont(current: ReaderFont, customFontPath: String? = null): ReaderFont {
    val order = if (customFontPath != null) {
        listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS, ReaderFont.CUSTOM)
    } else {
        listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS)
    }
    return order[(order.indexOf(current) + 1).mod(order.size)]
}

/** 主题循环：跟随系统→默认白→护眼绿→柔光米黄→夜间黑（与 ReaderViewModel.cycleTheme 一致） */
private fun nextTheme(current: ReadingTheme?): ReadingTheme? {
    val order = listOf(null, ReadingTheme.DEFAULT, ReadingTheme.EYE_CARE,
        ReadingTheme.SOFT_LIGHT, ReadingTheme.NIGHT)
    return order[(order.indexOf(current) + 1).mod(order.size)]
}

@Composable
private fun themeLabel(theme: ReadingTheme?): String = when (theme) {
    null -> stringResource(R.string.reader_theme_system)
    ReadingTheme.DEFAULT -> stringResource(R.string.reader_theme_default)
    ReadingTheme.EYE_CARE -> stringResource(R.string.reader_theme_eye_care)
    ReadingTheme.SOFT_LIGHT -> stringResource(R.string.reader_theme_soft)
    ReadingTheme.NIGHT -> stringResource(R.string.reader_theme_night)
}
