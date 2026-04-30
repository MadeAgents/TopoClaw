package com.cloudcontrol.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.content.res.Resources

/**
 * 自定义Drawable，实现CENTER_CROP效果
 * 保持图片宽高比，放大以填满整个区域，允许部分区域不可见
 */
class CenterCropDrawable(
    resources: Resources,
    bitmap: Bitmap
) : Drawable() {
    
    private val bitmapDrawable: BitmapDrawable = BitmapDrawable(resources, bitmap)
    
    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) {
            return
        }
        
        val bitmap = bitmapDrawable.bitmap ?: return
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        val viewWidth = bounds.width()
        val viewHeight = bounds.height()
        
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return
        }
        
        // 计算缩放比例，取较大的值以确保填满整个区域
        val scaleX = viewWidth.toFloat() / bitmapWidth
        val scaleY = viewHeight.toFloat() / bitmapHeight
        val scale = maxOf(scaleX, scaleY)
        
        // 计算缩放后的尺寸
        val scaledWidth = bitmapWidth * scale
        val scaledHeight = bitmapHeight * scale
        
        // 计算居中位置
        val left = (viewWidth - scaledWidth) / 2f
        val top = (viewHeight - scaledHeight) / 2f
        
        // 计算源矩形（整个bitmap）
        val srcRect = Rect(0, 0, bitmapWidth, bitmapHeight)
        
        // 计算目标矩形（缩放后居中）
        val dstRect = Rect(
            (bounds.left + left).toInt(),
            (bounds.top + top).toInt(),
            (bounds.left + left + scaledWidth).toInt(),
            (bounds.top + top + scaledHeight).toInt()
        )
        
        // 绘制bitmap
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)
    }
    
    override fun setAlpha(alpha: Int) {
        bitmapDrawable.alpha = alpha
        invalidateSelf()
    }
    
    override fun getAlpha(): Int {
        return bitmapDrawable.alpha
    }
    
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        bitmapDrawable.colorFilter = colorFilter
        invalidateSelf()
    }
    
    override fun getColorFilter(): android.graphics.ColorFilter? {
        return bitmapDrawable.colorFilter
    }
    
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int {
        return bitmapDrawable.opacity
    }
    
    override fun getIntrinsicWidth(): Int {
        return bitmapDrawable.intrinsicWidth
    }
    
    override fun getIntrinsicHeight(): Int {
        return bitmapDrawable.intrinsicHeight
    }
}

