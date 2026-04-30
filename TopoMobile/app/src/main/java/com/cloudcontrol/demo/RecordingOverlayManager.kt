package com.cloudcontrol.demo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 录屏悬浮窗管理器
 * 管理录屏时的圆形悬浮窗显示和隐藏
 * 样式与任务执行时的悬浮窗保持一致
 */
object RecordingOverlayManager {
    private const val TAG = "RecordingOverlay"
    
    private var overlayView: TaskIndicatorOverlayView? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var context: Context? = null
    private var onStopRecordingCallback: (() -> Unit)? = null
    
    /**
     * 初始化管理器
     */
    fun initialize(context: Context): Boolean {
        if (windowManager != null) {
            Log.d(TAG, "WindowManager已初始化，直接返回true")
            return true
        }
        
        // 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermission = Settings.canDrawOverlays(context)
            Log.d(TAG, "检查悬浮窗权限: $hasPermission")
            if (!hasPermission) {
                Log.w(TAG, "没有悬浮窗权限，无法显示录屏指示器。请在设置中授予悬浮窗权限")
                return false
            }
        }
        
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val success = windowManager != null
        Log.d(TAG, "WindowManager初始化: $success")
        return success
    }
    
    /**
     * 显示录屏指示器悬浮窗
     * @param context 上下文
     * @param onStopRecording 点击悬浮窗时的回调（结束录制）
     */
    fun show(context: Context, onStopRecording: () -> Unit) {
        Log.d(TAG, "尝试显示录屏悬浮窗，isShowing=$isShowing")
        
        if (isShowing) {
            Log.w(TAG, "悬浮窗已显示，跳过重复显示")
            return
        }
        
        if (!initialize(context)) {
            Log.w(TAG, "初始化失败，无法显示录屏指示器（可能缺少悬浮窗权限）")
            return
        }
        
        Log.d(TAG, "初始化成功，准备创建悬浮窗")
        
        val wm = windowManager ?: return
        this.context = context
        this.onStopRecordingCallback = onStopRecording
        
        try {
            // 创建悬浮窗View（复用TaskIndicatorOverlayView，样式一致）
            overlayView = TaskIndicatorOverlayView(context).apply {
                // 设置点击事件：点击后结束录制
                setOnClickListener {
                    try {
                        Log.d(TAG, "点击录屏悬浮窗，结束录制")
                        onStopRecordingCallback?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "点击悬浮窗结束录制失败: ${e.message}", e)
                    }
                }
            }
            
            // 设置WindowManager参数（与TaskIndicatorOverlayManager保持一致）
            val displayMetrics = context.resources.displayMetrics
            val params = WindowManager.LayoutParams().apply {
                width = (45 * displayMetrics.density).toInt() // 45dp
                height = (45 * displayMetrics.density).toInt() // 45dp
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                gravity = Gravity.END or Gravity.CENTER_VERTICAL // 右侧中间
                x = (16 * displayMetrics.density).toInt() // 距离右边缘16dp
                y = 0 // 垂直居中，不需要偏移
            }
            
            // 添加到WindowManager
            try {
                wm.addView(overlayView, params)
                Log.d(TAG, "悬浮窗已添加到WindowManager，位置: x=${params.x}, y=${params.y}, width=${params.width}, height=${params.height}")
            } catch (e: Exception) {
                Log.e(TAG, "添加悬浮窗到WindowManager失败: ${e.message}", e)
                throw e
            }
            
            // 开始动画
            overlayView?.startAnimation()
            
            isShowing = true
            Log.d(TAG, "录屏指示器悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示录屏指示器失败: ${e.message}", e)
            overlayView = null
        }
    }
    
    /**
     * 隐藏录屏指示器悬浮窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        val wm = windowManager ?: return
        val view = overlayView ?: return
        
        try {
            // 停止动画
            view.stopAnimation()
            
            // 从WindowManager移除
            wm.removeView(view)
            
            overlayView = null
            isShowing = false
            onStopRecordingCallback = null
            Log.d(TAG, "录屏指示器悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏录屏指示器失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
        windowManager = null
        context = null
    }
}

