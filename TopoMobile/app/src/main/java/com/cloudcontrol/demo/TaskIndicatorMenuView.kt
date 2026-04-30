package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 任务指示器菜单视图
 * 显示三个选项：继续执行、返回应用、结束任务
 */
class TaskIndicatorMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 美化后的颜色配置
    private val backgroundColor = 0xF5FFFFFF.toInt() // 半透明白色背景（95%不透明度）
    private val textColor = 0xFF333333.toInt() // 深灰色文字，更柔和
    private val dividerColor = 0x1A000000.toInt() // 半透明黑色分隔线（10%不透明度）
    private val selectedColor = 0x1A000000.toInt() // 选中时的半透明背景（10%不透明度）
    private val shadowColor = 0x40000000.toInt() // 阴影颜色（25%不透明度）

    private val cornerRadius = 16f * resources.displayMetrics.density // 16dp圆角
    private val shadowRadius = 8f * resources.displayMetrics.density // 8dp阴影半径
    private val shadowOffsetX = 0f
    private val shadowOffsetY = 4f * resources.displayMetrics.density // 4dp阴影偏移
    private val padding = 8f * resources.displayMetrics.density // 8dp内边距

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = shadowColor
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 16f * resources.displayMetrics.scaledDensity // 16sp文字大小
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
        isFakeBoldText = false
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dividerColor
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density // 1dp分隔线宽度
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = selectedColor
        style = Paint.Style.FILL
    }

    private var menuItems = mutableListOf("继续执行", "返回应用", "结束任务")
    private var itemHeight = 0f
    private var selectedIndex = -1

    var onItemClickListener: ((Int) -> Unit)? = null
    
    /**
     * 更新菜单项（根据任务暂停状态和是否运行）
     * @param isPaused 是否暂停
     * @param isRunning 是否运行中（用于决定是否显示补充任务和暂停任务）
     */
    fun updateMenuItems(isPaused: Boolean, isRunning: Boolean = true) {
        menuItems.clear()
        if (isPaused) {
            // 任务已暂停时：继续任务、返回应用、结束任务
            menuItems.addAll(listOf("继续任务", "返回应用", "结束任务"))
        } else {
            val items = mutableListOf<String>()
            // 当任务运行且未暂停时，添加暂停任务选项
            if (isRunning) {
                items.add("暂停任务")
            }
            items.add("返回应用")
            // 当任务运行且未暂停时，添加补充任务选项
            if (isRunning) {
                items.add("补充任务")
            }
            items.add("结束任务")
            menuItems.addAll(items)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 考虑padding，计算实际可用高度
        val availableHeight = height - padding * 2
        itemHeight = availableHeight / menuItems.size.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 绘制阴影（先绘制阴影，再绘制背景）
        val shadowRect = RectF(
            padding + shadowOffsetX,
            padding + shadowOffsetY,
            width.toFloat() - padding + shadowOffsetX,
            height.toFloat() - padding + shadowOffsetY
        )
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)

        // 绘制圆角背景
        val bgRect = RectF(padding, padding, width.toFloat() - padding, height.toFloat() - padding)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, backgroundPaint)

        // 绘制菜单项（考虑padding）
        val contentTop = padding
        menuItems.forEachIndexed { index, text ->
            val itemTop = contentTop + index * itemHeight
            val itemBottom = contentTop + (index + 1) * itemHeight
            val centerY = itemTop + itemHeight / 2f

            // 绘制选中背景（带圆角）
            if (selectedIndex == index) {
                val itemRect = RectF(padding, itemTop, width.toFloat() - padding, itemBottom)
                // 根据位置调整圆角：第一项只有上圆角，最后一项只有下圆角，中间项无圆角
                val topRadius = if (index == 0) cornerRadius else 0f
                val bottomRadius = if (index == menuItems.size - 1) cornerRadius else 0f
                val path = android.graphics.Path().apply {
                    addRoundRect(itemRect, floatArrayOf(
                        topRadius, topRadius,  // 左上
                        topRadius, topRadius,  // 右上
                        bottomRadius, bottomRadius,  // 右下
                        bottomRadius, bottomRadius   // 左下
                    ), android.graphics.Path.Direction.CW)
                }
                canvas.drawPath(path, selectedPaint)
            }

            // 绘制文字（垂直居中）
            val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, width / 2f, textY, textPaint)

            // 绘制分隔线（最后一项不绘制，使用更柔和的分隔线）
            if (index < menuItems.size - 1) {
                val lineY = itemBottom
                canvas.drawLine(
                    padding + cornerRadius / 2,  // 左边留一些间距
                    lineY,
                    width.toFloat() - padding - cornerRadius / 2,  // 右边留一些间距
                    lineY,
                    dividerPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val y = event.y
                // 考虑padding，计算实际内容区域的索引
                val contentTop = padding
                val relativeY = y - contentTop
                if (relativeY >= 0) {
                    val index = (relativeY / itemHeight).toInt()
                    if (index >= 0 && index < menuItems.size) {
                        selectedIndex = index
                        invalidate()
                    }
                }
                return true
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
        }
        return super.onTouchEvent(event)
    }
}

