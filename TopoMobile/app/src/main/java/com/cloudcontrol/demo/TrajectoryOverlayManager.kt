package com.cloudcontrol.demo

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import kotlinx.coroutines.*

/**
 * 轨迹采集覆盖层管理器
 * 管理覆盖层的显示、隐藏和事件处理
 */
object TrajectoryOverlayManager {
    private const val TAG = "TrajectoryOverlay"
    
    private var overlayView: TrajectoryOverlayView? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var context: Context? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    
    // 标记是否正在转发事件（防止无限循环）
    @Volatile private var isForwardingEvent = false
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * 显示覆盖层
     */
    fun show(context: Context) {
        if (isShowing) {
            Log.d(TAG, "覆盖层已显示，跳过")
            return
        }
        
        try {
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(context)) {
                    Log.e(TAG, "没有悬浮窗权限，无法显示覆盖层")
                    return
                }
            }
            
            // 检查无障碍服务是否启动
            val accessibilityService = MyAccessibilityService.getInstance()
            if (accessibilityService == null) {
                Log.e(TAG, "❌ 无障碍服务未启动，无法使用轨迹采集功能")
                Log.e(TAG, "请先开启无障碍服务")
                android.widget.Toast.makeText(
                    context,
                    "请先开启无障碍服务才能使用轨迹采集",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return
            }
            
            this.context = context.applicationContext
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            
            // 创建覆盖层View
            overlayView = TrajectoryOverlayView(context.applicationContext).apply {
                // 设置事件记录回调（支持动作前已采集的 screenshot、xml）
                setOnEventRecorded { event, screenshot, xml ->
                    recordEvent(event, screenshot, xml)
                }
                // 设置键盘状态变化回调，用于更新WindowManager高度
                setOnKeyboardVisibilityChanged { visible ->
                    updateOverlayHeight(visible)
                }
                // 开始监听键盘状态
                startKeyboardMonitoring()
            }
            
            // 创建窗口参数
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                // 注意：不设置 FLAG_NOT_TOUCHABLE，允许接收触摸事件
                // 设置 FLAG_NOT_TOUCH_MODAL 让事件可以传递给后面的窗口（键盘区域需要穿透）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            
            overlayParams = params
            
            // 添加到窗口管理器
            windowManager?.addView(overlayView, params)
            isShowing = true
            
            Log.d(TAG, "轨迹采集覆盖层已显示")
        } catch (e: SecurityException) {
            Log.e(TAG, "显示覆盖层失败：缺少悬浮窗权限", e)
        } catch (e: Exception) {
            Log.e(TAG, "显示覆盖层失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 隐藏覆盖层
     */
    fun hide() {
        if (!isShowing) {
            // 即使 isShowing 为 false，也尝试清理（防止状态不同步）
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                    view.cleanup()
                } catch (e: Exception) {
                    Log.w(TAG, "清理覆盖层View失败: ${e.message}", e)
                }
            }
            overlayView = null
            isShowing = false
            return
        }
        
        try {
            overlayView?.let { view ->
                windowManager?.removeView(view)
                view.cleanup()
            }
            overlayView = null
            isShowing = false
            
            Log.d(TAG, "轨迹采集覆盖层已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏覆盖层失败: ${e.message}", e)
            // 即使移除失败，也强制更新状态，防止状态不同步
            overlayView = null
            isShowing = false
            Log.w(TAG, "已强制更新覆盖层状态为隐藏")
        }
    }
    
    /**
     * 检查覆盖层是否显示
     */
    fun isShowing(): Boolean {
        // 检查覆盖层View是否真的存在
        if (isShowing && overlayView != null) {
            try {
                // 尝试检查View是否还在WindowManager中
                overlayView?.let { view ->
                    if (view.parent == null) {
                        // View已从WindowManager移除，但状态未更新
                        Log.w(TAG, "覆盖层View已从WindowManager移除，但状态未更新")
                        isShowing = false
                        return false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查覆盖层状态失败: ${e.message}", e)
            }
        }
        return isShowing
    }
    
    /**
     * 重置覆盖层状态
     * 用于窗口切换后恢复覆盖层功能
     */
    fun resetState() {
        try {
            // 重置事件转发标志
            isForwardingEvent = false
            
            // 确保事件拦截已启用
            overlayView?.setEventInterceptionEnabled(true)
            
            Log.d(TAG, "覆盖层状态已重置")
        } catch (e: Exception) {
            Log.e(TAG, "重置覆盖层状态失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查是否正在转发事件（供外部调用）
     */
    fun isForwardingEvent(): Boolean {
        return isForwardingEvent
    }
    
    /**
     * 设置键盘状态（供外部调用，如AccessibilityService）
     */
    fun setKeyboardVisible(visible: Boolean) {
        overlayView?.setKeyboardVisible(visible)
        // 动态调整覆盖层高度，让键盘区域不被覆盖
        updateOverlayHeight(visible)
    }
    
    private fun getStatusBarHeight(context: Context?): Int {
        if (context == null) return 0
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val userConfig = prefs.getInt("status_bar_height", -1)
        if (userConfig >= 0) return userConfig
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        if (result == 0) {
            result = (24 * context.resources.displayMetrics.density).toInt()
        }
        return result
    }
    
    /**
     * 获取当前应使用的覆盖层 LayoutParams（高度根据键盘状态统一计算）
     * OnePlus 等设备会忽略仅 height 的设置，必须 width/height 都为显式像素才生效
     */
    private fun getLayoutParamsForCurrentState(): WindowManager.LayoutParams {
        val baseParams = overlayParams ?: createDefaultLayoutParams()
        val dm = context?.resources?.displayMetrics
        val screenWidth = dm?.widthPixels ?: 1080
        val screenHeight = dm?.heightPixels ?: Int.MAX_VALUE
        val keyboardVisible = overlayView?.isKeyboardVisible() ?: false
        if (keyboardVisible) {
            val prefs = context?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val userConfig = prefs?.getInt("keyboard_height", 1480) ?: 1480
            val sb = getStatusBarHeight(context)
            val h = (userConfig - sb).coerceIn(0, screenHeight)
            baseParams.width = screenWidth
            baseParams.height = h
            Log.d(TAG, "getLayoutParamsForCurrentState: userConfig=$userConfig - statusBar=$sb -> height=$h")
        } else {
            baseParams.width = WindowManager.LayoutParams.MATCH_PARENT
            baseParams.height = WindowManager.LayoutParams.MATCH_PARENT
        }
        return baseParams
    }
    
    private fun createDefaultLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }
    
    /**
     * 更新覆盖层高度（键盘弹出时缩小覆盖层，让键盘区域不被覆盖）
     * 关键：必须移除后重新添加，updateViewLayout 在某些设备上不会限制触摸区域，
     * 导致 y>=keyboardHeight 的触摸仍会先到达覆盖层。Android 不允许在返回 false 时将
     * 事件传递给下层窗口（tapjacking 防护），所以必须让覆盖层在系统层面就不接收键盘区域的触摸。
     */
    private fun updateOverlayHeight(keyboardVisible: Boolean) {
        val view = overlayView
        val wm = windowManager
        val params = overlayParams
        
        if (view == null || wm == null || params == null || !isShowing) {
            return
        }
        
        try {
            val displayMetrics = context?.resources?.displayMetrics
            if (displayMetrics == null) {
                return
            }
            
            val screenHeight = displayMetrics.heightPixels
            val overlayHeight = if (keyboardVisible) {
                val prefs = context?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val userConfig = prefs?.getInt("keyboard_height", 1480) ?: 1480
                val sb = getStatusBarHeight(context)
                val height = (userConfig - sb).coerceIn(0, screenHeight)
                Log.d(TAG, "覆盖层高度: userConfig=$userConfig - statusBar=$sb -> overlayHeight=$height")
                height
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            }
            
            if (params.height != overlayHeight) {
                val oldHeight = params.height
                if (keyboardVisible) {
                    params.width = displayMetrics.widthPixels
                    params.height = overlayHeight
                } else {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                }
                
                // 移除后重新添加。部分设备(OnePlus)需 width/height 均为显式像素才能正确限制高度
                // updateViewLayout 在某些设备上不限制 InputDispatcher 的 touchable region
                try {
                    wm.removeView(view)
                    Log.d(TAG, "移除覆盖层以重新添加（高度 ${oldHeight} -> $overlayHeight）")
                } catch (e: Exception) {
                    Log.w(TAG, "移除覆盖层失败（可能已移除）: ${e.message}")
                }
                
                try {
                    wm.addView(view, params)
                    Log.d(TAG, "覆盖层已重新添加: ${if (keyboardVisible) "键盘弹出，高度=$overlayHeight，触摸区域 y∈[0,$overlayHeight)" else "键盘收起，全屏"}")
                } catch (e: Exception) {
                    Log.e(TAG, "重新添加覆盖层失败: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新覆盖层高度失败: ${e.message}", e)
        }
    }
    
    /**
     * 刷新键盘高度缓存（供外部调用，如设置页面）
     */
    fun refreshKeyboardHeight() {
        overlayView?.refreshKeyboardHeight()
        // 如果键盘当前是显示状态，重新更新覆盖层高度
        overlayView?.let {
            val keyboardVisible = it.isKeyboardVisible()
            if (keyboardVisible) {
                updateOverlayHeight(true)
            }
        }
    }
    
    /**
     * 记录事件
     * @param screenshot 动作前已采集的截图（可选）
     * @param xml 动作前已采集的 XML（可选）
     */
    private fun recordEvent(event: TrajectoryEvent, screenshot: android.graphics.Bitmap? = null, xml: String? = null) {
        // 获取当前应用包名
        val packageName = getCurrentPackageName()
        val updatedEvent = event.copy(packageName = packageName)
        
        // 记录到TrajectoryRecorder
        TrajectoryRecorder.recordEvent(updatedEvent, screenshot, xml)
        
        Log.d(TAG, "记录事件: ${event.type} at (${event.x}, ${event.y})")
    }
    
    /**
     * 转发点击事件
     */
    fun forwardClick(x: Int, y: Int) {
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "❌ 无障碍服务未启动，无法转发点击事件: ($x, $y)")
            Log.e(TAG, "请确保已开启无障碍服务")
            return
        }
        
        // 如果正在转发，直接跳过（避免覆盖层状态混乱）
        if (isForwardingEvent) {
            Log.w(TAG, "正在转发事件中，跳过重复转发: ($x, $y)")
            return
        }
        
        processClickInternal(x, y)
    }
    
    /**
     * 内部点击处理方法
     */
    private fun processClickInternal(x: Int, y: Int) {
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            return
        }
        
        scope.launch {
            var overlayRemoved = false
            try {
                isForwardingEvent = true
                
                Log.d(TAG, "开始转发点击事件: ($x, $y)")
                
                // 临时移除覆盖层，确保手势事件不被拦截
                val view = overlayView
                val wm = windowManager
                if (view != null && wm != null) {
                    try {
                        wm.removeView(view)
                        overlayRemoved = true
                        Log.d(TAG, "临时移除覆盖层以转发事件")
                        // 等待覆盖层完全移除，确保手势执行时覆盖层不在
                        delay(50) // 增加等待时间，确保覆盖层完全移除
                    } catch (e: Exception) {
                        Log.e(TAG, "移除覆盖层失败: ${e.message}", e)
                        // 如果移除失败，尝试禁用拦截
                        view.setEventInterceptionEnabled(false)
                        delay(20) // 增加等待时间
                    }
                }
                
                // 执行手势（performClick是suspend函数，会自动等待完成）
                // performClick内部会等待手势完成（手势持续100ms）
                val success = accessibilityService.performClick(x, y)
                
                if (success) {
                    Log.d(TAG, "✓ 点击事件转发成功: ($x, $y)")
                    // 手势执行成功，等待手势事件完全处理完成
                    // performClick已经等待手势完成，这里再等待50ms确保事件处理完成
                    delay(50)
                } else {
                    Log.e(TAG, "✗ 点击事件转发失败: ($x, $y)")
                    // 手势执行失败，也等待一下再恢复
                    delay(30)
                }
            } catch (e: Exception) {
                Log.e(TAG, "转发点击事件异常: ${e.message}", e)
                e.printStackTrace()
            } finally {
                // 恢复覆盖层（在手势完成后）
                if (overlayRemoved && overlayView != null && windowManager != null && context != null) {
                    try {
                        // 不再额外延迟，因为上面已经等待过了
                        
                        // 检查覆盖层是否真的被移除了
                        val view = overlayView
                        if (view != null && view.parent == null) {
                            val params = getLayoutParamsForCurrentState()
                            windowManager?.addView(view, params)
                            isShowing = true
                            Log.d(TAG, "覆盖层已恢复（点击后），高度=${params.height}")
                        } else {
                            // View还在，不需要重新添加
                            Log.d(TAG, "覆盖层View仍在，无需恢复")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "恢复覆盖层失败: ${e.message}", e)
                        // 如果恢复失败，尝试重新显示
                        try {
                            context?.let { ctx ->
                                show(ctx)
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "重新显示覆盖层也失败: ${e2.message}", e2)
                        }
                    }
                } else {
                    // 如果只是禁用了拦截，恢复拦截
                    overlayView?.setEventInterceptionEnabled(true)
                }
                isForwardingEvent = false
                Log.d(TAG, "事件转发完成")
            }
        }
    }
    
    /**
     * 转发滑动事件
     * 优化版本：增加等待时间，使用手势回调准确判断完成时间
     * @param startX 起始X坐标
     * @param startY 起始Y坐标
     * @param endX 结束X坐标
     * @param endY 结束Y坐标
     * @param duration 滑动持续时间（毫秒），如果为null则使用默认值1200ms。轨迹采集时会传入真实的duration
     */
    fun forwardSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long? = null) {
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "❌ 无障碍服务未启动，无法转发滑动事件")
            return
        }
        
        // 防止重复转发
        if (isForwardingEvent) {
            Log.w(TAG, "正在转发事件中，跳过重复转发")
            return
        }
        
        scope.launch {
            var overlayRemoved = false
            var gestureSuccess = false
            try {
                isForwardingEvent = true
                
                Log.d(TAG, "开始转发滑动事件: ($startX, $startY) -> ($endX, $endY)")
                
                // 临时移除覆盖层，确保滑动手势事件不被拦截
                val view = overlayView
                val wm = windowManager
                if (view != null && wm != null) {
                    try {
                        wm.removeView(view)
                        overlayRemoved = true
                        Log.d(TAG, "临时移除覆盖层以转发滑动事件")
                        // 优化：增加等待时间，确保覆盖层完全移除（从20ms增加到100ms）
                        delay(100)
                        
                        // 双重检查：确认覆盖层确实被移除
                        if (view.parent != null) {
                            Log.w(TAG, "覆盖层移除后仍存在parent，再等待50ms")
                            delay(50)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "移除覆盖层失败: ${e.message}", e)
                        // 如果移除失败，尝试禁用拦截
                        view.setEventInterceptionEnabled(false)
                        // 优化：增加禁用拦截后的等待时间（从10ms增加到50ms）
                        delay(50)
                    }
                }
                
                // 执行手势（performSwipe是suspend函数，会自动等待手势完成）
                // 使用真实的duration（如果提供），否则使用默认值1200ms
                // 确保duration在合理范围内（最小100ms，最大5000ms）
                val baseDuration = duration ?: 1200L
                val swipeDuration = baseDuration.coerceIn(100L, 5000L)
                if (baseDuration != swipeDuration) {
                    Log.w(TAG, "swipe duration被限制: ${baseDuration}ms -> ${swipeDuration}ms")
                }
                Log.d(TAG, "开始执行滑动手势... duration=${swipeDuration}ms (原始: ${duration ?: "默认"}ms)")
                gestureSuccess = accessibilityService.performSwipe(startX, startY, endX, endY, swipeDuration)
                
                if (gestureSuccess) {
                    Log.d(TAG, "✓ 滑动手势执行成功，等待事件处理完成...")
                    // 优化：手势完成后，额外等待一段时间确保事件完全处理完成
                    // 不再使用固定延迟，而是等待手势回调完成后再等待额外时间
                    delay(100) // 等待系统处理手势事件
                } else {
                    Log.e(TAG, "✗ 滑动手势执行失败")
                    // 即使失败也等待一下，避免状态混乱
                    delay(50)
                }
            } catch (e: Exception) {
                Log.e(TAG, "转发滑动事件异常: ${e.message}", e)
                e.printStackTrace()
            } finally {
                // 恢复覆盖层（在手势完成后）
                if (overlayRemoved && overlayView != null && windowManager != null && context != null) {
                    try {
                        // 优化：在手势完成后，再等待一段时间确保完全处理完成
                        delay(50)
                        
                        // 检查覆盖层是否真的被移除了
                        val view = overlayView
                        if (view != null) {
                            // 优化：更严格的检查，确保View确实不在WindowManager中
                            val isViewRemoved = try {
                                view.parent == null
                            } catch (e: Exception) {
                                // 如果检查parent时出错，认为View已被移除
                                true
                            }
                            
                            if (isViewRemoved) {
                                val params = getLayoutParamsForCurrentState()
                                windowManager?.addView(view, params)
                                isShowing = true
                                Log.d(TAG, "覆盖层已恢复（滑动后），高度=${params.height}，手势结果: ${if (gestureSuccess) "成功" else "失败"}")
                            } else {
                                // View还在，不需要重新添加
                                Log.d(TAG, "覆盖层View仍在，无需恢复")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "恢复覆盖层失败: ${e.message}", e)
                        // 如果恢复失败，尝试重新显示
                        try {
                            context?.let { ctx ->
                                show(ctx)
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "重新显示覆盖层也失败: ${e2.message}", e2)
                        }
                    }
                } else {
                    // 如果只是禁用了拦截，恢复拦截
                    overlayView?.setEventInterceptionEnabled(true)
                    Log.d(TAG, "恢复事件拦截（覆盖层未移除）")
                }
                isForwardingEvent = false
                Log.d(TAG, "滑动事件转发完成，最终结果: ${if (gestureSuccess) "成功" else "失败"}")
            }
        }
    }
    
    /**
     * 转发长按事件
     * 优化版本：移除覆盖层，使用手势回调准确判断完成时间
     */
    fun forwardLongClick(x: Int, y: Int) {
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "❌ 无障碍服务未启动，无法转发长按事件")
            return
        }
        
        // 防止重复转发
        if (isForwardingEvent) {
            Log.w(TAG, "正在转发事件中，跳过重复转发")
            return
        }
        
        scope.launch {
            var overlayRemoved = false
            var gestureSuccess = false
            try {
                isForwardingEvent = true
                
                Log.d(TAG, "开始转发长按事件: ($x, $y)")
                
                // 临时移除覆盖层，确保长按手势事件不被拦截
                val view = overlayView
                val wm = windowManager
                if (view != null && wm != null) {
                    try {
                        wm.removeView(view)
                        overlayRemoved = true
                        Log.d(TAG, "临时移除覆盖层以转发长按事件")
                        // 优化：增加等待时间，确保覆盖层完全移除（从10ms增加到100ms）
                        delay(100)
                        
                        // 双重检查：确认覆盖层确实被移除
                        if (view.parent != null) {
                            Log.w(TAG, "覆盖层移除后仍存在parent，再等待50ms")
                            delay(50)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "移除覆盖层失败: ${e.message}", e)
                        // 如果移除失败，尝试禁用拦截
                        view.setEventInterceptionEnabled(false)
                        // 优化：增加禁用拦截后的等待时间（从10ms增加到50ms）
                        delay(50)
                    }
                }
                
                // 执行手势（performLongClick是suspend函数，会自动等待手势完成）
                Log.d(TAG, "开始执行长按手势...")
                gestureSuccess = accessibilityService.performLongClick(x, y)
                
                if (gestureSuccess) {
                    Log.d(TAG, "✓ 长按手势执行成功，等待事件处理完成...")
                    // 优化：手势完成后，额外等待一段时间确保事件完全处理完成
                    // 不再使用固定延迟，而是等待手势回调完成后再等待额外时间
                    delay(100) // 等待系统处理手势事件
                } else {
                    Log.e(TAG, "✗ 长按手势执行失败")
                    // 即使失败也等待一下，避免状态混乱
                    delay(50)
                }
            } catch (e: Exception) {
                Log.e(TAG, "转发长按事件异常: ${e.message}", e)
                e.printStackTrace()
            } finally {
                // 恢复覆盖层（在手势完成后）
                if (overlayRemoved && overlayView != null && windowManager != null && context != null) {
                    try {
                        // 优化：在手势完成后，再等待一段时间确保完全处理完成
                        delay(50)
                        
                        // 检查覆盖层是否真的被移除了
                        val view = overlayView
                        if (view != null) {
                            // 优化：更严格的检查，确保View确实不在WindowManager中
                            val isViewRemoved = try {
                                view.parent == null
                            } catch (e: Exception) {
                                // 如果检查parent时出错，认为View已被移除
                                true
                            }
                            
                            if (isViewRemoved) {
                                val params = getLayoutParamsForCurrentState()
                                windowManager?.addView(view, params)
                                isShowing = true
                                Log.d(TAG, "覆盖层已恢复（长按后），高度=${params.height}，手势结果: ${if (gestureSuccess) "成功" else "失败"}")
                            } else {
                                // View还在，不需要重新添加
                                Log.d(TAG, "覆盖层View仍在，无需恢复")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "恢复覆盖层失败: ${e.message}", e)
                        // 如果恢复失败，尝试重新显示
                        try {
                            context?.let { ctx ->
                                show(ctx)
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "重新显示覆盖层也失败: ${e2.message}", e2)
                        }
                    }
                } else {
                    // 如果只是禁用了拦截，恢复拦截
                    overlayView?.setEventInterceptionEnabled(true)
                    Log.d(TAG, "恢复事件拦截（覆盖层未移除）")
                }
                isForwardingEvent = false
                Log.d(TAG, "长按事件转发完成，最终结果: ${if (gestureSuccess) "成功" else "失败"}")
            }
        }
    }
    
    /**
     * 获取当前应用包名
     */
    private fun getCurrentPackageName(): String? {
        return try {
            val accessibilityService = MyAccessibilityService.getInstance()
            accessibilityService?.let {
                val rootNode = it.rootInActiveWindow
                rootNode?.packageName?.toString()?.also {
                    rootNode.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取当前包名失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
        scope.cancel()
    }
}

