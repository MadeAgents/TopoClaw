package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * 应用安装辅助类
 * 用于检测应用安装状态、提取包名等
 */
object AppInstallHelper {
    private const val TAG = "AppInstallHelper"
    
    /** 系统显示名 → 云侧期望的应用名，用于 install_apps 与云侧 app_name 匹配（忽略大小写） */
    private val DISPLAY_LABEL_TO_CLOUD_NAME = mapOf(
        "rednote" to "小红书",
        "alipay" to "支付宝",
        "wechat" to "微信"
    )
    
    /**
     * 检测应用是否已安装
     * @param packageName 包名
     * @param context 上下文
     * @return 是否已安装
     */
    fun isAppInstalled(packageName: String, context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.e(TAG, "检测应用安装状态失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 轮询检测应用是否已安装
     * @param packageName 包名
     * @param context 上下文
     * @param maxWaitTime 最大等待时间（毫秒），默认60秒
     * @param checkInterval 检查间隔（毫秒），默认2秒
     * @return 是否安装成功
     */
    suspend fun waitForAppInstallation(
        packageName: String,
        context: Context,
        maxWaitTime: Long = 60_000,
        checkInterval: Long = 2_000
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            if (isAppInstalled(packageName, context)) {
                Log.d(TAG, "检测到应用已安装: $packageName")
                return true
            }
            delay(checkInterval)
        }
        
        Log.w(TAG, "等待应用安装超时: $packageName")
        return false
    }
    
    /**
     * 根据应用名查找包名（从已安装的应用中查找）
     * 优先精确匹配，然后模糊匹配
     * @param appName 应用名
     * @param context 上下文
     * @return 包名，如果找不到返回null
     */
    fun findPackageNameByAppName(appName: String, context: Context): String? {
        try {
            val packageManager = context.packageManager
            val installedPackages = packageManager.getInstalledPackages(0)
            
            // 精确匹配
            val exactMatch = installedPackages.find { 
                try {
                    val label = packageManager.getApplicationLabel(it.applicationInfo).toString()
                    label == appName
                } catch (e: Exception) {
                    false
                }
            }
            if (exactMatch != null) {
                Log.d(TAG, "精确匹配到应用: $appName -> ${exactMatch.packageName}")
                return exactMatch.packageName
            }
            
            // 模糊匹配（包含关键词）
            val fuzzyMatch = installedPackages.find { 
                try {
                    val label = packageManager.getApplicationLabel(it.applicationInfo).toString()
                    label.contains(appName) || appName.contains(label)
                } catch (e: Exception) {
                    false
                }
            }
            if (fuzzyMatch != null) {
                Log.d(TAG, "模糊匹配到应用: $appName -> ${fuzzyMatch.packageName}")
                return fuzzyMatch.packageName
            }
            
            Log.w(TAG, "未找到应用: $appName")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "查找包名失败: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 获取已安装应用「应用名 -> 包名」映射（与 getInstalledAppNamesForCloud 同源）
     * 当「是否获取已安装应用」开关开启时，用于 open 动作根据应用名查找包名
     * @param context 上下文
     * @return 应用名 -> 包名的映射
     */
    fun getInstalledAppsNameToPackageMap(context: Context): Map<String, String> {
        try {
            val pm = context.packageManager
            val resolveInfos = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
            val map = mutableMapOf<String, String>()
            resolveInfos.distinctBy { it.activityInfo.packageName }.forEach { resolveInfo ->
                val pkgName = resolveInfo.activityInfo.packageName
                val rawName = AppMappingManager.getAppName(pkgName)
                    ?: try {
                        resolveInfo.activityInfo.applicationInfo.loadLabel(pm).toString().trim()
                    } catch (e: Exception) {
                        ""
                    }.takeIf { it.isNotEmpty() }
                rawName?.let { name ->
                    val cloudName = DISPLAY_LABEL_TO_CLOUD_NAME[name.trim().lowercase()] ?: name
                    map[cloudName] = pkgName
                }
            }
            Log.d(TAG, "getInstalledAppsNameToPackageMap: 获取到 ${map.size} 个应用映射")
            return map
        } catch (e: Exception) {
            Log.e(TAG, "获取已安装应用映射失败: ${e.message}", e)
            return emptyMap()
        }
    }

    /**
     * 获取已安装应用名称列表（MVP格式，用于传递给云侧install_apps）
     * 与「已安装应用包名」功能相同的获取方式：仅桌面应用，包名转应用名
     * @param context 上下文
     * @return 应用名称列表，如 ["微信", "支付宝", "抖音"]
     */
    fun getInstalledAppNamesForCloud(context: Context): List<String> {
        try {
            val pm = context.packageManager
            val resolveInfos = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0
            )
            val appNames = resolveInfos
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { resolveInfo ->
                    val pkgName = resolveInfo.activityInfo.packageName
                    val rawName = AppMappingManager.getAppName(pkgName)
                        ?: try {
                            resolveInfo.activityInfo.applicationInfo.loadLabel(pm).toString().trim()
                        } catch (e: Exception) {
                            ""
                        }.takeIf { it.isNotEmpty() }
                    rawName?.let { name ->
                        DISPLAY_LABEL_TO_CLOUD_NAME[name.trim().lowercase()] ?: name
                    }
                }
                .distinct()
                .sortedBy { it.lowercase() }
            Log.d(TAG, "getInstalledAppNamesForCloud: 获取到 ${appNames.size} 个应用")
            return appNames
        } catch (e: Exception) {
            Log.e(TAG, "获取已安装应用名称列表失败: ${e.message}", e)
            return emptyList()
        }
    }

    /**
     * 查找最近安装的应用（在指定时间范围内）
     * @param context 上下文
     * @param timeWindow 时间窗口（毫秒），默认5分钟
     * @return 最近安装的应用列表（包名列表）
     */
    fun findRecentlyInstalledApps(context: Context, timeWindow: Long = 5 * 60 * 1000): List<String> {
        try {
            val packageManager = context.packageManager
            val installedPackages = packageManager.getInstalledPackages(0)
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - timeWindow
            
            // 注意：PackageInfo.firstInstallTime 和 lastUpdateTime 可能不准确
            // 这里使用一个简化的方法：返回所有已安装的应用
            // 实际使用时，应该结合安装开始时间来判断
            return installedPackages.map { it.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "查找最近安装的应用失败: ${e.message}", e)
            return emptyList()
        }
    }
}

