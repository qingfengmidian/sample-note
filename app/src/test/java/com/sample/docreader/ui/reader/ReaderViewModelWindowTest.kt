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
}
