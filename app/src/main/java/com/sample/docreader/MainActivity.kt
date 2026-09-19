package com.sample.docreader

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sample.docreader.core.askai.AskAi
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.settings.LocaleSwitcher
import com.sample.docreader.core.settings.SettingsRepository
import com.sample.docreader.ui.AppTheme
import com.sample.docreader.ui.FixtureEntryScreen
import com.sample.docreader.ui.HomeScreen
import com.sample.docreader.ui.SettingsScreen
import com.sample.docreader.ui.editor.EditorScreen
import com.sample.docreader.ui.reader.ReaderScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 外部 ACTION_VIEW 打开的文件 uri（singleTask 下经 onCreate/onNewIntent 汇入，交给首页嗅探后导航） */
    private val pendingViewUri = MutableStateFlow<Uri?>(null)

    /**
     * F4.9/M16：按持久语言覆盖包装 base context（跟随系统时原样返回）——「手动选择优先于系统语言」
     * 在此路径保证；API 33+ 的框架态同步见 LocaleSwitcher.applyToFramework（返回 false 时由 UI 自行 recreate）。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleSwitcher.wrapBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // U2（M10）：沉浸式——正文绘制延伸至状态栏/导航栏后方，系统栏配色由阅读界面跟随主题（见 ReaderScreen）
        enableEdgeToEdge()
        handleViewIntent(intent)
        setContent {
            AppTheme {
                val navController = rememberNavController()
                val viewUri by pendingViewUri.collectAsStateWithLifecycle()
                val context = LocalContext.current
                // F4.10/M18：全局 Snackbar 宿主（问 AI 提示与 ⋮ 菜单兜底提示共用）
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val repository = remember { SettingsRepository(context) }
                // 三键与阅读设置键级隔离（设计 REV18 §3.10-1），单独订阅
                val askAiEnabled by repository.askAiEnabled.collectAsStateWithLifecycle(initialValue = true)
                val askAiTarget by repository.askAiTargetPackage.collectAsStateWithLifecycle(initialValue = null)
                val askAiPrefix by repository.askAiPrefix.collectAsStateWithLifecycle(initialValue = null)
                val defaultPrefix = stringResource(R.string.settings_ask_ai_prefix_default)

                // 读剪贴板纯文本（无内容/空白 → null）
                fun clipboardText(): String? {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return null
                    val clip = manager.primaryClip ?: return null
                    if (clip.itemCount == 0) return null
                    val item = clip.getItemAt(0) ?: return null
                    return item.coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotBlank() }
                }

                // 统一发送路径：未选目标 → 引导提示；发送失败（目标缺失/被禁用）→ 错误提示
                fun sendAskAi(text: String) {
                    val target = askAiTarget
                    if (target.isNullOrBlank()) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.ask_ai_no_target),
                                duration = SnackbarDuration.Short
                            )
                        }
                        return
                    }
                    val payload = AskAi.composePayload(askAiPrefix, defaultPrefix, text)
                    if (!AskAi.send(context, target, payload)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.ask_ai_send_failed),
                                duration = SnackbarDuration.Short
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
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
                                },
                                // F4.10/M18：⋮ 菜单兜底——总开关关闭/剪贴板为空/未选目标/发送失败均给出提示
                                onAskAi = {
                                    if (!askAiEnabled) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.ask_ai_disabled),
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    } else {
                                        val text = clipboardText()
                                        if (text == null) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.ask_ai_clipboard_empty),
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        } else {
                                            sendAskAi(text)
                                        }
                                    }
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
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                // F4.10/M18：仅前台监听剪贴板（后台不读）；开关关闭或未选目标时不注册
                AskAiClipboardEffect(
                    snackbarHostState = snackbarHostState,
                    enabled = askAiEnabled,
                    targetPackage = askAiTarget,
                    onAction = { text -> sendAskAi(text) }
                )
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

/**
 * F4.10/M18 剪贴板监听（设计 REV18 §3.10-3）：`LifecycleResumeEffect` 在 RESUMED 注册
 * `OnPrimaryClipChangedListener`、离开 RESUMED 注销；回调经 [AskAi.shouldOffer] 四门控后才提示
 * （同文重复复制不重复打扰），Snackbar action「问 AI」点按才发送。
 *
 * [enabled]/[targetPackage] 作为 key：任一变化即重注册，保证门控取最新值。
 */
@Composable
private fun AskAiClipboardEffect(
    snackbarHostState: SnackbarHostState,
    enabled: Boolean,
    targetPackage: String?,
    onAction: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnAction by rememberUpdatedState(onAction)
    var lastOffered by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(enabled, targetPackage) {
        if (!enabled || targetPackage.isNullOrBlank()) {
            onPauseOrDispose { }
        } else {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                val clip = manager?.primaryClip ?: return@OnPrimaryClipChangedListener
                if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
                val item = clip.getItemAt(0) ?: return@OnPrimaryClipChangedListener
                val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
                if (!AskAi.shouldOffer(enabled, targetPackage, text, lastOffered)) {
                    return@OnPrimaryClipChangedListener
                }
                lastOffered = text
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(R.string.ask_ai_snackbar_message, AskAi.excerpt(text)),
                        actionLabel = context.getString(R.string.ask_ai_snackbar_action),
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) currentOnAction(text)
                }
            }
            manager?.addPrimaryClipChangedListener(listener)
            onPauseOrDispose { manager?.removePrimaryClipChangedListener(listener) }
        }
    }
}
