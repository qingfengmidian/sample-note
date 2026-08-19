package com.sample.docreader.core.recent

import com.sample.docreader.core.model.DocFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 最近文件领域模型 */
data class RecentFile(
    val uri: String,
    val name: String,
    val format: DocFormat,
    val openedAt: Long
)

/** 最近打开列表仓库（F1.3：去重、上限 50 条、置顶最新、单条删除、清空） */
class RecentFilesRepository(private val dao: RecentFileDao) {

    constructor(db: AppDatabase) : this(db.recentFileDao())

    val recent: Flow<List<RecentFile>> = dao.observeAll().map { list ->
        list.map { RecentFile(it.uri, it.name, DocFormat.valueOf(it.format), it.openedAt) }
    }

    /** 打开记录（重复 uri 更新时间并置顶，随后裁剪到上限） */
    suspend fun touch(uri: String, name: String, format: DocFormat, now: Long = System.currentTimeMillis()) {
        dao.upsert(RecentFileEntity(uri, name, format.name, now))
        dao.trimToLimit()
    }

    suspend fun remove(uri: String) = dao.delete(uri)

    suspend fun clear() = dao.clear()
}
