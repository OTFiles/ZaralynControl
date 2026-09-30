package com.readboy.control.network

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    // ==================== 验证码登录：手机号 AES 加密 ====================
    // 反编译 com.readboy.rbmanager.util.AESUtil + JMBase64

    /**
     * AES-256-CBC(PKCS5Padding) 加密手机号，返回 URL-safe Base64（IV 前置）
     *
     * key = RB_MANAGER_SECRET 的 UTF-8 字节（32 字节 → AES-256）
     * IV = 随机 16 字节，拼接在密文之前
     * 输出 = JMBase64(IV + cipher)，字符表 A-Za-z0-9-_，保留 '=' padding
     */
    fun aesEncryptForQuery(plain: String): String {
        val keySpec = SecretKeySpec(RB_MANAGER_SECRET.toByteArray(Charsets.UTF_8), "AES")
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        // URL_SAFE 使用 -_ 字符表并保留 '=' padding（与 JMBase64 一致）
        return Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /**
     * 解密 encrypt=1 接口的加密响应体
     *
     * 响应 = URL-safe Base64(IV(16B) + AES-256-CBC 密文)，key 同上
     * @return 解密后的 UTF-8 字符串，失败返回 null
     */
    fun aesDecryptFromBase64(data: String): String? {
        return try {
            val raw = Base64.decode(data.trim(), Base64.NO_WRAP or Base64.URL_SAFE)
            if (raw.size <= 16) return null
            val keySpec = SecretKeySpec(RB_MANAGER_SECRET.toByteArray(Charsets.UTF_8), "AES")
            val iv = raw.copyOfRange(0, 16)
            val cipherText = raw.copyOfRange(16, raw.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}