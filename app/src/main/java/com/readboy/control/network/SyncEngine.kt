package com.readboy.control.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.readboy.control.AppLogger
import com.readboy.control.db.MirrorControlItem
import com.readboy.control.db.MirrorDatabase
import com.readboy.control.db.MirrorMeta
import com.readboy.control.db.MirrorUserInfo
import com.readboy.control.db.MirrorSwitchItem
import kotlinx.coroutines.runBlocking

/**
 * 本地同步引擎：镜像库 ↔ 家长管理 ContentProvider
 *
 * 自动适配新版（install_app_list / disabled_state）和旧版（forbidden_app / state）
 *
 * 双向同步：
 *   pullFromProvider(): 从家长管理 Provider 读取 → 写入镜像库
 *   pushToProvider():   从镜像库读取 → 写入家长管理 Provider
 *   sync():             双向同步（先拉再推，确保镜像库权威）
 */
object SyncEngine {

    private const val TAG = "SyncEngine"
    private const val AUTHORITY = "com.readboy.parentmanager.AppContentProvider"

    // ==================== 对外接口 ====================

    /**
     * 从家长管理 Provider 拉取全量数据 → 写入镜像库
     */
    suspend fun pullFromProvider(context: Context): SyncResult {
        AppLogger.i(TAG, "===== 开始从家长管理拉取 =====")
        try {
            val db = MirrorDatabase.getInstance(context)
            val version = VersionDetector.detect(context)
            val auth = resolveAuthority(context)

            val controlList = if (version == VersionDetector.PmsVersion.NEW) {
                pullInstallAppList(context, auth)
            } else {
                pullForbiddenApp(context, auth)
            }

            // 写入镜像库
            db.controlListDao().clear()
            if (controlList.isNotEmpty()) {
                db.controlListDao().insertAll(controlList)
            }

            // 拉取密码
            val userInfo = pullUserInfo(context, auth)
            if (userInfo != null) {
                db.userInfoDao().clear()
                db.userInfoDao().insert(userInfo)
            }

            // 拉取开关状态
            val switches = pullSwitches(context, auth)
            if (switches.isNotEmpty()) {
                db.switchDao().clear()
                switches.forEach { db.switchDao().insert(it) }
            }

            AppLogger.i(TAG, "拉取完成: control_list=${controlList.size} 项, password=${userInfo != null}, switches=${switches.size} 项")
            return SyncResult(true, "拉取成功", controlList.size, userInfo != null, switches.size)
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取失败: ${e.message}", e)
            return SyncResult(false, "拉取失败: ${e.message}")
        }
    }

    /**
     * 从镜像库推送到家长管理 Provider
     */
    suspend fun pushToProvider(context: Context): SyncResult {
        AppLogger.i(TAG, "===== 开始覆盖家长管理 =====")
        try {
            val db = MirrorDatabase.getInstance(context)
            val version = VersionDetector.detect(context)
            val auth = resolveAuthority(context)

            // 推送管控列表
            val items = db.controlListDao().getAll()
            var pushed = 0
            for (item in items) {
                if (version == VersionDetector.PmsVersion.NEW) {
                    pushInstallAppItem(context, auth, item)
                } else {
                    pushForbiddenAppItem(context, auth, item)
                }
                pushed++
            }

            // 推送密码
            val userInfo = db.userInfoDao().get()
            if (userInfo != null) {
                pushUserInfo(context, auth, userInfo)
            }

            // 推送开关
            val switches = db.switchDao().getAll()
            for (sw in switches) {
                pushSwitch(context, auth, sw)
            }

            AppLogger.i(TAG, "覆盖完成: 推送 $pushed 项管控, password=${userInfo != null}, switches=${switches.size} 项")
            return SyncResult(true, "覆盖成功", pushed, userInfo != null, switches.size)
        } catch (e: Exception) {
            AppLogger.e(TAG, "覆盖失败: ${e.message}", e)
            return SyncResult(false, "覆盖失败: ${e.message}")
        }
    }

    /**
     * 双向同步：先拉取 Provider 到镜像库，再推送镜像库覆盖 Provider
     */
    suspend fun sync(context: Context): SyncResult {
        AppLogger.i(TAG, "===== 开始双向同步 =====")
        val pull = pullFromProvider(context)
        val push = pushToProvider(context)
        AppLogger.i(TAG, "双向同步完成: 拉取=${pull.success}, 推送=${push.success}")
        return SyncResult(
            pull.success && push.success,
            "拉取: ${pull.message} | 推送: ${push.message}",
            push.itemCount
        )
    }

    // ==================== 新版 Provider 读写 ====================

    private fun installAppListUri(auth: String): Uri =
        Uri.parse("content://$auth/install_app_list")

    private suspend fun pullInstallAppList(context: Context, auth: String): List<MirrorControlItem> {
        val list = mutableListOf<MirrorControlItem>()
        try {
            val cursor = context.contentResolver.query(
                installAppListUri(auth), null, null, null, null
            )
            cursor?.use {
                // 检测数据库表结构变化
                detectSchemaChanges(context, auth, it)

                val idxPkg = it.getColumnIndex("package_name") ?: -1
                val idxName = it.getColumnIndex("app_name") ?: -1
                val idxDs = it.getColumnIndex("disabled_state") ?: -1
                val idxType = it.getColumnIndex("app_type") ?: -1
                val idxMode = it.getColumnIndex("system_mode") ?: -1
                val idxVer = it.getColumnIndex("version_code") ?: -1

                while (it.moveToNext()) {
                    if (idxPkg < 0) continue
                    val pkg = it.getString(idxPkg) ?: continue
                    list.add(MirrorControlItem(
                        package_name = pkg,
                        app_name = if (idxName >= 0) it.getString(idxName) else null,
                        disabled_state = if (idxDs >= 0) it.getInt(idxDs) else 0,
                        app_type = if (idxType >= 0) it.getInt(idxType) else 0,
                        system_mode = if (idxMode >= 0) it.getInt(idxMode) else 0,
                        version_code = if (idxVer >= 0) it.getString(idxVer) else null,
                        sync_status = 1
                    ))
                }
            }
            AppLogger.d(TAG, "从 install_app_list 拉取 ${list.size} 项")
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取 install_app_list 失败: ${e.message}", e)
        }
        return list
    }

    /**
     * 检测数据库表结构变化，记录到 mirror_meta
     * 当家长管理 App 更新后新增/删除列时，自动检测并告警
     */
    private fun detectSchemaChanges(context: Context, auth: String, cursor: android.database.Cursor) {
        try {
            val columnNames = cursor.columnNames?.toList() ?: return
            val name = columnNames.joinToString(",")
            val db = MirrorDatabase.getInstance(context)
            kotlinx.coroutines.runBlocking {
                val lastSchema = db.metaDao().get("install_app_list_schema")
                if (lastSchema != name) {
                    // 记录当前 schema
                    db.metaDao().put(
                        com.readboy.control.db.MirrorMeta(
                            key = "install_app_list_schema",
                            value = name
                        )
                    )
                    if (lastSchema == null) {
                        AppLogger.i(TAG, "首次检测到 install_app_list 表结构: ${columnNames.size} 列")
                    } else {
                        val lastCols = lastSchema.split(",")
                        val newCols = columnNames - lastCols.toSet()
                        val removedCols = lastCols - columnNames.toSet()
                        if (newCols.isNotEmpty()) {
                            AppLogger.w(TAG, "⚠️ 家长管理数据库新增列: ${newCols.joinToString(",")}（已自动适配）")
                        }
                        if (removedCols.isNotEmpty()) {
                            AppLogger.w(TAG, "⚠️ 家长管理数据库移除列: ${removedCols.joinToString(",")}（可能影响功能）")
                        }
                        if (newCols.isEmpty() && removedCols.isEmpty()) {
                            AppLogger.d(TAG, "install_app_list 表结构无变化")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "检测表结构失败: ${e.message}")
        }
    }

    private suspend fun pushInstallAppItem(context: Context, auth: String, item: MirrorControlItem) {
        try {
            val cv = ContentValues().apply {
                put("package_name", item.package_name)
                put("disabled_state", item.disabled_state)
                item.app_name?.let { put("app_name", it) }
                put("app_type", item.app_type)
                put("system_mode", item.system_mode)
                item.version_code?.let { put("version_code", it) }
            }

            // 先尝试更新（已存在），没有则插入
            val updated = context.contentResolver.update(
                installAppListUri(auth), cv,
                "package_name = ?", arrayOf(item.package_name)
            )
            if (updated == 0) {
                context.contentResolver.insert(installAppListUri(auth), cv)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "推送 ${item.package_name} 失败: ${e.message}", e)
        }
    }

    // ==================== 旧版 Provider 读写 ====================

    private fun forbiddenAppUri(auth: String): Uri =
        Uri.parse("content://$auth/forbidden_app")

    private fun unMallAppStateUri(auth: String): Uri =
        Uri.parse("content://$auth/un_mall_app_state")

    private suspend fun pullForbiddenApp(context: Context, auth: String): List<MirrorControlItem> {
        val list = mutableListOf<MirrorControlItem>()
        try {
            val cursor = context.contentResolver.query(
                forbiddenAppUri(auth), null, null, null, null
            )
            cursor?.use {
                val idxPkg = it.getColumnIndex("package_name") ?: -1
                val idxState = it.getColumnIndex("state") ?: -1
                while (it.moveToNext()) {
                    if (idxPkg < 0) continue
                    val pkg = it.getString(idxPkg) ?: continue
                    val state = if (idxState >= 0) it.getInt(idxState) else 0
                    // 旧版：state=0 黑名单/state=1 白名单 → 映射到 disabled_state: 0=allow/1=disabled
                    // 白名单(state=1)=允许(disabled_state=0)，黑名单(state=0)=禁用(disabled_state=1)
                    val disabledState = if (state == 1) 0 else 1
                    list.add(MirrorControlItem(
                        package_name = pkg,
                        disabled_state = disabledState,
                        sync_status = 1
                    ))
                }
            }
            AppLogger.d(TAG, "从 forbidden_app 拉取 ${list.size} 项")
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取 forbidden_app 失败: ${e.message}", e)
        }
        return list
    }

    private suspend fun pushForbiddenAppItem(context: Context, auth: String, item: MirrorControlItem) {
        try {
            // 旧版：state 0=黑名单(禁用) / 1=白名单(允许)
            val state = if (item.disabled_state == 0) 1 else 0
            val cv = ContentValues().apply {
                put("package_name", item.package_name)
                put("state", state)
            }

            val updated = context.contentResolver.update(
                forbiddenAppUri(auth), cv,
                "package_name = ?", arrayOf(item.package_name)
            )
            if (updated == 0) {
                context.contentResolver.insert(forbiddenAppUri(auth), cv)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "推送 ${item.package_name} 到 forbidden_app 失败: ${e.message}", e)
        }
    }

    // ==================== 密码操作 ====================

    private fun userInfoUri(auth: String): Uri =
        Uri.parse("content://$auth/user_info")

    private suspend fun pullUserInfo(context: Context, auth: String): MirrorUserInfo? {
        return try {
            val cursor = context.contentResolver.query(
                userInfoUri(auth), null, "_id > ?", arrayOf("0"), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idxPwd = it.getColumnIndex("password") ?: -1
                    val idxLong = it.getColumnIndex("is_long_pwd") ?: -1
                    val idxAllow = it.getColumnIndex("is_allow_input_pwd") ?: -1
                    MirrorUserInfo(
                        password = if (idxPwd >= 0) it.getString(idxPwd) ?: "" else "",
                        is_long_pwd = if (idxLong >= 0) it.getInt(idxLong) else 0,
                        is_allow_input_pwd = if (idxAllow >= 0) it.getInt(idxAllow) else 1
                    )
                } else null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取 user_info 失败: ${e.message}", e)
            null
        }
    }

    private suspend fun pushUserInfo(context: Context, auth: String, info: MirrorUserInfo) {
        try {
            val cv = ContentValues().apply {
                put("password", info.password)
                put("is_long_pwd", info.is_long_pwd)
                put("is_allow_input_pwd", info.is_allow_input_pwd)
            }
            val updated = context.contentResolver.update(
                userInfoUri(auth), cv, "_id > ?", arrayOf("0")
            )
            if (updated == 0) {
                context.contentResolver.insert(userInfoUri(auth), cv)
            }
            AppLogger.i(TAG, "推送密码完成")
        } catch (e: Exception) {
            AppLogger.e(TAG, "推送密码失败: ${e.message}", e)
        }
    }

    // ==================== 开关操作 ====================

    private suspend fun pullSwitches(context: Context, auth: String): List<MirrorSwitchItem> {
        val switches = mutableListOf<MirrorSwitchItem>()
        // un_mall_app_state 安装门禁
        try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://$auth/un_mall_app_state"),
                null, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex("state") ?: -1
                    if (idx >= 0) {
                        switches.add(MirrorSwitchItem("un_mall_app_state", it.getInt(idx)))
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "拉取 un_mall_app_state 失败: ${e.message}", e)
        }
        return switches
    }

    private suspend fun pushSwitch(context: Context, auth: String, sw: MirrorSwitchItem) {
        // 目前只处理 un_mall_app_state
        if (sw.switch_name == "un_mall_app_state") {
            try {
                val cv = ContentValues().apply { put("state", sw.status) }
                context.contentResolver.update(
                    Uri.parse("content://$auth/un_mall_app_state"), cv, null, null
                )
                AppLogger.d(TAG, "推送开关 un_mall_app_state = ${sw.status}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "推送开关失败: ${e.message}", e)
            }
        }
    }

    // ==================== 辅助 ====================

    private fun resolveAuthority(context: Context): String {
        return try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0)
            provider?.authority ?: AUTHORITY
        } catch (e: Exception) {
            AUTHORITY
        }
    }

    data class SyncResult(
        val success: Boolean,
        val message: String,
        val itemCount: Int = 0,
        val hasPassword: Boolean = false,
        val switchCount: Int = 0
    )
}