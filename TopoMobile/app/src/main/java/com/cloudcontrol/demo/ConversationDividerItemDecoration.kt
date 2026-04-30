package com.cloudcontrol.demo

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 会话列表分割线装饰
 * 分割线左侧与头像右边缘对齐，不抵近屏幕左边界
 */
class ConversationDividerItemDecoration(
    private val divider: Drawable,
    private val leftMarginPx: Int  // 分割线左边界距屏幕左侧的像素（= paddingStart + 头像宽度）
) : RecyclerView.ItemDecoration() {

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val dividerHeight = divider.intrinsicHeight.coerceAtLeast(1)
        val right = parent.width - parent.paddingRight

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val bottom = child.bottom
            val top = bottom - dividerHeight
            divider.setBounds(leftMarginPx, top, right, bottom)
            divider.draw(c)
        }
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.bottom = divider.intrinsicHeight.coerceAtLeast(1)
    }
}
