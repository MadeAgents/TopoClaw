package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    interface InteractionListener {
        fun onSingleTap()
        fun onLongPress()
    }

    var interactionListener: InteractionListener? = null

    private val drawMatrix = Matrix()
    private val imageRect = RectF()
    private val minScale = 1f
    private val maxScale = 4f
    private val doubleTapScale = 2.5f
    private var currentScale = 1f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val rawScale = currentScale * detector.scaleFactor
            val targetScale = rawScale.coerceIn(minScale, maxScale)
            val appliedScale = targetScale / currentScale
            if (appliedScale != 1f) {
                drawMatrix.postScale(appliedScale, appliedScale, detector.focusX, detector.focusY)
                currentScale = targetScale
                constrainTranslation()
                imageMatrix = drawMatrix
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale <= minScale + 0.01f) {
                zoomToScale(doubleTapScale.coerceAtMost(maxScale), e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
            } else {
                fitToCenter()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            interactionListener?.onSingleTap()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            interactionListener?.onLongPress()
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToCenter()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { fitToCenter() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        gestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(currentScale > minScale + 0.01f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && event.pointerCount == 1 && currentScale > minScale + 0.01f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!isDragging) {
                        isDragging = max(kotlin.math.abs(dx), kotlin.math.abs(dy)) > 2f
                    }
                    if (isDragging) {
                        drawMatrix.postTranslate(dx, dy)
                        constrainTranslation()
                        imageMatrix = drawMatrix
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (currentScale <= minScale + 0.01f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }

    private fun fitToCenter() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) return

        drawMatrix.reset()
        val scale = min(width / drawableWidth, height / drawableHeight)
        val dx = (width - drawableWidth * scale) / 2f
        val dy = (height - drawableHeight * scale) / 2f
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        currentScale = minScale
    }

    private fun constrainTranslation() {
        val drawable = drawable ?: return
        imageRect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        drawMatrix.mapRect(imageRect)

        val dx = when {
            imageRect.width() <= width -> width / 2f - imageRect.centerX()
            imageRect.left > 0f -> -imageRect.left
            imageRect.right < width -> width - imageRect.right
            else -> 0f
        }
        val dy = when {
            imageRect.height() <= height -> height / 2f - imageRect.centerY()
            imageRect.top > 0f -> -imageRect.top
            imageRect.bottom < height -> height - imageRect.bottom
            else -> 0f
        }
        if (dx != 0f || dy != 0f) {
            drawMatrix.postTranslate(dx, dy)
        }
    }

    private fun zoomToScale(targetScale: Float, focusX: Float, focusY: Float) {
        val clampedTarget = targetScale.coerceIn(minScale, maxScale)
        val appliedScale = clampedTarget / currentScale
        if (appliedScale == 1f) return
        drawMatrix.postScale(appliedScale, appliedScale, focusX, focusY)
        currentScale = clampedTarget
        constrainTranslation()
        imageMatrix = drawMatrix
    }
}
