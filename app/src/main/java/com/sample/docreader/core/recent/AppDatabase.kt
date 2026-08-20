package com.sample.docreader.core.recent

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sample.docreader.core.progress.ProgressDao
import com.sample.docreader.core.progress.ProgressEntity
import kotlinx.coroutines.flow.Flow

/** 最近打开记录（设计§3.5：uri 主键，REPLACE 插入） */
@Entity(tableName = "recent_file")
data class RecentFileEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val format: String,   // DocFormat.name
    val openedAt: Long
)

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_file ORDER BY openedAt DESC")
    fun observeAll(): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentFileEntity)

    /** 上限 50 条，淘汰最旧（F1.3） */
    @Query("DELETE FROM recent_file WHERE uri NOT IN (SELECT uri FROM recent_file ORDER BY openedAt DESC LIMIT 50)")
    suspend fun trimToLimit()

    @Query("DELETE FROM recent_file WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_file")
    suspend fun clear()
}

@Database(entities = [RecentFileEntity::class, ProgressEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun recentFileDao(): RecentFileDao

    abstract fun progressDao(): ProgressDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            // V1.0 迁移策略：fallbackToDestructiveMigration（见《开发计划》M1）
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "docreader.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
