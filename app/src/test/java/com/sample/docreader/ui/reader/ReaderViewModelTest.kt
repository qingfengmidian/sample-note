package com.sample.docreader.ui.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ReadingTheme
import com.sample.docreader.core.model.WrapMode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.io.InputStream

/** 测试用 FileOpener：从 test resources 的 fixtures/ 读文件，uri 形如 asset:///fixtures/xxx */
internal class ResourceFileOpener : FileOpener {
    override fun open(uri: String): InputStream =
        javaClass.classLoader!!.getResourceAsStream(uri.removePrefix("asset:///"))
            ?: throw FileNotFoundException(uri)
}

// Robolectric 提供 android.icu（CharsetDetector 反射调用），纯 JUnit 下会按探测失败回退 GBK 导致 UTF-8 乱码
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun newViewModel(scope: TestScope): Pair<ReaderViewModel, SettingsRepository> {
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("reader_${System.nanoTime()}.preferences_pb") }
        )
        val repo = SettingsRepository(store)
        return ReaderViewModel(ResourceFileOpener(), repo) to repo
    }

    /**
     * 等待 load 完成：TxtParser/PlainTextParser 内部 withContext(Dispatchers.IO) 走真实线程，
     * 测试调度器的 advanceUntilIdle 覆盖不到，需轮询等待加载结束再推进调度器。
     */
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

    @Test
    fun load_txt_success() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample_utf8.txt", DocFormat.TXT)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals("sample_utf8.txt", s.document?.name)
        assertEquals(DocFormat.TXT, s.document?.format)
        assertTrue(s.document!!.chapters[0].paragraphs.size > 5)
    }

    @Test
    fun load_md_usesPlainTextParser() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample.md", DocFormat.MD)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertEquals(DocFormat.MD, s.document?.format)
        assertTrue(s.document!!.chapters[0].paragraphs.any { it.startsWith("# 标题一") })
    }

    @Test
    fun load_missingFile_showsNoPermissionMessage() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/not_exist.txt", DocFormat.TXT)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.document)
        assertEquals(ParseException.NoPermission().message, s.error)
    }

    @Test
    fun load_epub_parsesChapters() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        assertEquals(DocFormat.EPUB, s.document?.format)
        assertTrue((s.document?.chapters?.size ?: 0) >= 3)
        assertEquals(0, s.chapterIndex)
    }

    /** TC-F2-18（VM 侧）：epub 重开恢复上次章节索引 */
    @Test
    fun load_epub_restoresChapterProgress() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val progress = ProgressRepository(db)
        progress.save("asset:///fixtures/sample.epub", chapter = 1, paragraph = 5)

        val store = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmpFolder.newFile("progress_${System.nanoTime()}.preferences_pb") }
        )
        val vm = ReaderViewModel(ResourceFileOpener(), SettingsRepository(store), progress)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        assertEquals(1, vm.uiState.value.chapterIndex)
        db.close()
    }

    /** TC-F2-24：0 字节空文件解析为全空段落，UI 据此显示空态提示 */
    @Test
    fun load_emptyFile_blankParagraphs() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/empty.txt", DocFormat.TXT)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        val paragraphs = s.document!!.chapters[0].paragraphs
        assertTrue(paragraphs.isEmpty() || paragraphs.all { it.isBlank() })
    }

    @Test
    fun settings_updateEffectiveImmediately_andPersisted() = runTest {
        val (vm, repo) = newViewModel(this)
        advanceUntilIdle()
        vm.stepFontSize(1)   // 16 → 18
        vm.cycleTheme()      // 跟随系统 → 默认白
        // 即时生效
        assertEquals(18, vm.uiState.value.settings.fontSizeSp)
        assertEquals(ReadingTheme.DEFAULT, vm.uiState.value.settings.theme)
        // 持久化：DataStore 落盘走真实 IO，虚拟时间覆盖不到，轮询等待
        val deadline = System.currentTimeMillis() + 10_000
        while (repo.settings.first().let { it.fontSizeSp != 18 || it.theme != ReadingTheme.DEFAULT }) {
            advanceUntilIdle()
            check(System.currentTimeMillis() < deadline) { "设置持久化超时" }
            Thread.sleep(20)
        }
    }

    @Test
    fun wrapToggle_andCycles_followSpecSteps() = runTest {
        val (vm, _) = newViewModel(this)
        advanceUntilIdle()
        vm.toggleWrap()
        assertEquals(WrapMode.NO_WRAP, vm.uiState.value.settings.wrapMode)
        vm.toggleWrap()
        assertEquals(WrapMode.WRAP, vm.uiState.value.settings.wrapMode)

        // 行距 150 → 180 → 200 → 120 循环
        vm.cycleLineHeight(); vm.cycleLineHeight(); vm.cycleLineHeight()
        assertEquals(120, vm.uiState.value.settings.lineHeightPercent)

        // 字号端点不越界
        repeat(10) { vm.stepFontSize(1) }
        assertEquals(24, vm.uiState.value.settings.fontSizeSp)
        repeat(10) { vm.stepFontSize(-1) }
        assertEquals(12, vm.uiState.value.settings.fontSizeSp)
    }
}
