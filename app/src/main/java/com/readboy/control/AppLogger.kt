package com.readboy.control

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用内日志系统，日志可复制，按日期存文件 */
object AppLogger {

    private const val TAG = "ZaralynControl"
    private const val LOG_DIR = "logdir"
    private val logBuffer = StringBuilder()
    private val listeners = mutableListOf<OnLogListener>()
    private var logDir: File? = null
    private var currentDate: String? = null
    private var currentFile: File? = null

    interface OnLogListener {
        fun onLogAdded(line: String)
    }

    fun addListener(listener: OnLogListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnLogListener) {
        listeners.remove(listener)
    }

    /** 初始化日志目录（在 Application.onCreate 中调用） */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR)
        logDir?.mkdirs()
        i(TAG, "日志目录: ${logDir?.absolutePath}")
    }

    private fun ensureLogFile() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (today != currentDate) {
            currentDate = today
            currentFile = logDir?.let { File(it, "$today.log") }
        }
    }

    private fun writeToFile(line: String) {
        try {
            ensureLogFile()
            currentFile?.appendText("$line\n")
        } catch (e: Exception) {
            Log.e(TAG, "写入日志文件失败: ${e.message}", e)
        }
    }

    private fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp][$level][$tag] $msg"
        val fullLine = if (tr != null) "$line\n  ${Log.getStackTraceString(tr)}" else line

        // 写入文件
        writeToFile(fullLine)

        // 写入内存缓冲
        synchronized(logBuffer) {
            logBuffer.appendLine(fullLine)
            if (logBuffer.length > 200000) {
                val truncated = logBuffer.substring(logBuffer.length - 180000)
                logBuffer.clear()
                logBuffer.append(truncated)
            }
        }

        // 通知监听器（UI 显示）
        listeners.forEach { it.onLogAdded(line) }

        // 输出到 logcat
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

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log("E", tag, msg, tr)

    fun getLogText(): String {
        synchronized(logBuffer) {
            return logBuffer.toString()
        }
    }

    fun clear() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
    }
}