package com.sample.docreader.ui.home

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.io.DraftStore
import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.recent.AppDatabase
import com.sample.docreader.core.recent.RecentFilesRepository
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * M7c 首页 VM 侧测试（TC-F3-08 新建文件、F4.6 最近列表联动删草稿，由 OpenCode 编写）：
 * - createNew：假 ContentProvider 直驱（query 提供显示名），持久权限为 best-effort（runCatching，
 *   Robolectric 阴影不支持时降级，真机 TC-F3-08 兜底断言权限）；
 * - remove/clear 联动 DraftStore.delete；
 * 说明：createNew 内部走 withContext(Dispatchers.IO)，测试调度器覆盖不到，用轮询等待（同 awaitLoaded）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var repo: RecentFilesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RecentFilesRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private val resolver
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    /** 假文档 Provider：query 返回 DISPLAY_NAME；failQuery 时抛错模拟不可写/不可用位置 */
    class FakeProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            if (failQuery) throw IllegalStateException("provider 不可用")
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
            names[uri.toString()]?.let { cursor.addRow(arrayOf(it)) }
            return cursor
        }

        override fun getType(uri: Uri): String = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
        ): Int = 0

        companion object {
            val names = mutableMapOf<String, String>()

            @Volatile
            var failQuery = false

            fun reset() {
                names.clear()
                failQuery = false
            }
        }
    }

    private fun TestScope.awaitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) break
            check(System.currentTimeMillis() < deadline) { "等待超时" }
            Thread.sleep(20)
        }
        advanceUntilIdle()
    }

    /** TC-F3-08（VM 侧）：新建 md——记录最近、editTarget 指向编辑器目标 */
    @Test
    fun createNew_md_touchesRecentAndSetsEditTarget() = runTest(mainDispatcher) {
        Robolectric.setupContentProvider(FakeProvider::class.java, AUTH)
        FakeProvider.reset()
        val uri = "content://$AUTH/docs/abc"
        FakeProvider.names[uri] = "新笔记.md"
        val vm = HomeViewModel(repo, resolver, DraftStore(tmpFolder.newFolder("files")))

        vm.createNew(Uri.parse(uri), DocFormat.MD)
        awaitUntil { vm.uiState.value.editTarget != null }

        assertEquals(OpenTarget(uri, "新笔记.md", DocFormat.MD), vm.uiState.value.editTarget)
        val recent = repo.recent.first()
        assertEquals(1, recent.size)
        assertEquals(uri, recent[0].uri)
        assertEquals(DocFormat.MD, recent[0].format)
    }

    /** TC-F3-08（VM 侧）：新建 txt 且 Provider 查不到显示名 → 兜底名"未命名.txt" */
    @Test
    fun createNew_txt_fallbackDisplayName() = runTest(mainDispatcher) {
        Robolectric.setupContentProvider(FakeProvider::class.java, AUTH)
        FakeProvider.reset()
        val uri = "content://$AUTH/docs/xyz"
        val vm = HomeViewModel(repo, resolver, DraftStore(tmpFolder.newFolder("files")))

        vm.createNew(Uri.parse(uri), DocFormat.TXT)
        awaitUntil { vm.uiState.value.editTarget != null }

        assertEquals(OpenTarget(uri, "未命名.txt", DocFormat.TXT), vm.uiState.value.editTarget)
    }

    /** 新建失败（Provider 不可用）：给一次性提示，不产生导航目标 */
    @Test
    fun createNew_providerFails_messageNoTarget() = runTest(mainDispatcher) {
        Robolectric.setupContentProvider(FakeProvider::class.java, AUTH)
        FakeProvider.reset()
        FakeProvider.failQuery = true
        val vm = HomeViewModel(repo, resolver, DraftStore(tmpFolder.newFolder("files")))

        vm.createNew(Uri.parse("content://$AUTH/docs/bad"), DocFormat.MD)
        awaitUntil { vm.uiState.value.message != null }

        assertTrue(vm.uiState.value.message!!.contains("新建文件失败"))
        assertNull(vm.uiState.value.editTarget)
        assertTrue(repo.recent.first().isEmpty())
    }

    /** F4.6：单条删除联动删草稿 */
    @Test
    fun remove_deletesDraft() = runTest(mainDispatcher) {
        val store = DraftStore(tmpFolder.newFolder("files"))
        val uri = "content://$AUTH/docs/a.txt"
        store.write(uri, "未保存草稿")
        val vm = HomeViewModel(repo, resolver, store)

        vm.remove(uri)
        advanceUntilIdle()

        assertNull("remove 应联动删除草稿", store.read(uri))
        assertTrue(repo.recent.first().isEmpty())
    }

    /** F4.6：清空最近列表联动删除所有已记录文件的草稿 */
    @Test
    fun clear_deletesAllDrafts() = runTest(mainDispatcher) {
        val store = DraftStore(tmpFolder.newFolder("files"))
        val uri1 = "content://$AUTH/docs/a.txt"
        val uri2 = "content://$AUTH/docs/b.md"
        repo.touch(uri1, "a.txt", DocFormat.TXT)
        repo.touch(uri2, "b.md", DocFormat.MD)
        store.write(uri1, "草稿1")
        store.write(uri2, "草稿2")
        val vm = HomeViewModel(repo, resolver, store)
        awaitUntil { vm.uiState.value.recent.size == 2 }

        vm.clear()
        advanceUntilIdle()

        assertNull(store.read(uri1))
        assertNull(store.read(uri2))
        assertTrue(repo.recent.first().isEmpty())
    }

    companion object {
        private const val AUTH = "com.sample.docreader.fakeprovider"
    }
}
