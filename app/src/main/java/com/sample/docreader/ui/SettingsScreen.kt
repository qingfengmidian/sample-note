package com.sample.docreader.ui

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 设置页（M4）：自定义字体导入（SAF 选 ttf/otf → 复制到 files/fonts/ → Typeface 预验证 → 存路径）。
 * 其余阅读排版设置（字号/行距/换行/主题）在阅读界面底部工具条内即时调节（设计§4）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = ReadingSettings())
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    fun importFont(uri: android.net.Uri) {
        scope.launch {
            message = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    importCustomFont(context, uri)
                }.getOrElse { e -> ImportResult(failure = e.message ?: "导入失败") }
            }
            if (result.path != null) {
                repository.save(settings.copy(font = ReaderFont.CUSTOM, customFontPath = result.path))
                message = "自定义字体已导入"
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
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("字体方案", style = MaterialTheme.typography.titleSmall)
            Text("当前：${fontLabel(settings.font)}", style = MaterialTheme.typography.bodyMedium)
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
                Text("导入自定义字体（ttf/otf）")
            }
            if (settings.font == ReaderFont.CUSTOM) {
                Text(
                    "已启用自定义字体${settings.customFontPath?.let { "：${File(it).name}" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class ImportResult(val path: String? = null, val failure: String? = null)

/** 复制所选字体到私有 files/fonts/ 并用 Typeface 预验证；失败返回描述，成功返回绝对路径 */
private fun importCustomFont(context: android.content.Context, uri: android.net.Uri): ImportResult {
    val displayName = context.contentResolver.query(
        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    val ext = displayName?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it == "ttf" || it == "otf" } ?: "ttf"

    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    val out = File(dir, "${UUID.randomUUID()}.$ext")
    val input = context.contentResolver.openInputStream(uri)
        ?: return ImportResult(failure = "无法读取所选文件")
    input.use { ins -> out.outputStream().use { os -> ins.copyTo(os) } }

    // Typeface.createFromFile 对无效字体抛异常；失败删除副本，避免残留垃圾文件
    val valid = runCatching { Typeface.createFromFile(out) }.isSuccess
    if (!valid) {
        out.delete()
        return ImportResult(failure = "字体文件无效，请选择有效的 ttf/otf")
    }
    return ImportResult(path = out.absolutePath)
}

private fun fontLabel(font: ReaderFont): String = when (font) {
    ReaderFont.CODE -> "代码风格（等宽）"
    ReaderFont.SERIF -> "衬线"
    ReaderFont.SANS -> "无衬线"
    ReaderFont.CUSTOM -> "自定义"
}
