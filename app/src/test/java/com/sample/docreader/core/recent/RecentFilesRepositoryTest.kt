package com.sample.docreader.core.recent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.model.DocFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TC-UT-10（最近列表部分）：去重置顶、上限 50 淘汰、单条删除、清空、按时间倒序。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecentFilesRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: RecentFilesRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RecentFilesRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun touch_upsertsAndMovesToTop() = runTest {
        repo.touch("u1", "a.txt", DocFormat.TXT, now = 100)
        repo.touch("u2", "b.md", DocFormat.MD, now = 200)
        repo.touch("u1", "a.txt", DocFormat.TXT, now = 300)  // 重复 uri：更新时间并置顶
        val list = repo.recent.first()
        assertEquals(2, list.size)
        assertEquals("u1", list[0].uri)           // 最新时间置顶
        assertEquals(300, list[0].openedAt)
        assertEquals("u2", list[1].uri)
    }

    @Test
    fun touch_trimsToLimit50() = runTest {
        repeat(55) { i ->
            repo.touch("uri_$i", "f_$i.txt", DocFormat.TXT, now = (i + 1).toLong())
        }
        val list = repo.recent.first()
        assertEquals(50, list.size)
        assertEquals("uri_54", list[0].uri)       // 最新在前
        // 最旧的 5 条被淘汰
        repeat(5) { i -> assertFalse(list.any { it.uri == "uri_$i" }) }
    }

    @Test
    fun remove_deletesSingle() = runTest {
        repo.touch("u1", "a.txt", DocFormat.TXT, now = 100)
        repo.touch("u2", "b.md", DocFormat.MD, now = 200)
        repo.remove("u1")
        val list = repo.recent.first()
        assertEquals(1, list.size)
        assertEquals("u2", list[0].uri)
    }

    @Test
    fun clear_emptiesList() = runTest {
        repo.touch("u1", "a.txt", DocFormat.TXT, now = 100)
        repo.touch("u2", "b.md", DocFormat.MD, now = 200)
        repo.clear()
        assertTrue(repo.recent.first().isEmpty())
    }

    @Test
    fun recent_ordersByOpenedAtDesc() = runTest {
        repo.touch("u3", "c.xml", DocFormat.XML, now = 300)
        repo.touch("u1", "a.txt", DocFormat.TXT, now = 100)
        repo.touch("u2", "b.md", DocFormat.MD, now = 200)
        assertEquals(listOf("u3", "u2", "u1"), repo.recent.first().map { it.uri })
    }
}
