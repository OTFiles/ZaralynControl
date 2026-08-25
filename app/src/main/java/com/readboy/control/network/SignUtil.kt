package com.readboy.control.network

import java.security.MessageDigest

/**
 * 家长管理签名算法（反编译自 Sign.smali）
 *
 * getSign2(params) = MD5(秒 + APPSECRET + MD5(APP_ID2))
 *   → 用于一般请求的 signature 参数
 *
 * getSign(uid, timestamp_ms) = uid + 秒 + MD5(秒 + APP_KEY + MD5(APP_ID)) + APP_ID
 *   → 用于 header "sn"（parentadmin 身份认证）
 */
object SignUtil {

    // Sign.smali 常量（反编译 6.2.8）
    private const val APPSECRET = "de917e0e6b4962061d66d24f6cfdb5bf0d1b9b39"
    private const val APP_ID2 = "parent-manage"
    private const val APP_KEY = "9b332c2653ce7189da101dac5a63fd4e"
    private const val APP_ID = "parentsadmin"
    private const val DEFAULT_UID = "00000000"

    /**
     * getSign2：MD5(秒 + APPSECRET + MD5(APP_ID2))
     * 用于请求参数 signature
     */
    fun getSign2(timestampMs: Long = System.currentTimeMillis()): String {
        val seconds = timestampMs / 1000
        val input = "$seconds$APPSECRET${md5(APP_ID2)}"
        return md5(input)
    }

    /**
     * getSign：uid + 秒 + MD5(秒 + APP_KEY + MD5(APP_ID)) + APP_ID
     * 用于 header "sn"
     */
    fun getSign(uid: String = DEFAULT_UID, timestampMs: Long = System.currentTimeMillis()): String {
        val seconds = timestampMs / 1000
        val inner = "$seconds$APP_KEY${md5(APP_ID)}"
        val md5Inner = md5(inner)
        return "$uid$seconds$md5Inner$APP_ID"
    }

    /**
     * 获取标准请求参数 Map
     */
    fun getCommonParams(imei: String, timestampMs: Long = System.currentTimeMillis()): Map<String, String> {
        return mapOf(
            "signature" to getSign2(timestampMs),
            "imei" to imei,
            "timestamp" to (timestampMs / 1000).toString(),
            "app_id" to APP_ID2
        )
    }

    /** 公共参数拼接为 URL query string */
    fun getCommonQueryString(imei: String, timestampMs: Long = System.currentTimeMillis()): String {
        val seconds = timestampMs / 1000
        return "signature=${getSign2(timestampMs)}&imei=$imei&timestamp=$seconds&app_id=$APP_ID2"
    }

    /** MD5 小写 hex（密码加密用） */
    fun md5Password(input: String): String = md5(input)

    /** MD5 小写 hex */
    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ==================== api-super 域签名（家长助手手机端） ====================
    // 反编译 com.readboy.rbmanager Util.smali getSn()

    /** api-super 域密钥 */
    private const val RB_MANAGER_SECRET = "2f6de49d30f32a4dbf67500b80bb7074"
    private const val RB_MANAGER_PACKAGE = "com.readboy.rbmanager"

    /**
     * getUid8：8 位前补零
     */
    fun getUid8(uid: Long): String = String.format("%08d", uid)

    /**
     * getSn：uid8 + ts + MD5(ts + SECRET + arg3) + 包名
     * @param arg3 登录时 = MD5(包名)；已登录时 = MD5(uid8)
     */
    fun getSn(uid8: String, timestampMs: Long, arg3: String): String {
        val seconds = timestampMs / 1000
        val inner = md5("$seconds$RB_MANAGER_SECRET$arg3")
        return "$uid8$seconds$inner$RB_MANAGER_PACKAGE"
    }

    /** 已登录 getSn：arg3 = MD5(uid8) */
    fun getSnLoggedIn(uid: Long, timestampMs: Long = System.currentTimeMillis()): String {
        val uid8 = getUid8(uid)
        return getSn(uid8, timestampMs, md5(uid8))
    }

    /** 登录时 getSn：arg3 = MD5(包名) */
    fun getSnForLogin(timestampMs: Long = System.currentTimeMillis()): String {
        return getSn(getUid8(0), timestampMs, md5(RB_MANAGER_PACKAGE))
    }
}