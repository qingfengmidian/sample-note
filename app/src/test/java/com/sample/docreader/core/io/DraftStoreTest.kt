package com.sample.docreader.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

/**
 * TC-UT-09b（DraftStore 侧补充，M7c 由 OpenCode 编写）：
 * md5 文件名契约、读写回环、delete、cleanupOrphans 边界（含 7 天临界）。
 * EditorDraftTest 已覆盖 VM 联动，这里钉 DraftStore 自身行为。
 */
@RunWith(RobolectricTestRunner::class)
class DraftStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun newStore(): DraftStore = DraftStore(tmpFolder.newFolder("files"))

    private fun md5Hex16(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)

    /** 文件名契约：edit_recovery/<md5(uri) 前 16>.draft；纯路径计算不要求文件存在 */
    @Test
    fun draftFileFor_md5Name_underEditRecovery() {
        val store = newStore()
        val uri = "content://com.example.provider/docs/note.txt"
        val file = store.draftFileFor(uri)
        assertEquals(md5Hex16(uri) + ".draft", file.name)
        assertEquals("edit_recovery", file.parentFile!!.name)
        assertFalse("纯路径计算不应创建文件", file.exists())
    }

    /** 写读回环：中文正文 UTF-8 往返一致 */
    @Test
    fun writeThenRead_roundtrip() {
        val store = newStore()
        val uri = "content://a"
        val text = "第一行\n第二行 中文内容"
        store.write(uri, text)
        assertEquals(text, store.read(uri))
    }

    /** 不存在返回 null；删除后返回 null */
    @Test
    fun read_missing_returnsNull_delete_removes() {
        val store = newStore()
        val uri = "content://b"
        assertNull(store.read(uri))
        store.write(uri, "草稿")
        store.delete(uri)
        assertNull(store.read(uri))
        assertFalse(store.draftFileFor(uri).exists())
    }

    /** 覆盖写：同一 uri 再写，旧内容被替换 */
    @Test
    fun write_overwritesExisting() {
        val store = newStore()
        val uri = "content://c"
        store.write(uri, "旧草稿")
        store.write(uri, "新草稿")
        assertEquals("新草稿", store.read(uri))
    }

    /**
     * cleanupOrphans 边界（TC-F4-06a ③）：7 天临界——
     * 恰好 7 天前（lastModified == now - 7d）保留，早 1ms 删除；非 .draft 文件不动。
     */
    @Test
    fun cleanupOrphans_sevenDayBoundary() {
        val store = newStore()
        store.write("content://old", "超期")
        store.write("content://edge", "临界")
        store.write("content://fresh", "新鲜")
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L
        assertTrue(store.draftFileFor("content://old").setLastModified(now - 7 * day - 1))
        assertTrue(store.draftFileFor("content://edge").setLastModified(now - 7 * day))
        assertTrue(store.draftFileFor("content://fresh").setLastModified(now - 1000))
        // 非草稿文件：即使超期也不删
        val other = File(store.draftFileFor("x").parentFile!!, "notes.tmp")
        other.writeText("别动我")
        assertTrue(other.setLastModified(now - 30 * day))

        val deleted = store.cleanupOrphans(now = now)

        assertEquals(1, deleted)
        assertFalse(store.draftFileFor("content://old").exists())
        assertTrue("恰好 7 天（== 阈值）应保留", store.draftFileFor("content://edge").exists())
        assertTrue(store.draftFileFor("content://fresh").exists())
        assertTrue("非 .draft 文件不清理", other.exists())
    }

    /** 草稿目录不存在时清理返回 0（首次启动尚未写过草稿） */
    @Test
    fun cleanupOrphans_missingDir_returnsZero() {
        val store = newStore()
        assertEquals(0, store.cleanupOrphans())
    }
}
