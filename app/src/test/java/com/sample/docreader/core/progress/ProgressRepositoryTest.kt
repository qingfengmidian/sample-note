package com.sample.docreader.core.progress

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sample.docreader.core.recent.AppDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TC-UT-11：ProgressRepository 写入并读取 `(uri, chapter, paragraph)`，uri 主键 REPLACE 覆盖。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProgressRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ProgressRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = ProgressRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveAndRead_roundtrip() = runTest {
        assertNull(repo.progress("u1").first())
        repo.save("u1", chapter = 3, paragraph = 42)
        val p = repo.progress("u1").first()
        assertEquals(3, p?.chapter)
        assertEquals(42, p?.paragraph)
    }

    @Test
    fun save_overwrites_sameUri() = runTest {
        repo.save("u1", chapter = 1, paragraph = 10)
        repo.save("u1", chapter = 5, paragraph = 99)
        val p = repo.progress("u1").first()
        assertEquals(5, p?.chapter)
        assertEquals(99, p?.paragraph)
    }

    /** TC-UT-11：多本书进度按 uri 隔离——各自存取互不影响，覆盖一本不影响其他 */
    @Test
    fun save_multipleUris_isolated() = runTest {
        repo.save("book-a", chapter = 1, paragraph = 10)
        repo.save("book-b", chapter = 2, paragraph = 20)
        repo.save("book-a", chapter = 3, paragraph = 30) // 覆盖 a，不应影响 b

        val a = repo.progress("book-a").first()
        val b = repo.progress("book-b").first()
        assertEquals(3, a?.chapter)
        assertEquals(30, a?.paragraph)
        assertEquals(2, b?.chapter)
        assertEquals(20, b?.paragraph)
        assertNull("未保存过的 uri 应无进度", repo.progress("book-c").first())
    }
}
