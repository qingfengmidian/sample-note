package com.sample.docreader.ui.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 编辑保存成功后的阅读页重载信号（M7b，TC-F3-02 依赖）。
 *
 * EditorScreen 无法在不改 MainActivity 路由结构的前提下拿到
 * `navController.previousBackStackEntry.savedStateHandle`（简报要求不动 MainActivity），
 * 故以进程内单例记录"已保存 uri"集合作为等价回传通道：
 * 保存成功 → [notifySaved]，ReaderScreen 观察到自身 uri 被标记即 [consume] 并重载。
 * 进程被杀后集合清空——此时保存已落盘，重开文件自然读到新内容，无副作用。
 */
object ReaderReloadNotifier {
    private val _savedUris = MutableStateFlow<Set<String>>(emptySet())

    /** 已保存待重载的 uri 集合（ReaderScreen 观察用） */
    val savedUris: StateFlow<Set<String>> = _savedUris.asStateFlow()

    fun notifySaved(uri: String) = _savedUris.update { it + uri }

    fun consume(uri: String) = _savedUris.update { it - uri }
}
