package com.sample.docreader.core.progress

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.sample.docreader.core.recent.AppDatabase
import kotlinx.coroutines.flow.Flow

/** 阅读进度记录（设计§3.5）：uri 主键，REPLACE 覆盖 */
@Entity(tableName = "reading_progress")
data class ProgressEntity(
    @PrimaryKey val uri: String,
    val chapter: Int,      // 章节索引（0 起）
    val paragraph: Int     // 章节内首个可见段落索引（0 起）
)

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE uri = :uri")
    fun observe(uri: String): Flow<ProgressEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ProgressEntity)
}

/** 阅读进度仓库（F2.8）：写入 `(uri, chapter, paragraph)`，重开时按 uri 恢复 */
class ProgressRepository(private val dao: ProgressDao) {

    constructor(db: AppDatabase) : this(db.progressDao())

    fun progress(uri: String): Flow<ProgressEntity?> = dao.observe(uri)

    suspend fun save(uri: String, chapter: Int, paragraph: Int) =
        dao.upsert(ProgressEntity(uri, chapter, paragraph))
}
