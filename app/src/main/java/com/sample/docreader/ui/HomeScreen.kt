package com.sample.docreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** M0 占位首页：最近文件列表与"打开文件"在 M4 实现。 */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("简易文档")
        Text("最近打开列表（待 M4 实现）", modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 24.dp)) {
            Text("设置")
        }
    }
}
