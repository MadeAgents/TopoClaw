package com.cloudcontrol.demo

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 截图服务（前台服务）
 * 负责定时截图并发送到云侧
 * 
 * 功能：
 * 1. 定时截图（固定分辨率1080x1920）
 * 2. 压缩图片
 * 3. 发送到云侧
 * 4. 接收云侧指令
 * 5. 执行点击操作
 */
class ScreenshotService : Service() {
    
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screenshot_service_channel"
        
        // 固定截图分辨率（方案C）
        private const val SCREENSHOT_WIDTH = 1080
        private const val SCREENSHOT_HEIGHT = 1920
        
        // 图片压缩质量
        private const val JPEG_QUALITY = 75
        
        // 动作常量
        const val ACTION_START = "com.cloudcontrol.demo.ACTION_START"
        const val ACTION_STOP = "com.cloudcontrol.demo.ACTION_STOP"
        
        // 参数键
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_MEDIA_PROJECTION = "media_projection"
        
        // 异常检测结果广播
        const val ACTION_ANOMALY_DETECTION_RESULT = "com.cloudcontrol.demo.ACTION_ANOMALY_DETECTION_RESULT"
        const val EXTRA_DETECTION_MESSAGE = "detection_message"
        
        // call_user动作广播（从异常检测触发）
        const val ACTION_CALL_USER_FROM_ANOMALY = "com.cloudcontrol.demo.ACTION_CALL_USER_FROM_ANOMALY"
        const val EXTRA_CALL_USER_TEXT = "call_user_text"
        
        // 端侧模型开关相关
        /**
         * 获取端侧模型开关状态
         * @param context 上下文
         * @return true表示启用端侧模型，false表示禁用（默认禁用）
         */
        fun isEdgeModelEnabled(context: Context): Boolean {
            return false
        }
        
        /**
         * 设置端侧模型开关状态
         * @param context 上下文
         * @param enabled true表示启用，false表示禁用
         */
        fun setEdgeModelEnabled(context: Context, enabled: Boolean) {
            Log.i(TAG, "[开关] 端侧模型功能已下线，忽略设置请求: enabled=$enabled")
        }
    }
    
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    private var isRunning = false
    private var screenshotJob: Job? = null
    private var serverUrl: String = ""
    private var interval: Long = 3 // 默认3秒
    
    // 异常检测管理器
    private var anomalyDetectionManager: AnomalyDetectionManager? = null
    
    // 加载中检测重试计数
    private var loadingRetryCount = 0
    private val maxLoadingRetries = 3
    
    // 端侧异常检测状态管理
    private var hasJustCalledUser = false  // 标记是否刚刚执行了 call_user（执行后必须上发云侧）
    private var consecutiveWaitCount = 0  // 连续 wait 的次数（最多 2 次）
    
    // 统计信息
    private var totalScreenshots = 0
    private var anomaliesDetected = 0
    private var anomaliesHandled = 0
    private var loadingDetected = 0
    private var cloudRequestsSaved = 0
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "========== ScreenshotService 创建 ==========")
        Log.i(TAG, "[服务] onCreate() 被调用")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 获取MediaProjectionManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // 初始化异常检测管理器（功能已下线，保留空引用用于兼容既有流程）
        anomalyDetectionManager = null
        Log.i(TAG, "===========================================")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
                val intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL, 3)
                val resultCode = intent.getIntExtra("result_code", -1)
                val resultData = intent.getParcelableExtra<Intent>("result_data")
                
                if (url.isNotEmpty() && resultCode != -1 && resultData != null) {
                    startService(url, intervalSeconds, resultCode, resultData)
                } else {
                    Log.e(TAG, "启动参数不完整")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopService()
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopService()
        Log.d(TAG, "服务销毁")
    }
    
    /**
     * 启动服务
     */
    private fun startService(
        url: String,
        intervalSeconds: Int,
        resultCode: Int,
        resultData: Intent
    ) {
        if (isRunning) {
            Log.w(TAG, "服务已在运行")
            return
        }
        
        serverUrl = url
        interval = intervalSeconds.toLong()
        
        // 初始化网络服务
        NetworkService.initialize(url, this)
        
        // 创建MediaProjection
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        
        // 创建ImageReader用于接收截图
        imageReader = ImageReader.newInstance(
            SCREENSHOT_WIDTH,
            SCREENSHOT_HEIGHT,
            PixelFormat.RGBA_8888,
            2
        )
        
        // 创建VirtualDisplay
        val metrics = resources.displayMetrics
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenshotDisplay",
            SCREENSHOT_WIDTH,
            SCREENSHOT_HEIGHT,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        
        // 显示前台通知
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 开始截图循环
        isRunning = true
        startScreenshotLoop()
        
        Log.i(TAG, "========== ScreenshotService 已启动 ==========")
        Log.i(TAG, "[服务] 截图间隔: ${interval}秒")
        Log.i(TAG, "[服务] 服务器URL: $serverUrl")
        Log.i(TAG, "[服务] 异常检测管理器: ${if (anomalyDetectionManager != null) "已初始化" else "未初始化"}")
        Log.i(TAG, "===========================================")
    }
    
    /**
     * 停止服务
     */
    private fun stopService() {
        if (!isRunning) return
        
        isRunning = false
        screenshotJob?.cancel()
        
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        
        // 重置加载重试计数和统计信息
        loadingRetryCount = 0
        logStatistics() // 停止时输出最终统计
        
        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "服务已停止")
    }
    
    /**
     * 开始截图循环
     */
    private fun startScreenshotLoop() {
        screenshotJob = serviceScope.launch {
            while (isRunning) {
                try {
                    // 截图
                    val bitmap = captureScreenshot()
                    if (bitmap != null) {
                        // 异常检测和处理
                        val shouldSendToCloud = processScreenshotWithAnomalyDetection(bitmap)
                        
                        if (shouldSendToCloud) {
                            // 正常页面或处理失败，发送到云侧
                            Log.d(TAG, "[云侧请求] 发送截图到云侧")
                            sendScreenshotToCloud(bitmap)
                        } else {
                            // 异常已处理，不发送到云侧，等待下次循环
                            // 日志已在 processScreenshotWithAnomalyDetection 中记录
                        }
                    } else {
                        Log.w(TAG, "截图失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "截图循环出错: ${e.message}", e)
                }
                
                // 等待指定间隔
                delay(interval * 1000)
            }
        }
    }
    
    /**
     * 处理截图并进行异常检测
     * @param bitmap 截图
     * @return true表示应该发送到云侧，false表示异常已处理不需要发送
     */
    private suspend fun processScreenshotWithAnomalyDetection(bitmap: Bitmap): Boolean {
        totalScreenshots++
        Log.d(TAG, "========== 开始异常检测 (总截图数: $totalScreenshots) ==========")
        
        // 检查端侧模型开关
        if (!isEdgeModelEnabled(this)) {
            Log.i(TAG, "[开关] 端侧模型已禁用，直接发送到云侧")
            // 重置状态
            hasJustCalledUser = false
            consecutiveWaitCount = 0
            return true
        }
        
        // 检查是否刚刚执行了 call_user，如果是，必须发送到云侧
        if (hasJustCalledUser) {
            Log.i(TAG, "[状态检查] 刚刚执行了 call_user，强制发送到云侧")
            hasJustCalledUser = false  // 重置状态
            consecutiveWaitCount = 0   // 重置 wait 计数
            return true  // 发送到云侧
        }
        
        val manager = anomalyDetectionManager ?: run {
            Log.w(TAG, "[异常检测] 管理器未初始化，直接发送到云侧")
            return true
        }
        
        try {
            // 1. 检测异常
            Log.i(TAG, "[异常检测] 开始检测，截图尺寸: ${bitmap.width}x${bitmap.height}")
            val detectionResult = manager.detect(bitmap)
            
            // 打印完整的检测结果（使用Log.i确保输出，每条日志单独打印避免被截断）
            Log.i(TAG, "========== 异常检测结果详情 ==========")
            Log.i(TAG, "[检测结果] hasAnomaly = ${detectionResult.hasAnomaly}")
            Log.i(TAG, "[检测结果] anomalyType = ${detectionResult.anomalyType}")
            Log.i(TAG, "[检测结果] anomalyTypeName = ${getAnomalyTypeName(detectionResult.anomalyType)}")
            Log.i(TAG, "[检测结果] anomalyConfidence = ${detectionResult.anomalyConfidence}")
            Log.i(TAG, "[检测结果] closeButtonX (归一化) = ${detectionResult.closeButtonX}")
            Log.i(TAG, "[检测结果] closeButtonY (归一化) = ${detectionResult.closeButtonY}")
            Log.i(TAG, "[检测结果] closeButtonConfidence = ${detectionResult.closeButtonConfidence}")
            Log.i(TAG, "[检测结果] 截图尺寸 = ${bitmap.width}x${bitmap.height}")
            
            // 构建检测结果消息
            val resultMessage = StringBuilder()
            resultMessage.append("【异常检测结果】\n")
            resultMessage.append("截图尺寸: ${bitmap.width}x${bitmap.height}\n")
            resultMessage.append("检测到异常: ${if (detectionResult.hasAnomaly) "是" else "否"}\n")
            
            if (detectionResult.hasAnomaly) {
                val anomalyTypeName = getAnomalyTypeName(detectionResult.anomalyType)
                resultMessage.append("异常类型: $anomalyTypeName(${detectionResult.anomalyType})\n")
                resultMessage.append("异常置信度: ${String.format("%.2f", detectionResult.anomalyConfidence ?: 0f)}\n")
            }
            
            // 如果有关闭按钮，计算并打印实际坐标
            if (detectionResult.closeButtonX != null && detectionResult.closeButtonY != null) {
                val (actualX, actualY) = manager.normalizeToActualCoordinates(
                    detectionResult.closeButtonX,
                    detectionResult.closeButtonY,
                    bitmap.width,
                    bitmap.height
                )
                Log.i(TAG, "[检测结果] 关闭按钮实际坐标 = ($actualX, $actualY)")
                Log.i(TAG, "[检测结果] 坐标映射: 归一化(${detectionResult.closeButtonX}, ${detectionResult.closeButtonY}) -> 实际($actualX, $actualY)")
                Log.i(TAG, "[检测结果] 坐标计算: X=${detectionResult.closeButtonX} * ${bitmap.width} = $actualX, Y=${detectionResult.closeButtonY} * ${bitmap.height} = $actualY")
                
                resultMessage.append("关闭按钮位置: ($actualX, $actualY)\n")
                resultMessage.append("关闭按钮置信度: ${String.format("%.2f", detectionResult.closeButtonConfidence ?: 0f)}\n")
            } else {
                Log.i(TAG, "[检测结果] 未检测到关闭按钮")
                resultMessage.append("关闭按钮: 未检测到\n")
            }
            
            // 通过广播发送检测结果到系统消息
            sendDetectionResultToSystemMessage(resultMessage.toString().trim())
            
            Log.i(TAG, "=====================================")
            
            if (detectionResult.hasAnomaly) {
                anomaliesDetected++
                val anomalyTypeName = getAnomalyTypeName(detectionResult.anomalyType)
                Log.i(TAG, "[异常检测] ✓✓✓ 检测到异常: type=$anomalyTypeName(${detectionResult.anomalyType}), confidence=${detectionResult.anomalyConfidence}")
                
                // 2. 处理异常
                val handled = handleAnomaly(detectionResult, bitmap)
                
                if (handled) {
                    anomaliesHandled++
                    cloudRequestsSaved++
                    Log.i(TAG, "[异常处理] ✓✓✓ 异常已在端侧处理成功，跳过云侧请求 (已节省: $cloudRequestsSaved 次)")
                    // 异常已处理，等待后重新检测
                    delay(1000)
                    return false // 不发送到云侧
                } else {
                    // 处理失败，发送到云侧
                    Log.w(TAG, "[异常处理] ✗ 处理失败，发送到云侧")
                    return true
                }
            } else {
                // 3. 无异常，检查是否加载中
                val isLoading = manager.checkLoading(bitmap)
                
                // 构建加载检测结果消息
                val loadingMessage = StringBuilder()
                loadingMessage.append("【加载状态检测】\n")
                loadingMessage.append("页面状态: ${if (isLoading) "加载中" else "加载完成"}\n")
                
                if (isLoading) {
                    loadingDetected++
                    consecutiveWaitCount++
                    Log.i(TAG, "[加载检测] ⏳ 检测到页面加载中，连续 wait 计数: $consecutiveWaitCount/1 (总检测: $loadingDetected 次)")
                    loadingMessage.append("连续 wait 计数: $consecutiveWaitCount/1\n")
                    
                    // 通过广播发送加载检测结果到系统消息
                    sendDetectionResultToSystemMessage(loadingMessage.toString().trim())
                    
                    if (consecutiveWaitCount < 1) {
                        // 等待后重试（wait）
                        cloudRequestsSaved++
                        Log.d(TAG, "[加载处理] 等待2秒后重试（wait），跳过云侧请求 (已节省: $cloudRequestsSaved 次)")
                        delay(2000)
                        return false // 不发送到云侧，等待下次循环
                    } else {
                        // 超过最大连续 wait 次数（1次），发送到云侧
                        Log.w(TAG, "[加载处理] 超过最大连续 wait 次数（1次），发送到云侧")
                        consecutiveWaitCount = 0  // 重置计数
                        loadingRetryCount = 0     // 重置旧的计数（兼容）
                        return true  // 发送到云侧
                    }
                } else {
                    // 正常页面，发送到云侧
                    // 注意：不在这里重置 consecutiveWaitCount，只在发送到云侧时重置
                    loadingRetryCount = 0    // 重置旧的计数（兼容）
                    Log.i(TAG, "[异常检测] 正常页面（无异常且未加载中），发送到云侧")
                    
                    // 通过广播发送加载检测结果到系统消息
                    sendDetectionResultToSystemMessage(loadingMessage.toString().trim())
                    
                    // 发送到云侧时，在 sendScreenshotToCloud 中会重置 consecutiveWaitCount
                    logStatistics()
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[异常检测] ✗✗✗ 处理失败: ${e.message}", e)
            // 检测失败，降级到云侧处理
            return true
        }
    }
    
    /**
     * 获取异常类型名称
     */
    private fun getAnomalyTypeName(type: Int?): String {
        return when (type) {
            0 -> "权限类"
            1 -> "登录类"
            2 -> "验证类"
            3 -> "支付类"
            4 -> "隐私类"
            else -> "未知类型"
        }
    }
    
    /**
     * 记录统计信息
     */
    private fun logStatistics() {
        Log.i(TAG, "========== 异常检测统计 ==========")
        Log.i(TAG, "总截图数: $totalScreenshots")
        Log.i(TAG, "检测到异常: $anomaliesDetected 次")
        Log.i(TAG, "成功处理异常: $anomaliesHandled 次")
        Log.i(TAG, "检测到加载中: $loadingDetected 次")
        Log.i(TAG, "节省云侧请求: $cloudRequestsSaved 次")
        if (totalScreenshots > 0) {
            val saveRate = (cloudRequestsSaved * 100.0 / totalScreenshots).toInt()
            Log.i(TAG, "节省率: $saveRate%")
        }
        Log.i(TAG, "=================================")
    }
    
    /**
     * 处理检测到的异常
     * @param detectionResult 检测结果
     * @param bitmap 原始截图
     * @return true表示处理成功，false表示处理失败
     */
    private suspend fun handleAnomaly(
        detectionResult: AnomalyDetectionManager.DetectionResult,
        bitmap: Bitmap
    ): Boolean {
        return try {
            val anomalyType = detectionResult.anomalyType
            val anomalyTypeName = getAnomalyTypeName(anomalyType)
            // 新模型类别0-4（权限类、登录类、验证类、支付类、隐私类）均需要用户操作
            when (anomalyType) {
                0, 1, 2, 3, 4 -> {
                    Log.i(TAG, "[异常处理] 需要用户操作的异常类型($anomalyTypeName)，返回call_user动作")
                    val callUserText = "检测到$anomalyTypeName，请手动操作"
                    hasJustCalledUser = true
                    Log.d(TAG, "[状态管理] 设置 hasJustCalledUser = true")
                    sendCallUserFromAnomaly(callUserText)
                    true
                }
                else -> {
                    Log.i(TAG, "[异常处理] 异常类型($anomalyTypeName)，发送到云侧处理")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理异常时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 捕获截图
     */
    private suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            val image = imageReader?.acquireLatestImage() ?: return@withContext null
            
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * SCREENSHOT_WIDTH
                
                val actualWidth = if (rowPadding > 0) {
                    SCREENSHOT_WIDTH + rowPadding / pixelStride
                } else {
                    SCREENSHOT_WIDTH
                }
                
                val tempBitmap = Bitmap.createBitmap(
                    actualWidth,
                    SCREENSHOT_HEIGHT,
                    Bitmap.Config.ARGB_8888
                )
                
                tempBitmap.copyPixelsFromBuffer(buffer)
                
                if (rowPadding == 0) {
                    return@withContext tempBitmap
                }
                
                val finalBitmap = if (actualWidth > SCREENSHOT_WIDTH) {
                    Bitmap.createBitmap(tempBitmap, 0, 0, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT)
                } else {
                    tempBitmap
                }
                
                if (tempBitmap != finalBitmap) {
                    tempBitmap.recycle()
                }
                
                val resultBitmap = if (finalBitmap.width != SCREENSHOT_WIDTH || finalBitmap.height != SCREENSHOT_HEIGHT) {
                    Log.w(TAG, "最终bitmap尺寸不正确: ${finalBitmap.width}x${finalBitmap.height}, 期望: ${SCREENSHOT_WIDTH}x${SCREENSHOT_HEIGHT}")
                    val scaled = Bitmap.createScaledBitmap(finalBitmap, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true)
                    if (finalBitmap != scaled) {
                        finalBitmap.recycle()
                    }
                    scaled
                } else {
                    finalBitmap
                }
                
                val croppedBitmap = detectAndCropBlackBorders(resultBitmap)
                if (croppedBitmap != resultBitmap) {
                    resultBitmap.recycle()
                }
                
                croppedBitmap
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图捕获失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 发送截图到云侧
     */
    private suspend fun sendScreenshotToCloud(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        // 发送到云侧时，重置端侧异常检测状态
        hasJustCalledUser = false
        consecutiveWaitCount = 0
        Log.d(TAG, "[状态管理] 发送到云侧，重置 hasJustCalledUser 和 consecutiveWaitCount")
        try {
            // 转换为Base64（内部会压缩）
            val base64Image = bitmapToBase64(bitmap)
            
            // 发送到云侧
            val apiService = NetworkService.getApiService()
            if (apiService == null) {
                Log.e(TAG, "API服务未初始化")
                return@withContext
            }
            
            // 获取当前 Activity 信息
            val (packageName, className) = MyAccessibilityService.getCurrentActivityInfo()
            
            val response = apiService.sendScreenshot(
                image = base64Image,
                query = null,
                screenWidth = SCREENSHOT_WIDTH,
                screenHeight = SCREENSHOT_HEIGHT,
                packageName = packageName,
                className = className
            )
            
            if (response.isSuccessful) {
                val cloudResponse = response.body()
                if (cloudResponse != null) {
                    Log.d(TAG, "收到云侧指令: ${cloudResponse.action}, x=${cloudResponse.x}, y=${cloudResponse.y}")
                    
                    // 执行指令（切换到主线程执行，因为无障碍服务需要在主线程）
                    withContext(Dispatchers.Main) {
                        executeAction(cloudResponse)
                    }
                } else {
                    Log.w(TAG, "云侧返回空响应")
                }
            } else {
                Log.e(TAG, "请求失败: ${response.code()} - ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送截图失败: ${e.message}", e)
        }
    }
    
    /**
     * 检测并裁剪黑边
     * 黑边通常是纯黑色或接近黑色的像素
     */
    private fun detectAndCropBlackBorders(bitmap: Bitmap): Bitmap {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // 如果尺寸已经是目标尺寸，先检查是否有黑边
            if (width == SCREENSHOT_WIDTH && height == SCREENSHOT_HEIGHT) {
                // 检测左右两侧是否有黑边
                val leftBorder = detectLeftBlackBorder(bitmap)
                val rightBorder = detectRightBlackBorder(bitmap)
                
                if (leftBorder > 0 || rightBorder > 0) {
                    Log.d(TAG, "检测到黑边: 左侧=${leftBorder}px, 右侧=${rightBorder}px")
                    
                    // 计算实际内容区域
                    val contentLeft = leftBorder
                    val contentWidth = width - leftBorder - rightBorder
                    
                    if (contentWidth > 0 && contentWidth < width) {
                        // 裁剪掉黑边
                        val cropped = Bitmap.createBitmap(bitmap, contentLeft, 0, contentWidth, height)
                        
                        // 如果裁剪后宽度不是目标宽度，缩放回目标宽度
                        if (cropped.width != SCREENSHOT_WIDTH) {
                            val scaled = Bitmap.createScaledBitmap(cropped, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true)
                            if (cropped != scaled) {
                                cropped.recycle()
                            }
                            return scaled
                        }
                        
                        return cropped
                    }
                }
            }
            
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "检测黑边失败: ${e.message}", e)
            return bitmap
        }
    }
    
    /**
     * 检测左侧黑边宽度
     */
    private fun detectLeftBlackBorder(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampleHeight = minOf(height, 100) // 只检测前100像素高度
        val threshold = 30 // 黑边阈值（RGB值小于30认为是黑色）
        
        var leftBorder = 0
        var foundNonBlack = false
        
        // 从左侧开始检测
        for (x in 0 until minOf(width / 2, 200)) { // 最多检测200像素
            var isBlackColumn = true
            
            // 检测这一列的多个采样点
            for (y in 0 until sampleHeight step 10) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // 如果RGB值都小于阈值，认为是黑色
                if (r > threshold || g > threshold || b > threshold) {
                    isBlackColumn = false
                    foundNonBlack = true
                    break
                }
            }
            
            if (!isBlackColumn) {
                leftBorder = x
                break
            }
        }
        
        return if (foundNonBlack) leftBorder else 0
    }
    
    /**
     * 检测右侧黑边宽度
     */
    private fun detectRightBlackBorder(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampleHeight = minOf(height, 100) // 只检测前100像素高度
        val threshold = 30 // 黑边阈值
        
        var rightBorder = 0
        var foundNonBlack = false
        
        // 从右侧开始检测
        for (x in (width - 1) downTo maxOf(width / 2, width - 200)) { // 最多检测200像素
            var isBlackColumn = true
            
            // 检测这一列的多个采样点
            for (y in 0 until sampleHeight step 10) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // 如果RGB值都小于阈值，认为是黑色
                if (r > threshold || g > threshold || b > threshold) {
                    isBlackColumn = false
                    foundNonBlack = true
                    break
                }
            }
            
            if (!isBlackColumn) {
                rightBorder = width - 1 - x
                break
            }
        }
        
        return if (foundNonBlack) rightBorder else 0
    }
    
    /**
     * 将Bitmap转换为Base64字符串（同时压缩）
     * 返回格式：data:image/jpeg;base64,<base64字符串>
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val byteArray = outputStream.toByteArray()
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        // 添加data URI前缀，云侧期望格式：data:image/jpeg;base64,<base64字符串>
        return "data:image/jpeg;base64,$base64String"
    }
    
    /**
     * 执行云侧返回的指令
     */
    private suspend fun executeAction(response: CloudResponse) {
        when (response.action.lowercase()) {
            "click" -> {
                val x = response.x
                val y = response.y
                if (x != null && y != null) {
                    // 使用无障碍服务执行点击
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        MyAccessibilityService.getInstance()?.performClick(x, y)
                    } else {
                        Log.e(TAG, "Android版本过低，不支持手势点击")
                    }
                } else {
                    Log.w(TAG, "点击指令缺少坐标")
                }
            }
            "home" -> {
                MyAccessibilityService.getInstance()?.goHome()
            }
            "back" -> {
                MyAccessibilityService.getInstance()?.goBack()
            }
            else -> {
                Log.w(TAG, "未知指令: ${response.action}")
            }
        }
    }
    
    /**
     * 创建通知渠道（Android 8.0+需要）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 发送检测结果到系统消息（通过广播）
     */
    private fun sendDetectionResultToSystemMessage(message: String) {
        try {
            val intent = Intent(ACTION_ANOMALY_DETECTION_RESULT).apply {
                putExtra(EXTRA_DETECTION_MESSAGE, message)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "[广播] 已发送异常检测结果到系统消息")
        } catch (e: Exception) {
            Log.e(TAG, "[广播] 发送异常检测结果失败: ${e.message}", e)
        }
    }
    
    /**
     * 发送call_user动作（从异常检测触发）
     */
    private fun sendCallUserFromAnomaly(callUserText: String) {
        try {
            val intent = Intent(ACTION_CALL_USER_FROM_ANOMALY).apply {
                putExtra(EXTRA_CALL_USER_TEXT, callUserText)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "[广播] 已发送call_user动作: $callUserText")
        } catch (e: Exception) {
            Log.e(TAG, "[广播] 发送call_user动作失败: ${e.message}", e)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

