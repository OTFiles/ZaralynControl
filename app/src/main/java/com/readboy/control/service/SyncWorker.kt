package com.readboy.control.service

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.readboy.control.AppLogger
import com.readboy.control.network.CloudSyncEngine
import com.readboy.control.network.DeviceUtil
import com.readboy.control.network.SyncEngine
import com.readboy.control.network.VersionDetector
import java.util.concurrent.TimeUnit

/**
 * 后台同步 Worker
 *
 * 默认 1 分钟间隔，可配置。
 * 每次同步：本地双向同步 → 云端上传（如开启）
 * 重试 3 次（已在 SyncEngine/CloudSyncEngine 内部实现）
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val prefs: SharedPreferences =
        applicationContext.getSharedPreferences("zaralyn_control_prefs", Context.MODE_PRIVATE)

    override fun doWork(): Result {
        AppLogger.i("SyncWorker", "===== 后台同步开始 =====")

        return try {
            // 1. 检测版本
            val version = VersionDetector.detect(applicationContext)
            if (version == VersionDetector.PmsVersion.UNKNOWN) {
                AppLogger.w("SyncWorker", "未检测到家长管理，跳过同步")
                return Result.success()
            }
            AppLogger.d("SyncWorker", "当前版本: $version")

            // 2. 本地双向同步
            val syncResult = runBlockingSync {
                SyncEngine.sync(applicationContext)
            }
            AppLogger.i("SyncWorker", "本地同步: ${syncResult.message}")

            // 3. 云端上传（如果开启）
            val cloudSyncEnabled = prefs.getBoolean("cloud_sync_enabled", false)
            if (cloudSyncEnabled) {
                val imei = DeviceUtil.getEffectiveSerial()
                if (imei != null) {
                    val cloudResult = runBlockingSync {
                        CloudSyncEngine.pushToCloud(imei)
                    }
                    AppLogger.i("SyncWorker", "云端上传: ${cloudResult.message}")
                } else {
                    AppLogger.w("SyncWorker", "无法获取设备序列号，跳过云端上传")
                }
            }

            AppLogger.i("SyncWorker", "===== 后台同步完成 =====")
            Result.success()
        } catch (e: Exception) {
            AppLogger.e("SyncWorker", "同步异常: ${e.message}", e)
            Result.retry()
        }
    }

    private fun <T> runBlockingSync(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }

    private fun getDeviceSerial(): String? {
        return try {
            android.os.Build.getSerial()
        } catch (e: SecurityException) {
            // 无权限，尝试 root 方式
            try {
                val proc = Runtime.getRuntime().exec("getprop ro.serialno")
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
                val serial = reader.readLine()?.trim()
                reader.close()
                proc.destroy()
                serial
            } catch (e2: Exception) {
                AppLogger.e("SyncWorker", "获取序列号失败: ${e2.message}", e2)
                null
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "zaralyn_control_sync"

        /**
         * 启动或更新周期同步
         * @param intervalMinutes 同步间隔（分钟），默认 1
         */
        fun schedule(context: Context, intervalMinutes: Long = 1) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            AppLogger.i(TAG, "计划后台同步: 每 ${intervalMinutes} 分钟")
        }

        /** 取消周期同步 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            AppLogger.i(TAG, "取消后台同步")
        }
    }
}