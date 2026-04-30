package com.cloudcontrol.demo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import android.view.View

/**
 * 优雅边框特效View
 * 在屏幕四边绘制蓝紫色渐变边框，支持流动动画效果和渐变透明
 */
class BorderEffectView(context: Context) : View(context) {
    
    companion object {
        private const val TAG = "BorderEffectView"
        private const val BORDER_WIDTH_DP = 10f  // 边框宽度（dp）
        private const val ANIMATION_DURATION = 3000L  // 动画持续时间（3秒一圈）
        private const val GRADIENT_LAYERS = 5  // 渐变层数，越多越平滑
    }
    
    private val borderWidth: Float = BORDER_WIDTH_DP * resources.displayMetrics.density
    
    // 画笔数组（用于多层渐变，从外到内逐渐透明）
    private val borderPaints = Array(GRADIENT_LAYERS) { index ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // 外层（index=0）最不透明，内层（index越大）逐渐透明
            // 使用平方函数使渐变更自然
            val progress = index.toFloat() / (GRADIENT_LAYERS - 1)
            val alpha = (255 * (1f - progress * progress)).toInt().coerceIn(0, 255)
            this.alpha = alpha
        }
    }
    
    // 优雅的蓝紫色渐变（低调且有设计感）
    private val elegantColors = intArrayOf(
        Color.parseColor("#6366F1"),  // 靛蓝色
        Color.parseColor("#8B5CF6"),  // 紫色
        Color.parseColor("#A855F7"),  // 深紫色
        Color.parseColor("#6366F1"),  // 回到靛蓝色（闭合循环）
        Color.parseColor("#8B5CF6")   // 紫色
    )
    
    // 动画相关
    private var animator: ValueAnimator? = null
    private var gradientOffset = 0f  // 渐变偏移量（0-1循环）
    
    // 渐变着色器（每条边一个，动态更新）
    private var topGradient: LinearGradient? = null
    private var rightGradient: LinearGradient? = null
    private var bottomGradient: LinearGradient? = null
    private var leftGradient: LinearGradient? = null
    
    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0
    
    init {
        // 设置View为透明背景
        setBackgroundColor(Color.TRANSPARENT)
        
        // 确保View不拦截任何触摸事件
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        setFilterTouchesWhenObscured(false)
    }
    
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        // 不消费任何触摸事件，让事件穿透到底层
        return false
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenWidth = w
        screenHeight = h
        startAnimation()
    }
    
    /**
     * 更新渐变着色器（根据动画偏移量动态更新）
     */
    private fun updateGradients() {
        val halfBorder = borderWidth / 2
        val width = screenWidth.toFloat()
        val height = screenHeight.toFloat()
        
        // 计算每条边的长度
        val topLength = width - borderWidth
        val rightLength = height - borderWidth
        val bottomLength = width - borderWidth
        val leftLength = height - borderWidth
        
        // 总长度（用于计算循环偏移）
        val totalLength = topLength + rightLength + bottomLength + leftLength
        
        // 当前偏移量（像素）
        var currentOffset = gradientOffset * totalLength
        
        // 上边：从左到右
        val topStart = halfBorder + (currentOffset % topLength)
        val topEnd = topStart + topLength
        topGradient = LinearGradient(
            topStart, halfBorder,
            topEnd, halfBorder,
            elegantColors,
            null,
            Shader.TileMode.REPEAT
        )
        currentOffset -= topLength
        
        // 右边：从上到下
        if (currentOffset > 0) {
            val rightStart = halfBorder + (currentOffset % rightLength)
            val rightEnd = rightStart + rightLength
            rightGradient = LinearGradient(
                width - halfBorder, rightStart,
                width - halfBorder, rightEnd,
                elegantColors,
                null,
                Shader.TileMode.REPEAT
            )
            currentOffset -= rightLength
        } else {
            rightGradient = LinearGradient(
                width - halfBorder, halfBorder,
                width - halfBorder, height - halfBorder,
                elegantColors,
                null,
                Shader.TileMode.REPEAT
            )
        }
        
        // 下边：从右到左
        if (currentOffset > 0) {
            val bottomStart = width - halfBorder - (currentOffset % bottomLength)
            val bottomEnd = bottomStart - bottomLength
            bottomGradient = LinearGradient(
                bottomStart, height - halfBorder,
                bottomEnd, height - halfBorder,
                elegantColors,
                null,
                Shader.TileMode.REPEAT
            )
            currentOffset -= bottomLength
        } else {
            bottomGradient = LinearGradient(
                width - halfBorder, height - halfBorder,
                halfBorder, height - halfBorder,
                elegantColors,
                null,
                Shader.TileMode.REPEAT
            )
        }
        
        // 左边：从下到上
        if (currentOffset > 0) {
            val leftStart = height - halfBorder - (currentOffset % leftLength)
            val leftEnd = leftStart - leftLength
            leftGradient = LinearGradient(
                halfBorder, leftStart,
                halfBorder, leftEnd,
                elegantColors,
                null,
                Shader.TileMode.REPEAT
            )
        } else {
            leftGradient = LinearGradient(
                halfBorder, height - halfBorder,
                halfBorder, halfBorder,
                elegantColors,
                null,
                Shader.TileMode.REPEAT
            )
        }
    }
    
    /**
     * 启动动画
     */
    private fun startAnimation() {
        stopAnimation()
        
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animation ->
                gradientOffset = animation.animatedValue as Float
                updateGradients()  // 更新渐变
                invalidate()  // 触发重绘
            }
        }
        animator?.start()
    }
    
    /**
     * 停止动画
     */
    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // 绘制多层渐变边框，从外到内逐渐透明
        // 外层（layer=0）最不透明，内层（layer越大）越透明
        for (layer in 0 until GRADIENT_LAYERS) {
            // 计算当前层的偏移量（从边缘向内）
            val layerOffset = layer * (borderWidth / GRADIENT_LAYERS)
            val layerWidth = borderWidth / GRADIENT_LAYERS
            
            borderPaints[layer].strokeWidth = layerWidth
            
            // 绘制上边
            topGradient?.let {
                borderPaints[layer].shader = it
                canvas.drawLine(
                    layerOffset, layerOffset,
                    width - layerOffset, layerOffset,
                    borderPaints[layer]
                )
            }
            
            // 绘制右边
            rightGradient?.let {
                borderPaints[layer].shader = it
                canvas.drawLine(
                    width - layerOffset, layerOffset,
                    width - layerOffset, height - layerOffset,
                    borderPaints[layer]
                )
            }
            
            // 绘制下边
            bottomGradient?.let {
                borderPaints[layer].shader = it
                canvas.drawLine(
                    width - layerOffset, height - layerOffset,
                    layerOffset, height - layerOffset,
                    borderPaints[layer]
                )
            }
            
            // 绘制左边
            leftGradient?.let {
                borderPaints[layer].shader = it
                canvas.drawLine(
                    layerOffset, height - layerOffset,
                    layerOffset, layerOffset,
                    borderPaints[layer]
                )
            }
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopAnimation()
        topGradient = null
        rightGradient = null
        bottomGradient = null
        leftGradient = null
    }
}

