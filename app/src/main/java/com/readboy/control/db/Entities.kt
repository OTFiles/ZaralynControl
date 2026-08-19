package com.readboy.control.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 管控应用列表镜像（对应家长管理 install_app_list / forbidden_app） */
@Entity(tableName = "mirror_control_list")
data class MirrorControlItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val package_name: String,
    val app_name: String? = null,
    /** 0=允许 1=禁用（与新版 install_app_list.disabled_state 一致） */
    val disabled_state: Int = 0,
    val app_type: Int = 0,
    val system_mode: Int = 0,
    val version_code: String? = null,
    /** add / update / delete */
    val operation: String = "update",
    /** 0=本地未同步 1=本地已同步 2=云端未上传 3=云端已上传 */
    val sync_status: Int = 1
)

/** 家长密码镜像（对应 user_info 表） */
@Entity(tableName = "mirror_user_info")
data class MirrorUserInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val password: String = "",
    val is_long_pwd: Int = 0,
    val is_allow_input_pwd: Int = 1,
    val sync_status: Int = 1
)

/** 控制开关镜像（对应云端 xxx.status 各开关） */
@Entity(tableName = "mirror_switches")
data class MirrorSwitchItem(
    @PrimaryKey val switch_name: String,
    val status: Int = 0,
    val sync_status: Int = 1
)

/** 元信息（版本、时间戳等） */
@Entity(tableName = "mirror_meta")
data class MirrorMeta(
    @PrimaryKey val key: String,
    val value: String
)