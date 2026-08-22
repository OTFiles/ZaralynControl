package com.readboy.control.network

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.readboy.control.AppLogger

/**
 * 家长管理版本检测（复用 ZaralynSetting 的 PRAGMA 探测逻辑）
 *
 * 版本判据：PRAGMA install_app_list 是否包含 disabled_state 列
 *   - 包含 → NEW 新版（6.2.8+）
 *   - 不包含 → OLD 老版
 *   - 查询失败 → UNKNOWN
 */
object VersionDetector {

    private const val TAG = "VersionDetector"
    private const val AUTHORITY = "com.readboy.parentmanager.AppContentProvider"
    private const val SQLITE_AUTHORITY = "com.readboy.parentmanager.SqliteProvider"

    enum class PmsVersion {
        OLD,    // 老版：forbidden_app(state 0/1) + un_mall_app_state
        NEW,    // 6.2.8+：install_app_list(disabled_state)
        UNKNOWN
    }

    private var cachedVersion: PmsVersion? = null

    /** 检测版本（有缓存，先直接 content 查询再 fallback 包管理） */
    fun detect(context: Context): PmsVersion {
        cachedVersion?.let { return it }

        // 先尝试直接 content 查询（不受包可见性/停止状态影响）
        val auth = resolveAuthority(context)
        AppLogger.d(TAG, "探测版本，authority=$auth")

        val version = probeVersion(context, auth)
        if (version != PmsVersion.UNKNOWN) {
            cachedVersion = version
            AppLogger.i(TAG, "版本检测完成: $version")
            return version
        }

        // 失败则全量扫描
        val scanResult = scanForVersion(context)
        cachedVersion = scanResult
        AppLogger.i(TAG, "扫描检测完成: $scanResult")
        return scanResult
    }

    fun clearCache() {
        cachedVersion = null
    }

    fun isNewVersion(context: Context): Boolean = detect(context) == PmsVersion.NEW
    fun isOldVersion(context: Context): Boolean = detect(context) == PmsVersion.OLD

    /** 获取 provider authority（解析或回退默认） */
    private fun resolveAuthority(context: Context): String {
        return try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0)
            if (provider?.authority != null) {
                AppLogger.d(TAG, "解析 authority: ${provider.authority}")
                provider.authority
            } else {
                AUTHORITY
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "解析 authority 失败，用默认值: $AUTHORITY")
            AUTHORITY
        }
    }

    /** 直接 content 查询探测版本 */
    private fun probeVersion(context: Context, auth: String): PmsVersion {
        // 尝试 PRAGMA 查询（新版/旧版都能识别）
        val pragmaUri = Uri.parse("content://$auth/raw_sql")
        val sql = "PRAGMA table_info(install_app_list)"
        return try {
            val cursor = context.contentResolver.query(pragmaUri, null, sql, null, null)
            cursor?.use {
                val columns = mutableListOf<String>()
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow("name"))
                    columns.add(name)
                }
                AppLogger.d(TAG, "install_app_list 列: $columns")
                if ("disabled_state" in columns) {
                    PmsVersion.NEW
                } else if ("state" in columns) {
                    PmsVersion.OLD
                } else {
                    PmsVersion.UNKNOWN
                }
            } ?: PmsVersion.UNKNOWN
        } catch (e: Exception) {
            AppLogger.w(TAG, "PRAGMA 查询失败: ${e.message}")
            PmsVersion.UNKNOWN
        }
    }

    /** 全量扫描已安装应用找家长管理（兼容 Android 10 包可见性限制） */
    private fun scanForVersion(context: Context): PmsVersion {
        return try {
            // 参照 ZaralynSetting: 使用 MATCH_UNINSTALLED_PACKAGES 包含被强制停止的应用
            val packages = if (android.os.Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getInstalledPackages(
                    android.content.pm.PackageManager.GET_PROVIDERS or
                    android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
                )
            } else {
                context.packageManager.getInstalledPackages(android.content.pm.PackageManager.GET_PROVIDERS)
            }
            for (pkg in packages) {
                val pn = pkg.packageName
                if (pn.contains("parentmanager") || pn.contains("readboy.parent")) {
                    AppLogger.d(TAG, "找到候选家长管理: $pn")
                    // 尝试用其 provider 查询
                    // 扫描所有 provider 而非简单拼接 authority
                    val providers = pkg.providers
                    if (providers != null) {
                        for (pr in providers) {
                            if (pr.authority?.contains("AppContentProvider") == true) {
                                val auth = pr.authority!!
                                AppLogger.d(TAG, "候选 provider: $auth")
                                val result = probeVersion(context, auth)
                                if (result != PmsVersion.UNKNOWN) return result
                            }
                        }
                    }
                }
            }
            PmsVersion.UNKNOWN
        } catch (e: Exception) {
            AppLogger.e(TAG, "全量扫描失败: ${e.message}", e)
            PmsVersion.UNKNOWN
        }
    }
}