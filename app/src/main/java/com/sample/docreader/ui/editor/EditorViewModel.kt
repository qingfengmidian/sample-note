package com.sample.docreader.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.docreader.core.io.DocumentWriter
import com.sample.docreader.core.io.DraftStore
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.parse.ParserFactory
import com.sample.docreader.core.parse.PlainTextParser
import com.sample.docreader.core.parse.TxtParser
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * M7c 范围（TC-UT-09b、TC-F3-06/07、TC-F4-06，设计§3.4 草稿段）：
 * - 内容变化防抖 1s 写私有草稿（仅 dirty 时；退回基线删草稿），进程死亡后可恢复；
 * - uri/光标存 SavedStateHandle（轻量，不存正文）；load 后草稿存在则恢复文本+光标+dirty；
 * - save 成功 / discardDraft（三选"放弃"）删除草稿；
 * - setAutoSave 开启后内容变化防抖 3s 自动 save（持久化由 UI 层经 SettingsRepository）。
 * - 历史栈存 TextFieldValue（含光标/选区）；文本变化入栈，仅光标/选区变化（text 相同）不入栈。
 * - 满 100 步后丢弃最早记录（设计§3.4）。
 * - dirty 以"已保存基线"（构造 initialText 或 load 载入的全文）为准，撤销回基线即归 false。
 * - draftStore/savedStateHandle 为 null 时草稿/恢复功能安全降级为无操作（M7a 单参构造不受影响）。
 */
class EditorViewModel(
    initialText: String = "",
    private val fileOpener: FileOpener? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val documentWriter: DocumentWriter? = null,
    private val cacheDir: File? = null,
    private val draftStore: DraftStore? = null,
    private val savedStateHandle: SavedStateHandle? = null
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

    /** 自动保存行为开关（TC-F3-06/07）：默认关；SettingsRepository 存在时跟随 settings.autoSave */
    private var autoSaveEnabled = false

    private var draftJob: Job? = null
    private var autoSaveJob: Job? = null

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    init {
        settingsRepository?.let { repo ->
            viewModelScope.launch {
                repo.settings.collect {
                    settings = it
                    autoSaveEnabled = it.autoSave
                }
            }
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
        // 同步置 loading（协程体外）：测试 awaitLoaded 轮询依赖 load 返回时即为 true，
        // 与 ReaderUiState.loading 默认 true 同一模式；真实 IO 在协程内 withContext(IO) 完成
        loading = true
        error = null
        viewModelScope.launch {
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
                restoreDraftIfAny(uri, text)
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
     * 临时文件写入/校验失败（C5）：防截断保证失效，直接 `saveResult = FailedSaveAs` 中止，不写回；
     * "wt" 抛 FileNotFoundException/UnsupportedOperationException 时回退 "w"；
     * 仍失败：保留临时文件、`saveResult = FailedSaveAs`、dirty 保持；
     * 异常消息含 "No content provider" 时 `saveResult = ProviderKilled`（S1 探针结论）。
     * 成功：基线更新为当前文本、dirty=false、临时文件删除、`saveResult = Success`。
     */
    fun save() {
        if (!dirty) return
        val uri = loadedUri.orEmpty()
        // 新一次保存开始时取消未落盘的防抖草稿任务，避免保存成功后又被回写
        draftJob?.cancel()
        viewModelScope.launch {
            val writer = documentWriter
            if (writer == null) {
                saveResult = SaveResult.FailedSaveAs
                return@launch
            }
            val text = content.text
            val bytes = text.toByteArray(Charsets.UTF_8)
            val tempFile = writeTempFile(text)
            if (tempFile == null) {
                // C5：临时文件写入/读回校验失败时防截断保证已失效，必须中止写回（不得直接落盘）
                saveResult = SaveResult.FailedSaveAs
                return@launch
            }
            try {
                writeBackWithFallback(writer, uri, bytes)
                baseline = text
                syncDerived()
                tempFile.delete()
                if (uri.isNotEmpty()) draftStore?.delete(uri) // 保存成功即清除草稿（TC-UT-09b）
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

    /**
     * 自动保存行为开关（TC-F3-06/07）：开启后内容变化防抖 3s 自动 [save]；关闭则不落盘。
     * 持久化由 UI 层经 SettingsRepository 负责；SettingsRepository 存在时本开关随 settings.autoSave 刷新。
     */
    fun setAutoSave(enabled: Boolean) {
        autoSaveEnabled = enabled
        if (!enabled) autoSaveJob?.cancel()
    }

    /** 三选对话框选"放弃"（TC-UT-09b）：删除对应草稿并取消未执行的防抖任务 */
    fun discardDraft() {
        draftJob?.cancel()
        autoSaveJob?.cancel()
        loadedUri?.let { draftStore?.delete(it) }
    }

    /**
     * 草稿恢复（TC-UT-09b/TC-F4-06）：load 载入原文为基线后调用。
     * 草稿存在且 ≠ 基线：恢复草稿文本 + SavedStateHandle 中的光标 + dirty=true；
     * 不伪造 undo 历史（undo 栈保持为空，无法撤销回原文——正文以草稿为准）。
     * 草稿 == 基线：属残留草稿，顺手删除。
     */
    private fun restoreDraftIfAny(uri: String, original: String) {
        val store = draftStore ?: return
        val draft = store.read(uri) ?: return
        if (draft == original) {
            store.delete(uri)
            return
        }
        content = TextFieldValue(draft, selection = restoredCursor(uri, draft))
        undoStack.clear()
        redoStack.clear()
        syncDerived()
    }

    /** 光标恢复：仅当 SavedStateHandle 里记录的 uri 与当前一致才采用，越界收敛到文本范围 */
    private fun restoredCursor(uri: String, text: String): TextRange {
        val handle = savedStateHandle ?: return TextRange.Zero
        if (handle.get<String>(KEY_URI) != uri) return TextRange.Zero
        val start = handle.get<Int>(KEY_CURSOR_START) ?: return TextRange.Zero
        val end = handle.get<Int>(KEY_CURSOR_END) ?: start
        return TextRange(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
    }

    /** 内容变化后的 M7c 联动：轻量状态入 SavedStateHandle + 防抖草稿 + 自动保存调度 */
    private fun onEdited() {
        persistLightState()
        scheduleDraftPersist()
        scheduleAutoSave()
    }

    /** uri/光标存 SavedStateHandle（只存轻量状态，不存正文——正文在草稿文件） */
    private fun persistLightState() {
        val handle = savedStateHandle ?: return
        val uri = loadedUri ?: return
        handle[KEY_URI] = uri
        handle[KEY_CURSOR_START] = content.selection.start
        handle[KEY_CURSOR_END] = content.selection.end
    }

    /** 防抖 1s 写草稿（TC-UT-09b）：dirty 时写正文；内容退回基线（撤销/改回）则删除草稿 */
    private fun scheduleDraftPersist() {
        val store = draftStore ?: return
        val uri = loadedUri ?: return
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            if (content.text == baseline) store.delete(uri) else store.write(uri, content.text)
        }
    }

    /** 自动保存（TC-F3-07）：开启时内容变化防抖 3s 自动 save；save 内部校验 dirty */
    private fun scheduleAutoSave() {
        if (!autoSaveEnabled || loadedUri == null) return
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MS)
            save()
        }
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
        onEdited()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(content)
        content = undoStack.removeLast()
        syncDerived()
        onEdited()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        pushUndo(content)
        content = redoStack.removeLast()
        syncDerived()
        onEdited()
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

        /** 草稿防抖时长（TC-UT-09b）：内容停顿 1s 后写私有草稿 */
        const val DRAFT_DEBOUNCE_MS = 1000L

        /** 自动保存防抖时长（TC-F3-07）：开启后内容停顿 3s 自动落盘 */
        const val AUTO_SAVE_DEBOUNCE_MS = 3000L

        /** SavedStateHandle 键：轻量状态（uri + 光标），不存正文 */
        private const val KEY_URI = "draft_uri"
        private const val KEY_CURSOR_START = "draft_cursor_start"
        private const val KEY_CURSOR_END = "draft_cursor_end"
    }
}
