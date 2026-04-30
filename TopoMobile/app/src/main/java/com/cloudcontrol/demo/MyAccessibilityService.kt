package com.cloudcontrol.demo

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * get_wechat_link：基准整屏 1080×2376 上标定的相对比例，与 [Display.getRealMetrics] 整屏坐标系一致。
 */
private val GET_WECHAT_LINK_POINT_RATIOS: List<Pair<Float, Float>> = listOf(
    Pair(609f / 1080f, 1048f / 2376f),
    Pair(726f / 1080f, 2083f / 2376f),
    Pair(775f / 1080f, 1473f / 2376f),
    Pair(519f / 1080f, 1985f / 2376f),
    Pair(1005f / 1080f, 184f / 2376f),
    Pair(664f / 1080f, 1935f / 2376f),
)

/**
 * 无障碍服务
 * 用于执行云侧返回的点击指令
 * 
 * 使用说明：
 * 1. 用户需要在"设置 -> 无障碍"中手动开启此服务
 * 2. 开启后，可以通过performClick方法执行点击操作
 */
class MyAccessibilityService : AccessibilityService() {
    
    // 协程作用域（用于执行异步操作）
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val TAG = "AccessibilityService"
        private var instance: MyAccessibilityService? = null
        
        // 当前 Activity 信息
        @Volatile
        private var currentPackageName: String? = null
        @Volatile
        private var currentClassName: String? = null
        
        // 上次记录的窗口（用于 WINDOW_CHANGE 去重）
        @Volatile
        private var lastRecordedWindowPkg: String? = null
        @Volatile
        private var lastRecordedWindowCls: String? = null
        
        // 输入法切换相关状态
        @Volatile
        private var isWaitingForIMESwitch: Boolean = false
        @Volatile
        private var hasOpenedIMESettings: Boolean = false
        
        // 无障碍快捷弹窗检测防抖
        @Volatile
        private var lastShortcutDialogTriggerTime: Long = 0
        private const val SHORTCUT_DIALOG_TRIGGER_INTERVAL = 500L // 0.5秒内只触发一次

        /** 最近任务「清除全部」按钮：在基准分辨率 1080×2376 上标定，此处存相对整屏比例 */
        private const val RECENT_TASKS_CLEAR_BUTTON_X_RATIO = 587f / 1080f
        private const val RECENT_TASKS_CLEAR_BUTTON_Y_RATIO = 2176f / 2376f
        
        // 标记是否已经触发过输入弹窗（防止循环触发）
        @Volatile
        private var hasTriggeredInputDialog: Boolean = false
        
        // 标记是否是程序输入（用于过滤程序自动输入）
        @Volatile
        private var isProgrammaticInput: Boolean = false

        // 兼容 MainActivity onResume 的设置页返回逻辑：开启无障碍后补触发输入弹窗
        @JvmField
        @Volatile
        var pendingInputDialogAfterSettingsEnable: Boolean = false
        
        // 键盘状态
        @Volatile
        private var keyboardVisible: Boolean = false
        
        // 键盘检测防抖：记录上次检测到键盘变化的时间
        @Volatile
        private var lastKeyboardStateChangeTime: Long = 0
        private const val KEYBOARD_STATE_CHANGE_DEBOUNCE_MS = 300L // 300ms防抖
        
        // 键盘收起检测的多次确认参数
        private const val KEYBOARD_HIDE_CHECK_DELAY_MS = 500L // 延迟500ms开始检测
        private const val KEYBOARD_HIDE_CHECK_INTERVAL_MS = 100L // 每100ms检查一次
        private const val KEYBOARD_HIDE_CHECK_COUNT = 3 // 连续3次都没有焦点才判定收起
        
        // 文本变化事件去重缓存（用于避免重复记录）
        private val textChangeCache = mutableMapOf<String, Pair<Long, String>>() // viewId -> (timestamp, text)
        private val textChangeTimers = mutableMapOf<String, android.os.Handler>() // viewId -> Handler
        private const val TEXT_CHANGE_DEBOUNCE_MS = 300L // 300ms内的文本变化事件只记录最后一次
        
        // 输入会话管理（用于记录完整的输入操作）
        private data class InputSession(
            val viewId: String,
            val startTime: Long,
            var initialText: String,
            var lastText: String,
            var lastTextTime: Long,
            val packageName: String?,
            val className: String?,
            val contentDescription: String?,
            val x: Int,
            val y: Int
        )
        private val activeInputSessions = mutableMapOf<String, InputSession>() // viewId -> InputSession
        private val inputSessionTimeouts = mutableMapOf<String, Runnable>() // viewId -> Runnable（用于取消定时器）
        // 输入框聚焦时预采集的截图/XML（动作前状态），供 TEXT_CHANGE 记录时使用
        private val inputSessionPreCapture = mutableMapOf<String, kotlin.Pair<android.graphics.Bitmap?, String?>>()
        // 预采集的 Deferred（仅 XML 开启时使用），供 endInputSession 等待采集完成
        private val inputSessionCaptureDeferred = mutableMapOf<String, CompletableDeferred<Pair<android.graphics.Bitmap?, String?>>>()
        private const val PRE_CAPTURE_WAIT_TIMEOUT_MS = 300L // 等待预采集完成的最大时间（ms）
        private const val INPUT_SESSION_TIMEOUT_MS = 2000L // 输入会话超时时间（2秒无文本变化则结束会话）
        private val inputSessionHandler = android.os.Handler(android.os.Looper.getMainLooper()) // 统一的handler
        
        // 服务连接后检查无障碍快捷弹窗的时间窗口（10秒内）
        private const val SERVICE_CONNECTED_CHECK_WINDOW = 10000L
        
        // 无障碍快捷弹窗点击坐标（基于设计分辨率）
        private const val DESIGN_WIDTH = 1080
        private const val DESIGN_HEIGHT = 2376
        private const val DESIGN_X = 300
        private const val DESIGN_Y = 2150
        
        // 相对坐标比例（预先计算）
        private const val RELATIVE_X = DESIGN_X / DESIGN_WIDTH.toFloat()  // 300/1080 ≈ 0.2778
        private const val RELATIVE_Y = DESIGN_Y / DESIGN_HEIGHT.toFloat() // 2150/2376 ≈ 0.9049
        
        /**
         * 获取服务实例（用于从外部调用）
         */
        fun getInstance(): MyAccessibilityService? {
            return instance
        }
        
        /**
         * 检查服务是否已启用
         */
        fun isServiceEnabled(): Boolean {
            return instance != null
        }
        
        /**
         * 模型发起点击输入框前调用：设置 IME 零高度模式（键盘不弹出）+ 备用隐藏
         */
        fun prepareForModelInput() {
            SimpleInputMethodService.setModelInputMode(true)
            hideKeyboardForModelInput()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    instance?.softKeyboardController?.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
                    Log.d(TAG, "prepareForModelInput: 已设置 SoftKeyboardController")
                } catch (e: Exception) {
                    Log.w(TAG, "prepareForModelInput 失败: ${e.message}")
                }
            }
        }
        
        /**
         * 发送隐藏键盘广播。
         */
        fun hideKeyboardForModelInput() {
            try {
                val ctx = instance ?: return
                val hideIntent = Intent(SimpleInputMethodService.ACTION_HIDE_KEYBOARD).apply {
                    setPackage(ctx.packageName)
                }
                ctx.sendBroadcast(hideIntent)
                Log.d(TAG, "hideKeyboardForModelInput: 已发送 HIDE_KEYBOARD")
            } catch (e: Exception) {
                Log.w(TAG, "hideKeyboardForModelInput 失败: ${e.message}")
            }
        }
        
        /**
         * 点击后调用：立即隐藏键盘，并在 10/50/100/200ms 重试，300ms 后恢复 IME + SoftKeyboardController（用于 standalone click）
         */
        fun hideKeyboardAfterClickWithRetries() {
            hideKeyboardForModelInput()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({ hideKeyboardForModelInput() }, 10)
            handler.postDelayed({ hideKeyboardForModelInput() }, 50)
            handler.postDelayed({ hideKeyboardForModelInput() }, 100)
            handler.postDelayed({ hideKeyboardForModelInput() }, 200)
            // standalone click 无 inputText，需在此恢复 IME 模式 + SoftKeyboardController，否则用户手动输入时键盘不弹
            handler.postDelayed({
                SimpleInputMethodService.setModelInputMode(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        instance?.softKeyboardController?.setShowMode(AccessibilityService.SHOW_MODE_AUTO)
                        Log.d(TAG, "hideKeyboardAfterClickWithRetries: 已恢复 SoftKeyboardController 为 AUTO")
                    } catch (e: Exception) {
                        Log.w(TAG, "恢复 SoftKeyboardController 失败: ${e.message}")
                    }
                }
                Log.d(TAG, "hideKeyboardAfterClickWithRetries: 已恢复 IME 正常模式")
            }, 300)
            Log.d(TAG, "hideKeyboardAfterClickWithRetries: 已调度 0/10/50/100/200ms 重试 + 300ms 恢复模式")
        }
        
        /**
         * 获取当前 Activity 的包名
         */
        fun getCurrentPackageName(): String? {
            return currentPackageName
        }
        
        /**
         * 获取当前 Activity 的类名
         */
        fun getCurrentClassName(): String? {
            return currentClassName
        }
        
        /**
         * 获取当前 Activity 信息（包名和类名）
         */
        fun getCurrentActivityInfo(): Pair<String?, String?> {
            return Pair(currentPackageName, currentClassName)
        }
        
        /**
         * 重置上次记录的窗口（新会话开始时调用，用于 WINDOW_CHANGE 去重）
         */
        fun resetLastRecordedWindow() {
            lastRecordedWindowPkg = null
            lastRecordedWindowCls = null
        }
        
        /**
         * 检查是否正在等待输入法切换
         */
        fun isWaitingForIMESwitch(): Boolean {
            return isWaitingForIMESwitch
        }
        
        /**
         * 设置等待输入法切换状态
         */
        fun setWaitingForIMESwitch(waiting: Boolean) {
            isWaitingForIMESwitch = waiting
        }
        
        /**
         * 检查是否已打开过输入法设置页面
         */
        fun hasOpenedIMESettings(): Boolean {
            return hasOpenedIMESettings
        }
        
        /**
         * 设置已打开输入法设置页面标志
         */
        fun setHasOpenedIMESettings(opened: Boolean) {
            hasOpenedIMESettings = opened
        }
        
        /**
         * 重置输入法切换相关状态
         */
        fun resetIMESwitchState() {
            isWaitingForIMESwitch = false
            hasOpenedIMESettings = false
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        TrajectoryCloudConfig.initialize(applicationContext)
        val connectTime = System.currentTimeMillis()
        Log.d(TAG, "无障碍服务已连接")
        
        // 服务连接后，检查是否应该触发输入弹窗
        // 这用于处理用户从未启用状态通过无障碍快捷弹窗启用服务的情况
        serviceScope.launch {
            try {
                Log.d(TAG, "========== 服务连接后检查开始 ==========")
                Log.d(TAG, "服务连接时间: $connectTime")
                
                // 延迟0.8秒，等待系统UI稳定（优化：从1.5秒减少到0.8秒）
                delay(800)
                
                val currentTime = System.currentTimeMillis()
                val timeSinceConnect = currentTime - connectTime
                
                Log.d(TAG, "当前时间: $currentTime")
                Log.d(TAG, "服务连接后经过时间: ${timeSinceConnect}ms")
                
                // 检查是否在时间窗口内（10秒内连接的服务可能是从无障碍快捷弹窗启用的）
                if (timeSinceConnect >= SERVICE_CONNECTED_CHECK_WINDOW) {
                    Log.d(TAG, "✗ 服务连接时间超过窗口期（${timeSinceConnect}ms >= ${SERVICE_CONNECTED_CHECK_WINDOW}ms），不触发输入弹窗")
                    Log.d(TAG, "========================================")
                    return@launch
                }
                
                Log.d(TAG, "✓ 服务连接时间在窗口期内（${timeSinceConnect}ms < ${SERVICE_CONNECTED_CHECK_WINDOW}ms）")
                
                // 获取当前窗口信息
                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.d(TAG, "✗ 无法获取根节点，不触发输入弹窗")
                    Log.d(TAG, "========================================")
                    return@launch
                }
                
                val rootPackageName = rootNode.packageName?.toString()
                val rootClassName = rootNode.className?.toString()
                
                Log.d(TAG, "当前窗口信息:")
                Log.d(TAG, "  包名: $rootPackageName")
                Log.d(TAG, "  类名: $rootClassName")
                Log.d(TAG, "  自己的包名: $packageName")
                
                // 排除自己的应用（应用重启等情况）
                if (rootPackageName == packageName) {
                    Log.d(TAG, "✗ 当前窗口是自己的应用，不触发输入弹窗（可能是应用重启）")
                    rootNode.recycle()
                    Log.d(TAG, "========================================")
                    return@launch
                }
                
                // 排除设置页面（用户通过设置页面启用服务的情况）
                if (rootPackageName == "com.android.settings" || rootPackageName?.contains("settings", ignoreCase = true) == true) {
                    Log.d(TAG, "✗ 当前窗口是设置页面，不触发输入弹窗（用户通过设置页面启用）")
                    rootNode.recycle()
                    Log.d(TAG, "========================================")
                    return@launch
                }
                
                Log.d(TAG, "✓ 当前窗口不是设置页面，也不是自己的应用，继续检查")
                
                // 先尝试检测无障碍快捷弹窗（如果弹窗还在）
                val hasTopoClaw = findNodeWithText(rootNode, "TopoClaw") != null
                val hasClickHint = findNodeWithText(rootNode, "点击相应功能") != null
                val hasTalkBack = findNodeWithText(rootNode, "TalkBack") != null
                val hasModifyShortcut = findNodeWithText(rootNode, "修改快捷方式") != null
                
                Log.d(TAG, "无障碍快捷弹窗检测结果:")
                Log.d(TAG, "  TopoClaw: $hasTopoClaw")
                Log.d(TAG, "  点击相应功能: $hasClickHint")
                Log.d(TAG, "  TalkBack: $hasTalkBack")
                Log.d(TAG, "  修改快捷方式: $hasModifyShortcut")
                
                val hasServiceName = hasTopoClaw || hasTalkBack
                val hasDialogButtons = hasModifyShortcut
                val isShortcutDialog = hasClickHint && (hasServiceName || hasDialogButtons)
                
                rootNode.recycle()
                
                if (isShortcutDialog) {
                    Log.d(TAG, "✓✓✓ 检测到无障碍快捷弹窗，服务连接后触发输入弹窗")
                    delay(300) // 再等待一点时间确保弹窗完全显示（优化：从500ms减少到300ms）
                    triggerInputDialog()
                } else {
                    // 即使检测不到弹窗，但如果服务刚连接且不在设置页面，也触发输入弹窗
                    // 这是因为弹窗可能在服务连接前就关闭了
                    Log.d(TAG, "未检测到无障碍快捷弹窗，但服务刚连接（${timeSinceConnect}ms）且不在设置页面")
                    Log.d(TAG, "✓ 触发输入弹窗（降级方案：弹窗可能已关闭）")
                    delay(500) // 等待系统UI稳定（优化：从1000ms减少到500ms）
                    triggerInputDialog()
                }
                
                Log.d(TAG, "========================================")
            } catch (e: Exception) {
                Log.e(TAG, "服务连接后检查无障碍快捷弹窗失败: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        resetIMESwitchState()
        Log.d(TAG, "无障碍服务已销毁")
    }
    
    /**
     * 当无障碍事件发生时调用（如界面变化）
     * 监听窗口状态变化，保存当前 Activity 的包名和类名
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val packageName = event.packageName?.toString()
        val className = event.className?.toString()
        
        // 监听输入框焦点变化（用于输入会话管理和键盘检测）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = event.source
            if (source != null) {
                try {
                    // 检查是否是输入框（可编辑的）
                    val isEditable = source.isEditable
                    
                    // 如果正在记录轨迹，且不是程序输入，则开始输入会话
                    if (TrajectoryRecorder.isRecording() && !isProgrammaticInput) {
                        handleInputFocusGained(event)
                        
                        // 如果是输入框获得焦点，记录键盘弹出事件
                        if (isEditable && !keyboardVisible) {
                            recordKeyboardEvent(TrajectoryEventType.KEYBOARD_SHOW, "输入框获得焦点")
                        }
                    }
                } finally {
                    source.recycle()
                }
            }
        }
        
        // 监听文本变化事件（用于记录输入动作）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // 如果正在记录轨迹，且不是程序输入，则更新输入会话
            if (TrajectoryRecorder.isRecording() && !isProgrammaticInput) {
                handleTextChangeInSession(event)
            }
            return
        }


        
        // 监听输入框失去焦点（结束输入会话和键盘检测）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = event.source
            if (source != null) {
                try {
                    val focusedViewId = source.viewIdResourceName ?: "${packageName}_${className}_${source.hashCode()}"
                    val isEditable = source.isEditable
                    
                    // 结束所有其他输入会话（除了当前获得焦点的）
                    val sessionsToEnd = activeInputSessions.keys.filter { it != focusedViewId }
                    sessionsToEnd.forEach { viewId ->
                        endInputSession(viewId)
                    }
                    
                    // 如果焦点从输入框转移到非输入框，且键盘当前是显示状态，记录键盘收起事件
                    if (TrajectoryRecorder.isRecording() && !isProgrammaticInput) {
                        if (!isEditable && keyboardVisible) {
                            // 使用多次确认机制检测键盘收起，避免误判
                            checkKeyboardHideWithConfirmation("输入框失去焦点")
                        }
                    }
                } finally {
                    source.recycle()
                }
            }
        }
        
        // 监听窗口状态变化，检测输入法窗口
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 检查是否是输入法窗口
            val isInputMethodWindow = isInputMethodWindow(packageName, className)
            
            if (TrajectoryRecorder.isRecording() && !isProgrammaticInput) {
                if (isInputMethodWindow) {
                    // 输入法窗口出现
                    if (!keyboardVisible) {
                        recordKeyboardEvent(TrajectoryEventType.KEYBOARD_SHOW, "输入法窗口出现")
                    }
                } else {
                    // 非输入法窗口出现，可能是键盘收起
                    // 使用多次确认机制检测，避免误判
                    if (keyboardVisible) {
                        checkKeyboardHideWithConfirmation("输入法窗口消失")
                    }
                }
            }
        }
        
        // 只关心前台 Activity / 窗口切换
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 排除一些系统 UI 窗口（状态栏、通知栏等），避免干扰
            if (packageName == "com.android.systemui" && className == "android.view.View") {
                return
            }
            
            // 窗口切换时，结束所有输入会话并清理定时器
            val sessionsToEnd = activeInputSessions.keys.toList()
            sessionsToEnd.forEach { viewId ->
                endInputSession(viewId)
            }
            // 确保所有定时器都被清理
            inputSessionTimeouts.clear()
            
            // 检测无障碍快捷弹窗（传入事件信息用于更精确的检测）
            if (isAccessibilityShortcutDialog(packageName, className)) {
                triggerInputDialog()
            }
            
            // 使用 getWindows() 获取真实前台应用（排除覆盖层、输入法等）
            val (realPkg, realCls) = getRealForegroundApp(event)
            
            // 仅当获取到有效真实应用时更新和记录（避免覆盖层等污染）
            if (realPkg != null) {
                currentPackageName = realPkg
                currentClassName = realCls
                Log.d(TAG, "Activity change: package=$realPkg class=$realCls")
                
                // 采集 Activity 开启且正在记录时，记录 WINDOW_CHANGE 事件（去重：仅当发生变化时记录）
                if (TrajectoryRecorder.isRecording() && TrajectoryCloudConfig.isCollectActivityEnabled()) {
                    if (lastRecordedWindowPkg != realPkg || lastRecordedWindowCls != realCls) {
                        lastRecordedWindowPkg = realPkg
                        lastRecordedWindowCls = realCls
                        val windowEvent = TrajectoryEvent(
                            type = TrajectoryEventType.WINDOW_CHANGE,
                            timestamp = System.currentTimeMillis(),
                            packageName = realPkg,
                            className = realCls
                        )
                        TrajectoryRecorder.recordEvent(windowEvent)
                        Log.d(TAG, "记录窗口切换: $realPkg / $realCls")
                    }
                }
                
                // 窗口切换时，检查并恢复轨迹采集覆盖层
                checkAndRestoreTrajectoryOverlay()
            }
            
            // 窗口切换时，如果键盘是显示状态，且不是输入法窗口，则记录键盘收起
            if (TrajectoryRecorder.isRecording() && !isProgrammaticInput) {
                val isInputMethodWindow = isInputMethodWindow(packageName, className)
                if (!isInputMethodWindow && keyboardVisible) {
                    // 窗口切换时，键盘通常会收起，使用多次确认机制
                    checkKeyboardHideWithConfirmation("窗口切换")
                }
            }
        }
    }


    
    /**
     * 当服务被中断时调用
     */
    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }
    
    /**
     * 处理输入框获得焦点（开始输入会话）
     */
    private fun handleInputFocusGained(event: AccessibilityEvent) {
        try {
            val source = event.source
            if (source == null) {
                return
            }
            
            // 检查是否是输入框（可编辑的文本视图）
            if (!source.isEditable && !source.isFocusable) {
                source.recycle()
                return
            }
            
            // 获取View信息
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()
            val viewId = source.viewIdResourceName ?: "${packageName}_${className}_${source.hashCode()}"
            val contentDescription = source.contentDescription?.toString()
            
            // 获取输入框位置
            val bounds = android.graphics.Rect()
            source.getBoundsInScreen(bounds)
            val x = bounds.centerX()
            val y = bounds.centerY()
            
            // 获取当前文本
            val currentText = event.text?.firstOrNull()?.toString() ?: ""
            
            val currentTime = System.currentTimeMillis()
            
            // 如果已有会话，先结束它
            activeInputSessions[viewId]?.let { existingSession ->
                endInputSession(existingSession.viewId)
            }
            
            // 创建新的输入会话
            val session = InputSession(
                viewId = viewId,
                startTime = currentTime,
                initialText = currentText,
                lastText = currentText,
                lastTextTime = currentTime,
                packageName = packageName,
                className = className,
                contentDescription = contentDescription,
                x = x,
                y = y
            )
            
            activeInputSessions[viewId] = session
            
            // 输入框聚焦时立即采集截图/XML（动作前状态），供后续 TEXT_CHANGE 记录使用
            if (TrajectoryRecorder.isRecording() && TrajectoryCloudConfig.isEnabled() &&
                (TrajectoryCloudConfig.shouldUploadScreenshot() || TrajectoryCloudConfig.isXmlEnabled())) {
                val deferred = if (TrajectoryCloudConfig.isXmlEnabled()) {
                    CompletableDeferred<Pair<android.graphics.Bitmap?, String?>>().also {
                        inputSessionCaptureDeferred[viewId] = it
                    }
                } else null
                serviceScope.launch {
                    try {
                        val result = TrajectoryCloudService.captureBeforeAction(applicationContext)
                        inputSessionPreCapture[viewId] = result
                        deferred?.complete(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "输入会话预采集失败: ${e.message}", e)
                        deferred?.complete(kotlin.Pair(null, null))
                    }
                }
            }
            
            // 设置超时定时器：如果2秒内没有文本变化，自动结束会话
            scheduleInputSessionTimeout(viewId)
            
            Log.d(TAG, "开始输入会话: viewId=$viewId, 初始文本=\"$currentText\", 位置=($x, $y)")
            
            source.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "处理输入焦点获得失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理输入会话中的文本变化
     */
    private fun handleTextChangeInSession(event: AccessibilityEvent) {
        try {
            val source = event.source
            if (source == null) {
                return
            }
            
            // 获取View信息
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()
            val viewId = source.viewIdResourceName ?: "${packageName}_${className}_${source.hashCode()}"
            
            // 获取当前文本
            val currentText = event.text?.firstOrNull()?.toString() ?: ""
            val currentTime = System.currentTimeMillis()
            
            // 查找或创建输入会话
            val session = activeInputSessions[viewId]
            if (session == null) {
                // 如果没有会话，创建一个（可能是焦点事件被遗漏了）
                val bounds = android.graphics.Rect()
                source.getBoundsInScreen(bounds)
                val x = bounds.centerX()
                val y = bounds.centerY()
                val contentDescription = source.contentDescription?.toString()
                
                val newSession = InputSession(
                    viewId = viewId,
                    startTime = currentTime,
                    initialText = "",
                    lastText = currentText,
                    lastTextTime = currentTime,
                    packageName = packageName,
                    className = className,
                    contentDescription = contentDescription,
                    x = x,
                    y = y
                )
                activeInputSessions[viewId] = newSession
                Log.d(TAG, "创建输入会话（文本变化时）: viewId=$viewId, 文本=\"$currentText\"")
            } else {
                // 更新会话的文本和时间
                session.lastText = currentText
                session.lastTextTime = currentTime
            }
            
            // 重置超时定时器
            scheduleInputSessionTimeout(viewId)
            
            source.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "处理输入会话文本变化失败: ${e.message}", e)
        }
    }
    
    /**
     * 设置输入会话超时定时器
     */
    private fun scheduleInputSessionTimeout(viewId: String) {
        val session = activeInputSessions[viewId] ?: return
        
        // 取消之前的定时器
        inputSessionTimeouts[viewId]?.let { oldRunnable ->
            inputSessionHandler.removeCallbacks(oldRunnable)
        }
        
        // 创建新的定时器
        val timeoutRunnable = Runnable {
            activeInputSessions[viewId]?.let { timeoutSession ->
                val timeSinceLastText = System.currentTimeMillis() - timeoutSession.lastTextTime
                if (timeSinceLastText >= INPUT_SESSION_TIMEOUT_MS) {
                    endInputSession(viewId)
                }
            }
            inputSessionTimeouts.remove(viewId)
        }
        
        inputSessionTimeouts[viewId] = timeoutRunnable
        inputSessionHandler.postDelayed(timeoutRunnable, INPUT_SESSION_TIMEOUT_MS)
    }
    
    /**
     * 结束输入会话并记录最终文本变化
     */
    private fun endInputSession(viewId: String) {
        try {
            val session = activeInputSessions.remove(viewId) ?: return
            
            // 取消超时定时器
            inputSessionTimeouts[viewId]?.let { runnable ->
                inputSessionHandler.removeCallbacks(runnable)
            }
            inputSessionTimeouts.remove(viewId)
            
            // 计算实际输入的文本（最终文本 - 初始文本）
            val inputText = if (session.lastText.length >= session.initialText.length) {
                // 通常是追加输入
                session.lastText.substring(session.initialText.length)
            } else {
                // 可能是删除后重新输入，记录最终文本
                session.lastText
            }
            
            // 只有当有实际输入时才记录
            if (inputText.isNotEmpty() && inputText != session.initialText) {
                val trajectoryEvent = TrajectoryEvent(
                    type = TrajectoryEventType.TEXT_CHANGE,
                    timestamp = session.startTime,
                    x = session.x,
                    y = session.y,
                    text = inputText, // 只记录实际输入的文本
                    packageName = session.packageName,
                    className = session.className,
                    viewId = session.viewId,
                    contentDescription = session.contentDescription
                )
                var (screenshot, xml) = inputSessionPreCapture.remove(viewId) ?: kotlin.Pair(null, null)
                if (screenshot != null || xml != null) inputSessionCaptureDeferred.remove(viewId) // 已有预采集时清理 Deferred
                // 仅当 XML 开启时：若预采集未就绪，等待采集完成（最多 PRE_CAPTURE_WAIT_TIMEOUT_MS）
                if (screenshot == null && xml == null && TrajectoryCloudConfig.isXmlEnabled()) {
                    val deferred = inputSessionCaptureDeferred.remove(viewId)
                    if (deferred != null) {
                        serviceScope.launch {
                            val result = withTimeoutOrNull(PRE_CAPTURE_WAIT_TIMEOUT_MS) { deferred.await() }
                            inputSessionPreCapture.remove(viewId) // 清理可能由采集协程写入的条目
                            TrajectoryRecorder.recordEvent(trajectoryEvent, result?.first, result?.second)
                            Log.d(TAG, "结束输入会话并记录（等待预采集后）: viewId=$viewId, 输入文本=\"$inputText\", 初始=\"${session.initialText}\", 最终=\"${session.lastText}\"")
                        }
                        return
                    }
                }
                TrajectoryRecorder.recordEvent(trajectoryEvent, screenshot, xml)
                Log.d(TAG, "结束输入会话并记录: viewId=$viewId, 输入文本=\"$inputText\", 初始=\"${session.initialText}\", 最终=\"${session.lastText}\"")
            } else {
                inputSessionPreCapture.remove(viewId)
                inputSessionCaptureDeferred.remove(viewId) // 无实际输入时清理，避免内存泄漏
                Log.d(TAG, "结束输入会话（无实际输入）: viewId=$viewId, 初始=\"${session.initialText}\", 最终=\"${session.lastText}\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "结束输入会话失败: ${e.message}", e)
        }
    }
    
    /**
     * 记录文本变化事件（带去重机制）
     * @deprecated 已改用输入会话机制，此方法保留用于兼容
     */
    @Deprecated("已改用输入会话机制")
    private fun recordTextChangeEvent(event: AccessibilityEvent) {
        try {
            val source = event.source
            if (source == null) {
                Log.w(TAG, "文本变化事件源为空，跳过记录")
                return
            }
            
            // 获取文本内容
            val text = event.text?.firstOrNull()?.toString() ?: ""
            
            // 获取View信息
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()
            val viewId = source.viewIdResourceName ?: "${packageName}_${className}_${source.hashCode()}"
            val contentDescription = source.contentDescription?.toString()
            
            // 获取输入框位置
            val bounds = android.graphics.Rect()
            source.getBoundsInScreen(bounds)
            val x = bounds.centerX()
            val y = bounds.centerY()
            
            val currentTime = System.currentTimeMillis()
            
            // 取消之前的定时器（如果有）
            textChangeTimers[viewId]?.removeCallbacksAndMessages(null)
            
            // 去重机制：对于同一个输入框，在短时间内（300ms）的多次文本变化，只记录最后一次
            val cached = textChangeCache[viewId]
            if (cached != null) {
                val (lastTimestamp, lastText) = cached
                val timeDiff = currentTime - lastTimestamp
                
                if (timeDiff < TEXT_CHANGE_DEBOUNCE_MS) {
                    // 在去重时间窗口内，更新缓存但不记录
                    textChangeCache[viewId] = Pair(currentTime, text)
                    Log.d(TAG, "文本变化事件去重: 文本=\"$text\" (${timeDiff}ms前记录过\"$lastText\")")
                    
                    // 设置定时器，确保最后一个事件也被记录
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    textChangeTimers[viewId] = handler
                    handler.postDelayed({
                        // 延迟后记录最后一次文本变化
                        val finalCached = textChangeCache[viewId]
                        if (finalCached != null && finalCached.second == text) {
                            val trajectoryEvent = TrajectoryEvent(
                                type = TrajectoryEventType.TEXT_CHANGE,
                                timestamp = finalCached.first,
                                x = x,
                                y = y,
                                text = finalCached.second,
                                packageName = packageName,
                                className = className,
                                viewId = viewId,
                                contentDescription = contentDescription
                            )
                            TrajectoryRecorder.recordEvent(trajectoryEvent)
                            Log.d(TAG, "记录文本变化事件（延迟记录）: 文本=\"${finalCached.second}\", 位置=($x, $y)")
                            textChangeCache.remove(viewId)
                            textChangeTimers.remove(viewId)
                        }
                    }, TEXT_CHANGE_DEBOUNCE_MS)
                    
                    source.recycle()
                    return
                } else {
                    // 超过去重时间窗口，记录上一次的文本变化
                    if (lastText.isNotEmpty()) {
                        val trajectoryEvent = TrajectoryEvent(
                            type = TrajectoryEventType.TEXT_CHANGE,
                            timestamp = lastTimestamp,
                            x = x,
                            y = y,
                            text = lastText,
                            packageName = packageName,
                            className = className,
                            viewId = viewId,
                            contentDescription = contentDescription
                        )
                        TrajectoryRecorder.recordEvent(trajectoryEvent)
                        Log.d(TAG, "记录文本变化事件（去重后）: 文本=\"$lastText\", 位置=($x, $y)")
                    }
                }
            }
            
            // 更新缓存
            textChangeCache[viewId] = Pair(currentTime, text)
            
            // 如果文本为空（删除操作），立即记录
            if (text.isEmpty()) {
                val trajectoryEvent = TrajectoryEvent(
                    type = TrajectoryEventType.TEXT_CHANGE,
                    timestamp = currentTime,
                    x = x,
                    y = y,
                    text = text,
                    packageName = packageName,
                    className = className,
                    viewId = viewId,
                    contentDescription = contentDescription
                )
                TrajectoryRecorder.recordEvent(trajectoryEvent)
                Log.d(TAG, "记录文本变化事件（删除）: 文本为空, 位置=($x, $y)")
                // 清除缓存和定时器
                textChangeCache.remove(viewId)
                textChangeTimers[viewId]?.removeCallbacksAndMessages(null)
                textChangeTimers.remove(viewId)
            } else {
                // 设置定时器，确保最后一个事件也被记录
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                textChangeTimers[viewId] = handler
                handler.postDelayed({
                    // 延迟后记录最后一次文本变化
                    val finalCached = textChangeCache[viewId]
                    if (finalCached != null && finalCached.second == text) {
                        val trajectoryEvent = TrajectoryEvent(
                            type = TrajectoryEventType.TEXT_CHANGE,
                            timestamp = finalCached.first,
                            x = x,
                            y = y,
                            text = finalCached.second,
                            packageName = packageName,
                            className = className,
                            viewId = viewId,
                            contentDescription = contentDescription
                        )
                        TrajectoryRecorder.recordEvent(trajectoryEvent)
                        Log.d(TAG, "记录文本变化事件（延迟记录）: 文本=\"${finalCached.second}\", 位置=($x, $y)")
                        textChangeCache.remove(viewId)
                        textChangeTimers.remove(viewId)
                    }
                }, TEXT_CHANGE_DEBOUNCE_MS)
            }
            
            source.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "记录文本变化事件失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查并恢复轨迹采集覆盖层
     * 在窗口切换时调用，确保覆盖层正常工作
     */
    private fun checkAndRestoreTrajectoryOverlay() {
        try {
            // 检查轨迹采集是否启用（同时检查设置和实际记录状态）
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val isTrajectoryRecordingEnabled = prefs.getBoolean("trajectory_recording_enabled", false)
            val isActuallyRecording = TrajectoryRecorder.isRecording()
            
            // 只有当设置启用且实际正在记录时，才恢复覆盖层
            if (isTrajectoryRecordingEnabled && isActuallyRecording) {
                // 检查覆盖层是否显示
                if (!TrajectoryOverlayManager.isShowing()) {
                    Log.d(TAG, "检测到窗口切换，覆盖层未显示，尝试恢复...")
                    serviceScope.launch {
                        // 延迟一下，等待窗口切换完成
                        delay(300)
                        val ctx = applicationContext
                        if (ctx != null && TrajectoryRecorder.isRecording()) {
                            // 再次检查是否还在记录（防止在延迟期间关闭了轨迹采集）
                            TrajectoryOverlayManager.show(ctx)
                            // 同时恢复导航悬浮球
                            TrajectoryNavigationOverlayManager.show(ctx)
                        }
                    }
                } else {
                    // 覆盖层存在，但可能失效，重置状态
                    Log.d(TAG, "窗口切换，重置覆盖层状态")
                    TrajectoryOverlayManager.resetState()
                    // 确保导航悬浮球也显示
                    if (!TrajectoryNavigationOverlayManager.isShowing()) {
                        serviceScope.launch {
                            delay(300)
                            val ctx = applicationContext
                            if (ctx != null && TrajectoryRecorder.isRecording()) {
                                TrajectoryNavigationOverlayManager.show(ctx)
                            }
                        }
                    }
                }
            } else if (!isActuallyRecording && TrajectoryOverlayManager.isShowing()) {
                // 如果设置启用但实际没有在记录，且覆盖层还在显示，则隐藏覆盖层
                Log.d(TAG, "检测到轨迹采集已停止但覆盖层仍在显示，隐藏覆盖层")
                TrajectoryOverlayManager.hide()
                // 同时隐藏导航悬浮球
                TrajectoryNavigationOverlayManager.hide()
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查并恢复轨迹采集覆盖层失败: ${e.message}", e)
        }
    }
    
    /**
     * 通过 getWindows() 获取真实前台应用（排除覆盖层、系统 UI、输入法）
     * @param event 当前窗口事件，用于在包名匹配时补充 Activity 类名
     * @return Pair(包名, 类名)，若无法获取则返回 Pair(null, null)
     */
    private fun getRealForegroundApp(event: AccessibilityEvent): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return Pair(event.packageName?.toString(), event.className?.toString())
        }
        return try {
            val windows = windows ?: return Pair(null, null)
            val selfPkg = packageName
            val candidates = windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { win ->
                    val root = win.root ?: return@mapNotNull null
                    try {
                        val pkg = root.packageName?.toString() ?: return@mapNotNull null
                        val cls = root.className?.toString()
                        Triple(win, pkg, cls)
                    } finally {
                        root.recycle()
                    }
                }
                .filter { (_, pkg, _) ->
                    pkg != selfPkg &&
                    pkg != "com.android.systemui" &&
                    !isInputMethodWindow(pkg, null)
                }
                .sortedByDescending { (win, _, _) -> win.layer }
            val first = candidates.firstOrNull() ?: return Pair(null, null)
            val (_, pkg, nodeClass) = first
            val eventPkg = event.packageName?.toString()
            val eventCls = event.className?.toString()
            val cls = if (eventPkg == pkg && eventCls != null) eventCls else nodeClass
            Pair(pkg, cls)
        } catch (e: Exception) {
            Log.e(TAG, "getRealForegroundApp 失败: ${e.message}", e)
            Pair(null, null)
        }
    }
    
    /**
     * 判断是否是输入法窗口
     */
    private fun isInputMethodWindow(packageName: String?, className: String?): Boolean {
        if (packageName == null || className == null) {
            return false
        }
        
        // 检查是否是输入法相关的类
        // InputMethodService 或其子类
        return className.contains("InputMethodService", ignoreCase = true) ||
               className.contains("InputMethod", ignoreCase = true) ||
               // 常见的输入法包名
               packageName.contains("inputmethod", ignoreCase = true) ||
               packageName.contains("keyboard", ignoreCase = true) ||
               packageName.contains("ime", ignoreCase = true) ||
               // 自定义输入法
               packageName == this.packageName && className.contains("SimpleInputMethodService")
    }
    
    /**
     * 多次确认检测键盘收起（避免误判）
     * 延迟后连续多次检查，只有连续多次都没有输入框焦点才判定为收起
     */
    private fun checkKeyboardHideWithConfirmation(reason: String) {
        if (!keyboardVisible) {
            return // 键盘已经是收起状态，不需要检测
        }
        
        serviceScope.launch {
            // 延迟开始检测，等待焦点切换完成
            delay(KEYBOARD_HIDE_CHECK_DELAY_MS)
            
            // 如果键盘状态已经改变（可能被其他路径检测到了），不再检测
            if (!keyboardVisible) {
                return@launch
            }
            
            var consecutiveNoFocusCount = 0
            
            // 连续多次检查
            for (i in 0 until KEYBOARD_HIDE_CHECK_COUNT) {
                // 检查是否还有输入框有焦点
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    try {
                        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        val hasEditableFocus = focusedNode != null && focusedNode.isEditable
                        
                        if (hasEditableFocus) {
                            // 发现有输入框有焦点，重置计数
                            consecutiveNoFocusCount = 0
                            Log.d(TAG, "键盘收起检测($reason): 第${i+1}次检查发现输入框有焦点，取消收起判定")
                            focusedNode?.recycle()
                            return@launch // 发现有焦点，直接退出
                        } else {
                            consecutiveNoFocusCount++
                            Log.d(TAG, "键盘收起检测($reason): 第${i+1}次检查无输入框焦点 (连续${consecutiveNoFocusCount}次)")
                        }
                        
                        focusedNode?.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "键盘收起检测失败: ${e.message}", e)
                        return@launch
                    } finally {
                        rootNode.recycle()
                    }
                } else {
                    // 无法获取根节点，可能是窗口切换了，重置计数
                    consecutiveNoFocusCount = 0
                    return@launch
                }
                
                // 如果不是最后一次检查，等待间隔时间
                if (i < KEYBOARD_HIDE_CHECK_COUNT - 1) {
                    delay(KEYBOARD_HIDE_CHECK_INTERVAL_MS)
                    
                    // 再次检查键盘状态（可能已经被其他路径改变了）
                    if (!keyboardVisible) {
                        return@launch
                    }
                }
            }
            
            // 连续多次都没有焦点，确认键盘收起
            if (consecutiveNoFocusCount >= KEYBOARD_HIDE_CHECK_COUNT) {
                Log.d(TAG, "键盘收起检测($reason): 连续${consecutiveNoFocusCount}次检查无输入框焦点，确认键盘收起")
                recordKeyboardEvent(TrajectoryEventType.KEYBOARD_HIDE, reason)
            }
        }
    }
    
    /**
     * 记录键盘事件
     */
    private fun recordKeyboardEvent(eventType: TrajectoryEventType, reason: String) {
        // 防抖：如果距离上次状态变化时间太短，跳过
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastKeyboardStateChangeTime < KEYBOARD_STATE_CHANGE_DEBOUNCE_MS) {
            Log.d(TAG, "键盘事件防抖: 距离上次变化仅${currentTime - lastKeyboardStateChangeTime}ms，跳过")
            return
        }
        
        // 检查状态是否真的变化了
        val shouldShow = eventType == TrajectoryEventType.KEYBOARD_SHOW
        if (shouldShow == keyboardVisible) {
            // 状态没有变化，不需要记录
            Log.d(TAG, "键盘状态未变化: 当前状态=${if (keyboardVisible) "显示" else "隐藏"}, 事件类型=${if (shouldShow) "显示" else "隐藏"}")
            return
        }
        
        keyboardVisible = shouldShow
        lastKeyboardStateChangeTime = currentTime
        
        // 同步键盘状态到覆盖层
        TrajectoryOverlayManager.setKeyboardVisible(shouldShow)
        
        val event = TrajectoryEvent(
            type = eventType,
            timestamp = currentTime,
            packageName = currentPackageName,
            text = reason // 使用text字段存储原因
        )
        
        TrajectoryRecorder.recordEvent(event)
        
        Log.d(TAG, if (shouldShow) "✓ 键盘弹出: $reason" else "✓ 键盘收起: $reason")
    }
    
    /**
     * 执行点击操作（同步等待完成）
     * @param x 点击的x坐标
     * @param y 点击的y坐标
     * @param fromModel 是否为模型发起的点击。用于与用户手动点击区分（如轨迹记录、键盘策略等）
     * @return 是否成功执行
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun performClick(x: Int, y: Int, fromModel: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "[点击] 开始执行点击操作: ($x, $y), fromModel=$fromModel")
            
            // 创建点击手势路径
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            // 创建手势描述
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,  // 开始时间
                        100 // 持续时间（毫秒）
                    )
                )
                .build()
            
            Log.d(TAG, "[点击] 手势已创建，准备发送...")
            
            // 使用协程等待手势完成
            suspendCancellableCoroutine { continuation ->
                val result = dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "[点击] ✓✓✓ 点击手势执行成功: ($x, $y)")
                            continuation.resume(true)
                        }
                        
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "[点击] ❌ 点击手势被取消: ($x, $y)")
                            continuation.resume(false)
                        }
                    },
                    null
                )
                
                if (!result) {
                    Log.e(TAG, "[点击] ❌ 点击手势发送失败: ($x, $y)")
                    continuation.resume(false)
                } else {
                    Log.d(TAG, "[点击] 点击手势已发送，等待回调...")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行点击时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 返回主页
     */
    fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "返回主页")
    }
    
    /**
     * 返回上一页
     */
    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "返回上一页")
    }
    
    /**
     * 打开最近任务列表
     */
    fun openRecentTasks() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        Log.d(TAG, "打开最近任务列表")
    }

    /**
     * 当前默认显示器的整屏像素尺寸（与 [android.view.Display.getRealMetrics] 一致，含系统栏区域）。
     */
    private fun getFullScreenPixelSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            display.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * get_wechat_link：将基准 1080×2376 上的相对标定换算为当前整屏像素坐标（与手势 [dispatchGesture] 一致）。
     */
    fun getWechatLinkClickPixels(): List<Pair<Int, Int>> {
        val (w, h) = getFullScreenPixelSize()
        return GET_WECHAT_LINK_POINT_RATIOS.map { (rx, ry) ->
            val x = if (w > 0) {
                (w * rx).roundToInt().coerceIn(0, w - 1)
            } else {
                0
            }
            val y = if (h > 0) {
                (h * ry).roundToInt().coerceIn(0, h - 1)
            } else {
                0
            }
            x to y
        }
    }

    /**
     * 由整屏宽高与标定比例计算「清除全部」按钮点击像素坐标。
     */
    private fun recentTasksClearButtonPixelCoordinates(): Pair<Int, Int> {
        val (w, h) = getFullScreenPixelSize()
        val x = if (w > 0) {
            (w * RECENT_TASKS_CLEAR_BUTTON_X_RATIO).roundToInt().coerceIn(0, w - 1)
        } else {
            0
        }
        val y = if (h > 0) {
            (h * RECENT_TASKS_CLEAR_BUTTON_Y_RATIO).roundToInt().coerceIn(0, h - 1)
        } else {
            0
        }
        return x to y
    }
    
    /**
     * 打开最近任务列表并点击清除按钮（清除后台应用）
     * 点击位置按整屏 [getRealMetrics] 尺寸与基准 1080×2376 上的比例换算，与截图坐标系一致。
     * @param returnToHome 是否在清除后返回主页（默认true）
     * @return 是否成功点击清除按钮
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun clearAppFromRecentTasks(returnToHome: Boolean = true): Boolean {
        return try {
            val (fullW, fullH) = getFullScreenPixelSize()
            val (clearButtonX, clearButtonY) = recentTasksClearButtonPixelCoordinates()
            Log.d(TAG, "整屏 ${fullW}x${fullH}，清除按钮像素: ($clearButtonX, $clearButtonY)")
            Log.d(TAG, "打开最近任务列表并点击清除按钮: ($clearButtonX, $clearButtonY)")
            
            // 步骤1：打开最近任务列表
            openRecentTasks()
            delay(2000)  // 等待最近任务列表加载完成
            
            // 步骤2：点击清除按钮
            Log.d(TAG, "点击清除按钮: ($clearButtonX, $clearButtonY)")
            val success = performClick(clearButtonX, clearButtonY, fromModel = false)
            
            if (success) {
                Log.d(TAG, "成功点击清除按钮")
                delay(500)  // 等待清除动画完成
            } else {
                Log.w(TAG, "点击清除按钮失败")
            }
            
            // 步骤3：如果需要，关闭最近任务列表（返回主页）
            if (returnToHome) {
                delay(300)
                goHome()
                Log.d(TAG, "已返回主页")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "清理应用时出错: ${e.message}", e)
            // 如果出错，确保返回主页
            if (returnToHome) {
                try {
                    goHome()
                } catch (e2: Exception) {
                    Log.e(TAG, "返回主页失败: ${e2.message}", e2)
                }
            }
            false
        }
    }
    
    /**
     * 在最近任务列表中递归查找目标应用
     * @param node 当前节点
     * @param packageName 目标应用包名
     * @return 找到的应用节点，如果未找到返回null
     */
    private fun findAppInRecentTasks(node: AccessibilityNodeInfo?, packageName: String): AccessibilityNodeInfo? {
        if (node == null) return null
        
        try {
            // 检查当前节点的包名
            val nodePackageName = node.packageName?.toString()
            if (nodePackageName == packageName) {
                // 找到目标应用，返回其父节点（应用卡片）
                val parent = node.parent
                if (parent != null) {
                    Log.d(TAG, "找到目标应用: $packageName")
                    return parent
                }
                return node
            }
            
            // 检查节点文本内容（可能包含应用名）
            val text = node.text?.toString() ?: ""
            val contentDescription = node.contentDescription?.toString() ?: ""
            
            // 递归查找子节点
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val found = findAppInRecentTasks(child, packageName)
                if (found != null) {
                    return found
                }
                child?.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "查找应用节点时出错: ${e.message}", e)
        }
        
        return null
    }
    
    /**
     * 启动应用
     * @param packageName 应用的包名
     * @return 是否成功启动
     */
    fun launchApp(packageName: String): Boolean {
        return try {
            Log.d(TAG, "启动应用: $packageName")
            
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "应用启动成功: $packageName")
                true
            } else {
                Log.w(TAG, "应用不存在或无法启动: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败: $packageName, 错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 执行滑动操作（同步等待完成）
     * @param x1 起始x坐标
     * @param y1 起始y坐标
     * @param x2 结束x坐标
     * @param y2 结束y坐标
     * @param duration 滑动持续时间（毫秒），默认300ms
     * @return 是否成功执行
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 1200): Boolean {
        return try {
            // 获取屏幕尺寸
            val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            val screenWidth = windowManager?.let {
                val displayMetrics = android.util.DisplayMetrics()
                it.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.widthPixels
            } ?: 1080  // 默认值，如果获取失败则使用固定值
            val screenHeight = windowManager?.let {
                val displayMetrics = android.util.DisplayMetrics()
                it.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.heightPixels
            } ?: 1920  // 默认值，如果获取失败则使用固定值
            
            // 计算滑动方向和距离
            val deltaX = x2 - x1
            val deltaY = y2 - y1
            val absDeltaX = kotlin.math.abs(deltaX)
            val absDeltaY = kotlin.math.abs(deltaY)
            
            // 直接使用模型输出的原始滑动距离，确保坐标在屏幕范围内
            val finalX2 = x2.coerceIn(0, screenWidth - 1)
            val finalY2 = y2.coerceIn(0, screenHeight - 1)
            
            // 详细日志：记录实际滑动的开始和结束坐标
            Log.d(TAG, "========== 滑动操作详情 ==========")
            Log.d(TAG, "原始输入坐标: 起点($x1, $y1) -> 终点($x2, $y2)")
            Log.d(TAG, "滑动偏移: deltaX=$deltaX, deltaY=$deltaY, absDeltaX=$absDeltaX, absDeltaY=$absDeltaY")
            Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}")
            if (absDeltaX > absDeltaY) {
                Log.d(TAG, "滑动类型: 水平滑动")
            } else {
                Log.d(TAG, "滑动类型: 垂直滑动")
            }
            if (x2 != finalX2) {
                Log.d(TAG, "X坐标被限制: $x2 -> $finalX2 (限制范围: 0 ~ ${screenWidth - 1})")
            }
            if (y2 != finalY2) {
                Log.d(TAG, "Y坐标被限制: $y2 -> $finalY2 (限制范围: 0 ~ ${screenHeight - 1})")
            }
            Log.d(TAG, "最终执行坐标: 起点($x1, $y1) -> 终点($finalX2, $finalY2)")
            Log.d(TAG, "持续时间: ${duration}ms")
            Log.d(TAG, "==================================")
            
            // 创建滑动路径
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(finalX2.toFloat(), finalY2.toFloat())
            }
            
            // 创建手势描述
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,  // 开始时间
                        duration  // 持续时间
                    )
                )
                .build()
            
            // 使用协程等待手势完成
            suspendCancellableCoroutine { continuation ->
                val result = dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "滑动执行成功: ($x1, $y1) -> ($finalX2, $finalY2)")
                            continuation.resume(true)
                        }
                        
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "滑动执行被取消: ($x1, $y1) -> ($finalX2, $finalY2)")
                            continuation.resume(false)
                        }
                    },
                    null
                )
                
                if (!result) {
                    Log.e(TAG, "滑动指令发送失败: ($x1, $y1) -> ($finalX2, $finalY2)")
                    continuation.resume(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行滑动时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 执行长按操作（同步等待完成）
     * @param x 长按的x坐标
     * @param y 长按的y坐标
     * @param duration 长按持续时间（毫秒），默认500ms
     * @return 是否成功执行
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun performLongClick(x: Int, y: Int, duration: Long = 500): Boolean {
        return try {
            Log.d(TAG, "执行长按: ($x, $y), 持续时间: ${duration}ms")
            
            // 创建长按路径（在同一位置停留）
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            
            // 创建手势描述
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,  // 开始时间
                        duration  // 持续时间（长按需要较长时间）
                    )
                )
                .build()
            
            // 使用协程等待手势完成
            suspendCancellableCoroutine { continuation ->
                val result = dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "长按执行成功: ($x, $y)")
                            continuation.resume(true)
                        }
                        
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "长按执行被取消: ($x, $y)")
                            continuation.resume(false)
                        }
                    },
                    null
                )
                
                if (!result) {
                    Log.e(TAG, "长按指令发送失败: ($x, $y)")
                    continuation.resume(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行长按时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 执行拖拽操作（慢速版swipe，同步等待完成）
     * @param x1 起始位置的x坐标
     * @param y1 起始位置的y坐标
     * @param x2 目标位置的x坐标
     * @param y2 目标位置的y坐标
     * @return 是否成功执行
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun performDrag(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        return try {
            // 获取屏幕尺寸
            val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            val screenWidth = windowManager?.let {
                val displayMetrics = android.util.DisplayMetrics()
                it.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.widthPixels
            } ?: 1080  // 默认值，如果获取失败则使用固定值
            val screenHeight = windowManager?.let {
                val displayMetrics = android.util.DisplayMetrics()
                it.defaultDisplay.getMetrics(displayMetrics)
                displayMetrics.heightPixels
            } ?: 1920  // 默认值，如果获取失败则使用固定值
            
            // 目标坐标直接使用传入的x2, y2
            var endX = x2
            var endY = y2
            
            // 安全检查：避免拖拽到系统敏感区域
            val topSafeZone = 100  // 顶部安全区域（像素）
            val bottomSafeZone = 100 // 底部安全区域
            val edgeSafeZone = 50  // 边缘安全区域（像素）
            
            // 1. 避免从屏幕顶部向下拖拽（可能触发通知栏）
            if (y1 < topSafeZone && endY > y1) { // 如果起始位置在顶部安全区且是向下拖拽
                Log.w(TAG, "警告：起始位置在顶部安全区域内($y1 < $topSafeZone)，且向下拖拽，可能触发通知栏")
                // 限制endY，使其不超过屏幕高度减去底部安全区，且不小于起始y1
                endY = endY.coerceAtMost(screenHeight - bottomSafeZone).coerceAtLeast(y1 + 1)
            }
            
            // 2. 避免拖拽到屏幕边缘（可能触发系统手势）
            endX = endX.coerceIn(edgeSafeZone, screenWidth - edgeSafeZone - 1)
            endY = endY.coerceIn(edgeSafeZone, screenHeight - edgeSafeZone - 1)
            
            // 计算实际拖拽距离
            val deltaX = endX - x1
            val deltaY = endY - y1
            val actualDragDistance = kotlin.math.sqrt(
                (deltaX * deltaX + deltaY * deltaY).toDouble()
            ).toFloat()
            
            Log.d(TAG, "========== 拖拽操作详情 ==========")
            Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}")
            Log.d(TAG, "起始坐标: ($x1, $y1)")
            Log.d(TAG, "目标坐标: ($endX, $endY)")
            Log.d(TAG, "拖拽距离: ${actualDragDistance}像素")
            
            // 根据滑动长度动态计算拖动时间：速度 = 50像素/秒
            // 时间(ms) = 距离(像素) / 50 * 1000
            val dragDuration = ((actualDragDistance / 50.0) * 1000).toLong().coerceAtLeast(100L)  // 最少100ms
            
            Log.d(TAG, "拖动时间: ${dragDuration}ms (速度: 50像素/秒)")
            Log.d(TAG, "==================================")
            
            // 创建一个简单的路径：从起始位置到目标位置（慢速版swipe）
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }
            
            // 创建手势描述：使用单个连续的Stroke（就是一个swipe）
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,  // 开始时间：0ms
                        dragDuration  // 持续时间：根据距离计算，速度50像素/秒
                    )
                )
                .build()
            
            // 使用协程等待手势完成
            suspendCancellableCoroutine { continuation ->
                val result = dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "拖拽执行成功: ($x1, $y1) -> ($endX, $endY)")
                            continuation.resume(true)
                        }
                        
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "拖拽执行被取消: ($x1, $y1) -> ($endX, $endY)")
                            continuation.resume(false)
                        }
                    },
                    null
                )
                
                if (!result) {
                    Log.e(TAG, "拖拽指令发送失败: ($x1, $y1) -> ($endX, $endY)")
                    continuation.resume(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行拖拽时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 执行双击操作（同步等待完成）
     * @param x 双击的x坐标
     * @param y 双击的y坐标
     * @param clickDuration 每次点击的持续时间（毫秒），默认100ms
     * @param doubleClickInterval 两次点击之间的间隔（毫秒），默认150ms
     * @return 是否成功执行
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun performDoubleClick(x: Int, y: Int, clickDuration: Long = 100, doubleClickInterval: Long = 150): Boolean {
        return try {
            Log.d(TAG, "执行双击: ($x, $y)")
            
            // 第一次点击
            val path1 = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture1 = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path1,
                        0,  // 开始时间
                        clickDuration  // 持续时间
                    )
                )
                .build()
            
            // 等待第一次点击完成
            val firstClickSuccess = suspendCancellableCoroutine { continuation ->
                val result = dispatchGesture(
                    gesture1,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            continuation.resume(true)
                        }
                        
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            continuation.resume(false)
                        }
                    },
                    null
                )
                
                if (!result) {
                    continuation.resume(false)
                }
            }
            
            if (!firstClickSuccess) {
                Log.w(TAG, "第一次点击失败: ($x, $y)")
                return false
            }
            
            // 等待双击间隔
            delay(doubleClickInterval)
            
            // 第二次点击
            val path2 = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture2 = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path2,
                        0,  // 开始时间
                        clickDuration  // 持续时间
                    )
                )
                .build()
            
            // 等待第二次点击完成
            val secondClickSuccess = suspendCancellableCoroutine { continuation ->
                val result = dispatchGesture(
                    gesture2,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            Log.d(TAG, "双击执行成功: ($x, $y)")
                            continuation.resume(true)
                        }
                        
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "第二次点击被取消: ($x, $y)")
                            continuation.resume(false)
                        }
                    },
                    null
                )
                
                if (!result) {
                    Log.e(TAG, "第二次点击指令发送失败: ($x, $y)")
                    continuation.resume(false)
                }
            }
            
            secondClickSuccess
        } catch (e: Exception) {
            Log.e(TAG, "执行双击时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 输入文本
     * @param text 要输入的文本
     * @param fromModel 是否为模型发起的输入。若为true，自定义键盘将隐藏UI后直接输入
     * @return 是否成功执行
     */
    fun inputText(text: String, fromModel: Boolean = false): Boolean {
        // 设置程序输入标记，避免记录程序自动输入
        isProgrammaticInput = true
        // 模型发起时：设置 IME 零高度模式 + 发送隐藏广播（双保险）
        if (fromModel) {
            SimpleInputMethodService.setModelInputMode(true)
            try {
                val hideIntent = Intent(SimpleInputMethodService.ACTION_HIDE_KEYBOARD).apply {
                    setPackage(packageName)
                }
                sendBroadcast(hideIntent)
                Log.d(TAG, "fromModel=true，已设置模型模式并发送 HIDE_KEYBOARD")
            } catch (e: Exception) {
                Log.w(TAG, "发送隐藏键盘广播失败: ${e.message}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    softKeyboardController?.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
                    Log.d(TAG, "fromModel=true，已设置 SoftKeyboardController 隐藏模式")
                } catch (e: Exception) {
                    Log.w(TAG, "设置键盘隐藏模式失败: ${e.message}")
                }
            }
        }
        return try {
            Log.d(TAG, "========== 开始输入文本 ==========")
            Log.d(TAG, "输入文本内容: $text")
            
            // 步骤0: 确保使用自定义输入法（程序调用时总是切换到自定义输入法）
            Log.d(TAG, "步骤0: 确保使用自定义输入法...")
            val imeReady = ensureCustomIME()
            if (!imeReady) {
                Log.w(TAG, "⚠️ 无法确保自定义输入法，但继续尝试输入...")
            }
            
            // 获取当前活动窗口的根节点
            Log.d(TAG, "步骤1: 尝试获取根节点 (rootInActiveWindow)...")
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.e(TAG, "❌❌❌ 无法获取根节点 (rootInActiveWindow为null)")
                Log.e(TAG, "   可能原因: 无障碍服务未完全激活，或当前窗口不可访问")
                return false
            }
            Log.d(TAG, "✓ 步骤1完成: 成功获取根节点")
            Log.d(TAG, "   根节点类名: ${rootNode.className}")
            Log.d(TAG, "   根节点包名: ${rootNode.packageName}")
            
            // 查找可聚焦的输入框
            Log.d(TAG, "步骤2: 开始查找聚焦的输入框节点...")
            val focusedNode = findFocusedNode(rootNode)
            Log.d(TAG, "步骤2完成: ${if (focusedNode != null) "找到聚焦节点" else "未找到聚焦节点"}")
            
            if (focusedNode != null) {
                // 输出节点详细信息
                val className = focusedNode.className?.toString() ?: "未知"
                val isEditable = focusedNode.isEditable
                val isFocused = focusedNode.isFocused
                val textBefore = focusedNode.text?.toString() ?: ""
                val contentDescription = focusedNode.contentDescription?.toString() ?: ""
                val viewIdResourceName = focusedNode.viewIdResourceName ?: ""
                
                Log.d(TAG, "✓ 找到聚焦节点:")
                Log.d(TAG, "  - 类名: $className")
                Log.d(TAG, "  - 是否可编辑: $isEditable")
                Log.d(TAG, "  - 是否聚焦: $isFocused")
                Log.d(TAG, "  - 当前文本: $textBefore")
                Log.d(TAG, "  - 内容描述: $contentDescription")
                Log.d(TAG, "  - ViewId: $viewIdResourceName")
                
                // 检查节点是否支持输入
                val supportedActions = mutableListOf<String>()
                if (focusedNode.actionList != null) {
                    for (action in focusedNode.actionList) {
                        when (action.id) {
                            AccessibilityNodeInfo.ACTION_SET_TEXT -> supportedActions.add("ACTION_SET_TEXT")
                            AccessibilityNodeInfo.ACTION_PASTE -> supportedActions.add("ACTION_PASTE")
                            AccessibilityNodeInfo.ACTION_FOCUS -> supportedActions.add("ACTION_FOCUS")
                            AccessibilityNodeInfo.ACTION_CLICK -> supportedActions.add("ACTION_CLICK")
                        }
                    }
                }
                Log.d(TAG, "  - 支持的操作: ${supportedActions.joinToString(", ")}")
                
                // 如果有聚焦的节点，直接输入
                val arguments = android.os.Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
                
                Log.d(TAG, "尝试执行 ACTION_SET_TEXT 操作...")
                val success = focusedNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    arguments
                )
                
                if (success) {
                    Log.d(TAG, "✓✓✓ 步骤3成功: 文本输入成功: $text")
                    Log.d(TAG, "========== 输入文本结束（成功） ==========")
                    focusedNode.recycle()
                    rootNode.recycle()
                    return true
                } else {
                    Log.w(TAG, "❌❌❌ 步骤3失败: performAction返回false")
                    Log.w(TAG, "   可能原因:")
                    Log.w(TAG, "   1. 节点不支持ACTION_SET_TEXT操作")
                    Log.w(TAG, "   2. 应用阻止了无障碍服务的输入操作")
                    Log.w(TAG, "   3. 输入框处于不可编辑状态")
                    Log.w(TAG, "   4. 需要先执行其他操作（如点击、聚焦）")
                    
                    // 步骤3.5: ACTION_SET_TEXT失败，检测输入法并尝试输入法方案
                    Log.d(TAG, "步骤3.5: ACTION_SET_TEXT失败，尝试输入法方案...")
                    val imeSuccess = tryInputTextViaIMEWithAutoSwitch(text, rootNode, fromModel)
                    
                    focusedNode.recycle()
                    rootNode.recycle()
                    
                    if (imeSuccess) {
                        Log.d(TAG, "✓✓✓ 步骤3.5成功: 通过输入法输入成功: $text")
                        Log.d(TAG, "========== 输入文本结束（成功，使用输入法方案） ==========")
                        return true
                    } else {
                        Log.w(TAG, "❌ 步骤3.5失败: 输入法方案也失败")
                        Log.w(TAG, "========== 输入文本结束（失败） ==========")
                        return false
                    }
                }
            } else {
                // 如果没有聚焦的节点，尝试输入法方案
                Log.w(TAG, "❌❌❌ 步骤2失败: 未找到聚焦的输入框节点")
                Log.w(TAG, "   可能原因:")
                Log.w(TAG, "   1. 点击后输入框未获得焦点")
                Log.w(TAG, "   2. 等待时间不足（当前300ms可能不够）")
                Log.w(TAG, "   3. 应用输入框不支持标准焦点机制")
                Log.w(TAG, "   4. 坐标点击位置不准确")
                Log.d(TAG, "步骤2.6: 尝试使用自定义输入法方案...")
                
                // 尝试使用自定义输入法输入
                val imeSuccess = tryInputTextViaIME(text, fromModel)
                
                if (imeSuccess) {
                    Log.d(TAG, "✓✓✓ 步骤2.6成功: 通过输入法输入成功: $text")
                    rootNode.recycle()
                    Log.d(TAG, "========== 输入文本结束（成功，使用输入法方案） ==========")
                    return true
                } else {
                    Log.w(TAG, "❌ 步骤2.6失败: 输入法方案也失败")
                    
                    // 步骤2.7: 检测输入法并尝试自动切换
                    Log.d(TAG, "步骤2.7: 检测输入法状态并尝试自动切换...")
                    val autoSwitchSuccess = tryInputTextViaIMEWithAutoSwitch(text, rootNode, fromModel)
                    
                    if (autoSwitchSuccess) {
                        Log.d(TAG, "✓✓✓ 步骤2.7成功: 自动切换后输入成功: $text")
                        Log.d(TAG, "========== 输入文本结束（成功，自动切换后输入） ==========")
                        return true
                    }
                    
                    Log.w(TAG, "开始遍历所有可编辑节点（用于调试）...")
                    findAllEditableNodes(rootNode)
                    Log.w(TAG, "遍历完成，请检查上方是否有可编辑节点")
                    rootNode.recycle()
                    Log.w(TAG, "========== 输入文本结束（失败） ==========")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ 输入文本时发生异常: ${e.message}", e)
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            e.printStackTrace()
            Log.e(TAG, "========== 输入文本结束（异常） ==========")
            false
        } finally {
            // 恢复键盘显示模式（模型发起时在开头设置了 SHOW_MODE_HIDDEN）
            if (fromModel && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    softKeyboardController?.setShowMode(AccessibilityService.SHOW_MODE_AUTO)
                    Log.d(TAG, "已恢复键盘显示模式为 AUTO")
                } catch (e: Exception) {
                    Log.w(TAG, "恢复键盘显示模式失败: ${e.message}")
                }
            }
            // 模型输入结束：延迟恢复 IME 正常显示模式（与 hideKeyboardAfterClickWithRetries 统一用 300ms）
            if (fromModel) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    SimpleInputMethodService.setModelInputMode(false)
                    Log.d(TAG, "已恢复 IME 正常显示模式")
                }, 300)
            }
            // 延迟清除程序输入标记，确保文本变化事件不会被记录
            serviceScope.launch {
                delay(500) // 等待500ms，确保文本变化事件处理完成
                isProgrammaticInput = false
                Log.d(TAG, "程序输入标记已清除")
            }
        }
    }
    
    /**
     * 递归查找聚焦的节点
     */
    private fun findFocusedNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        
        if (node.isFocused) {
            Log.d(TAG, "找到聚焦节点: ${node.className}")
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val focused = findFocusedNode(child)
            if (focused != null) {
                return focused
            }
            child?.recycle()
        }
        
        return null
    }
    
    /**
     * 查找所有可编辑的节点（用于调试）
     */
    private fun findAllEditableNodes(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 10) return // 限制深度避免过多日志
        
        val indent = "  ".repeat(depth)
        val className = node.className?.toString() ?: "未知"
        val isEditable = node.isEditable
        val isFocused = node.isFocused
        val text = node.text?.toString() ?: ""
        
        if (isEditable) {
            Log.d(TAG, "$indent📝 可编辑节点 (深度$depth):")
            Log.d(TAG, "$indent  - 类名: $className")
            Log.d(TAG, "$indent  - 是否聚焦: $isFocused")
            Log.d(TAG, "$indent  - 文本: ${text.take(50)}")
            Log.d(TAG, "$indent  - ViewId: ${node.viewIdResourceName ?: "无"}")
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findAllEditableNodes(child, depth + 1)
            child?.recycle()
        }
    }
    
    /**
     * 确保使用自定义输入法（程序调用时自动切换）
     * 
     * @return 是否成功切换到自定义输入法
     */
    private fun ensureCustomIME(): Boolean {
        return try {
            Log.d(TAG, "ensureCustomIME: 开始检查当前输入法...")
            
            val currentIME = getCurrentIME()
            val customIMEId = "${packageName}/${SimpleInputMethodService::class.java.name}"
            
            Log.d(TAG, "当前输入法: $currentIME")
            Log.d(TAG, "目标输入法: $customIMEId")
            
            if (currentIME == customIMEId) {
                Log.d(TAG, "✓ 当前已是自定义输入法，无需切换")
                return true
            }
            
            Log.d(TAG, "当前不是自定义输入法，开始切换...")
            val switched = switchToCustomIME(customIMEId)
            
            if (switched) {
                Log.d(TAG, "✓ 切换成功，等待输入法激活...")
                // 等待输入法激活
                waitForIMEActivation(500)
                return true
            } else {
                Log.w(TAG, "⚠️ 切换失败，但继续尝试输入（可能输入法已激活）")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureCustomIME异常: ${e.message}", e)
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 获取当前激活的输入法ID
     * 
     * @return 输入法ID，如果获取失败返回null
     */
    fun getCurrentIME(): String? {
        return try {
            val imeId = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            imeId
        } catch (e: Exception) {
            Log.e(TAG, "获取当前输入法失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 切换到自定义输入法
     * 
     * @param customIMEId 自定义输入法的完整ID
     * @return 是否成功
     */
    private fun switchToCustomIME(customIMEId: String): Boolean {
        return try {
            Log.d(TAG, "switchToCustomIME: 开始切换...")
            
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm == null) {
                Log.e(TAG, "❌ 无法获取InputMethodManager")
                return false
            }
            
            // 在 AccessibilityService 中，无法直接获取 window token
            // 尝试使用 switchToNextInputMethod（向前切换）
            // 但这需要知道当前输入法的位置，可能不准确
            
            // 更可靠的方法：直接设置默认输入法（需要 WRITE_SECURE_SETTINGS 权限）
            // 但普通应用没有这个权限
            
            // 简化方案：如果当前不是自定义输入法，记录日志并继续
            // 因为用户可能已经手动切换，或者输入法切换是异步的
            Log.d(TAG, "注意: 在 AccessibilityService 中无法直接切换输入法")
            Log.d(TAG, "   请确保用户已切换到自定义输入法")
            Log.d(TAG, "   或者等待输入法自动激活")
            
            // 返回 true，让程序继续尝试（可能输入法已经激活）
            true
        } catch (e: Exception) {
            Log.e(TAG, "切换输入法异常: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 等待输入法激活
     * 
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否激活成功
     */
    private fun waitForIMEActivation(timeoutMs: Long): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            val checkInterval = 50L // 每50ms检查一次
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val imeService = SimpleInputMethodService.getInstance()
                if (imeService != null) {
                    Log.d(TAG, "✓ 输入法已激活")
                    return true
                }
                Thread.sleep(checkInterval)
            }
            
            Log.w(TAG, "⚠️ 等待输入法激活超时")
            false
        } catch (e: InterruptedException) {
            Log.e(TAG, "等待输入法激活被中断: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "等待输入法激活异常: ${e.message}", e)
            false
        }
    }
    
    /**
     * 通过自定义输入法输入文本
     * 
     * @param text 要输入的文本
     * @param fromModel 是否为模型发起。若为true，自定义键盘将隐藏UI后直接输入
     * @return 是否成功
     */
    private fun tryInputTextViaIME(text: String, fromModel: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "========== 开始尝试输入法方案 ==========")
            Log.d(TAG, "输入文本: $text")
            
            // 检查输入法是否已激活
            val imeService = SimpleInputMethodService.getInstance()
            if (imeService == null) {
                Log.w(TAG, "❌ 自定义输入法未激活")
                
                // 检测当前输入法，如果不是自定义输入法，尝试切换
                Log.d(TAG, "检测当前输入法状态...")
                val currentIME = getCurrentIME()
                val customIMEId = "${packageName}/${SimpleInputMethodService::class.java.name}"
                
                if (currentIME != customIMEId) {
                    Log.w(TAG, "当前不是自定义输入法: $currentIME")
                    Log.d(TAG, "尝试自动切换回自定义输入法...")
                    
                    // 尝试切换回自定义输入法
                    val switched = attemptSwitchToCustomIME(customIMEId)
                    
                    if (switched) {
                        Log.d(TAG, "✓ 切换成功，等待输入法激活...")
                        waitForIMEActivation(1000) // 等待更长时间
                        
                        // 切换后重试
                        val retryService = SimpleInputMethodService.getInstance()
                        if (retryService != null) {
                            Log.d(TAG, "✓ 输入法已激活，重试输入...")
                            return retryInputViaIME(text, fromModel)
                        }
                    }
                }
                
                Log.w(TAG, "   请确保:")
                Log.w(TAG, "   1. 在'设置 -> 系统 -> 语言和输入法'中启用自定义输入法")
                Log.w(TAG, "   2. 切换到自定义输入法")
                Log.d(TAG, "========== 输入法方案结束（输入法未激活） ==========")
                return false
            }
            
            Log.d(TAG, "✓ 自定义输入法已激活")
            
            // 通过Broadcast发送输入请求
            val intent = Intent(SimpleInputMethodService.ACTION_INPUT_TEXT).apply {
                putExtra(SimpleInputMethodService.EXTRA_TEXT, text)
                putExtra(SimpleInputMethodService.EXTRA_FROM_MODEL, fromModel)
                setPackage(packageName)
            }
            
            Log.d(TAG, "发送Broadcast请求输入...")
            sendBroadcast(intent)
            
            // 等待一小段时间让输入完成
            Thread.sleep(200)
            
            Log.d(TAG, "✓✓✓ 输入法方案执行完成")
            Log.d(TAG, "========== 输入法方案结束（成功） ==========")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ 输入法方案执行异常: ${e.message}", e)
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            e.printStackTrace()
            Log.d(TAG, "========== 输入法方案结束（异常） ==========")
            false
        }
    }
    
    /**
     * 重试通过输入法输入（切换后使用）
     */
    private fun retryInputViaIME(text: String, fromModel: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "retryInputViaIME: 重试输入...")
            
            val intent = Intent(SimpleInputMethodService.ACTION_INPUT_TEXT).apply {
                putExtra(SimpleInputMethodService.EXTRA_TEXT, text)
                putExtra(SimpleInputMethodService.EXTRA_FROM_MODEL, fromModel)
                setPackage(packageName)
            }
            
            sendBroadcast(intent)
            Thread.sleep(200)
            
            Log.d(TAG, "✓ 重试输入完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "重试输入异常: ${e.message}", e)
            false
        }
    }
    
    /**
     * 通过输入法输入文本，如果失败则自动切换并重试
     * 
     * @param text 要输入的文本
     * @param rootNode 根节点（用于回收）
     * @param fromModel 是否为模型发起
     * @return 是否成功
     */
    private fun tryInputTextViaIMEWithAutoSwitch(text: String, rootNode: AccessibilityNodeInfo, fromModel: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "tryInputTextViaIMEWithAutoSwitch: 开始...")
            
            // 先尝试正常输入
            val firstAttempt = tryInputTextViaIME(text, fromModel)
            if (firstAttempt) {
                return true
            }
            
            // 如果失败，检测输入法状态
            Log.d(TAG, "第一次尝试失败，检测输入法状态...")
            val currentIME = getCurrentIME()
            val customIMEId = "${packageName}/${SimpleInputMethodService::class.java.name}"
            
            if (currentIME != customIMEId) {
                Log.w(TAG, "检测到当前不是自定义输入法: $currentIME")
                Log.w(TAG, "尝试自动切换回自定义输入法...")
                
                val switched = attemptSwitchToCustomIME(customIMEId)
                
                if (switched) {
                    Log.d(TAG, "✓ 切换成功，等待激活并重试...")
                    waitForIMEActivation(2000) // 等待更长时间
                    
                    // 重试输入
                    val retrySuccess = tryInputTextViaIME(text, fromModel)
                    if (retrySuccess) {
                        Log.d(TAG, "✓✓✓ 自动切换后重试成功")
                        return true
                    }
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "tryInputTextViaIMEWithAutoSwitch异常: ${e.message}", e)
            false
        }
    }
    
    /**
     * 检查是否在输入法设置页面
     */
    fun isInIMESettingsPage(): Boolean {
        val packageName = getCurrentPackageName()
        val className = getCurrentClassName()
        
        val isSettings = packageName?.contains("settings", ignoreCase = true) == true &&
               (className?.contains("InputMethod", ignoreCase = true) == true ||
                className?.contains("Language", ignoreCase = true) == true ||
                className?.contains("Locale", ignoreCase = true) == true)
        
        Log.d(TAG, "isInIMESettingsPage: package=$packageName, class=$className, result=$isSettings")
        return isSettings
    }
    
    /**
     * 尝试切换到自定义输入法（用于输入失败时自动切换）
     * 注意：现在TYPE操作的输入法切换由ChatFragment控制，此方法仅用于其他场景
     */
    private fun attemptSwitchToCustomIME(customIMEId: String): Boolean {
        return try {
            Log.d(TAG, "attemptSwitchToCustomIME: 尝试切换...")
            
            // 如果已经打开过设置页面，不再重复打开
            if (hasOpenedIMESettings) {
                Log.d(TAG, "已打开过输入法设置页面，不再重复打开")
                return false
            }
            
            // 尝试通过 Broadcast 通知输入法切换
            try {
                val switchIntent = Intent("com.cloudcontrol.demo.SWITCH_TO_CUSTOM_IME").apply {
                    setPackage(packageName)
                }
                sendBroadcast(switchIntent)
                Thread.sleep(300)
                
                val currentIME = getCurrentIME()
                if (currentIME == customIMEId) {
                    Log.d(TAG, "✓ 切换成功（通过Broadcast）")
                    return true
                }
                
                // Broadcast失败，返回false（不再自动打开设置页面，由ChatFragment控制）
                Log.d(TAG, "Broadcast切换失败")
                return false
            } catch (e: Exception) {
                Log.e(TAG, "切换输入法失败: ${e.message}", e)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "attemptSwitchToCustomIME异常: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 检测是否是无障碍快捷弹窗
     * @param eventPackageName 事件的包名
     * @param eventClassName 事件的类名
     */
    private fun isAccessibilityShortcutDialog(eventPackageName: String?, eventClassName: String?): Boolean {
        return try {
            // 排除自己的应用，避免误检测
            if (eventPackageName == packageName) {
                Log.d(TAG, "检测无障碍快捷弹窗: 跳过自己的应用包名")
                return false
            }
            
            // 获取窗口根节点
            val rootNode = rootInActiveWindow ?: run {
                Log.d(TAG, "检测无障碍快捷弹窗: 无法获取根节点")
                return false
            }
            
            // 检查根节点的包名
            val rootPackageName = rootNode.packageName?.toString()
            val rootClassName = rootNode.className?.toString()
            
            // 排除自己的应用（双重检查）
            if (rootPackageName == packageName) {
                Log.d(TAG, "检测无障碍快捷弹窗: 跳过自己的应用根节点包名")
                rootNode.recycle()
                return false
            }
            
            // 查找特征文本
            val hasTopoClaw = findNodeWithText(rootNode, "TopoClaw") != null
            val hasClickHint = findNodeWithText(rootNode, "点击相应功能") != null
            val hasTalkBack = findNodeWithText(rootNode, "TalkBack") != null
            val hasModifyShortcut = findNodeWithText(rootNode, "修改快捷方式") != null
            
            // 检查是否包含输入弹窗标题（我们的输入弹窗，需要排除。中文："有什么可以帮到你"，英文："What can I help you with?"）
            val hasOurInputDialogTitle = findNodeWithText(rootNode, "有什么可以帮到你") != null ||
                findNodeWithText(rootNode, "What can I help you with?") != null
            
            // 记录检测信息
            Log.d(TAG, "检测无障碍快捷弹窗: eventPackage=$eventPackageName, eventClass=$eventClassName")
            Log.d(TAG, "  rootPackage=$rootPackageName, rootClass=$rootClassName")
            Log.d(TAG, "  文本匹配: TopoClaw=$hasTopoClaw, 点击相应功能=$hasClickHint, TalkBack=$hasTalkBack, 修改快捷方式=$hasModifyShortcut, 我们的输入弹窗=$hasOurInputDialogTitle")
            
            // 如果检测到我们的输入弹窗，直接排除（避免循环触发）
            if (hasOurInputDialogTitle) {
                Log.d(TAG, "检测到我们的输入弹窗，排除以避免循环触发")
                rootNode.recycle()
                return false
            }
            
            // 检测逻辑：必须包含"点击相应功能"（这是无障碍快捷弹窗的核心特征）
            // 并且至少包含以下之一：
            // 1. "TopoClaw" 或 "TalkBack"（服务名称）
            // 2. "修改快捷方式"（弹窗底部按钮）
            // 注意：不再使用"取消"按钮作为特征，因为我们的输入弹窗也有"取消"按钮
            val hasServiceName = hasTopoClaw || hasTalkBack
            val hasDialogButtons = hasModifyShortcut
            val found = hasClickHint && (hasServiceName || hasDialogButtons)
            
            if (found) {
                Log.d(TAG, "✓✓✓ 检测到无障碍快捷弹窗！")
            } else {
                Log.d(TAG, "✗ 不是无障碍快捷弹窗（缺少关键特征）")
            }
            
            rootNode.recycle()
            found
        } catch (e: Exception) {
            Log.e(TAG, "检测无障碍快捷弹窗失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 查找包含指定文本的节点
     * 使用迭代实现，避免深层无障碍树或循环引用导致的 StackOverflowError
     */
    private fun findNodeWithText(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null
        
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addFirst(node)
            var processedCount = 0
            val maxNodes = 5000 // 防止异常结构导致无限循环
            
            while (stack.isNotEmpty()) {
                if (processedCount++ > maxNodes) {
                    Log.w(TAG, "findNodeWithText: 达到最大遍历节点数限制")
                    recycleStackExcept(stack, node)
                    return null
                }
                
                val current = stack.removeFirst() ?: continue
                
                val text = current.text?.toString() ?: ""
                val contentDescription = current.contentDescription?.toString() ?: ""
                
                if (text.contains(targetText, ignoreCase = true) ||
                    contentDescription.contains(targetText, ignoreCase = true)) {
                    recycleStackExcept(stack, node)
                    return current
                }
                
                val childCount = current.childCount
                for (i in childCount - 1 downTo 0) {
                    current.getChild(i)?.let { stack.addFirst(it) }
                }
                
                if (current !== node) {
                    current.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查找节点时出错: ${e.message}", e)
        }
        
        return null
    }
    
    /** 将栈中除 root 外的节点 recycle，root 由调用方持有 */
    private fun recycleStackExcept(stack: ArrayDeque<AccessibilityNodeInfo>, root: AccessibilityNodeInfo) {
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            if (n !== root) n.recycle()
        }
    }
    
    /**
     * 获取当前屏幕分辨率（使用getRealMetrics获取包括系统UI的完整屏幕尺寸）
     */
    private fun getScreenSize(): Pair<Int, Int> {
        return try {
            val windowManager = getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (windowManager != null) {
                val displayMetrics = android.util.DisplayMetrics()
                val display = windowManager.defaultDisplay
                // 使用getRealMetrics获取实际屏幕分辨率（包括状态栏和导航栏）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    display.getRealMetrics(displayMetrics)
                } else {
                    @Suppress("DEPRECATION")
                    display.getMetrics(displayMetrics)
                }
                Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
            } else {
                // 降级方案：使用默认值
                Log.w(TAG, "无法获取WindowManager，使用默认分辨率")
                Pair(DESIGN_WIDTH, DESIGN_HEIGHT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕分辨率失败: ${e.message}", e)
            // 降级方案：使用设计分辨率
            Pair(DESIGN_WIDTH, DESIGN_HEIGHT)
        }
    }
    
    /**
     * 将相对坐标转换为适应屏幕大小的绝对坐标
     * @return Pair<Int, Int> 转换后的绝对坐标 (x, y)
     */
    private fun convertToAbsoluteCoordinates(): Pair<Int, Int> {
        val (screenWidth, screenHeight) = getScreenSize()
        val absoluteX = (screenWidth * RELATIVE_X).toInt().coerceIn(0, screenWidth - 1)
        val absoluteY = (screenHeight * RELATIVE_Y).toInt().coerceIn(0, screenHeight - 1)
        
        Log.d(TAG, "========== 坐标转换详情 ==========")
        Log.d(TAG, "设计分辨率: ${DESIGN_WIDTH}x${DESIGN_HEIGHT}")
        Log.d(TAG, "设计坐标: ($DESIGN_X, $DESIGN_Y)")
        Log.d(TAG, "实际屏幕分辨率: ${screenWidth}x${screenHeight}")
        Log.d(TAG, "相对比例: X=${RELATIVE_X}, Y=${RELATIVE_Y}")
        Log.d(TAG, "转换后坐标: ($absoluteX, $absoluteY)")
        Log.d(TAG, "Y坐标计算: ${screenHeight} * ${RELATIVE_Y} = ${screenHeight * RELATIVE_Y} -> $absoluteY")
        Log.d(TAG, "==================================")
        
        return Pair(absoluteX, absoluteY)
    }
    
    /**
     * 触发显示输入弹窗（带防抖）
     * 先点击指定坐标，然后等待1秒后发送广播显示输入弹窗
     */
    private fun triggerInputDialog() {
        val currentTime = System.currentTimeMillis()
        
        Log.d(TAG, "========== triggerInputDialog 调用 ==========")
        Log.d(TAG, "当前时间: $currentTime")
        Log.d(TAG, "上次触发时间: $lastShortcutDialogTriggerTime")
        Log.d(TAG, "时间间隔: ${currentTime - lastShortcutDialogTriggerTime}ms")
        Log.d(TAG, "防抖间隔: ${SHORTCUT_DIALOG_TRIGGER_INTERVAL}ms")
        
        // 防抖检查：0.5秒内只触发一次
        if (currentTime - lastShortcutDialogTriggerTime < SHORTCUT_DIALOG_TRIGGER_INTERVAL) {
            Log.d(TAG, "✗ 触发输入弹窗被防抖拦截，距离上次触发仅${currentTime - lastShortcutDialogTriggerTime}ms（需要等待${SHORTCUT_DIALOG_TRIGGER_INTERVAL}ms）")
            Log.d(TAG, "==========================================")
            return
        }
        
        Log.d(TAG, "✓ 防抖检查通过，继续触发输入弹窗")
        lastShortcutDialogTriggerTime = currentTime
        hasTriggeredInputDialog = true
        
        // 0.5秒后重置标志，允许再次触发
        serviceScope.launch {
            delay(SHORTCUT_DIALOG_TRIGGER_INTERVAL)
            hasTriggeredInputDialog = false
            Log.d(TAG, "输入弹窗触发标志已重置，可以再次触发")
        }
        
        // 在协程中执行点击操作，然后发送广播
        serviceScope.launch {
            try {
                Log.d(TAG, "检测到无障碍快捷弹窗，等待0.2秒后开始点击操作")
                
                // 先等待0.2秒，确保弹窗完全显示
                delay(200)
                
                // 获取屏幕分辨率并转换坐标
                val (x, y) = convertToAbsoluteCoordinates()
                Log.d(TAG, "开始点击操作: 设计坐标($DESIGN_X, $DESIGN_Y) -> 实际坐标($x, $y)")
                
                // 点击指定坐标
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val clickSuccess = performClick(x, y, fromModel = false)
                    if (clickSuccess) {
                        Log.d(TAG, "点击操作成功，等待0.2秒后发送广播")
                        delay(200) // 等待0.2秒确保点击操作完成
                    } else {
                        Log.w(TAG, "点击操作失败，但仍继续发送广播")
                    }
                } else {
                    Log.w(TAG, "Android版本低于N，无法执行点击操作，直接发送广播")
                }
                
                // 发送广播显示输入弹窗（标识为从无障碍快捷方式触发）
                val intent = Intent(TaskIndicatorOverlayManager.ACTION_START_TASK).apply {
                    setPackage(packageName)
                    putExtra("from_accessibility_shortcut", true) // 标识从无障碍快捷方式触发
                }
                sendBroadcast(intent)
                Log.d(TAG, "已发送广播唤醒输入弹窗（从无障碍快捷方式触发）")
            } catch (e: Exception) {
                Log.e(TAG, "触发输入弹窗失败: ${e.message}", e)
            }
        }
    }
}

