package com.cloudcontrol.demo

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.media.projection.MediaProjectionManager
import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.FragmentTransaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * 任务运行指示器悬浮窗管理器
 * 管理圆形悬浮窗的显示和隐藏
 */
object TaskIndicatorOverlayManager {
    private const val TAG = "TaskIndicatorOverlay"
    private const val COMPANION_TARGET_CONVERSATION_ID = "custom_topoclaw"
    private const val NOTIFICATION_ID = 2001
    private const val CHANNEL_ID = "companion_mode_notification_channel"
    private const val CHANNEL_NAME = "伴随模式通知"
    
    // Action IDs（需要public，供BroadcastReceiver使用）
    const val ACTION_BACK_TO_APP = "com.cloudcontrol.demo.ACTION_BACK_TO_APP"
    const val ACTION_START_TASK = "com.cloudcontrol.demo.ACTION_START_TASK"
    const val ACTION_SHOW_OVERLAY = "com.cloudcontrol.demo.ACTION_SHOW_OVERLAY"
    const val ACTION_CLOSE_APP = "com.cloudcontrol.demo.ACTION_CLOSE_APP"
    
    private var overlayView: TaskIndicatorOverlayView? = null
    private var menuView: TaskIndicatorMenuView? = null
    private var menuBackgroundView: android.view.View? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var isMenuShowing = false
    private var isNotificationBarShowing = false
    private var isCompanionMode = false
    private var isMinimized = false // 是否已缩小
    private var originalSize = 0 // 原始大小
    private var context: Context? = null
    private var wasOverlayShowingBeforeRecording = false // 录制前悬浮球是否显示
    private var notificationKeepAliveHandler: android.os.Handler? = null
    private var notificationKeepAliveRunnable: Runnable? = null
    
    // 录制相关
    private var isRecording = false
    private var recordingJob: Job? = null
    private var recordingScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingRecordingResultCode: Int? = null
    private var pendingRecordingData: Intent? = null
    
    // 无障碍快捷方式触发标志
    private var isFromAccessibilityShortcut = false
    
    // 自动缩小相关变量
    private var lastClickTime: Long = 0L // 最后一次点击时间
    private var autoMinimizeJob: Job? = null // 自动缩小检查的协程任务
    private val autoMinimizeScope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // 自动缩小协程作用域
    private const val AUTO_MINIMIZE_DELAY_MS = 60_000L // 1分钟（60秒）
    private const val AUTO_MINIMIZE_CHECK_INTERVAL_MS = 10_000L // 检查间隔：10秒
    
    // 顶部触发区域阈值（dp转px）
    private fun getTopTriggerThreshold(context: Context): Int {
        return (60 * context.resources.displayMetrics.density).toInt()
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel(context: Context) {
        try {
            Log.d(TAG, "createNotificationChannel: 开始创建通知渠道，SDK版本=${Build.VERSION.SDK_INT}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 对于ColorOS系统，使用IMPORTANCE_HIGH确保通知能够显示且不被清理
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH // 使用HIGH重要性，防止被ColorOS清理
                ).apply {
                    description = "伴随模式通知栏"
                    setShowBadge(false)
                    // 设置为公开可见
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    // 不允许绕过勿扰模式
                    setBypassDnd(false)
                    // 允许声音和振动（虽然我们不会触发，但有助于保持通知）
                    enableVibration(false)
                    enableLights(false)
                    // 设置声音为null（静音）
                    setSound(null, null)
                }
                Log.d(TAG, "createNotificationChannel: 通知渠道对象已创建")
                
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (notificationManager == null) {
                    Log.e(TAG, "createNotificationChannel: 无法获取NotificationManager")
                    return
                }
                
                Log.d(TAG, "createNotificationChannel: 准备调用createNotificationChannel")
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "createNotificationChannel: 通知渠道创建成功")
            } else {
                Log.d(TAG, "createNotificationChannel: Android 8.0以下，不需要创建通知渠道")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createNotificationChannel: 创建通知渠道失败: ${e.message}", e)
            e.printStackTrace()
            // 不抛出异常，允许继续执行（Android 8.0以下不需要渠道）
        }
    }
    
    /**
     * 创建通知对象
     */
    private fun createNotification(context: Context): Notification {
        try {
            Log.d(TAG, "createNotification: 开始创建通知对象")
            // 创建PendingIntent用于按钮点击
            val backToAppIntent = Intent(ACTION_BACK_TO_APP).apply {
                setPackage(context.packageName)
            }
            val startTaskIntent = Intent(ACTION_START_TASK).apply {
                setPackage(context.packageName)
            }
            val showOverlayIntent = Intent(ACTION_SHOW_OVERLAY).apply {
                setPackage(context.packageName)
            }
            val closeAppIntent = Intent(ACTION_CLOSE_APP).apply {
                setPackage(context.packageName)
            }
            
            Log.d(TAG, "createNotification: Intent已创建")
            
            val backToAppPendingIntent = PendingIntent.getBroadcast(
                context, 0, backToAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val startTaskPendingIntent = PendingIntent.getBroadcast(
                context, 1, startTaskIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val showOverlayPendingIntent = PendingIntent.getBroadcast(
                context, 2, showOverlayIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val closeAppPendingIntent = PendingIntent.getBroadcast(
                context, 3, closeAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            Log.d(TAG, "createNotification: PendingIntent已创建")
            
            Log.d(TAG, "createNotification: 开始构建Notification")
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("TopoClaw")
                .setContentText("伴随模式已启用")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true) // 常驻通知，无法滑动删除
                .setPriority(NotificationCompat.PRIORITY_HIGH) // 使用HIGH优先级，防止被ColorOS清理
                .setShowWhen(false)
                .setAutoCancel(false) // 不自动取消
                .setCategory(NotificationCompat.CATEGORY_SERVICE) // 设置为服务类别
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 公开可见
                .setSilent(true) // 静音，避免打扰
                .addAction(android.R.drawable.ic_menu_revert, "返回应用", backToAppPendingIntent)
                .addAction(android.R.drawable.ic_menu_add, "发起任务", startTaskPendingIntent)
                .addAction(android.R.drawable.ic_menu_view, "打开悬浮球", showOverlayPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭应用", closeAppPendingIntent)
                .build()
            
            Log.d(TAG, "createNotification: 通知对象创建成功")
            return notification
        } catch (e: Exception) {
            Log.e(TAG, "createNotification: 创建通知对象失败: ${e.message}", e)
            e.printStackTrace()
            // 返回一个简单的通知，避免崩溃
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("TopoClaw")
                .setContentText("伴随模式已启用")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(true)
                .build()
        }
    }
    
    /**
     * 初始化管理器
     */
    fun initialize(context: Context): Boolean {
        if (windowManager != null) {
            return true
        }
        
        // 检查权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "没有悬浮窗权限，无法显示任务指示器")
                return false
            }
        }
        
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return windowManager != null
    }
    
    /**
     * 检查是否开启了伴随模式
     */
    fun isCompanionModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("companion_mode_enabled", true) // 默认true（打开）
    }
    
    /**
     * 显示伴随模式悬浮窗（一直显示，即使没有任务）
     */
    fun showCompanionMode(context: Context) {
        // 如果正在从悬浮球录制切换，不执行任何操作（避免触发TopoClaw）
        val mainActivity = context as? MainActivity
        if (mainActivity?.isSwitchingFromOverlayRecording == true) {
            Log.d(TAG, "showCompanionMode: 正在从悬浮球录制切换，跳过显示伴随模式悬浮球")
            return
        }
        
        isCompanionMode = true
        // 如果通知栏正在显示，隐藏通知栏（用户点击"打开悬浮球"时）
        if (isNotificationBarShowing) {
            hideNotificationBar()
        }
        
        // 重置点击时间，启动自动缩小检查
        lastClickTime = System.currentTimeMillis()
        startAutoMinimizeCheck(context)
        
        // 如果悬浮球已经在显示，更新其状态
        if (isShowing && overlayView != null) {
            // 检查任务运行状态，如果任务正在运行则显示转圈圈
            val isTaskRunning = mainActivity?.getTaskRunningStatus() ?: false
            updateOverlayForTaskStatus(isTaskRunning)
            // 刷新窗口参数，确保触摸事件能够正常处理（解决点击失效问题）
            refreshOverlayWindow()
        } else {
            show(context)
            // 显示后也要检查任务运行状态并更新点击事件
            val isTaskRunning = mainActivity?.getTaskRunningStatus() ?: false
            updateOverlayForTaskStatus(isTaskRunning)
        }
    }
    
    /**
     * 显示任务指示器悬浮窗
     * 注意：此方法用于任务运行模式，会自动设置 isCompanionMode = false
     */
    fun show(context: Context) {
        // 从应用内发起任务时，确保是任务运行模式（不是伴随模式）
        val wasCompanionMode = isCompanionMode
        isCompanionMode = false
        Log.d(TAG, "show: 设置 isCompanionMode = false (之前是: $wasCompanionMode)")
        
        // 停止自动缩小检查（任务运行模式下不需要）
        stopAutoMinimizeCheck()
        
        if (isShowing) {
            Log.d(TAG, "show: 悬浮球已显示，更新点击事件和状态")
            // 即使悬浮球已经显示，也要更新点击事件（因为模式可能改变了）
            this.context = context
            updateClickListener(context)
            // 更新转圈圈显示状态：先停止再启动，确保状态正确
            overlayView?.stopAnimation() // 先停止，确保清理状态
            overlayView?.setShowArc(true)
            overlayView?.startAnimation() // 重新启动动画
            // 刷新窗口参数，确保触摸事件能够正常处理（解决点击失效问题）
            refreshOverlayWindow()
            return
        }
        
        Log.d(TAG, "show: 开始显示悬浮球（任务运行模式）")
        
        if (!initialize(context)) {
            Log.w(TAG, "初始化失败，无法显示任务指示器")
            return
        }
        
        val wm = windowManager ?: return
        this.context = context
        
        try {
            // 创建悬浮窗View
            overlayView = TaskIndicatorOverlayView(context).apply {
                Log.d(TAG, "show: 创建悬浮窗View，准备设置点击事件")
                // 设置点击事件：根据模式和任务状态动态决定行为
                updateClickListener(context)
                
                // 长按事件：伴随模式下回到应用（与上滑到顶部一致）
                setOnLongClickListener {
                    // 记录长按时间（长按也算交互）
                    lastClickTime = System.currentTimeMillis()
                    // 如果已缩小，恢复大小
                    if (isMinimized) {
                        restoreSizeIfMinimizedWithAnimation(context)
                    }
                    
                    // 快速自检权限：如果没有权限，自动跳转到权限设置页面
                    if (!checkAndRequestOverlayPermission(context)) {
                        Log.d(TAG, "权限缺失，已跳转到设置页面，跳过长按处理")
                        return@setOnLongClickListener
                    }
                    
                    if (isCompanionMode) {
                        Log.d(TAG, "伴随模式下长按悬浮球，执行回到应用")
                        handleBackToAppFromOverlay(context)
                    }
                }
                
                // 设置双击事件：双击切换大小
                setOnDoubleClickListener {
                    // 记录点击时间（双击也算点击）
                    lastClickTime = System.currentTimeMillis()
                    toggleSize(context)
                }
                
                // 伴随模式下不显示转圈圈效果
                setShowArc(!isCompanionMode)
            }
            
            // 设置WindowManager参数
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val overlaySize = (45 * displayMetrics.density).toInt() // 45dp
            originalSize = overlaySize // 保存原始大小
            val params = WindowManager.LayoutParams().apply {
                width = if (isMinimized) overlaySize / 4 else overlaySize
                height = if (isMinimized) overlaySize / 4 else overlaySize
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                gravity = Gravity.START or Gravity.TOP // 使用绝对坐标定位
                x = screenWidth - overlaySize - (16 * displayMetrics.density).toInt() // 距离右边缘16dp
                y = screenHeight / 2 - overlaySize / 2 // 垂直居中
            }
            
            overlayParams = params
            
            // 设置拖拽位置更新监听器
            overlayView?.setOnPositionUpdateListener { newX, newY ->
                updateOverlayPosition(newX, newY, displayMetrics)
            }
            
            // 设置拖拽开始监听器（重置点击时间，但不恢复大小）
            overlayView?.setOnDragStartListener {
                // 记录拖拽时间（拖拽也算交互，重置计时器）
                lastClickTime = System.currentTimeMillis()
                Log.d(TAG, "拖拽开始，重置点击时间")
            }
            
            // 设置拖拽结束监听器（自动靠边）
            overlayView?.setOnDragEndListener {
                // 如果不在顶部区域，才自动靠边
                val params = overlayParams
                if (params != null) {
                    val topThreshold = getTopTriggerThreshold(context)
                    if (params.y >= topThreshold) {
                        autoSnapToEdge(displayMetrics)
                    }
                } else {
                    autoSnapToEdge(displayMetrics)
                }
            }
            
            // 设置拖拽结束在顶部时的监听器（自动回到应用）
            overlayView?.setOnDragEndAtTopListener {
                Log.d(TAG, "onDragEndAtTopListener被触发，检测到拖拽到顶部，准备回到应用")
                handleBackToAppFromOverlay(context)
            }
            
            // 设置拖拽结束在底部时的监听器（kill应用）
            overlayView?.setOnDragEndAtBottomListener {
                Log.d(TAG, "onDragEndAtBottomListener被触发，检测到拖拽到底部，准备kill应用")
                try {
                    // 清理所有资源
                    cleanup()
                    // 获取MainActivity并kill应用
                    val mainActivity = context as? MainActivity
                    if (mainActivity != null) {
                        Log.d(TAG, "正在kill应用")
                        mainActivity.finish()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } else {
                        Log.e(TAG, "无法获取MainActivity，无法kill应用")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "kill应用时出错: ${e.message}", e)
                    e.printStackTrace()
                }
            }
            
            // 在添加窗口前再次检查权限（双重保险）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.w(TAG, "添加窗口前检测到权限缺失，无法显示悬浮球")
                    overlayView = null
                    return
                }
            }
            
            // 添加到WindowManager
            wm.addView(overlayView, params)
            
            // 开始动画（仅在非伴随模式下）
            if (!isCompanionMode) {
                overlayView?.startAnimation()
            }
            
            isShowing = true
            Log.d(TAG, "任务指示器悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示任务指示器失败: ${e.message}", e)
            overlayView = null
        }
    }
    
    /**
     * 处理悬浮球触发的回到应用动作（长按和上滑到顶部共用）
     */
    private fun handleBackToAppFromOverlay(context: Context) {
        try {
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                val success = mainActivity.bringAppToForeground()
                if (success) {
                    Log.d(TAG, "已成功回到TopoClaw应用")
                    // 延迟一下确保应用已切换，然后将悬浮球移回右侧居中位置
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        moveToRightCenter()
                    }, 300)
                } else {
                    Log.w(TAG, "回到应用失败")
                }
            } else {
                Log.e(TAG, "无法获取MainActivity，无法回到应用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "回到应用时出错: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 更新悬浮窗位置（用于拖拽）
     */
    private fun updateOverlayPosition(newX: Float, newY: Float, displayMetrics: android.util.DisplayMetrics) {
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        val ctx = context ?: return
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val currentSize = params.width // 使用当前实际大小
        
        // 边界检测：确保悬浮球不会拖出屏幕
        val minX = 0f
        val maxX = (screenWidth - currentSize).toFloat()
        val minY = 0f
        val maxY = (screenHeight - currentSize).toFloat()
        
        var clampedX = newX.coerceIn(minX, maxX)
        var clampedY = newY.coerceIn(minY, maxY)
        
        // 在伴随模式下，检测是否拖拽到顶部（仅吸附，不触发显示通知栏）
        if (isCompanionMode && !isNotificationBarShowing) {
            val topThreshold = getTopTriggerThreshold(ctx)
            if (clampedY < topThreshold) {
                // 拖拽到顶部区域，吸附到顶部并居中（视觉反馈）
                clampedY = 0f
                clampedX = (screenWidth / 2f - currentSize / 2f)
                Log.d(TAG, "拖拽到顶部区域，Y=$clampedY, 阈值=$topThreshold")
            }
        }
        
        params.x = clampedX.toInt()
        params.y = clampedY.toInt()
        
        try {
            wm.updateViewLayout(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "更新悬浮窗位置失败: ${e.message}", e)
        }
    }
    
    /**
     * 刷新悬浮窗窗口参数，确保触摸事件能够正常处理
     * 用于解决在某些情况下点击失效的问题
     */
    private fun refreshOverlayWindow() {
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        
        try {
            // 通过更新窗口参数来刷新窗口状态，确保触摸事件能够正常处理
            // 这个方法在应用回到前台时调用，可以解决点击失效的问题
            wm.updateViewLayout(view, params)
            Log.d(TAG, "刷新悬浮窗窗口参数成功")
        } catch (e: Exception) {
            Log.e(TAG, "刷新悬浮窗窗口参数失败: ${e.message}", e)
            // 如果更新失败，可能是窗口已被移除，尝试重新添加
            try {
                if (isShowing && overlayView != null && overlayParams != null) {
                    Log.w(TAG, "窗口更新失败，尝试重新添加窗口")
                    wm.removeView(view)
                    wm.addView(view, params)
                    Log.d(TAG, "重新添加悬浮窗成功")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "重新添加悬浮窗失败: ${e2.message}", e2)
                // 如果重新添加也失败，重置状态
                isShowing = false
                overlayView = null
            }
        }
    }
    
    /**
     * 切换悬浮球大小（缩小到1/2或恢复原样）- 带动画
     */
    private fun toggleSize(context: Context) {
        if (isMinimized) {
            restoreSizeIfMinimizedWithAnimation(context)
        } else {
            minimizeSizeWithAnimation(context)
        }
    }
    
    /**
     * 缩小悬浮球（带动画）- 如果未缩小则缩小
     */
    private fun minimizeSizeWithAnimation(context: Context) {
        if (isMinimized) {
            Log.d(TAG, "悬浮球已缩小，跳过")
            return
        }
        
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        
        try {
            val targetSize = originalSize / 2
            
            // 获取当前中心位置
            val currentCenterX = params.x + params.width / 2
            val currentCenterY = params.y + params.height / 2
            
            // 计算目标位置，保持中心点不变
            val targetX = currentCenterX - targetSize / 2
            val targetY = currentCenterY - targetSize / 2
            
            // 边界检测
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val minX = 0
            val maxX = screenWidth - targetSize
            val minY = 0
            val maxY = screenHeight - targetSize
            
            val clampedTargetX = targetX.coerceIn(minX, maxX)
            val clampedTargetY = targetY.coerceIn(minY, maxY)
            
            // 使用动画平滑过渡
            val currentSize = params.width.toFloat()
            val currentX = params.x.toFloat()
            val currentY = params.y.toFloat()
            
            val sizeAnimator = ValueAnimator.ofFloat(currentSize, targetSize.toFloat()).apply {
                duration = 300 // 300ms 动画时长
                addUpdateListener { animation ->
                    val animatedSize = (animation.animatedValue as Float).toInt()
                    val progress = (animatedSize - currentSize) / (targetSize - currentSize)
                    
                    // 计算当前位置（保持中心点）
                    val animatedX = currentX + (clampedTargetX - currentX) * progress
                    val animatedY = currentY + (clampedTargetY - currentY) * progress
                    
                    params.width = animatedSize
                    params.height = animatedSize
                    params.x = animatedX.toInt()
                    params.y = animatedY.toInt()
                    
                    try {
                        wm.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "缩小动画更新位置失败: ${e.message}", e)
                    }
                }
            }
            
            sizeAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isMinimized = true
                    overlayView?.invalidate()
                    Log.d(TAG, "悬浮球缩小动画完成，大小=$targetSize")
                }
            })
            
            sizeAnimator.start()
        } catch (e: Exception) {
            Log.e(TAG, "缩小悬浮球失败: ${e.message}", e)
        }
    }
    
    /**
     * 恢复悬浮球大小（带动画）- 如果已缩小则恢复
     */
    private fun restoreSizeIfMinimizedWithAnimation(context: Context) {
        if (!isMinimized) {
            Log.d(TAG, "悬浮球未缩小，跳过恢复")
            return
        }
        
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        
        try {
            val targetSize = originalSize
            
            // 获取当前中心位置
            val currentCenterX = params.x + params.width / 2
            val currentCenterY = params.y + params.height / 2
            
            // 计算目标位置，保持中心点不变
            val targetX = currentCenterX - targetSize / 2
            val targetY = currentCenterY - targetSize / 2
            
            // 边界检测
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val minX = 0
            val maxX = screenWidth - targetSize
            val minY = 0
            val maxY = screenHeight - targetSize
            
            val clampedTargetX = targetX.coerceIn(minX, maxX)
            val clampedTargetY = targetY.coerceIn(minY, maxY)
            
            // 使用动画平滑过渡
            val currentSize = params.width.toFloat()
            val currentX = params.x.toFloat()
            val currentY = params.y.toFloat()
            
            val sizeAnimator = ValueAnimator.ofFloat(currentSize, targetSize.toFloat()).apply {
                duration = 300 // 300ms 动画时长
                addUpdateListener { animation ->
                    val animatedSize = (animation.animatedValue as Float).toInt()
                    val progress = (animatedSize - currentSize) / (targetSize - currentSize)
                    
                    // 计算当前位置（保持中心点）
                    val animatedX = currentX + (clampedTargetX - currentX) * progress
                    val animatedY = currentY + (clampedTargetY - currentY) * progress
                    
                    params.width = animatedSize
                    params.height = animatedSize
                    params.x = animatedX.toInt()
                    params.y = animatedY.toInt()
                    
                    try {
                        wm.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "恢复动画更新位置失败: ${e.message}", e)
                    }
                }
            }
            
            sizeAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    isMinimized = false
                    overlayView?.invalidate()
                    Log.d(TAG, "悬浮球恢复动画完成，大小=$targetSize")
                }
            })
            
            sizeAnimator.start()
        } catch (e: Exception) {
            Log.e(TAG, "恢复悬浮球大小失败: ${e.message}", e)
        }
    }
    
    /**
     * 自动靠边：拖拽结束后自动移动到最近的左边缘或右边缘
     */
    private fun autoSnapToEdge(displayMetrics: android.util.DisplayMetrics) {
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        
        val screenWidth = displayMetrics.widthPixels
        val currentSize = params.width // 使用当前实际大小
        val margin = (16 * displayMetrics.density).toInt() // 距离边缘的间距
        
        val currentX = params.x.toFloat()
        val currentY = params.y.toFloat()
        
        // 判断更靠近左边还是右边
        val screenCenterX = screenWidth / 2f
        val targetX = if (currentX < screenCenterX) {
            // 靠近左边，移动到左边缘
            margin.toFloat()
        } else {
            // 靠近右边，移动到右边缘
            (screenWidth - currentSize - margin).toFloat()
        }
        
        // 使用动画平滑移动到目标位置
        val animator = ValueAnimator.ofFloat(currentX, targetX).apply {
            duration = 300 // 300ms 动画时长
            addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Float
                params.x = animatedValue.toInt()
                try {
                    wm.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "自动靠边动画更新位置失败: ${e.message}", e)
                }
            }
        }
        animator.start()
    }
    
    /**
     * 将悬浮球移动到屏幕右侧居中位置
     */
    private fun moveToRightCenter() {
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        val ctx = context ?: return
        
        val displayMetrics = ctx.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val currentSize = params.width // 使用当前实际大小
        val margin = (16 * displayMetrics.density).toInt() // 距离边缘的间距
        
        // 目标位置：右侧居中
        val targetX = (screenWidth - currentSize - margin).toFloat()
        val targetY = (screenHeight / 2f - currentSize / 2f).toFloat()
        
        val currentX = params.x.toFloat()
        val currentY = params.y.toFloat()
        
        // 使用动画平滑移动到目标位置（使用进度值 0f 到 1f）
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300 // 300ms 动画时长
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                // 根据进度计算当前位置
                val animatedX = currentX + (targetX - currentX) * progress
                val animatedY = currentY + (targetY - currentY) * progress
                
                params.x = animatedX.toInt()
                params.y = animatedY.toInt()
                try {
                    wm.updateViewLayout(overlayView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "移动到右侧居中位置失败: ${e.message}", e)
                }
            }
        }
        animator.start()
        
        Log.d(TAG, "悬浮球开始移动到右侧居中位置: ($currentX, $currentY) -> ($targetX, $targetY)")
    }
    
    /**
     * 获取任务指示器悬浮窗的位置信息
     * @return Pair<Int, Int>? 返回 (x, y) 坐标，如果不存在则返回 null
     */
    fun getOverlayPosition(): Pair<Int, Int>? {
        val params = overlayParams ?: return null
        if (!isShowing) return null
        return Pair(params.x, params.y)
    }
    
    /**
     * 检查任务指示器是否正在显示
     */
    fun isOverlayShowing(): Boolean {
        return isShowing && overlayView != null
    }
    
    /**
     * 隐藏任务指示器悬浮窗
     */
    fun hide() {
        // 如果是伴随模式，不隐藏
        if (isCompanionMode) {
            Log.d(TAG, "伴随模式已开启，不隐藏悬浮窗")
            return
        }
        
        if (!isShowing) {
            return
        }
        
        // 停止自动缩小检查
        stopAutoMinimizeCheck()
        
        val wm = windowManager ?: return
        val view = overlayView ?: return
        
        try {
            // 停止动画
            view.stopAnimation()
            
            // 从WindowManager移除
            wm.removeView(view)
            
            overlayView = null
            overlayParams = null
            isShowing = false
            isMinimized = false // 重置缩小状态
            originalSize = 0 // 重置原始大小
            lastClickTime = 0L // 重置点击时间
            Log.d(TAG, "任务指示器悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏任务指示器失败: ${e.message}", e)
        }
    }
    
    /**
     * 强制隐藏悬浮窗（即使伴随模式开启）
     */
    fun forceHide() {
        isCompanionMode = false
        hide()
    }
    
    /**
     * 显示菜单
     * 统一使用垂直菜单布局
     */
    private fun showMenu(context: Context) {
        if (isMenuShowing) {
            return
        }
        
        Log.d(TAG, "showMenu: 显示垂直菜单, isCompanionMode=$isCompanionMode")
        showVerticalMenu(context)
    }
    
    /**
     * 显示垂直菜单
     */
    private fun showVerticalMenu(context: Context) {
        val wm = windowManager ?: return
        val overlay = overlayView ?: return
        
        try {
            // 获取悬浮窗的位置和尺寸
            val params = this.overlayParams ?: overlay.layoutParams as? WindowManager.LayoutParams
            val overlayX = params?.x ?: 0
            val overlayY = params?.y ?: 0
            val overlayWidth = overlay.width
            val overlayHeight = overlay.height
            
            val displayMetrics = context.resources.displayMetrics
            val menuWidth = (120 * displayMetrics.density).toInt() // 120dp（扩大5号，从80dp增加到120dp）
            
            // 获取任务状态
            val mainActivity = context as? MainActivity
            val isPaused = mainActivity?.isTaskPaused() ?: false
            val isRunning = mainActivity?.getTaskRunningStatus() ?: false
            
            // 根据任务状态计算菜单项数量，动态调整菜单高度
            val menuItemCount = if (isPaused) {
                3 // 继续任务、返回应用、结束任务
            } else {
                // 任务未暂停时：暂停任务（如果运行中）、返回应用、补充任务（如果运行中）、结束任务
                var count = 1 // 返回应用
                if (isRunning) {
                    count += 2 // 暂停任务、补充任务
                }
                count += 1 // 结束任务
                count
            }
            // 每个菜单项48dp高度（增加高度以容纳更好的视觉效果），加上padding和阴影
            val itemHeight = 48f * displayMetrics.density
            val padding = 8f * displayMetrics.density
            val shadowOffset = 4f * displayMetrics.density
            val menuHeight = (itemHeight * menuItemCount + padding * 2 + shadowOffset).toInt()
            
            // 计算悬浮窗的实际位置（使用绝对坐标）
            // overlayY 是相对于屏幕顶部的绝对位置
            val overlayCenterY = overlayY + overlayHeight / 2
            val overlayBottom = overlayY + overlayHeight
            
            // 创建菜单视图
            menuView = TaskIndicatorMenuView(context).apply {
                layoutParams = ViewGroup.LayoutParams(menuWidth, menuHeight)
                
                // 根据任务状态更新菜单项
                updateMenuItems(isPaused, isRunning)
                
                // 设置菜单项点击事件
                onItemClickListener = { index ->
                    hideMenu() // 先关闭菜单
                    handleMenuItemClick(mainActivity, index, isPaused, isRunning)
                }
            }
            
            // 创建一个透明的背景视图来捕获外部点击
            // 注意：需要让悬浮球区域的触摸事件能够穿透，避免遮挡悬浮球点击
            val backgroundView = object : android.view.View(context) {
                override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                    // 检查触摸位置是否在悬浮球区域内
                    val touchX = event.rawX
                    val touchY = event.rawY
                    
                    // 获取悬浮球的位置和大小
                    val params = overlayParams
                    if (params != null) {
                        val overlayX = params.x.toFloat()
                        val overlayY = params.y.toFloat()
                        val overlayWidth = overlayView?.width?.toFloat() ?: 0f
                        val overlayHeight = overlayView?.height?.toFloat() ?: 0f
                        
                        // 检查触摸位置是否在悬浮球区域内（扩大一些区域，确保点击有效）
                        val padding = 10f * context.resources.displayMetrics.density // 10dp的容差
                        val isInOverlayArea = touchX >= overlayX - padding &&
                                            touchX <= overlayX + overlayWidth + padding &&
                                            touchY >= overlayY - padding &&
                                            touchY <= overlayY + overlayHeight + padding
                        
                        if (isInOverlayArea) {
                            // 触摸位置在悬浮球区域，不拦截事件，让悬浮球处理
                            Log.d(TAG, "背景视图：触摸位置在悬浮球区域，不拦截事件")
                            return false
                        }
                    }
                    
                    // 触摸位置不在悬浮球区域，拦截事件并关闭菜单
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        hideMenu()
                    }
                    return true
                }
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            
            val backgroundParams = WindowManager.LayoutParams().apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
            
            // 先添加背景视图
            wm.addView(backgroundView, backgroundParams)
            menuBackgroundView = backgroundView
            
            // 设置菜单的WindowManager参数
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // 计算菜单位置：悬浮球下方，水平居中
            val overlayCenterX = overlayX + overlayWidth / 2
            val menuX = overlayCenterX - menuWidth / 2 // 菜单左边缘位置（使菜单在悬浮球下方居中）
            val menuY = overlayBottom + (8 * displayMetrics.density).toInt() // 悬浮窗下方8dp
            
            // 边界检测：确保菜单不会超出屏幕
            val clampedMenuX = menuX.coerceIn(0, screenWidth - menuWidth)
            val clampedMenuY = menuY.coerceIn(0, screenHeight - menuHeight)
            
            val menuParams = WindowManager.LayoutParams().apply {
                width = menuWidth
                height = menuHeight
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                gravity = Gravity.START or Gravity.TOP // 使用绝对坐标系统
                x = clampedMenuX // 菜单左边缘的绝对X坐标
                y = clampedMenuY // 菜单顶部的绝对Y坐标
            }
            
            Log.d(TAG, "菜单位置计算：悬浮球中心X=$overlayCenterX, 菜单X=$clampedMenuX, 菜单Y=$clampedMenuY, 悬浮球位置=($overlayX, $overlayY)")
            
            // 添加菜单到WindowManager
            wm.addView(menuView, menuParams)
            
            isMenuShowing = true
            Log.d(TAG, "任务指示器菜单已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示任务指示器菜单失败: ${e.message}", e)
            menuView = null
        }
    }
    
    /**
     * 隐藏菜单
     */
    private fun hideMenu() {
        if (!isMenuShowing) {
            return
        }
        
        val wm = windowManager ?: return
        
        try {
            // 移除垂直菜单
            menuView?.let { menu ->
                try {
                    wm.removeView(menu)
                } catch (e: Exception) {
                    Log.e(TAG, "移除垂直菜单视图失败: ${e.message}", e)
                }
            }
            menuView = null
            
            // 移除背景视图
            removeBackgroundView(wm)
            
            isMenuShowing = false
            Log.d(TAG, "垂直菜单已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏任务指示器菜单失败: ${e.message}", e)
        }
    }
    
    /**
     * 移除背景视图
     */
    private fun removeBackgroundView(wm: WindowManager) {
        try {
            menuBackgroundView?.let { bg ->
                try {
                    wm.removeView(bg)
                } catch (e: Exception) {
                    Log.e(TAG, "移除菜单背景视图失败: ${e.message}", e)
                }
            }
            menuBackgroundView = null
        } catch (e: Exception) {
            Log.e(TAG, "移除背景视图失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理菜单项点击事件
     */
    private fun handleMenuItemClick(
        mainActivity: MainActivity?,
        index: Int,
        isPaused: Boolean,
        isRunning: Boolean
    ) {
        if (isPaused) {
            // 任务已暂停时的菜单：继续任务、返回应用、结束任务
            // 索引顺序：0=继续任务，1=返回应用，2=结束任务
            when (index) {
                0 -> {
                    // 继续任务
                    Log.d(TAG, "用户选择：继续任务")
                    mainActivity?.toggleTaskPauseFromOverlay()
                }
                1 -> {
                    // 返回应用
                    Log.d(TAG, "用户选择：返回应用")
                    mainActivity?.bringAppToForeground()
                }
                2 -> {
                    // 结束任务
                    Log.d(TAG, "用户选择：结束任务")
                    mainActivity?.stopTaskFromOverlay()
                }
            }
        } else {
            // 任务未暂停时的菜单：暂停任务（如果运行中）、返回应用、补充任务（如果运行中）、结束任务
            // 索引顺序：
            // - 如果运行中：0=暂停任务，1=返回应用，2=补充任务，3=结束任务
            // - 如果未运行：0=返回应用，1=结束任务
            if (isRunning) {
                when (index) {
                    0 -> {
                        // 暂停任务
                        Log.d(TAG, "用户选择：暂停任务")
                        mainActivity?.toggleTaskPauseFromOverlay()
                    }
                    1 -> {
                        // 返回应用
                        Log.d(TAG, "用户选择：返回应用")
                        mainActivity?.bringAppToForeground()
                    }
                    2 -> {
                        // 补充任务
                        Log.d(TAG, "用户选择：补充任务")
                        mainActivity?.handleTaskSupplementFromOverlay()
                    }
                    3 -> {
                        // 结束任务
                        Log.d(TAG, "用户选择：结束任务")
                        mainActivity?.stopTaskFromOverlay()
                    }
                }
            } else {
                when (index) {
                    0 -> {
                        // 返回应用
                        Log.d(TAG, "用户选择：返回应用")
                        mainActivity?.bringAppToForeground()
                    }
                    1 -> {
                        // 结束任务
                        Log.d(TAG, "用户选择：结束任务")
                        mainActivity?.stopTaskFromOverlay()
                    }
                }
            }
        }
    }
    
    /**
     * 显示输入弹窗（伴随模式）
     */
    // 预设查询列表（与ChatFragment保持一致）
    private val PRESET_QUERIES = listOf(
        "小红书搜索gui智能体，浏览2个内容并总结给我",
        "打开TeamTalk，到班车模块看一下从我的位置到滨海湾的最近的班车是几点",
        "这是哪里，导航过去",
        "图中商品是啥，帮我去淘宝拼多多搜同款",
        "问微信好友Hh源晚上想吃啥，然后执行他的命令",
        "到12306分别搜索明天深圳到成都的高铁票和机票，然后对比一下价格和耗时谁有优势",
        "抖音搜索OPPO周易保，关注并评论他的置顶视频\"太帅了吧\"，最后统计一下他的粉丝数",
        "打开小红书，搜索上海旅行攻略",
        "在12306找到临时身份证"
    )
    
    // 保存PopupWindow引用
    private var queryPopupWindow: android.widget.PopupWindow? = null
    
    /**
     * 获取用户查询历史（按时间排序，最新的在最下面）
     */
    private fun getUserQueryHistory(context: Context): List<String> {
        val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("user_query_history", null)
        // 由于使用Set保存会丢失顺序，我们需要从ChatFragment获取顺序
        // 但为了兼容性，我们直接使用Set转List，然后在显示时反转顺序
        // 注意：ChatFragment中userQueryHistory是add(0, query)，所以最新的在最前面
        // 用户要求最新的在最下面，所以需要反转
        val historyList = historySet?.toList() ?: emptyList()
        // 反转列表，使最新的在最下面
        return historyList.reversed()
    }
    
    /**
     * 显示预设查询菜单（在弹窗中）
     */
    private fun showPresetQueryMenuInDialog(context: Context, anchor: android.view.View, etInput: com.google.android.material.textfield.TextInputEditText) {
        // 如果已有下拉菜单打开，先关闭
        queryPopupWindow?.dismiss()
        
        // 合并预设查询和用户历史查询
        // 预设查询在最上面，用户历史查询在最下面（最新的在最下面）
        val allQueries = mutableListOf<String>()
        allQueries.addAll(PRESET_QUERIES)
        allQueries.addAll(getUserQueryHistory(context))
        
        if (allQueries.isEmpty()) {
            android.widget.Toast.makeText(context, "暂无查询记录", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // 创建ListView用于显示查询列表
        val listView = android.widget.ListView(context)
        
        // 创建适配器
        val adapter = android.widget.ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            allQueries
        )
        listView.adapter = adapter
        
        // 设置列表项点击监听
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position >= 0 && position < allQueries.size) {
                val selectedQuery = allQueries[position]
                // 将选中的查询填入输入框
                etInput.setText(selectedQuery)
                // 将光标移到末尾
                etInput.setSelection(selectedQuery.length)
                // 关闭下拉菜单
                queryPopupWindow?.dismiss()
            }
        }
        
        // 计算下拉菜单的高度（单页显示5个，每个约48dp）
        val itemHeight = (48 * context.resources.displayMetrics.density).toInt()
        val maxHeight = itemHeight * 5 // 单页显示5个
        val totalHeight = itemHeight * allQueries.size
        val listViewHeight = if (totalHeight > maxHeight) maxHeight else totalHeight
        
        // 设置ListView高度
        listView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            listViewHeight
        )
        
        // 计算宽度（与输入框宽度一致）
        val inputWidth = anchor.width
        val popupWidth = if (inputWidth > 0) inputWidth else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        
        // 创建PopupWindow
        queryPopupWindow = android.widget.PopupWindow(
            listView,
            popupWidth,
            listViewHeight,
            true // focusable
        )
        
        // 设置背景和样式
        queryPopupWindow?.setBackgroundDrawable(
            androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_light_frame)
        )
        queryPopupWindow?.elevation = 8f
        
        // 设置外部点击关闭
        queryPopupWindow?.isOutsideTouchable = true
        
        // 显示在输入框下方
        queryPopupWindow?.showAsDropDown(anchor, 0, 0, android.view.Gravity.START)
    }
    
    private fun showInputDialog(context: Context) {
        // 如果正在从悬浮球录制切换到技能学习小助手，不显示输入弹窗
        val mainActivity = context as? MainActivity
        if (mainActivity?.isSwitchingFromOverlayRecording == true) {
            Log.d(TAG, "showInputDialog: 正在从悬浮球录制切换，跳过显示输入弹窗")
            return
        }
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_companion_input, null
            )
            
            val etInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCompanionInput)
            val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
            val btnSend = dialogView.findViewById<android.widget.Button>(R.id.btnSend)
            val btnQueryDropdown = dialogView.findViewById<android.widget.TextView>(R.id.btnQueryDropdown)
            val btnExpand = dialogView.findViewById<android.widget.ImageButton>(R.id.btnExpand)
            var isAskScreenEnabled = false

            fun updateAskScreenToggleUi() {
                val bgRes = if (isAskScreenEnabled) {
                    R.drawable.bg_ask_screen_chip_on
                } else {
                    R.drawable.bg_ask_screen_chip
                }
                val textColor = if (isAskScreenEnabled) {
                    android.graphics.Color.WHITE
                } else {
                    android.graphics.Color.parseColor("#8A94A6")
                }
                btnQueryDropdown?.setBackgroundResource(bgRes)
                btnQueryDropdown?.setTextColor(textColor)
            }
            
            // 初始化语音输入功能
            val llVoicePanel = dialogView.findViewById<android.widget.LinearLayout>(R.id.llVoicePanel)
            val btnVoiceInput = dialogView.findViewById<android.widget.ImageButton>(R.id.btnVoiceInput)
            val btnVoiceRecord = dialogView.findViewById<android.widget.ImageButton>(R.id.btnVoiceRecord)
            val tvVoiceHint = dialogView.findViewById<android.widget.TextView>(R.id.tvVoiceHint)
            val ivRecordingRing = dialogView.findViewById<RecordingRingView>(R.id.ivRecordingRing)
            
            var voiceInputHelper: VoiceInputHelper? = null
            if (llVoicePanel != null && btnVoiceInput != null && btnVoiceRecord != null && 
                tvVoiceHint != null && ivRecordingRing != null && etInput != null) {
                voiceInputHelper = VoiceInputHelper(
                    context,
                    etInput,
                    llVoicePanel,
                    btnVoiceInput,
                    btnVoiceRecord,
                    tvVoiceHint,
                    ivRecordingRing
                )
                
                // 如果是从无障碍快捷方式触发的，预先初始化ASR和录音管理器，减少启动延迟
                if (isFromAccessibilityShortcut) {
                    voiceInputHelper.preInitialize()
                }
            }
            
            // 确保输入框可以聚焦和接收输入
            etInput.isFocusable = true
            etInput.isFocusableInTouchMode = true
            
            // 监听输入框文本变化，调整语音输入图标位置（问屏图标常驻显示）
            etInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    // 调整语音输入图标位置：问屏图标常驻，语音输入固定在其左侧
                    btnVoiceInput?.let { voiceBtn ->
                        val params = voiceBtn.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                        params?.let {
                            it.marginEnd = (54 * context.resources.displayMetrics.density).toInt()
                            // 确保向下偏移3dp
                            it.topMargin = (3 * context.resources.displayMetrics.density).toInt()
                            voiceBtn.layoutParams = it
                        }
                    }
                }
            })
            
            // 问屏图标固定显示，不再作为下拉菜单入口
            btnQueryDropdown?.visibility = android.view.View.VISIBLE
            updateAskScreenToggleUi()
            
            // 初始化语音输入图标位置
            btnVoiceInput?.let { voiceBtn ->
                val params = voiceBtn.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                params?.let {
                    // 问屏图标常驻，语音图标固定在左侧
                    it.marginEnd = (54 * context.resources.displayMetrics.density).toInt()
                    // 确保向下偏移2dp
                    it.topMargin = (2 * context.resources.displayMetrics.density).toInt()
                    voiceBtn.layoutParams = it
                }
            }
            
            // 问屏开关：默认关闭，点击切换高亮状态
            btnQueryDropdown?.setOnClickListener {
                isAskScreenEnabled = !isAskScreenEnabled
                updateAskScreenToggleUi()
                Log.d(TAG, "问屏开关状态: $isAskScreenEnabled")
            }
            
            // 创建对话框
            val dialog = android.app.AlertDialog.Builder(context)
                .setView(dialogView)
                .create()
            
            // 扩大图标按钮点击事件：导航到TopoClaw聊天详情页
            btnExpand?.setOnClickListener {
                Log.d(TAG, "用户点击扩大图标，准备导航到TopoClaw聊天详情页")
                
                // 先隐藏键盘
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
                
                // 关闭弹窗
                dialog.dismiss()
                
                // 延迟一下确保弹窗完全关闭
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    navigateToAssistantChat(context)
                }, 100)
            }
            
            // 设置对话框窗口类型为悬浮窗
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            // 美化对话框窗口样式
            dialog.window?.let { window ->
                // 设置背景透明，让圆角背景显示出来
                window.setBackgroundDrawableResource(android.R.color.transparent)
                // 移除默认的窗口装饰
                window.decorView.setBackgroundResource(android.R.color.transparent)
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
                // 设置窗口标志，允许输入焦点（重要：这样才能弹出键盘）
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                )
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                // 设置软输入模式，确保键盘可以显示
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            }
            
            // 取消按钮
            btnCancel.setOnClickListener {
                dialog.dismiss()
            }
            
            // 发送按钮
            btnSend.setOnClickListener {
                val userInput = etInput.text?.toString()?.trim() ?: ""
                
                // 先隐藏键盘
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
                
                // 关闭弹窗
                dialog.dismiss()
                
                if (userInput.isNotEmpty()) {
                    // 触发任务（延迟一下确保弹窗和键盘都已关闭）
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        triggerTaskFromCompanionMode(context, userInput, isAskScreenEnabled)
                    }, 100)
                }
            }
            
            // 输入框回车键发送
            etInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    val userInput = etInput.text?.toString()?.trim() ?: ""
                    
                    // 先隐藏键盘
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
                    
                    // 关闭弹窗
                    dialog.dismiss()
                    
                    if (userInput.isNotEmpty()) {
                        // 触发任务（延迟一下确保弹窗和键盘都已关闭）
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            triggerTaskFromCompanionMode(context, userInput, isAskScreenEnabled)
                        }, 100)
                    }
                    true
                } else {
                    false
                }
            }
            
            // 设置对话框关闭监听器，清理语音输入资源
            dialog.setOnDismissListener {
                voiceInputHelper?.cleanup()
            }
            
            // 设置对话框显示后的监听器，确保对话框完全显示后再弹出键盘
            dialog.setOnShowListener {
                // 如果是从无障碍快捷方式触发的，自动打开语音面板并开始录音
                if (isFromAccessibilityShortcut && voiceInputHelper != null) {
                    Log.d(TAG, "从无障碍快捷方式触发，准备开始录音")
                    // 重置标志，避免影响后续操作
                    isFromAccessibilityShortcut = false
                    // 延迟一小段时间确保对话框布局完全完成后再开始录音
                    // 使用post延迟，确保视图已附加到窗口
                    dialog.window?.decorView?.postDelayed({
                        try {
                            if (voiceInputHelper != null) {
                                Log.d(TAG, "对话框布局完成，开始录音")
                                voiceInputHelper?.showVoicePanelAndStartRecording()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "开始录音失败: ${e.message}", e)
                        }
                    }, 100) // 延迟100ms确保布局完成
                } else {
                    // 非无障碍快捷方式触发，正常弹出键盘
                    // 自动聚焦输入框并弹出键盘
                    etInput.requestFocus()
                    // 使用多次延迟尝试，确保键盘能够弹出
                    etInput.postDelayed({
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        // 第一次尝试：立即显示键盘
                        imm?.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        
                        // 第二次尝试：延迟200ms后重试
                        etInput.postDelayed({
                            if (imm?.isActive(etInput) != true) {
                                imm?.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            }
                        }, 200)
                        
                        // 第三次尝试：延迟400ms后再次重试
                        etInput.postDelayed({
                            if (imm?.isActive(etInput) != true) {
                                imm?.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            }
                        }, 400)
                    }, 150) // 延迟150ms确保对话框完全显示
                }
            }
            
            // 显示对话框
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示输入弹窗失败: ${e.message}", e)
        }
    }
    
    /**
     * 从伴随模式触发任务
     */
    private fun triggerTaskFromCompanionMode(context: Context, userInput: String, isAskScreenEnabled: Boolean) {
        try {
            Log.d(TAG, "从伴随模式触发任务: $userInput")
            
            // 获取 MainActivity 实例
            val mainActivity = context as? MainActivity
            if (mainActivity == null) {
                Log.w(TAG, "无法获取 MainActivity，无法触发任务")
                return
            }
            
            // 标记任务是从伴随模式发起的
            mainActivity.isTaskFromCompanionMode = true
            mainActivity.isCompanionAskScreenEnabled = isAskScreenEnabled
            Log.d(TAG, "标记任务从伴随模式发起: isTaskFromCompanionMode=true")
            
            // 记录任务发起时的原始应用包名（用于任务完成后切回原应用）
            // 使用不依赖无障碍服务的方法获取，这样即使无障碍服务未启用也能获取到
            try {
                val currentPackageName = mainActivity.getCurrentPackageNameWithoutAccessibility()
                val selfPackageName = context.packageName
                
                if (currentPackageName != null && currentPackageName != selfPackageName) {
                    // 当前应用不是自己，说明是从外部应用发起的任务
                    mainActivity.isTaskFromExternalApp = true
                    mainActivity.originalPackageName = currentPackageName
                    Log.d(TAG, "任务从外部应用发起（伴随模式），原始包名: $currentPackageName")
                } else {
                    // 当前应用是自己，说明是从应用内发起的任务
                    mainActivity.isTaskFromExternalApp = false
                    mainActivity.originalPackageName = null
                    Log.d(TAG, "任务从应用内发起（伴随模式），当前包名: $currentPackageName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取当前应用包名失败: ${e.message}", e)
                // 获取失败时，默认认为是从应用内发起的
                mainActivity.isTaskFromExternalApp = false
                mainActivity.originalPackageName = null
            }
            
            val targetConversationId = COMPANION_TARGET_CONVERSATION_ID
            val targetAssistant = CustomAssistantManager.getById(context, targetConversationId)
            if (targetAssistant == null) {
                Log.e(TAG, "未找到目标会话: $targetConversationId，终止发送")
                android.widget.Toast.makeText(context, "未找到内置小助手 topoclaw", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            // 检查并切换到 topoclaw 聊天页面
            var chatFragment = mainActivity.getChatFragment()
            val currentConv = chatFragment?.arguments?.getSerializable("conversation") as? com.cloudcontrol.demo.Conversation
            
            // 如果ChatFragment不存在或者不是 topoclaw，需要切换
            if (chatFragment == null || currentConv?.id != targetConversationId) {
                Log.d(TAG, "ChatFragment不存在或不是topoclaw，切换到topoclaw聊天页面")
                
                // 切换到 topoclaw 聊天页面（在后台切换，不跳转回应用）
                val targetConversation = Conversation(
                    id = targetAssistant.id,
                    name = targetAssistant.name,
                    avatar = targetAssistant.avatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                mainActivity.switchToChatFragment(targetConversation)
                
                // 使用Handler等待Fragment切换完成并等待聊天记录恢复完成（避免阻塞主线程）
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var retryCount = 0
                val maxRetries = 30 // 增加到30次，给聊天记录恢复更多时间
                var lastFragmentState: ChatFragment? = null
                
                fun checkAndSendTask() {
                    val currentFragment = mainActivity.getChatFragment()
                    val conv = currentFragment?.arguments?.getSerializable("conversation") as? com.cloudcontrol.demo.Conversation
                    
                    // 检查Fragment是否存在且是正确的对话
                    if (currentFragment != null && conv?.id == targetConversationId) {
                        // Fragment已就绪，检查是否和上次一样（说明Fragment已经稳定）
                        if (currentFragment == lastFragmentState) {
                            // Fragment已经稳定，等待一下确保聊天记录恢复完成
                            // 检查RecyclerView是否已经初始化（通过检查adapter是否存在）
                            val adapter = currentFragment.getChatAdapter()
                            if (adapter != null) {
                                Log.d(TAG, "已成功切换到topoclaw聊天页面，Fragment已稳定")
                                // 再等待一小段时间，确保聊天记录恢复完成
                                handler.postDelayed({
                                    mainActivity.getScreenshotAndSendTask(userInput, currentFragment)
                                }, 300) // 额外等待300ms，确保聊天记录恢复完成
                            } else if (retryCount < maxRetries) {
                                // Adapter还未初始化，继续等待
                                retryCount++
                                handler.postDelayed({ checkAndSendTask() }, 100)
                            } else {
                                Log.e(TAG, "ChatFragment的Adapter初始化超时")
                                android.widget.Toast.makeText(context, "聊天页面初始化超时", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // Fragment刚创建，记录状态并继续等待
                            lastFragmentState = currentFragment
                            retryCount++
                            handler.postDelayed({ checkAndSendTask() }, 150) // 等待150ms后重试
                        }
                    } else if (retryCount < maxRetries) {
                        retryCount++
                        handler.postDelayed({ checkAndSendTask() }, 100) // 等待100ms后重试
                    } else {
                        Log.e(TAG, "切换后ChatFragment仍然不存在或不是topoclaw")
                        android.widget.Toast.makeText(context, "聊天页面初始化失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                
                // 延迟一下再开始检查，给Fragment切换一些时间
                handler.postDelayed({ checkAndSendTask() }, 300) // 增加到300ms，给Fragment创建更多时间
                return // 异步处理，提前返回
            }
            
            // ChatFragment已存在且是TopoClaw，直接获取截图并发送任务
            mainActivity.getScreenshotAndSendTask(userInput, chatFragment)
        } catch (e: Exception) {
            Log.e(TAG, "从伴随模式触发任务失败: ${e.message}", e)
            android.widget.Toast.makeText(context, "触发任务失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 显示系统通知栏（使用前台服务）
     * 前台服务（ForegroundService）是Android中一种特殊的服务，它会在通知栏显示一个持续的通知，
     * 告诉用户应用正在运行。前台服务的通知通常不会被系统清理，因为它们是系统级别的服务。
     */
    private fun showNotificationBar(context: Context) {
        if (isNotificationBarShowing) {
            Log.d(TAG, "通知栏已经在显示中，跳过")
            return
        }
        
        Log.d(TAG, "========== 开始显示系统通知栏（使用前台服务） ==========")
        
        try {
            // 启动前台服务来保持通知
            val serviceIntent = Intent(context, CompanionModeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            isNotificationBarShowing = true
            Log.d(TAG, "========== 前台服务已启动，通知应该会显示 ==========")
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败: ${e.message}", e)
            e.printStackTrace()
            isNotificationBarShowing = false
        }
    }
    
    /**
     * 显示输入弹窗（供通知栏按钮调用）
     */
    fun showInputDialogForNotification(context: Context) {
        // 通知栏触发，不是无障碍快捷方式
        isFromAccessibilityShortcut = false
        showInputDialog(context)
    }
    
    /**
     * 从无障碍快捷方式显示输入弹窗
     */
    fun showInputDialogFromAccessibility(context: Context) {
        // 无障碍快捷方式触发
        isFromAccessibilityShortcut = true
        showInputDialog(context)
    }
    
    /**
     * 隐藏通知栏（供BroadcastReceiver调用）
     */
    fun hideNotificationBarFromReceiver(context: Context) {
        hideNotificationBar()
    }
    
    /**
     * 启动通知保活机制（定期更新通知，防止被ColorOS系统清理）
     */
    private fun startNotificationKeepAlive(context: Context) {
        stopNotificationKeepAlive() // 先停止之前的任务
        
        notificationKeepAliveHandler = android.os.Handler(android.os.Looper.getMainLooper())
        notificationKeepAliveRunnable = object : Runnable {
            override fun run() {
                if (isNotificationBarShowing) {
                    // 更新通知（重新显示可以防止被系统清理）
                    try {
                        Log.d(TAG, "通知保活：更新通知")
                        updateNotification(context)
                        // 每5秒更新一次（更频繁，防止被ColorOS清理）
                        notificationKeepAliveHandler?.postDelayed(this, 5000)
                    } catch (e: Exception) {
                        Log.e(TAG, "通知保活更新失败: ${e.message}", e)
                    }
                }
            }
        }
        
        // 5秒后开始第一次更新（更频繁，防止被ColorOS清理）
        notificationKeepAliveHandler?.postDelayed(notificationKeepAliveRunnable!!, 5000)
    }
    
    /**
     * 更新通知（用于保活机制）
     */
    private fun updateNotification(context: Context) {
        try {
            val notification = createNotification(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "更新通知失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止通知保活机制
     */
    private fun stopNotificationKeepAlive() {
        notificationKeepAliveRunnable?.let { 
            notificationKeepAliveHandler?.removeCallbacks(it)
        }
        notificationKeepAliveHandler = null
        notificationKeepAliveRunnable = null
    }
    
    /**
     * 隐藏系统通知栏（停止前台服务）
     */
    private fun hideNotificationBar() {
        if (!isNotificationBarShowing) {
            return
        }
        
        try {
            // 停止保活机制
            stopNotificationKeepAlive()
            
            // 停止前台服务
            val ctx = context ?: return
            val serviceIntent = Intent(ctx, CompanionModeService::class.java)
            ctx.stopService(serviceIntent)
            
            isNotificationBarShowing = false
            Log.d(TAG, "前台服务已停止，系统通知栏已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏系统通知栏失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新点击事件监听器（根据模式和任务状态）
     */
    /**
     * 检查并自动请求悬浮窗权限（如果缺失）
     * @return true表示有权限，false表示无权限且已跳转到设置页面
     */
    private fun checkAndRequestOverlayPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "检测到悬浮窗权限缺失，自动跳转到权限设置页面")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    android.widget.Toast.makeText(
                        context,
                        "请开启'显示在其他应用的上层'权限",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return false
                } catch (e: Exception) {
                    Log.e(TAG, "跳转到权限设置页面失败: ${e.message}", e)
                    android.widget.Toast.makeText(
                        context,
                        "无法打开权限设置页面，请手动在设置中开启悬浮窗权限",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return false
                }
            }
        }
        return true
    }
    
    private fun updateClickListener(context: Context) {
        Log.d(TAG, "updateClickListener: 设置点击事件监听器, isCompanionMode=$isCompanionMode")
        
        // 提取点击处理逻辑为一个内部函数，供两种方式调用
        fun handleClick() {
            Log.d(TAG, "悬浮球被点击！")
            
            // 记录点击时间（用于自动缩小功能）
            lastClickTime = System.currentTimeMillis()
            
            // 如果已缩小，恢复大小（带动画）
            if (isMinimized) {
                restoreSizeIfMinimizedWithAnimation(context)
            }
            
            // 快速自检权限：如果没有权限，自动跳转到权限设置页面
            if (!checkAndRequestOverlayPermission(context)) {
                Log.d(TAG, "权限缺失，已跳转到设置页面，跳过点击处理")
                return
            }
            
            val mainActivity = context as? MainActivity
            
            // 优先检查是否处于手动接管状态
            val isManualTakeover = mainActivity?.isManualTakeover() ?: false
            if (isManualTakeover) {
                Log.d(TAG, "检测到手动接管状态，结束手动操作")
                // 收起菜单（如果有）
                if (isMenuShowing) {
                    hideMenu()
                }
                // 结束手动接管
                mainActivity?.endManualTakeover()
                return
            }
            
            val isTaskRunning = mainActivity?.getTaskRunningStatus() ?: false
            val isTaskPaused = mainActivity?.isTaskPaused() ?: false
            Log.d(TAG, "点击事件处理: isTaskRunning=$isTaskRunning, isTaskPaused=$isTaskPaused, isCompanionMode=$isCompanionMode")
            
            if (isCompanionMode) {
                // 如果正在从悬浮球录制切换到技能学习小助手，不执行任何操作
                if (mainActivity?.isSwitchingFromOverlayRecording == true) {
                    Log.d(TAG, "正在从悬浮球录制切换，跳过点击事件处理")
                    return
                }
                
                // 伴随模式下：检查任务运行状态
                if (isTaskRunning && !isTaskPaused) {
                    // 任务运行中且未暂停：直接显示菜单（不暂停任务）
                    Log.d(TAG, "任务运行中，点击悬浮球：显示菜单")
                    showMenu(context)
                } else if (isTaskPaused) {
                    // 任务已暂停：继续任务 + 收起菜单（一气呵成）
                    Log.d(TAG, "任务已暂停，点击悬浮球：继续任务 + 收起菜单")
                    if (isMenuShowing) {
                        hideMenu()
                    }
                    mainActivity?.toggleTaskPauseFromOverlay()
                } else {
                    // 任务未运行：先获取并保存原应用包名，然后显示输入弹窗
                    // 在显示输入弹窗之前就获取，避免用户打开设置页面后获取到错误的包名
                    try {
                        val currentPackageName = mainActivity?.getCurrentPackageNameWithoutAccessibility()
                        val selfPackageName = context.packageName
                        
                        if (currentPackageName != null && currentPackageName != selfPackageName) {
                            // 当前应用不是自己，说明是从外部应用发起的
                            mainActivity?.isTaskFromExternalApp = true
                            mainActivity?.originalPackageName = currentPackageName
                            Log.d(TAG, "悬浮球点击：保存原始应用包名: $currentPackageName")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "悬浮球点击：获取当前应用包名失败: ${e.message}", e)
                    }
                    
                    // 显示输入弹窗
                    showInputDialog(context)
                }
            } else {
                // 非伴随模式下（任务运行模式）：检查任务运行状态
                Log.d(TAG, "非伴随模式点击悬浮球：isTaskRunning=$isTaskRunning, isTaskPaused=$isTaskPaused, isCompanionMode=$isCompanionMode")
                if (isTaskRunning && !isTaskPaused) {
                    // 任务运行中且未暂停：直接显示菜单（不暂停任务）
                    Log.d(TAG, "任务运行中，点击悬浮球：显示菜单")
                    showMenu(context)
                } else if (isTaskPaused) {
                    // 任务已暂停：继续任务 + 收起菜单（一气呵成）
                    Log.d(TAG, "任务已暂停，点击悬浮球：继续任务 + 收起菜单")
                    if (isMenuShowing) {
                        hideMenu()
                    }
                    mainActivity?.toggleTaskPauseFromOverlay()
                } else {
                    // 任务未运行：不应该显示菜单（任务运行模式下，没有任务时不应该显示菜单）
                    Log.d(TAG, "任务未运行，点击悬浮球：无操作（任务运行模式下）")
                }
            }
        }
        
        // 同时设置两种方式，确保点击事件能够正确触发
        // 1. setOnClickListener：用于performClick()机制（备用）
        overlayView?.setOnClickListener { handleClick() }
        // 2. setOnSingleClickListener：用于直接回调（主要方式，避免监听器被替换导致失效）
        overlayView?.setOnSingleClickListener { handleClick() }
    }
    
    /**
     * 更新悬浮窗状态（根据任务运行状态）
     * 伴随模式下：任务运行中显示转圈圈并恢复菜单功能，任务未运行时隐藏转圈圈
     * 任务运行模式下：任务运行中显示转圈圈并恢复菜单功能
     */
    fun updateOverlayForTaskStatus(isTaskRunning: Boolean) {
        if (!isShowing || overlayView == null) {
            Log.d(TAG, "updateOverlayForTaskStatus: 悬浮球未显示或View为空，跳过")
            return
        }
        
        val ctx = context ?: return
        
        Log.d(TAG, "更新悬浮窗状态：isTaskRunning=$isTaskRunning, isCompanionMode=$isCompanionMode")
        
        if (isTaskRunning) {
            // 任务运行中：先停止之前的动画（确保清理状态），然后显示转圈圈并启动动画，恢复菜单功能
            overlayView?.stopAnimation() // 先停止，确保状态清理
            overlayView?.setShowArc(true)
            overlayView?.startAnimation() // 重新启动动画
            // 更新点击事件，使其显示菜单
            updateClickListener(ctx)
            
            // 如果已缩小，恢复大小（任务执行时应该保持正常大小）
            if (isMinimized) {
                restoreSizeIfMinimizedWithAnimation(ctx)
            }
            
            if (isCompanionMode) {
                Log.d(TAG, "任务运行中（伴随模式），显示转圈圈动画，恢复菜单功能")
            } else {
                Log.d(TAG, "任务运行中（任务运行模式），显示转圈圈动画，恢复菜单功能")
            }
        } else {
            // 任务未运行：根据模式处理
            if (isCompanionMode) {
                // 伴随模式：隐藏转圈圈并停止动画，点击显示输入弹窗
                overlayView?.stopAnimation()
                overlayView?.setShowArc(false)
                // 更新点击事件，使其显示输入弹窗
                updateClickListener(ctx)
                // 重置点击时间，重新开始计时（任务结束时）
                lastClickTime = System.currentTimeMillis()
                Log.d(TAG, "任务未运行（伴随模式），隐藏转圈圈动画，点击显示输入弹窗")
            } else {
                // 任务运行模式：任务未运行时，隐藏转圈圈并停止动画
                overlayView?.stopAnimation()
                overlayView?.setShowArc(false)
                // 更新点击事件（虽然任务运行模式下没有任务时不应该显示菜单）
                updateClickListener(ctx)
                Log.d(TAG, "任务未运行（任务运行模式），隐藏转圈圈动画")
            }
        }
    }
    
    /**
     * 启动自动缩小检查（伴随模式下）
     */
    private fun startAutoMinimizeCheck(context: Context) {
        // 先停止之前的检查
        stopAutoMinimizeCheck()
        
        // 只在伴随模式下启动
        if (!isCompanionMode) {
            Log.d(TAG, "非伴随模式，不启动自动缩小检查")
            return
        }
        
        Log.d(TAG, "启动自动缩小检查")
        
        autoMinimizeJob = autoMinimizeScope.launch {
            try {
                while (isCompanionMode && isShowing) {
                    delay(AUTO_MINIMIZE_CHECK_INTERVAL_MS)
                    
                    // 检查条件
                    val ctx = context
                    if (ctx == null || !isShowing || !isCompanionMode) {
                        Log.d(TAG, "自动缩小检查：条件不满足，停止检查")
                        break
                    }
                    
                    val mainActivity = ctx as? MainActivity
                    val isTaskRunning = mainActivity?.getTaskRunningStatus() ?: false
                    
                    // 检查是否需要自动缩小
                    val timeSinceLastClick = System.currentTimeMillis() - lastClickTime
                    val shouldMinimize = !isTaskRunning && 
                                        !isMinimized && 
                                        timeSinceLastClick >= AUTO_MINIMIZE_DELAY_MS
                    
                    if (shouldMinimize) {
                        Log.d(TAG, "自动缩小检查：满足条件，开始缩小（距离上次点击${timeSinceLastClick}ms）")
                        withContext(Dispatchers.Main) {
                            minimizeSizeWithAnimation(ctx)
                        }
                    } else {
                        Log.d(TAG, "自动缩小检查：不满足条件（isTaskRunning=$isTaskRunning, isMinimized=$isMinimized, timeSinceLastClick=${timeSinceLastClick}ms）")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动缩小检查异常: ${e.message}", e)
            }
        }
    }
    
    /**
     * 停止自动缩小检查
     */
    private fun stopAutoMinimizeCheck() {
        autoMinimizeJob?.cancel()
        autoMinimizeJob = null
        Log.d(TAG, "停止自动缩小检查")
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hideMenu()
        hideNotificationBar()
        stopAutoMinimizeCheck() // 停止自动缩小检查
        forceHide()
        windowManager = null
        context = null
        recordingJob?.cancel()
        recordingJob = null
        lastClickTime = 0L // 重置点击时间
    }
    
    /**
     * 从悬浮球启动屏幕录制（伴随模式）
     */
    private fun startScreenRecordingFromOverlay(context: Context) {
        if (isRecording) {
            Log.w(TAG, "录制已在进行中，忽略重复请求")
            android.widget.Toast.makeText(context, "录制已在进行中", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val mainActivity = context as? MainActivity
        if (mainActivity == null) {
            Log.e(TAG, "无法获取MainActivity，无法启动录制")
            android.widget.Toast.makeText(context, "无法启动录制", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // 在请求权限之前就设置标记，防止应用回到前台时触发TopoClaw
        mainActivity.isSwitchingFromOverlayRecording = true
        Log.d(TAG, "设置 isSwitchingFromOverlayRecording = true，防止请求权限时触发TopoClaw")
        
        try {
            Log.d(TAG, "开始请求录制权限")
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            mainActivity.startActivityForResult(captureIntent, MainActivity.REQUEST_MEDIA_PROJECTION_OVERLAY)
            Log.d(TAG, "已请求录制权限")
        } catch (e: Exception) {
            Log.e(TAG, "请求录制权限失败: ${e.message}", e)
            android.widget.Toast.makeText(context, "请求录制权限失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            // 如果请求权限失败，清除标记
            mainActivity.isSwitchingFromOverlayRecording = false
            Log.d(TAG, "请求权限失败，清除 isSwitchingFromOverlayRecording = false")
        }
    }
    
    /**
     * 处理录制权限请求结果
     */
    fun handleRecordingPermissionResult(resultCode: Int, data: Intent?) {
        val ctx = context ?: return
        val mainActivity = ctx as? MainActivity ?: return
        
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "录制权限被拒绝或数据无效")
            android.widget.Toast.makeText(ctx, "录制权限被拒绝", android.widget.Toast.LENGTH_SHORT).show()
            // 权限被拒绝，清除标记
            mainActivity.isSwitchingFromOverlayRecording = false
            Log.d(TAG, "权限被拒绝，清除 isSwitchingFromOverlayRecording = false")
            return
        }
        
        Log.d(TAG, "录制权限已授予，开始启动录制服务")
        // 标记保持为 true，直到录制完成并切换到技能学习小助手对话
        pendingRecordingResultCode = resultCode
        pendingRecordingData = data
        
        // 启动录制服务
        startRecordingService()
    }
    
    /**
     * 启动录制服务
     */
    private fun startRecordingService() {
        val ctx = context ?: return
        val resultCode = pendingRecordingResultCode ?: return
        val resultData = pendingRecordingData ?: return
        
        try {
            // 设置录制回调
            ScreenRecordingService.setCallback(createRecordingCallback())
            
            // 启动录制服务
            val intent = Intent(ctx, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_START
                putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, resultData)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            
            isRecording = true
            Log.d(TAG, "录制服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动录制服务失败: ${e.message}", e)
            android.widget.Toast.makeText(ctx, "启动录制失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }
    
    /**
     * 创建录制回调
     */
    private fun createRecordingCallback(): RecordingCallback {
        return object : RecordingCallback {
            override fun onRecordingStarted(videoPath: String) {
                val ctx = context ?: return
                Log.d(TAG, "录制已开始: $videoPath")
                
                // 隐藏伴随模式的悬浮球（避免两个悬浮球同时显示）
                wasOverlayShowingBeforeRecording = isShowing
                if (isShowing && isCompanionMode) {
                    Log.d(TAG, "录制开始，临时隐藏伴随模式悬浮球")
                    // 临时隐藏悬浮球（不改变isCompanionMode状态）
                    val wm = windowManager
                    val view = overlayView
                    if (wm != null && view != null) {
                        try {
                            view.stopAnimation()
                            wm.removeView(view)
                            // 临时设置isShowing = false，但保留overlayView和overlayParams以便恢复
                            isShowing = false
                        } catch (e: Exception) {
                            Log.e(TAG, "隐藏悬浮球失败: ${e.message}", e)
                        }
                    }
                }
                
                // 显示录制悬浮窗
                RecordingOverlayManager.show(ctx) {
                    // 点击悬浮窗时停止录制
                    stopRecordingFromOverlay()
                }
                
                android.widget.Toast.makeText(ctx, "录屏已开始", android.widget.Toast.LENGTH_SHORT).show()
            }
            
            override fun onRecordingStopped(videoPath: String, screenWidth: Int, screenHeight: Int) {
                val ctx = context ?: return
                Log.d(TAG, "录制已停止: $videoPath")
                
                isRecording = false
                RecordingOverlayManager.hide()
                
                // 处理录制完成后的逻辑（在协程中处理，完成后恢复悬浮球）
                // 注意：不在这里恢复悬浮球，而是在 handleRecordingCompleted 完成后恢复
                handleRecordingCompleted(videoPath, screenWidth, screenHeight)
            }
            
            override fun onRecordingError(error: String) {
                val ctx = context ?: return
                Log.e(TAG, "录制错误: $error")
                
                isRecording = false
                RecordingOverlayManager.hide()
                
                // 恢复伴随模式的悬浮球（如果录制前是显示的）
                if (wasOverlayShowingBeforeRecording && isCompanionMode && !isShowing) {
                    Log.d(TAG, "录制错误，恢复伴随模式悬浮球")
                    // 重新显示悬浮球
                    val wm = windowManager
                    val view = overlayView
                    if (wm != null && view != null) {
                        try {
                            val params = overlayParams
                            if (params != null) {
                                wm.addView(view, params)
                                isShowing = true
                                // 如果任务未运行，不显示转圈圈
                                val mainActivity = ctx as? MainActivity
                                val isTaskRunning = mainActivity?.getTaskRunningStatus() ?: false
                                if (!isTaskRunning) {
                                    view.stopAnimation()
                                    view.setShowArc(false)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "恢复悬浮球失败: ${e.message}", e)
                        }
                    }
                }
                wasOverlayShowingBeforeRecording = false
                
                android.widget.Toast.makeText(ctx, "录制错误: $error", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 停止录制（从悬浮窗）
     */
    private fun stopRecordingFromOverlay() {
        val ctx = context ?: return
        
        try {
            val intent = Intent(ctx, ScreenRecordingService::class.java).apply {
                action = ScreenRecordingService.ACTION_STOP
            }
            ctx.startService(intent)
            Log.d(TAG, "已发送停止录制请求")
        } catch (e: Exception) {
            Log.e(TAG, "停止录制失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理录制完成后的逻辑：切换到技能学习小助手对话并发送视频
     */
    private fun handleRecordingCompleted(videoPath: String, screenWidth: Int, screenHeight: Int) {
        val ctx = context ?: return
        val mainActivity = ctx as? MainActivity ?: return
        
        // 保存录制前悬浮球显示状态（用于后续恢复）
        val shouldRestoreOverlay = wasOverlayShowingBeforeRecording && isCompanionMode && !isShowing
        
        // 取消之前的任务（如果有）
        recordingJob?.cancel()
        recordingJob = recordingScope.launch {
            // 在协程内部使用外部变量
            val shouldRestore = shouldRestoreOverlay
            
            try {
                Log.d(TAG, "处理录制完成，准备切换到技能学习小助手对话")
                
                // 步骤1: 设置标记，防止触发TopoClaw
                withContext(Dispatchers.Main) {
                    mainActivity.isSwitchingFromOverlayRecording = true
                    Log.d(TAG, "设置 isSwitchingFromOverlayRecording = true，防止触发TopoClaw")
                }
                
                // 步骤2: 先确保应用在前台（避免触发TopoClaw）
                withContext(Dispatchers.Main) {
                    try {
                        val success = mainActivity.bringAppToForeground()
                        if (success) {
                            Log.d(TAG, "已返回到TopoClaw应用页面")
                            // 等待应用切换到前台
                            delay(500)
                        } else {
                            Log.w(TAG, "返回到TopoClaw应用页面失败")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "返回到应用页面时出错: ${e.message}", e)
                    }
                }
                
                // 步骤3: 切换到技能学习小助手对话
                withContext(Dispatchers.Main) {
                    // 检查当前对话是否是技能学习小助手
                    val chatFragment = mainActivity.getChatFragment()
                    val currentConv = chatFragment?.arguments?.getSerializable("conversation") as? Conversation
                    
                    if (chatFragment == null || currentConv?.id != ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING) {
                        Log.d(TAG, "当前不是技能学习小助手对话，切换到技能学习小助手")
                        
                        // 创建技能学习小助手对话
                        val skillConv = Conversation(
                            id = ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING,
                            name = "技能学习小助手",
                            avatar = null,
                            lastMessage = null,
                            lastMessageTime = System.currentTimeMillis()
                        )
                        
                        // 切换到技能学习小助手对话（使用复用机制）
                        val extraArgs = Bundle().apply {
                            // 添加标记，表示这是从悬浮球录制触发的，不要显示TopoClaw的弹窗
                            putBoolean("fromOverlayRecording", true)
                        }
                        mainActivity.switchToChatFragment(skillConv, extraArgs)
                        
                        // 等待Fragment切换完成
                        delay(500)
                    }
                    
                    // 步骤2: 获取ChatFragment并发送视频
                    var retryCount = 0
                    val maxRetries = 20
                    
                    while (retryCount < maxRetries) {
                        val currentChatFragment = mainActivity.getChatFragment()
                        val currentConv = currentChatFragment?.arguments?.getSerializable("conversation") as? Conversation
                        
                        if (currentChatFragment != null && currentConv?.id == ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING) {
                            Log.d(TAG, "已切换到技能学习小助手对话，准备发送视频")
                            
                            // 显示视频消息
                            currentChatFragment.addVideoMessageFromOverlay(videoPath)
                            
                            // 触发上传流程
                            currentChatFragment.uploadVideoToCloudFromOverlay(videoPath, screenWidth, screenHeight)
                            
                            android.widget.Toast.makeText(ctx, "视频已发送到技能学习小助手", android.widget.Toast.LENGTH_SHORT).show()
                            
                            // 清除标记（在恢复悬浮球之前清除，避免恢复时触发TopoClaw）
                            mainActivity.isSwitchingFromOverlayRecording = false
                            Log.d(TAG, "清除 isSwitchingFromOverlayRecording = false")
                            
                            // 延迟一下再恢复悬浮球，确保所有逻辑都已完成
                            delay(300)
                            
                            // 恢复伴随模式的悬浮球（如果录制前是显示的）
                            if (shouldRestore && isCompanionMode && !isShowing) {
                                Log.d(TAG, "录制完成，恢复伴随模式悬浮球")
                                // 重新显示悬浮球
                                val wm = windowManager
                                val view = overlayView
                                if (wm != null && view != null) {
                                    try {
                                        val params = overlayParams
                                        if (params != null) {
                                            wm.addView(view, params)
                                            isShowing = true
                                            // 如果任务未运行，不显示转圈圈
                                            val isTaskRunning = mainActivity.getTaskRunningStatus()
                                            if (!isTaskRunning) {
                                                view.stopAnimation()
                                                view.setShowArc(false)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "恢复悬浮球失败: ${e.message}", e)
                                    }
                                }
                            }
                            wasOverlayShowingBeforeRecording = false
                            
                            return@withContext
                        }
                        
                        retryCount++
                        delay(100)
                    }
                    
                    Log.e(TAG, "切换对话失败，无法发送视频")
                    android.widget.Toast.makeText(ctx, "切换对话失败", android.widget.Toast.LENGTH_SHORT).show()
                    
                    // 清除标记
                    mainActivity.isSwitchingFromOverlayRecording = false
                    Log.d(TAG, "清除 isSwitchingFromOverlayRecording = false（失败情况）")
                    
                    // 延迟一下再恢复悬浮球
                    delay(300)
                    
                    // 恢复伴随模式的悬浮球（如果录制前是显示的）
                    if (shouldRestore && isCompanionMode && !isShowing) {
                        Log.d(TAG, "录制完成失败，恢复伴随模式悬浮球")
                        // 重新显示悬浮球
                        val wm = windowManager
                        val view = overlayView
                        if (wm != null && view != null) {
                            try {
                                val params = overlayParams
                                if (params != null) {
                                    wm.addView(view, params)
                                    isShowing = true
                                    // 如果任务未运行，不显示转圈圈
                                    val isTaskRunning = mainActivity.getTaskRunningStatus()
                                    if (!isTaskRunning) {
                                        view.stopAnimation()
                                        view.setShowArc(false)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "恢复悬浮球失败: ${e.message}", e)
                            }
                        }
                    }
                    wasOverlayShowingBeforeRecording = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理录制完成失败: ${e.message}", e)
                android.widget.Toast.makeText(ctx, "处理录制失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                
                // 清除标记
                val mainActivity = ctx as? MainActivity
                mainActivity?.isSwitchingFromOverlayRecording = false
                Log.d(TAG, "清除 isSwitchingFromOverlayRecording = false（异常情况）")
                
                // 延迟一下再恢复悬浮球
                delay(300)
                
                // 恢复伴随模式的悬浮球（如果录制前是显示的）
                if (shouldRestore && isCompanionMode && !isShowing) {
                    Log.d(TAG, "录制完成异常，恢复伴随模式悬浮球")
                    // 重新显示悬浮球
                    val wm = windowManager
                    val view = overlayView
                    if (wm != null && view != null) {
                        try {
                            val params = overlayParams
                            if (params != null) {
                                wm.addView(view, params)
                                isShowing = true
                                // 如果任务未运行，不显示转圈圈
                                val isTaskRunning = mainActivity?.getTaskRunningStatus() ?: false
                                if (!isTaskRunning) {
                                    view.stopAnimation()
                                    view.setShowArc(false)
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "恢复悬浮球失败: ${ex.message}", ex)
                        }
                    }
                }
                wasOverlayShowingBeforeRecording = false
            } finally {
                recordingJob = null
            }
        }
    }
    
    /**
     * 导航到TopoClaw聊天详情页
     * 从任务发起弹窗点击扩大图标时调用
     */
    private fun navigateToAssistantChat(context: Context) {
        try {
            Log.d(TAG, "navigateToAssistantChat: 开始导航到TopoClaw聊天详情页")
            
            val mainActivity = context as? MainActivity
            
            // 创建Intent，用于启动或带到前台MainActivity
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // 添加一个extra标记，表示需要导航到TopoClaw
                putExtra("navigate_to_assistant", true)
            }
            
            // 如果context是MainActivity实例，检查是否在前台
            if (mainActivity != null) {
                val isInForeground = try {
                    mainActivity.isAppInForeground()
                } catch (e: Exception) {
                    Log.w(TAG, "检查应用前台状态失败: ${e.message}")
                    false // 如果检查失败，假设不在前台，使用Intent启动
                }
                
                if (isInForeground) {
                    // 应用已在前台，直接导航
                    Log.d(TAG, "navigateToAssistantChat: MainActivity已在前台，直接导航")
                    val assistantConversation = CustomAssistantManager
                        .getById(context, COMPANION_TARGET_CONVERSATION_ID)
                        ?.let { target ->
                            Conversation(
                                id = target.id,
                                name = target.name,
                                avatar = target.avatar,
                                lastMessage = null,
                                lastMessageTime = System.currentTimeMillis()
                            )
                        }
                        ?: run {
                            Log.w(TAG, "navigateToAssistantChat: 未找到$COMPANION_TARGET_CONVERSATION_ID，兜底到assistant")
                            Conversation(
                                id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                                name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                                avatar = null,
                                lastMessage = null,
                                lastMessageTime = System.currentTimeMillis()
                            )
                        }
                    mainActivity.switchToChatFragment(assistantConversation)
                    Log.d(TAG, "navigateToAssistantChat: 已导航到TopoClaw聊天详情页")
                    return
                } else {
                    // 应用不在前台，使用Intent带到前台（会触发onNewIntent）
                    Log.d(TAG, "navigateToAssistantChat: MainActivity不在前台，使用Intent带到前台")
                    intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    mainActivity.startActivity(intent)
                    Log.d(TAG, "navigateToAssistantChat: 已启动MainActivity，将在onNewIntent中处理导航")
                    return
                }
            }
            
            // context不是MainActivity，需要启动MainActivity
            Log.d(TAG, "navigateToAssistantChat: context不是MainActivity，需要启动MainActivity")
            context.startActivity(intent)
            Log.d(TAG, "navigateToAssistantChat: 已启动MainActivity，将在onCreate中处理导航")
        } catch (e: Exception) {
            Log.e(TAG, "navigateToAssistantChat: 导航失败: ${e.message}", e)
            android.widget.Toast.makeText(context, "导航失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}


