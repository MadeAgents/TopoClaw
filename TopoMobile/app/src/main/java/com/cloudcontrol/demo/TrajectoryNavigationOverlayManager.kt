package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import kotlinx.coroutines.*

/**
 * 轨迹采集导航悬浮球管理器
 * 在轨迹采集模式下显示返回和主页按钮悬浮球
 */
object TrajectoryNavigationOverlayManager {
    private const val TAG = "TrajectoryNavOverlay"
    
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var context: Context? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    
    // 位置状态：true表示在右侧，false表示在左侧
    private var isOnRightSide = true
    
    // 协程作用域，用于执行异步操作
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
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
                Log.w(TAG, "没有悬浮窗权限，无法显示导航悬浮球")
                return false
            }
        }
        
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return windowManager != null
    }
    
    /**
     * 显示导航悬浮球（仅在轨迹采集模式下）
     */
    fun show(context: Context) {
        // 检查是否正在轨迹采集
        if (!TrajectoryRecorder.isRecording()) {
            Log.d(TAG, "不在轨迹采集模式，不显示导航悬浮球")
            return
        }
        
        if (isShowing) {
            Log.d(TAG, "导航悬浮球已显示，跳过")
            return
        }
        
        if (!initialize(context)) {
            Log.w(TAG, "初始化失败，无法显示导航悬浮球")
            return
        }
        
        val wm = windowManager ?: return
        this.context = context.applicationContext
        
        try {
            // 加载布局
            val inflater = LayoutInflater.from(context)
            overlayView = inflater.inflate(R.layout.overlay_trajectory_navigation, null)
            
            val btnBack = overlayView?.findViewById<Button>(R.id.btnBack)
            val btnHome = overlayView?.findViewById<Button>(R.id.btnHome)
            val btnStop = overlayView?.findViewById<Button>(R.id.btnStop)
            val btnControl = overlayView?.findViewById<Button>(R.id.btnControl)
            
            // 设置返回按钮点击事件
            btnBack?.setOnClickListener {
                Log.d(TAG, "返回按钮被点击")
                handleBackButtonClick()
            }
            
            // 设置主页按钮点击事件
            btnHome?.setOnClickListener {
                Log.d(TAG, "主页按钮被点击")
                handleHomeButtonClick()
            }
            
            // 设置结束轨迹采集按钮点击事件
            btnStop?.setOnClickListener {
                Log.d(TAG, "结束轨迹采集按钮被点击")
                handleStopButtonClick()
            }
            
            // 设置控制按钮点击事件
            btnControl?.setOnClickListener {
                Log.d(TAG, "控制按钮被点击")
                handleControlButtonClick()
            }
            
            // 创建窗口参数
            val density = context.resources.displayMetrics.density
            
            overlayParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 默认显示在右侧
                gravity = Gravity.END or Gravity.TOP // 右侧顶部对齐
                x = (-16 * density).toInt() // 距离右边缘16dp
                // 先设置一个临时位置，等视图测量完成后再更新
                y = 0
            }
            
            // 初始化位置状态为右侧
            isOnRightSide = true
            
            // 添加到窗口管理器
            wm.addView(overlayView, overlayParams)
            
            // 等待视图测量完成，然后更新位置使按钮下边缘在正确位置
            overlayView?.post {
                updateButtonPosition()
            }
            
            isShowing = true
            
            Log.d(TAG, "导航悬浮球已显示")
        } catch (e: SecurityException) {
            Log.e(TAG, "显示导航悬浮球失败：缺少悬浮窗权限", e)
        } catch (e: Exception) {
            Log.e(TAG, "显示导航悬浮球失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 隐藏导航悬浮球
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
            }
            overlayView = null
            isShowing = false
            
            Log.d(TAG, "导航悬浮球已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏导航悬浮球失败: ${e.message}", e)
            // 即使移除失败，也强制更新状态
            overlayView = null
            isShowing = false
        }
    }
    
    /**
     * 检查悬浮球是否显示
     */
    fun isShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 检查触摸位置是否在悬浮窗区域内
     * @param rawX 触摸点的X坐标（屏幕绝对坐标）
     * @param rawY 触摸点的Y坐标（屏幕绝对坐标）
     * @return 是否在悬浮窗区域内
     */
    fun isTouchInOverlayArea(rawX: Float, rawY: Float): Boolean {
        val view = overlayView ?: return false
        val params = overlayParams ?: return false
        val ctx = context ?: return false
        
        try {
            // 获取屏幕尺寸
            val displayMetrics = ctx.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            
            // 获取视图尺寸
            val viewWidth = view.width
            val viewHeight = view.height
            
            if (viewWidth == 0 || viewHeight == 0) {
                // 视图还未测量完成，返回false（保守处理）
                return false
            }
            
            // 根据gravity和x/y计算视图在屏幕上的实际位置
            val left: Int
            val top: Int
            
            when {
                // 右侧对齐
                (params.gravity and Gravity.END) == Gravity.END -> {
                    // x是负数，表示距离右边缘的距离
                    val rightEdge = screenWidth + params.x // params.x是负数，所以用加法
                    left = rightEdge - viewWidth
                    top = params.y
                }
                // 左侧对齐
                (params.gravity and Gravity.START) == Gravity.START -> {
                    left = params.x
                    top = params.y
                }
                else -> {
                    // 默认情况（应该不会发生，但保守处理）
                    left = params.x
                    top = params.y
                }
            }
            
            val right = left + viewWidth
            val bottom = top + viewHeight
            
            // 检查触摸点是否在视图区域内
            val isInArea = rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
            
            return isInArea
        } catch (e: Exception) {
            Log.e(TAG, "检查触摸位置失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 处理悬浮窗区域的触摸事件，触发对应按钮的点击
     * @param rawX 触摸点的X坐标（屏幕绝对坐标）
     * @param rawY 触摸点的Y坐标（屏幕绝对坐标）
     * @return 是否处理了触摸事件
     */
    fun handleTouchInOverlayArea(rawX: Float, rawY: Float): Boolean {
        val view = overlayView ?: return false
        val params = overlayParams ?: return false
        val ctx = context ?: return false
        
        try {
            // 获取屏幕尺寸
            val displayMetrics = ctx.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            
            // 获取视图尺寸
            val viewWidth = view.width
            val viewHeight = view.height
            
            if (viewWidth == 0 || viewHeight == 0) {
                return false
            }
            
            // 根据gravity和x/y计算视图在屏幕上的实际位置
            val left: Int
            val top: Int
            
            when {
                // 右侧对齐
                (params.gravity and Gravity.END) == Gravity.END -> {
                    val rightEdge = screenWidth + params.x
                    left = rightEdge - viewWidth
                    top = params.y
                }
                // 左侧对齐
                (params.gravity and Gravity.START) == Gravity.START -> {
                    left = params.x
                    top = params.y
                }
                else -> {
                    left = params.x
                    top = params.y
                }
            }
            
            // 计算触摸点相对于悬浮窗的坐标
            val relativeX = rawX - left
            val relativeY = rawY - top
            
            // 按钮尺寸：56dp，间距：8dp，布局padding：8dp
            val density = displayMetrics.density
            val buttonSize = (56 * density).toInt()
            val buttonMargin = (8 * density).toInt()
            val layoutPadding = (8 * density).toInt()
            val buttonTotalHeight = buttonSize + buttonMargin
            
            // 减去padding，得到相对于按钮区域的坐标
            val buttonAreaY = relativeY - layoutPadding
            
            // 如果触摸点在padding区域，不在按钮上
            if (buttonAreaY < 0) {
                return false
            }
            
            // 计算触摸点在哪个按钮上
            val buttonIndex = (buttonAreaY / buttonTotalHeight).toInt()
            
            // 检查触摸点是否在按钮的有效区域内（考虑按钮的实际高度）
            val buttonOffset = buttonAreaY % buttonTotalHeight
            if (buttonOffset >= buttonSize) {
                // 触摸点在按钮之间的间距区域，不在按钮上
                return false
            }
            
            // 触发对应按钮的点击
            when (buttonIndex) {
                0 -> {
                    // 返回按钮
                    Log.d(TAG, "触摸在返回按钮上，触发点击")
                    view.findViewById<Button>(R.id.btnBack)?.performClick()
                    return true
                }
                1 -> {
                    // 主页按钮
                    Log.d(TAG, "触摸在主页按钮上，触发点击")
                    view.findViewById<Button>(R.id.btnHome)?.performClick()
                    return true
                }
                2 -> {
                    // 结束轨迹采集按钮
                    Log.d(TAG, "触摸在结束轨迹采集按钮上，触发点击")
                    view.findViewById<Button>(R.id.btnStop)?.performClick()
                    return true
                }
                3 -> {
                    // 控制按钮
                    Log.d(TAG, "触摸在控制按钮上，触发点击")
                    view.findViewById<Button>(R.id.btnControl)?.performClick()
                    return true
                }
                else -> {
                    Log.d(TAG, "触摸在悬浮窗区域内但不在按钮上: buttonIndex=$buttonIndex")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理悬浮窗触摸事件失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 处理返回按钮点击
     * 执行顺序：隐藏悬浮窗 -> 截图 -> 恢复悬浮窗 -> 执行动作 -> 记录事件（带截图）
     */
    private fun handleBackButtonClick() {
        val ctx = context ?: run {
            Log.e(TAG, "Context为空，无法执行返回操作")
            return
        }
        
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "无障碍服务未启动，无法执行返回操作")
            return
        }
        
        // 在协程中执行：隐藏悬浮窗 -> 截图 -> 恢复悬浮窗 -> 执行动作 -> 记录事件
        coroutineScope.launch {
            try {
                var screenshot: Bitmap? = null
                
                // 1. 隐藏悬浮窗
                val wasShowing = isShowing
                if (wasShowing) {
                    Log.d(TAG, "隐藏悬浮窗以便截图")
                    hide()
                    delay(100) // 等待悬浮窗完全移除
                }
                
                // 2. 截图（如果需要上传截图）
                if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                    screenshot = withContext(Dispatchers.IO) {
                        TrajectoryCloudService.captureScreenshotSync(ctx)
                    }
                    Log.d(TAG, if (screenshot != null) "截图成功" else "截图失败")
                }
                
                // 3. 恢复悬浮窗
                if (wasShowing) {
                    Log.d(TAG, "恢复悬浮窗显示")
                    show(ctx)
                    delay(50) // 等待悬浮窗显示完成
                }
                
                // 4. 执行返回操作
                accessibilityService.goBack()
                
                // 5. 记录事件（带截图）
                val event = TrajectoryEvent(
                    type = TrajectoryEventType.BACK_BUTTON,
                    timestamp = System.currentTimeMillis(),
                    packageName = MyAccessibilityService.getCurrentPackageName()
                )
                TrajectoryRecorder.recordEvent(event, screenshot)
                
                Log.d(TAG, "已执行返回操作并记录事件（带截图）")
            } catch (e: Exception) {
                Log.e(TAG, "处理返回按钮点击失败: ${e.message}", e)
                // 确保悬浮窗恢复显示
                if (isShowing.not() && context != null) {
                    show(context!!)
                }
            }
        }
    }
    
    /**
     * 处理主页按钮点击
     * 执行顺序：隐藏悬浮窗 -> 截图 -> 恢复悬浮窗 -> 执行动作 -> 记录事件（带截图）
     */
    private fun handleHomeButtonClick() {
        val ctx = context ?: run {
            Log.e(TAG, "Context为空，无法执行主页操作")
            return
        }
        
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "无障碍服务未启动，无法执行主页操作")
            return
        }
        
        // 在协程中执行：隐藏悬浮窗 -> 截图 -> 恢复悬浮窗 -> 执行动作 -> 记录事件
        coroutineScope.launch {
            try {
                var screenshot: Bitmap? = null
                
                // 1. 隐藏悬浮窗
                val wasShowing = isShowing
                if (wasShowing) {
                    Log.d(TAG, "隐藏悬浮窗以便截图")
                    hide()
                    delay(100) // 等待悬浮窗完全移除
                }
                
                // 2. 截图（如果需要上传截图）
                if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                    screenshot = withContext(Dispatchers.IO) {
                        TrajectoryCloudService.captureScreenshotSync(ctx)
                    }
                    Log.d(TAG, if (screenshot != null) "截图成功" else "截图失败")
                }
                
                // 3. 恢复悬浮窗
                if (wasShowing) {
                    Log.d(TAG, "恢复悬浮窗显示")
                    show(ctx)
                    delay(50) // 等待悬浮窗显示完成
                }
                
                // 4. 执行主页操作
                accessibilityService.goHome()
                
                // 5. 记录事件（带截图）
                val event = TrajectoryEvent(
                    type = TrajectoryEventType.HOME_BUTTON,
                    timestamp = System.currentTimeMillis(),
                    packageName = MyAccessibilityService.getCurrentPackageName()
                )
                TrajectoryRecorder.recordEvent(event, screenshot)
                
                Log.d(TAG, "已执行主页操作并记录事件（带截图）")
            } catch (e: Exception) {
                Log.e(TAG, "处理主页按钮点击失败: ${e.message}", e)
                // 确保悬浮窗恢复显示
                if (isShowing.not() && context != null) {
                    show(context!!)
                }
            }
        }
    }
    
    /**
     * 处理结束轨迹采集按钮点击
     * 若开启了上传截图：先隐藏覆盖层、截一张图并上传，再执行结束流程
     * 否则：直接停止采集、隐藏覆盖层，再启动应用并跳转到轨迹采集页面
     */
    private fun handleStopButtonClick() {
        val ctx = context ?: run {
            Log.e(TAG, "Context为空，无法结束轨迹采集")
            return
        }
        
        coroutineScope.launch {
            try {
                Log.d(TAG, "开始结束轨迹采集流程")
                
                // 若开启了上传截图，先截一张图并上传
                if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                    Log.d(TAG, "上传截图已开启，先截取最后一张图并上传")
                    withContext(Dispatchers.Main) {
                        TrajectoryOverlayManager.hide()
                        hide()
                    }
                    kotlinx.coroutines.delay(150)
                    val screenshot = TrajectoryCloudService.captureScreenshotSync(ctx)
                    val sessionId = TrajectoryRecorder.getCurrentSessionId()
                    if (screenshot != null && sessionId != null) {
                        TrajectoryCloudService.uploadSessionEndScreenshot(ctx, sessionId, screenshot)
                        screenshot.recycle()
                        Log.d(TAG, "已上传会话结束截图")
                    } else {
                        Log.w(TAG, "无法截取或上传会话结束截图: screenshot=${screenshot != null}, sessionId=$sessionId")
                        screenshot?.recycle()
                    }
                }
                
                // 执行结束流程
                withContext(Dispatchers.Main) {
                    performEndTrajectoryCollection(ctx)
                }
            } catch (e: Exception) {
                Log.e(TAG, "结束轨迹采集失败: ${e.message}", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    performEndTrajectoryCollection(ctx)
                }
            }
        }
    }
    
    /**
     * 执行结束轨迹采集的清理和跳转
     */
    private fun performEndTrajectoryCollection(ctx: Context) {
        try {
            TrajectoryRecorder.stopRecording(ctx)
            Log.d(TAG, "已停止轨迹采集")
            
            TrajectoryOverlayManager.hide()
            Log.d(TAG, "已隐藏覆盖层")
            
            hide()
            Log.d(TAG, "已隐藏导航悬浮球")
            
            val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("trajectory_recording_enabled", false).apply()
            Log.d(TAG, "已更新轨迹采集开关为关闭状态")
            
            val packageName = "com.cloudcontrol.demo"
            val intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (isAppRunning(ctx, packageName)) {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    Log.d(TAG, "应用已运行，REORDER_TO_FRONT 回到最新页面")
                }
                putExtra(MainActivity.EXTRA_SHOW_TRAJECTORY_QUERY_DIALOG, true)
            }
            ctx.startActivity(intent)
            Log.d(TAG, "已打开 TopoClaw，显示Query弹窗")
        } catch (e: Exception) {
            Log.e(TAG, "performEndTrajectoryCollection 失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 检查应用是否正在运行
     * @param ctx Context
     * @param packageName 应用包名
     * @return 是否在运行
     */
    private fun isAppRunning(ctx: Context, packageName: String): Boolean {
        return try {
            val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0+ 使用 getRunningAppProcesses
                val runningProcesses = activityManager.runningAppProcesses
                runningProcesses?.any { 
                    // 检查进程名是否匹配包名（进程名可能是包名或包名:进程名）
                    it.processName == packageName || it.processName.startsWith("$packageName:")
                } ?: false
            } else {
                // Android 5.x 及以下使用 getRunningTasks（已废弃，但可用）
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(10) // 检查最近10个任务
                runningTasks.any { it.topActivity?.packageName == packageName }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查应用运行状态失败: ${e.message}", e)
            false // 出错时返回false，使用正常启动方式
        }
    }
    
    /**
     * 更新按钮位置，使按钮下边缘在键盘高度-15px位置
     */
    private fun updateButtonPosition() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        val ctx = context ?: return
        
        try {
            // 获取视图高度（测量完成后才能获取）
            val viewHeight = view.height
            if (viewHeight == 0) {
                // 如果视图还未测量完成，延迟重试
                view.postDelayed({ updateButtonPosition() }, 50)
                return
            }
            
            // 获取用户设置的键盘高度（单位：像素）
            val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val keyboardHeight = prefs.getInt("keyboard_height", 1480)
            
            // 按钮下边缘位置：键盘高度 - 15px（从屏幕顶部计算，y轴从上到下递增）
            val bottomEdgeY = keyboardHeight - 15
            
            // 计算视图顶部位置：下边缘位置 - 视图高度
            val topY = bottomEdgeY - viewHeight
            
            // 更新y位置
            params.y = topY
            wm.updateViewLayout(view, params)
            
            Log.d(TAG, "按钮位置已更新：下边缘在y=${bottomEdgeY}px，视图顶部在y=${topY}px")
        } catch (e: Exception) {
            Log.e(TAG, "更新按钮位置失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理控制按钮点击（切换左右位置）
     */
    private fun handleControlButtonClick() {
        val ctx = context ?: return
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        
        // 切换位置：如果在右侧则移到左侧，如果在左侧则移到右侧
        if (isOnRightSide) {
            moveToLeft()
        } else {
            moveToRight()
        }
    }
    
    /**
     * 移动到左侧
     */
    private fun moveToLeft() {
        val ctx = context ?: return
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        
        val displayMetrics = ctx.resources.displayMetrics
        val density = displayMetrics.density
        
        try {
            // 获取用户设置的键盘高度（单位：像素）
            val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val keyboardHeight = prefs.getInt("keyboard_height", 1480)
            
            // 按钮下边缘位置：键盘高度 - 15px（从屏幕顶部计算，y轴从上到下递增）
            val bottomEdgeY = keyboardHeight - 15
            
            // 获取视图高度
            val viewHeight = view.height
            if (viewHeight == 0) {
                // 如果视图还未测量完成，延迟重试
                view.postDelayed({ moveToLeft() }, 50)
                return
            }
            
            // 计算视图顶部位置：下边缘位置 - 视图高度
            val topY = bottomEdgeY - viewHeight
            
            params.gravity = Gravity.START or Gravity.TOP // 左侧顶部对齐
            params.x = (16 * density).toInt() // 距离左边缘16dp
            params.y = topY // 视图顶部位置，使按钮下边缘在键盘高度-15px位置
            
            wm.updateViewLayout(view, params)
            isOnRightSide = false
            
            Log.d(TAG, "悬浮窗已移动到左侧：下边缘在y=${bottomEdgeY}px")
        } catch (e: Exception) {
            Log.e(TAG, "移动悬浮窗到左侧失败: ${e.message}", e)
        }
    }
    
    /**
     * 移动到右侧
     */
    private fun moveToRight() {
        val ctx = context ?: return
        val params = overlayParams ?: return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        
        val displayMetrics = ctx.resources.displayMetrics
        val density = displayMetrics.density
        
        try {
            // 获取用户设置的键盘高度（单位：像素）
            val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val keyboardHeight = prefs.getInt("keyboard_height", 1480)
            
            // 按钮下边缘位置：键盘高度 - 15px（从屏幕顶部计算，y轴从上到下递增）
            val bottomEdgeY = keyboardHeight - 15
            
            // 获取视图高度
            val viewHeight = view.height
            if (viewHeight == 0) {
                // 如果视图还未测量完成，延迟重试
                view.postDelayed({ moveToRight() }, 50)
                return
            }
            
            // 计算视图顶部位置：下边缘位置 - 视图高度
            val topY = bottomEdgeY - viewHeight
            
            params.gravity = Gravity.END or Gravity.TOP // 右侧顶部对齐
            params.x = (-16 * density).toInt() // 距离右边缘16dp
            params.y = topY // 视图顶部位置，使按钮下边缘在键盘高度-15px位置
            
            wm.updateViewLayout(view, params)
            isOnRightSide = true
            
            Log.d(TAG, "悬浮窗已移动到右侧：下边缘在y=${bottomEdgeY}px")
        } catch (e: Exception) {
            Log.e(TAG, "移动悬浮窗到右侧失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
        windowManager = null
        context = null
        overlayParams = null
        isOnRightSide = true // 重置为默认位置
        coroutineScope.cancel() // 取消所有协程
    }
}

