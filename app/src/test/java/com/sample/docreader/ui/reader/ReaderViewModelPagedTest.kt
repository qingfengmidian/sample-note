package com.sample.docreader.ui.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.parse.PagedTextLoader
import com.sample.docreader.core.progress.ProgressRepository
import com.sample.docreader.core.recent.AppDatabase
import com.sample.docreader.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream

/** 内存生成的假文件：内容自定，length 可伪造（editable 判定路径） */
private class GeneratedFileOpener(
    private val content: ByteArray,
    private val spoofedLength: Long? = content.size.toLong()
) : FileOpener {
    override fun open(uri: String): InputStream = ByteArrayInputStream(content)
    override fun length(uri: String): Long? = spoofedLength
}

/**
 * M8b：ReaderViewModel txt 分块加载（F4.2/TC-F4-02）。
 * txt 一律走分页路径：小文件首块即全文（行为与全量解析一致），大文件首屏 200 段、loadMore 追加。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelPagedTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun bigText(lines: Int): String = buildString {
        repeat(lines) { i -> appendLine("第 $i 行：分块加载验证样本，含多字节字符 🎉 $i") }
    }

    private fun newViewModel(
        scope: TestScope,
        opener: FileOpener,
        withProgress: Boolean = false
    ): Triple<ReaderViewModel, ProgressRepository?, AppDatabase?> {
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("paged_${System.nanoTime()}.preferences_pb") }
        )
        if (!withProgress) return Triple(ReaderViewModel(opener, SettingsRepository(store)), null, null)
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val progress = ProgressRepository(db)
        return Triple(ReaderViewModel(opener, SettingsRepository(store), progress), progress, db)
    }

    /** 与 ReaderViewModelTest 同款：解析内部走真实 IO 线程，需轮询 + 推进调度器 */
    private fun TestScope.awaitLoaded(vm: ReaderViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.uiState.value.loading) {
            advanceUntilIdle()
            if (!vm.uiState.value.loading) break
            check(System.currentTimeMillis() < deadline) { "加载超时" }
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    private fun TestScope.awaitMore(vm: ReaderViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.uiState.value.loadingMore) {
            advanceUntilIdle()
            if (!vm.uiState.value.loadingMore) break
            check(System.currentTimeMillis() < deadline) { "追加超时" }
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    /** 大 txt 首屏只载一块（200 段），hasMore=true，不阻塞首屏（F4.2 首屏 ≤3s 的结构保证） */
    @Test
    fun load_largeTxt_firstChunkOnly() = runTest {
        val text = bigText(3000)
        val (vm, _, _) = newViewModel(this, GeneratedFileOpener(text.toByteArray(Charsets.UTF_8)))
        vm.load("content://fake/big.txt", DocFormat.TXT, displayName = "big.txt")
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertEquals(PagedTextLoader.CHUNK_PARAGRAPHS, s.document!!.chapters[0].paragraphs.size)
        assertTrue(s.hasMore)
        assertFalse(s.loadingMore)
    }

    /** 持续 loadMore 至 EOF：总段落与 lines() 黄金对照一致，索引只增不改 */
    @Test
    fun loadMore_untilEof_matchesFullLines() = runTest {
        val text = bigText(3000)
        val golden = text.lines()
        val (vm, _, _) = newViewModel(this, GeneratedFileOpener(text.toByteArray(Charsets.UTF_8)))
        vm.load("content://fake/big.txt", DocFormat.TXT, displayName = "big.txt")
        awaitLoaded(vm)

        var guard = 0
        while (vm.uiState.value.hasMore) {
            val before = vm.uiState.value.document!!.chapters[0].paragraphs.size
            vm.loadMore()
            awaitMore(vm)
            val after = vm.uiState.value.document!!.chapters[0].paragraphs.size
            assertTrue("每次 loadMore 必须追加段落（防死循环）", after > before)
            check(++guard < 100) { "追加次数异常" }
        }
        assertEquals(golden, vm.uiState.value.document!!.chapters[0].paragraphs)
    }

    /** 阅读进度恢复到首块之外（段落 2500）：load 内应持续追加直至覆盖该段落再恢复 */
    @Test
    fun progressRestore_beyondFirstChunk_loadsUntilCovered() = runTest {
        val text = bigText(3000)
        val uri = "content://fake/big.txt"
        val (vm, progress, db) = newViewModel(this, GeneratedFileOpener(text.toByteArray(Charsets.UTF_8)), withProgress = true)
        try {
            progress!!.save(uri, 0, 2500)
            advanceUntilIdle()
            vm.load(uri, DocFormat.TXT, displayName = "big.txt")
            awaitLoaded(vm)
            val s = vm.uiState.value
            assertNull(s.error)
            assertEquals(2500, s.restoreParagraph)
            assertTrue("已加载段落须覆盖恢复点", s.document!!.chapters[0].paragraphs.size > 2500)
        } finally {
            db?.close()
        }
    }

    /** editable 判定走 FileOpener.length：伪造 15MB → 只读（TC-F3-09 在分块路径下保持） */
    @Test
    fun editableFalse_whenLengthExceedsMax() = runTest {
        val small = "短内容".toByteArray(Charsets.UTF_8)
        val (vm, _, _) = newViewModel(
            this,
            GeneratedFileOpener(small, spoofedLength = 15L * 1024 * 1024)
        )
        vm.load("content://fake/huge.txt", DocFormat.TXT, displayName = "huge.txt")
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertFalse(s.editable)
    }

    /** 小 txt 走分页路径但行为透明：首块即全文、hasMore=false、loadMore 为空操作 */
    @Test
    fun smallTxt_pagedPathTransparent() = runTest {
        val text = "一\n二\n三\n"
        val (vm, _, _) = newViewModel(this, GeneratedFileOpener(text.toByteArray(Charsets.UTF_8)))
        vm.load("content://fake/small.txt", DocFormat.TXT, displayName = "small.txt")
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertEquals(text.lines(), s.document!!.chapters[0].paragraphs)
        assertFalse(s.hasMore)
        assertTrue(s.editable)

        vm.loadMore() // EOF 后空操作：状态不变
        advanceUntilIdle()
        assertEquals(text.lines(), vm.uiState.value.document!!.chapters[0].paragraphs)
        assertFalse(vm.uiState.value.loadingMore)
    }

    /** md 仍走全量解析，不进分页路径（不回归） */
    @Test
    fun md_staysFullParse() = runTest {
        val text = "# 标题\n\n正文段落\n"
        val (vm, _, _) = newViewModel(this, GeneratedFileOpener(text.toByteArray(Charsets.UTF_8)))
        vm.load("content://fake/doc.md", DocFormat.MD, displayName = "doc.md")
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertEquals(text.lines(), s.document!!.chapters[0].paragraphs)
        assertFalse(s.hasMore)
    }
}
