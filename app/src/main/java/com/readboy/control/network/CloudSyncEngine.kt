package com.readboy.control.network

import android.content.Context
import com.readboy.control.AppLogger
import com.readboy.control.db.MirrorControlItem
import com.readboy.control.db.MirrorDatabase
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端同步引擎
 *
 * 读接口：jpush/content 全量拉取配置
 * 写接口：controlApp/upload 上传管控列表
 *
 * 重试策略：3次，每次失败记录 debug 日志
 */
object CloudSyncEngine {

    private const val TAG = "CloudSync"
    private const val MAX_RETRY = 3
    private const val JPUSH_URL = "http://parent-manage.readboy.com/api/v1/jpush/content"
    private const val CONTROL_APP_URL = "https://parentadmin.readboy.com/v1/appinfo/controlApp/upload"

    private val gson = Gson()

    // ==================== 拉取（云端 → 本地） ====================

    /**
     * 从云端全量拉取管控配置
     * @param imei 设备序列号。null 时使用 DeviceUtil.getEffectiveSerial()
     */
    suspend fun pullFromCloud(
        imei: String? = null
    ): CloudPullResult = withContext(Dispatchers.IO) {
        val effectiveImei = imei ?: DeviceUtil.getEffectiveSerial()
        if (effectiveImei.isNullOrEmpty()) {
            AppLogger.e(TAG, "无法获取设备序列号，无法拉取云端配置")
            return@withContext CloudPullResult(false, "无法获取设备序列号")
        }
        AppLogger.i(TAG, "===== 开始从云端拉取配置 (imei=$effectiveImei) =====")
        var lastError: String? = null

        for (attempt in 1..MAX_RETRY) {
            try {
                val timestampMs = System.currentTimeMillis()
                val queryString = SignUtil.getCommonQueryString(effectiveImei, timestampMs) + "&get_all=1"
                val url = URL("$JPUSH_URL?$queryString")

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "ZaralynControl/1.0")

                val responseCode = conn.responseCode
                val responseBody = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                }
                conn.disconnect()

                AppLogger.d(TAG, "拉取响应: HTTP $responseCode, body=$responseBody")

                if (responseCode in 200..299) {
                    return@withContext CloudPullResult(true, responseBody)
                } else {
                    lastError = "HTTP $responseCode"
                    AppLogger.w(TAG, "拉取失败(尝试$attempt): $lastError")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "未知错误"
                AppLogger.d(TAG, "拉取异常(尝试$attempt): ${e.message}")
            }

            // 重试等待
            if (attempt < MAX_RETRY) {
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }

        AppLogger.e(TAG, "拉取失败(已重试${MAX_RETRY}次): $lastError")
        return@withContext CloudPullResult(false, "拉取失败: $lastError")
    }

    /**
     * 解析云端配置并更新镜像库
     */
    suspend fun parseAndUpdateMirror(context: android.content.Context, responseBody: String) {
        AppLogger.i(TAG, "解析云端响应...")
        try {
            val db = MirrorDatabase.getInstance(context)
            val response = gson.fromJson(responseBody, JpushResponse::class.java)

            if (response.status != 1) {
                AppLogger.w(TAG, "云端返回 status=${response.status}，可能配置未更新")
                return
            }

            // 更新管控列表（实测修正：control_list 在 data.app_control 内）
            response.data?.app_control?.control_list?.let { list ->
                db.controlListDao().clear()
                val items = list.mapNotNull { item ->
                    if (item.pack_name.isNullOrBlank()) return@mapNotNull null
                    MirrorControlItem(
                        package_name = item.pack_name,
                        app_name = item.app_name,
                        // 实测修正：status={0,2} 都禁用，仅 status=1 放行
                        disabled_state = if (item.status == 1) 0 else 1,
                        app_type = item.app_type ?: 0,
                        sync_status = 1
                    )
                }
                if (items.isNotEmpty()) {
                    db.controlListDao().insertAll(items)
                    AppLogger.i(TAG, "更新镜像库: ${items.size} 项管控列表")
                } else {
                    AppLogger.w(TAG, "control_list 为空或全部无包名，跳过（空列表设备端不处理）")
                }
            } ?: AppLogger.w(TAG, "响应中无 data.app_control.control_list")

            // 更新密码
            response.data?.password?.let { pwd ->
                val pwdStr = pwd.password ?: ""
                db.userInfoDao().clear()
                db.userInfoDao().insert(
                    com.readboy.control.db.MirrorUserInfo(
                        password = pwdStr,
                        is_long_pwd = pwd.is_long_pwd ?: 0
                    )
                )
                AppLogger.i(TAG, "更新密码: ${if (pwdStr.isNotEmpty()) "已设置" else "未设置"}")
            }

            // 更新 allow_input_pwd 开关
            response.data?.allow_input_pwd?.let { aip ->
                db.switchDao().insert(
                    com.readboy.control.db.MirrorSwitchItem(
                        switch_name = "allow_input_pwd",
                        status = aip.status ?: 1
                    )
                )
            }

            // 更新其他开关
            response.data?.switch_list?.forEach { sw ->
                db.switchDao().insert(
                    com.readboy.control.db.MirrorSwitchItem(
                        switch_name = sw.switch_name ?: "unknown",
                        status = sw.switch_state ?: 0
                    )
                )
            }

            AppLogger.i(TAG, "镜像库更新完成")
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析云端响应失败: ${e.message}", e)
        }
    }

    // ==================== 上传（本地 → 云端） ====================

    /**
     * 上传管控列表到云端
     */
    suspend fun pushToCloud(
        imei: String? = null,
        uid: String = "00000000"
    ): CloudPushResult = withContext(Dispatchers.IO) {
        val effectiveImei = imei ?: DeviceUtil.getEffectiveSerial()
        if (effectiveImei.isNullOrEmpty()) {
            AppLogger.e(TAG, "无法获取设备序列号，无法上传云端")
            return@withContext CloudPushResult(false, "无法获取设备序列号")
        }
        AppLogger.i(TAG, "===== 开始上传管控到云端 (imei=$effectiveImei) =====")
        var lastError: String? = null

        for (attempt in 1..MAX_RETRY) {
            try {
                val timestampMs = System.currentTimeMillis()
                val seconds = timestampMs / 1000

                // 构造管控列表 JSON
                val db = MirrorDatabase.getInstance(com.readboy.control.ZaralynControlApp.instance)
                val items = db.controlListDao().getAll()
                val uploadList = items.map { item ->
                    UploadControlItem(
                        packageName = item.package_name,
                        // 云端 status 方向与本地相反：1=放行/0=禁用
                        status = if (item.disabled_state == 0) 1 else 0,
                        operation = item.operation,
                        system_mode = item.system_mode
                    )
                }

                val uploadJson = gson.toJson(uploadList)
                AppLogger.d(TAG, "上传 JSON: ${uploadJson.take(500)}")

                // 构造请求
                val url = URL(CONTROL_APP_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true

                // 设置 Header
                conn.setRequestProperty("sn", SignUtil.getSign(uid, timestampMs))
                conn.setRequestProperty("signature", SignUtil.getSign2(timestampMs))
                conn.setRequestProperty("imei", effectiveImei)
                conn.setRequestProperty("timestamp", seconds.toString())
                conn.setRequestProperty("app_id", "parent-manage")
                conn.setRequestProperty("control_list", uploadJson)
                conn.setRequestProperty("initialize", "1")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                // 发送标准参数为 body
                val body = SignUtil.getCommonQueryString(effectiveImei, timestampMs)
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = conn.responseCode
                val responseBody = if (responseCode in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                }
                conn.disconnect()

                AppLogger.d(TAG, "上传响应: HTTP $responseCode, body=$responseBody")

                if (responseCode in 200..299) {
                    // 更新同步状态
                    items.forEach { item ->
                        db.controlListDao().updateSyncStatus(item.package_name, 3) // 云端已上传
                    }
                    return@withContext CloudPushResult(true, "上传成功: ${uploadList.size} 项")
                } else {
                    lastError = "HTTP $responseCode $responseBody"
                    AppLogger.w(TAG, "上传失败(尝试$attempt): $lastError")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "未知错误"
                AppLogger.d(TAG, "上传异常(尝试$attempt): ${e.message}")
            }

            if (attempt < MAX_RETRY) {
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }

        AppLogger.e(TAG, "上传失败(已重试${MAX_RETRY}次): $lastError")
        return@withContext CloudPushResult(false, "上传失败: $lastError")
    }

    // ==================== JSON 响应结构 ====================

    data class JpushResponse(
        val status: Int = 0,
        val errno: Int = 0,
        val errmsg: String? = null,
        // 实测修正：所有配置字段在 data 对象内
        val data: JpushData? = null
    )

    data class JpushData(
        @SerializedName("app_control") val app_control: AppControl? = null,
        val password: PasswordItem? = null,
        @SerializedName("allow_input_pwd") val allow_input_pwd: AllowInputPwd? = null,
        @SerializedName("switch_list") val switch_list: List<SwitchListItem>? = null,
        @SerializedName("synchronize_data") val synchronize_data: Int? = null
    )

    data class AppControl(
        @SerializedName("control_list") val control_list: List<ControlListItem>? = null
    )

    data class ControlListItem(
        @SerializedName("pack_name") val pack_name: String? = null,
        val status: Int = 0,
        val operated: Int? = null,
        @SerializedName("app_name") val app_name: String? = null,
        @SerializedName("app_type") val app_type: Int? = null
    )

    data class PasswordItem(
        val password: String? = null,
        @SerializedName("is_long_pwd") val is_long_pwd: Int? = 0
    )

    data class AllowInputPwd(
        val status: Int? = 1
    )

    data class SwitchListItem(
        @SerializedName("switch_id") val switch_id: Int? = null,
        @SerializedName("switch_name") val switch_name: String? = null,
        @SerializedName("switch_state") val switch_state: Int? = null
    )

    // ==================== 上传 JSON 结构 ====================

    data class UploadControlItem(
        val packageName: String,
        val status: Int,
        val operation: String,
        @SerializedName("system_mode") val system_mode: Int = 0
    )

    // ==================== 结果 ====================

    data class CloudPullResult(
        val success: Boolean,
        val responseBody: String? = null,
        val message: String = if (success) "拉取成功" else responseBody ?: "拉取失败"
    )

    data class CloudPushResult(
        val success: Boolean,
        val message: String = if (success) "上传成功" else "上传失败"
    )
}