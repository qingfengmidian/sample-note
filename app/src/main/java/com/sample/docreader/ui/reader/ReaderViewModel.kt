package com.sample.docreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import com.sample.docreader.core.parse.EpubParser
import com.sample.docreader.core.parse.ParserFactory
import com.sample.docreader.core.parse.PlainTextParser
import com.sample.docreader.core.parse.TxtParser
import com.sample.docreader.core.progress.ProgressRepository
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

/** 阅读界面状态（设计§3.3：ReaderViewModel 向 UI 暴露单个 StateFlow） */
data class ReaderUiState(
    val loading: Boolean = true,
    val document: Document? = null,
    val settings: ReadingSettings = ReadingSettings(),
    val error: String? = null,             // ParseException 差异化文案
    val chapterIndex: Int = 0,             // 当前章节索引（epub 多章）
    val restoreParagraph: Int? = null      // 加载/切章后待滚动到的段落索引（一次性）
)

/**
 * 阅读器 ViewModel：持有 Document + 当前章节 + ReadingSettings。
 * 加载经 FileOpener 读字节 + ParserFactory 分发（txt/md/xml 单章，epub 多章）；
 * epub 阅读进度按 uri 经 ProgressRepository 恢复/保存（F2.8）。
 */
class ReaderViewModel(
    private val fileOpener: FileOpener,
    private val settingsRepository: SettingsRepository,
    private val progressRepository: ProgressRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentUri: String = ""
    private var currentParagraph: Int = 0

    init {
        // 全局设置变化即时反映到阅读界面
        viewModelScope.launch {
            settingsRepository.settings.collect { s -> _uiState.update { it.copy(settings = s) } }
        }
    }

    fun load(uri: String, format: DocFormat, displayName: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val bytes = fileOpener.open(uri).use { it.readBytes() }
                val name = displayName?.takeIf { it.isNotBlank() } ?: uri.substringAfterLast('/')
                val document = parseBytes(bytes, name, uri, format)
                currentUri = uri
                val restored = progressRepository?.progress(uri)?.first()
                val chapterIndex = restored?.chapter
                    ?.coerceIn(0, (document.chapters.size - 1).coerceAtLeast(0)) ?: 0
                // paragraph 同样需 clamp 到目标章节范围内，防止恢复越界（章节空时归 0）
                val maxParagraph =
                    (document.chapters.getOrNull(chapterIndex)?.paragraphs?.lastIndex ?: 0).coerceAtLeast(0)
                currentParagraph = restored?.paragraph?.coerceIn(0, maxParagraph) ?: 0
                _uiState.update {
                    it.copy(
                        loading = false,
                        document = document,
                        chapterIndex = chapterIndex,
                        restoreParagraph = currentParagraph
                    )
                }
            } catch (e: ParseException) {
                _uiState.update { it.copy(loading = false, error = e.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FileNotFoundException) {
                // 云盘 Provider 进程被系统/OEM 杀死时（S1 探针结论）访问会抛
                // "No content provider ..."，不得误判权限失效；提示用户启动对应应用。
                if ((e.message ?: "").contains("No content provider")) {
                    _uiState.update {
                        it.copy(loading = false, error = "文件提供方（如云盘应用）未运行，请启动对应应用后重试")
                    }
                } else {
                    _uiState.update { it.copy(loading = false, error = ParseException.NoPermission().message) }
                }
            } catch (e: Exception) {
                // 文件读不到（被移动/删除等）统一按无权限提示
                _uiState.update { it.copy(loading = false, error = ParseException.NoPermission().message) }
            }
        }
    }

    private suspend fun parseBytes(bytes: ByteArray, name: String, uri: String, format: DocFormat): Document =
        when (val parser = ParserFactory.forFormat(format)) {
            is TxtParser -> parser.parseBytes(bytes, name, uri)
            is PlainTextParser -> parser.parseBytes(bytes, name, uri)
            is EpubParser -> parser.parseBytes(bytes, name, uri)
            else -> throw ParseException.UnsupportedFormat()
        }

    /** 跳转到指定章节（epub），越界忽略；切换后从章首开始并记录进度（F2.7） */
    fun selectChapter(index: Int) {
        val document = _uiState.value.document ?: return
        if (index !in document.chapters.indices) return
        currentParagraph = 0
        _uiState.update { it.copy(chapterIndex = index, restoreParagraph = 0) }
        persistProgress(index, 0)
    }

    /** UI 滚动时上报首个可见段落索引，用于阅读进度恢复（F2.8） */
    fun onFirstVisibleParagraphChanged(paragraph: Int) {
        currentParagraph = paragraph.coerceAtLeast(0)
        persistProgress(_uiState.value.chapterIndex, currentParagraph)
    }

    /** UI 完成一次 restoreParagraph 滚动后调用，清除一次性目标 */
    fun consumeRestoreParagraph() = _uiState.update { it.copy(restoreParagraph = null) }

    private fun persistProgress(chapter: Int, paragraph: Int) {
        val repo = progressRepository ?: return
        if (currentUri.isEmpty()) return
        viewModelScope.launch { repo.save(currentUri, chapter, paragraph) }
    }

    /** 设置改动即时生效（先更新 StateFlow）并经 SettingsRepository 持久化（全局共享，F3.3） */
    fun updateSettings(transform: (ReadingSettings) -> ReadingSettings) {
        val newSettings = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = newSettings) }
        viewModelScope.launch { settingsRepository.save(newSettings) }
    }

    fun toggleWrap() = updateSettings {
        it.copy(wrapMode = if (it.wrapMode == WrapMode.WRAP) WrapMode.NO_WRAP else WrapMode.WRAP)
    }

    /** 字号档位步进（12/14/16/18/20/22/24），到端点不变 */
    fun stepFontSize(delta: Int) = updateSettings {
        val index = FONT_SIZE_STEPS.indexOf(it.fontSizeSp).coerceAtLeast(0)
        it.copy(fontSizeSp = FONT_SIZE_STEPS[(index + delta).coerceIn(0, FONT_SIZE_STEPS.lastIndex)])
    }

    /** 行距档位循环：120→150→180→200 */
    fun cycleLineHeight() = updateSettings {
        val index = LINE_HEIGHT_STEPS.indexOf(it.lineHeightPercent)
        it.copy(lineHeightPercent = LINE_HEIGHT_STEPS[(index + 1).mod(LINE_HEIGHT_STEPS.size)])
    }

    /** 字体循环：代码→衬线→无衬线（CUSTOM 导入在 M4） */
    fun cycleFont() = updateSettings {
        val order = listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS)
        it.copy(font = order[(order.indexOf(it.font) + 1).mod(order.size)])
    }

    /** 主题循环：跟随系统→默认白→护眼绿→柔光米黄→夜间黑 */
    fun cycleTheme() = updateSettings {
        val order = listOf(null, ReadingTheme.DEFAULT, ReadingTheme.EYE_CARE,
            ReadingTheme.SOFT_LIGHT, ReadingTheme.NIGHT)
        it.copy(theme = order[(order.indexOf(it.theme) + 1).mod(order.size)])
    }

    companion object {
        val FONT_SIZE_STEPS = listOf(12, 14, 16, 18, 20, 22, 24)
        val LINE_HEIGHT_STEPS = listOf(120, 150, 180, 200)
    }
}
