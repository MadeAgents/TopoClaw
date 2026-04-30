package com.cloudcontrol.demo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Reasoning悬浮窗管理器
 * 在顶部显示reasoning字段，5秒后自动消失
 * 悬浮窗不阻挡点击事件
 */
object ReasoningOverlayManager {
    private const val TAG = "ReasoningOverlay"
    private const val AUTO_HIDE_DELAY_MS = 5000L // 5秒自动消失
    
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var hideHandler: Handler? = null
    private var hideRunnable: Runnable? = null
    
    /**
     * 初始化管理器
     */
    fun initialize(context: Context): Boolean {
        if (windowManager != null) {
            return true
        }
        
        // 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "没有悬浮窗权限，无法显示reasoning悬浮窗")
                return false
            }
        }
        
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        hideHandler = Handler(Looper.getMainLooper())
        return windowManager != null
    }
    
    /**
     * 检查端侧推理弹窗开关是否开启
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("reasoning_overlay_enabled", true) // 默认true（打开）
    }
    
    /**
     * 显示reasoning悬浮窗
     * @param context 上下文
     * @param reasoningText reasoning文本内容
     */
    fun show(context: Context, reasoningText: String) {
        // 检查开关是否开启
        if (!isEnabled(context)) {
            Log.d(TAG, "端侧推理弹窗开关未开启，不显示")
            return
        }
        
        if (reasoningText.isBlank()) {
            Log.d(TAG, "reasoning文本为空，不显示悬浮窗")
            return
        }
        
        // 确保已初始化
        if (!initialize(context)) {
            Log.w(TAG, "初始化失败，无法显示reasoning悬浮窗")
            return
        }
        
        val wm = windowManager ?: return
        
        // 如果已经显示，先隐藏旧的
        hide()
        
        try {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // 创建TextView显示reasoning
            val textView = TextView(context).apply {
                text = reasoningText
                textSize = 14f // 14sp
                setTextColor(0xFF1E293B.toInt()) // 深色文字（在浅蓝紫色背景上更清晰）
                setPadding(
                    (24 * context.resources.displayMetrics.density).toInt(), // 24dp
                    (18 * context.resources.displayMetrics.density).toInt(),   // 18dp
                    (24 * context.resources.displayMetrics.density).toInt(), // 24dp
                    (18 * context.resources.displayMetrics.density).toInt()    // 18dp
                )
                maxLines = 5 // 最多显示5行
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(4 * context.resources.displayMetrics.density, 1f) // 行间距
                
                // 现代设计感的背景：浅蓝紫色系半透明背景 + 柔和的边框
                val borderWidth = (1.5f * context.resources.displayMetrics.density).toInt()
                val cornerRadius = 24f * context.resources.displayMetrics.density
                
                // 使用浅蓝紫色系完全不透明背景
                background = android.graphics.drawable.GradientDrawable().apply {
                    // 设置渐变方向（从上到下）
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                    // 浅蓝紫色系渐变（柔和、现代、优雅，完全不透明）
                    setColors(intArrayOf(
                        0xFFE0E7FF.toInt(),  // 浅蓝紫色（lavender-50，完全不透明）
                        0xFFEDE9FE.toInt()   // 浅紫色（violet-50，完全不透明）
                    ))
                    setCornerRadius(cornerRadius)
                    // 柔和的蓝紫色边框（与背景色系一致，更有层次感）
                    setStroke(borderWidth, 0xFFC4B5FD.toInt()) // 蓝紫色边框（完全不透明）
                    alpha = 255 // 完全不透明
                }
                
                // 柔和的阴影效果（更现代）
                elevation = 8f * context.resources.displayMetrics.density
                
                // 确保View不拦截触摸事件
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                setFilterTouchesWhenObscured(false)
            }
            
            // 设置左右边距（12dp）
            val horizontalMargin = (12 * context.resources.displayMetrics.density).toInt()
            val topMargin = (1 * context.resources.displayMetrics.density).toInt() // 顶部边距1dp，更靠近顶部
            
            // 获取状态栏高度（用于计算顶部位置）
            val statusBarHeight = getStatusBarHeight(context)
            
            // 设置窗口参数
            val params = WindowManager.LayoutParams(
                screenWidth - horizontalMargin * 2, // 宽度减去左右边距（各12dp）
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                // 关键：使用多个标志确保悬浮窗不阻挡点击事件，避免系统弹窗警告
                // FLAG_NOT_TOUCHABLE: 窗口不接收触摸事件
                // FLAG_NOT_FOCUSABLE: 窗口不获取焦点
                // FLAG_NOT_TOUCH_MODAL: 触摸事件会传递给后面的窗口（即使触摸在窗口区域内）
                // FLAG_LAYOUT_IN_SCREEN: 窗口可以延伸到屏幕外
                // FLAG_HARDWARE_ACCELERATED: 硬件加速
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START // 定位在顶部
                x = horizontalMargin // 左边距
                y = statusBarHeight + topMargin // 定位在状态栏下方
            }
            
            // 添加悬浮窗
            wm.addView(textView, params)
            overlayView = textView
            isShowing = true
            
            Log.d(TAG, "Reasoning悬浮窗已显示: $reasoningText")
            
            // 设置5秒后自动隐藏
            hideRunnable = Runnable {
                hide()
            }
            hideHandler?.postDelayed(hideRunnable!!, AUTO_HIDE_DELAY_MS)
            
        } catch (e: Exception) {
            Log.e(TAG, "显示reasoning悬浮窗失败: ${e.message}", e)
        }
    }
    
    /**
     * 隐藏reasoning悬浮窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            // 取消自动隐藏的Runnable
            hideRunnable?.let {
                hideHandler?.removeCallbacks(it)
            }
            hideRunnable = null
            
            // 移除悬浮窗
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
            
            overlayView = null
            isShowing = false
            Log.d(TAG, "Reasoning悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏reasoning悬浮窗失败: ${e.message}", e)
        }
    }
    
    
    /**
     * 获取状态栏高度
     */
    private fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier(
            "status_bar_height",
            "dimen",
            "android"
        )
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        // 如果获取失败，使用默认值（通常为24dp）
        if (result == 0) {
            result = (24 * context.resources.displayMetrics.density).toInt()
        }
        return result
    }
    
    /**
     * 检查是否正在显示
     */
    fun isShowing(): Boolean {
        return isShowing
    }
}

