package com.sample.docreader.core.io

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 编辑草稿存储（M7c，设计§3.4 草稿段，F4.6/TC-F4-06/06a）：
 * 编辑正文落应用私有目录 `filesDir/edit_recovery/<md5(uri) hex 前 16 字符>.draft`，
 * 供进程被杀后跨进程恢复（uri/光标等轻量状态在 SavedStateHandle，正文只在草稿文件）。
 *
 * - MD5 仅作文件名散列，避免 uri 直接进入文件路径，非安全用途；
 * - 只碰 filesDir 私有目录，不走 SAF、不需要任何权限、不联网；
 * - [write] 全量写后读回校验字节完整性（同 M7b save 临时文件校验精神），校验失败删除并抛错。
 */
class DraftStore(filesDir: File) {

    /** 草稿目录，惰性创建（首次 write 时 mkdirs） */
    private val dir = File(filesDir, DIR_NAME)

    /** 纯路径计算：返回 uri 对应的草稿文件（不要求文件已存在） */
    fun draftFileFor(uri: String): File = File(dir, md5Hex16(uri) + EXTENSION)

    /** 写入草稿（UTF-8），写后读回校验；校验失败删除残缺文件并抛 IOException */
    fun write(uri: String, text: String) {
        dir.mkdirs()
        val file = draftFileFor(uri)
        file.writeText(text, Charsets.UTF_8)
        val verified = file.readBytes().contentEquals(text.toByteArray(Charsets.UTF_8))
        if (!verified) {
            file.delete()
            throw IOException("草稿写入校验失败")
        }
    }

    /** 读取草稿；不存在或读取失败返回 null */
    fun read(uri: String): String? {
        val file = draftFileFor(uri)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** 删除 uri 对应草稿（不存在时空操作） */
    fun delete(uri: String) {
        draftFileFor(uri).delete()
    }

    /**
     * 清理孤儿草稿（TC-F4-06a ③）：删除 `edit_recovery` 下
     * `lastModified < now - olderThanMs` 的 .draft 文件，返回删除数量。
     * 目录不存在时返回 0；恰好等于阈值的文件保留（严格小于才删）。
     */
    fun cleanupOrphans(
        olderThanMs: Long = DEFAULT_ORPHAN_AGE_MS,
        now: Long = System.currentTimeMillis()
    ): Int {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: return 0
        val cutoff = now - olderThanMs
        var deleted = 0
        for (file in files) {
            if (file.lastModified() < cutoff && file.delete()) deleted++
        }
        return deleted
    }

    companion object {
        /** 草稿目录名（filesDir 下） */
        const val DIR_NAME = "edit_recovery"

        /** 草稿文件扩展名 */
        const val EXTENSION = ".draft"

        /** 孤儿草稿默认存活期：7 天（TC-F4-06a） */
        const val DEFAULT_ORPHAN_AGE_MS = 7L * 24 * 3600 * 1000

        /** uri → MD5 十六进制前 16 字符（文件名散列，非安全用途） */
        internal fun md5Hex16(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)
    }
}
