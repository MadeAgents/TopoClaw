package com.cloudcontrol.demo

import android.content.Context

/**
 * 管理员模式管理器
 */
object AdminModeManager {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_ADMIN_MODE_ENABLED = "admin_mode_enabled"
    private val ADMIN_PASSWORD: String = BuildConfig.ADMIN_PASSWORD
    
    /**
     * 验证管理员密码
     */
    fun verifyPassword(password: String): Boolean {
        return password == ADMIN_PASSWORD
    }
    
    /**
     * 检查管理员模式是否已启用
     */
    fun isAdminModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ADMIN_MODE_ENABLED, false)
    }
    
    /**
     * 启用管理员模式
     */
    fun enableAdminMode(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ADMIN_MODE_ENABLED, true).apply()
    }
    
    /**
     * 禁用管理员模式
     */
    fun disableAdminMode(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ADMIN_MODE_ENABLED, false).apply()
    }
}

