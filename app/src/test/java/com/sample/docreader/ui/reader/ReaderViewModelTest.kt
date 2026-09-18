package com.sample.docreader.ui.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.ParseException
import com.sample.docreader.core.model.ReaderFont
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
import kotlinx.coroutines.test.advanceTimeBy
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

    /** 带进度仓库的 ViewModel（内存 Room）；用完需 close db */
    private fun newViewModelWithProgress(
        scope: TestScope
    ): Triple<ReaderViewModel, ProgressRepository, AppDatabase> {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val progress = ProgressRepository(db)
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("progress_${System.nanoTime()}.preferences_pb") }
        )
        return Triple(ReaderViewModel(ResourceFileOpener(), SettingsRepository(store), progress), progress, db)
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

    /** TC-F1-09：显示名经路由传入后应优先于 URI 末段（避免 content:// 的百分号编码段当标题） */
    @Test
    fun load_prefersDisplayNameOverUriSegment() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample_utf8.txt", DocFormat.TXT, displayName = "自定义名.txt")
        awaitLoaded(vm)
        assertEquals("自定义名.txt", vm.uiState.value.document?.name)
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
        assertEquals(ReaderError.NoPermission, s.error)
    }

    /** TC-F2-23（端到端，M8a）：broken.epub（缺 OPF）经完整 load 链路，error 透传 MissingOpf 差异化文案，非笼统"文件损坏" */
    @Test
    fun load_brokenEpub_showsMissingOpfMessage() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/broken.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.document)
        assertEquals(ReaderError.MissingOpf, s.error)
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

    /**
     * 等待进度落库：persistProgress 经 viewModelScope 异步写 Room（滚动路径再防抖 500ms，G4-code），
     * 轮询直至读到预期值。expected 为 null 时只推进调度器（用于断言"未写入"的场景）。
     */
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

    /** TC-F2-18（VM 侧）：epub 重开恢复上次章节索引；段落越界时 clamp 到该章末尾 */
    @Test
    fun load_epub_restoresChapterProgress() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        // sample.epub 第 2 章实际段落数 < 6，paragraph=5 应被 clamp 到 lastIndex
        ProgressRepository(db).save("asset:///fixtures/sample.epub", chapter = 1, paragraph = 5)

        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertEquals(1, s.chapterIndex)
        assertEquals(s.document!!.chapters[1].paragraphs.lastIndex, s.restoreParagraph)
        db.close()
    }

    /** TC-F2-18：chapter 越界上界 clamp 到末章 */
    @Test
    fun load_epub_chapterBeyondLast_clampedToLastChapter() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        ProgressRepository(db).save("asset:///fixtures/sample.epub", chapter = 99, paragraph = 2)

        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        val lastChapter = s.document!!.chapters.lastIndex
        assertEquals(lastChapter, s.chapterIndex)
        assertEquals(2.coerceAtMost(s.document.chapters[lastChapter].paragraphs.lastIndex), s.restoreParagraph)
        db.close()
    }

    /** TC-F2-18：负值 chapter/paragraph clamp 到 0 */
    @Test
    fun load_epub_negativeProgress_clampedToZero() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        ProgressRepository(db).save("asset:///fixtures/sample.epub", chapter = -1, paragraph = -3)

        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertEquals(0, s.chapterIndex)
        assertEquals(0, s.restoreParagraph)
        db.close()
    }

    /** TC-F2-18：selectChapter 越界（-1 与 size）被忽略，状态不变 */
    @Test
    fun selectChapter_outOfRange_ignored() = runTest {
        val (vm, _, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)
        val before = vm.uiState.value

        vm.selectChapter(-1)
        vm.selectChapter(before.document!!.chapters.size)
        advanceUntilIdle()

        val after = vm.uiState.value
        assertEquals(before.chapterIndex, after.chapterIndex)
        assertEquals(before.restoreParagraph, after.restoreParagraph)
        db.close()
    }

    /** TC-F2-18：selectChapter 合法跳转 → chapterIndex 更新、restoreParagraph=0 且进度落库 */
    @Test
    fun selectChapter_valid_jumpAndPersist() = runTest {
        val (vm, progress, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)

        vm.selectChapter(2)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(2, s.chapterIndex)
        assertEquals(0, s.restoreParagraph)
        awaitProgress(progress, "asset:///fixtures/sample.epub", chapter = 2, paragraph = 0)
        db.close()
    }

    /** TC-F2-18：onFirstVisibleParagraphChanged 负数 clamp 为 0 并落库 */
    @Test
    fun onFirstVisibleParagraphChanged_negative_clampedAndPersisted() = runTest {
        val (vm, progress, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)

        vm.onFirstVisibleParagraphChanged(-5)
        awaitProgress(progress, "asset:///fixtures/sample.epub", chapter = 0, paragraph = 0)
        db.close()
    }

    /** G4-code：滚动进度防抖——500ms 内连续变化不落库，停顿后只写最终值 */
    @Test
    fun progressPersist_debounced_writesLatestOnly() = runTest {
        val (vm, progress, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample.epub", DocFormat.EPUB)
        awaitLoaded(vm)

        vm.onFirstVisibleParagraphChanged(3)
        advanceTimeBy(ReaderViewModel.PROGRESS_DEBOUNCE_MS - 100)
        vm.onFirstVisibleParagraphChanged(7) // 重置防抖计时
        advanceTimeBy(ReaderViewModel.PROGRESS_DEBOUNCE_MS - 100)
        assertNull(
            "防抖窗口内不应落库",
            kotlinx.coroutines.runBlocking {
                progress.progress("asset:///fixtures/sample.epub").first()
            }
        )

        advanceTimeBy(200) // 越过 500ms 防抖窗口
        awaitProgress(progress, "asset:///fixtures/sample.epub", chapter = 0, paragraph = 7)
        db.close()
    }

    /** TC-F2-18：未加载任何文件（currentUri 为空）时 persistProgress 不写入 */
    @Test
    fun persistProgress_withoutLoad_nothingSaved() = runTest {
        val (vm, progress, db) = newViewModelWithProgress(this)
        advanceUntilIdle()

        vm.onFirstVisibleParagraphChanged(3)
        advanceUntilIdle()

        assertNull(kotlinx.coroutines.runBlocking {
            progress.progress("asset:///fixtures/sample.epub").first()
        })
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

    /**
     * TC-F3-09（VM 侧）：超过可编辑上限（设计§8，2MB = ReaderViewModel.MAX_EDITABLE_BYTES）的 txt 只读——
     * `ReaderUiState.editable` 按加载字节数判定；UI 据此将编辑入口置灰并附说明。
     */
    @Test
    fun tcF309_hugeFile_notEditable_normalFileEditable() = runTest {
        val (bigVm, _) = newViewModel(this)
        bigVm.load("asset:///fixtures/huge.txt", DocFormat.TXT) // 15 MB fixture（TC-F3-09 指定）
        awaitLoaded(bigVm)
        assertEquals(null, bigVm.uiState.value.error)
        assertFalse("15MB 超出 2MB 可编辑上限，应只读", bigVm.uiState.value.editable)

        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample_utf8.txt", DocFormat.TXT)
        awaitLoaded(vm)
        assertTrue("正常小文件应可编辑", vm.uiState.value.editable)
    }

    /**
     * G1（F2.6）：txt 手动编码覆盖——setEncodingOverride 非空覆盖即时重载并按该编码解码；
     * 置 null 恢复自动探测回到探测结果（会话内有效，不持久化）。
     * 用 GBK fixture（含 GBK 特有字符"镕"）驱动：自动探测判 GBK 正确解码；
     * 覆盖 UTF-8 后重载结果与"同字节按 UTF-8 黄金解码"一致且不同于自动探测结果（证明覆盖生效）；
     * 置 null 后回到自动探测结果。
     */
    @Test
    fun g1_encodingOverride_reloadAndReset() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample_gbk.txt", DocFormat.TXT)
        awaitLoaded(vm)
        val autoParas = vm.uiState.value.document!!.chapters[0].paragraphs
        assertTrue("自动探测应判 GBK 并正确解码", autoParas.any { it.contains("镕") })

        // 黄金对照：同一批字节按 UTF-8 解码（String.decode 与 PagedTextLoader 同用 REPLACE 解码器）
        val raw = javaClass.classLoader!!.getResourceAsStream("fixtures/sample_gbk.txt")!!.readBytes()
        val goldenUtf8 = String(raw, Charsets.UTF_8).lines()

        // 覆盖 UTF-8 → 即时重载，段落等于按 UTF-8 解码结果，且与自动探测（GBK）结果不同
        vm.setEncodingOverride("UTF-8")
        // 等待重载真正落地：loading 位在重载调度间隙可能短暂为 false，直接等到段落已切换为覆盖解码结果
        // （即不同于自动探测结果），避免提前读到旧文档
        waitUntilDocDiffersFrom(vm, autoParas)
        assertEquals("UTF-8", vm.uiState.value.encodingOverride)
        assertNull("覆盖重载不应报错", vm.uiState.value.error)
        assertEquals("覆盖后应按指定编码（UTF-8）解码", goldenUtf8, vm.uiState.value.document!!.chapters[0].paragraphs)
        assertTrue("覆盖解码结果应与自动探测不同", goldenUtf8 != autoParas)

        // 置 null → 恢复自动探测，回到 GBK 正确解码结果
        vm.setEncodingOverride(null)
        waitUntilDocEquals(vm, autoParas)
        assertNull(vm.uiState.value.encodingOverride)
        assertEquals(autoParas, vm.uiState.value.document!!.chapters[0].paragraphs)
    }

    /** 轮询等待重载后的文档段落不再是 [stale]（覆盖生效的判据），防 loading 位调度间隙误判 */
    private fun TestScope.waitUntilDocDiffersFrom(vm: ReaderViewModel, stale: List<String>) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.uiState.value.document?.chapters?.getOrNull(0)?.paragraphs == stale) {
            advanceUntilIdle()
            if (vm.uiState.value.document?.chapters?.getOrNull(0)?.paragraphs != stale) break
            check(System.currentTimeMillis() < deadline) { "编码覆盖重载未落地" }
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    /** 轮询等待恢复自动探测后文档段落回到 [target] */
    private fun TestScope.waitUntilDocEquals(vm: ReaderViewModel, target: List<String>) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.uiState.value.document?.chapters?.getOrNull(0)?.paragraphs != target) {
            advanceUntilIdle()
            if (vm.uiState.value.document?.chapters?.getOrNull(0)?.paragraphs == target) break
            check(System.currentTimeMillis() < deadline) { "恢复自动探测重载未落地" }
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    /** G2（M10）：customFontPath 非空时 cycleFont 循环序列纳入 CUSTOM；为 null 维持三态循环 */
    @Test
    fun g2_cycleFont_includesCustomWhenPathSet() = runTest {
        val (vm, _) = newViewModel(this)
        advanceUntilIdle()

        // 无 customFontPath：三态 CODE→SERIF→SANS→CODE
        vm.setFont(ReaderFont.CODE)
        vm.cycleFont()
        assertEquals(ReaderFont.SERIF, vm.uiState.value.settings.font)
        vm.cycleFont()
        assertEquals(ReaderFont.SANS, vm.uiState.value.settings.font)
        vm.cycleFont()
        assertEquals(ReaderFont.CODE, vm.uiState.value.settings.font)

        // 置 customFontPath（applyCustomFont 同时切到 CUSTOM）：四态序列 CODE→SERIF→SANS→CUSTOM→CODE
        vm.applyCustomFont(tmpFolder.newFile("cycle_font.ttf").absolutePath)
        assertEquals(ReaderFont.CUSTOM, vm.uiState.value.settings.font)
        vm.cycleFont() // CUSTOM → CODE（从 CUSTOM 一步可达 CODE）
        assertEquals(ReaderFont.CODE, vm.uiState.value.settings.font)
        vm.setFont(ReaderFont.SANS)
        vm.cycleFont() // SANS → CUSTOM
        assertEquals(ReaderFont.CUSTOM, vm.uiState.value.settings.font)
        vm.cycleFont() // CUSTOM → CODE
        assertEquals(ReaderFont.CODE, vm.uiState.value.settings.font)
    }

    /**
     * G2（M10）：applyCustomFont 重新导入成功后删除上一版旧字体文件（孤儿文件清理）；
     * 切换离开 CUSTOM 不删文件（允许切回）。用临时目录构造，勿动真机 files/fonts。
     */
    @Test
    fun g2_applyCustomFont_deletesOldFile_onlyOnReimport() = runTest {
        val (vm, _) = newViewModel(this)
        advanceUntilIdle()
        val oldFile = tmpFolder.newFile("old_font.ttf")
        val newFile = tmpFolder.newFile("new_font.ttf")

        // 首次导入：仅记路径（无旧文件可清理）
        vm.applyCustomFont(oldFile.absolutePath)
        assertTrue(oldFile.exists())
        assertEquals(oldFile.absolutePath, vm.uiState.value.settings.customFontPath)

        // 重新导入成功：旧字体文件被删除、路径更新到新文件
        vm.applyCustomFont(newFile.absolutePath)
        assertFalse("重新导入成功后旧字体文件应被清理", oldFile.exists())
        assertTrue(newFile.exists())
        assertEquals(newFile.absolutePath, vm.uiState.value.settings.customFontPath)

        // 切换离开 CUSTOM：不删除已导入的字体文件（允许切回）。
        // 注意：settings 改动同步生效（updateSettings 先改 StateFlow），此处直接断言，不 advanceUntilIdle，
        // 避免 DataStore 初始读的迟到回放覆盖掉刚写入的状态（与既有 settings 单测同约定）
        vm.setFont(ReaderFont.SANS)
        assertEquals(ReaderFont.SANS, vm.uiState.value.settings.font)
        assertTrue("切换离开 CUSTOM 不应删除字体文件", newFile.exists())
    }

    /** F2.13（TC-F2-20a 解析→VM）：加载含多级标题的 sample_toc.md → Document.toc 非空且标题正确 */
    @Test
    fun load_md_fixture_populatesToc() = runTest {
        val (vm, _) = newViewModel(this)
        vm.load("asset:///fixtures/sample_toc.md", DocFormat.MD)
        awaitLoaded(vm)
        val s = vm.uiState.value
        assertNull(s.error)
        val toc = s.document?.toc.orEmpty()
        assertTrue("md 应提取目录", toc.isNotEmpty())
        assertEquals(1, s.document!!.chapters.size)
        // toc 标题纯文本应与源码标题行一致（sample_toc.md 标题行 = "# 标题" 形式，无行内标记干扰）
        val paragraphs = s.document!!.chapters[0].paragraphs
        toc.forEach { entry ->
            assertTrue(paragraphs[entry.paragraphIndex].startsWith("#".repeat(entry.level) + " "))
        }
    }

    /** F2.13（S3 方案①）：目录跳转立即落库（不经防抖），重开恢复回到该标题段落 */
    @Test
    fun tocJump_immediatePersist_reopenRestores() = runTest {
        val (vm, progress, db) = newViewModelWithProgress(this)
        vm.load("asset:///fixtures/sample_toc.md", DocFormat.MD)
        awaitLoaded(vm)
        val toc = vm.uiState.value.document!!.toc
        assertTrue(toc.isNotEmpty())
        val jumpParagraph = toc.last().paragraphIndex // 跳到末标题，非首段 0

        vm.onTocJumpToParagraph(jumpParagraph)
        awaitProgress(progress, "asset:///fixtures/sample_toc.md", chapter = 0, paragraph = jumpParagraph)

        // 重开同一文件 → 恢复到该标题段落（复用同一 Room 库构造第二个 VM，进度才能被读到）
        val store2 = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmpFolder.newFile("reader2_${System.nanoTime()}.preferences_pb") }
        )
        val vm2 = ReaderViewModel(ResourceFileOpener(), SettingsRepository(store2), ProgressRepository(db))
        vm2.load("asset:///fixtures/sample_toc.md", DocFormat.MD)
        awaitLoaded(vm2)
        assertEquals(jumpParagraph, vm2.uiState.value.restoreParagraph)
        db.close()
    }
}
