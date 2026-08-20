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
 * 回归测试（TC-F1-09）：content:// URI 经导航路由 `reader/{format}/{Uri.encode(uri)}` 往返后，
 * Navigation 已对路径参数解码一次，`arguments.getString("uri")` 应直接等于原始 uri，
 * 不得再 Uri.decode（否则会二次解码损坏含 %3A/%2F 的 SAF document URI）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationUriTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun contentUri_argumentIsAlreadyDecoded() {
        val original = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fnote.md"
        var raw by mutableStateOf("")

        composeRule.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    Button(onClick = {
                        nav.navigate("reader/${DocFormat.MD.name}/${Uri.encode(original)}")
                    }) { Text("go") }
                }
                composable("reader/{format}/{uri}") { entry ->
                    LaunchedEffect(Unit) {
                        raw = entry.arguments?.getString("uri").orEmpty()
                    }
                    Text(entry.arguments?.getString("uri").orEmpty())
                }
            }
        }

        composeRule.onNodeWithText("go").performClick()
        composeRule.waitForIdle()

        assertEquals(original, raw)
    }
}
