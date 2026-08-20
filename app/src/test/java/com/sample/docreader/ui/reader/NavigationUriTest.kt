package com.sample.docreader.ui.reader

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sample.docreader.core.model.DocFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 回归测试（TC-F1-09）：content:// URI 与文件名经导航路由
 * `reader/{format}/{Uri.encode(uri)}/{Uri.encode(name)}` 往返后，
 * Navigation 已对路径参数解码一次，`arguments.getString(...)` 应直接等于原始值，
 * 不得再 Uri.decode（否则会二次解码损坏含 %3A/%2F 的 SAF document URI 或特殊字符文件名）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationUriTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun contentUri_andName_argumentsAreAlreadyDecoded() {
        val originalUri = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fnote.md"
        val originalName = "note #1 & 副本.md"
        var rawUri by mutableStateOf("")
        var rawName by mutableStateOf("")

        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    Button(onClick = {
                        nav.navigate(
                            "reader/${DocFormat.MD.name}/${Uri.encode(originalUri)}/${Uri.encode(originalName)}"
                        )
                    }) { Text("go") }
                }
                composable("reader/{format}/{uri}/{name}") { entry ->
                    LaunchedEffect(Unit) {
                        rawUri = entry.arguments?.getString("uri").orEmpty()
                        rawName = entry.arguments?.getString("name").orEmpty()
                    }
                    Text(entry.arguments?.getString("uri").orEmpty())
                }
            }
        }

        composeRule.onNodeWithText("go").performClick()
        composeRule.waitForIdle()

        assertEquals(originalUri, rawUri)
        assertEquals(originalName, rawName)
    }
}
