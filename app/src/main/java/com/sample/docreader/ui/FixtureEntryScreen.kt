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
import androidx.compose.ui.unit.dp

/**
 * M3 极简 fixtures 选择入口：列出 debug assets fixtures 中的 txt/md/xml，点击进入阅读界面。
 * M4 由 HomeScreen（最近列表 + SAF 打开）替换本入口；epub 阅读为 M5，此处不列出。
 */
@Composable
fun FixtureEntryScreen(onOpenFixture: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val fixtures = remember {
        context.assets.list("fixtures").orEmpty()
            .filter { it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".xml") }
            .sorted()
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("测试样本（M3 开发入口）", style = MaterialTheme.typography.titleMedium)
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
            Text("设置")
        }
    }
}
