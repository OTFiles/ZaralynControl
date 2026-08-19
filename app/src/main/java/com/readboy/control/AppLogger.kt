package com.readboy.control

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用内日志系统，日志可复制 */
object AppLogger {

    private const val TAG = "ZaralynControl"
    private val logBuffer = StringBuilder()
    private val listeners = mutableListOf<OnLogListener>()

    interface OnLogListener {
        fun onLogAdded(line: String)
    }

    fun addListener(listener: OnLogListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnLogListener) {
        listeners.remove(listener)
    }

    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp][$level] $msg"
        synchronized(logBuffer) {
            logBuffer.appendLine(line)
            if (tr != null) {
                logBuffer.appendLine("  ${Log.getStackTraceString(tr)}")
            }
            // 限制日志长度
            if (logBuffer.length > 50000) {
                val truncated = logBuffer.substring(logBuffer.length - 40000)
                logBuffer.clear()
                logBuffer.append(truncated)
            }
        }
        // 通知监听器
        listeners.forEach { it.onLogAdded(line) }
        // 同时输出到 logcat
        when (level) {
            "E" -> Log.e(tag, msg, tr)
            "W" -> Log.w(tag, msg, tr)
            "I" -> Log.i(tag, msg, tr)
            "D" -> Log.d(tag, msg, tr)
            else -> Log.d(tag, msg, tr)
        }
    }

    fun d(msg: String) = log("D", TAG, msg)
    fun i(msg: String) = log("I", TAG, msg)
    fun w(msg: String) = log("W", TAG, msg)
    fun e(msg: String, tr: Throwable? = null) = log("E", TAG, msg, tr)

    /** 带自定义 tag 的重载 */
    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("E", tag, msg, tr)

    /** 获取完整日志文本 */
    fun getLogText(): String {
        synchronized(logBuffer) {
            return logBuffer.toString()
        }
    }

    /** 清空日志 */
    fun clear() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}