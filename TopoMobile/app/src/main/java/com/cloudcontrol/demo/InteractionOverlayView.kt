package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.*

/**
 * 交互点可视化覆盖层
 * 用于在屏幕上显示点击、长按等交互点的水波纹效果
 */
class InteractionOverlayView(context: Context) : View(context) {
    
    companion object {
        private const val TAG = "InteractionOverlay"
        private const val RIPPLE_DURATION = 600L // 水波纹动画持续时间（毫秒）
        private const val MAX_RIPPLE_RADIUS = 150f // 最大波纹半径
        // 注意：不能使用 const val 因为 Color.parseColor() 不是编译时常量
        private val RIPPLE_COLOR = Color.parseColor("#4A90E2") // 波纹颜色（蓝色）
        private val CENTER_COLOR = Color.parseColor("#FFFFFF") // 中心点颜色（白色）
    }
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = RIPPLE_COLOR
    }
    
    private var interactionX = 0f
    private var interactionY = 0f
    private var rippleRadius = 0f
    private var isAnimating = false
    private var animationJob: Job? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "覆盖层尺寸变化: ${w}x${h}")
    }
    
    /**
     * 显示点击效果
     */
    fun showClickEffect(x: Int, y: Int) {
        Log.d(TAG, "显示点击效果: ($x, $y)")
        showInteractionEffect(x, y, InteractionType.CLICK)
    }
    
    /**
     * 显示长按效果
     */
    fun showLongPressEffect(x: Int, y: Int) {
        Log.d(TAG, "显示长按效果: ($x, $y)")
        showInteractionEffect(x, y, InteractionType.LONG_PRESS)
    }
    
    /**
     * 显示输入效果（在输入框位置）
     */
    fun showInputEffect(x: Int, y: Int) {
        Log.d(TAG, "显示输入效果: ($x, $y)")
        showInteractionEffect(x, y, InteractionType.INPUT)
    }
    
    private enum class InteractionType {
        CLICK, LONG_PRESS, INPUT
    }
    
    private fun showInteractionEffect(x: Int, y: Int, type: InteractionType) {
        // 取消之前的动画
        animationJob?.cancel()
        
        // 获取实际屏幕尺寸（使用getRealMetrics获取包括状态栏和导航栏的完整尺寸）
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val displayMetrics = android.util.DisplayMetrics()
        val display = windowManager?.defaultDisplay
        if (display != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealMetrics(displayMetrics)
            } else {
                @Suppress("DEPRECATION")
                display.getMetrics(displayMetrics)
            }
        }
        
        // 坐标直接使用屏幕坐标，覆盖层是全屏的，不需要调整
        // 但为了调试，记录一下坐标
        Log.d(TAG, "showInteractionEffect: 原始坐标=($x, $y), 实际屏幕尺寸=${displayMetrics.widthPixels}x${displayMetrics.heightPixels}, 显示尺寸=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
        
        interactionX = x.toFloat()
        interactionY = y.toFloat()
        rippleRadius = 0f
        isAnimating = true
        
        // 根据类型设置不同的颜色
        when (type) {
            InteractionType.CLICK -> {
                ripplePaint.color = Color.parseColor("#4A90E2") // 蓝色
            }
            InteractionType.LONG_PRESS -> {
                ripplePaint.color = Color.parseColor("#FF6B6B") // 红色
            }
            InteractionType.INPUT -> {
                ripplePaint.color = Color.parseColor("#51CF66") // 绿色
            }
        }
        
        // 立即显示中心点
        post { invalidate() }
        
        // 启动动画
        animationJob = scope.launch(Dispatchers.Main) {
            val startTime = System.currentTimeMillis()
            val duration = RIPPLE_DURATION
            
            while (isAnimating) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= duration) {
                    isAnimating = false
                    rippleRadius = 0f
                    post { invalidate() }
                    break
                }
                
                // 计算当前波纹半径（使用缓动函数）
                val progress = elapsed.toFloat() / duration
                rippleRadius = MAX_RIPPLE_RADIUS * progress
                
                // 强制在主线程刷新
                post { invalidate() }
                delay(16) // 约60fps
            }
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isAnimating) {
            return
        }
        
        // 调试：记录绘制时的坐标和View尺寸（只在首次绘制时记录一次）
        if (rippleRadius == 0f) {
            // 获取实际屏幕尺寸
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val displayMetrics = android.util.DisplayMetrics()
            val display = windowManager?.defaultDisplay
            if (display != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    display.getRealMetrics(displayMetrics)
                } else {
                    @Suppress("DEPRECATION")
                    display.getMetrics(displayMetrics)
                }
            }
            Log.d(TAG, "onDraw: 绘制坐标=($interactionX, $interactionY), View尺寸=${width}x${height}, 实际屏幕=${displayMetrics.widthPixels}x${displayMetrics.heightPixels}, 显示尺寸=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}")
        }
        
        // 绘制中心点（始终显示）
        paint.color = CENTER_COLOR
        paint.alpha = 255
        canvas.drawCircle(interactionX, interactionY, 10f, paint)
        
        // 绘制外圈（更明显）
        paint.color = ripplePaint.color
        paint.alpha = 200
        canvas.drawCircle(interactionX, interactionY, 15f, paint)
        
        // 绘制水波纹（多个同心圆）
        if (rippleRadius > 0) {
            // 第一个波纹
            val alpha1 = (255 * (1 - rippleRadius / MAX_RIPPLE_RADIUS)).toInt().coerceIn(0, 255)
            ripplePaint.alpha = alpha1
            canvas.drawCircle(interactionX, interactionY, rippleRadius, ripplePaint)
            
            // 第二个波纹（延迟一些）
            if (rippleRadius > 40) {
                val secondRadius = rippleRadius - 40
                val alpha2 = (255 * (1 - secondRadius / MAX_RIPPLE_RADIUS)).toInt().coerceIn(0, 255)
                ripplePaint.alpha = alpha2
                canvas.drawCircle(interactionX, interactionY, secondRadius, ripplePaint)
            }
            
            // 第三个波纹（再延迟一些）
            if (rippleRadius > 80) {
                val thirdRadius = rippleRadius - 80
                val alpha3 = (255 * (1 - thirdRadius / MAX_RIPPLE_RADIUS)).toInt().coerceIn(0, 255)
                ripplePaint.alpha = alpha3
                canvas.drawCircle(interactionX, interactionY, thirdRadius, ripplePaint)
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        animationJob?.cancel()
        scope.cancel()
    }
}

/**
 * 交互覆盖层管理器
 * 负责创建和管理覆盖层窗口
 */
object InteractionOverlayManager {
    private const val TAG = "InteractionOverlay"
    private var overlayView: InteractionOverlayView? = null
    private var windowManager: WindowManager? = null
    
    /**
     * 初始化覆盖层
     */
    fun initialize(context: Context) {
        if (overlayView != null) {
            Log.d(TAG, "覆盖层已存在，跳过初始化")
            return // 已经初始化
        }
        
        try {
            // 检查权限（Android 6.0+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "没有悬浮窗权限，无法显示交互效果。请在设置中授权'显示在其他应用的上层'权限")
                    return
                }
            }
            
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            overlayView = InteractionOverlayView(context)
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                // 明确设置覆盖层从屏幕的 (0,0) 开始，与 AccessibilityService 的坐标系统一致
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
            }
            
            windowManager?.addView(overlayView, params)
            
            // 获取实际屏幕尺寸用于调试
            val displayMetrics = android.util.DisplayMetrics()
            val display = windowManager?.defaultDisplay
            if (display != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    display.getRealMetrics(displayMetrics)
                } else {
                    @Suppress("DEPRECATION")
                    display.getMetrics(displayMetrics)
                }
                Log.d(TAG, "交互覆盖层已初始化并添加到窗口，实际屏幕尺寸=${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "初始化交互覆盖层失败：缺少悬浮窗权限。请在设置中授权'显示在其他应用的上层'权限", e)
        } catch (e: Exception) {
            Log.e(TAG, "初始化交互覆盖层失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 显示点击效果
     */
    fun showClickEffect(x: Int, y: Int) {
        if (overlayView == null) {
            Log.w(TAG, "覆盖层未初始化，无法显示点击效果。请先授权悬浮窗权限")
            return
        }
        Log.d(TAG, "准备显示点击效果: ($x, $y), overlayView=${overlayView != null}")
        overlayView?.showClickEffect(x, y)
    }
    
    /**
     * 显示长按效果
     */
    fun showLongPressEffect(x: Int, y: Int) {
        if (overlayView == null) {
            Log.w(TAG, "覆盖层未初始化，无法显示长按效果。请先授权悬浮窗权限")
            return
        }
        Log.d(TAG, "准备显示长按效果: ($x, $y), overlayView=${overlayView != null}")
        overlayView?.showLongPressEffect(x, y)
    }
    
    /**
     * 显示输入效果
     */
    fun showInputEffect(x: Int, y: Int) {
        if (overlayView == null) {
            Log.w(TAG, "覆盖层未初始化，无法显示输入效果。请先授权悬浮窗权限")
            return
        }
        Log.d(TAG, "准备显示输入效果: ($x, $y), overlayView=${overlayView != null}")
        overlayView?.showInputEffect(x, y)
    }
    
    /**
     * 清理覆盖层
     */
    fun cleanup() {
        try {
            overlayView?.cleanup()
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
            windowManager = null
            Log.d(TAG, "交互覆盖层已清理")
        } catch (e: Exception) {
            Log.e(TAG, "清理交互覆盖层失败: ${e.message}", e)
        }
    }
}

