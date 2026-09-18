package com.sample.docreader.ui.editor

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sample.docreader.R
import com.sample.docreader.core.io.ContentResolverDocumentWriter
import com.sample.docreader.core.io.DelegatingFileOpener
import com.sample.docreader.core.io.DraftStore
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ReadingSettings
import com.sample.docreader.core.settings.SettingsRepository
import com.sample.docreader.ui.reader.ReaderError
import com.sample.docreader.ui.reader.ReaderReloadNotifier
import com.sample.docreader.ui.reader.fontFamilyFor
import com.sample.docreader.ui.reader.lineHeightSp
import com.sample.docreader.ui.reader.readerErrorText
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
                settingsRepository = SettingsRepository(context),
                documentWriter = ContentResolverDocumentWriter(context.contentResolver),
                cacheDir = context.cacheDir,
                // M7c：草稿落 filesDir/edit_recovery/；uri/光标经 SavedStateHandle 跨进程死亡保留
                draftStore = DraftStore(context.filesDir),
                savedStateHandle = createSavedStateHandle()
            )
        }
    })
    LaunchedEffect(uri) {
        viewModel.load(uri, format, displayName.orEmpty())
    }

    // 退出拦截（F3.2）：dirty 时拦截返回弹出三选对话框；非 dirty 直接退出（BackHandler 不注册）
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var exitAfterSave by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = viewModel.dirty) { showExitDialog = true }

    // 保存结果一次性事件（M7b）：消费并给反馈；三选对话框"保存"成功后回退阅读页
    LaunchedEffect(viewModel.saveResult) {
        when (val result = viewModel.saveResult) {
            null -> Unit
            SaveResult.Success -> {
                // 通知阅读页重载新内容（TC-F3-02）
                ReaderReloadNotifier.notifySaved(uri)
                if (exitAfterSave) {
                    exitAfterSave = false
                    onBack()
                } else {
                    feedback = context.getString(saveResultMessage(result))
                }
            }
            SaveResult.FailedSaveAs, SaveResult.ProviderKilled -> {
                exitAfterSave = false
                feedback = context.getString(saveResultMessage(result))
            }
        }
        viewModel.consumeSaveResult()
    }

    EditorContent(
        content = viewModel.content,
        canUndo = viewModel.canUndo,
        canRedo = viewModel.canRedo,
        dirty = viewModel.dirty,
        settings = viewModel.settings,
        displayName = viewModel.displayName.ifBlank { displayName.orEmpty() },
        loading = viewModel.loading,
        error = viewModel.error,
        showExitDialog = showExitDialog,
        onContentChange = viewModel::onContentChange,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSave = { viewModel.save() },
        onSaveAndExit = {
            showExitDialog = false
            exitAfterSave = true
            viewModel.save()
        },
        onDiscard = {
            // 三选"放弃"（TC-UT-09b）：删除草稿后退出，避免下次打开误恢复
            viewModel.discardDraft()
            onBack()
        },
        onCancelExit = { showExitDialog = false },
        onBack = onBack
    )

    // 保存结果反馈（从简，AlertDialog；Success/FailedSaveAs/ProviderKilled 分别提示）
    feedback?.let { message ->
        AlertDialog(
            onDismissRequest = { feedback = null },
            title = { Text(stringResource(R.string.common_save)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { feedback = null }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }
}

/** 保存结果 → 提示文案资源（M7b；ProviderKilled 文案与阅读页对齐，见 S1 探针结论）。
 *  M14（F4.6，T3）：改返回资源 id，由 UI 层经 stringResource/getString 映射，VM 不持有中文文案。 */
@StringRes
internal fun saveResultMessage(result: SaveResult): Int = when (result) {
    SaveResult.Success -> R.string.editor_save_success
    SaveResult.FailedSaveAs -> R.string.editor_save_failed
    SaveResult.ProviderKilled -> R.string.reader_error_provider_not_running
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
    dirty: Boolean,
    settings: ReadingSettings,
    displayName: String,
    loading: Boolean,
    error: ReaderError?,
    showExitDialog: Boolean,
    onContentChange: (TextFieldValue) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onSaveAndExit: () -> Unit,
    onDiscard: () -> Unit,
    onCancelExit: () -> Unit,
    onBack: () -> Unit
) {
    val theme = resolveReadingTheme(settings.theme)
    val editorContentCd = stringResource(R.string.editor_content_cd)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back_cd))
                    }
                },
                actions = {
                    EditorTopBarButton(stringResource(R.string.editor_undo), enabled = canUndo, onClick = onUndo)
                    EditorTopBarButton(stringResource(R.string.editor_redo), enabled = canRedo, onClick = onRedo)
                    // 保存：dirty 且有内容变更时可点（TC-F3-01/02，M7b 接通落盘）
                    EditorTopBarButton(stringResource(R.string.common_save), enabled = dirty && !loading, onClick = onSave)
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
                    readerErrorText(error),
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
                        .semantics { contentDescription = editorContentCd }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    if (showExitDialog) {
        EditorExitDialog(
            onSave = onSaveAndExit,
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
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { EditorExitDialogContent(onSave, onDiscard, onCancel) },
        confirmButton = {}
    )
}

/**
 * 三选对话框内容主体（TC-F3-03）。
 * Robolectric 下 AlertDialog 窗口内容不进语义树，抽成可直驱的 internal composable 供 TC-UI-04 测试
 * （同 ReaderChapterListTest 的 ChapterListContent 做法）。
 * 保存项 M7b 接通：点击触发 onSave（保存成功后由 EditorScreen 回退阅读页）。
 */
@Composable
internal fun EditorExitDialogContent(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    val saveLabel = stringResource(R.string.common_save)
    val discardLabel = stringResource(R.string.common_discard)
    val cancelLabel = stringResource(R.string.common_cancel)
    Column {
        Text(stringResource(R.string.editor_unsaved_message))
        TextButton(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = saveLabel }
        ) {
            Text(saveLabel)
        }
        TextButton(
            onClick = onDiscard,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = discardLabel }
        ) {
            Text(discardLabel)
        }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = cancelLabel }
        ) {
            Text(cancelLabel)
        }
    }
}
