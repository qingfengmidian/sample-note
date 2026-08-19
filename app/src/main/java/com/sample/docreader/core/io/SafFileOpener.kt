package com.sample.docreader.core.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

/** SAF/ContentResolver 实现（M4 正式实现，设计§3.6）。uri 形如 content://... */
class ContentResolverFileOpener(private val resolver: ContentResolver) : FileOpener {
    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)
}

/**
 * 按 scheme 分发的 FileOpener：asset:/// 走 debug fixtures 桩（M3 开发入口保留），
 * 其余走 SAF/ContentResolver。M5+ 全量切换后可移除 asset 分支。
 */
class DelegatingFileOpener(context: Context) : FileOpener {
    private val assetsOpener = AssetsFileOpener(context.assets)
    private val safOpener = ContentResolverFileOpener(context.contentResolver)

    override fun open(uri: String): InputStream =
        if (uri.startsWith("asset:///")) assetsOpener.open(uri) else safOpener.open(uri)
}
