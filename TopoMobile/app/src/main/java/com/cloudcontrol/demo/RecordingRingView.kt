package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlinx.coroutines.*

/**
 * 录音转圈特效View
 * 参考TaskIndicatorOverlayView的实现
 */
class RecordingRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2196F3.toInt() // 蓝色圆弧，与录音按钮颜色一致
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    
    private var isAnimating = false
    private var animationJob: Job? = null
    private var rotationAngle = 0f
    private val animationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * 启动动画
     */
    fun startAnimation() {
        if (isAnimating) {
            return
        }
        isAnimating = true
        visibility = View.VISIBLE
        rotationAngle = 0f
        animationJob = animationScope.launch {
            try {
                while (isAnimating) {
                    rotationAngle += 10f
                    if (rotationAngle >= 360f) {
                        rotationAngle = 0f
                    }
                    post { invalidate() }
                    delay(30) // 30ms更新一次，约33fps
                }
            } catch (e: CancellationException) {
                // 正常取消，不需要处理
            } catch (e: Exception) {
                android.util.Log.e("RecordingRingView", "动画异常: ${e.message}", e)
            } finally {
                isAnimating = false
            }
        }
    }
    
    /**
     * 停止动画
     */
    fun stopAnimation() {
        if (!isAnimating && animationJob == null) {
            return
        }
        isAnimating = false
        animationJob?.cancel()
        animationJob = null
        rotationAngle = 0f
        visibility = View.GONE
        post { invalidate() }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isAnimating) {
            return
        }
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f * 0.85f // 留一点边距
        
        // 绘制旋转的圆弧（转圈效果）
        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        // 绘制一个270度的圆弧，留90度空白，形成转圈效果
        val rect = android.graphics.RectF(
            centerX - radius * 0.7f,
            centerY - radius * 0.7f,
            centerX + radius * 0.7f,
            centerY + radius * 0.7f
        )
        canvas.drawArc(rect, 0f, 270f, false, arcPaint)
        canvas.restore()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
        animationScope.cancel()
    }
}

