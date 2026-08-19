package com.readboy.control.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ControlListDao {

    @Query("SELECT * FROM mirror_control_list ORDER BY package_name")
    suspend fun getAll(): List<MirrorControlItem>

    @Query("SELECT * FROM mirror_control_list WHERE package_name = :pkg LIMIT 1")
    suspend fun getByPackage(pkg: String): MirrorControlItem?

    @Query("SELECT * FROM mirror_control_list WHERE sync_status != 1 OR sync_status != 3")
    suspend fun getUnsynced(): List<MirrorControlItem>

    @Query("SELECT * FROM mirror_control_list WHERE sync_status = 2")
    suspend fun getCloudUnsynced(): List<MirrorControlItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MirrorControlItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MirrorControlItem>)

    @Update
    suspend fun update(item: MirrorControlItem)

    @Delete
    suspend fun delete(item: MirrorControlItem)

    @Query("DELETE FROM mirror_control_list")
    suspend fun clear()

    @Query("UPDATE mirror_control_list SET sync_status = :status WHERE package_name = :pkg")
    suspend fun updateSyncStatus(pkg: String, status: Int)

    @Query("SELECT COUNT(*) FROM mirror_control_list")
    suspend fun count(): Int
}

@Dao
interface UserInfoDao {

    @Query("SELECT * FROM mirror_user_info LIMIT 1")
    suspend fun get(): MirrorUserInfo?

    @Query("SELECT * FROM mirror_user_info")
    suspend fun getAll(): List<MirrorUserInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(info: MirrorUserInfo): Long

    @Update
    suspend fun update(info: MirrorUserInfo)

    @Query("DELETE FROM mirror_user_info")
    suspend fun clear()
}

@Dao
interface SwitchDao {

    @Query("SELECT * FROM mirror_switches")
    suspend fun getAll(): List<MirrorSwitchItem>

    @Query("SELECT * FROM mirror_switches WHERE switch_name = :name LIMIT 1")
    suspend fun get(name: String): MirrorSwitchItem?

    @Query("SELECT * FROM mirror_switches WHERE sync_status != 1 AND sync_status != 3")
    suspend fun getUnsynced(): List<MirrorSwitchItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MirrorSwitchItem): Long

    @Update
    suspend fun update(item: MirrorSwitchItem)

    @Query("DELETE FROM mirror_switches")
    suspend fun clear()
}

@Dao
interface MetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: MirrorMeta)

    @Query("SELECT value FROM mirror_meta WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("DELETE FROM mirror_meta WHERE key = :key")
    suspend fun delete(key: String)
}