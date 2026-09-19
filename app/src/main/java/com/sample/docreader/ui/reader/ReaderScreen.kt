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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
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
import com.sample.docreader.R
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

/** G1（F2.6）：手动编码覆盖可选项（charset 名，null = 自动探测）；仅 txt 显示。
 *  M14（F4.6）：null（自动探测）标签在渲染处经 stringResource 本地化。 */
internal val ENCODING_OPTIONS: List<String?> = listOf(
    null,
    "UTF-8",
    "UTF-16LE",
    "UTF-16BE",
    "GBK",
    "ISO-8859-1"
)

@Composable
fun ReaderScreen(
    uri: String,
    format: DocFormat,
    onBack: () -> Unit,
    displayName: String? = null,
    onEdit: () -> Unit = {},
    // F4.10/M18：⋮ 菜单「问 AI」兜底入口，剪贴板文本与发送由 MainActivity 统一处理
    onAskAi: () -> Unit = {}
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
                    .getOrElse { FontImportResult(failure = context.getString(R.string.reader_font_import_failed)) }
            }
            if (result.path != null) {
                viewModel.applyCustomFont(result.path)
                fontImportMessage = context.getString(R.string.reader_font_imported)
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
        // F4.7（M12c）：滚动上报为**全局 item**，VM 经 ChapterItemMap.locate 换算进度坐标（单章时等价段落）
        onFirstVisibleParagraphChanged = viewModel::onFirstVisibleItemChanged,
        onRestoreParagraphConsumed = viewModel::consumeRestoreParagraph,
        onLoadMore = viewModel::loadMore,
        onTocJumpToParagraph = viewModel::onTocJumpToParagraph,
        // M12c：章级渐进物化 + epub 目录锚点跳转 + 全局 item 恢复
        onAppendNextChapter = viewModel::appendNextChapter,
        onEnsureMaterialized = viewModel::ensureMaterialized,
        onTocJumpToItem = viewModel::onTocJumpToItem,
        onRestoreItemConsumed = viewModel::consumeRestoreItem,
        onEdit = onEdit,
        onAskAi = onAskAi
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
    onTocJumpToParagraph: ((Int) -> Unit)? = null,
    // M12c（F4.7/F4.8/F2.16）：章级渐进物化、epub 目录锚点跳转、全局 item 恢复目标
    onAppendNextChapter: (() -> Unit)? = null,
    onEnsureMaterialized: ((Int) -> Unit)? = null,
    onTocJumpToItem: ((Int) -> Unit)? = null,
    onRestoreItemConsumed: (() -> Unit)? = null,
    // F4.10/M18：⋮ 菜单「问 AI」兜底入口
    onAskAi: (() -> Unit)? = null
) {
    val settings = state.settings
    val theme = resolveReadingTheme(settings.theme)
    // M14（F4.6）：错误态返回操作文案（错误态分支与顶栏共用同一本地化值）
    val backLabel = stringResource(R.string.common_back_cd)
    // M13（F4.4/F4.5）：面板/菜单强调色随阅读主题（对 theme.background 对比度 ≥4.5:1），不取 colorScheme
    val accent = theme.foreground
    val document = state.document
    val chapters = document?.chapters.orEmpty()
    val chapter = chapters.getOrNull(state.chapterIndex)
    val paragraphs = chapter?.paragraphs
    val toc = document?.toc.orEmpty() // F2.13/F2.16：md 标题目录 / epub 文件自带目录
    val isEpub = document?.format == DocFormat.EPUB
    val isMd = document?.format == DocFormat.MD
    val isTxt = document?.format == DocFormat.TXT
    // F4.7（M12c）：全书 item 前缀和映射；物化窗口以 VM 状态为准（count 0 为未初始化/测试直驱，
    // 至少物化当前章，避免正文空白），只组装前 materializedCount 章。
    val itemMap = remember(document) { ChapterItemMap(chapters) }
    val materializedCount = if (chapters.isEmpty()) 0
        else maxOf(state.materializedChapterCount, state.chapterIndex + 1, 1)
    val items = remember(document, materializedCount) {
        assembleReaderItems(chapters, materializedCount)
    }
    // 空态判定上移到整本（连续滚动下不再只看当前章）：全空段落且无图才判空（M3 纯图章不回归）
    val isEmptyDoc = chapters.isEmpty() ||
        chapters.all { ch -> ch.paragraphs.all { it.isBlank() } && ch.images.isEmpty() }
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
    // F4.7/F4.8：目录/进度条跳转的全局 item 目标（一次性；未物化时待窗口扩展后消费）
    var pendingItemScroll by remember { mutableIntStateOf(-1) }

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

    // F4.7（M12c）：目录/进度条跳转的一次性全局 item 滚动目标——未物化时目标超出当前 item 数，
    // 待 ensureMaterialized 触发重组、items 增长后消费（同一坐标只滚一次）。
    LaunchedEffect(items.size, pendingItemScroll) {
        val target = pendingItemScroll
        if (target >= 0 && target < items.size) {
            listState.scrollToItem(target)
            pendingItemScroll = -1
        }
    }

    // 加载/切章恢复的一次性滚动目标：全局 item 优先（epub 多章正确坐标），
    // restoreParagraph 作为单章（md/txt/xml）与既有测试接缝的兼容路径保留。
    LaunchedEffect(state.restoreItem, state.restoreParagraph, items.size) {
        val targetItem = state.restoreItem
        val targetParagraph = state.restoreParagraph
        if (targetItem != null && targetItem in 0 until items.size) {
            listState.scrollToItem(targetItem)
            onRestoreItemConsumed?.invoke()
            if (targetParagraph != null) onRestoreParagraphConsumed?.invoke()
        } else if (!isEpub && targetParagraph != null) {
            listState.scrollToItem(targetParagraph)
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

    // F4.7（M12c）：非 txt（epub/md/xml）触底追加下一章——滚近已物化尾部（PREFETCH_MARGIN）且仍有
    // 未物化章时 appendNextChapter；txt 走上方分块 loadMore，两路径互斥不重复触发。
    val latestItemsSize by rememberUpdatedState(items.size)
    val latestChapterCount by rememberUpdatedState(chapters.size)
    LaunchedEffect(listState, isTxt) {
        if (isTxt) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val s = latestState
                if (lastVisible >= 0 &&
                    lastVisible >= latestItemsSize - PagedTextLoader.PREFETCH_MARGIN &&
                    s.materializedChapterCount < latestChapterCount
                ) {
                    onAppendNextChapter?.invoke()
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
                    // R3（F2.16）：只显示当前章匹配到的目录条目标题；无匹配留空（**不再回落书名/「第 N 章」**）
                    Text(
                        chapter?.title?.takeIf { it.isNotBlank() }.orEmpty(),
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                actions = {
                    // F4.5/F2.16：epub 章节/目录入口上移顶栏图标；文件无目录（toc 空）时隐藏，不做生成式兜底
                    if (isEpub && toc.isNotEmpty()) {
                        IconButton(onClick = { chapterSheetOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.reader_chapters_cd))
                        }
                    }
                    // F2.13（TC-F2-20b）：md 目录入口与 epub 章节同位置；toc 为空时隐藏
                    if (isMd && toc.isNotEmpty()) {
                        IconButton(onClick = { tocSheetOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.reader_toc_cd))
                        }
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.reader_menu_cd))
                    }
                    // M13（F4.4/F4.5）：菜单容器/文字/禁用态随阅读主题（不取 colorScheme，避免明暗错配）
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = theme.background
                    ) {
                        if (isMd) {
                            DropdownMenuItem(
                                text = { Text(if (mdPreview) stringResource(R.string.reader_menu_source) else stringResource(R.string.reader_menu_preview)) },
                                colors = MenuDefaults.itemColors(
                                    textColor = theme.foreground,
                                    disabledTextColor = theme.foreground.copy(alpha = 0.38f)
                                ),
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
                                text = {
                                    Text(
                                        if (state.editable) stringResource(R.string.reader_menu_edit)
                                        else stringResource(R.string.reader_edit_too_large)
                                    )
                                },
                                enabled = state.editable,
                                colors = MenuDefaults.itemColors(
                                    textColor = theme.foreground,
                                    disabledTextColor = theme.foreground.copy(alpha = 0.38f)
                                ),
                                onClick = {
                                    menuOpen = false
                                    onEdit?.invoke()
                                }
                            )
                        }
                        // F4.4：阅读设置入口——唤起 ModalBottomSheet 设置面板
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_menu_settings)) },
                            colors = MenuDefaults.itemColors(
                                textColor = theme.foreground,
                                disabledTextColor = theme.foreground.copy(alpha = 0.38f)
                            ),
                            onClick = {
                                menuOpen = false
                                settingsSheetOpen = true
                            }
                        )
                        // F4.10/M18：问 AI 兜底入口（在「阅读设置」之后），走 MainActivity 统一发送路径
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reader_menu_ask_ai)) },
                            colors = MenuDefaults.itemColors(
                                textColor = theme.foreground,
                                disabledTextColor = theme.foreground.copy(alpha = 0.38f)
                            ),
                            onClick = {
                                menuOpen = false
                                onAskAi?.invoke()
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
                    Text(readerErrorText(state.error), color = theme.foreground)
                    TextButton(
                        onClick = onBack,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = accent,
                            disabledContentColor = accent.copy(alpha = 0.38f)
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = backLabel }
                    ) { Text(backLabel) }
                }
                // 加载中：document 为 null 且无错误（避免 0 章 epub 误判为加载中而永久转圈）
                state.loading || state.document == null ->
                    CircularProgressIndicator(
                        color = theme.foreground,
                        modifier = Modifier.align(Alignment.Center)
                    )
                // TC-F2-24：空文件/空章节（0 字节、全空段落或 epub 空 spine）显示空态提示。
                // M3：某章 images 非空（纯图章，解析层已合成空宿主段落）时不得判空——需渲染图片；
                // F4.7 连续滚动下空态判定上移到整本（isEmptyDoc），真正无图真空章仍走既有空态。
                isEmptyDoc -> Text(
                    stringResource(R.string.reader_empty),
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                isMd && mdPreview -> MarkdownPreview(mdBlocks, settings, listState, theme)
                else -> ReaderTextView(
                    items = items,
                    settings = settings,
                    listState = listState,
                    // F2.14：epub 图片文件根 = epub 解压缓存目录（imageRoot）；图片/样式已按段组装进 item
                    imageRoot = document?.imageRoot
                )
            }
            // F4.8（M12c）进度条：真机反馈观感不佳，2026-09-12 起**暂时隐藏**（组件 ReaderProgressBar.kt
            // 完整保留，恢复时取消下方注释即可；对应的 Robolectric/设备端进度条用例已 @Ignore 同步停用）。
            /*
            if (document != null && !state.loading && !isEmptyDoc && !mdPreview && items.isNotEmpty() &&
                !settingsSheetOpen && !chapterSheetOpen && !tocSheetOpen
            ) {
                val progressTotal = if (state.totalItems > 0) state.totalItems else items.size
                ReaderProgressBar(
                    listState = listState,
                    totalItems = progressTotal,
                    theme = theme,
                    onSeek = { target ->
                        // 未物化章先物化，再经 pendingItemScroll 待窗口扩展后滚动（F4.8/TC-UI-11）
                        onEnsureMaterialized?.invoke(itemMap.locate(target).chapter)
                        pendingItemScroll = target
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
            */
        }
    }

    // F4.5/F2.16：epub 章节/目录面板（ModalBottomSheet）——数据源为 Document.toc（文件自带目录原样，
    // 无「第 N 章」生成条目），底部内置上一章/下一章翻章（按 spine，与目录有无无关）。
    if (chapterSheetOpen) {
        // 当前可见位置的目录高亮基准：epub 用全局 item 换算的章内段落（面板打开时才读取，不牵连正文重组）
        val visibleParagraph = itemMap.locate(listState.firstVisibleItemIndex.coerceAtLeast(0)).paragraph
        ModalBottomSheet(
            onDismissRequest = { chapterSheetOpen = false },
            containerColor = theme.background,
            contentColor = theme.foreground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = accent) }
        ) {
            ChapterSheetContent(
                toc = toc,
                selectedIndex = epubTocSelectedIndex(toc, state.chapterIndex, visibleParagraph),
                accent = accent,
                onSelect = { entry ->
                    chapterSheetOpen = false
                    // F2.16：锚点跳转——目标章未物化先物化，再滚到锚点全局 item 并立即落库
                    val target = itemMap.globalItem(entry.chapterIndex, entry.anchorParagraph ?: 0)
                    onEnsureMaterialized?.invoke(entry.chapterIndex)
                    pendingItemScroll = target
                    onTocJumpToItem?.invoke(target)
                },
                // 面板内翻章后停留在面板可继续选（F4.5）
                onPrev = { onPrevChapter?.invoke() },
                onNext = { onNextChapter?.invoke() },
                prevEnabled = state.chapterIndex > 0,
                nextEnabled = state.chapterIndex < chapters.lastIndex
            )
        }
    }

    // F2.13：md 目录面板（复用章节面板交互模式，F4.5）。当前标题高亮（S11：首可见段落
    // 位于第一个目录标题之前时无高亮，selectedIndex = -1）；点击目录项关闭面板并跳转（经 tocJumpParagraph）。
    if (tocSheetOpen) {
        val firstVisibleItem = listState.firstVisibleItemIndex.coerceAtLeast(0)
        val visibleParagraph = if (mdPreview)
            mapping.paragraphIndexForBlock(mdBlocks, firstVisibleItem)
        else
            firstVisibleItem
        val selectedTocIndex = mapping.tocIndexAtParagraph(visibleParagraph, toc)
        ModalBottomSheet(
            onDismissRequest = { tocSheetOpen = false },
            containerColor = theme.background,
            contentColor = theme.foreground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = accent) }
        ) {
            TocSheetContent(
                toc = toc,
                selectedIndex = selectedTocIndex,
                accent = accent,
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
            contentColor = theme.foreground,
            dragHandle = { BottomSheetDefaults.DragHandle(color = accent) }
        ) {
            ReaderSettingsSheetContent(
                settings = settings,
                isTxt = isTxt,
                encodingOverride = state.encodingOverride,
                accent = accent,
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
 * M13（F4.4/F4.5）：面板强调色语义键——记录选中勾/当前目录项等控件**实际绘制所用**的强调色
 * （= 阅读主题前景色，对 `theme.background` 对比度 ≥4.5:1），供 UI 测试断色值（TC-UI-13/14；
 * 同 TC-UI-03 的 ReaderBackground/ForegroundColorKey 机制，不用像素截图）。键必须与绘制同一局部值。
 */
val ReaderPanelAccentColorKey = SemanticsPropertyKey<Color>("ReaderPanelAccent")

/**
 * epub 章节/目录面板主体（F4.5/F2.16）：数据源为 [toc]（文件自带目录原样，条目/标题/层级均不改写，
 * 见 [TocListContent]）+ 底部上一章/下一章翻章按钮（按 spine，边界置灰，与目录有无无关）。
 * 从 ModalBottomSheet 抽出以便 Robolectric 直驱（同 md 目录面板 TocSheetContent 的做法）。
 */
@Composable
internal fun ChapterSheetContent(
    toc: List<TocEntry>,
    selectedIndex: Int,
    accent: Color,
    onSelect: (TocEntry) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    prevEnabled: Boolean,
    nextEnabled: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.reader_chapters_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        TocListContent(toc, selectedIndex, onSelect, accent = accent)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val prevLabel = stringResource(R.string.reader_prev_chapter)
            val nextLabel = stringResource(R.string.reader_next_chapter)
            TextButton(
                onClick = onPrev,
                enabled = prevEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = accent,
                    disabledContentColor = accent.copy(alpha = 0.38f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = prevLabel }
            ) { Text(prevLabel) }
            TextButton(
                onClick = onNext,
                enabled = nextEnabled,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = accent,
                    disabledContentColor = accent.copy(alpha = 0.38f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .semantics { contentDescription = nextLabel }
            ) { Text(nextLabel) }
        }
    }
}

/**
 * epub 目录当前高亮条目（F2.16）：取最后一个「章坐标不晚于当前阅读位置」的条目；
 * 首个条目之前返回 -1（无高亮，与 md 的 S11 语义一致）。纯函数便于单测。
 */
internal fun epubTocSelectedIndex(toc: List<TocEntry>, chapter: Int, paragraph: Int): Int {
    var selected = -1
    toc.forEachIndexed { index, entry ->
        val anchor = entry.anchorParagraph ?: 0
        if (entry.chapterIndex < chapter || (entry.chapterIndex == chapter && anchor <= paragraph)) {
            selected = index
        }
    }
    return selected
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
    accent: Color,
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
        Text(stringResource(R.string.reader_layout), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            SheetRadioOption(
                label = stringResource(R.string.reader_auto_wrap),
                selected = settings.wrapMode == WrapMode.WRAP,
                accent = accent,
                onClick = { onSelectWrapMode(WrapMode.WRAP) },
                modifier = Modifier.weight(1f)
            )
            SheetRadioOption(
                label = stringResource(R.string.reader_no_wrap),
                selected = settings.wrapMode == WrapMode.NO_WRAP,
                accent = accent,
                onClick = { onSelectWrapMode(WrapMode.NO_WRAP) },
                modifier = Modifier.weight(1f)
            )
        }
        // 字号：A- / 当前值 sp / A+（档位与端点 clamp 见 ReaderViewModel.stepFontSize）
        val decreaseFontSizeLabel = stringResource(R.string.reader_decrease_font_size)
        val increaseFontSizeLabel = stringResource(R.string.reader_increase_font_size)
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.reader_font_size), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${settings.fontSizeSp}sp", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = { onFontSizeStep(-1) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = accent,
                    disabledContentColor = accent.copy(alpha = 0.38f)
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = decreaseFontSizeLabel }
            ) { Text("A-") }
            TextButton(
                onClick = { onFontSizeStep(1) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = accent,
                    disabledContentColor = accent.copy(alpha = 0.38f)
                ),
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = increaseFontSizeLabel }
            ) { Text("A+") }
        }
        // 行距单选（U3：不再循环）：120/150/180/200%
        ReaderViewModel.LINE_HEIGHT_STEPS.forEach { percent ->
            SheetRadioOption(
                label = stringResource(R.string.reader_line_spacing, percent),
                selected = settings.lineHeightPercent == percent,
                accent = accent,
                onClick = { onSelectLineHeight(percent) }
            )
        }
        // G1（F2.6）：编码手动切换，仅 txt 显示；自动探测时无额外探测结果展示
        if (isTxt) {
            Text(stringResource(R.string.reader_encoding), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            ENCODING_OPTIONS.forEach { charsetName ->
                SheetRadioOption(
                    label = encodingLabel(charsetName),
                    selected = encodingOverride == charsetName,
                    accent = accent,
                    onClick = { onSelectEncoding(charsetName) }
                )
            }
        }

        // ---- 字体组（U3 单选）：代码 / 衬线 / 无衬线 / 自定义（仅 customFontPath 非空时出现）+ 导入字体 ----
        Text(stringResource(R.string.reader_font), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        val importFontCd = stringResource(R.string.reader_import_font_cd)
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
                accent = accent,
                onClick = { onSelectFont(font) }
            )
        }
        TextButton(
            onClick = onImportFont,
            colors = ButtonDefaults.textButtonColors(
                contentColor = accent,
                disabledContentColor = accent.copy(alpha = 0.38f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = importFontCd }
        ) { Text(stringResource(R.string.reader_import_font)) }
        fontImportMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // ---- 主题组（U3 单选，色块 + 文字，选中勾） ----
        Text(stringResource(R.string.reader_theme), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
        THEME_OPTIONS.forEach { value ->
            SheetRadioOption(
                label = themeLabel(value),
                selected = settings.theme == value,
                accent = accent,
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
private val THEME_OPTIONS: List<ReadingTheme?> = listOf(
    null,
    ReadingTheme.DEFAULT,
    ReadingTheme.EYE_CARE,
    ReadingTheme.SOFT_LIGHT,
    ReadingTheme.NIGHT
)

/** 编码单选项标签：null → 自动探测；ISO-8859-1 沿用旧显示名 Latin-1（M14 仅本地化自动探测项） */
@Composable
private fun encodingLabel(charsetName: String?): String = when (charsetName) {
    null -> stringResource(R.string.reader_encoding_auto)
    "ISO-8859-1" -> "Latin-1"
    else -> charsetName
}

/** 主题标签（null = 跟随系统） */
@Composable
private fun themeLabel(theme: ReadingTheme?): String = when (theme) {
    null -> stringResource(R.string.reader_theme_system)
    ReadingTheme.DEFAULT -> stringResource(R.string.reader_theme_default)
    ReadingTheme.EYE_CARE -> stringResource(R.string.reader_theme_eye_care)
    ReadingTheme.SOFT_LIGHT -> stringResource(R.string.reader_theme_soft)
    ReadingTheme.NIGHT -> stringResource(R.string.reader_theme_night)
}

/**
 * 面板单选项（U3）：Modifier.selectable + RadioButton 角色（自带 selected 语义）+ stateDescription，
 * 选中项末尾显示勾；整行 ≥ 48dp。M13：选中勾强调色随阅读主题（[accent]，语义键记录**实际绘制**色）。
 */
@Composable
private fun SheetRadioOption(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    // M13：强调色取阅读主题前景色（[accent]），语义键必须与绘制使用同一局部值
    val markColor = accent
    val selectedState = stringResource(R.string.common_state_selected)
    val unselectedState = stringResource(R.string.common_state_unselected)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics {
                stateDescription = if (selected) selectedState else unselectedState
                if (selected) this[ReaderPanelAccentColorKey] = markColor
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", color = markColor, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * md 目录面板主体（F2.13）：标题 + [TocListContent]。M13：当前项高亮强调色随阅读主题（[accent]）。
 * 从 ModalBottomSheet 抽出以便 Robolectric 直驱（同章节面板的做法，TC-F2-20a/b）。
 */
@Composable
internal fun TocSheetContent(
    toc: List<TocEntry>,
    selectedIndex: Int,
    accent: Color,
    onSelect: (TocEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.reader_toc_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        TocListContent(toc, selectedIndex, onSelect, accent = accent)
    }
}

/**
 * md 目录列表主体（F2.13）：按 level 缩进展示 H1~H6 层级，当前标题高亮
 * （强调色+加粗+selected 语义，S11：selectedIndex = -1 时无高亮项）；点击目标 ≥ 48dp。
 * M13：高亮色取 [accent]（阅读主题前景色），语义键记录**实际绘制**色。
 */
@Composable
internal fun TocListContent(
    toc: List<TocEntry>,
    selectedIndex: Int,
    onSelect: (TocEntry) -> Unit,
    accent: Color
) {
    LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
        itemsIndexed(toc, key = { index, _ -> index }) { index, entry ->
            val isCurrent = index == selectedIndex
            // M13：高亮色取阅读主题前景色（[accent]），语义键必须与绘制使用同一局部值
            val currentColor = accent
            Text(
                entry.title,
                color = if (isCurrent) currentColor else Color.Unspecified,
                fontWeight = if (isCurrent) FontWeight.Bold else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable { onSelect(entry) }
                    .semantics {
                        selected = isCurrent // 供无障碍与 UI 测试识别当前标题
                        if (isCurrent) this[ReaderPanelAccentColorKey] = currentColor
                    }
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

@Composable
private fun fontLabel(font: ReaderFont): String = when (font) {
    ReaderFont.CODE -> stringResource(R.string.reader_font_code)
    ReaderFont.SERIF -> stringResource(R.string.reader_font_serif)
    ReaderFont.SANS -> stringResource(R.string.reader_font_sans)
    ReaderFont.CUSTOM -> stringResource(R.string.reader_font_custom)
}
