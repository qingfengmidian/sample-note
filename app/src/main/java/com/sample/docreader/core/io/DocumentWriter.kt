package com.sample.docreader.core.io

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * 文档写回抽象（M7b，设计§3.4）：编辑保存经此接口以指定 mode 打开输出流。
 * 与 [FileOpener] 对称，解析/读写与 UI 层解耦，测试用假实现注入即可驱动保存流程。
 */
interface DocumentWriter {
    /** 以 mode（"wt"/"w" 截断写）打开 uri 的输出流。 */
    fun openOutput(uri: String, mode: String): OutputStream
}

/**
 * SAF/ContentResolver 实现（M7b，设计§3.4/§3.6）。
 * "wt"/"w" 均为截断写，S1 探针验证 Drive 上两者成功且正确截断。
 */
class ContentResolverDocumentWriter(private val resolver: ContentResolver) : DocumentWriter {
    override fun openOutput(uri: String, mode: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), mode) ?: throw FileNotFoundException(uri)
}
