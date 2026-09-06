package com.sample.docreader.core.io

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * epub 解压缓存层（《软件设计文档》§3.2-5 / §5.1，M11b F2.14）：
 * SAF content:// 流不支持随机读，而图片需懒加载（BitmapFactory.decodeFile）——打开 epub 时一次性解压到
 * 应用私有缓存 `cache/epub/<uri md5 前16位>/`，解析与图片渲染**均只从缓存目录读**。
 *
 * 约定：
 * - 完整性标记 = `META-INF/container.xml` 存在；同书二次打开目录完整即跳过（幂等）；
 * - **单实例 LRU**：解压"新书"完成后，以当前书解压后体积为预算，按最近访问时间清理最旧目录，**不删当前书**；
 * - **取消安全（C4）**：解压循环段级检查 [cancelCheck]，默认取线程中断（生产经 runInterruptible 由协程取消触发）；
 *   取消即抛 [CancellationException] 并清理本次临时目录，**不留半成品**；
 * - **失败容忍**：IO/磁盘满/非 zip 输入一律清理本次残留并返回 null——调用方回退整书字节解析，图片按占位处理，不阻塞阅读；
 * - zip-slip 防护：拒绝绝对路径与含 `..` 段的条目名，防解压越出缓存根目录。
 */
class EpubCache(private val rootDir: File) {

    /** 目录 key：uri 的 md5 取前 16 位（确定性短哈希，同草稿恢复文件命名约定） */
    fun hashOf(uri: String): String =
        MessageDigest.getInstance("MD5")
            .digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /**
     * 解压（或定位）epub 到缓存目录。失败返回 null；取消抛 [CancellationException]。
     * [input] 惰性提供原始 epub 流（调用方在 IO 线程打开，避免提前占流）。
     */
    fun extract(
        uri: String,
        input: () -> InputStream,
        cancelCheck: () -> Boolean = { Thread.currentThread().isInterrupted }
    ): File? {
        val key = hashOf(uri)
        val target = File(rootDir, key)
        if (isComplete(target)) {
            touch(target)
            return target // 同书二次打开：存在即跳过
        }
        clearStaleTmp()
        if (target.exists()) target.deleteRecursively() // 上次取消/失败留下的半成品先清掉
        val tmp = File(rootDir, "tmp_$key")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            val written = input().use { extractEntries(tmp, it, cancelCheck) }
            if (!File(tmp, "META-INF/container.xml").isFile) {
                // 结构不完整（非合法 epub 容器）不作为缓存落盘，调用方走字节解析以得到差异化错误
                tmp.deleteRecursively()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.deleteRecursively()
                return null
            }
            touch(target)
            evictLru(keep = target, budget = written)
            return target
        } catch (e: CancellationException) {
            tmp.deleteRecursively() // 不留半成品
            throw e
        } catch (e: Exception) {
            tmp.deleteRecursively() // 磁盘满/IO/非 zip 等：失败不阻塞阅读
            return null
        }
    }

    /** 缓存目录是否存在且解压完成（container.xml 为完整标记） */
    fun isCached(uri: String): Boolean = isComplete(File(rootDir, hashOf(uri)))

    /** 删除该书缓存目录（M1：缓存目录解析失败后删除并重抽一次时调用），不抛异常 */
    fun delete(uri: String) {
        File(rootDir, hashOf(uri)).deleteRecursively()
    }

    private fun isComplete(dir: File): Boolean = File(dir, "META-INF/container.xml").isFile

    /** 遍历 zip 条目解压到 [dir]；返回解压写入的总字节数（LRU 预算 = 当前书体积）。 */
    private fun extractEntries(dir: File, stream: InputStream, cancelCheck: () -> Boolean): Long {
        var total = 0L
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                if (cancelCheck()) throw CancellationException("epub 缓存解压被取消")
                val entry = zip.nextEntry ?: break
                val name = entry.name
                // 目录条目（zip -r 常见，如 "OEBPS/"）无内容：直接跳过，不得当文件写（否则必抛 IOException）
                if (entry.isDirectory || name.endsWith('/')) continue
                if (safeEntryName(name)) {
                    val bytes = zip.readBytes()
                    total += bytes.size
                    val out = File(dir, name)
                    out.parentFile?.mkdirs()
                    out.writeBytes(bytes)
                } else {
                    // 越界条目名：跳过写入（zip-slip 防护），但仍需消费内容推进流
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (zip.read(buf, 0, buf.size) > 0) Unit
                }
            }
        }
        return total
    }

    /** zip-slip 防护：拒绝绝对路径与含 .. 段（含形如 a/../ 的逃逸）的条目 */
    private fun safeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith('/') || name.contains('\u0000')) return false
        return name.split('/').none { it == ".." }
    }

    /** 更新最近访问标记（供 LRU 排序；文件名以点开头，几乎不与 epub 条目冲突） */
    private fun touch(dir: File) {
        runCatching { File(dir, ACCESS_MARKER).writeText(System.currentTimeMillis().toString()) }
    }

    /**
     * 单实例 LRU 清理：总占用超过预算（当前书体积）时，从最久未访问目录开始删除（**跳过 [keep] 当前书**），
     * 直到占用 ≤ 预算或无其它目录可删。删除按最近访问时间升序（最旧先删）。
     */
    private fun evictLru(keep: File, budget: Long) {
        if (totalBytes(rootDir) <= budget) return
        val others = rootDir.listFiles { f -> f.isDirectory && f.name != keep.name }?.toMutableList() ?: return
        others.sortBy { lastAccess(it) } // 最旧在前
        for (dir in others) {
            if (totalBytes(rootDir) <= budget) break
            dir.deleteRecursively()
        }
    }

    private fun clearStaleTmp() {
        // 进程被杀遗留的 tmp_* 半成品清理（不入 LRU 排序，避免占空间）
        rootDir.listFiles { f -> f.isDirectory && f.name.startsWith("tmp_") }?.forEach { it.deleteRecursively() }
    }

    private fun lastAccess(dir: File): Long {
        val marker = File(dir, ACCESS_MARKER)
        val markerTime = if (marker.isFile) marker.lastModified() else 0L
        return markerTime.coerceAtLeast(dir.lastModified())
    }

    private fun totalBytes(dir: File): Long =
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val ACCESS_MARKER = ".last_access"
    }
}
