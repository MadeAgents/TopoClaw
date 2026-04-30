package com.cloudcontrol.demo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

/**
 * 圆形菜单视图（方案A）
 * 悬浮球点击后放大成圆形菜单盘，按键呈圆形排列
 * 仅用于任务运行模式，不用于伴随模式
 */
class CircularMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 菜单项数据
    data class MenuItem(
        val text: String,
        val angle: Float, // 角度（度），0度为顶部，顺时针
        val icon: Bitmap? = null
    )

    // 颜色定义
    private val diskBackgroundColor = 0xE0000000.toInt() // 半透明黑色背景（88%不透明度）
    private val buttonBackgroundColor = 0xFFF5F5F5.toInt() // 按钮背景色（浅灰）
    private val buttonPressedColor = 0xFFE0E0E0.toInt() // 按钮按下时的颜色
    private val textColor = 0xFF000000.toInt() // 文字颜色（黑色）
    private val centerIconColor = 0xFF9C27B0.toInt() // 中心图标颜色（紫色）

    // Paint对象
    private val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = diskBackgroundColor
        style = Paint.Style.FILL
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = buttonBackgroundColor
        style = Paint.Style.FILL
    }

    private val buttonPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = buttonPressedColor
        style = Paint.Style.FILL
    }

    private val buttonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 14f * resources.displayMetrics.scaledDensity // 14sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = centerIconColor
        style = Paint.Style.FILL
    }

    // 菜单项
    private var menuItems = mutableListOf<MenuItem>()
    private var selectedIndex = -1
    private var centerIcon: Bitmap? = null

    // 尺寸参数
    private var centerX = 0f
    private var centerY = 0f
    private var diskRadius = 0f
    private var buttonRadius = 0f
    private var buttonDistance = 0f // 按钮距离中心的距离

    // 动画相关
    private var scaleAnimator: ValueAnimator? = null
    private var currentScale = 0f
    private var isAnimating = false

    var onItemClickListener: ((Int) -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // 加载应用图标作为中心图标
        loadCenterIcon()
    }

    /**
     * 加载应用图标作为中心图标
     */
    private fun loadCenterIcon() {
        try {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val drawable = packageManager.getApplicationIcon(appInfo)

            centerIcon = when (drawable) {
                is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
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
            android.util.Log.w("CircularMenuView", "加载应用图标失败: ${e.message}")
            centerIcon = null
        }
    }

    /**
     * 更新菜单项（根据任务暂停状态和是否运行）
     * 注意：菜单项的顺序必须和垂直菜单（TaskIndicatorMenuView）保持一致
     * 暂停时：0=继续任务，1=返回应用，2=结束任务
     * 未暂停时：0=返回应用，1=补充任务(可选)，2=结束任务
     */
    fun updateMenuItems(isPaused: Boolean, isRunning: Boolean = true) {
        menuItems.clear()

        if (isPaused) {
            // 任务已暂停时：继续任务、返回应用、结束任务
            // 索引顺序：0=继续任务，1=返回应用，2=结束任务
            // 布局：顶部=继续任务，右侧=返回应用，底部=结束任务
            menuItems.add(MenuItem("继续任务", 0f))     // 索引0，顶部
            menuItems.add(MenuItem("返回应用", 90f))     // 索引1，右侧
            menuItems.add(MenuItem("结束任务", 180f))    // 索引2，底部
        } else {
            // 任务未暂停时：返回应用、补充任务（如果运行中）、结束任务
            // 索引顺序：0=返回应用，1=补充任务(可选)，2=结束任务
            menuItems.add(MenuItem("返回应用", 0f))      // 索引0，顶部
            if (isRunning) {
                menuItems.add(MenuItem("补充任务", 270f)) // 索引1，左侧
            }
            menuItems.add(MenuItem("结束任务", 180f))    // 索引2，底部
        }

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        centerX = width / 2f
        centerY = height / 2f
        diskRadius = minOf(width, height) / 2f * 0.9f // 圆盘半径为90%的尺寸
        buttonRadius = 35f * resources.displayMetrics.density // 按钮半径35dp
        buttonDistance = diskRadius * 0.65f // 按钮距离中心65%的圆盘半径

        // 启动展开动画
        startExpandAnimation()
    }

    /**
     * 启动展开动画
     */
    private fun startExpandAnimation() {
        if (isAnimating) return

        isAnimating = true
        currentScale = 0f

        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L // 250ms动画
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                currentScale = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 启动收缩动画
     */
    fun startCollapseAnimation(onEnd: () -> Unit) {
        if (!isAnimating) {
            onEnd()
            return
        }

        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(currentScale, 0f).apply {
            duration = 200L // 200ms动画
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                currentScale = animation.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isAnimating = false
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (currentScale <= 0f) return

        // 保存画布状态
        canvas.save()

        // 应用缩放和透明度
        val scale = currentScale
        val alphaValue = (255 * scale).toInt()
        canvas.scale(scale, scale, centerX, centerY)

        // 绘制圆盘背景（半透明）
        diskPaint.alpha = (alphaValue * 0.8f).toInt()
        canvas.drawCircle(centerX, centerY, diskRadius, diskPaint)

        // 绘制中心图标
        centerIcon?.let { icon ->
            val iconSize = diskRadius * 0.3f // 中心图标大小为圆盘半径的30%
            val iconLeft = centerX - iconSize / 2f
            val iconTop = centerY - iconSize / 2f
            val iconRight = centerX + iconSize / 2f
            val iconBottom = centerY + iconSize / 2f

            val srcRect = Rect(0, 0, icon.width, icon.height)
            val dstRect = RectF(iconLeft, iconTop, iconRight, iconBottom)

            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = alphaValue
            }
            canvas.drawBitmap(icon, srcRect, dstRect, iconPaint)
        }

        // 绘制菜单按钮
        menuItems.forEachIndexed { index, item ->
            // 计算按钮位置（极坐标转直角坐标）
            val angleRad = Math.toRadians(item.angle.toDouble())
            val buttonX = centerX + buttonDistance * cos(angleRad).toFloat()
            val buttonY = centerY + buttonDistance * sin(angleRad).toFloat()

            // 选择画笔（根据是否选中）
            val paint = if (selectedIndex == index) buttonPressedPaint else buttonPaint
            paint.alpha = alphaValue

            // 绘制按钮背景（圆形）
            canvas.drawCircle(buttonX, buttonY, buttonRadius, paint)

            // 绘制按钮边框
            buttonStrokePaint.alpha = alphaValue
            canvas.drawCircle(buttonX, buttonY, buttonRadius, buttonStrokePaint)

            // 绘制文字
            textPaint.alpha = alphaValue
            // 文字位置在按钮下方
            val textY = buttonY + buttonRadius + textPaint.textSize + 8f * resources.displayMetrics.density
            canvas.drawText(item.text, buttonX, textY, textPaint)
        }

        // 恢复画布状态
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentScale <= 0f) return false

        // 将触摸坐标转换为相对于中心的坐标（考虑缩放）
        val touchX = event.x
        val touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val clickedIndex = findClickedButton(touchX, touchY)
                if (clickedIndex >= 0) {
                    selectedIndex = clickedIndex
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (selectedIndex >= 0 && selectedIndex < menuItems.size) {
                    onItemClickListener?.invoke(selectedIndex)
                }
                selectedIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                selectedIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 检查是否还在按钮上
                val clickedIndex = findClickedButton(touchX, touchY)
                if (clickedIndex != selectedIndex) {
                    selectedIndex = clickedIndex
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 查找被点击的按钮索引
     */
    private fun findClickedButton(x: Float, y: Float): Int {
        menuItems.forEachIndexed { index, item ->
            // 计算按钮位置
            val angleRad = Math.toRadians(item.angle.toDouble())
            val buttonX = centerX + buttonDistance * cos(angleRad).toFloat()
            val buttonY = centerY + buttonDistance * sin(angleRad).toFloat()

            // 计算距离
            val distance = sqrt((x - buttonX).pow(2) + (y - buttonY).pow(2))
            if (distance <= buttonRadius) {
                return index
            }
        }
        return -1
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scaleAnimator?.cancel()
        scaleAnimator = null
    }
}

