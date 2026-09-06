package com.sample.docreader.core.io

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sample.docreader.BuildConfig
import java.io.FileNotFoundException
import java.io.InputStream

/** SAF/ContentResolver 实现（M4 正式实现，设计§3.6）。uri 形如 content://... */
class ContentResolverFileOpener(private val resolver: ContentResolver) : FileOpener {
    override fun open(uri: String): InputStream =
        resolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException(uri)

    /** 文件长度（M8b）：优先 AssetFileDescriptor；-1/不可得时回退查询 OpenableColumns.SIZE；都没有返回 null */
    override fun length(uri: String): Long? {
        val parsed = Uri.parse(uri)
        val fdLength = runCatching {
            resolver.openAssetFileDescriptor(parsed, "r")?.use { it.length }
        }.getOrNull()
        if (fdLength != null && fdLength >= 0) return fdLength
        return runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }.getOrNull()
    }
}

/**
 * 按 scheme 分发的 FileOpener：asset:/// 走 debug fixtures 桩（M3 开发入口保留），
 * 其余走 SAF/ContentResolver。
 * U6（M10）：asset 分支仅在 debug 构建可用（BuildConfig.DEBUG 门控）；release 遇 asset URI
 * 直接走"文件不可访问"路径（FileNotFoundException → UI 提示无权限/已被移动）。
 */
class DelegatingFileOpener(context: Context) : FileOpener {
    private val assetsOpener = AssetsFileOpener(context.assets)
    private val safOpener = ContentResolverFileOpener(context.contentResolver)

    /** asset URI 是否可用（仅 debug；U6 门控） */
    private fun assetEnabled(uri: String) = BuildConfig.DEBUG && uri.startsWith("asset:///")

    override fun open(uri: String): InputStream = when {
        assetEnabled(uri) -> assetsOpener.open(uri)
        uri.startsWith("asset:///") -> throw FileNotFoundException("asset 入口仅 debug 构建可用：$uri")
        else -> safOpener.open(uri)
    }

    override fun length(uri: String): Long? = when {
        assetEnabled(uri) -> assetsOpener.length(uri)
        uri.startsWith("asset:///") -> null // release 下 asset 不可用，长度未知
        else -> safOpener.length(uri)
    }
}
