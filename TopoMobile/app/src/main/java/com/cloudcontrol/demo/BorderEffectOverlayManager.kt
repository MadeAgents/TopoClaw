package com.cloudcontrol.demo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 炫彩边框特效管理器
 * 负责管理屏幕边缘炫彩边框的显示和隐藏
 */
object BorderEffectOverlayManager {
    private const val TAG = "BorderEffectOverlay"
    
    private var overlayView: BorderEffectView? = null
    private var windowManager: WindowManager? = null
    
    /**
     * 初始化管理器
     */
    fun initialize(context: Context) {
        if (overlayView != null) {
            Log.d(TAG, "边框特效已初始化，跳过")
            return
        }
        
        try {
            // 检查权限（Android 6.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "没有悬浮窗权限，无法显示边框特效")
                    return
                }
            }
            
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            Log.d(TAG, "边框特效管理器初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "初始化边框特效管理器失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查是否开启了边框特效
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("border_effect_enabled", false) // 默认false（关闭），与ServiceFragment保持一致
    }
    
    /**
     * 显示边框特效
     */
    fun show(context: Context) {
        // 检查设置是否开启
        if (!isEnabled(context)) {
            Log.d(TAG, "边框特效未开启，跳过显示")
            return
        }
        
        if (overlayView != null) {
            Log.d(TAG, "边框特效已显示，跳过")
            return
        }
        
        try {
            // 检查权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "没有悬浮窗权限，无法显示边框特效")
                    return
                }
            }
            
            val wm = windowManager ?: run {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                windowManager
            }
            
            if (wm == null) {
                Log.e(TAG, "WindowManager为null，无法显示边框特效")
                return
            }
            
            // 创建边框View
            overlayView = BorderEffectView(context)
            
            // 创建窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            
            // 确保View不拦截触摸事件
            overlayView?.isClickable = false
            overlayView?.isFocusable = false
            overlayView?.isFocusableInTouchMode = false
            overlayView?.setFilterTouchesWhenObscured(false)
            
            // 添加到窗口管理器
            wm.addView(overlayView, params)
            Log.d(TAG, "边框特效已显示")
        } catch (e: SecurityException) {
            Log.e(TAG, "显示边框特效失败：缺少悬浮窗权限", e)
        } catch (e: Exception) {
            Log.e(TAG, "显示边框特效失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 隐藏边框特效
     */
    fun hide() {
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
                view.cleanup()
                overlayView = null
                Log.d(TAG, "边框特效已隐藏")
            }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏边框特效失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查是否正在显示
     */
    fun isShowing(): Boolean {
        return overlayView != null
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
        windowManager = null
        Log.d(TAG, "边框特效管理器已清理")
    }
}

