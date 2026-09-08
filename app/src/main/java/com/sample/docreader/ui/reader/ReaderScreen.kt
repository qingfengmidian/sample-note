package com.sample.docreader.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sample.docreader.core.io.DelegatingFileOpener
import com.sample.docreader.core.io.EpubCache
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.TocEntry
import com.sample.docreader.core.model.WrapMode
import com.sample.docreader.core.parse.PagedTextLoader
import com.sample.docreader.core.progress.ProgressRepository
import com.sample.docreader.core.recent.AppDatabase
import com.sample.docreader.core.render.MarkdownParser
import com.sample.docreader.core.settings.SettingsRepository
import com.sample.docreader.ui.FontImportResult
import com.sample.docreader.ui.importCustomFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阅读界面（设计§4，M10 改版 F4.4/F4.5）：
 * 顶栏（返回 + 章节名/书名 + epub 章节图标 + ⋯ 菜单）+ 全屏正文区；
 * 阅读设置收纳为「⋯ → 阅读设置」ModalBottomSheet 面板（排版/字体/主题三组，U3 单选/分段选择）；
 * epub 章节面板升级为 ModalBottomSheet 并内置上一章/下一章翻章。**不常驻底部工具条**。
 */

/** 超大文件（>2MB）编辑入口置灰说明文字（TC-F3-09，设计§8） */
const val EDIT_HINT_TOO_LARGE = "编辑（文件超过 2MB，仅可只读）"

/** G1（F2.6）：手动编码覆盖可选项（charset 名，null = 自动探测）；仅 txt 显示 */
internal val ENCODING_OPTIONS: List<Pair<String?, String>> = listOf(
    null to "自动探测",
    "UTF-8" to "UTF-8",
    "UTF-16LE" to "UTF-16LE",
    "UTF-16BE" to "UTF-16BE",
    "GBK" to "GBK",
    "ISO-8859-1" to "Latin-1"
)

@Composable
fun ReaderScreen(
    uri: String,
    format: DocFormat,
    onBack: () -> Unit,
    displayName: String? = null,
    onEdit: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: ReaderViewModel = viewModel(factory = viewModelFactory {
        initializer {
            ReaderViewModel(
                DelegatingFileOpener(context),
                SettingsRepository(context),
                ProgressRepository(AppDatabase.get(context.applicationContext)),
                // M11b（F2.14）：epub 解压缓存目录（应用私有 cache/epub/，见设计 §3.2-5）
                EpubCache(File(context.cacheDir, "epub"))
            )
        }
    })
    LaunchedEffect(uri) {
        if (viewModel.uiState.value.document == null) {
            viewModel.load(uri, format, displayName)
        }
    }
    // 编辑保存成功后的重载（TC-F3-02）：观察保存信号，命中本 uri 即消费并重载一次
    val reloadUris by ReaderReloadNotifier.savedUris.collectAsStateWithLifecycle()
    LaunchedEffect(uri, reloadUris) {
        if (uri in reloadUris) {
            ReaderReloadNotifier.consume(uri)
            viewModel.load(uri, format, displayName)
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 「导入字体」入口（F4.4 设置面板，G2）：复用 SettingsScreen 的 SAF 导入流程，
    // 成功后经 ViewModel.applyCustomFont 切到 CUSTOM 并清理旧字体孤儿文件
    val scope = rememberCoroutineScope()
    var fontImportMessage by remember { mutableStateOf<String?>(null) }
    val fontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            fontImportMessage = null
            val result = withContext(Dispatchers.IO) {
                runCatching { importCustomFont(context, picked) }
                    .getOrElse { e -> FontImportResult(failure = e.message ?: "导入失败") }
            }
            if (result.path != null) {
                viewModel.applyCustomFont(result.path)
                fontImportMessage = "自定义字体已导入"
            } else {
                fontImportMessage = result.failure
            }
        }
    }

    BackHandler { onBack() }
    ReaderContent(
        state = state,
        onBack = onBack,
        onFontSizeStep = viewModel::stepFontSize,
        onSetWrapMode = viewModel::setWrapMode,
        onSetLineHeight = viewModel::setLineHeight,
        onSetFont = viewModel::setFont,
        onSetTheme = viewModel::setTheme,
        onSetEncoding = viewModel::setEncodingOverride,
        onImportFont = {
            fontLauncher.launch(
                arrayOf(
                    "application/x-font-ttf",
                    "application/x-font-otf",
                    "font/ttf",
                    "font/otf",
                    "application/octet-stream"
                )
            )
        },
        fontImportMessage = fontImportMessage,
        onSelectChapter = viewModel::selectChapter,
        onPrevChapter = { viewModel.selectChapter(state.chapterIndex - 1) },
        onNextChapter = { viewModel.selectChapter(state.chapterIndex + 1) },
        onFirstVisibleParagraphChanged = viewModel::onFirstVisibleParagraphChanged,
        onRestoreParagraphConsumed = viewModel::consumeRestoreParagraph,
        onLoadMore = viewModel::loadMore,
        onTocJumpToParagraph = viewModel::onTocJumpToParagraph,
        onEdit = onEdit
    )
}

/**
 * 无状态阅读界面主体：状态与回调全部外提，便于 Compose UI Test 直接驱动。
 * 换行模式切换位置保持（F2.2/TC-UI-01）：切换前记录首可见段落索引，重组后滚回该段落。
 * M10（F4.4）：设置类操作不再平铺底部工具条，全部收纳进 ⋯ 菜单唤起的阅读设置面板；
 * 面板内换行分段选择/行距单选/字体单选/主题单选/编码单选（G1，仅 txt）均为直接设置回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onBack: () -> Unit,
    onFontSizeStep: (Int) -> Unit,
    onSetWrapMode: (WrapMode) -> Unit = {},
    onSetLineHeight: (Int) -> Unit = {},
    onSetFont: (ReaderFont) -> Unit = {},
    onSetTheme: (ReadingTheme?) -> Unit = {},
    onSetEncoding: (String?) -> Unit = {},
    onImportFont: () -> Unit = {},
    fontImportMessage: String? = null,
    listState: LazyListState = rememberLazyListState(),
    onSelectChapter: ((Int) -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    onFirstVisibleParagraphChanged: ((Int) -> Unit)? = null,
    onRestoreParagraphConsumed: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    // F2.13（S3 方案①）：目录跳转完成后的进度上报回调（预览模式跳转也必须上报，供 VM 落库）
    onTocJumpToParagraph: ((Int) -> Unit)? = null
) {
    val settings = state.settings
    val theme = resolveReadingTheme(settings.theme)
    val chapters = state.document?.chapters.orEmpty()
    val chapter = chapters.getOrNull(state.chapterIndex)
    val paragraphs = chapter?.paragraphs
    val toc = state.document?.toc.orEmpty() // F2.13：md 目录锚点（xml/txt/epub 为空）
    val isEpub = state.document?.format == DocFormat.EPUB
    val isMd = state.document?.format == DocFormat.MD
    val isTxt = state.document?.format == DocFormat.TXT
    // rememberSaveable：旋转/重建后不丢预览状态
    var mdPreview by rememberSaveable { mutableStateOf(false) }
    val mdBlocks = remember(paragraphs, isMd) {
        if (isMd) MarkdownParser.parse(paragraphs.orEmpty()) else emptyList()
    }
    // F2.13/U4：块↔段落索引映射工具（preview item 是块、源码 item 是段落）
    val mapping = MdViewMapping

    // F2.13/U4 状态：md 目录面板开关、目录跳转段落索引（一次性）、源码↔预览切换滚动目标（一次性，-1 = 空）
    var tocSheetOpen by remember { mutableStateOf(false) }
    var tocJumpParagraph by remember { mutableIntStateOf(-1) }
    var switchScrollTarget by remember { mutableIntStateOf(-1) }

    // U4：源码↔预览切换位置映射——两个视图共享同一 listState 但 item 语义不同（源码=段落、预览=块），
    // 切换前记录当前位置并在重组后滚到经块↔段落映射换算的目标（保持一致阅读语义，而非同序号不同内容）。
    LaunchedEffect(mdPreview) {
        if (switchScrollTarget >= 0) {
            listState.scrollToItem(switchScrollTarget)
            switchScrollTarget = -1
        }
    }

    // F2.13（S4/S3）：目录跳转——目标为段落索引；源码直接滚段落，预览经块↔段落映射换算后滚动。
    // 跳转后把段落索引上报进度（预览模式下普通滚动不上报，但目录跳转必须上报，保证重开回到该标题）。
    LaunchedEffect(tocJumpParagraph) {
        if (tocJumpParagraph >= 0) {
            val paragraph = tocJumpParagraph
            tocJumpParagraph = -1
            val target = if (mdPreview) mapping.blockIndexForParagraph(mdBlocks, paragraph) else paragraph
            listState.scrollToItem(target)
            onTocJumpToParagraph?.invoke(paragraph)
        }
    }

    // U2（M10）：状态栏/导航栏颜色跟随当前阅读主题背景，**图标明暗也跟随**（深色主题→浅色图标，
    // 浅色主题→深色图标；否则系统深色模式下强制默认白会出现白图标贴浅底的对比度事故）；
    // 跟随系统（theme == null）或离开阅读页时恢复 enableEdgeToEdge 的系统默认值。经 LocalView.context 取 window。
    val view = LocalView.current
    DisposableEffect(settings.theme) {
        val window = (view.context as? Activity)?.window
        val explicitTheme = settings.theme
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        // 设置前捕获系统默认外观，onDispose 原样恢复
        val prevLightStatus = insetsController?.isAppearanceLightStatusBars
        val prevLightNav = insetsController?.isAppearanceLightNavigationBars
        if (window != null && explicitTheme != null) {
            val barColor = explicitTheme.background.toArgb()
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
            val lightBars = explicitTheme.background.luminance() > 0.5f
            insetsController?.isAppearanceLightStatusBars = lightBars
            insetsController?.isAppearanceLightNavigationBars = lightBars
        }
        onDispose {
            window?.statusBarColor = Color.Transparent.toArgb()
            window?.navigationBarColor = Color.Transparent.toArgb()
            if (prevLightStatus != null) insetsController?.isAppearanceLightStatusBars = prevLightStatus
            if (prevLightNav != null) insetsController?.isAppearanceLightNavigationBars = prevLightNav
        }
    }

    // rememberSaveable：旋转恰好发生在切换重组间隙时不丢位置保持目标（Int 可直接 saveable）
    var pendingParagraph by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(settings.wrapMode) {
        if (pendingParagraph >= 0) {
            listState.scrollToItem(pendingParagraph)
            pendingParagraph = -1
        }
    }

    // 阅读进度：首个可见段落变化时上报（供 ProgressRepository 持久化，F2.8）。
    // md 预览模式下 item 是 Markdown 块而非段落，索引与段落进度不同义，不上报（否则污染源码视图进度）。
    LaunchedEffect(listState, mdPreview) {
        if (mdPreview) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onFirstVisibleParagraphChanged?.invoke(it) }
    }

    // 加载恢复/切章后的一次性滚动目标
    LaunchedEffect(state.restoreParagraph) {
        state.restoreParagraph?.let { p ->
            listState.scrollToItem(p)
            onRestoreParagraphConsumed?.invoke()
        }
    }

    // txt 分块加载（M8b，F4.2）：滚动至距底 PREFETCH_MARGIN 段内时预取下一块。
    // distinctUntilChanged 避免重组/同索引重复触发；hasMore/loadingMore 经 rememberUpdatedState 取最新值。
    val latestState by rememberUpdatedState(state)
    val latestParagraphCount by rememberUpdatedState(paragraphs?.size ?: 0)
    LaunchedEffect(listState, isTxt) {
        if (!isTxt) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val s = latestState
                if (lastVisible >= 0 && s.hasMore && !s.loadingMore &&
                    lastVisible >= latestParagraphCount - PagedTextLoader.PREFETCH_MARGIN
                ) {
                    onLoadMore?.invoke()
                }
            }
    }

    var chapterSheetOpen by remember { mutableStateOf(false) }
    var settingsSheetOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        chapter?.title?.takeIf { it.isNotBlank() } ?: state.document?.name.orEmpty(),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // F4.5：epub 章节入口上移顶栏图标
                    if (isEpub) {
                        IconButton(onClick = { chapterSheetOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "章节列表")
                        }
                    }
                    // F2.13（TC-F2-20b）：md 目录入口与 epub 章节同位置；toc 为空时隐藏
                    if (isMd && toc.isNotEmpty()) {
                        IconButton(onClick = { tocSheetOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "菜单")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (isMd) {
                            DropdownMenuItem(
                                text = { Text(if (mdPreview) "源码" else "预览") },
                                onClick = {
                                    menuOpen = false
                                    // U4：切换前记录语义位置，重组后经 LaunchedEffect(mdPreview) 滚到映射目标
                                    val current = listState.firstVisibleItemIndex.coerceAtLeast(0)
                                    switchScrollTarget = if (mdPreview) {
                                        mapping.paragraphIndexForBlock(mdBlocks, current) // 预览→源码：块起始段落
                                    } else {
                                        mapping.blockIndexForParagraph(mdBlocks, current) // 源码→预览：段落所属块
                                    }
                                    mdPreview = !mdPreview
                                }
                            )
                        }
                        // 编辑入口仅 md/txt 显示（F3.1/F4.1：epub/xml 只读）；
                        // 超出可编辑上限（>2MB，ReaderViewModel.MAX_EDITABLE_BYTES）置灰并附说明，仅可只读浏览（TC-F3-09，设计§8）
                        if (isMd || isTxt) {
                            DropdownMenuItem(
                                text = { Text(if (state.editable) "编辑" else EDIT_HINT_TOO_LARGE) },
                                enabled = state.editable,
                                onClick = {
                                    menuOpen = false
                                    onEdit?.invoke()
                                }
                            )
                        }
                        // F4.4：阅读设置入口——唤起 ModalBottomSheet 设置面板
                        DropdownMenuItem(
                            text = { Text("阅读设置") },
                            onClick = {
                                menuOpen = false
                                settingsSheetOpen = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.foreground,
                    navigationIconContentColor = theme.foreground,
                    actionIconContentColor = theme.foreground
                )
            )
        }
        // M10（F4.4）：不常驻底部工具条，正文区全屏
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // U7（M10）：错误态附「返回」操作引导（走现有返回导航）
                state.error != null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.error.orEmpty(), color = theme.foreground)
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "返回" }
                    ) { Text("返回") }
                }
                // 加载中：document 为 null 且无错误（避免 0 章 epub 误判为加载中而永久转圈）
                state.loading || state.document == null ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                // TC-F2-24：空文件/空章节（0 字节、全空段落或 epub 空 spine）显示空态提示。
                // M3：某章 images 非空（纯图章，解析层已合成空宿主段落）时不得判空——需渲染图片；
                // 真正无图真空章仍走既有空态（TC-F2-24 不回归）。
                (paragraphs.isNullOrEmpty() || paragraphs.all { it.isBlank() }) &&
                    chapter?.images.isNullOrEmpty() != false -> Text(
                    "文件为空",
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                isMd && mdPreview -> MarkdownPreview(mdBlocks, settings, listState, theme)
                else -> ReaderTextView(
                    paragraphs.orEmpty(),
                    settings,
                    listState,
                    // F2.14：epub 章节图片挂段落 item 内渲染；图片文件根 = epub 解压缓存目录（imageRoot）
                    images = chapter?.images.orEmpty(),
                    imageRoot = state.document?.imageRoot,
                    // F2.15：epub 章节段样式提示（仅 epub 非空；txt/md/xml 空表零影响）
                    styles = chapter?.styles.orEmpty()
                )
            }
        }
    }

    // F4.5：章节面板升级为 ModalBottomSheet（原 AlertDialog），底部内置上一章/下一章翻章
    if (chapterSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { chapterSheetOpen = false },
            containerColor = theme.background,
            contentColor = theme.foreground
        ) {
            ChapterSheetContent(
                chapters = chapters,
                currentIndex = state.chapterIndex,
                onSelect = { index ->
                    chapterSheetOpen = false
                    onSelectChapter?.invoke(index)
                },
                // 面板内翻章后停留在面板可继续选（F4.5）
                onPrev = { onPrevChapter?.invoke() },
                onNext = { onNextChapter?.invoke() }
            )
        }
    }

    // F2.13：md 目录面板（复用章节面板交互模式，F4.5）。当前标题高亮（S11：首可见段落
    // 位于第一个目录标题之前时无高亮，selectedIndex = -1）；点击目录项关闭面板并跳转（经 tocJumpParagraph）。
    if (tocSheetOpen) {
        val visibleParagraph = if (mdPreview)
            mapping.paragraphIndexForBlock(mdBlocks, listState.firstVisibleItemIndex.coerceAtLeast(0))
        else
            listState.firstVisibleItemIndex.coerceAtLeast(0)
        val selectedTocIndex = mapping.tocIndexAtParagraph(visibleParagraph, toc)
        ModalBottomSheet(
            onDismissRequest = { tocSheetOpen = false },
            containerColor = theme.background,
            contentColor = theme.foreground
        ) {
            TocSheetContent(
                toc = toc,
                selectedIndex = selectedTocIndex,
                onSelect = { entry ->
                    tocSheetOpen = false
                    tocJumpParagraph = entry.paragraphIndex
                }
            )
        }
    }

    // F4.4：阅读设置面板（ModalBottomSheet，排版/字体/主题三组，面板跟随当前阅读主题配色）
    if (settingsSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { settingsSheetOpen = false },
            containerColor = theme.background,
            contentColor = theme.foreground
        ) {
            ReaderSettingsSheetContent(
                settings = settings,
                isTxt = isTxt,
                encodingOverride = state.encodingOverride,
                onSelectWrapMode = { mode ->
                    // 换行切换位置保持（F2.2/TC-UI-01）：切换前记录首可见段落索引，重组后由上方 LaunchedEffect 滚回
                    if (mode != settings.wrapMode) pendingParagraph = listState.firstVisibleItemIndex
                    onSetWrapMode(mode)
                },
                onFontSizeStep = onFontSizeStep,
                onSelectLineHeight = onSetLineHeight,
                onSelectFont = onSetFont,
                onSelectTheme = onSetTheme,
                onSelectEncoding = onSetEncoding,
                onImportFont = onImportFont,
                fontImportMessage = fontImportMessage
            )
        }
    }
}

/**
 * 章节面板主体（F4.5）：章节列表（高亮/缩进/selected 语义不变，见 [ChapterListContent]）
 * + 底部上一章/下一章翻章按钮（边界章节置灰）。
 * 从 ModalBottomSheet 抽出以便 Robolectric 直驱（同 AlertDialog 时代的 ChapterListContent 做法）。
 */
@Composable
internal fun ChapterSheetContent(
    chapters: List<com.sample.docreader.core.model.Chapter>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "章节",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        ChapterListContent(chapters, currentIndex, onSelect)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onPrev,
                enabled = currentIndex > 0,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "上一章" }
            ) { Text("上一章") }
            TextButton(
                onClick = onNext,
                enabled = currentIndex < chapters.lastIndex,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = "下一章" }
            ) { Text("下一章") }
        }
    }
}

/**
 * 阅读设置面板主体（F4.4，U3）：排版 / 字体 / 主题三组，单选带选中勾与 stateDescription，
 * 全部可点击目标 ≥ 48dp。与 SettingsScreen 全局页同一 SettingsRepository 数据源（F3.3 全局共享不变）。
 * 从 ModalBottomSheet 抽出以便 Robolectric 直驱（设计 §4：面板内控件存在与回调触发为断言对象）。
 */
@Composable
internal fun ReaderSettingsSheetContent(
    settings: ReadingSettings,
    isTxt: Boolean,
    encodingOverride: String?,
    onSelectWrapMode: (WrapMode) -> Unit,
    onFontSizeStep: (Int) -> Unit,
    onSelectLineHeight: (Int) -> Unit,
    onSelectFont: (ReaderFont) -> Unit,
    onSelectTheme: (ReadingTheme?) -> Unit,
    onSelectEncoding: (String?) -> Unit,
    onImportFont: () -> Unit,
    fontImportMessage: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        // ---- 排版组：换行模式（分段选择）/ 字号（A- A+）/ 行距（单选）/ 编码（G1，仅 txt） ----
        Text("排版", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SheetRadioOption(
                label = "自动换行",
                selected = settings.wrapMode == WrapMode.WRAP,
                onClick = { onSelectWrapMode(WrapMode.WRAP) },
                modifier = Modifier.weight(1f)
            )
            SheetRadioOption(
                label = "不换行",
                selected = settings.wrapMode == WrapMode.NO_WRAP,
                onClick = { onSelectWrapMode(WrapMode.NO_WRAP) },
                modifier = Modifier.weight(1f)
            )
        }
        // 字号：A- / 当前值 sp / A+（档位与端点 clamp 见 ReaderViewModel.stepFontSize）
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("字号", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${settings.fontSizeSp}sp", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = { onFontSizeStep(-1) },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "减小字号" }
            ) { Text("A-") }
            TextButton(
                onClick = { onFontSizeStep(1) },
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "增大字号" }
            ) { Text("A+") }
        }
        // 行距单选（U3：不再循环）：120/150/180/200%
        ReaderViewModel.LINE_HEIGHT_STEPS.forEach { percent ->
            SheetRadioOption(
                label = "行距 $percent%",
                selected = settings.lineHeightPercent == percent,
                onClick = { onSelectLineHeight(percent) }
            )
        }
        // G1（F2.6）：编码手动切换，仅 txt 显示；自动探测时无额外探测结果展示
        if (isTxt) {
            Text("编码", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            ENCODING_OPTIONS.forEach { (charsetName, label) ->
                SheetRadioOption(
                    label = label,
                    selected = encodingOverride == charsetName,
                    onClick = { onSelectEncoding(charsetName) }
                )
            }
        }

        // ---- 字体组（U3 单选）：代码 / 衬线 / 无衬线 / 自定义（仅 customFontPath 非空时出现）+ 导入字体 ----
        Text("字体", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        val fontOptions = buildList {
            add(ReaderFont.CODE)
            add(ReaderFont.SERIF)
            add(ReaderFont.SANS)
            if (settings.customFontPath != null) add(ReaderFont.CUSTOM) // G2：导入后 CUSTOM 可再次选中
        }
        fontOptions.forEach { font ->
            SheetRadioOption(
                label = fontLabel(font),
                selected = settings.font == font,
                onClick = { onSelectFont(font) }
            )
        }
        TextButton(
            onClick = onImportFont,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "导入字体" }
        ) { Text("导入自定义字体（ttf/otf）") }
        fontImportMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // ---- 主题组（U3 单选，色块 + 文字，选中勾） ----
        Text("主题", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        THEME_OPTIONS.forEach { (value, label) ->
            SheetRadioOption(
                label = label,
                selected = settings.theme == value,
                onClick = { onSelectTheme(value) },
                leading = value?.let { themeValue ->
                    {
                        // 主题色块预览（背景色块 + 细边框，夜间黑在浅底上也可辨认）
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(24.dp)
                                .background(themeValue.background)
                                .border(1.dp, themeValue.foreground)
                        )
                    }
                }
            )
        }
    }
}

/** 主题单选项（null = 跟随系统） */
private val THEME_OPTIONS: List<Pair<ReadingTheme?, String>> = listOf(
    null to "跟随系统",
    ReadingTheme.DEFAULT to "默认白",
    ReadingTheme.EYE_CARE to "护眼绿",
    ReadingTheme.SOFT_LIGHT to "柔光米黄",
    ReadingTheme.NIGHT to "夜间黑"
)

/**
 * 面板单选项（U3）：Modifier.selectable + RadioButton 角色（自带 selected 语义）+ stateDescription，
 * 选中项末尾显示勾；整行 ≥ 48dp。
 */
@Composable
private fun SheetRadioOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { stateDescription = if (selected) "已选中" else "未选中" }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

/** 章节列表主体：按 Chapter.level 缩进展示嵌套层级，当前章节高亮（主色+加粗+selected 语义），点击跳转 */
@Composable
internal fun ChapterListContent(
    chapters: List<com.sample.docreader.core.model.Chapter>,
    currentIndex: Int,
    onSelect: (Int) -> Unit
) {
    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
        itemsIndexed(chapters, key = { index, _ -> index }) { index, chapter ->
            val isCurrent = index == currentIndex
            Text(
                // 与 topBar 一致：空白标题兜底"第 N 章"
                chapter.title?.takeIf { it.isNotBlank() } ?: "第 ${index + 1} 章",
                // 当前章节高亮（加粗 + 主色）
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(index) }
                    .semantics { selected = isCurrent } // 供无障碍与 UI 测试识别当前章节
                    .padding(
                        start = ((chapter.level - 1) * 16).dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 12.dp
                    )
            )
        }
    }
}

/**
 * md 目录面板主体（F2.13）：标题 + [TocListContent]。
 * 从 ModalBottomSheet 抽出以便 Robolectric 直驱（同章节面板的做法，TC-F2-20a/b）。
 */
@Composable
internal fun TocSheetContent(
    toc: List<TocEntry>,
    selectedIndex: Int,
    onSelect: (TocEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        TocListContent(toc, selectedIndex, onSelect)
    }
}

/**
 * md 目录列表主体（F2.13）：按 level 缩进展示 H1~H6 层级，当前标题高亮
 * （主色+加粗+selected 语义，S11：selectedIndex = -1 时无高亮项）；点击目标 ≥ 48dp。
 */
@Composable
internal fun TocListContent(
    toc: List<TocEntry>,
    selectedIndex: Int,
    onSelect: (TocEntry) -> Unit
) {
    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
        itemsIndexed(toc, key = { index, _ -> index }) { index, entry ->
            val isCurrent = index == selectedIndex
            Text(
                entry.title,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable { onSelect(entry) }
                    .semantics { selected = isCurrent } // 供无障碍与 UI 测试识别当前标题
                    .padding(
                        start = ((entry.level - 1) * 16).dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    )
            )
        }
    }
}

private fun fontLabel(font: ReaderFont): String = when (font) {
    ReaderFont.CODE -> "代码"
    ReaderFont.SERIF -> "衬线"
    ReaderFont.SANS -> "无衬线"
    ReaderFont.CUSTOM -> "自定义"
}
