package com.sample.docreader.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.docreader.core.io.DocumentWriter
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.parse.ParserFactory
import com.sample.docreader.core.parse.PlainTextParser
import com.sample.docreader.core.parse.TxtParser
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

/** 保存结果（设计§3.4 + S1 探针结论）：一次性事件，UI 消费后经 [EditorViewModel.consumeSaveResult] 清除 */
sealed class SaveResult {
    /** 写回成功（临时文件已删除、基线已更新） */
    object Success : SaveResult()

    /** 写回失败（临时文件保留、提示另存） */
    object FailedSaveAs : SaveResult()

    /** Provider 进程被 OEM 杀死（异常消息含 "No content provider"），不按权限失效处理 */
    object ProviderKilled : SaveResult()
}

/**
 * 编辑器 ViewModel（设计§3.4）：持有 TextFieldValue + dirty 标记 + 撤销/重做历史栈（100 步上限）。
 *
 * M7a 范围（TC-UT-08）：纯文本编辑 + 撤销/重做 + 退出拦截的 dirty 依据。
 * M7b 范围（TC-UT-09/09a）：保存落盘（临时文件校验 → "wt" → 回退 "w" → 另存提示）。
 * - 历史栈存 TextFieldValue（含光标/选区）；文本变化入栈，仅光标/选区变化（text 相同）不入栈。
 * - 满 100 步后丢弃最早记录（设计§3.4）。
 * - dirty 以"已保存基线"（构造 initialText 或 load 载入的全文）为准，撤销回基线即归 false。
 */
class EditorViewModel(
    initialText: String = "",
    private val fileOpener: FileOpener? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val documentWriter: DocumentWriter? = null,
    private val cacheDir: File? = null
) : ViewModel() {

    var content by mutableStateOf(TextFieldValue(initialText))
        private set

    /** 当前文本 ≠ 已保存基线（TC-F3-03 退出拦截依据） */
    var dirty by mutableStateOf(false)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 编辑器样式与阅读界面共享同一套 ReadingSettings（F3.3） */
    var settings by mutableStateOf(ReadingSettings())
        private set

    var displayName by mutableStateOf("")
        private set

    /** 保存结果（一次性事件，TC-UT-09/09a）：UI 展示后调用 [consumeSaveResult] 置 null */
    var saveResult by mutableStateOf<SaveResult?>(null)
        private set

    private var baseline: String = initialText
    private var loadedUri: String? = null

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    init {
        settingsRepository?.let { repo ->
            viewModelScope.launch { repo.settings.collect { settings = it } }
        }
    }

    /**
     * 载入文档全文（Chapter.paragraphs 拼接）作为编辑基线。
     * 解析层与 UI 层解耦：编辑器只消费归一化后的全文文本，不感知解析细节。
     */
    fun load(uri: String, format: DocFormat, displayName: String) {
        if (loadedUri == uri) return
        loadedUri = uri
        this.displayName = displayName
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val opener = fileOpener ?: throw ParseException.NoPermission()
                val bytes = opener.open(uri).use { it.readBytes() }
                val name = displayName.takeIf { it.isNotBlank() } ?: uri.substringAfterLast('/')
                val document = when (val parser = ParserFactory.forFormat(format)) {
                    is TxtParser -> parser.parseBytes(bytes, name, uri)
                    is PlainTextParser -> parser.parseBytes(bytes, name, uri)
                    else -> throw ParseException.UnsupportedFormat()
                }
                val text = document.chapters.joinToString("\n") { it.paragraphs.joinToString("\n") }
                resetHistory(text)
            } catch (e: ParseException) {
                error = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 文件读不到（被移动/删除等）统一按无权限提示，与阅读页一致
                error = ParseException.NoPermission().message
            } finally {
                loading = false
            }
        }
    }

    /**
     * 保存落盘（TC-UT-09/09a，设计§3.4）：非 dirty 空操作；
     * 文本按 UTF-8 写 cacheDir 临时文件并读回校验字节完整性 → `openOutput(uri, "wt")` 一次性写回；
     * "wt" 抛 FileNotFoundException/UnsupportedOperationException 时回退 "w"；
     * 仍失败：保留临时文件、`saveResult = FailedSaveAs`、dirty 保持；
     * 异常消息含 "No content provider" 时 `saveResult = ProviderKilled`（S1 探针结论）。
     * 成功：基线更新为当前文本、dirty=false、临时文件删除、`saveResult = Success`。
     */
    fun save() {
        if (!dirty) return
        val uri = loadedUri.orEmpty()
        viewModelScope.launch {
            val writer = documentWriter
            if (writer == null) {
                saveResult = SaveResult.FailedSaveAs
                return@launch
            }
            val text = content.text
            val bytes = text.toByteArray(Charsets.UTF_8)
            val tempFile = writeTempFile(text)
            try {
                writeBackWithFallback(writer, uri, bytes)
                baseline = text
                syncDerived()
                tempFile?.delete()
                saveResult = SaveResult.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写回失败：临时文件保留（内容不丢），dirty 保持，据异常分类提示
                val killed = (e.message ?: "").contains(NO_CONTENT_PROVIDER)
                saveResult = if (killed) SaveResult.ProviderKilled else SaveResult.FailedSaveAs
            }
        }
    }

    /** UI 消费保存结果后调用，清除一次性事件（M7b） */
    fun consumeSaveResult() {
        saveResult = null
    }

    /** 文本 UTF-8 写入 cacheDir 临时文件并读回校验字节完整性；失败/校验不通过返回 null */
    private fun writeTempFile(text: String): File? {
        val cache = cacheDir ?: return null
        val temp = File(cache, "docreader_edit_${System.nanoTime()}.tmp")
        return try {
            temp.writeText(text, Charsets.UTF_8)
            val verified = temp.readBytes().contentEquals(text.toByteArray(Charsets.UTF_8))
            if (verified) temp else { temp.delete(); null }
        } catch (e: Exception) {
            temp.delete()
            null
        }
    }

    /** "wt" 优先，FileNotFoundException/UnsupportedOperationException 回退 "w"（S1：Drive 两模式均支持） */
    private fun writeBackWithFallback(writer: DocumentWriter, uri: String, bytes: ByteArray) {
        try {
            writer.openOutput(uri, "wt").use { it.write(bytes) }
        } catch (e: FileNotFoundException) {
            writer.openOutput(uri, "w").use { it.write(bytes) }
        } catch (e: UnsupportedOperationException) {
            writer.openOutput(uri, "w").use { it.write(bytes) }
        }
    }

    /** 文本变化入历史栈；仅光标/选区变化（text 相同）不入栈（TC-UT-08） */
    fun onContentChange(newValue: TextFieldValue) {
        if (newValue.text != content.text) {
            pushUndo(content)
            content = newValue
            redoStack.clear()
        } else {
            content = newValue
        }
        syncDerived()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(content)
        content = undoStack.removeLast()
        syncDerived()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        pushUndo(content)
        content = redoStack.removeLast()
        syncDerived()
    }

    private fun pushUndo(value: TextFieldValue) {
        if (undoStack.size >= HISTORY_LIMIT) undoStack.removeFirst()
        undoStack.addLast(value)
    }

    private fun resetHistory(text: String) {
        baseline = text
        content = TextFieldValue(text)
        undoStack.clear()
        redoStack.clear()
        syncDerived()
    }

    private fun syncDerived() {
        dirty = content.text != baseline
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    companion object {
        /** 撤销/重做历史栈上限（设计§3.4） */
        const val HISTORY_LIMIT = 100

        /** 云盘 Provider 进程被 OEM 杀死时异常消息特征（S1 探针结论，设计§8） */
        const val NO_CONTENT_PROVIDER = "No content provider"
    }
}
