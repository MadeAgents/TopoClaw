package com.cloudcontrol.demo

import android.app.*
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

/**
 * 录屏回调接口
 * 注意：不再需要 actionsJsonPath，因为不再记录动作数据
 */
interface RecordingCallback {
    fun onRecordingStarted(videoPath: String)
    fun onRecordingStopped(videoPath: String, screenWidth: Int, screenHeight: Int)
    fun onRecordingError(error: String)
}

/**
 * 屏幕录制服务（前台服务）
 * 用于录制屏幕视频并记录用户动作
 */
class ScreenRecordingService : Service() {
    
    companion object {
        private const val TAG = "ScreenRecordingService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "screen_recording_service_channel"
        
        // 动作常量
        const val ACTION_START = "com.cloudcontrol.demo.ACTION_START_RECORDING"
        const val ACTION_STOP = "com.cloudcontrol.demo.ACTION_STOP_RECORDING"
        
        // 参数键
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        
        // 单例
        @Volatile
        private var instance: ScreenRecordingService? = null
        
        fun getInstance(): ScreenRecordingService? = instance
        
        @Volatile
        private var callback: RecordingCallback? = null
        
        fun setCallback(callback: RecordingCallback?) {
            this.callback = callback
        }
    }
    
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    private var screenDensity = 0
    
    private var videoPath: String? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")
        instance = this
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 获取MediaProjectionManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // 获取屏幕尺寸
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
        
        Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}, DPI: $screenDensity")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        // 立即显示前台通知
        try {
            startForeground(NOTIFICATION_ID, createNotification("正在录制屏幕..."))
            Log.d(TAG, "前台通知已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示前台通知失败: ${e.message}", e)
        }
        
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
                
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startRecording(resultCode, resultData)
                } else {
                    Log.e(TAG, "启动录制失败: resultCode=$resultCode, resultData=${resultData != null}")
                    callback?.onRecordingError("启动录制失败: 无效的权限数据")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务销毁")
        stopRecording()
        instance = null
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕录制服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于录制屏幕视频"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏幕录制中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 开始录制
     */
    private fun startRecording(resultCode: Int, resultData: Intent) {
        try {
            Log.d(TAG, "开始录制屏幕")
            
            // 生成视频文件路径（先设置路径，再初始化MediaRecorder）
            val timestamp = System.currentTimeMillis()
            val videoDir = File(getExternalFilesDir(null), "recordings")
            if (!videoDir.exists()) {
                videoDir.mkdirs()
            }
            videoPath = File(videoDir, "recording_$timestamp.mp4").absolutePath
            
            // 创建MediaProjection
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                Log.e(TAG, "创建MediaProjection失败")
                callback?.onRecordingError("创建MediaProjection失败")
                stopSelf()
                return
            }
            
            // 注册MediaProjection回调（Android新版本要求）
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection已停止")
                    // 注意：这里不调用stopRecording()，避免重复停止
                    // 因为用户主动停止时会调用stopRecording()，如果这里也调用会导致重复
                }
            }, null)
            
            // 初始化MediaRecorder（此时videoPath已设置）
            setupMediaRecorder()
            
            // 创建VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenRecording",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder?.surface,
                null,
                null
            )
            
            // 开始录制（不再需要启动动作记录，因为不再发送 user_example）
            mediaRecorder?.start()
            
            Log.d(TAG, "录制已开始，视频保存路径: $videoPath")
            callback?.onRecordingStarted(videoPath ?: "")
            
            // 更新通知
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification("录制中... 视频: ${File(videoPath ?: "").name}"))
            
        } catch (e: Exception) {
            Log.e(TAG, "开始录制失败: ${e.message}", e)
            callback?.onRecordingError("开始录制失败: ${e.message}")
            stopSelf()
        }
    }
    
    /**
     * 设置MediaRecorder
     */
    private fun setupMediaRecorder() {
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoPath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(screenWidth, screenHeight)
                setVideoEncodingBitRate(5 * 1000 * 1000) // 5 Mbps
                setVideoFrameRate(30)
                
                try {
                    prepare()
                } catch (e: IOException) {
                    Log.e(TAG, "MediaRecorder准备失败: ${e.message}", e)
                    throw e
                }
            }
            
            Log.d(TAG, "MediaRecorder设置完成")
        } catch (e: Exception) {
            Log.e(TAG, "设置MediaRecorder失败: ${e.message}", e)
            mediaRecorder?.release()
            mediaRecorder = null
            throw e
        }
    }
    
    /**
     * 停止录制
     */
    @Volatile
    private var isStopping = false  // 防止重复停止
    
    private fun stopRecording() {
        // 防止重复停止
        if (isStopping) {
            Log.w(TAG, "录制正在停止中，忽略重复调用")
            return
        }
        
        // 检查是否已经停止
        if (mediaRecorder == null && virtualDisplay == null) {
            Log.w(TAG, "录制已经停止，忽略重复调用")
            return
        }
        
        isStopping = true
        
        try {
            Log.d(TAG, "停止录制")
            
            // 停止MediaRecorder（不再需要停止动作记录）
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止MediaRecorder失败: ${e.message}", e)
            }
            
            // 释放VirtualDisplay
            virtualDisplay?.release()
            virtualDisplay = null
            
            // 释放MediaProjection
            mediaProjection?.stop()
            mediaProjection = null
            
            // 释放MediaRecorder
            mediaRecorder?.release()
            mediaRecorder = null
            
            // 注意：不再保存动作数据，因为不再发送 user_example 字段
            
            // 更新通知
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(
                NOTIFICATION_ID,
                createNotification("录制已完成: ${File(videoPath ?: "").name}")
            )
            
            // 保存路径和屏幕尺寸，用于回调（避免在回调中使用可能为null的变量）
            val finalVideoPath = videoPath
            val finalScreenWidth = screenWidth
            val finalScreenHeight = screenHeight
            
            Log.d(TAG, "录制已停止，视频: $finalVideoPath")
            
            // 停止服务
            stopForeground(true)
            stopSelf()
            
            // 在服务停止后通知回调（避免重复调用）
            if (finalVideoPath != null) {
                callback?.onRecordingStopped(finalVideoPath, finalScreenWidth, finalScreenHeight)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "停止录制失败: ${e.message}", e)
            callback?.onRecordingError("停止录制失败: ${e.message}")
        } finally {
            isStopping = false
        }
    }
    
    /**
     * 注意：已移除 saveActionsToJson 方法
     * 因为不再需要保存动作数据，不再发送 user_example 字段
     */
}

