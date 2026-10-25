package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity

class SshHostRepository(private val dao: SshHostDao) {
    suspend fun getAll(): List<SshHostEntity> = dao.getAll()
    suspend fun getByName(name: String): SshHostEntity? = dao.getByName(name)

    /** 主机列表的观察流，供运行实例页使用（增删改即时反映）。 */
    fun listFlow(): Flow<List<SshHostEntity>> = dao.listFlow()

    suspend fun upsert(host: SshHostEntity) = dao.upsert(host)
    suspend fun deleteByName(name: String) = dao.deleteByName(name)
}
