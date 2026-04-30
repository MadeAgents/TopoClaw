package com.cloudcontrol.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 长截图工具类
 * 实现滚动截图并拼接成长图的功能
 */
object LongScreenshotHelper {
    private const val TAG = "LongScreenshotHelper"
    
    // 日志回调（可选，用于输出到UI）
    private var logCallback: ((String) -> Unit)? = null
    
    // 屏幕尺寸（固定值，与ChatScreenshotService一致）
    private const val SCREEN_WIDTH = 1080
    private const val SCREEN_HEIGHT = 1920
    
    /**
     * 设置日志回调（用于将日志输出到UI）
     */
    fun setLogCallback(callback: (String) -> Unit) {
        logCallback = callback
    }
    
    /**
     * 清除日志回调
     */
    fun clearLogCallback() {
        logCallback = null
    }
    
    /**
     * 输出日志（同时输出到Log和回调）
     */
    private fun log(message: String) {
        Log.d(TAG, message)
        logCallback?.invoke(message)
    }
    
    // 滚动参数
    private const val SWIPE_DURATION = 1200L  // 滑动持续时间（毫秒）
    
    // 等待时间
    private const val WAIT_AFTER_SCROLL = 600L  // 滚动后等待时间（毫秒）
    
    /**
     * 长截图和当前屏幕截图的数据类
     */
    data class LongScreenshotResult(
        val longScreenshot: Bitmap,      // 长截图
        val currentScreen: Bitmap        // 当前屏幕截图（最后一帧）
    )
    
    /**
     * 捕获长截图
     * 
     * @param direction 滚动方向，"down"向下或"up"向上
     * @param steps 滚动次数（默认3次）
     * @return LongScreenshotResult包含长截图和当前屏幕截图，失败返回null
     */
    suspend fun captureLongScreenshot(
        direction: String = "down",
        steps: Int = 3
    ): LongScreenshotResult? = withContext(Dispatchers.IO) {
        val screenshots = mutableListOf<Bitmap>()
        return@withContext try {
            log("开始捕获长截图: direction=$direction, steps=$steps")
            
            val chatService = ChatScreenshotService.getInstance()
            val accessibilityService = MyAccessibilityService.getInstance()
            
            if (chatService == null || !chatService.isReady()) {
                log("✗ 截图服务未就绪")
                return@withContext null
            }
            
            if (accessibilityService == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                log("✗ 无障碍服务未就绪或Android版本过低")
                return@withContext null
            }
            
            val actualSteps = steps.coerceIn(1, 10)
            log("实际滚动次数: $actualSteps")
            
            log("获取初始截图...")
            val initialScreenshot = chatService.captureScreenshot()
            if (initialScreenshot == null) {
                log("✗ 初始截图失败")
                return@withContext null
            }
            
            log("✓ 初始截图成功，尺寸: ${initialScreenshot.width}x${initialScreenshot.height}")
            screenshots.add(initialScreenshot)
            
            val isDown = direction.lowercase() == "down"
            
            val startY: Int
            val endY: Int
            
            if (isDown) {
                startY = (SCREEN_HEIGHT * 0.8).toInt()
                endY = (SCREEN_HEIGHT * 0.2).toInt()
            } else {
                startY = (SCREEN_HEIGHT * 0.2).toInt()
                endY = (SCREEN_HEIGHT * 0.8).toInt()
            }
            
            val scrollDistance = kotlin.math.abs(endY - startY)
            val actualOverlapHeight = SCREEN_HEIGHT - scrollDistance
            
            val overlapRatio = 0.75f
            val usedOverlapHeight = (actualOverlapHeight * overlapRatio).toInt()
            
            log("滚动方向: ${if (isDown) "向下" else "向上"}, startY=$startY, endY=$endY, 滑动距离=$scrollDistance")
            log("实际重叠高度: $actualOverlapHeight (${(actualOverlapHeight.toFloat() / SCREEN_HEIGHT * 100).toInt()}%)")
            log("使用重叠高度: $usedOverlapHeight (去除${(overlapRatio * 100).toInt()}%，保留${((1 - overlapRatio) * 100).toInt()}%缓冲)")
            
            for (i in 0 until actualSteps) {
                log("========== 执行第${i + 1}次滚动 ==========")
                log("滚动参数: startY=$startY, endY=$endY, 距离=${kotlin.math.abs(endY - startY)}")
                
                log("开始执行滑动手势...")
                val swipeSuccess = withContext(Dispatchers.Main) {
                    accessibilityService.performSwipe(
                        SCREEN_WIDTH / 2,
                        startY,
                        SCREEN_WIDTH / 2,
                        endY,
                        SWIPE_DURATION
                    )
                }
                
                log("滑动手势执行结果: $swipeSuccess")
                
                if (!swipeSuccess) {
                    log("✗ 第${i + 1}次滚动失败，停止滚动")
                    break
                }
                
                log("等待页面稳定 ${WAIT_AFTER_SCROLL}ms...")
                delay(WAIT_AFTER_SCROLL)
                
                log("获取第${i + 1}次滚动后的截图...")
                val screenshot = chatService.captureScreenshot()
                if (screenshot == null) {
                    log("✗ 第${i + 1}次滚动后截图失败，停止滚动")
                    break
                }
                
                log("✓ 第${i + 1}次截图成功，尺寸: ${screenshot.width}x${screenshot.height}")
                screenshots.add(screenshot)
                
                if (screenshots.size >= 2) {
                    val similarity = calculateSimilarity(
                        screenshots[screenshots.size - 2],
                        screenshots[screenshots.size - 1]
                    )
                    log("截图${screenshots.size - 1}和${screenshots.size}的相似度: $similarity")
                    if (similarity > 0.95) {
                        log("检测到页面底部（相似度>0.95），停止滚动")
                        break
                    }
                }
                
                log("第${i + 1}次滚动完成，当前截图数量: ${screenshots.size}")
            }
            
            log("滚动循环结束，共获取 ${screenshots.size} 张截图")
            
            log("开始拼接${screenshots.size}张截图...")
            val mergedBitmap = mergeBitmaps(screenshots, isDown, usedOverlapHeight)
            
            screenshots.forEach { it.recycle() }
            screenshots.clear()
            
            if (mergedBitmap == null) {
                log("✗ 长截图拼接失败")
                return@withContext null
            }
            
            log("✓ 长截图拼接成功，尺寸: ${mergedBitmap.width}x${mergedBitmap.height}")
            
            log("获取当前屏幕截图（最后一帧）...")
            val currentScreen = chatService.captureScreenshot()
            if (currentScreen == null) {
                log("✗ 获取当前屏幕截图失败")
                mergedBitmap.recycle()
                return@withContext null
            }
            
            log("✓ 当前屏幕截图获取成功，尺寸: ${currentScreen.width}x${currentScreen.height}")
            
            return@withContext LongScreenshotResult(
                longScreenshot = mergedBitmap,
                currentScreen = currentScreen
            )
        } catch (e: Exception) {
            log("✗ 捕获长截图时发生异常: ${e.message}")
            Log.e(TAG, "捕获长截图时发生异常: ${e.message}", e)
            screenshots.forEach { it.recycle() }
            null
        }
    }
    
    /**
     * 拼接多张截图
     * 
     * @param bitmaps 截图列表
     * @param isDown 是否向下滚动（影响拼接顺序）
     * @param overlapHeight 重叠高度（像素）
     * @return 拼接后的Bitmap
     */
    private fun mergeBitmaps(bitmaps: List<Bitmap>, isDown: Boolean, overlapHeight: Int): Bitmap? {
        if (bitmaps.isEmpty()) {
            return null
        }
        
        if (bitmaps.size == 1) {
            // 只有一张截图，直接返回副本
            return Bitmap.createBitmap(bitmaps[0])
        }
        
        try {
            val width = SCREEN_WIDTH
            val singleHeight = SCREEN_HEIGHT
            
            // 计算总高度：第一张完整高度 + 后续每张的有效高度（去除重叠部分）
            val totalHeight = singleHeight + (bitmaps.size - 1) * (singleHeight - overlapHeight)
            
            Log.d(TAG, "创建拼接Bitmap: ${width}x${totalHeight}, 重叠高度: $overlapHeight")
            val mergedBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mergedBitmap)
            
            var currentY = 0f
            
            // 如果向上滚动，需要反转顺序
            val orderedBitmaps = if (isDown) bitmaps else bitmaps.reversed()
            
            for (i in orderedBitmaps.indices) {
                val bitmap = orderedBitmaps[i]
                
                if (i == 0) {
                    // 第一张：完整高度
                    canvas.drawBitmap(bitmap, 0f, currentY, null)
                    currentY += singleHeight
                } else {
                    // 后续：去除重叠部分
                    val srcRect = android.graphics.Rect(
                        0,
                        overlapHeight,
                        width,
                        singleHeight
                    )
                    val dstRect = android.graphics.RectF(
                        0f,
                        currentY,
                        width.toFloat(),
                        currentY + (singleHeight - overlapHeight)
                    )
                    canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                    currentY += (singleHeight - overlapHeight)
                }
            }
            
            Log.d(TAG, "拼接完成，最终高度: $currentY")
            return mergedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "拼接Bitmap时发生异常: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 计算两张截图的相似度（简单方法：比较底部区域）
     * 
     * @param bitmap1 第一张截图
     * @param bitmap2 第二张截图
     * @return 相似度（0.0-1.0）
     */
    private fun calculateSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        try {
            // 比较底部200像素区域
            val compareHeight = 200.coerceAtMost(bitmap1.height.coerceAtMost(bitmap2.height))
            val startY1 = bitmap1.height - compareHeight
            val startY2 = bitmap2.height - compareHeight
            
            var samePixels = 0
            var totalPixels = 0
            
            for (y in 0 until compareHeight) {
                for (x in 0 until bitmap1.width.coerceAtMost(bitmap2.width)) {
                    totalPixels++
                    val pixel1 = bitmap1.getPixel(x, startY1 + y)
                    val pixel2 = bitmap2.getPixel(x, startY2 + y)
                    
                    // 允许一定的颜色差异（RGB差值<10认为相同）
                    val r1 = android.graphics.Color.red(pixel1)
                    val g1 = android.graphics.Color.green(pixel1)
                    val b1 = android.graphics.Color.blue(pixel1)
                    val r2 = android.graphics.Color.red(pixel2)
                    val g2 = android.graphics.Color.green(pixel2)
                    val b2 = android.graphics.Color.blue(pixel2)
                    
                    val diff = kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
                    if (diff < 30) {  // 允许30的RGB差值
                        samePixels++
                    }
                }
            }
            
            return if (totalPixels > 0) {
                samePixels.toFloat() / totalPixels
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "计算相似度时发生异常: ${e.message}", e)
            return 0f
        }
    }
}

