package com.cloudcontrol.demo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * 全息投影扫描特效View
 * 在屏幕上绘制全息网格线，从下往上扫描激活，带有光晕、抖动和速度差异效果
 */
class GridActivationEffectView(context: Context) : View(context) {
    
    companion object {
        private const val TAG = "GridActivationEffect"
        private const val GRID_SPACING_DP = 2f  // 网格间距（dp），极小的间距使网格极其密集
        private const val GRID_LINE_WIDTH_DP = 1f  // 网格线宽度（dp），更细的线条
        private const val ANIMATION_DURATION = 600L  // 动画持续时间（毫秒），更快的动画使效果更高频
        private const val ACTIVATION_HEIGHT_RATIO = 0.25f  // 激活区域高度比例（屏幕高度的25%），更小的激活区域使扫描更快
        private const val GLOW_LAYERS = 3  // 光晕层数
        private const val GLOW_SPREAD_DP = 3f  // 光晕扩散范围（dp）
        private const val JITTER_AMOUNT_DP = 0.5f  // 抖动幅度（dp）
    }
    
    private val gridSpacing: Float = GRID_SPACING_DP * resources.displayMetrics.density
    private val gridLineWidth: Float = GRID_LINE_WIDTH_DP * resources.displayMetrics.density
    private val glowSpread: Float = GLOW_SPREAD_DP * resources.displayMetrics.density
    private val jitterAmount: Float = JITTER_AMOUNT_DP * resources.displayMetrics.density
    
    // 网格线画笔（主线条）
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = gridLineWidth
        strokeCap = Paint.Cap.ROUND
    }
    
    // 光晕画笔数组（多层光晕，从内到外逐渐透明）
    private val glowPaints = Array(GLOW_LAYERS) { index ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            // 外层光晕更透明
            val alpha = (255 * (1f - index.toFloat() / GLOW_LAYERS)).toInt()
            this.alpha = alpha
        }
    }
    
    // 全息投影风格的颜色渐变（蓝紫色系，带有青色和白色高光）
    private val holographicColors = intArrayOf(
        Color.parseColor("#00FFFF"),  // 青色（全息投影的经典颜色）
        Color.parseColor("#6366F1"),  // 靛蓝色
        Color.parseColor("#8B5CF6"),  // 紫色
        Color.parseColor("#A855F7"),  // 深紫色
        Color.parseColor("#FFFFFF"),  // 白色高光
        Color.parseColor("#00FFFF"),  // 回到青色
        Color.parseColor("#6366F1")   // 回到靛蓝色
    )
    
    // 速度偏移映射（每条线的速度差异）
    private var horizontalLineSpeedOffsets = mutableMapOf<Float, Float>()
    private var verticalLineSpeedOffsets = mutableMapOf<Float, Float>()
    
    // 抖动偏移映射（每条线的抖动）
    private var horizontalLineJitters = mutableMapOf<Float, Float>()
    private var verticalLineJitters = mutableMapOf<Float, Float>()
    
    // 动画相关
    private var animator: ValueAnimator? = null
    private var activationProgress = 0f  // 激活进度（0-1）
    
    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0
    
    // 网格线数组（预计算，避免重复计算）
    private var horizontalLines = mutableListOf<Float>()
    private var verticalLines = mutableListOf<Float>()
    
    // 动画完成回调
    var onAnimationCompleteCallback: (() -> Unit)? = null
    
    init {
        // 设置View为透明背景
        setBackgroundColor(Color.TRANSPARENT)
        
        // 确保View不拦截任何触摸事件
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setFilterTouchesWhenObscured(false)
        
        // 获取屏幕实际尺寸
        updateScreenSize()
    }
    
    /**
     * 获取屏幕实际尺寸（包括系统装饰区域如导航栏）
     */
    private fun updateScreenSize() {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val displayMetrics = DisplayMetrics()
            val display = windowManager?.defaultDisplay
            if (display != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    display.getRealMetrics(displayMetrics)
                } else {
                    @Suppress("DEPRECATION")
                    display.getMetrics(displayMetrics)
                }
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
                Log.d(TAG, "获取屏幕实际尺寸: ${screenWidth}x${screenHeight}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕尺寸失败: ${e.message}", e)
            screenWidth = width
            screenHeight = height
        }
    }
    
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        // 不消费任何触摸事件，让事件穿透到底层
        return false
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScreenSize()
        
        // 如果获取失败，使用 View 的尺寸作为备选
        if (screenWidth == 0 || screenHeight == 0) {
            screenWidth = w
            screenHeight = h
            Log.d(TAG, "使用View尺寸作为备选: ${screenWidth}x${screenHeight}")
        }
        
        // 预计算网格线位置
        calculateGridLines()
        
        // 如果已经设置了启动标志，在尺寸确定后自动启动动画
        if (shouldStartOnReady) {
            shouldStartOnReady = false
            post {
                startActivation()
            }
        }
    }
    
    // 标记是否应该在尺寸准备好后启动动画
    private var shouldStartOnReady = false
    
    /**
     * 预计算网格线位置、速度偏移和抖动
     */
    private fun calculateGridLines() {
        horizontalLines.clear()
        verticalLines.clear()
        horizontalLineSpeedOffsets.clear()
        verticalLineSpeedOffsets.clear()
        horizontalLineJitters.clear()
        verticalLineJitters.clear()
        
        val width = screenWidth.toFloat()
        val height = screenHeight.toFloat()
        
        // 使用随机种子确保每次启动时随机性一致
        val random = java.util.Random(System.currentTimeMillis())
        
        // 计算水平线（从下往上）
        var y = height
        while (y >= 0) {
            horizontalLines.add(y)
            // 为每条线添加速度偏移（-0.15到0.15的随机偏移）
            horizontalLineSpeedOffsets[y] = (random.nextFloat() - 0.5f) * 0.3f
            // 为每条线添加抖动偏移（-jitterAmount到jitterAmount）
            horizontalLineJitters[y] = (random.nextFloat() - 0.5f) * 2f * jitterAmount
            y -= gridSpacing
        }
        
        // 计算垂直线
        var x = 0f
        while (x <= width) {
            verticalLines.add(x)
            // 为每条线添加速度偏移
            verticalLineSpeedOffsets[x] = (random.nextFloat() - 0.5f) * 0.3f
            // 为每条线添加抖动偏移
            verticalLineJitters[x] = (random.nextFloat() - 0.5f) * 2f * jitterAmount
            x += gridSpacing
        }
        
        Log.d(TAG, "网格线计算完成: ${horizontalLines.size}条水平线, ${verticalLines.size}条垂直线")
    }
    
    /**
     * 启动激活动画
     */
    fun startActivation() {
        // 检查网格线是否已计算
        if (horizontalLines.isEmpty() || verticalLines.isEmpty()) {
            Log.w(TAG, "网格线未计算，延迟启动动画")
            shouldStartOnReady = true
            // 如果尺寸已确定，立即计算网格线
            if (screenWidth > 0 && screenHeight > 0) {
                calculateGridLines()
            }
            return
        }
        
        Log.d(TAG, "启动网格激活动画，网格线数量: ${horizontalLines.size}条水平线, ${verticalLines.size}条垂直线")
        stopAnimation()
        
        activationProgress = 0f
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                activationProgress = animation.animatedValue as Float
                invalidate()  // 触发重绘
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    Log.d(TAG, "网格激活动画结束")
                    // 动画结束后，通过回调通知管理器隐藏特效
                    onAnimationCompleteCallback?.invoke()
                }
            })
        }
        animator?.start()
        Log.d(TAG, "网格激活动画已启动")
    }
    
    /**
     * 停止动画
     */
    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }
    
    /**
     * 计算网格线在当前激活进度下的透明度和强度（带速度偏移）
     * @param lineY 网格线的Y坐标
     * @param speedOffset 速度偏移（正值=更快，负值=更慢）
     * @return Pair<透明度, 强度> 强度用于控制光晕效果
     */
    private fun calculateLineAlphaAndIntensity(lineY: Float, speedOffset: Float = 0f): Pair<Int, Float> {
        val height = screenHeight.toFloat()
        
        // 应用速度偏移：正值让进度更快（提前），负值让进度更慢（延迟）
        val adjustedProgress = (activationProgress + speedOffset).coerceIn(0f, 1f)
        
        // 激活区域从底部开始，向上移动（保持从下到上的基本方向）
        val activationStartY = height * (1f - adjustedProgress)
        val activationEndY = activationStartY - height * ACTIVATION_HEIGHT_RATIO
        
        // 如果网格线在激活区域内，计算其透明度和强度
        if (lineY <= activationStartY && lineY >= activationEndY) {
            // 在激活区域内，根据位置计算透明度（激活区域中心最亮）
            val relativePos = (lineY - activationEndY) / (activationStartY - activationEndY)
            // 使用正弦函数使渐变更自然
            val alphaProgress = kotlin.math.sin(relativePos * kotlin.math.PI).toFloat()
            // 提高亮度：增大最小和最大透明度
            val minAlpha = 180  // 提高最小透明度，使网格线更亮
            val maxAlpha = 255
            val alpha = (minAlpha + (maxAlpha - minAlpha) * alphaProgress * adjustedProgress).toInt()
            val intensity = alphaProgress * adjustedProgress  // 强度用于光晕效果
            return Pair(alpha.coerceIn(minAlpha, maxAlpha), intensity)
        } else if (lineY < activationEndY) {
            // 已经激活过的区域，逐渐淡出（保持较高亮度）
            val fadeOutProgress = (activationEndY - lineY) / (height * 0.2f)
            val alpha = (255 * (1f - fadeOutProgress.coerceIn(0f, 1f)) * adjustedProgress).toInt()
            val intensity = (1f - fadeOutProgress.coerceIn(0f, 1f)) * adjustedProgress
            return Pair(alpha.coerceIn(120, 255), intensity)
        }
        
        // 未激活区域，完全透明
        return Pair(0, 0f)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = if (screenWidth > 0) screenWidth.toFloat() else width.toFloat()
        val height = if (screenHeight > 0) screenHeight.toFloat() else height.toFloat()
        
        // 如果进度为0，不绘制
        if (activationProgress <= 0f) {
            return
        }
        
        // 如果网格线未计算，不绘制
        if (horizontalLines.isEmpty() || verticalLines.isEmpty()) {
            Log.w(TAG, "onDraw: 网格线未计算，跳过绘制")
            return
        }
        
        // 绘制水平网格线（全息投影扫描效果）
        var drawnHorizontalCount = 0
        for (y in horizontalLines) {
            val speedOffset = horizontalLineSpeedOffsets[y] ?: 0f
            val jitter = horizontalLineJitters[y] ?: 0f
            val (alpha, intensity) = calculateLineAlphaAndIntensity(y, speedOffset)
            
            if (alpha > 0 && intensity > 0) {
                // 应用抖动偏移
                val jitteredY = y + jitter * intensity
                
                // 创建全息投影渐变着色器（从左到右）
                val gradient = LinearGradient(
                    0f, jitteredY,
                    width, jitteredY,
                    holographicColors,
                    null,
                    Shader.TileMode.REPEAT
                )
                
                // 绘制光晕（多层，从外到内）
                for (layer in GLOW_LAYERS - 1 downTo 0) {
                    val glowWidth = gridLineWidth + glowSpread * (layer + 1) * intensity
                    glowPaints[layer].strokeWidth = glowWidth
                    glowPaints[layer].shader = gradient
                    glowPaints[layer].alpha = (alpha * (1f - layer.toFloat() / GLOW_LAYERS) * 0.5f).toInt()
                    canvas.drawLine(0f, jitteredY, width, jitteredY, glowPaints[layer])
                }
                
                // 绘制主线条
                gridPaint.shader = gradient
                gridPaint.alpha = alpha
                canvas.drawLine(0f, jitteredY, width, jitteredY, gridPaint)
                drawnHorizontalCount++
            }
        }
        
        // 绘制垂直网格线（全息投影扫描效果）
        var drawnVerticalCount = 0
        for (x in verticalLines) {
            val speedOffset = verticalLineSpeedOffsets[x] ?: 0f
            val jitter = verticalLineJitters[x] ?: 0f
            // 垂直线使用屏幕中部的Y坐标计算激活进度
            val midY = height * 0.5f
            val adjustedProgress = (activationProgress + speedOffset).coerceIn(0f, 1f)
            val activationStartY = height * (1f - adjustedProgress)
            val activationEndY = activationStartY - height * ACTIVATION_HEIGHT_RATIO
            
            val (alpha, intensity) = if (midY <= activationStartY && midY >= activationEndY) {
                val relativePos = (midY - activationEndY) / (activationStartY - activationEndY)
                val alphaProgress = kotlin.math.sin(relativePos * kotlin.math.PI).toFloat()
                val alpha = (180 + (255 - 180) * alphaProgress * adjustedProgress).toInt()
                Pair(alpha.coerceIn(180, 255), alphaProgress * adjustedProgress)
            } else if (midY < activationEndY) {
                val fadeOutProgress = (activationEndY - midY) / (height * 0.2f)
                val alpha = (255 * (1f - fadeOutProgress.coerceIn(0f, 1f)) * adjustedProgress).toInt()
                Pair(alpha.coerceIn(120, 255), (1f - fadeOutProgress.coerceIn(0f, 1f)) * adjustedProgress)
            } else {
                Pair(0, 0f)
            }
            
            if (alpha > 0 && intensity > 0) {
                // 应用抖动偏移
                val jitteredX = x + jitter * intensity
                
                // 创建全息投影渐变着色器（从上到下）
                val gradient = LinearGradient(
                    jitteredX, 0f,
                    jitteredX, height,
                    holographicColors,
                    null,
                    Shader.TileMode.REPEAT
                )
                
                // 绘制光晕（多层，从外到内）
                for (layer in GLOW_LAYERS - 1 downTo 0) {
                    val glowWidth = gridLineWidth + glowSpread * (layer + 1) * intensity
                    glowPaints[layer].strokeWidth = glowWidth
                    glowPaints[layer].shader = gradient
                    glowPaints[layer].alpha = (alpha * (1f - layer.toFloat() / GLOW_LAYERS) * 0.5f).toInt()
                    canvas.drawLine(jitteredX, 0f, jitteredX, height, glowPaints[layer])
                }
                
                // 绘制主线条
                gridPaint.shader = gradient
                gridPaint.alpha = alpha
                canvas.drawLine(jitteredX, 0f, jitteredX, height, gridPaint)
                drawnVerticalCount++
            }
        }
        
        // 调试日志（只在第一次绘制时输出）
        if (activationProgress > 0.01f && activationProgress < 0.02f) {
            Log.d(TAG, "onDraw: 绘制了 $drawnHorizontalCount 条水平线, $drawnVerticalCount 条垂直线, 进度=$activationProgress")
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopAnimation()
        horizontalLines.clear()
        verticalLines.clear()
        horizontalLineSpeedOffsets.clear()
        verticalLineSpeedOffsets.clear()
        horizontalLineJitters.clear()
        verticalLineJitters.clear()
    }
}

