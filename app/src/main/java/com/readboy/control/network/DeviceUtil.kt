package com.readboy.control.network

import android.content.Context
import com.readboy.control.AppLogger
import com.readboy.control.ZaralynControlApp

/**
 * 设备序列号获取工具 + 远程模式管理
 *
 * 优先检查 SharedPreferences 中的自定义远程序列号（remote_serial），
 * 如果设置了则使用它（远程模式，仅发请求不修改本地数据库），
 * 否则使用本机设备序列号。
 *
 * 当家长管理 App 未安装时自动进入远程模式。
 */
object DeviceUtil {

    private const val TAG = "DeviceUtil"

    /** 获取当前生效的设备序列号（优先使用自定义远程序列号） */
    fun getEffectiveSerial(): String? {
        val remoteSerial = getRemoteSerial()
        if (!remoteSerial.isNullOrEmpty()) {
            AppLogger.d(TAG, "使用远程设备序列号: $remoteSerial（仅请求模式）")
            return remoteSerial
        }
        return getDeviceSerial()
    }

    /** 是否处于远程模式（设置了自定义序列号，或家长管理未安装时自动进入） */
    fun isRemoteMode(): Boolean {
        val remoteSerial = getRemoteSerial()
        if (!remoteSerial.isNullOrEmpty()) return true
        // 家长管理未安装且未设置序列号→提示用户设置，仍算远程模式
        return !isParentManagerAvailable()
    }

    /** 是否已设置自定义远程序列号 */
    fun hasRemoteSerial(): Boolean {
        return !getRemoteSerial().isNullOrEmpty()
    }

    /** 获取自定义远程序列号 */
    fun getRemoteSerial(): String? {
        val prefs = ZaralynControlApp.instance.getSharedPreferences("zaralyn_control_prefs", Context.MODE_PRIVATE)
        return prefs.getString("remote_serial", "")?.trim()
    }

    /** 保存远程序列号 */
    fun saveRemoteSerial(serial: String) {
        val prefs = ZaralynControlApp.instance.getSharedPreferences("zaralyn_control_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("remote_serial", serial).apply()
    }

    /** 检查家长管理 App 是否已安装 */
    fun isParentManagerAvailable(): Boolean {
        return VersionDetector.detect(ZaralynControlApp.instance) != VersionDetector.PmsVersion.UNKNOWN
    }

    /** 获取本机设备序列号 */
    private fun getDeviceSerial(): String? {
        return try {
            val serial = android.os.Build.getSerial()
            if (serial.isNullOrBlank() || serial == "unknown") {
                fallbackSerial()
            } else serial
        } catch (e: SecurityException) {
            fallbackSerial()
        }
    }

    /** 通过 root 方式 fallback 获取序列号 */
    private fun fallbackSerial(): String? {
        return try {
            val proc = Runtime.getRuntime().exec("getprop ro.serialno")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            val serial = reader.readLine()?.trim()
            reader.close()
            proc.destroy()
            if (serial.isNullOrBlank()) null else serial
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取序列号失败: ${e.message}", e)
            null
        }
    }
}