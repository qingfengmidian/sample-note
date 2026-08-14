package com.sample.docreader.ui.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sample.docreader.core.io.AssetsFileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ReaderFont
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
import com.sample.docreader.core.settings.SettingsRepository

/** 阅读界面（设计§4）：顶部栏（文件名/菜单）+ 正文区 + 底部工具条。M3 入口为 fixtures 桩，SAF 入口在 M4。 */
@Composable
fun ReaderScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ReaderViewModel = viewModel(factory = viewModelFactory {
        initializer { ReaderViewModel(AssetsFileOpener(context.assets), SettingsRepository(context)) }
    })
    LaunchedEffect(name) {
        if (viewModel.uiState.value.document == null) {
            viewModel.load("asset:///fixtures/$name", formatOfName(name))
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReaderContent(
        state = state,
        onBack = onBack,
        onToggleWrap = viewModel::toggleWrap,
        onFontSizeStep = viewModel::stepFontSize,
        onCycleLineHeight = viewModel::cycleLineHeight,
        onCycleFont = viewModel::cycleFont,
        onCycleTheme = viewModel::cycleTheme
    )
}

/** 按扩展名判定格式；M3 入口仅 txt/md/xml */
internal fun formatOfName(name: String): DocFormat = when (name.substringAfterLast('.', "")) {
    "md" -> DocFormat.MD
    "xml" -> DocFormat.XML
    else -> DocFormat.TXT
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
    listState: LazyListState = rememberLazyListState()
) {
    val settings = state.settings
    val theme = resolveReadingTheme(settings.theme)

    var pendingParagraph by remember { mutableIntStateOf(-1) }
    LaunchedEffect(settings.wrapMode) {
        if (pendingParagraph >= 0) {
            listState.scrollToItem(pendingParagraph)
            pendingParagraph = -1
        }
    }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                title = { Text(state.document?.name ?: "", maxLines = 1) },
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
                        DropdownMenuItem(
                            text = { Text("编辑（M7 里程碑）") },
                            onClick = {},
                            enabled = false
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
                onCycleTheme = onCycleTheme
            )
        }
    ) { padding ->
        val paragraphs = state.document?.chapters?.firstOrNull()?.paragraphs
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    state.error,
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                state.loading || paragraphs == null ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                // TC-F2-24：空文件（0 字节或全空段落）显示空态提示
                paragraphs.isEmpty() || paragraphs.all { it.isBlank() } -> Text(
                    "文件为空",
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> ReaderTextView(paragraphs, settings, listState)
            }
        }
    }
}

/** 底部工具条：换行模式 / 字号 A- A+ / 行距 / 字体 / 阅读主题；按钮均带 contentDescription 且 ≥48dp */
@Composable
private fun ReaderToolbar(
    settings: ReadingSettings,
    onToggleWrap: () -> Unit,
    onFontSizeStep: (Int) -> Unit,
    onCycleLineHeight: () -> Unit,
    onCycleFont: () -> Unit,
    onCycleTheme: () -> Unit
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
