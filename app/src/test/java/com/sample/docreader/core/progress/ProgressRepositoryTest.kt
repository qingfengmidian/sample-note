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
}
