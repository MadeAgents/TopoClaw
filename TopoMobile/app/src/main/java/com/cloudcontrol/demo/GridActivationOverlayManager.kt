package com.cloudcontrol.demo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager

/**
 * 网格激活特效管理器
 * 负责管理全息网格激活特效的显示和隐藏
 */
object GridActivationOverlayManager {
    private const val TAG = "GridActivationOverlay"
    
    private var overlayView: GridActivationEffectView? = null
    private var windowManager: WindowManager? = null
    
    /**
     * 初始化管理器
     */
    fun initialize(context: Context) {
        if (overlayView != null) {
            Log.d(TAG, "网格激活特效已初始化，跳过")
            return
        }
        
        try {
            // 检查权限（Android 6.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "没有悬浮窗权限，无法显示网格激活特效")
                    return
                }
            }
            
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            Log.d(TAG, "网格激活特效管理器初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "初始化网格激活特效管理器失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查是否开启了网格激活特效
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("grid_activation_effect_enabled", false)  // 默认关闭
    }
    
    /**
     * 显示网格激活特效（一次性播放）
     */
    fun showOnce(context: Context) {
        Log.d(TAG, "========== showOnce 开始 ==========")
        
        // 检查设置是否开启
        if (!isEnabled(context)) {
            Log.d(TAG, "网格激活特效未开启，跳过显示")
            return
        }
        
        // 如果已经有特效在显示，先隐藏
        if (overlayView != null) {
            Log.d(TAG, "网格激活特效已显示，先隐藏旧的")
            hide()
        }
        
        try {
            // 检查权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasPermission = Settings.canDrawOverlays(context)
                Log.d(TAG, "悬浮窗权限检查: $hasPermission")
                if (!hasPermission) {
                    Log.w(TAG, "没有悬浮窗权限，无法显示网格激活特效")
                    return
                }
            } else {
                Log.d(TAG, "Android版本低于6.0，跳过权限检查")
            }
            
            val wm = windowManager ?: run {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                windowManager
            }
            
            if (wm == null) {
                Log.e(TAG, "WindowManager为null，无法显示网格激活特效")
                return
            }
            
            // 创建网格激活View
            overlayView = GridActivationEffectView(context).apply {
                // 设置动画完成回调
                onAnimationCompleteCallback = {
                    Log.d(TAG, "网格激活动画完成，自动隐藏特效")
                    hide()
                }
            }
            
            // 获取屏幕实际尺寸（包括导航栏）
            val displayMetrics = DisplayMetrics()
            val display = wm.defaultDisplay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealMetrics(displayMetrics)
            } else {
                @Suppress("DEPRECATION")
                display.getMetrics(displayMetrics)
            }
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            Log.d(TAG, "屏幕实际尺寸: ${screenWidth}x${screenHeight}")
            
            // 创建窗口参数
            val params = WindowManager.LayoutParams(
                screenWidth,
                screenHeight,
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
            Log.d(TAG, "准备将View添加到WindowManager，View: $overlayView, Params: width=${params.width}, height=${params.height}")
            wm.addView(overlayView, params)
            Log.d(TAG, "网格激活特效View已添加到窗口，等待尺寸确定后启动动画")
            
            // 延迟启动激活动画，确保View完成onSizeChanged
            overlayView?.postDelayed({
                Log.d(TAG, "延迟启动网格激活动画，View尺寸: ${overlayView?.width}x${overlayView?.height}")
                overlayView?.startActivation()
            }, 100)  // 延迟100ms确保View尺寸已确定
            
            Log.d(TAG, "========== showOnce 完成 ==========")
        } catch (e: SecurityException) {
            Log.e(TAG, "显示网格激活特效失败：缺少悬浮窗权限", e)
        } catch (e: Exception) {
            Log.e(TAG, "显示网格激活特效失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 隐藏网格激活特效
     */
    fun hide() {
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
                view.cleanup()
                overlayView = null
                Log.d(TAG, "网格激活特效已隐藏")
            }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏网格激活特效失败: ${e.message}", e)
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
        Log.d(TAG, "网格激活特效管理器已清理")
    }
}

