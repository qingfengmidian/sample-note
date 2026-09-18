package com.sample.docreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sample.docreader.R

/**
 * M3 极简 fixtures 选择入口：列出 debug assets fixtures 中的 txt/md/xml/epub，点击进入阅读界面。
 * M4 由 HomeScreen（最近列表 + SAF 打开）替换主入口；本入口保留用于开发/测试样本走查
 * （M11b 起收录 epub 含图样本，供设备端 MdImageDeviceTest 验收 TC-F2-17/17c/17d）。
 */
@Composable
fun FixtureEntryScreen(onOpenFixture: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val fixtures = remember {
        context.assets.list("fixtures").orEmpty()
            .filter {
                it.endsWith(".txt") || it.endsWith(".md") ||
                    it.endsWith(".xml") || it.endsWith(".epub")
            }
            .sorted()
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.fixture_title), style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(fixtures) { name ->
                Text(
                    name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { onOpenFixture(name) }
                        .padding(vertical = 12.dp)
                )
            }
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        ) {
            Text(stringResource(R.string.home_settings_cd))
        }
    }
}
