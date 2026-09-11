package com.sample.docreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sample.docreader.core.io.EpubCache
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.Chapter
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import com.sample.docreader.core.parse.EpubParser
import com.sample.docreader.core.parse.PagedTextLoader
import com.sample.docreader.core.parse.ParserFactory
import com.sample.docreader.core.parse.PlainTextParser
import com.sample.docreader.core.parse.TxtParser
import com.sample.docreader.core.progress.ProgressRepository
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.Charset

/** 阅读界面状态（设计§3.3：ReaderViewModel 向 UI 暴露单个 StateFlow） */
data class ReaderUiState(
    val loading: Boolean = true,
    val document: Document? = null,
    val settings: ReadingSettings = ReadingSettings(),
    val error: String? = null,             // ParseException 差异化文案
    val chapterIndex: Int = 0,             // 当前章节索引（epub 多章）
    val restoreParagraph: Int? = null,     // 加载/切章后待滚动到的段落索引（一次性）
    val editable: Boolean = true,          // 文件 ≤2MB 可编辑，超出只读（TC-F3-09，设计§8；真机验证 1.35MB 可编辑，5MB 触发 ANR）
    val hasMore: Boolean = false,          // txt 分块加载：还有未加载段落（M8b，F4.2）
    val loadingMore: Boolean = false,      // txt 分块加载：正在追加下一块（防重入）
    val encodingOverride: String? = null,  // G1（F2.6）：txt 手动编码覆盖的 charset 名；null = 自动探测（会话内有效，不持久化）
    val materializedChapterCount: Int = 0, // F4.7（M12b）：已物化进 item 列表的章数（初始 1；恢复时 = 恢复章 + 1）
    val totalItems: Int = 0,               // F4.7（M12b）：全书 item 总数（= ChapterItemMap.totalItems，解析全量故精确）
    val restoreItem: Int? = null           // F4.7（M12b）：一次性全局 item 滚动目标（M12c 消费；与 restoreParagraph 并存）
)

/**
 * 阅读器 ViewModel：持有 Document + 当前章节 + ReadingSettings。
 * 加载经 FileOpener 读字节 + ParserFactory 分发（txt/md/xml 单章，epub 多章）；
 * epub 阅读进度按 uri 经 ProgressRepository 恢复/保存（F2.8）。
 */
class ReaderViewModel(
    private val fileOpener: FileOpener,
    private val settingsRepository: SettingsRepository,
    private val progressRepository: ProgressRepository? = null,
    private val epubCache: EpubCache? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var currentUri: String = ""
    private var currentParagraph: Int = 0

    /** F4.7（M12b）：全局 item ↔ (章, 段) 前缀和映射；load 时按当前 Document 重建，txt 追加后同步刷新 */
    private var itemMap: ChapterItemMap? = null

    /** G1（F2.6）：txt 手动编码覆盖；null = 自动探测。仅会话内有效，不写 DataStore */
    private var encodingOverride: Charset? = null

    /** G4-code：进度落库防抖——滚动期只记最新待写点，停顿 500ms 后一次性写 Room */
    private data class ProgressPoint(val uri: String, val chapter: Int, val paragraph: Int)

    private var pendingPoint: ProgressPoint? = null
    private var progressJob: Job? = null

    /** txt 分块加载器（M8b）：仅分页路径持有；重新 load/onCleared 时关闭，防 fd 泄漏（TC-F4-08 同类） */
    private var pagedLoader: PagedTextLoader? = null

    override fun onCleared() {
        closePagedLoader()
        // G4-code：兜底落库——防抖任务随 viewModelScope 取消，不能丢最后一段进度
        val point = pendingPoint
        pendingPoint = null
        progressJob?.cancel()
        val repo = progressRepository
        if (point != null && repo != null) {
            // viewModelScope 已取消，用一次性 IO 协程完成最后一次写入
            CoroutineScope(Dispatchers.IO).launch { repo.save(point.uri, point.chapter, point.paragraph) }
        }
    }

    private fun closePagedLoader() {
        pagedLoader?.close()
        pagedLoader = null
    }

    init {
        // 全局设置变化即时反映到阅读界面
        viewModelScope.launch {
            settingsRepository.settings.collect { s -> _uiState.update { it.copy(settings = s) } }
        }
    }

    fun load(uri: String, format: DocFormat, displayName: String? = null) {
        viewModelScope.launch {
            closePagedLoader() // 资源卫生：重载前关闭旧 loader
            _uiState.update { it.copy(loading = true, error = null, hasMore = false, loadingMore = false) }
            try {
                if (format == DocFormat.TXT) {
                    // txt 一律走分块加载（M8b，F4.2）：小文件首块即全文，行为与全量解析一致
                    loadTxtPaged(uri, displayName)
                    return@launch
                }
                val name = displayName?.takeIf { it.isNotBlank() } ?: uri.substringAfterLast('/')
                val (document, editable) = loadNonTxt(uri, name, format)
                currentUri = uri
                val restored = progressRepository?.progress(uri)?.first()
                val chapterIndex = restored?.chapter
                    ?.coerceIn(0, (document.chapters.size - 1).coerceAtLeast(0)) ?: 0
                // paragraph 同样需 clamp 到目标章节范围内，防止恢复越界（章节空时归 0）
                val maxParagraph =
                    (document.chapters.getOrNull(chapterIndex)?.paragraphs?.lastIndex ?: 0).coerceAtLeast(0)
                currentParagraph = restored?.paragraph?.coerceIn(0, maxParagraph) ?: 0
                val map = ChapterItemMap(document.chapters)
                itemMap = map
                _uiState.update {
                    it.copy(
                        loading = false,
                        document = document,
                        chapterIndex = chapterIndex,
                        restoreParagraph = currentParagraph,
                        editable = editable,
                        materializedChapterCount = minOf(chapterIndex + 1, document.chapters.size),
                        totalItems = map.totalItems,
                        restoreItem = map.globalItem(chapterIndex, currentParagraph)
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

    /**
     * md/xml/epub 全量加载：读字节 → 解析。M11b（F2.14）epub 优先走缓存解压——
     * 解压到应用私有缓存并从缓存目录解析（`parseFromDir`，正文不整书驻留内存，图片可懒加载）；
     * 缓存目录解析失败（部分条目丢失/损坏）时**删除目录并重新解压一次**，仍失败回退整书字节解析；
     * 缓存未接入（单测）/首次解压失败回退整书字节解析，图片按占位处理，不阻塞阅读。
     */
    private suspend fun loadNonTxt(uri: String, name: String, format: DocFormat): Pair<Document, Boolean> {
        if (format == DocFormat.EPUB) {
            val cache = epubCache
            if (cache != null) {
                val parser = ParserFactory.forFormat(DocFormat.EPUB)
                val dir = runCatchingExtract(cache, uri)
                if (dir != null && parser is EpubParser) {
                    try {
                        // epub 只读（编辑入口仅 md/txt），缓存路径无需字节体积判定 editable
                        return parser.parseFromDir(dir, uri, name) to false
                    } catch (e: ParseException) {
                        // M1（评审）：缓存目录不完整/损坏（container.xml 在但部分条目缺失）→ 删除并重抽一次
                        val retryDir = runCatchingExtract(cache, uri, force = true)
                        if (retryDir != null) {
                            try {
                                return parser.parseFromDir(retryDir, uri, name) to false
                            } catch (e2: ParseException) {
                                // 重抽后仍失败：真实结构问题，回退整书字节解析（给出差异化的原始错误）
                            }
                        }
                    }
                }
            }
        }
        // 整书字节读取属 IO，移到 Dispatchers.IO（L4，评审）；解析本身内部即 IO 调度
        val bytes = withContext(Dispatchers.IO) { fileOpener.open(uri).use { it.readBytes() } }
        return parseBytes(bytes, name, uri, format) to (bytes.size <= MAX_EDITABLE_BYTES)
    }

    /** 缓存解压辅助：正常提取，或 force=true 时先删除旧目录再重抽一次；取消透传，其余失败回 null */
    private suspend fun runCatchingExtract(cache: EpubCache, uri: String, force: Boolean = false): File? {
        if (force) cache.delete(uri)
        return try {
            // runInterruptible：协程取消会中断工作线程，缓存解压段级检查及时止损并清理半成品（C4）
            runInterruptible(Dispatchers.IO) { cache.extract(uri, input = { fileOpener.open(uri) }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // 缓存失败：回退整书字节解析（图片不可用走占位）
        }
    }

    /**
     * txt 分块加载（M8b，F4.2/TC-F4-02）：首块（200 段）即上屏，其余由 [loadMore] 滚动追加。
     * 进度恢复点超出首块时循环追加至覆盖或 EOF（有界）；editable 优先按 FileOpener.length 判定，
     * length 未知（null）时持续读至 EOF 按累计字节修正（保证 TC-F3-09 判定正确）。
     */
    private suspend fun loadTxtPaged(uri: String, displayName: String?) {
        val name = displayName?.takeIf { it.isNotBlank() } ?: uri.substringAfterLast('/')
        val declaredLength = fileOpener.length(uri)
        val loader = withContext(Dispatchers.IO) {
            PagedTextLoader.open(fileOpener.open(uri), charsetOverride = encodingOverride)
        }
        pagedLoader = loader
        currentUri = uri

        val restored = progressRepository?.progress(uri)?.first()
        val target = restored?.paragraph?.coerceAtLeast(0) ?: 0
        // 单 IO 块内连续取块（避免块级线程切换）：首块即上屏所需；恢复点超出首块时追加至覆盖（有界）；
        // length 未知（null）时读至 EOF，按累计字节修正 editable（保证 TC-F3-09 判定正确）
        val paragraphs = ArrayList<String>()
        withContext(Dispatchers.IO) {
            do {
                paragraphs += loader.nextChunk()
            } while (loader.hasMore && (paragraphs.size <= target || declaredLength == null))
        }

        val editable = declaredLength?.let { it <= MAX_EDITABLE_BYTES }
            ?: (loader.bytesRead <= MAX_EDITABLE_BYTES) // length 未知：EOF 后按累计字节修正
        val document = Document(uri, name, DocFormat.TXT, listOf(Chapter(null, paragraphs)))
        val maxParagraph = (paragraphs.size - 1).coerceAtLeast(0)
        currentParagraph = target.coerceIn(0, maxParagraph)
        itemMap = ChapterItemMap(document.chapters)
        _uiState.update {
            it.copy(
                loading = false,
                document = document,
                chapterIndex = 0,
                restoreParagraph = currentParagraph,
                editable = editable,
                hasMore = loader.hasMore,
                loadingMore = false,
                materializedChapterCount = 1,
                totalItems = paragraphs.size,
                restoreItem = currentParagraph
            )
        }
    }

    private suspend fun parseBytes(bytes: ByteArray, name: String, uri: String, format: DocFormat): Document =
        when (val parser = ParserFactory.forFormat(format)) {
            // G1：txt 手动编码覆盖同样作用于全量解析路径（当前 txt 一律走分页，本分支为防御性接线）
            is TxtParser -> parser.parseBytes(bytes, name, uri, charsetOverride = encodingOverride)
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
        flushPendingProgress() // G4-code：切章前把未落盘的滚动进度兜底写一次
        persistProgress(index, 0)
    }

    /** UI 滚动时上报首个可见段落索引，用于阅读进度恢复（F2.8）；落库经 500ms 防抖（G4-code） */
    fun onFirstVisibleParagraphChanged(paragraph: Int) {
        currentParagraph = paragraph.coerceAtLeast(0)
        scheduleProgressPersist(_uiState.value.chapterIndex, currentParagraph)
    }

    /**
     * F2.13（S3 方案①）：md 目录跳转完成后的进度上报——**立即落库**（不经防抖），
     * 保证跳转后立刻退出/杀进程，重开也能回到该标题处。预览模式经目录跳转时 UI 走此上报
     * （预览模式普通滚动不上报，仅目录跳转例外）。
     */
    fun onTocJumpToParagraph(paragraph: Int) {
        val p = paragraph.coerceAtLeast(0)
        currentParagraph = p
        persistProgress(_uiState.value.chapterIndex, p)
    }

    /** UI 完成一次 restoreParagraph 滚动后调用，清除一次性目标 */
    fun consumeRestoreParagraph() = _uiState.update { it.copy(restoreParagraph = null) }

    // ---- F4.7（M12b）：跨章连续滚动的 VM 层坐标系与章级窗口（加法式，既有 API 行为不变）----

    /**
     * 连续滚动触底追加下一章（章级渐进物化）。已到末章时为空操作（末章停止）。
     * 只增不减：已读章不回收（决策日志 D-2026-09-11-03，索引稳定优先）。
     */
    fun appendNextChapter() {
        val total = _uiState.value.document?.chapters?.size ?: return
        _uiState.update {
            it.copy(materializedChapterCount = (it.materializedChapterCount + 1).coerceAtMost(total))
        }
    }

    /**
     * 把物化窗口扩到至少 [chapter] + 1 章（进度条拖动/目录跳转前置，F4.7/F4.8）。
     * 越界 [chapter] 不报错：目标 clamp 到 [0, 章数]，且不缩小当前窗口。
     */
    fun ensureMaterialized(chapter: Int) {
        val total = _uiState.value.document?.chapters?.size ?: return
        _uiState.update {
            val target = (chapter + 1).coerceIn(0, total)
            it.copy(materializedChapterCount = maxOf(it.materializedChapterCount, target))
        }
    }

    /**
     * UI 上报首个可见全局 item → 经 [ChapterItemMap.locate] 换算为 (章, 段)，
     * 当前章跟随滚动位置，并走既有 500ms 防抖落库（进度坐标仍为 `(chapter, paragraph)`，零迁移）。
     */
    fun onFirstVisibleItemChanged(itemIndex: Int) {
        val map = itemMap ?: return
        val pos = map.locate(itemIndex)
        currentParagraph = pos.paragraph
        _uiState.update { it.copy(chapterIndex = pos.chapter) }
        scheduleProgressPersist(pos.chapter, pos.paragraph)
    }

    /** UI 完成一次 restoreItem 滚动后调用，清除一次性目标（与 consumeRestoreParagraph 同型） */
    fun consumeRestoreItem() = _uiState.update { it.copy(restoreItem = null) }

    /**
     * txt 分块加载（M8b，F4.2）：滚动接近底部（余量 [com.sample.docreader.core.parse.PagedTextLoader.PREFETCH_MARGIN] 段）
     * 且 hasMore && !loadingMore 时由 UI 触发，追加下一块到首章段落末尾；非分块路径（md/xml/epub）调用为空操作。
     * 契约：防重入；追加保持段落索引稳定（只 append，不重排）；EOF 后 hasMore=false；
     * length 未知的文件在 EOF 时按累计字节修正 editable。
     */
    fun loadMore() {
        val loader = pagedLoader ?: return // 非分页路径：空操作
        val s = _uiState.value
        if (!s.hasMore || s.loadingMore) return // 防重入
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val chunk = withContext(Dispatchers.IO) { loader.nextChunk() }
                _uiState.update { st ->
                    // load() 重载已替换 loader 时丢弃本次结果，避免污染新文档
                    if (pagedLoader !== loader) return@update st
                    val doc = st.document
                        ?: return@update st.copy(loadingMore = false, hasMore = loader.hasMore)
                    val first = doc.chapters.first()
                    // 只 append：段落索引稳定（itemsIndexed key 为索引，重排会炸 UI）
                    val chapters = listOf(first.copy(paragraphs = first.paragraphs + chunk)) +
                        doc.chapters.drop(1)
                    // F4.7（M12b）：txt 单章追加后同步刷新全局 item 总数（只 append，索引稳定契约不变）
                    val map = ChapterItemMap(chapters)
                    itemMap = map
                    st.copy(
                        document = doc.copy(chapters = chapters),
                        hasMore = loader.hasMore,
                        loadingMore = false,
                        totalItems = map.totalItems
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 追加失败（流被外部关闭等）：停住加载态，已载内容保持可读
                _uiState.update { it.copy(loadingMore = false, hasMore = loader.hasMore) }
            }
        }
    }

    private fun persistProgress(chapter: Int, paragraph: Int) {
        val repo = progressRepository ?: return
        if (currentUri.isEmpty()) return
        viewModelScope.launch { repo.save(currentUri, chapter, paragraph) }
    }

    /** G4-code：滚动进度防抖落库——每段变化仅刷新待写点并重置计时，停顿 500ms 后写最新值 */
    private fun scheduleProgressPersist(chapter: Int, paragraph: Int) {
        val repo = progressRepository ?: return
        if (currentUri.isEmpty()) return
        val point = ProgressPoint(currentUri, chapter, paragraph)
        pendingPoint = point
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(PROGRESS_DEBOUNCE_MS)
            if (pendingPoint == point) pendingPoint = null
            repo.save(point.uri, point.chapter, point.paragraph)
        }
    }

    /** 兜底立即落库待写进度点（切章时调用；退出路径在 onCleared 单独处理，不能丢最后一段） */
    private fun flushPendingProgress() {
        val point = pendingPoint ?: return
        pendingPoint = null
        progressJob?.cancel()
        viewModelScope.launch { progressRepository?.save(point.uri, point.chapter, point.paragraph) }
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

    /**
     * 字体循环（SettingsScreen 循环入口用）：代码→衬线→无衬线；
     * G2（M10）：customFontPath 非空时序列追加 CUSTOM（代码→衬线→无衬线→自定义→回到代码）。
     */
    fun cycleFont() = updateSettings {
        val order = if (it.customFontPath != null) {
            listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS, ReaderFont.CUSTOM)
        } else {
            listOf(ReaderFont.CODE, ReaderFont.SERIF, ReaderFont.SANS)
        }
        it.copy(font = order[(order.indexOf(it.font) + 1).mod(order.size)])
    }

    /** 主题循环：跟随系统→默认白→护眼绿→柔光米黄→夜间黑 */
    fun cycleTheme() = updateSettings {
        val order = listOf(null, ReadingTheme.DEFAULT, ReadingTheme.EYE_CARE,
            ReadingTheme.SOFT_LIGHT, ReadingTheme.NIGHT)
        it.copy(theme = order[(order.indexOf(it.theme) + 1).mod(order.size)])
    }

    // ---- M10 阅读设置面板（F4.4）：直接设置方法（U3 单选/分段选择替代循环）；语义与 cycle/step 一致，即时生效并持久化 ----

    /** 直接设置换行模式（阅读设置面板分段选择，F4.4） */
    fun setWrapMode(mode: WrapMode) = updateSettings { it.copy(wrapMode = mode) }

    /** 直接设置字体（阅读设置面板单选，F4.4/U3） */
    fun setFont(font: ReaderFont) = updateSettings { it.copy(font = font) }

    /** 直接设置阅读主题（null = 跟随系统；阅读设置面板单选，F4.4/U3） */
    fun setTheme(theme: ReadingTheme?) = updateSettings { it.copy(theme = theme) }

    /** 直接设置行距百分值（阅读设置面板单选，F4.4/U3；调用方传 LINE_HEIGHT_STEPS 档位值） */
    fun setLineHeight(percent: Int) = updateSettings { it.copy(lineHeightPercent = percent) }

    /**
     * G2（M10）：自定义字体 SAF 导入成功后调用——切到 CUSTOM 并持久化路径，
     * 同时删除上一版旧字体文件（孤儿文件清理）。**切换离开 CUSTOM 不删文件**（允许切回），故只在这里清理。
     */
    fun applyCustomFont(path: String) {
        val oldPath = _uiState.value.settings.customFontPath
        updateSettings { it.copy(font = ReaderFont.CUSTOM, customFontPath = path) }
        if (oldPath != null && oldPath != path) File(oldPath).delete()
    }

    /**
     * G1（F2.6）：txt 手动切换编码。[charsetName] 为 null 时恢复自动探测；
     * 选择后即时重载当前 txt（小文件 parseBytes 路径与大文件 PagedTextLoader 路径统一经 load 生效）。
     * 仅会话内有效，不全局持久化。非 txt 文档调用只更新状态不触发重载。
     */
    fun setEncodingOverride(charsetName: String?) {
        encodingOverride = charsetName?.let { name ->
            runCatching { charset(name) }.getOrNull()
        }
        _uiState.update { it.copy(encodingOverride = encodingOverride?.name()) }
        val doc = _uiState.value.document
        if (doc != null && doc.format == DocFormat.TXT && currentUri.isNotEmpty()) {
            load(currentUri, DocFormat.TXT, doc.name)
        }
    }

    companion object {
        val FONT_SIZE_STEPS = listOf(12, 14, 16, 18, 20, 22, 24)
        val LINE_HEIGHT_STEPS = listOf(120, 150, 180, 200)

        /** 可编辑文件大小上限（设计§8：默认 2MB 可编辑，超出只读；真机 5MB 编辑 ANR） */
        const val MAX_EDITABLE_BYTES = 2 * 1024 * 1024

        /** 滚动进度落库防抖时长（G4-code）：停顿 500ms 后一次性写 Room */
        const val PROGRESS_DEBOUNCE_MS = 500L
    }
}
