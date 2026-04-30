package com.cloudcontrol.demo

import android.app.*
import android.content.Context
import android.content.Intent
import android.app.Activity
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * 聊天截图服务（前台服务）
 * 专门用于聊天功能的截图
 */
class ChatScreenshotService : Service() {
    
    companion object {
        private const val TAG = "ChatScreenshotService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "chat_screenshot_service_channel"
        
        // 固定截图分辨率
        private const val SCREENSHOT_WIDTH = 1080
        private const val SCREENSHOT_HEIGHT = 1920
        
        // 动作常量
        const val ACTION_START = "com.cloudcontrol.demo.ACTION_START_CHAT_SCREENSHOT"
        const val ACTION_STOP = "com.cloudcontrol.demo.ACTION_STOP_CHAT_SCREENSHOT"
        const val ACTION_GET_SCREENSHOT = "com.cloudcontrol.demo.ACTION_GET_SCREENSHOT"
        
        // 参数键
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        // 单例
        @Volatile
        private var instance: ChatScreenshotService? = null
        
        fun getInstance(): ChatScreenshotService? = instance
    }
    
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var projectionSessionId: Long = 0L
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        instance = this
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 获取MediaProjectionManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // 立即显示前台通知，避免超时异常
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "前台通知已显示（立即）")
        } catch (e: Exception) {
            Log.e(TAG, "显示前台通知失败: ${e.message}", e)
        }
        
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                // 注意：resultData 应该直接传递 Intent，但需要检查传递方式
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                Log.d(TAG, "onStartCommand: resultCode=$resultCode (RESULT_OK=${Activity.RESULT_OK}), resultData=${resultData != null}")
                if (resultData != null) {
                    Log.d(TAG, "onStartCommand: resultData.action=${resultData.action}, resultData.dataString=${resultData.dataString}")
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startService(resultCode, resultData)
                } else {
                    Log.e(TAG, "onStartCommand: 参数无效，resultCode=$resultCode (期望=${Activity.RESULT_OK}), resultData=${resultData != null}")
                    // 参数无效时也要保持前台服务运行
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand: 停止服务")
                stopService()
            }
            else -> {
                Log.w(TAG, "onStartCommand: 未知的action: ${intent?.action}")
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        instance = null
        stopService()
        serviceScope.cancel()
    }
    
    /**
     * 启动服务
     */
    private fun startService(resultCode: Int, resultData: Intent) {
        val sessionId = ++projectionSessionId
        Log.d(TAG, "启动服务，resultCode=$resultCode, sessionId=$sessionId")
        try {
            // 清理旧资源。先解绑旧回调再 stop，避免旧回调异步把新会话资源清空。
            val oldProjection = mediaProjection
            val oldCallback = projectionCallback
            projectionCallback = null
            mediaProjection = null

            virtualDisplay?.release()
            imageReader?.close()
            virtualDisplay = null
            imageReader = null

            if (oldProjection != null && oldCallback != null) {
                try {
                    oldProjection.unregisterCallback(oldCallback)
                } catch (e: Exception) {
                    Log.w(TAG, "解绑旧MediaProjection回调失败: ${e.message}")
                }
            }
            try {
                oldProjection?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "停止旧MediaProjection失败: ${e.message}")
            }
            
            // 创建MediaProjection
            if (mediaProjectionManager == null) {
                Log.e(TAG, "MediaProjectionManager为null")
                return
            }
            
            mediaProjection = try {
                mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            } catch (e: Exception) {
                Log.e(TAG, "创建MediaProjection失败: ${e.message}", e)
                e.printStackTrace()
                null
            }
            
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection创建失败，返回null")
                return
            }
            Log.d(TAG, "MediaProjection创建成功")

            val currentProjection = mediaProjection ?: return
            
            // 注册MediaProjection回调（Android 14+ 必需）
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 旧会话/旧实例的异步stop不应影响新会话
                    if (projectionSessionId != sessionId || mediaProjection !== currentProjection) {
                        Log.d(TAG, "忽略过期MediaProjection.onStop: callbackSession=$sessionId, currentSession=$projectionSessionId")
                        return
                    }
                    Log.d(TAG, "MediaProjection已停止，sessionId=$sessionId")
                    // 清理当前会话资源
                    virtualDisplay?.release()
                    imageReader?.close()
                    virtualDisplay = null
                    imageReader = null
                    projectionCallback = null
                    mediaProjection = null
                }
            }
            currentProjection.registerCallback(callback, null)
            projectionCallback = callback
            Log.d(TAG, "MediaProjection回调已注册")
            
            // 获取实际屏幕分辨率
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val realMetrics = android.util.DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                display.getRealMetrics(realMetrics)
            } else {
                @Suppress("DEPRECATION")
                display.getMetrics(realMetrics)
            }
            val screenWidth = realMetrics.widthPixels
            val screenHeight = realMetrics.heightPixels
            Log.d(TAG, "实际屏幕分辨率: ${screenWidth}x${screenHeight}")
            
            // 使用实际屏幕分辨率创建ImageReader（避免黑边）
            imageReader = try {
                ImageReader.newInstance(
                    screenWidth,
                    screenHeight,
                    PixelFormat.RGBA_8888,
                    2
                )
            } catch (e: Exception) {
                Log.e(TAG, "创建ImageReader失败: ${e.message}", e)
                e.printStackTrace()
                null
            }
            
            if (imageReader == null) {
                Log.e(TAG, "ImageReader创建失败，返回null")
                return
            }
            Log.d(TAG, "ImageReader创建成功: ${screenWidth}x${screenHeight}")
            
            // 使用实际屏幕分辨率创建VirtualDisplay（避免黑边）
            val metrics = resources.displayMetrics
            virtualDisplay = try {
                currentProjection.createVirtualDisplay(
                    "ChatScreenshotDisplay",
                    screenWidth,
                    screenHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "创建VirtualDisplay失败: ${e.message}", e)
                e.printStackTrace()
                null
            }
            
            if (virtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay创建失败，返回null")
                // 即使失败也要保持前台服务运行
                return
            }
            Log.d(TAG, "VirtualDisplay创建成功")
            
            // 前台通知已经在 onStartCommand 中显示，这里不需要再次显示
            Log.d(TAG, "服务已启动，所有组件就绪")
        } catch (e: Exception) {
            Log.e(TAG, "启动服务时发生异常: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 停止服务
     */
    private fun stopService() {
        val currentProjection = mediaProjection
        val currentCallback = projectionCallback
        projectionCallback = null
        mediaProjection = null

        virtualDisplay?.release()
        imageReader?.close()

        virtualDisplay = null
        imageReader = null

        if (currentProjection != null && currentCallback != null) {
            try {
                currentProjection.unregisterCallback(currentCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopService: 解绑MediaProjection回调失败: ${e.message}")
            }
        }
        try {
            currentProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stopService: 停止MediaProjection失败: ${e.message}")
        }
        
        stopForeground(true)
        stopSelf()
        
        Log.d(TAG, "服务已停止")
    }
    
    /**
     * 获取截图
     * 使用实际屏幕分辨率截图，不进行缩放
     */
    suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            if (imageReader == null) {
                Log.w(TAG, "imageReader为null")
                return@withContext null
            }
            
            val image = imageReader?.acquireLatestImage() ?: return@withContext null
            
            try {
                val planes = image.planes
                if (planes.isEmpty()) {
                    return@withContext null
                }
                
                val imageWidth = image.width
                val imageHeight = image.height
                Log.d(TAG, "Image实际尺寸: ${imageWidth}x${imageHeight}")
                
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * imageWidth
                
                val actualWidth = if (rowPadding > 0) {
                    imageWidth + rowPadding / pixelStride
                } else {
                    imageWidth
                }
                
                val tempBitmap = Bitmap.createBitmap(
                    actualWidth,
                    imageHeight,
                    Bitmap.Config.ARGB_8888
                )
                
                tempBitmap.copyPixelsFromBuffer(buffer)
                
                val finalBitmap = if (rowPadding > 0 && actualWidth > imageWidth) {
                    val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, imageWidth, imageHeight)
                    tempBitmap.recycle()
                    cropped
                } else {
                    tempBitmap
                }
                
                Log.d(TAG, "最终bitmap尺寸（实际尺寸，未缩放）: ${finalBitmap.width}x${finalBitmap.height}")
                finalBitmap
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图捕获失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 检查服务是否就绪
     */
    fun isReady(): Boolean {
        val ready = imageReader != null && mediaProjection != null && virtualDisplay != null
        Log.d(TAG, "isReady: imageReader=${imageReader != null}, mediaProjection=${mediaProjection != null}, virtualDisplay=${virtualDisplay != null}, ready=$ready")
        return ready
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "聊天截图服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于聊天功能的截图服务"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("聊天截图服务")
            .setContentText("正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}

