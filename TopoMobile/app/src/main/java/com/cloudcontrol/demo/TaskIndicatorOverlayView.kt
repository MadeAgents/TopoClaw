package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlinx.coroutines.*

/**
 * 任务运行指示器悬浮窗View
 * 显示一个圆形的小弹窗，表示任务正在运行
 */
class TaskIndicatorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val indicatorColor = 0x60E5FCFF.toInt() // 浅蓝色，更透明（约38%不透明度）
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = indicatorColor
        style = Paint.Style.FILL
    }
    
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9C27B0.toInt() // 紫色圆弧
        style = Paint.Style.STROKE
        strokeWidth = 6f // 加粗（从4f改为6f）
        strokeCap = Paint.Cap.ROUND
    }
    
    private var appIcon: Bitmap? = null
    private var isAnimating = false
    private var animationJob: Job? = null
    private var rotationAngle = 0f
    private var animationScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var showArc = true // 是否显示转圈圈效果（伴随模式下不显示）
    
    // 拖拽相关变量
    private var onPositionUpdateListener: ((Float, Float) -> Unit)? = null
    private var onDragStartListener: (() -> Unit)? = null // 拖拽开始监听器
    private var onDragEndListener: (() -> Unit)? = null
    private var onDragEndAtTopListener: (() -> Unit)? = null
    private var onDragEndAtBottomListener: (() -> Unit)? = null
    private var onLongClickListener: (() -> Unit)? = null
    private var onDoubleClickListener: (() -> Unit)? = null
    private var onClickListener: (() -> Unit)? = null // 直接的单机点击回调
    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    
    // 双击检测相关变量
    private var lastClickTime = 0L
    private var clickCount = 0
    private val doubleClickDelay = 300L // 300ms内两次点击视为双击
    private var pendingClickRunnable: Runnable? = null // 待执行的点击回调
    
    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        loadAppIcon()
    }
    
    /**
     * 设置位置更新监听器
     */
    fun setOnPositionUpdateListener(listener: (Float, Float) -> Unit) {
        onPositionUpdateListener = listener
    }
    
    /**
     * 设置拖拽开始监听器
     */
    fun setOnDragStartListener(listener: () -> Unit) {
        onDragStartListener = listener
    }
    
    /**
     * 设置拖拽结束监听器
     */
    fun setOnDragEndListener(listener: () -> Unit) {
        onDragEndListener = listener
    }
    
    /**
     * 设置拖拽结束在顶部时的监听器
     */
    fun setOnDragEndAtTopListener(listener: () -> Unit) {
        onDragEndAtTopListener = listener
    }
    
    /**
     * 设置拖拽结束在底部时的监听器
     */
    fun setOnDragEndAtBottomListener(listener: () -> Unit) {
        onDragEndAtBottomListener = listener
    }
    
    /**
     * 设置长按监听器
     */
    fun setOnLongClickListener(listener: () -> Unit) {
        onLongClickListener = listener
    }
    
    /**
     * 设置双击监听器
     */
    fun setOnDoubleClickListener(listener: () -> Unit) {
        onDoubleClickListener = listener
    }
    
    /**
     * 设置单机点击监听器（直接回调，避免依赖performClick机制）
     * 这个方法会被TaskIndicatorOverlayManager调用，用于设置点击事件处理
     */
    fun setOnSingleClickListener(listener: () -> Unit) {
        onClickListener = listener
    }
    
    /**
     * 设置是否显示转圈圈效果（伴随模式下不显示）
     */
    fun setShowArc(show: Boolean) {
        showArc = show
        invalidate() // 重新绘制
    }
    
    /**
     * 加载应用图标
     */
    private fun loadAppIcon() {
        try {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val drawable = packageManager.getApplicationIcon(appInfo)
            
            // 将Drawable转换为Bitmap
            appIcon = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val bitmap = Bitmap.createBitmap(
                        drawable.intrinsicWidth,
                        drawable.intrinsicHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("TaskIndicatorOverlay", "加载应用图标失败: ${e.message}")
            appIcon = null
        }
    }
    
    /**
     * 开始显示动画（转圈圈效果）
     */
    fun startAnimation() {
        // 确保 animationScope 可用，如果已被取消则重新创建
        if (animationScope.isActive.not()) {
            android.util.Log.w("TaskIndicatorOverlayView", "animationScope 已被取消，重新创建")
            animationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        
        // 检查动画是否已经在正常运行
        val job = animationJob
        if (isAnimating && job != null && job.isActive) {
            // 动画正在运行，不需要重新启动
            android.util.Log.d("TaskIndicatorOverlayView", "动画已在运行，跳过")
            return
        }
        
        // 先停止之前的动画（确保清理状态）
        if (isAnimating || job != null) {
            android.util.Log.d("TaskIndicatorOverlayView", "停止之前的动画状态")
            isAnimating = false
            job?.cancel()
            animationJob = null
        }
        
        // 启动新动画
        isAnimating = true
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
                // 协程被取消是正常情况，不需要处理
                android.util.Log.d("TaskIndicatorOverlayView", "动画协程被取消")
            } catch (e: Exception) {
                // 其他异常，记录日志并重置状态
                android.util.Log.e("TaskIndicatorOverlayView", "动画协程异常: ${e.message}", e)
                isAnimating = false
            }
        }
        android.util.Log.d("TaskIndicatorOverlayView", "动画已启动，isAnimating=$isAnimating, job.isActive=${animationJob?.isActive}")
    }
    
    /**
     * 停止动画
     */
    fun stopAnimation() {
        if (!isAnimating && animationJob == null) {
            // 已经停止，不需要重复操作
            return
        }
        android.util.Log.d("TaskIndicatorOverlayView", "停止动画，isAnimating=$isAnimating")
        isAnimating = false
        animationJob?.cancel()
        animationJob = null
        rotationAngle = 0f
        post { invalidate() }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.85f // 留一点边距
        
        // 绘制圆形背景（更透明的浅蓝色）
        canvas.drawCircle(centerX, centerY, radius, indicatorPaint)
        
        // 绘制应用图标（在圆圈中间，先绘制图标）
        appIcon?.let { icon ->
            val iconSize = radius * 1.2f // 图标大小为圆圈半径的120%（放大一倍）
            val iconLeft = centerX - iconSize / 2f
            val iconTop = centerY - iconSize / 2f
            val iconRight = centerX + iconSize / 2f
            val iconBottom = centerY + iconSize / 2f
            
            val srcRect = android.graphics.Rect(0, 0, icon.width, icon.height)
            val dstRect = android.graphics.RectF(iconLeft, iconTop, iconRight, iconBottom)
            
            canvas.drawBitmap(icon, srcRect, dstRect, null)
        }
        
        // 绘制旋转的圆弧（转圈圈效果，紫色，最后绘制，确保在最上层）
        // 仅在showArc为true时绘制（伴随模式下不显示）
        if (showArc) {
            if (isAnimating) {
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
            } else {
                // 未动画时，绘制一个静态的圆弧
                val rect = android.graphics.RectF(
                    centerX - radius * 0.7f,
                    centerY - radius * 0.7f,
                    centerX + radius * 0.7f,
                    centerY + radius * 0.7f
                )
                canvas.drawArc(rect, 0f, 270f, false, arcPaint)
            }
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                android.util.Log.d("TaskIndicatorOverlayView", "onTouchEvent: ACTION_DOWN")
                // 记录初始位置
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                longPressTriggered = false
                val params = layoutParams as? android.view.WindowManager.LayoutParams
                if (params != null) {
                    initialX = params.x.toFloat()
                    initialY = params.y.toFloat()
                }
                isDragging = false
                // 启动长按检测
                longPressRunnable = Runnable {
                    if (!isDragging) {
                        // 没有拖拽，触发长按
                        // 在触发长按前也检查权限（如果权限缺失，长按也会失效）
                        longPressTriggered = true
                        onLongClickListener?.invoke()
                    }
                }
                postDelayed(longPressRunnable!!, 500) // 500ms 长按时间
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 计算移动距离
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY
                
                // 如果移动距离超过阈值，开始拖拽（提高阈值，避免误判点击为拖拽）
                if (!isDragging && (abs(deltaX) > 20 || abs(deltaY) > 20)) {
                    isDragging = true
                    android.util.Log.d("TaskIndicatorOverlayView", "开始拖拽: deltaX=$deltaX, deltaY=$deltaY")
                    // 通知拖拽开始
                    onDragStartListener?.invoke()
                }
                
                if (isDragging) {
                    // 计算新位置
                    val newX = initialX + deltaX
                    val newY = initialY + deltaY
                    
                    // 通知位置更新
                    onPositionUpdateListener?.invoke(newX, newY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 取消长按检测
                longPressRunnable?.let { removeCallbacks(it) }
                longPressRunnable = null
                
                // 如果只是点击（没有拖拽），处理点击事件
                if (!isDragging) {
                    if (longPressTriggered) {
                        // 长按已经触发，抬手时不再当作单击/双击处理
                        android.util.Log.d("TaskIndicatorOverlayView", "长按已触发，跳过点击事件")
                        clickCount = 0
                        pendingClickRunnable?.let { removeCallbacks(it) }
                        pendingClickRunnable = null
                    } else {
                    android.util.Log.d("TaskIndicatorOverlayView", "检测到点击（非拖拽），处理点击事件")
                    // 取消之前的待执行点击回调（如果有）
                    pendingClickRunnable?.let { removeCallbacks(it) }
                    
                    // 检测双击
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastClickTime < doubleClickDelay) {
                        clickCount++
                    } else {
                        clickCount = 1
                    }
                    lastClickTime = currentTime
                    
                    // 延迟处理，等待可能的第二次点击
                    // 使用闭包捕获当前的onClickListener，确保即使监听器被替换也能正确触发
                    val currentClickListener = onClickListener
                    pendingClickRunnable = Runnable {
                        if (clickCount >= 2) {
                            // 双击
                            android.util.Log.d("TaskIndicatorOverlayView", "触发双击事件")
                            clickCount = 0
                            onDoubleClickListener?.invoke()
                        } else if (clickCount == 1) {
                            // 单击：优先使用直接回调，如果没有则使用performClick
                            android.util.Log.d("TaskIndicatorOverlayView", "触发单击事件")
                            clickCount = 0
                            if (currentClickListener != null) {
                                // 使用直接回调，避免监听器被替换导致失效
                                android.util.Log.d("TaskIndicatorOverlayView", "使用直接回调触发单击事件")
                                currentClickListener.invoke()
                            } else {
                                // 备用方案：使用performClick
                                android.util.Log.d("TaskIndicatorOverlayView", "使用performClick触发单击事件")
                                performClick()
                            }
                        }
                    }
                    postDelayed(pendingClickRunnable!!, doubleClickDelay)
                    }
                } else {
                    // 拖拽结束，通知监听器
                    onDragEndListener?.invoke()
                    
                    // 检查是否拖拽到顶部或底部
                    val params = layoutParams as? android.view.WindowManager.LayoutParams
                    if (params != null) {
                        val displayMetrics = context.resources.displayMetrics
                        val screenHeight = displayMetrics.heightPixels
                        val topThreshold = 60 * displayMetrics.density
                        val bottomThreshold = 60 * displayMetrics.density
                        val currentY = params.y.toFloat()
                        android.util.Log.d("TaskIndicatorOverlayView", "拖拽结束，当前Y=$currentY, 屏幕高度=$screenHeight, 悬浮球高度=${params.height}")
                        
                        // 检查是否在顶部区域（考虑悬浮球的高度）
                        val topArea = topThreshold + params.height / 2f
                        if (currentY < topArea) {
                            android.util.Log.d("TaskIndicatorOverlayView", "检测到拖拽到顶部区域，触发通知栏显示")
                            onDragEndAtTopListener?.invoke()
                        } else {
                            // 检查是否在底部区域（悬浮球的底部接近屏幕底部）
                            val bottomY = currentY + params.height // 悬浮球底部的位置
                            val bottomArea = screenHeight - bottomThreshold // 底部触发区域的顶部位置
                            if (bottomY > bottomArea) {
                                android.util.Log.d("TaskIndicatorOverlayView", "检测到拖拽到底部区域，触发kill应用")
                                onDragEndAtBottomListener?.invoke()
                            } else {
                                android.util.Log.d("TaskIndicatorOverlayView", "未在顶部或底部区域，不触发特殊操作")
                            }
                        }
                    } else {
                        android.util.Log.w("TaskIndicatorOverlayView", "无法获取LayoutParams，无法检测顶部或底部区域")
                    }
                }
                isDragging = false
                longPressTriggered = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 清理待执行的点击回调
        pendingClickRunnable?.let { removeCallbacks(it) }
        pendingClickRunnable = null
        stopAnimation()
        // 注意：不要取消整个 animationScope，因为悬浮窗 View 可能不会真正 detached
        // 只取消当前的 animationJob 即可，这样下次启动时可以重新创建 scope
        // animationScope.cancel() // 移除这行，避免导致无法重新启动动画
    }
}

