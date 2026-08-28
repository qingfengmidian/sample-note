package com.sample.docreader.core.io

import android.content.res.AssetManager
import java.io.InputStream

/**
 * 文件打开抽象（M1 预置，见《开发计划》M1/M3/M4）。
 * M3 阶段由 [AssetsFileOpener] 桩从内置 fixtures 提供文件输入；
 * M4 替换为 SAF/ContentResolver 实现，接口不变。
 */
interface FileOpener {
    /** 打开文档内容流。uri 形式由实现定义（asset:/// 或 content://）。 */
    fun open(uri: String): InputStream

    /**
     * 文件字节长度（M8b，用于 txt 分块加载的 editable 判定与进度参考）。
     * 无法获知时返回 null（调用方按未知处理：先按可编辑，EOF 后按累计字节修正）。
     */
    fun length(uri: String): Long? = null
}

/** M3 开发期桩：从 debug assets 读取 fixtures。uri 形如 asset:///fixtures/sample_utf8.txt */
class AssetsFileOpener(private val assets: AssetManager) : FileOpener {
    override fun open(uri: String): InputStream =
        assets.open(uri.removePrefix("asset:///"))
}
