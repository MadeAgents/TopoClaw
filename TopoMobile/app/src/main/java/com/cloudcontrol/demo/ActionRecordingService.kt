package com.cloudcontrol.demo

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 动作记录服务
 * 基于 AccessibilityService，用于记录用户在录屏期间的所有动作
 */
class ActionRecordingService : AccessibilityService() {
    
    companion object {
        private const val TAG = "ActionRecordingService"
        private var instance: ActionRecordingService? = null
        private var isRecording = false
        private var recordingStartTime: Long = 0
        private val actionList = CopyOnWriteArrayList<ActionRecord>()
        private var actionIdCounter = 0
        
        // 触摸状态跟踪（用于检测滑动操作）
        private var touchStartX: Int? = null
        private var touchStartY: Int? = null
        private var touchStartTime: Long = 0
        private var touchStartPackage: String? = null
        
        // 滚动事件去重（避免记录微小的滚动）
        private const val MIN_SCROLL_DELTA = 50 // 最小滚动偏移量（像素）
        private var lastScrollTime: Long = 0
        private var lastScrollPackage: String? = null
        private var lastScrollDeltaX: Int = 0
        private var lastScrollDeltaY: Int = 0
        private const val SCROLL_MERGE_THRESHOLD_MS = 100 // 100ms内的滚动事件合并
        
        /**
         * 获取服务实例
         */
        fun getInstance(): ActionRecordingService? {
            return instance
        }
        
        /**
         * 检查服务是否已启用
         */
        fun isServiceEnabled(): Boolean {
            return instance != null
        }
        
        /**
         * 开始录制
         */
        fun startRecording() {
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            actionList.clear()
            actionIdCounter = 0
            // 重置触摸状态
            touchStartX = null
            touchStartY = null
            touchStartTime = 0
            touchStartPackage = null
            // 重置滚动状态
            lastScrollTime = 0
            lastScrollPackage = null
            lastScrollDeltaX = 0
            lastScrollDeltaY = 0
            Log.d(TAG, "开始录制动作，开始时间: $recordingStartTime")
        }
        
        /**
         * 停止录制
         */
        fun stopRecording(): List<ActionRecord> {
            isRecording = false
            val actions = ArrayList(actionList)
            Log.d(TAG, "停止录制动作，共记录 ${actions.size} 个动作")
            return actions
        }
        
        /**
         * 检查是否正在录制
         */
        fun isRecording(): Boolean {
            return isRecording
        }
        
        /**
         * 获取录制开始时间
         */
        fun getRecordingStartTime(): Long {
            return recordingStartTime
        }
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "动作记录服务已连接")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRecording = false
        actionList.clear()
        Log.d(TAG, "动作记录服务已销毁")
    }
    
    /**
     * 当无障碍事件发生时调用
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) {
            return
        }
        
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    recordClickAction(event)
                }
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    recordLongClickAction(event)
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    recordScrollAction(event)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    recordWindowChangedAction(event)
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    recordTouchStartAction(event)
                }
                AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                    recordTouchEndAction(event)
                }
                // 监听更多事件类型以提高覆盖率
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // 文本变化可能伴随输入操作，但这里不记录（避免过多噪音）
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    // 焦点变化可能伴随点击，但这里不记录（避免过多噪音）
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理无障碍事件时出错: ${e.message}", e)
        }
    }
    
    /**
     * 记录点击动作（通过TYPE_VIEW_CLICKED事件）
     * 注意：很多应用不会触发此事件，所以主要依赖触摸事件检测点击
     */
    private fun recordClickAction(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            
            val currentTime = System.currentTimeMillis()
            
            // 检查是否与最近的触摸事件记录的点击重复（避免重复记录）
            // 如果最近100ms内有触摸事件记录的点击，且坐标相近，则跳过
            val recentClick = actionList.lastOrNull { 
                it.type == "click" && 
                (currentTime - it.timestamp) < 100 &&
                it.packageName == event.packageName?.toString()
            }
            
            if (recentClick != null && recentClick.centerX != null && recentClick.centerY != null) {
                val distance = kotlin.math.sqrt(
                    ((bounds.centerX() - recentClick.centerX!!) * (bounds.centerX() - recentClick.centerX!!) + 
                     (bounds.centerY() - recentClick.centerY!!) * (bounds.centerY() - recentClick.centerY!!)).toDouble()
                ).toInt()
                
                if (distance < 50) {
                    Log.d(TAG, "跳过重复点击: ${event.packageName} at (${bounds.centerX()}, ${bounds.centerY()}), 与最近点击距离=$distance")
                    return
                }
            }
            
            val action = ActionRecord(
                actionId = ++actionIdCounter,
                type = "click",
                eventType = event.eventType,
                packageName = event.packageName?.toString(),
                className = event.className?.toString(),
                viewText = event.text?.firstOrNull()?.toString(),
                contentDescription = source.contentDescription?.toString(),
                bounds = Rect(bounds),
                centerX = bounds.centerX(),
                centerY = bounds.centerY(),
                timestamp = currentTime,
                relativeTimeMs = currentTime - recordingStartTime
            )
            
            actionList.add(action)
            Log.d(TAG, "记录点击动作（TYPE_VIEW_CLICKED）: ${event.packageName} at (${bounds.centerX()}, ${bounds.centerY()})")
        } finally {
            source.recycle()
        }
    }
    
    /**
     * 记录长按动作
     */
    private fun recordLongClickAction(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            
            val action = ActionRecord(
                actionId = ++actionIdCounter,
                type = "long_click",
                eventType = event.eventType,
                packageName = event.packageName?.toString(),
                className = event.className?.toString(),
                viewText = event.text?.firstOrNull()?.toString(),
                contentDescription = source.contentDescription?.toString(),
                bounds = Rect(bounds),
                centerX = bounds.centerX(),
                centerY = bounds.centerY(),
                timestamp = System.currentTimeMillis(),
                relativeTimeMs = System.currentTimeMillis() - recordingStartTime
            )
            
            actionList.add(action)
            Log.d(TAG, "记录长按动作: ${event.packageName} at (${bounds.centerX()}, ${bounds.centerY()})")
        } finally {
            source.recycle()
        }
    }
    
    /**
     * 记录滚动动作（带过滤和去重）
     */
    private fun recordScrollAction(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            
            // 获取滚动偏移量
            val scrollDeltaX = if (event.scrollDeltaX != null) event.scrollDeltaX else 0
            val scrollDeltaY = if (event.scrollDeltaY != null) event.scrollDeltaY else 0
            
            // 过滤：只记录滚动偏移量超过阈值的滚动事件
            val totalDelta = kotlin.math.abs(scrollDeltaX) + kotlin.math.abs(scrollDeltaY)
            if (totalDelta < MIN_SCROLL_DELTA) {
                Log.d(TAG, "忽略微小滚动: delta: ($scrollDeltaX, $scrollDeltaY), 总偏移: $totalDelta")
                return
            }
            
            val currentTime = System.currentTimeMillis()
            val currentPackage = event.packageName?.toString()
            
            // 去重：合并短时间内相同包的连续滚动事件
            if (lastScrollTime > 0 && 
                currentTime - lastScrollTime < SCROLL_MERGE_THRESHOLD_MS &&
                currentPackage == lastScrollPackage) {
                // 合并滚动偏移量
                val mergedDeltaX = lastScrollDeltaX + scrollDeltaX
                val mergedDeltaY = lastScrollDeltaY + scrollDeltaY
                
                // 更新最后一个滚动动作
                val lastAction = actionList.lastOrNull()
                if (lastAction != null && lastAction.type == "scroll" && lastAction.packageName == currentPackage) {
                    // 移除最后一个滚动动作，添加合并后的新动作
                    actionList.removeAt(actionList.size - 1)
                    actionIdCounter-- // 回退计数器
                }
                
                // 记录合并后的滚动动作
                val action = ActionRecord(
                    actionId = ++actionIdCounter,
                    type = "scroll",
                    eventType = event.eventType,
                    packageName = currentPackage,
                    className = event.className?.toString(),
                    viewText = event.text?.firstOrNull()?.toString(),
                    contentDescription = source.contentDescription?.toString(),
                    bounds = Rect(bounds),
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    scrollDeltaX = mergedDeltaX,
                    scrollDeltaY = mergedDeltaY,
                    timestamp = currentTime,
                    relativeTimeMs = currentTime - recordingStartTime
                )
                
                actionList.add(action)
                lastScrollDeltaX = mergedDeltaX
                lastScrollDeltaY = mergedDeltaY
                Log.d(TAG, "记录合并滚动动作: ${event.packageName}, 合并delta: ($mergedDeltaX, $mergedDeltaY)")
            } else {
                // 记录新的滚动动作
                val action = ActionRecord(
                    actionId = ++actionIdCounter,
                    type = "scroll",
                    eventType = event.eventType,
                    packageName = currentPackage,
                    className = event.className?.toString(),
                    viewText = event.text?.firstOrNull()?.toString(),
                    contentDescription = source.contentDescription?.toString(),
                    bounds = Rect(bounds),
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    scrollDeltaX = scrollDeltaX,
                    scrollDeltaY = scrollDeltaY,
                    timestamp = currentTime,
                    relativeTimeMs = currentTime - recordingStartTime
                )
                
                actionList.add(action)
                lastScrollTime = currentTime
                lastScrollPackage = currentPackage
                lastScrollDeltaX = scrollDeltaX
                lastScrollDeltaY = scrollDeltaY
                Log.d(TAG, "记录滚动动作: ${event.packageName}, delta: ($scrollDeltaX, $scrollDeltaY)")
            }
        } finally {
            source.recycle()
        }
    }
    
    /**
     * 记录窗口变化动作
     */
    private fun recordWindowChangedAction(event: AccessibilityEvent) {
        val action = ActionRecord(
            actionId = ++actionIdCounter,
            type = "window_changed",
            eventType = event.eventType,
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            timestamp = System.currentTimeMillis(),
            relativeTimeMs = System.currentTimeMillis() - recordingStartTime
        )
        
        actionList.add(action)
        Log.d(TAG, "记录窗口变化动作: ${event.packageName}/${event.className}")
    }
    
    /**
     * 记录触摸开始动作（记录坐标，用于检测滑动）
     */
    private fun recordTouchStartAction(event: AccessibilityEvent) {
        val source = event.source
        val currentTime = System.currentTimeMillis()
        val currentPackage = event.packageName?.toString()
        
        // 尝试获取触摸坐标
        var touchX: Int? = null
        var touchY: Int? = null
        
        if (source != null) {
            try {
                val bounds = Rect()
                source.getBoundsInScreen(bounds)
                touchX = bounds.centerX()
                touchY = bounds.centerY()
            } catch (e: Exception) {
                Log.w(TAG, "获取触摸开始坐标失败: ${e.message}")
            } finally {
                source.recycle()
            }
        }
        
        // 保存触摸开始状态（用于检测滑动）
        if (touchX != null && touchY != null) {
            touchStartX = touchX
            touchStartY = touchY
            touchStartTime = currentTime
            touchStartPackage = currentPackage
        }
        
        // 不记录touch_start事件本身（避免噪音），只在touch_end时判断是否为滑动
        Log.d(TAG, "触摸开始: ${event.packageName} at ($touchX, $touchY)")
    }
    
    /**
     * 记录触摸结束动作（检测滑动操作）
     */
    private fun recordTouchEndAction(event: AccessibilityEvent) {
        val source = event.source
        val currentTime = System.currentTimeMillis()
        val currentPackage = event.packageName?.toString()
        
        // 尝试获取触摸结束坐标
        var touchEndX: Int? = null
        var touchEndY: Int? = null
        
        if (source != null) {
            try {
                val bounds = Rect()
                source.getBoundsInScreen(bounds)
                touchEndX = bounds.centerX()
                touchEndY = bounds.centerY()
            } catch (e: Exception) {
                Log.w(TAG, "获取触摸结束坐标失败: ${e.message}")
            } finally {
                source.recycle()
            }
        }
        
        // 如果有触摸开始坐标，判断是滑动还是点击
        if (touchStartX != null && touchStartY != null && 
            touchEndX != null && touchEndY != null &&
            currentPackage == touchStartPackage) {
            
            val deltaX = touchEndX - touchStartX!!
            val deltaY = touchEndY - touchStartY!!
            val distance = kotlin.math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toInt()
            val duration = currentTime - touchStartTime
            
            // 判断是否为滑动：距离超过阈值（50像素）且时间合理（50ms-2000ms）
            if (distance > 50 && duration >= 50 && duration <= 2000) {
                // 记录滑动操作
                val gestureType = when {
                    kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) -> {
                        if (deltaX > 0) "swipe_right" else "swipe_left"
                    }
                    else -> {
                        if (deltaY > 0) "swipe_down" else "swipe_up"
                    }
                }
                
                val action = ActionRecord(
                    actionId = ++actionIdCounter,
                    type = "swipe",
                    eventType = event.eventType,
                    packageName = currentPackage,
                    className = event.className?.toString(),
                    bounds = null,
                    centerX = touchStartX,
                    centerY = touchStartY,
                    scrollDeltaX = deltaX, // 使用scrollDeltaX/Y存储滑动偏移
                    scrollDeltaY = deltaY,
                    gestureType = gestureType,
                    timestamp = currentTime,
                    relativeTimeMs = currentTime - recordingStartTime
                )
                
                actionList.add(action)
                Log.d(TAG, "记录滑动动作: ${event.packageName}, $gestureType, 距离: $distance, 时间: ${duration}ms")
            } else if (distance <= 50 && duration <= 500) {
                // 距离很短且时间很短，判断为点击操作
                // 注意：这里记录点击，因为TYPE_VIEW_CLICKED在很多应用中不会触发
                val action = ActionRecord(
                    actionId = ++actionIdCounter,
                    type = "click",
                    eventType = event.eventType,
                    packageName = currentPackage,
                    className = event.className?.toString(),
                    bounds = null,
                    centerX = touchStartX,
                    centerY = touchStartY,
                    timestamp = currentTime,
                    relativeTimeMs = currentTime - recordingStartTime
                )
                
                actionList.add(action)
                Log.d(TAG, "记录点击动作（通过触摸事件）: ${event.packageName} at ($touchStartX, $touchStartY), 距离=$distance, 时间=${duration}ms")
            } else {
                // 距离很短但时间很长，可能是长按或其他操作
                Log.d(TAG, "触摸结束但不符合点击或滑动条件: 距离=$distance, 时间=${duration}ms")
            }
        } else if (touchStartX != null && touchStartY != null && currentPackage == touchStartPackage) {
            // 有触摸开始但没有触摸结束坐标，可能是点击（触摸结束事件没有source）
            // 使用触摸开始坐标记录点击
            val duration = currentTime - touchStartTime
            if (duration <= 500) {
                val action = ActionRecord(
                    actionId = ++actionIdCounter,
                    type = "click",
                    eventType = event.eventType,
                    packageName = currentPackage,
                    className = event.className?.toString(),
                    bounds = null,
                    centerX = touchStartX,
                    centerY = touchStartY,
                    timestamp = currentTime,
                    relativeTimeMs = currentTime - recordingStartTime
                )
                
                actionList.add(action)
                Log.d(TAG, "记录点击动作（触摸结束无坐标）: ${event.packageName} at ($touchStartX, $touchStartY), 时间=${duration}ms")
            }
        }
        
        // 重置触摸状态
        touchStartX = null
        touchStartY = null
        touchStartTime = 0
        touchStartPackage = null
    }
    
    /**
     * 当服务被中断时调用
     */
    override fun onInterrupt() {
        Log.w(TAG, "动作记录服务被中断")
    }
}

