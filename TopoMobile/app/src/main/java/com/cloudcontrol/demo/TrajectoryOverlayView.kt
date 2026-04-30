package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlinx.coroutines.*

/**
 * 轨迹采集覆盖层View
 * 拦截所有触摸事件，记录后转发到下层应用
 */
class TrajectoryOverlayView(context: Context) : FrameLayout(context) {
    
    companion object {
        private const val TAG = "TrajectoryOverlay"
        // 延迟转发时间（毫秒）- 减少延迟以提高响应速度
        private const val CLICK_DELAY_MS = 10L       // 点击延迟（减少到10ms）
        private const val SWIPE_DELAY_MS = 20L       // 滑动延迟（减少到20ms）
        private const val LONG_CLICK_DELAY_MS = 50L  // 长按延迟（减少到50ms）
        // 长按判断时间（毫秒）
        private const val LONG_PRESS_THRESHOLD_MS = 500L
    }
    
    private var onEventRecorded: ((TrajectoryEvent, android.graphics.Bitmap?, String?) -> Unit)? = null
    
    // 键盘状态变化回调，用于通知Manager更新WindowManager高度
    private var onKeyboardVisibilityChanged: ((Boolean) -> Unit)? = null
    
    // 是否启用事件拦截（用于防止无限循环）
    @Volatile private var eventInterceptionEnabled = true
    
    // 触摸事件状态
    private var touchStartTime: Long = 0
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var touchDownTime: Long = 0
    private var isLongPress = false
    private var longPressJob: Job? = null
    private var currentSwipeDuration: Long? = null // 当前滑动的真实duration（毫秒）
    private var isTouchInOverlayArea = false // 标记触摸是否在悬浮窗区域开始
    
    // 键盘状态监听
    private var keyboardVisible = false
    private var lastRootViewHeight = 0
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 调试用：键盘弹出时只覆盖 0~keyboardHeight 的蓝色子 View，避免受 WindowManager/父布局影响
    private var debugOverlayRect: View? = null
    
    init {
        setBackgroundColor(Color.TRANSPARENT)
        // 确保View不拦截被遮挡的触摸事件
        setFilterTouchesWhenObscured(false)
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 覆盖层挂载后立即根据当前状态更新显示（解决键盘未弹出时覆盖层不显示的问题）
        post { updateDebugOverlay(keyboardVisible, getOverlayHeight()) }
    }
    
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 如果键盘弹出，限制View高度 = 用户配置 - 状态栏（补偿状态栏位移）
        if (keyboardVisible) {
            val overlayHeight = getOverlayHeight()
            val screenHeight = resources.displayMetrics.heightPixels
            val limitedHeightSpec = View.MeasureSpec.makeMeasureSpec(
                overlayHeight.coerceIn(0, screenHeight),
                View.MeasureSpec.EXACTLY
            )
            super.onMeasure(widthMeasureSpec, limitedHeightSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
    
    /**
     * 设置是否启用事件拦截
     */
    fun setEventInterceptionEnabled(enabled: Boolean) {
        eventInterceptionEnabled = enabled
        Log.d(TAG, "事件拦截已${if (enabled) "启用" else "禁用"}")
    }
    
    /**
     * 设置事件记录回调
     * @param callback (event, screenshot?, xml?) 当有动作前采集时，screenshot 和 xml 非 null
     */
    fun setOnEventRecorded(callback: (TrajectoryEvent, android.graphics.Bitmap?, String?) -> Unit) {
        onEventRecorded = callback
    }
    
    /**
     * 设置键盘状态变化回调（用于通知Manager更新WindowManager高度）
     */
    fun setOnKeyboardVisibilityChanged(callback: (Boolean) -> Unit) {
        onKeyboardVisibilityChanged = callback
    }
    
    /**
     * 设置键盘状态（从外部同步，如从AccessibilityService）
     */
    fun setKeyboardVisible(visible: Boolean) {
        if (keyboardVisible != visible) {
            keyboardVisible = visible
            val overlayHeight = getOverlayHeight()
            Log.d(TAG, "键盘状态已更新: ${if (visible) "显示" else "隐藏"}, overlayHeight=$overlayHeight (y >= ${getKeyboardHeight()} 为键盘区域)")
            updateDebugOverlay(visible, overlayHeight)
            requestLayout()
            invalidate()
            onKeyboardVisibilityChanged?.invoke(visible)
        }
    }
    
    /**
     * 添加/更新覆盖子 View
     * 根据"显示覆盖层"设置：开启时始终显示蓝色半透明覆盖层（键盘弹出时为 overlayHeight，否则全屏）；
     * 关闭时为不可见透明
     */
    private fun updateDebugOverlay(keyboardVisible: Boolean, overlayHeight: Int) {
        val showOverlay = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("show_overlay", false)
        if (showOverlay) {
            val rect = debugOverlayRect ?: View(context).apply {
                isClickable = false
                isFocusable = false
            }.also { debugOverlayRect = it }
            rect.setBackgroundColor(Color.argb(102, 0, 0, 255))
            val targetHeight = if (keyboardVisible) overlayHeight else ViewGroup.LayoutParams.MATCH_PARENT
            if (rect.parent == null) {
                addView(rect, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight))
                Log.d(TAG, "覆盖层已添加，高度=${if (keyboardVisible) overlayHeight else "全屏"}, 显示=true")
            } else {
                val lp = rect.layoutParams
                if (lp != null && lp.height != targetHeight) {
                    lp.height = targetHeight
                    rect.layoutParams = lp
                    Log.d(TAG, "覆盖层高度已更新: ${if (keyboardVisible) overlayHeight else "全屏"}")
                }
            }
        } else {
            debugOverlayRect?.let {
                if (it.parent != null) {
                    removeView(it)
                    Log.d(TAG, "覆盖层已移除")
                }
            }
        }
    }
    
    /**
     * 获取键盘可见状态（供外部查询）
     */
    fun isKeyboardVisible(): Boolean {
        return keyboardVisible
    }
    
    /**
     * 开始监听键盘状态
     */
    fun startKeyboardMonitoring() {
        try {
            viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
            Log.d(TAG, "开始监听键盘状态")
        } catch (e: Exception) {
            Log.e(TAG, "开始监听键盘状态失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止监听键盘状态
     */
    fun stopKeyboardMonitoring() {
        try {
            viewTreeObserver.removeOnGlobalLayoutListener(keyboardLayoutListener)
            Log.d(TAG, "停止监听键盘状态")
        } catch (e: Exception) {
            Log.e(TAG, "停止监听键盘状态失败: ${e.message}", e)
        }
    }
    
    /**
     * 键盘状态监听器
     */
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (!TrajectoryRecorder.isRecording()) {
            return@OnGlobalLayoutListener
        }
        
        try {
            // 获取屏幕高度
            val displayMetrics = resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels
            
            // 获取窗口可见区域（相对于屏幕）
            val rect = android.graphics.Rect()
            getWindowVisibleDisplayFrame(rect)
            
            // 计算键盘高度（屏幕高度 - 可见区域底部）
            val keypadHeight = screenHeight - rect.bottom
            
            // 添加调试日志
            if (keypadHeight > 0 || keyboardVisible) {
                Log.d(TAG, "键盘检测: screenHeight=$screenHeight, rect.bottom=${rect.bottom}, keypadHeight=$keypadHeight, keyboardVisible=$keyboardVisible")
            }
            
            // 如果键盘高度超过屏幕高度的15%，认为键盘已弹出
            val keyboardThreshold = screenHeight * 0.15f
            val isKeyboardVisible = keypadHeight > keyboardThreshold
            
            // 如果键盘状态发生变化，记录事件并通知Manager更新WindowManager高度
            if (isKeyboardVisible != keyboardVisible) {
                // 使用setKeyboardVisible方法，这样会通知Manager更新WindowManager高度
                setKeyboardVisible(isKeyboardVisible)
                
                val eventType = if (isKeyboardVisible) {
                    TrajectoryEventType.KEYBOARD_SHOW
                } else {
                    TrajectoryEventType.KEYBOARD_HIDE
                }
                
                recordEvent(
                    type = eventType
                )
                
                Log.d(TAG, if (isKeyboardVisible) "键盘弹出 (高度: $keypadHeight, 阈值: $keyboardThreshold)" else "键盘收起 (高度: $keypadHeight)")
            }
            
            lastRootViewHeight = screenHeight
        } catch (e: Exception) {
            Log.e(TAG, "检测键盘状态失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // 如果键盘弹出，优先检查触摸位置是否在键盘区域（在dispatch阶段就返回false，让事件完全穿透）
        if (keyboardVisible) {
            val keyboardHeight = getKeyboardHeight()
            
            // 如果触摸位置在键盘区域（y >= keyboardHeight），不拦截，让事件穿透到键盘
            // 例如：键盘高度设置为1480，则 y >= 1480 的区域是键盘区域
            if (event.rawY >= keyboardHeight) {
                // 添加调试日志（仅在ACTION_DOWN时记录，避免日志过多）
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.d(TAG, "键盘区域触摸，dispatch阶段直接穿透: y=${event.rawY}, keyboardHeight=$keyboardHeight")
                }
                // 在dispatch阶段就返回false，事件不会进入onTouchEvent，直接穿透到下层
                return false
            }
        }
        
        // 继续正常的dispatch流程
        return super.dispatchTouchEvent(event)
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 优先检查触摸位置是否在导航悬浮窗区域内
        // 如果在悬浮窗区域内，手动触发按钮点击（因为覆盖层会拦截事件）
        val isInOverlayArea = TrajectoryNavigationOverlayManager.isShowing() && 
            TrajectoryNavigationOverlayManager.isTouchInOverlayArea(event.rawX, event.rawY)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录触摸是否在悬浮窗区域开始
                isTouchInOverlayArea = isInOverlayArea
                
                if (isTouchInOverlayArea) {
                    Log.d(TAG, "触摸位置在导航悬浮窗区域内，手动触发按钮点击: (${event.rawX}, ${event.rawY})")
                    // 手动触发按钮点击
                    val handled = TrajectoryNavigationOverlayManager.handleTouchInOverlayArea(event.rawX, event.rawY)
                    if (handled) {
                        // 如果成功处理了按钮点击，消费事件，不传递给下层
                        return true
                    }
                    // 如果不在按钮上，也消费事件，避免触发滑动
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // 如果触摸在悬浮窗区域开始，整个触摸序列都应该被消费
                if (isTouchInOverlayArea) {
                    // 消费事件，避免触发滑动
                    Log.d(TAG, "触摸在悬浮窗区域，消费MOVE事件，避免触发滑动")
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 如果触摸在悬浮窗区域开始，整个触摸序列都应该被消费
                if (isTouchInOverlayArea) {
                    // 重置标记，准备下次触摸
                    Log.d(TAG, "触摸在悬浮窗区域结束，重置标记，消费事件")
                    isTouchInOverlayArea = false
                    // 消费事件，避免触发滑动
                    return true
                }
            }
        }
        
        // 如果触摸不在悬浮窗区域，继续后续处理
        if (isInOverlayArea && event.action == MotionEvent.ACTION_DOWN) {
            // 这种情况不应该发生，但为了安全起见
            return true
        }
        
        // 注意：键盘区域的检查已经在dispatchTouchEvent中处理，这里不需要再检查
        
        // 如果不在记录状态，不拦截，让事件穿透（防止关闭轨迹采集后仍拦截事件）
        if (!TrajectoryRecorder.isRecording()) {
            return false
        }
        
        // 如果事件拦截被禁用（正在转发事件），不拦截，让事件穿透
        if (!eventInterceptionEnabled) {
            // 不记录日志，避免日志过多
            return false
        }
        
        // 检查是否正在转发事件（双重检查，防止时序问题）
        if (TrajectoryOverlayManager.isForwardingEvent()) {
            return false
        }
        
        // 记录事件
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                handleActionDown(event)
            }
            MotionEvent.ACTION_MOVE -> {
                handleActionMove(event)
            }
            MotionEvent.ACTION_UP -> {
                handleActionUp(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                handleActionCancel(event)
            }
        }
        
        // 返回true表示消费事件，阻止传递到下层
        // 事件会通过AccessibilityService转发
        return true
    }
    
    /**
     * 检查是否应该拦截事件
     * 这个方法可以被外部调用来检查拦截状态
     */
    fun shouldInterceptEvent(): Boolean {
        return eventInterceptionEnabled
    }
    
    /**
     * 处理 ACTION_DOWN
     */
    private fun handleActionDown(event: MotionEvent) {
        touchDownTime = System.currentTimeMillis()
        touchStartTime = event.eventTime
        touchStartX = event.rawX
        touchStartY = event.rawY
        isLongPress = false
        currentSwipeDuration = null // 重置swipe duration
        isTouchInOverlayArea = false // 重置悬浮窗区域标记
        
        // 取消之前的长按检测
        longPressJob?.cancel()
        
        // 不记录 ACTION_DOWN 的 TOUCH 事件（减少噪音，只在最终确定是点击时才记录）
        
        // 启动长按检测
        longPressJob = scope.launch {
            delay(LONG_PRESS_THRESHOLD_MS)
            if (!isLongPress) {
                isLongPress = true
                // 先采集再记录再转发（确保截图/XML 为动作前状态）
                val (screenshot, xml) = if (TrajectoryCloudConfig.isEnabled() && (TrajectoryCloudConfig.shouldUploadScreenshot() || TrajectoryCloudConfig.isXmlEnabled())) {
                    TrajectoryCloudService.captureBeforeAction(context.applicationContext)
                } else Pair(null, null)
                recordEvent(
                    type = TrajectoryEventType.LONG_CLICK,
                    x = touchStartX.toInt(),
                    y = touchStartY.toInt(),
                    duration = System.currentTimeMillis() - touchDownTime,
                    screenshot = screenshot,
                    xml = xml
                )
                forwardEvent(event, LONG_CLICK_DELAY_MS)
            }
        }
    }
    
    /**
     * 处理 ACTION_MOVE
     */
    private fun handleActionMove(event: MotionEvent) {
        val currentX = event.rawX
        val currentY = event.rawY
        val distance = Math.sqrt(
            Math.pow((currentX - touchStartX).toDouble(), 2.0) +
            Math.pow((currentY - touchStartY).toDouble(), 2.0)
        ).toFloat()
        
        // 如果移动距离超过阈值，取消长按检测
        val moveThreshold = 10f * resources.displayMetrics.density // 10dp
        if (distance > moveThreshold) {
            longPressJob?.cancel()
            isLongPress = false
        }
        
        // 不记录 ACTION_MOVE 的 TOUCH 事件（减少噪音，只在最终确定是滑动时才记录）
    }
    
    /**
     * 处理 ACTION_UP
     */
    private fun handleActionUp(event: MotionEvent) {
        longPressJob?.cancel()
        
        val currentX = event.rawX
        val currentY = event.rawY
        val distance = Math.sqrt(
            Math.pow((currentX - touchStartX).toDouble(), 2.0) +
            Math.pow((currentY - touchStartY).toDouble(), 2.0)
        ).toFloat()
        
        val duration = event.eventTime - touchStartTime
        val moveThreshold = 10f * resources.displayMetrics.density // 10dp
        
        if (isLongPress) {
            // 长按事件已在长按检测中记录
            forwardEvent(event, 0L) // 立即转发UP事件
        } else if (distance > moveThreshold) {
            // 滑动事件：先采集再记录再转发
            currentSwipeDuration = duration
            scope.launch {
                val (screenshot, xml) = if (TrajectoryCloudConfig.isEnabled() && (TrajectoryCloudConfig.shouldUploadScreenshot() || TrajectoryCloudConfig.isXmlEnabled())) {
                    TrajectoryCloudService.captureBeforeAction(context.applicationContext)
                } else Pair(null, null)
                recordEvent(
                    type = TrajectoryEventType.SWIPE,
                    startX = touchStartX.toInt(),
                    startY = touchStartY.toInt(),
                    endX = currentX.toInt(),
                    endY = currentY.toInt(),
                    duration = duration,
                    screenshot = screenshot,
                    xml = xml
                )
                forwardEvent(event, SWIPE_DELAY_MS)
            }
        } else {
            // 点击事件：先采集再记录再转发
            scope.launch {
                val (screenshot, xml) = if (TrajectoryCloudConfig.isEnabled() && (TrajectoryCloudConfig.shouldUploadScreenshot() || TrajectoryCloudConfig.isXmlEnabled())) {
                    TrajectoryCloudService.captureBeforeAction(context.applicationContext)
                } else Pair(null, null)
                recordEvent(
                    type = TrajectoryEventType.CLICK,
                    x = touchStartX.toInt(),
                    y = touchStartY.toInt(),
                    screenshot = screenshot,
                    xml = xml
                )
                forwardEvent(event, 0L)
            }
        }
    }
    
    /**
     * 处理 ACTION_CANCEL
     */
    private fun handleActionCancel(event: MotionEvent) {
        longPressJob?.cancel()
        forwardEvent(event, 0L) // 立即转发取消事件
    }
    
    /**
     * 记录触摸事件（原始MotionEvent）
     * 统一使用 System.currentTimeMillis() 作为时间戳
     */
    private fun recordTouchEvent(event: MotionEvent, type: TrajectoryEventType) {
        val trajectoryEvent = TrajectoryEvent(
            type = type,
            timestamp = System.currentTimeMillis(), // 统一使用 System.currentTimeMillis()
            x = event.rawX.toInt(),
            y = event.rawY.toInt(),
            action = event.action
        )
        onEventRecorded?.invoke(trajectoryEvent, null, null)
    }
    
    /**
     * 记录轨迹事件
     * @param screenshot 动作前已采集的截图（可选）
     * @param xml 动作前已采集的 XML（可选）
     */
    private fun recordEvent(
        type: TrajectoryEventType,
        x: Int? = null,
        y: Int? = null,
        startX: Int? = null,
        startY: Int? = null,
        endX: Int? = null,
        endY: Int? = null,
        duration: Long? = null,
        screenshot: android.graphics.Bitmap? = null,
        xml: String? = null
    ) {
        val trajectoryEvent = TrajectoryEvent(
            type = type,
            timestamp = System.currentTimeMillis(),
            x = x,
            y = y,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration
        )
        onEventRecorded?.invoke(trajectoryEvent, screenshot, xml)
    }
    
    /**
     * 转发事件到下层应用
     */
    private fun forwardEvent(event: MotionEvent, delayMs: Long) {
        // 立即在后台线程处理，减少延迟
        scope.launch(Dispatchers.Main) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            
            // 根据事件类型转发
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    // UP事件：判断是点击还是滑动结束
                    val currentX = event.rawX.toInt()
                    val currentY = event.rawY.toInt()
                    val distance = Math.sqrt(
                        Math.pow((currentX - touchStartX).toDouble(), 2.0) +
                        Math.pow((currentY - touchStartY).toDouble(), 2.0)
                    ).toFloat()
                    val moveThreshold = 10f * resources.displayMetrics.density
                    
                    if (isLongPress) {
                        // 长按：通过Manager转发
                        TrajectoryOverlayManager.forwardLongClick(
                            touchStartX.toInt(),
                            touchStartY.toInt()
                        )
                    } else if (distance > moveThreshold) {
                        // 滑动：通过Manager转发，传递真实的duration
                        val duration = currentSwipeDuration ?: 1200L // 如果没有duration，使用默认值1200ms
                        TrajectoryOverlayManager.forwardSwipe(
                            touchStartX.toInt(),
                            touchStartY.toInt(),
                            currentX,
                            currentY,
                            duration
                        )
                        // 清除duration，避免影响下次滑动
                        currentSwipeDuration = null
                    } else {
                        // 点击：通过Manager转发（Manager会调用AccessibilityService）
                        TrajectoryOverlayManager.forwardClick(
                            touchStartX.toInt(),
                            touchStartY.toInt()
                        )
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // CANCEL事件：不转发
                }
            }
        }
    }
    
    /**
     * 获取键盘高度设置（缓存以提高性能）
     */
    @Volatile
    private var cachedKeyboardHeight: Int = 1480
    private var lastKeyboardHeightUpdateTime: Long = 0
    private val KEYBOARD_HEIGHT_CACHE_DURATION_MS = 5000L // 缓存5秒
    
    /**
     * 覆盖层实际高度 = 用户配置 - 状态栏（补偿状态栏位移导致的 1480->1597）
     */
    private fun getOverlayHeight(): Int {
        return (getKeyboardHeight() - getStatusBarHeight()).coerceAtLeast(0)
    }
    
    private fun getStatusBarHeight(): Int {
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
    
    private fun getKeyboardHeight(): Int {
        val currentTime = System.currentTimeMillis()
        // 如果缓存过期，重新读取。始终使用用户配置的 keyboard_height，不用系统检测的 keypadHeight
        if (currentTime - lastKeyboardHeightUpdateTime > KEYBOARD_HEIGHT_CACHE_DURATION_MS) {
            try {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val userConfig = prefs.getInt("keyboard_height", 1480)
                if (cachedKeyboardHeight != userConfig) {
                    Log.d(TAG, "键盘高度缓存更新: $cachedKeyboardHeight -> $userConfig (用户配置)")
                }
                cachedKeyboardHeight = userConfig
                lastKeyboardHeightUpdateTime = currentTime
            } catch (e: Exception) {
                Log.e(TAG, "获取键盘高度设置失败: ${e.message}", e)
                cachedKeyboardHeight = 1480
            }
        }
        return cachedKeyboardHeight
    }
    
    /**
     * 刷新键盘高度缓存（当设置改变时调用）
     * 同时刷新覆盖层显示状态（如"显示覆盖层"开关变更）
     */
    fun refreshKeyboardHeight() {
        lastKeyboardHeightUpdateTime = 0 // 强制刷新
        getKeyboardHeight() // 立即更新缓存
        updateDebugOverlay(keyboardVisible, getOverlayHeight())
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        longPressJob?.cancel()
        stopKeyboardMonitoring()
        scope.cancel()
    }
}

