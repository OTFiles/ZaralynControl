package com.readboy.control.network

import android.content.Context
import com.readboy.control.AppLogger
import com.readboy.control.ZaralynControlApp

/**
 * 设备序列号获取工具
 *
 * 优先检查 SharedPreferences 中的自定义远程序列号（remote_serial），
 * 如果设置了则使用它（远程模式，仅发请求不修改本地数据库），
 * 否则使用本机设备序列号。
 */
object DeviceUtil {

    private const val TAG = "DeviceUtil"

    /** 获取当前生效的设备序列号（优先使用自定义远程序列号） */
    fun getEffectiveSerial(): String? {
        // 检查自定义远程序列号
        val prefs = ZaralynControlApp.instance.getSharedPreferences("zaralyn_control_prefs", Context.MODE_PRIVATE)
        val remoteSerial = prefs.getString("remote_serial", "")?.trim()
        if (!remoteSerial.isNullOrEmpty()) {
            AppLogger.d(TAG, "使用远程设备序列号: $remoteSerial（仅请求模式）")
            return remoteSerial
        }
        // 使用本机序列号
        return getDeviceSerial()
    }

    /** 是否处于远程模式（设置了自定义序列号） */
    fun isRemoteMode(): Boolean {
        val prefs = ZaralynControlApp.instance.getSharedPreferences("zaralyn_control_prefs", Context.MODE_PRIVATE)
        val remoteSerial = prefs.getString("remote_serial", "")?.trim()
        return !remoteSerial.isNullOrEmpty()
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