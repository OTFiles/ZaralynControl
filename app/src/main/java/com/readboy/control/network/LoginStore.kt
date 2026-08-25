package com.readboy.control.network

import android.content.Context
import android.content.SharedPreferences

/**
 * 家长账号登录状态存储（api-super 域）
 *
 * 存储 uid / access_token / access_expire / 手机号，
 * 供时间管控、允许输入密码等 api-super 接口调用。
 */
object LoginStore {

    private const val PREFS_NAME = "zaralyn_login_prefs"
    private const val KEY_UID = "login_uid"
    private const val KEY_TOKEN = "login_token"
    private const val KEY_EXPIRE = "login_expire"   // 过期时间戳（秒）
    private const val KEY_MOBILE = "login_mobile"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLogin(context: Context, uid: Long, token: String, expireAtSec: Long, mobile: String) {
        prefs(context).edit()
            .putLong(KEY_UID, uid)
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRE, expireAtSec)
            .putString(KEY_MOBILE, mobile)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** uid（0 表示未登录） */
    fun getUid(context: Context): Long = prefs(context).getLong(KEY_UID, 0L)

    /** token，可能为空 */
    fun getToken(context: Context): String = prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun getMobile(context: Context): String = prefs(context).getString(KEY_MOBILE, "") ?: ""

    /** 是否已登录且未过期 */
    fun isLoggedIn(context: Context): Boolean {
        val uid = getUid(context)
        val token = getToken(context)
        if (uid <= 0 || token.isEmpty()) return false
        val expire = prefs(context).getLong(KEY_EXPIRE, 0L)
        return expire <= 0 || System.currentTimeMillis() / 1000 < expire
    }

    /** 过期时间（秒），0=未知 */
    fun getExpireAt(context: Context): Long = prefs(context).getLong(KEY_EXPIRE, 0L)
}
