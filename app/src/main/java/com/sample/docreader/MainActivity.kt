package com.sample.docreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.sample.docreader.ui.editor.EditorScreen
import com.sample.docreader.ui.reader.ReaderScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** 外部 ACTION_VIEW 打开的文件 uri（singleTask 下经 onCreate/onNewIntent 汇入，交给首页嗅探后导航） */
    private val pendingViewUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // U2（M10）：沉浸式——正文绘制延伸至状态栏/导航栏后方，系统栏配色由阅读界面跟随主题（见 ReaderScreen）
        enableEdgeToEdge()
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
                            onOpenReader = { uri, name, format ->
                                navController.navigate(
                                    "reader/${format.name}/${Uri.encode(uri)}/${Uri.encode(name)}"
                                )
                            },
                            // 新建文件（F3.5/TC-F3-08）：创建成功后直接进入编辑器（空文件）
                            onOpenEditor = { uri, name, format ->
                                navController.navigate(
                                    "editor/${format.name}/${Uri.encode(uri)}/${Uri.encode(name)}"
                                )
                            },
                            pendingViewUri = viewUri,
                            onViewUriConsumed = { pendingViewUri.value = null }
                        )
                    }
                    composable("reader/{format}/{uri}/{name}") { entry ->
                        val format = entry.arguments?.getString("format")
                            ?.let { runCatching { DocFormat.valueOf(it) }.getOrNull() }
                            ?: DocFormat.TXT
                        // Navigation 已对路径参数解码一次，这里不能再 Uri.decode，否则会二次解码损坏 content:// URI
                        val uri = entry.arguments?.getString("uri").orEmpty()
                        val name = entry.arguments?.getString("name").orEmpty()
                        ReaderScreen(
                            uri = uri,
                            format = format,
                            displayName = name,
                            onBack = { navController.popBackStack() },
                            onEdit = {
                                navController.navigate(
                                    "editor/${format.name}/${Uri.encode(uri)}/${Uri.encode(name)}"
                                )
                            }
                        )
                    }
                    composable("editor/{format}/{uri}/{name}") { entry ->
                        val format = entry.arguments?.getString("format")
                            ?.let { runCatching { DocFormat.valueOf(it) }.getOrNull() }
                            ?: DocFormat.TXT
                        // Navigation 已对路径参数解码一次，这里不能再 Uri.decode（同 reader 路由，见 37e252b）
                        val uri = entry.arguments?.getString("uri").orEmpty()
                        val name = entry.arguments?.getString("name").orEmpty()
                        EditorScreen(
                            uri = uri,
                            format = format,
                            displayName = name,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                    // U6（M10）：fixtures 开发入口仅 debug 构建注册，release 包无此路由
                    if (BuildConfig.DEBUG) {
                        composable("fixtures") {
                            FixtureEntryScreen(
                                onOpenFixture = { name ->
                                    val format = when (name.substringAfterLast('.', "")) {
                                        "md" -> DocFormat.MD
                                        "xml" -> DocFormat.XML
                                        "epub" -> DocFormat.EPUB // M11b：fixtures 含图 epub 供设备端用例走读
                                        else -> DocFormat.TXT
                                    }
                                    navController.navigate(
                                        "reader/${format.name}/${Uri.encode("asset:///fixtures/$name")}/${Uri.encode(name)}"
                                    )
                                },
                                onOpenSettings = { navController.navigate("settings") }
                            )
                        }
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
