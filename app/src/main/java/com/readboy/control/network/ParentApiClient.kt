package com.readboy.control.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.readboy.control.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * api-super 域客户端（家长助手手机端接口）
 *
 * 域名 https://api-super.readboy.com/api/
 * 认证：sn=getSn(uid8, ts, MD5(uid8)) + token=access_token（登录后）
 * 登录：sn=getSn("00000000", ts, MD5(包名)) + username + password=MD5(密码)
 *
 * 逆向来源：jzzs-2.9.57.apk (com.readboy.rbmanager) ApiService.smali
 */
object ParentApiClient {

    private const val TAG = "ParentApi"
    private const val BASE = "https://api-super.readboy.com/api"
    private const val MAX_RETRY = 3

    private val gson = Gson()

    // ==================== 登录 ====================

    /**
     * 账号密码登录（mobile_login，无需验证码）
     * @return 登录结果（uid/token/过期时间）
     */
    suspend fun login(mobile: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val timestampMs = System.currentTimeMillis()
            val sn = SignUtil.getSnForLogin(timestampMs)
            val query = StringBuilder()
                .append("sn=").append(urlEncode(sn))
                .append("&username=").append(urlEncode(mobile))
                .append("&password=").append(urlEncode(SignUtil.md5Password(password)))
                .toString()

            val (code, body) = httpGet("$BASE/mobile_login", query)
            AppLogger.i(TAG, "登录响应: HTTP $code $body")

            if (code !in 200..299) return@withContext LoginResult(false, "HTTP $code")

            val resp = try { gson.fromJson(body, MobileRegisterResponse::class.java) } catch (e: Exception) { null }
            if (resp == null) return@withContext LoginResult(false, "响应解析失败: ${body.take(200)}")

            if (resp.errno != 0 && resp.errno != null && resp.errno != 200) {
                return@withContext LoginResult(false, "登录失败: ${resp.errmsg ?: "errno=${resp.errno}"}")
            }
            if (resp.uid.isNullOrBlank() || resp.access_token.isNullOrBlank()) {
                return@withContext LoginResult(false, "登录失败: ${resp.errmsg ?: body.take(200)}")
            }

            val uid = resp.uid.toLongOrNull() ?: return@withContext LoginResult(false, "uid 解析失败: ${resp.uid}")
            // access_expire 可能是过期时间戳(秒)或时长(秒)，兼容处理
            val expireSec = (resp.access_expire ?: 0).toLong()
            val nowSec = System.currentTimeMillis() / 1000
            val expireAt = if (expireSec > 1000000000L) expireSec else nowSec + expireSec

            AppLogger.i(TAG, "登录成功: uid=$uid mobile=$mobile token=${resp.access_token.take(8)}... expireAt=$expireAt")
            LoginResult(true, "登录成功", uid = uid, token = resp.access_token, expireAt = expireAt)
        } catch (e: Exception) {
            AppLogger.e(TAG, "登录异常: ${e.message}", e)
            LoginResult(false, "登录异常: ${e.message}")
        }
    }

    // ==================== 时间管控 ====================

    /**
     * 拉取时间管控配置（time_setting）
     */
    suspend fun getTimeSetting(context: android.content.Context, imei: String): TimeSettingResult = withContext(Dispatchers.IO) {
        val params = baseParams(context) + ("imei" to imei)
        val (code, body) = httpGet("$BASE/parent_control/time_setting", buildQuery(params))
        AppLogger.i(TAG, "time_setting 响应: HTTP $code $body")
        if (code !in 200..299) return@withContext TimeSettingResult(false, "HTTP $code $body")
        val resp = try { gson.fromJson(body, TimeSettingResponse::class.java) } catch (e: Exception) { null }
        if (resp == null) return@withContext TimeSettingResult(false, "解析失败: ${body.take(200)}")
        if (resp.errno != null && resp.errno != 0) {
            return@withContext TimeSettingResult(false, "${resp.errmsg ?: "errno=${resp.errno}"}")
        }
        TimeSettingResult(true, "拉取成功", resp)
    }

    /**
     * 设置时间管控（set_time）
     * @param tid 已有配置的 tid（修改时传），新建传 null
     * @param group 星期字符串如 "1,2,3,4,5,6,0"
     * @param periodStatus 0/1 是否启用时间段
     * @param periodsJson periods JSON 字符串如 [{"start":28800,"end":82800}]（periodStatus=1 时传）
     * @param totalTime 每日总时长秒（启用总时长时传），null 不传
     */
    suspend fun setTime(
        context: android.content.Context,
        imei: String,
        tid: Int?,
        group: String,
        periodStatus: Int,
        periodsJson: String?,
        totalTime: Int?
    ): ApiResult = withContext(Dispatchers.IO) {
        val params = mutableMapOf<String, String>()
        params.putAll(baseParams(context))
        params["imei"] = imei
        if (tid != null) params["tid"] = tid.toString()
        params["group"] = group
        params["period_status"] = periodStatus.toString()
        if (periodStatus == 1 && !periodsJson.isNullOrEmpty()) params["periods"] = periodsJson
        if (totalTime != null) params["total_time"] = totalTime.toString()

        val (code, body) = httpPost("$BASE/parent_control/set_time", buildQuery(params))
        AppLogger.i(TAG, "set_time 响应: HTTP $code $body")
        if (code !in 200..299) return@withContext ApiResult(false, "HTTP $code $body")
        val resp = try { gson.fromJson(body, SimpleResp::class.java) } catch (e: Exception) { null }
        if (resp == null) return@withContext ApiResult(false, "解析失败: ${body.take(200)}")
        if (resp.errno != null && resp.errno != 0) {
            return@withContext ApiResult(false, "${resp.errmsg ?: "errno=${resp.errno}"}")
        }
        ApiResult(true, "时间管控已保存")
    }

    // ==================== 允许输入密码 ====================

    /**
     * 修改允许输入密码（change_allow_input_pwd）
     * @param allow 1=允许输入 0=禁止输入
     */
    suspend fun changeAllowInputPwd(context: android.content.Context, imei: String, allow: Int): ApiResult = withContext(Dispatchers.IO) {
        val params = baseParams(context) + ("imei" to imei) + ("allow_input_pwd" to allow.toString())
        val (code, body) = httpGet("$BASE/parent_control/change_allow_input_pwd", buildQuery(params))
        AppLogger.i(TAG, "change_allow_input_pwd 响应: HTTP $code $body")
        if (code !in 200..299) return@withContext ApiResult(false, "HTTP $code $body")
        val resp = try { gson.fromJson(body, SimpleResp::class.java) } catch (e: Exception) { null }
        if (resp == null) return@withContext ApiResult(false, "解析失败: ${body.take(200)}")
        if (resp.errno != null && resp.errno != 0) {
            return@withContext ApiResult(false, "${resp.errmsg ?: "errno=${resp.errno}"}")
        }
        ApiResult(true, "已更新")
    }

    // ==================== 设备列表 ====================

    /**
     * 获取绑定设备列表（device_list）
     */
    suspend fun getDeviceList(context: android.content.Context): DeviceListResult = withContext(Dispatchers.IO) {
        val params = baseParams(context) + ("new_first" to "1") + ("only_power" to "1")
        val (code, body) = httpGet("$BASE/parent_control/device_list", buildQuery(params))
        AppLogger.i(TAG, "device_list 响应: HTTP $code $body")
        if (code !in 200..299) return@withContext DeviceListResult(false, "HTTP $code $body")
        val resp = try { gson.fromJson(body, DeviceListResponse::class.java) } catch (e: Exception) { null }
        if (resp == null) return@withContext DeviceListResult(false, "解析失败: ${body.take(200)}")
        if (resp.errno != null && resp.errno != 0) {
            return@withContext DeviceListResult(false, "${resp.errmsg ?: "errno=${resp.errno}"}")
        }
        DeviceListResult(true, "成功", resp)
    }

    // ==================== 通用 ====================

    /** 已登录基础参数：sn + token */
    private fun baseParams(context: android.content.Context, timestampMs: Long = System.currentTimeMillis()): Map<String, String> {
        val uid = LoginStore.getUid(context)
        val token = LoginStore.getToken(context)
        return mapOf(
            "sn" to SignUtil.getSnLoggedIn(uid, timestampMs),
            "token" to token
        )
    }

    private fun buildQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String, query: String): Pair<Int, String> {
        var lastErr: String? = null
        for (attempt in 1..MAX_RETRY) {
            try {
                val full = if (query.isEmpty()) url else "$url?$query"
                val conn = URL(full).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "ZaralynControl/1.0")
                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                }
                conn.disconnect()
                return Pair(code, body)
            } catch (e: Exception) {
                lastErr = e.message
                AppLogger.d(TAG, "GET 异常(尝试$attempt): ${e.message}")
            }
            if (attempt < MAX_RETRY) {
                Thread.sleep(1500L * attempt)
            }
        }
        return Pair(-1, "网络异常: $lastErr")
    }

    /** POST（参数在 URL query，body 空，兼容 Retrofit @QueryMap + POST） */
    private fun httpPost(url: String, query: String): Pair<Int, String> {
        var lastErr: String? = null
        for (attempt in 1..MAX_RETRY) {
            try {
                val full = if (query.isEmpty()) url else "$url?$query"
                val conn = URL(full).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "ZaralynControl/1.0")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { }
                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                }
                conn.disconnect()
                return Pair(code, body)
            } catch (e: Exception) {
                lastErr = e.message
                AppLogger.d(TAG, "POST 异常(尝试$attempt): ${e.message}")
            }
            if (attempt < MAX_RETRY) {
                Thread.sleep(1500L * attempt)
            }
        }
        return Pair(-1, "网络异常: $lastErr")
    }

    // ==================== 响应结构 ====================

    data class MobileRegisterResponse(
        val uid: String? = null,
        @SerializedName("access_token") val access_token: String? = null,
        @SerializedName("access_expire") val access_expire: Int? = null,
        val mobile: String? = null,
        val errno: Int? = null,
        val errmsg: String? = null,
        val registered: Int? = null
    )

    data class SimpleResp(
        val status: Int? = null,
        val errno: Int? = null,
        val errmsg: String? = null
    )

    data class TimeSettingResponse(
        val errno: Int? = null,
        val errmsg: String? = null,
        @SerializedName("anti_addiction") val anti_addiction: AntiData? = null,
        @SerializedName("anti_switch") val anti_switch: Int? = null,
        @SerializedName("limit_switch") val limit_switch: Int? = null,
        @SerializedName("time_switch") val time_switch: Int? = null,
        @SerializedName("extra_day") val extra_day: Int? = null,
        val data: List<TimeData>? = null
    )

    data class AntiData(
        @SerializedName("use_duration") val use_duration: Int? = null,
        @SerializedName("rest_duration") val rest_duration: Int? = null
    )

    data class TimeData(
        val tid: Int? = null,
        val imei: String? = null,
        val group: String? = null,
        @SerializedName("period_status") val period_status: Int? = null,
        val periods: List<PeriodData>? = null,
        @SerializedName("total_time") val total_time: Int? = null,
        val status: Int? = null
    )

    data class PeriodData(
        val start: Int? = null,
        val end: Int? = null
    )

    data class DeviceListResponse(
        val errno: Int? = null,
        val errmsg: String? = null,
        val data: List<DeviceData>? = null
    )

    data class DeviceData(
        val imei: String? = null,
        @SerializedName("allow_input_pwd") val allow_input_pwd: Int? = null,
        @SerializedName("has_allow_pwd") val has_allow_pwd: Int? = null,
        @SerializedName("has_control_time") val has_control_time: Int? = null,
        @SerializedName("has_input_pwd") val has_input_pwd: Int? = null
    )

    // ==================== 结果 ====================

    data class LoginResult(
        val success: Boolean,
        val message: String,
        val uid: Long = 0,
        val token: String = "",
        val expireAt: Long = 0
    )

    data class ApiResult(
        val success: Boolean,
        val message: String
    )

    data class TimeSettingResult(
        val success: Boolean,
        val message: String,
        val response: TimeSettingResponse? = null
    )

    data class DeviceListResult(
        val success: Boolean,
        val message: String,
        val response: DeviceListResponse? = null
    )
}
