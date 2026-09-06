package com.sample.docreader.core.io

import com.sample.docreader.core.parse.EpubParser
import com.sample.docreader.core.parse.Fixtures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * M11b epub 缓存解压层（设计 §3.2-5/§5.1，F2.14）：产物与 zip 条目一致、同书二次打开跳过、
 * 单实例 LRU（清理最旧且不删当前书）、取消后无半成品残留、失败回退 null 不阻塞阅读；
 * 并覆盖 parseFromDir（从缓存目录解析）与 parseBytes 产出一致、imageRoot 注入。
 */
@RunWith(RobolectricTestRunner::class)
class EpubCacheTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private var cacheRoot: File? = null

    private fun root(): File {
        if (cacheRoot == null) cacheRoot = tmpFolder.newFolder("epub_cache_root")
        return cacheRoot!!
    }

    private fun zipEntries(bytes: ByteArray): Set<String> {
        val names = linkedSetOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                names += e.name
                e = zip.nextEntry
            }
        }
        return names
    }

    private fun cacheEntries(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile && it.name != ".last_access" }
            .map { it.relativeTo(dir).path }.toSet()

    private fun buildZip(files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun containerOnly(bytes: ByteArray = """<container/>""".toByteArray()): Map<String, ByteArray> =
        linkedMapOf("META-INF/container.xml" to bytes)

    /** 解压产物与 zip 条目一一对应（忽略 .last_access 访问标记），目录 key = uri md5 前 16 位 */
    @Test
    fun extract_matchesZipEntriesAndKey() {
        val cache = EpubCache(root())
        val uri = "content://doc/book/sample.epub"
        val bytes = Fixtures.bytes("sample.epub")
        val dir = cache.extract(uri, input = { bytes.inputStream() })
        assertNotNull(dir)
        assertEquals(cache.hashOf(uri), dir!!.name)
        assertEquals("缓存内容应与 zip 条目一致", zipEntries(bytes), cacheEntries(dir))
        assertTrue("container.xml 存在 = 完整性标记", File(dir, "META-INF/container.xml").isFile)
        assertTrue(cache.isCached(uri))
    }

    /** I1：zip 显式目录条目（如 "OEBPS/"）应跳过而非当文件写——含目录条目的 epub 缓存解压不失败 */
    @Test
    fun extract_epubWithDirectoryEntries_succeeds() {
        val cache = EpubCache(root())
        val uri = "content://doc/sample_images.epub"
        val bytes = Fixtures.bytes("sample_images.epub")
        val dir = cache.extract(uri, input = { bytes.inputStream() })
        assertNotNull("含目录条目（zip -r 形态）也应解压成功", dir)
        assertTrue("目录条目不落为文件", !File(dir!!, "OEBPS").isFile)
        assertTrue("文件条目正常解压", File(dir, "OEBPS/images/fig1.png").isFile)
        assertTrue(File(dir, "META-INF/container.xml").isFile)
        assertTrue(cache.isCached(uri))
    }

    /** 同书二次打开：目录完整即跳过解压（删掉一个文件后二次 extract 不恢复它，证明未重新解压） */
    @Test
    fun sameUriSecondOpen_skipsReExtract() {
        val cache = EpubCache(root())
        val uri = "content://doc/sample.epub"
        val bytes = Fixtures.bytes("sample.epub")
        val dir1 = cache.extract(uri, input = { bytes.inputStream() })!!
        val victim = File(dir1, "OEBPS/ch2.xhtml")
        assertTrue(victim.isFile)
        victim.delete()
        val dir2 = cache.extract(uri, input = { bytes.inputStream() })
        assertEquals("二次打开应返回同一目录", dir1, dir2)
        assertFalse("二次打开跳过解压（不恢复被删文件）", File(dir2!!, "OEBPS/ch2.xhtml").exists())
    }

    /** 单实例 LRU：新书体积为预算，超限清理**最旧**目录且**不删当前书** */
    @Test
    fun lru_evictsOldest_keepsCurrent() {
        val cache = EpubCache(root())
        val big = containerOnly(ByteArray(200 * 1024) { 'x'.code.toByte() }) // A：大文件（旧）
        val small = containerOnly()                                          // B：小文件（新，预算小）
        val uriA = "content://doc/A.epub"
        val uriB = "content://doc/B.epub"
        val dirA = cache.extract(uriA, input = { buildZip(big).inputStream() })
        val dirB = cache.extract(uriB, input = { buildZip(small).inputStream() })
        assertNotNull(dirA)
        assertNotNull(dirB)
        // B 的体积预算远小于 A，进入 B 后超限 → 清掉最旧的 A，保留当前书 B
        assertFalse("最旧目录 A 应被 LRU 清理", dirA!!.exists())
        assertTrue("当前书 B 不应被清理", dirB!!.exists())
        assertTrue(cache.isCached(uriB))
    }

    /** 取消（模拟协程取消置中断）后：抛 CancellationException、本次半成品目录被清理、不留 tmp */
    @Test
    fun cancelDuringExtract_leavesNoPartial() {
        val cache = EpubCache(root())
        val uri = "content://doc/cancel.epub"
        val bytes = Fixtures.bytes("sample.epub")
        var checks = 0
        try {
            cache.extract(uri, input = { bytes.inputStream() }, cancelCheck = { ++checks >= 3 })
            fail("取消后应抛 CancellationException")
        } catch (e: CancellationException) {
            // 预期
        }
        assertFalse("目标目录不应落盘（半成品清理）", cache.isCached(uri))
        val leftovers = root().listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        assertTrue("不应残留 tmp_*/目标半成品目录，实际：$leftovers",
            leftovers.all { !it.startsWith("tmp_") && !it.startsWith(cache.hashOf(uri)) })
    }

    /** 失败容忍：非 zip 输入 → 返回 null 且不残留任何缓存目录（调用方回退字节解析，图片走占位） */
    @Test
    fun invalidInput_returnsNull_noLeftover() {
        val cache = EpubCache(root())
        val result = cache.extract("content://doc/garbage.epub", input = { "not a zip at all".byteInputStream() })
        assertNull("非法 epub 输入应返回 null 而非抛异常", result)
        assertEquals("失败后缓存根不应残留目录", 0,
            root().listFiles { f -> f.isDirectory }?.size ?: 0)
    }

    /** parseFromDir（缓存目录解析）与 parseBytes（整字节解析）产出一致，imageRoot 注入缓存目录 */
    @Test
    fun parseFromDir_equalsParseBytes_andSetsImageRoot() = runBlocking {
        val cache = EpubCache(root())
        val uri = "content://doc/sample_images.epub"
        val bytes = Fixtures.bytes("sample_images.epub")
        val dir = cache.extract(uri, input = { bytes.inputStream() })!!
        val parser = EpubParser()
        val docFromDir = parser.parseFromDir(dir, uri, "sample_images.epub")
        val docFromBytes = parser.parseBytes(bytes, "sample_images.epub", uri)
        assertEquals("缓存目录解析应产出与整字节解析一致的章节（含图片）", docFromBytes.chapters, docFromDir.chapters)
        assertEquals(dir.absolutePath, docFromDir.imageRoot)
        assertNull("整字节解析（缓存不可用回退）不注入 imageRoot", docFromBytes.imageRoot)
        assertTrue(docFromDir.chapters[0].images.isNotEmpty())
    }
}
