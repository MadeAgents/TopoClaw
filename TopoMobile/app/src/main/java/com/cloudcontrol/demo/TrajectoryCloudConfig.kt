package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 轨迹云侧服务配置管理
 * 负责管理云侧上传相关的配置项
 */
object TrajectoryCloudConfig {
    private const val TAG = "TrajectoryCloudConfig"
    private const val PREFS_NAME = "trajectory_cloud_prefs"
    
    // 配置键
    private const val KEY_ENABLED = "trajectory_cloud_enabled"
    private const val KEY_SERVER_URL = "trajectory_cloud_server_url"
    private const val KEY_UPLOAD_SCREENSHOT = "trajectory_cloud_upload_screenshot"
    private const val KEY_UPLOAD_XML = "trajectory_cloud_upload_xml"
    private const val KEY_CAPTURE_DELAY_MS = "trajectory_capture_delay_ms"
    private const val KEY_COLLECT_ACTIVITY = "trajectory_collect_activity_enabled"
    
    // 默认值（来自 local.properties / 环境变量，避免写死在代码）
    private val DEFAULT_SERVER_URL: String by lazy {
        normalizeUrl(BuildConfig.MOBILE_AGENT_TRAJECTORY_CLOUD_URL)
    }
    
    private var prefs: SharedPreferences? = null
    
    /**
     * 初始化配置（需要在Application或Activity中调用）
     */
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "轨迹云侧配置已初始化")
    }
    
    /**
     * 检查是否启用云侧上传
     */
    fun isEnabled(): Boolean {
        return prefs?.getBoolean(KEY_ENABLED, true) ?: true
    }
    
    /**
     * 设置是否启用云侧上传
     */
    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        Log.d(TAG, "云侧上传已${if (enabled) "启用" else "禁用"}")
    }
    
    /**
     * 获取服务器地址
     */
    fun getServerUrl(): String {
        val value = prefs?.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        return normalizeUrl(value)
    }
    
    /**
     * 设置服务器地址
     */
    fun setServerUrl(url: String) {
        val normalizedUrl = normalizeUrl(url)
        prefs?.edit()?.putString(KEY_SERVER_URL, normalizedUrl)?.apply()
        Log.d(TAG, "服务器地址已设置为: $normalizedUrl")
    }
    
    /**
     * 检查是否上传截图
     */
    fun shouldUploadScreenshot(): Boolean {
        return prefs?.getBoolean(KEY_UPLOAD_SCREENSHOT, true) ?: true
    }
    
    /**
     * 设置是否上传截图
     */
    fun setUploadScreenshot(upload: Boolean) {
        prefs?.edit()?.putBoolean(KEY_UPLOAD_SCREENSHOT, upload)?.apply()
        Log.d(TAG, "上传截图已${if (upload) "启用" else "禁用"}")
    }
    
    /**
     * 检查是否上传页面 XML
     */
    fun isXmlEnabled(): Boolean {
        return prefs?.getBoolean(KEY_UPLOAD_XML, false) ?: false
    }
    
    /**
     * 设置是否上传页面 XML
     */
    fun setUploadXml(upload: Boolean) {
        prefs?.edit()?.putBoolean(KEY_UPLOAD_XML, upload)?.apply()
        Log.d(TAG, "上传XML已${if (upload) "启用" else "禁用"}")
    }
    
    /**
     * 获取采集前等待时间（毫秒），用于等待页面稳定
     * 0 表示不等待
     */
    fun getCaptureDelayMs(): Int {
        val v = prefs?.getInt(KEY_CAPTURE_DELAY_MS, 0) ?: 0
        return v.coerceIn(0, 2000) // 限制在 0~2000ms
    }
    
    /**
     * 设置采集前等待时间（毫秒）
     */
    fun setCaptureDelayMs(ms: Int) {
        val v = ms.coerceIn(0, 2000)
        prefs?.edit()?.putInt(KEY_CAPTURE_DELAY_MS, v)?.apply()
        Log.d(TAG, "采集前等待已设置为: ${v}ms")
    }
    
    /**
     * 检查是否采集 Activity（窗口切换时记录真实前台应用）
     * 默认关闭
     */
    fun isCollectActivityEnabled(): Boolean {
        return prefs?.getBoolean(KEY_COLLECT_ACTIVITY, false) ?: false
    }
    
    /**
     * 设置是否采集 Activity
     */
    fun setCollectActivityEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_COLLECT_ACTIVITY, enabled)?.apply()
        Log.d(TAG, "采集 Activity 已${if (enabled) "启用" else "禁用"}")
    }
    
    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        val url = getServerUrl()
        return url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))
    }

    private fun normalizeUrl(url: String?): String {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }
}

