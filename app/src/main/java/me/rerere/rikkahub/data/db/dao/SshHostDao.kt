package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SshHostEntity

@Dao
interface SshHostDao {
    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    suspend fun getAll(): List<SshHostEntity>

    /**
     * 运行实例页要跟着主机增删改实时刷新；没有这条流，SSH 实例列表就只能在页面重进时更新。
     * 只加查询，不动表结构，因此不需要迁移。
     */
    @Query("SELECT * FROM ssh_hosts ORDER BY name ASC")
    fun listFlow(): Flow<List<SshHostEntity>>

    @Query("SELECT * FROM ssh_hosts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: SshHostEntity)

    @Delete
    suspend fun delete(host: SshHostEntity)

    @Query("DELETE FROM ssh_hosts WHERE name = :name")
    suspend fun deleteByName(name: String)
}
