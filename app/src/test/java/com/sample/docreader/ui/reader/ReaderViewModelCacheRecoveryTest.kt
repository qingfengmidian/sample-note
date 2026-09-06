package com.sample.docreader.ui.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sample.docreader.core.io.EpubCache
import com.sample.docreader.core.io.FileOpener
import com.sample.docreader.core.model.DocFormat
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** 测试用 FileOpener：从 test resources 的 fixtures/ 读文件，uri 形如 asset:///fixtures/xxx */
internal class ResourceFileOpener2 : FileOpener {
    override fun open(uri: String): InputStream =
        javaClass.classLoader!!.getResourceAsStream(uri.removePrefix("asset:///"))
            ?: throw FileNotFoundException(uri)
}

/**
 * M1（评审）设备路径回归：epub 走缓存目录解析时，若缓存"container.xml 在但部分条目缺失"
 * （此前完整性只判 container.xml），首次 parseFromDir 失败后应**删除目录并重新解压一次**，
 * 重抽成功即恢复阅读（不静默回退占位、不报错）；真正损坏仍会回退并给出原始错误。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelCacheRecoveryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private class Harness(
        val vm: ReaderViewModel,
        val cache: EpubCache,
        val opener: ResourceFileOpener2
    )

    private fun newHarness(scope: TestScope): Harness {
        val opener = ResourceFileOpener2()
        val cacheRoot = File(tmpFolder.newFolder("cache_root"), "epub")
        cacheRoot.mkdirs()
        val cache = EpubCache(cacheRoot)
        val store = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { tmpFolder.newFile("recovery_${System.nanoTime()}.preferences_pb") }
        )
        val vm = ReaderViewModel(opener, SettingsRepository(store), epubCache = cache)
        return Harness(vm, cache, opener)
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

    @Test
    fun partiallyMissingCache_autoReExtractRecovers() = runTest {
        val h = newHarness(this)
        val uri = "asset:///fixtures/sample.epub"
        // 预置"container.xml 在但章节条目缺失"的半损坏缓存（旧完整性判据会误判为完整并跳过解压）
        val seeded = h.cache.extract(uri, input = { h.opener.open(uri) })!!
        val victim = File(seeded, "OEBPS/ch2.xhtml")
        assertTrue("预置前章节文件存在", victim.isFile)
        victim.delete()

        h.vm.load(uri, DocFormat.EPUB)
        awaitLoaded(h.vm)

        val s = h.vm.uiState.value
        assertNull("缓存不完整应自动重抽而非报错", s.error)
        assertEquals("重抽后章节完整", 3, s.document?.chapters?.size)
        assertTrue("缺失章节被恢复", s.document!!.chapters[1].paragraphs.isNotEmpty())
        // 缓存目录已重建（imageRoot 指向重新解压的目录）
        assertEquals(File(seeded.parentFile, h.cache.hashOf(uri)).absolutePath, s.document.imageRoot)
    }
}
