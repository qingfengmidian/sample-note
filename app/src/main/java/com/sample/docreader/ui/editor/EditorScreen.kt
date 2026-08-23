package com.sample.docreader.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sample.docreader.core.io.DelegatingFileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.settings.SettingsRepository
import com.sample.docreader.ui.reader.fontFamilyFor
import com.sample.docreader.ui.reader.lineHeightSp
import com.sample.docreader.ui.reader.resolveReadingTheme

/** 编辑区容器 testTag，供 UI 测试定位 */
const val EDITOR_CONTENT_TAG = "editor_content"

/**
 * 编辑界面（设计§3.4/§4）：顶部栏（文件名/撤销/重做/保存）+ 全屏纯文本编辑区（BasicTextField）。
 * 仅 md/txt 可进入（F3.1/F4.1）；样式读取同一套 ReadingSettings，与阅读界面一致（F3.3）。
 */
@Composable
fun EditorScreen(
    uri: String,
    format: DocFormat,
    onBack: () -> Unit,
    displayName: String? = null
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel(factory = viewModelFactory {
        initializer {
            EditorViewModel(
                fileOpener = DelegatingFileOpener(context),
                settingsRepository = SettingsRepository(context)
            )
        }
    })
    LaunchedEffect(uri) {
        viewModel.load(uri, format, displayName.orEmpty())
    }

    // 退出拦截（F3.2）：dirty 时拦截返回弹出三选对话框；非 dirty 直接退出（BackHandler 不注册）
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = viewModel.dirty) { showExitDialog = true }

    EditorContent(
        content = viewModel.content,
        canUndo = viewModel.canUndo,
        canRedo = viewModel.canRedo,
        settings = viewModel.settings,
        displayName = viewModel.displayName.ifBlank { displayName.orEmpty() },
        loading = viewModel.loading,
        error = viewModel.error,
        showExitDialog = showExitDialog,
        onContentChange = viewModel::onContentChange,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSave = { /* M7b 接入保存落盘 */ },
        onDiscard = onBack,
        onCancelExit = { showExitDialog = false },
        onBack = onBack
    )
}

/**
 * 无状态编辑界面主体：状态与回调全部外提，便于 Compose UI Test 直接驱动（TC-UI-04）。
 * BackHandler 拦截在 [EditorScreen]，此处仅按 [showExitDialog] 渲染三选对话框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorContent(
    content: TextFieldValue,
    canUndo: Boolean,
    canRedo: Boolean,
    settings: ReadingSettings,
    displayName: String,
    loading: Boolean,
    error: String?,
    showExitDialog: Boolean,
    onContentChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancelExit: () -> Unit,
    onBack: () -> Unit
) {
    val theme = resolveReadingTheme(settings.theme)
    val textStyle = TextStyle(
        color = theme.foreground,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = lineHeightSp(settings),
        fontFamily = fontFamilyFor(settings.font, settings.customFontPath)
    )

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                title = { Text(displayName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    EditorTopBarButton("撤销", enabled = canUndo, onClick = onUndo)
                    EditorTopBarButton("重做", enabled = canRedo, onClick = onRedo)
                    // 保存：M7b 接入，本次禁用（TC-F3-03 保存分支手工验收推迟到 M7b）
                    EditorTopBarButton("保存", enabled = false, onClick = onSave)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.foreground,
                    navigationIconContentColor = theme.foreground,
                    actionIconContentColor = theme.foreground
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> Text(
                    error,
                    color = theme.foreground,
                    modifier = Modifier.align(Alignment.Center)
                )
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> BasicTextField(
                    value = content,
                    onValueChange = onContentChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(theme.foreground),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(theme.background)
                        .testTag(EDITOR_CONTENT_TAG)
                        .semantics { contentDescription = "编辑区" }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (showExitDialog) {
        EditorExitDialog(
            onSave = onSave,
            onDiscard = onDiscard,
            onCancel = onCancelExit
        )
    }
}

/** 顶部栏文字按钮：语义描述供无障碍与 UI 测试识别，可点击目标 ≥ 48dp */
@Composable
private fun EditorTopBarButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = label }
    ) {
        Text(label)
    }
}

/** 退出三选对话框（F3.2/TC-F3-03）：AlertDialog 包裹 [EditorExitDialogContent] */
@Composable
private fun EditorExitDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("未保存的修改") },
        text = { EditorExitDialogContent(onSave, onDiscard, onCancel) },
        confirmButton = {}
    )
}

/**
 * 三选对话框内容主体（TC-F3-03）。
 * Robolectric 下 AlertDialog 窗口内容不进语义树，抽成可直驱的 internal composable 供 TC-UI-04 测试
 * （同 ReaderChapterListTest 的 ChapterListContent 做法）。
 * 保存项 M7b 接入，本次渲染为 disabled 并带 contentDescription。
 */
@Composable
internal fun EditorExitDialogContent(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    Column {
        Text("是否保存对文件的修改？")
        TextButton(
            onClick = onSave,
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "保存" }
        ) {
            Text("保存")
        }
        TextButton(
            onClick = onDiscard,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "放弃" }
        ) {
            Text("放弃")
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = "取消" }
        ) {
            Text("取消")
        }
    }
}
