package com.sample.docreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.ui.AppTheme
import com.sample.docreader.ui.FixtureEntryScreen
import com.sample.docreader.ui.HomeScreen
import com.sample.docreader.ui.SettingsScreen
import com.sample.docreader.ui.reader.ReaderScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 外部 ACTION_VIEW 打开的文件 uri（singleTask 下经 onCreate/onNewIntent 汇入，交给首页嗅探后导航） */
    private val pendingViewUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleViewIntent(intent)
        setContent {
            AppTheme {
                val navController = rememberNavController()
                val viewUri by pendingViewUri.collectAsStateWithLifecycle()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenFixtures = { navController.navigate("fixtures") },
                            onOpenReader = { uri, format ->
                                navController.navigate("reader/${format.name}/${Uri.encode(uri)}")
                            },
                            pendingViewUri = viewUri,
                            onViewUriConsumed = { pendingViewUri.value = null }
                        )
                    }
                    composable("reader/{format}/{uri}") { entry ->
                        val format = entry.arguments?.getString("format")
                            ?.let { runCatching { DocFormat.valueOf(it) }.getOrNull() }
                            ?: DocFormat.TXT
                        // Navigation 已对路径参数解码一次，这里不能再 Uri.decode，否则会二次解码损坏 content:// URI
                        val uri = entry.arguments?.getString("uri").orEmpty()
                        ReaderScreen(
                            uri = uri,
                            format = format,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("fixtures") {
                        FixtureEntryScreen(
                            onOpenFixture = { name ->
                                val format = when (name.substringAfterLast('.', "")) {
                                    "md" -> DocFormat.MD
                                    "xml" -> DocFormat.XML
                                    else -> DocFormat.TXT
                                }
                                navController.navigate(
                                    "reader/${format.name}/${Uri.encode("asset:///fixtures/$name")}"
                                )
                            },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { pendingViewUri.value = it }
        }
    }
}
