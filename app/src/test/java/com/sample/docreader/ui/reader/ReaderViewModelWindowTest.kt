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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

/** 内存生成的假文件：内容自定，length 可伪造（与 ReaderViewModelPagedTest 同款） */
private class WindowFileOpener(private val content: ByteArray) : FileOpener {
    override fun open(uri: String): InputStream = ByteArrayInputStream(content)
    override fun length(uri: String): Long? = content.size.toLong()
}

/**
 * M12b（F4.7）：ReaderViewModel 章级窗口与全局 item 坐标（TC-UI-10/11 的 VM 层底座）。
 * 与既有 ReaderViewModelTest 的构造/等待手法一致；旧 API 行为不变，本批次仅加法式扩展。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelWindowTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun newViewModel(scope: TestScope, opener: FileOpener = ResourceFileOpener()): ReaderViewModel {
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("window_${System.nanoTime()}.preferences_pb") }
        )
        return ReaderViewModel(opener, SettingsRepository(store))
    }

    private fun newViewModelWithProgress(
        scope: TestScope
    ): Triple<ReaderViewModel, ProgressRepository, AppDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val progress = ProgressRepository(db)
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("window_progress_${System.nanoTime()}.preferences_pb") }
        )
        return Triple(ReaderViewModel(ResourceFileOpener(), SettingsRepository(store), progress), progress, db)
    }

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

    private fun TestScope.awaitProgress(
        progress: ProgressRepository, uri: String, chapter: Int, paragraph: Int
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            advanceUntilIdle()
            val saved = kotlinx.coroutines.runBlocking { progress.progress(uri).first() }
            if (saved != null && saved.chapter == chapter && saved.paragraph == paragraph) return
            check(System.currentTimeMillis() < deadline) { "进度持久化超时" }
            Thread.sleep(20)
        }
    }

    /** 1. 多章 epub：初始只物化第 1 章，totalItems = 各章段落数之和 */
    @Test
    fun openMultiChapterEpub_initialWindowIsFirstChapter() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        val chapters = s.document!!.chapters
        assertTrue("sample.epub 应为多章", chapters.size >= 3)
        assertEquals(1, s.materializedChapterCount)
        assertEquals(chapters.sumOf { it.paragraphs.size }, s.totalItems)
        assertEquals(0, s.chapterIndex)
        db.close()
    }

    /** 2. 带进度恢复：物化到恢复章，restoreItem = globalItem(恢复章, 恢复段) */
    @Test
    fun restoreProgress_materializesThroughRestoredChapter() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        ProgressRepository(db).save("asset:///fixtures/sample.epub", chapter = 2, paragraph = 1)

        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertTrue("应物化到恢复章（含）", s.materializedChapterCount >= 3)
        val map = ChapterItemMap(s.document!!.chapters)
        assertEquals(map.globalItem(2, 1), s.restoreItem)
        assertEquals(2, s.chapterIndex)
        assertEquals(1, s.restoreParagraph)
        db.close()
    }

    /** 3. appendNextChapter：逐章递增，到末章后不再变化（末章停止） */
    @Test
    fun appendNextChapter_stopsAtLastChapter() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val chapterCount = vm.uiState.value.document!!.chapters.size
        assertEquals(1, vm.uiState.value.materializedChapterCount)

        vm.appendNextChapter() // 1 → 2
        vm.appendNextChapter() // 2 → 3
        vm.appendNextChapter() // 末章后空操作
        advanceUntilIdle()
        assertEquals(chapterCount, vm.uiState.value.materializedChapterCount)
        db.close()
    }

    /** 4. ensureMaterialized：扩到 chapter+1；越界 clamp 到章数且不抛异常 */
    @Test
    fun ensureMaterialized_extendsAndClamps() = runTest {
        // many_chapters.epub = 120 章，可同时覆盖「扩到 6」与「clamp 到章数」
        val (vm, _, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/many_chapters.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val chapterCount = vm.uiState.value.document!!.chapters.size
        assertTrue("夹具应 > 6 章", chapterCount > 6)

        vm.ensureMaterialized(5)
        advanceUntilIdle()
        assertEquals(6, vm.uiState.value.materializedChapterCount)

        vm.ensureMaterialized(999) // 越界：clamp 到章数，不报错
        advanceUntilIdle()
        assertEquals(chapterCount, vm.uiState.value.materializedChapterCount)
        db.close()
    }

    /** 5. onFirstVisibleItemChanged：全局 item → (章, 段) 换算、chapterIndex 跟随并防抖落库 */
    @Test
    fun onFirstVisibleItemChanged_mapsChapterAndPersists() = runTest {
        val (vm, progress, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val uri = "asset:///fixtures/sample.epub"
        val chapters = vm.uiState.value.document!!.chapters
        val map = ChapterItemMap(chapters)
        // 取末章末段（合法坐标，确保 locate 往返一致；sample.epub 章段落数不同，不能硬编码 (1,2)）
        val targetChapter = chapters.lastIndex
        val targetParagraph = chapters[targetChapter].paragraphs.lastIndex
        val target = map.globalItem(targetChapter, targetParagraph)
        assertEquals(ChapterPosition(targetChapter, targetParagraph), map.locate(target))

        vm.onFirstVisibleItemChanged(target)
        awaitProgress(progress, uri, chapter = targetChapter, paragraph = targetParagraph)
        assertEquals(targetChapter, vm.uiState.value.chapterIndex)
        db.close()
    }

    /** 6. txt 分页：loadMore 追加后 totalItems 同步为首章段落数（增长后） */
    @Test
    fun txtLoadMore_refreshesTotalItems() = runTest {
        val text = buildString {
            repeat(3000) { i -> appendLine("第 $i 行：M12b 窗口用例样本 $i") }
        }
        val vm = newViewModel(this, WindowFileOpener(text.toByteArray(Charsets.UTF_8)))
        vm.load("content://fake/big.txt", DocFormat.TXT, displayName = "big.txt")
        awaitLoaded(vm)

        val first = vm.uiState.value.document!!.chapters[0].paragraphs.size
        assertEquals(PagedTextLoader.CHUNK_PARAGRAPHS, first)
        assertEquals(first, vm.uiState.value.totalItems)
        assertEquals(1, vm.uiState.value.materializedChapterCount)

        vm.loadMore()
        awaitMore(vm)
        val after = vm.uiState.value.document!!.chapters[0].paragraphs.size
        assertTrue("loadMore 应追加段落", after > first)
        assertEquals(after, vm.uiState.value.totalItems)
    }

    // ---- M12 热修（F4.7 空章跳过）：真机《邓小平文选》纯空章致打开白屏/断链 ----

    /**
     * 造一个 OEBPS 布局的最小多章 epub（章节体由调用方给定）：
     * 空章 = body 内 `<p></p>`（flush 丢弃空白段 → paragraphs 空，与解析层契约一致）。
     */
    private fun buildWindowTestEpub(bodies: List<String>): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
        """.trimIndent()
        // 逐行拼接（不走缩进 heredoc 内插）：$items/$spine 的续行会让 trimIndent 最小缩进归零，
        // 导致 <?xml 声明前残留空格、解析报 Unexpected token（本类既有 VM 用例踩坑修正）
        val opf = buildList {
            add("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            add("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\">")
            add("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>t</dc:title></metadata>")
            add("<manifest>")
            bodies.indices.forEach { i ->
                add("<item id=\"ch$i\" href=\"ch$i.xhtml\" media-type=\"application/xhtml+xml\"/>")
            }
            add("</manifest>")
            add("<spine>")
            bodies.indices.forEach { i -> add("<itemref idref=\"ch$i\"/>") }
            add("</spine>")
            add("</package>")
        }.joinToString("\n")
        val entries = linkedMapOf<String, ByteArray>(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray()
        )
        bodies.forEachIndexed { i, body ->
            entries["OEBPS/ch$i.xhtml"] =
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>章$i</title></head><body>$body</body></html>"
                    .toByteArray(Charsets.UTF_8)
        }
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** 7. appendNextChapter 越过中间空章直达下一非空章（真机断链：物化空章 +0 item，snapshotFlow 不重发） */
    @Test
    fun appendNextChapter_skipsEmptyChapters() = runTest {
        val epub = buildWindowTestEpub(
            listOf("<p>第一章首段。</p>", "<p></p>", "<p>第三章首段。</p><p>第三章次段。</p>")
        )
        val vm = newViewModel(this, WindowFileOpener(epub))
        vm.load("mem:///empty_mid.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val chapters = vm.uiState.value.document!!.chapters
        assertEquals(3, chapters.size)
        assertTrue("中间章应为纯空章", chapters[1].paragraphs.isEmpty())
        assertEquals(1, vm.uiState.value.materializedChapterCount)

        vm.appendNextChapter() // 第 2 章为空 → 直跳第 3 章
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.materializedChapterCount)

        vm.appendNextChapter() // 已到末章，空操作
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.materializedChapterCount)
    }

    /** 8. 打开（无进度）落在纯空首章时前进到下一非空章，restoreItem 指向新章首（真机白屏修复） */
    @Test
    fun openWithLeadingEmptyChapter_advancesToFirstNonEmpty() = runTest {
        val epub = buildWindowTestEpub(listOf("<p></p>", "<p>正文首段。</p>"))
        val vm = newViewModel(this, WindowFileOpener(epub))
        vm.load("mem:///empty_head.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertEquals("应前进到第 2 章", 1, s.chapterIndex)
        assertEquals(0, s.restoreParagraph)
        assertEquals(2, s.materializedChapterCount)
        val map = ChapterItemMap(s.document!!.chapters)
        assertEquals(map.globalItem(1, 0), s.restoreItem)
    }

    /** 9. 空章边界：末章为空时 append clamp 书尾不越界；全空书 load/append 不崩（退化情形保持原章） */
    @Test
    fun emptyChapterEdge_tailEmptyAndAllEmpty_safe() = runTest {
        // 末章为空：追加到书尾即停
        val tailEpub = buildWindowTestEpub(listOf("<p>正文。</p>", "<p></p>"))
        val vmTail = newViewModel(this, WindowFileOpener(tailEpub))
        vmTail.load("mem:///empty_tail.epub", DocFormat.EPUB)
        awaitLoaded(vmTail)
        vmTail.appendNextChapter()
        advanceUntilIdle()
        assertEquals(2, vmTail.valueOfMaterialized())
        vmTail.appendNextChapter()
        advanceUntilIdle()
        assertEquals(2, vmTail.valueOfMaterialized())

        // 全空书：不崩、不越界、保持原章（无可渲染内容属退化情形）
        val allEpub = buildWindowTestEpub(listOf("<p></p>", "<p></p>"))
        val vmAll = newViewModel(this, WindowFileOpener(allEpub))
        vmAll.load("mem:///empty_all.epub", DocFormat.EPUB)
        awaitLoaded(vmAll)
        assertEquals(0, vmAll.uiState.value.chapterIndex)
        assertEquals(0, vmAll.uiState.value.totalItems)
        vmAll.appendNextChapter()
        advanceUntilIdle()
        assertEquals(2, vmAll.valueOfMaterialized())
    }

    private fun ReaderViewModel.valueOfMaterialized(): Int = uiState.value.materializedChapterCount
}
