package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 轨迹记录器
 * 负责记录、缓冲和保存轨迹数据到本地文件
 */
object TrajectoryRecorder {
    private const val TAG = "TrajectoryRecorder"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_IS_RECORDING = "is_trajectory_recording"
    private const val RECORDS_DIR = "trajectory_records"
    private const val BATCH_SIZE = 50
    private const val BATCH_INTERVAL_MS = 5000L // 5秒
    
    private val gson = Gson()
    
    @Volatile private var isRecording = false
    @Volatile private var currentSession: TrajectorySession? = null
    private val eventQueue = ConcurrentLinkedQueue<TrajectoryEvent>()
    private val flushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var flushRunnable: Runnable? = null
    private var lastFlushTime: Long = 0L
    
    // 事件序列号计数器（用于保证事件顺序）
    @Volatile private var sequenceCounter: Long = 0L
    
    /**
     * 开始记录
     */
    fun startRecording(context: Context) {
        if (isRecording) {
            Log.d(TAG, "已经在记录中，跳过")
            return
        }
        
        try {
            recordingContext = context.applicationContext
            
            // 初始化云侧配置（如果尚未初始化）
            TrajectoryCloudConfig.initialize(context)
            
            val displayMetrics = context.resources.displayMetrics
            val deviceInfo = DeviceInfo(
                screenWidth = displayMetrics.widthPixels,
                screenHeight = displayMetrics.heightPixels,
                density = displayMetrics.density,
                densityDpi = displayMetrics.densityDpi
            )
            
            val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            currentSession = TrajectorySession(
                sessionId = sessionId,
                startTime = System.currentTimeMillis(),
                deviceInfo = deviceInfo,
                events = mutableListOf()
            )
            
            isRecording = true
            eventQueue.clear()
            lastFlushTime = System.currentTimeMillis()
            sequenceCounter = 0L // 重置序列号计数器
            
            // 重置窗口去重状态（新会话）
            MyAccessibilityService.resetLastRecordedWindow()
            
            // 启动定期刷新任务
            startFlushTask(context)

            // 记录剪切板基线（开始采集前）
            TrajectoryClipboardMonitor.startForSession(context, sessionId)
            
            // 显示导航悬浮球（仅在轨迹采集模式下）
            TrajectoryNavigationOverlayManager.show(context)
            
            Log.d(TAG, "开始轨迹记录: $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "开始记录失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止记录
     */
    fun stopRecording(context: Context) {
        if (!isRecording) {
            return
        }
        
        try {
            // 先标记会话结束，等待回到 TopoClaw 前台后再比对剪切板
            TrajectoryClipboardMonitor.markSessionFinished(currentSession?.sessionId)

            isRecording = false
            
            // 停止刷新任务
            stopFlushTask()
            
            // 立即刷新剩余事件
            flushEvents(context)
            
            // 保存会话
            currentSession?.let { session ->
                val completedSession = session.copy(endTime = System.currentTimeMillis())
                saveSessionToFile(context, completedSession)
            }
            
            currentSession = null
            eventQueue.clear()
            recordingContext = null
            
            // 隐藏导航悬浮球
            TrajectoryNavigationOverlayManager.hide()

            Log.d(TAG, "轨迹记录已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止记录失败: ${e.message}", e)
        }
    }
    
    /**
     * 记录事件
     * 自动为事件分配序列号，确保事件顺序
     * @param event 轨迹事件
     * @param screenshot 已截取的截图（可选，动作前采集则直接使用）
     * @param xml 已采集的页面 XML（可选，动作前采集则直接使用）
     */
    fun recordEvent(event: TrajectoryEvent, screenshot: android.graphics.Bitmap? = null, xml: String? = null) {
        if (!isRecording) {
            return
        }
        
        try {
            // 为事件分配序列号（如果还没有）
            val eventWithSequence = if (event.sequenceNumber == null) {
                val nextSequence = ++sequenceCounter
                event.copy(sequenceNumber = nextSequence)
            } else {
                event
            }
            
            eventQueue.offer(eventWithSequence)
            currentSession?.events?.add(eventWithSequence)
            
            // 如果启用云侧上传，立即上传事件
            if (TrajectoryCloudConfig.isEnabled() && recordingContext != null) {
                TrajectoryCloudService.uploadEventAsync(
                    context = recordingContext,
                    event = eventWithSequence,
                    sessionId = currentSession?.sessionId,
                    captureScreenshot = if (screenshot == null) TrajectoryCloudConfig.shouldUploadScreenshot() else false,
                    screenshot = screenshot,
                    xml = xml
                )
            }
            
            // 如果队列达到批量大小，立即刷新
            if (eventQueue.size >= BATCH_SIZE) {
                flushEvents(recordingContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "记录事件失败: ${e.message}", e)
        }
    }

    /**
     * 在会话已结束后补写事件（用于回到应用前台后补写剪切板变化）。
     */
    fun recordEventForCompletedSession(context: Context, sessionId: String, event: TrajectoryEvent): Boolean {
        return try {
            val recordsDir = File(context.getExternalFilesDir(null), RECORDS_DIR)
            val file = File(recordsDir, "$sessionId.json")
            if (!file.exists()) {
                Log.w(TAG, "补写事件失败，会话文件不存在: $sessionId")
                return false
            }

            val session = gson.fromJson(file.readText(), TrajectorySession::class.java)
            val nextSeq = (session.events.maxOfOrNull { it.sequenceNumber ?: 0L } ?: 0L) + 1L
            val eventWithSeq = if (event.sequenceNumber == null) event.copy(sequenceNumber = nextSeq) else event

            val updatedEvents = session.events.toMutableList().apply { add(eventWithSeq) }
            val updatedSession = session.copy(events = updatedEvents)
            file.writeText(gson.toJson(updatedSession))

            // 若启用云侧上传，同步补发到云端同一 session
            if (TrajectoryCloudConfig.isEnabled()) {
                TrajectoryCloudService.uploadEventAsync(
                    context = context.applicationContext,
                    event = eventWithSeq,
                    sessionId = sessionId,
                    captureScreenshot = false,
                    screenshot = null,
                    xml = null
                )
            }
            Log.d(TAG, "会话结束后补写事件成功: session=$sessionId, type=${eventWithSeq.type}, seq=${eventWithSeq.sequenceNumber}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "会话结束后补写事件失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 检查是否正在记录
     */
    fun isRecording(): Boolean {
        return isRecording
    }
    
    /**
     * 获取当前会话ID（在 stop 之前调用）
     */
    fun getCurrentSessionId(): String? = currentSession?.sessionId
    
    /**
     * 启动刷新任务
     */
    private fun startFlushTask(context: Context) {
        stopFlushTask()
        
        flushRunnable = Runnable {
            if (isRecording) {
                flushEvents(context)
                flushHandler.postDelayed(flushRunnable!!, BATCH_INTERVAL_MS)
            }
        }
        
        flushHandler.postDelayed(flushRunnable!!, BATCH_INTERVAL_MS)
    }
    
    /**
     * 停止刷新任务
     */
    private fun stopFlushTask() {
        flushRunnable?.let {
            flushHandler.removeCallbacks(it)
            flushRunnable = null
        }
    }
    
    // 保存context引用
    @Volatile private var recordingContext: Context? = null
    
    /**
     * 刷新事件到文件
     */
    private fun flushEvents(context: Context?) {
        val ctx = context ?: recordingContext
        if (ctx == null || eventQueue.isEmpty()) {
            return
        }
        
        try {
            val eventsToFlush = mutableListOf<TrajectoryEvent>()
            while (eventQueue.isNotEmpty() && eventsToFlush.size < BATCH_SIZE) {
                eventQueue.poll()?.let { eventsToFlush.add(it) }
            }
            
            if (eventsToFlush.isNotEmpty()) {
                // 事件已经添加到currentSession.events中，这里只需要保存文件
                currentSession?.let { session ->
                    saveSessionToFile(ctx, session)
                }
                
                lastFlushTime = System.currentTimeMillis()
                Log.d(TAG, "已刷新 ${eventsToFlush.size} 个事件到文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "刷新事件失败: ${e.message}", e)
        }
    }
    
    /**
     * 保存会话到文件
     */
    private fun saveSessionToFile(context: Context, session: TrajectorySession) {
        try {
            val recordsDir = File(context.getExternalFilesDir(null), RECORDS_DIR)
            if (!recordsDir.exists()) {
                recordsDir.mkdirs()
            }
            
            val file = File(recordsDir, "${session.sessionId}.json")
            val json = gson.toJson(session)
            file.writeText(json)
            
            Log.d(TAG, "会话已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存会话失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取已记录的会话列表
     */
    fun getRecordedSessions(context: Context): List<File> {
        return try {
            val recordsDir = File(context.getExternalFilesDir(null), RECORDS_DIR)
            if (!recordsDir.exists()) {
                return emptyList()
            }
            
            recordsDir.listFiles { _, name ->
                name.endsWith(".json")
            }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "获取会话列表失败: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 删除会话文件
     */
    fun deleteSession(context: Context, fileName: String): Boolean {
        return try {
            val recordsDir = File(context.getExternalFilesDir(null), RECORDS_DIR)
            val file = File(recordsDir, fileName)
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除会话失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 根据原会话ID和Query计算新会话ID（与 appendQueryToSession 使用相同的 sanitize 规则）
     */
    fun getNewSessionIdWithQuery(oldSessionId: String, query: String): String {
        if (query.isBlank()) return oldSessionId
        val sanitizedQuery = query.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(80)
            .ifBlank { "query" }
        return "${oldSessionId}_$sanitizedQuery"
    }
    
    /**
     * 在轨迹记录名称后面追加 Query 后缀
     * 例如 "20260221_214916.json" + "打开设置" -> "20260221_214916_打开设置.json"
     * @param context 上下文
     * @param oldFileName 原文件名（如 "20260221_214916.json"）
     * @param query 用户输入的 Query，会做文件名安全处理（空格等替换为下划线）
     * @return 是否重命名成功
     */
    fun appendQueryToSession(context: Context, oldFileName: String, query: String): Boolean {
        if (query.isBlank()) return false
        return try {
            val recordsDir = File(context.getExternalFilesDir(null), RECORDS_DIR)
            val oldFile = File(recordsDir, oldFileName)
            if (!oldFile.exists()) {
                Log.e(TAG, "appendQueryToSession: 文件不存在 $oldFileName")
                return false
            }
            val oldSessionId = oldFileName.removeSuffix(".json")
            val newSessionId = getNewSessionIdWithQuery(oldSessionId, query)
            val newFileName = "$newSessionId.json"
            val newFile = File(recordsDir, newFileName)
            if (newFile.exists()) {
                Log.e(TAG, "appendQueryToSession: 目标文件已存在 $newFileName")
                return false
            }
            val json = oldFile.readText()
            val session = gson.fromJson(json, TrajectorySession::class.java)
            val updatedSession = session.copy(sessionId = newSessionId)
            newFile.writeText(gson.toJson(updatedSession))
            oldFile.delete()
            Log.d(TAG, "appendQueryToSession: 已重命名 $oldFileName -> $newFileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "appendQueryToSession 失败: ${e.message}", e)
            false
        }
    }
}

