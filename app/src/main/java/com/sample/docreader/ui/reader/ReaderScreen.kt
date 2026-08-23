package com.sample.docreader.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sample.docreader.core.io.DelegatingFileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import com.sample.docreader.core.progress.ProgressRepository
import com.sample.docreader.core.recent.AppDatabase
import com.sample.docreader.core.render.MarkdownParser
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged

/** 阅读界面（设计§4）：顶部栏（书名/章节名）+ 正文区 + 底部工具条 + epub 章节列表/翻章。 */
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
                ProgressRepository(AppDatabase.get(context.applicationContext))
            )
        }
    })
    LaunchedEffect(uri) {
        if (viewModel.uiState.value.document == null) {
            viewModel.load(uri, format, displayName)
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    ReaderContent(
        state = state,
        onBack = onBack,
        onToggleWrap = viewModel::toggleWrap,
        onFontSizeStep = viewModel::stepFontSize,
        onCycleLineHeight = viewModel::cycleLineHeight,
        onCycleFont = viewModel::cycleFont,
        onCycleTheme = viewModel::cycleTheme,
        onSelectChapter = viewModel::selectChapter,
        onPrevChapter = { viewModel.selectChapter(state.chapterIndex - 1) },
        onNextChapter = { viewModel.selectChapter(state.chapterIndex + 1) },
        onFirstVisibleParagraphChanged = viewModel::onFirstVisibleParagraphChanged,
        onRestoreParagraphConsumed = viewModel::consumeRestoreParagraph,
        onEdit = onEdit
    )
}

/**
 * 无状态阅读界面主体：状态与回调全部外提，便于 Compose UI Test 直接驱动。
 * 换行模式切换位置保持（F2.2/TC-UI-01）：切换前记录首可见段落索引，重组后滚回该段落。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderContent(
    state: ReaderUiState,
    onBack: () -> Unit,
    onToggleWrap: () -> Unit,
    onFontSizeStep: (Int) -> Unit,
    onCycleLineHeight: () -> Unit,
    onCycleFont: () -> Unit,
    onCycleTheme: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    onSelectChapter: ((Int) -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    onFirstVisibleParagraphChanged: ((Int) -> Unit)? = null,
    onRestoreParagraphConsumed: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    val settings = state.settings
    val theme = resolveReadingTheme(settings.theme)
    val chapters = state.document?.chapters.orEmpty()
    val chapter = chapters.getOrNull(state.chapterIndex)
    val paragraphs = chapter?.paragraphs
    val isEpub = state.document?.format == DocFormat.EPUB
    val isMd = state.document?.format == DocFormat.MD
    val isTxt = state.document?.format == DocFormat.TXT
    // rememberSaveable：旋转/重建后不丢预览状态
    var mdPreview by rememberSaveable { mutableStateOf(false) }
    val mdBlocks = remember(paragraphs, isMd) {
        if (isMd) MarkdownParser.parse(paragraphs.orEmpty()) else emptyList()
    }

    var pendingParagraph by remember { mutableIntStateOf(-1) }
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

    var chapterSheetOpen by remember { mutableStateOf(false) }

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
                                    mdPreview = !mdPreview
                                }
                            )
                        }
                        // 编辑入口仅 md/txt 显示（F3.1/F4.1：epub/xml 只读）
                        if (isMd || isTxt) {
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                onClick = {
                                    menuOpen = false
                                    onEdit?.invoke()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.foreground,
                    navigationIconContentColor = theme.foreground,
                    actionIconContentColor = theme.foreground
                )
            )
        },
        bottomBar = {
            ReaderToolbar(
                settings = settings,
                onToggleWrap = {
                    pendingParagraph = listState.firstVisibleItemIndex
                    onToggleWrap()
                },
                onFontSizeStep = onFontSizeStep,
                onCycleLineHeight = onCycleLineHeight,
                onCycleFont = onCycleFont,
                onCycleTheme = onCycleTheme,
                isEpub = isEpub,
                onChapterList = if (isEpub) ({ chapterSheetOpen = true }) else null,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    state.error,
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                // 加载中：document 为 null 且无错误（避免 0 章 epub 误判为加载中而永久转圈）
                state.loading || state.document == null ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                // TC-F2-24：空文件/空章节（0 字节、全空段落或 epub 空 spine）显示空态提示
                paragraphs == null || paragraphs.isEmpty() || paragraphs.all { it.isBlank() } -> Text(
                    "文件为空",
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                isMd && mdPreview -> MarkdownPreview(mdBlocks, settings, listState, theme)
                else -> ReaderTextView(paragraphs, settings, listState)
            }
        }
    }

    if (chapterSheetOpen) {
        ChapterListSheet(
            chapters = chapters,
            currentIndex = state.chapterIndex,
            onSelect = { index ->
                chapterSheetOpen = false
                onSelectChapter?.invoke(index)
            },
            onDismiss = { chapterSheetOpen = false }
        )
    }
}

/** epub 章节列表弹层（F2.7）：AlertDialog 包裹 [ChapterListContent]（Robolectric 下对话框窗口不进语义树，列表主体单独抽测） */
@Composable
private fun ChapterListSheet(
    chapters: List<com.sample.docreader.core.model.Chapter>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("章节") },
        text = { ChapterListContent(chapters, currentIndex, onSelect) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
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

/** 底部工具条：换行模式 / 字号 A- A+ / 行距 / 字体 / 阅读主题；epub 追加章节列表与翻章。按钮均 ≥48dp */
@Composable
private fun ReaderToolbar(
    settings: ReadingSettings,
    onToggleWrap: () -> Unit,
    onFontSizeStep: (Int) -> Unit,
    onCycleLineHeight: () -> Unit,
    onCycleFont: () -> Unit,
    onCycleTheme: () -> Unit,
    isEpub: Boolean = false,
    onChapterList: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarButton(
            text = if (settings.wrapMode == WrapMode.WRAP) "自动换行" else "不换行",
            description = "切换换行模式",
            onClick = onToggleWrap
        )
        ToolbarButton(text = "A-", description = "减小字号", onClick = { onFontSizeStep(-1) })
        ToolbarButton(text = "A+", description = "增大字号", onClick = { onFontSizeStep(1) })
        ToolbarButton(
            text = "行距 ${settings.lineHeightPercent}%",
            description = "切换行距",
            onClick = onCycleLineHeight
        )
        ToolbarButton(text = fontLabel(settings.font), description = "切换字体", onClick = onCycleFont)
        ToolbarButton(text = themeLabel(settings.theme), description = "切换主题", onClick = onCycleTheme)
        if (isEpub) {
            if (onPrevChapter != null) {
                ToolbarButton(text = "上一章", description = "上一章", onClick = onPrevChapter)
            }
            if (onChapterList != null) {
                ToolbarButton(text = "章节", description = "章节列表", onClick = onChapterList)
            }
            if (onNextChapter != null) {
                ToolbarButton(text = "下一章", description = "下一章", onClick = onNextChapter)
            }
        }
    }
}

@Composable
private fun ToolbarButton(text: String, description: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }
    ) {
        Text(text)
    }
}

private fun fontLabel(font: ReaderFont): String = when (font) {
    ReaderFont.CODE -> "代码"
    ReaderFont.SERIF -> "衬线"
    ReaderFont.SANS -> "无衬线"
    ReaderFont.CUSTOM -> "自定义"
}

private fun themeLabel(theme: ReadingTheme?): String = when (theme) {
    null -> "跟随系统"
    ReadingTheme.DEFAULT -> "默认白"
    ReadingTheme.EYE_CARE -> "护眼绿"
    ReadingTheme.SOFT_LIGHT -> "柔光米黄"
    ReadingTheme.NIGHT -> "夜间黑"
}
