package com.sample.docreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.recent.AppDatabase
import com.sample.docreader.core.recent.RecentFile
import com.sample.docreader.core.recent.RecentFilesRepository
import com.sample.docreader.ui.home.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页（M4，设计§4.1）：最近打开列表 + SAF"打开文件"入口 + 设置/测试样本入口。
 * 最近列表支持点击打开、单条删除、清空；一次性提示走 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenFixtures: () -> Unit,
    onOpenReader: (uri: String, format: DocFormat) -> Unit,
    pendingViewUri: Uri? = null,
    onViewUriConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory {
        initializer {
            HomeViewModel(
                RecentFilesRepository(AppDatabase.get(context.applicationContext)),
                context.contentResolver
            )
        }
    })

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.onPicked(uri) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.openTarget) {
        state.openTarget?.let {
            onOpenReader(it.uri, it.format)
            viewModel.consumeOpenTarget()
        }
    }
    // 外部 ACTION_VIEW 传入的 uri：走与"打开文件"相同的嗅探→持久权限→最近→导航流程
    LaunchedEffect(pendingViewUri) {
        pendingViewUri?.let {
            viewModel.onPicked(it)
            onViewUriConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("简易文档") },
                actions = {
                    TextButton(onClick = onOpenFixtures) { Text("测试样本") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.recent.isEmpty()) {
                Text(
                    "暂无最近文件，点击下方\"打开文件\"选择文档",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            } else {
                Text(
                    "最近打开",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.recent, key = { it.uri }) { item ->
                        RecentFileRow(
                            item = item,
                            onOpen = { viewModel.openRecent(item) },
                            onRemove = { viewModel.remove(item.uri) }
                        )
                    }
                    item {
                        TextButton(
                            onClick = viewModel::clear,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                        ) {
                            Text("清空最近列表")
                        }
                    }
                }
            }
            Button(
                onClick = { launcher.launch(arrayOf("text/*", "application/epub+zip", "*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .defaultMinSize(minHeight = 48.dp)
            ) {
                Text("打开文件")
            }
        }
    }
}

@Composable
private fun RecentFileRow(item: RecentFile, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatLabel(item.format)} · ${timeLabel(item.openedAt)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        TextButton(onClick = onRemove) { Text("删除") }
    }
}

private fun formatLabel(format: DocFormat): String = when (format) {
    DocFormat.EPUB -> "epub"
    DocFormat.XML -> "xml"
    DocFormat.MD -> "md"
    DocFormat.TXT -> "txt"
}

private fun timeLabel(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
