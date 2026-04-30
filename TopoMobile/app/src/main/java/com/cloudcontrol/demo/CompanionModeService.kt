package com.cloudcontrol.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 伴随模式前台服务
 * 用于保持通知栏通知常驻
 */
class CompanionModeService : Service() {
    companion object {
        private const val TAG = "CompanionModeService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "companion_mode_notification_channel"
        private const val CHANNEL_NAME = "伴随模式通知"
        
        // Action IDs
        const val ACTION_BACK_TO_APP = "com.cloudcontrol.demo.ACTION_BACK_TO_APP"
        const val ACTION_START_TASK = "com.cloudcontrol.demo.ACTION_START_TASK"
        const val ACTION_SHOW_OVERLAY = "com.cloudcontrol.demo.ACTION_SHOW_OVERLAY"
        const val ACTION_CLOSE_APP = "com.cloudcontrol.demo.ACTION_CLOSE_APP"
        
        private var instance: CompanionModeService? = null
        
        fun getInstance(): CompanionModeService? = instance
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CompanionModeService onCreate")
        instance = this
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CompanionModeService onStartCommand")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 验证通知渠道状态
        verifyNotificationChannel()
        
        // 创建并显示前台通知
        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "CompanionModeService 前台通知已显示，ID=$NOTIFICATION_ID")
            
            // 延迟验证通知是否真的显示了
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                verifyNotificationDisplayed()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "显示前台通知失败: ${e.message}", e)
            e.printStackTrace()
        }
        
        return START_STICKY // 服务被杀死后自动重启
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CompanionModeService onDestroy")
        instance = null
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 检查渠道是否已存在
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel != null) {
                Log.d(TAG, "通知渠道已存在，重要性=${existingChannel.importance}")
                // 如果渠道已存在但重要性不够，删除并重新创建
                if (existingChannel.importance < NotificationManager.IMPORTANCE_HIGH) {
                    Log.d(TAG, "通知渠道重要性不够，删除并重新创建")
                    notificationManager.deleteNotificationChannel(CHANNEL_ID)
                } else {
                    return // 渠道已存在且重要性足够
                }
            }
            
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "伴随模式通知栏"
                setShowBadge(true) // 显示角标
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "通知渠道已创建，重要性=IMPORTANCE_HIGH")
        }
    }
    
    /**
     * 验证通知渠道状态
     */
    private fun verifyNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (channel != null) {
                Log.d(TAG, "通知渠道状态: 重要性=${channel.importance}, 是否启用=${channel.importance != NotificationManager.IMPORTANCE_NONE}")
                if (channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.e(TAG, "警告：通知渠道被禁用！")
                }
            } else {
                Log.e(TAG, "错误：通知渠道不存在！")
            }
        }
    }
    
    /**
     * 验证通知是否真的显示了
     */
    private fun verifyNotificationDisplayed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val activeNotifications = notificationManager.activeNotifications
                val found = activeNotifications.any { it.id == NOTIFICATION_ID }
                if (found) {
                    Log.d(TAG, "✓ 验证成功：通知确实在通知栏中（ID=$NOTIFICATION_ID）")
                    val notification = activeNotifications.find { it.id == NOTIFICATION_ID }
                    Log.d(TAG, "通知详情: ${notification?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)}")
                } else {
                    Log.e(TAG, "✗ 验证失败：通知不在通知栏中！可能被系统清理了")
                    // 尝试重新显示
                    Log.d(TAG, "尝试重新显示通知...")
                    val notification = createNotification()
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "验证通知时出错: ${e.message}", e)
            }
        }
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        // 创建PendingIntent用于按钮点击
        val backToAppIntent = Intent(ACTION_BACK_TO_APP).apply {
            setPackage(packageName)
        }
        val startTaskIntent = Intent(ACTION_START_TASK).apply {
            setPackage(packageName)
        }
        val showOverlayIntent = Intent(ACTION_SHOW_OVERLAY).apply {
            setPackage(packageName)
        }
        val closeAppIntent = Intent(ACTION_CLOSE_APP).apply {
            setPackage(packageName)
        }
        
        val backToAppPendingIntent = PendingIntent.getBroadcast(
            this, 0, backToAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val startTaskPendingIntent = PendingIntent.getBroadcast(
            this, 1, startTaskIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val showOverlayPendingIntent = PendingIntent.getBroadcast(
            this, 2, showOverlayIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeAppPendingIntent = PendingIntent.getBroadcast(
            this, 3, closeAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // 创建点击通知时打开应用的Intent
        val mainIntent = Intent(this, com.cloudcontrol.demo.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TopoClaw")
            .setContentText("伴随模式已启用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent) // 点击通知打开应用
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // Android 14+
            .addAction(android.R.drawable.ic_menu_revert, "返回应用", backToAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_add, "发起任务", startTaskPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "打开悬浮球", showOverlayPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭应用", closeAppPendingIntent)
            .build()
    }
    
    /**
     * 更新通知（供外部调用）
     */
    fun updateNotification() {
        try {
            val notification = createNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            // 同时更新前台服务通知
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "通知已更新")
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}", e)
        }
    }
}

