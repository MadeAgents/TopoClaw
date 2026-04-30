package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.WindowManager
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream

/**
 * 轨迹云侧服务
 * 负责将轨迹事件和截图上传到云侧服务器
 */
object TrajectoryCloudService {
    private const val TAG = "TrajectoryCloudService"
    private const val SCREENSHOT_FAILURE_POPUP_COOLDOWN_MS = 5000L
    
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var screenshotFailureDialog: android.app.AlertDialog? = null
    @Volatile private var lastScreenshotFailurePopupAt: Long = 0L
    
    // 需要过滤的应用包名列表
    private val FILTERED_PACKAGES = setOf(
        "com.android.launcher",
        "com.cloudcontrol.demo"
    )
    
    /**
     * 检查事件是否需要过滤（不上传到云侧）
     * @param event 轨迹事件
     * @return true表示需要过滤，false表示可以上传
     */
    private fun shouldFilterEvent(event: TrajectoryEvent): Boolean {
        // 过滤键盘弹出和键盘收起事件
        if (event.type == TrajectoryEventType.KEYBOARD_SHOW || 
            event.type == TrajectoryEventType.KEYBOARD_HIDE) {
            Log.d(TAG, "过滤事件: ${event.type}")
            return true
        }
        
        // BACK_BUTTON 和 HOME_BUTTON 是系统级操作，应该始终上传，不受包名过滤影响
        if (event.type == TrajectoryEventType.BACK_BUTTON || 
            event.type == TrajectoryEventType.HOME_BUTTON ||
            event.type == TrajectoryEventType.CLIPBOARD_CHANGE) {
            return false
        }
        
        // 过滤指定应用包名的事件（但不包括BACK_BUTTON和HOME_BUTTON）
        event.packageName?.let { packageName ->
            if (FILTERED_PACKAGES.contains(packageName)) {
                Log.d(TAG, "过滤事件: 应用包名 $packageName")
                return true
            }
        }
        
        return false
    }
    
    /**
     * 异步上传轨迹事件
     * @param context 上下文
     * @param event 轨迹事件
     * @param sessionId 会话ID
     * @param captureScreenshot 是否捕获截图（如果为false，则使用传入的screenshot）
     * @param screenshot 已截取的截图（可选，如果提供则直接使用，不再重新截图）
     */
    fun uploadEventAsync(
        context: Context?,
        event: TrajectoryEvent,
        sessionId: String?,
        captureScreenshot: Boolean,
        screenshot: Bitmap? = null,
        xml: String? = null
    ) {
        if (context == null) {
            Log.w(TAG, "Context为null，跳过上传")
            return
        }
        
        if (!TrajectoryCloudConfig.isEnabled()) {
            return
        }
        
        if (!TrajectoryCloudConfig.isValid()) {
            Log.w(TAG, "云侧配置无效，跳过上传")
            return
        }
        
        // 检查是否需要过滤该事件
        if (shouldFilterEvent(event)) {
            Log.d(TAG, "事件被过滤，不上传到云侧: ${event.type}, packageName=${event.packageName}")
            return
        }
        
        serviceScope.launch {
            try {
                // 采集前等待：仅当需要现场采集时生效；若使用动作前已采集的数据则不再等待
                val usePreCaptured = screenshot != null || xml != null
                if (!usePreCaptured) {
                    val delayMs = TrajectoryCloudConfig.getCaptureDelayMs()
                    if (delayMs > 0) {
                        kotlinx.coroutines.delay(delayMs.toLong())
                    }
                }
                
                // 使用传入的截图和 XML（动作前已采集），或按需捕获
                var finalScreenshot: Bitmap? = screenshot
                var capturedXml: String? = xml
                if (screenshot == null && captureScreenshot) {
                    val result = captureScreenshotAndXmlForEvent(context)
                    finalScreenshot = result.first
                    capturedXml = result.second
                } else if (screenshot != null && capturedXml == null && TrajectoryCloudConfig.isXmlEnabled()) {
                    // 有截图无 XML 时补充采集 XML（兼容旧调用）
                    capturedXml = captureXmlForEvent(context)
                }
                
                // 上传事件
                uploadEvent(context, event, sessionId, finalScreenshot, capturedXml)
                
                // 释放截图资源（只释放我们创建的截图，不释放外部传入的）
                if (finalScreenshot != null && screenshot == null) {
                    finalScreenshot.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "上传轨迹事件失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 同步捕获截图（在协程中使用）
     * 不包含隐藏/恢复悬浮窗的逻辑，由调用方负责处理悬浮窗状态
     * @param context 上下文
     * @return 截图Bitmap，失败返回null
     */
    suspend fun captureScreenshotSync(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            // 优先使用 ChatScreenshotService（如果已启动）
            val chatService = ChatScreenshotService.getInstance()
            val screenshot: Bitmap? = if (chatService != null) {
                chatService.captureScreenshot()
            } else {
                null
            }
            
            if (screenshot != null) {
                Log.d(TAG, "使用ChatScreenshotService捕获截图成功")
                screenshot
            } else {
                Log.w(TAG, "无法获取截图服务实例")
                if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                    notifyScreenshotCaptureFailure(context, "截图服务不可用，请重新授权截图权限")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "捕获截图失败: ${e.message}", e)
            if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                notifyScreenshotCaptureFailure(context, "截图失败：${e.message ?: "未知错误"}")
            }
            null
        }
    }
    
    /**
     * 动作前采集：在用户动作转发到目标 App 之前先采集截图和 XML
     * 供 TrajectoryOverlayView 在 forward 之前调用，确保截图/XML 反映“动作前”的页面状态
     * @return Pair(截图, XML)，若不需要采集则返回 (null, null)
     */
    suspend fun captureBeforeAction(context: Context): Pair<Bitmap?, String?> = withContext(Dispatchers.IO) {
        if (!TrajectoryCloudConfig.isEnabled() || !TrajectoryCloudConfig.isValid()) {
            return@withContext Pair(null, null)
        }
        val needScreenshot = TrajectoryCloudConfig.shouldUploadScreenshot()
        val needXml = TrajectoryCloudConfig.isXmlEnabled()
        if (!needScreenshot && !needXml) {
            return@withContext Pair(null, null)
        }
        captureScreenshotAndXmlForEvent(context)
    }
    
    /**
     * 捕获截图和 XML（在截图时同时记录页面结构）
     * 在截图前临时隐藏导航悬浮窗，先采集 XML 再截图，然后恢复显示
     * @return Pair(截图, XML字符串)，XML 未开启或失败时为 null
     */
    private suspend fun captureScreenshotAndXmlForEvent(context: Context): Pair<Bitmap?, String?> = withContext(Dispatchers.IO) {
        var wasShowing = false
        var screenshot: Bitmap? = null
        var xml: String? = null
        
        try {
            wasShowing = withContext(Dispatchers.Main) {
                TrajectoryNavigationOverlayManager.isShowing()
            }
            
            if (wasShowing) {
                Log.d(TAG, "检测到导航悬浮窗正在显示，先隐藏以便截图")
                withContext(Dispatchers.Main) {
                    TrajectoryNavigationOverlayManager.hide()
                }
                delay(100)
            }
            
            // 若开启 XML，先采集 XML（与截图同一时刻，无悬浮窗遮挡）
            if (TrajectoryCloudConfig.isXmlEnabled()) {
                xml = captureXmlForEvent(context)
                if (xml != null) {
                    Log.d(TAG, "页面 XML 采集成功，长度: ${xml.length}")
                }
            }
            
            val chatService = ChatScreenshotService.getInstance()
            screenshot = if (chatService != null) {
                chatService.captureScreenshot()
            } else {
                null
            }
            
            if (screenshot != null) {
                Log.d(TAG, "使用ChatScreenshotService捕获截图成功")
            } else {
                Log.w(TAG, "无法获取截图服务实例")
                if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                    notifyScreenshotCaptureFailure(context, "截图服务不可用，请重新授权截图权限")
                }
            }
            
            if (wasShowing) {
                Log.d(TAG, "截图完成，恢复导航悬浮窗显示")
                withContext(Dispatchers.Main) {
                    TrajectoryNavigationOverlayManager.show(context)
                }
            }
            
            Pair(screenshot, xml)
        } catch (e: Exception) {
            Log.e(TAG, "捕获截图或 XML 失败: ${e.message}", e)
            if (wasShowing) {
                try {
                    withContext(Dispatchers.Main) {
                        TrajectoryNavigationOverlayManager.show(context)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "恢复悬浮窗显示失败: ${e2.message}", e2)
                }
            }
            if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.shouldUploadScreenshot()) {
                notifyScreenshotCaptureFailure(context, "截图失败：${e.message ?: "未知错误"}")
            }
            Pair(null, null)
        }
    }

    private fun notifyScreenshotCaptureFailure(context: Context, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastScreenshotFailurePopupAt < SCREENSHOT_FAILURE_POPUP_COOLDOWN_MS) {
            return
        }
        lastScreenshotFailurePopupAt = now

        Handler(Looper.getMainLooper()).post {
            try {
                val existing = screenshotFailureDialog
                if (existing?.isShowing == true) {
                    return@post
                }

                val themedContext = ContextThemeWrapper(
                    context.applicationContext,
                    android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                )
                val dialog = android.app.AlertDialog.Builder(themedContext)
                    .setTitle("轨迹采集截图失败")
                    .setMessage("$detail\n\n请前往「轨迹采集」页面重新授权截图权限。")
                    .setCancelable(true)
                    .setPositiveButton("知道了", null)
                    .create()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    @Suppress("DEPRECATION")
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
                }

                dialog.setOnDismissListener {
                    screenshotFailureDialog = null
                }
                dialog.show()
                screenshotFailureDialog = dialog
            } catch (e: Exception) {
                Log.e(TAG, "显示截图失败弹窗失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 采集当前页面的 AccessibilityNodeInfo 为 XML 字符串
     */
    private fun captureXmlForEvent(context: Context): String? {
        return try {
            val service = MyAccessibilityService.getInstance()
            val root = service?.rootInActiveWindow ?: run {
                Log.w(TAG, "rootInActiveWindow 为 null，无法采集 XML")
                return null
            }
            try {
                val dm = context.resources.displayMetrics
                AccessibilityXmlDumper.dump(root, dm.widthPixels, dm.heightPixels)
            } finally {
                root.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "采集 XML 失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 上传轨迹事件到云侧
     * @param xml 页面 XML 字符串（可选），与截图同时采集
     */
    private suspend fun uploadEvent(
        context: Context,
        event: TrajectoryEvent,
        sessionId: String?,
        screenshot: Bitmap?,
        xml: String? = null
    ) {
        try {
            val serverUrl = TrajectoryCloudConfig.getServerUrl()
            
            // 确保NetworkService已初始化
            NetworkService.initialize(serverUrl, context)
            val apiService = NetworkService.getApiService()
            
            if (apiService == null) {
                Log.e(TAG, "ApiService未初始化")
                return
            }
            
            // 准备事件数据
            val eventJson = gson.toJson(event)
            val eventData = eventJson.toRequestBody("application/json".toMediaType())
            
            // 准备会话ID
            val sessionIdBody = (sessionId ?: "").toRequestBody("text/plain".toMediaType())
            
            // 准备截图（如果有）
            var screenshotPart: MultipartBody.Part? = null
            if (screenshot != null) {
                try {
                    val tempFile = File(context.cacheDir, "trajectory_screenshot_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(tempFile).use { out ->
                        screenshot.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    }
                    val requestFile = tempFile.asRequestBody("image/jpeg".toMediaType())
                    screenshotPart = MultipartBody.Part.createFormData("screenshot", tempFile.name, requestFile)
                    tempFile.deleteOnExit()
                } catch (e: Exception) {
                    Log.e(TAG, "准备截图文件失败: ${e.message}", e)
                }
            }
            
            // 准备 XML（如果有）
            var xmlPart: MultipartBody.Part? = null
            if (!xml.isNullOrBlank()) {
                try {
                    val xmlBody = xml.toRequestBody("application/xml; charset=utf-8".toMediaType())
                    xmlPart = MultipartBody.Part.createFormData("xml", "page_layout.xml", xmlBody)
                } catch (e: Exception) {
                    Log.e(TAG, "准备 XML 失败: ${e.message}", e)
                }
            }
            
            // 调用API上传
            val response = apiService.uploadTrajectoryEvent(
                sessionId = sessionIdBody,
                eventData = eventData,
                screenshot = screenshotPart,
                xml = xmlPart
            )
            
            if (response.isSuccessful) {
                Log.d(TAG, "轨迹事件上传成功: ${event.type} at ${event.timestamp}")
            } else {
                Log.w(TAG, "轨迹事件上传失败: ${response.code()} - ${response.message()}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "上传轨迹事件异常: ${e.message}", e)
        }
    }
    
    /**
     * 上传会话结束时的最后一张截图到云侧
     * 用于用户点击结束采集按钮时，先截一张图并上传
     * @param context 上下文
     * @param sessionId 当前会话ID
     * @param screenshot 已截取的截图
     * @return 是否上传成功
     */
    suspend fun uploadSessionEndScreenshot(
        context: Context,
        sessionId: String,
        screenshot: Bitmap
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!TrajectoryCloudConfig.isEnabled() || !TrajectoryCloudConfig.isValid()) {
                Log.d(TAG, "云侧上传未启用或配置无效，跳过会话结束截图上传")
                return@withContext true
            }
            val xml = if (TrajectoryCloudConfig.isXmlEnabled()) captureXmlForEvent(context) else null
            val event = TrajectoryEvent(
                type = TrajectoryEventType.SESSION_END,
                timestamp = System.currentTimeMillis()
            )
            uploadEvent(context, event, sessionId, screenshot, xml)
            Log.d(TAG, "会话结束截图上传成功: session_id=$sessionId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "上传会话结束截图失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 在云侧重命名轨迹会话目录
     * 将 trajectory_data/{oldSessionId} 重命名为 trajectory_data/{newSessionId}
     * @param context 上下文
     * @param oldSessionId 原会话ID（如 20260221_010435）
     * @param newSessionId 新会话ID（如 20260221_010435_打开设置）
     * @return 是否重命名成功
     */
    suspend fun renameSessionOnCloud(
        context: Context,
        oldSessionId: String,
        newSessionId: String
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!TrajectoryCloudConfig.isEnabled() || !TrajectoryCloudConfig.isValid()) {
                Log.d(TAG, "云侧上传未启用或配置无效，跳过云侧重命名")
                return@withContext true
            }
            val serverUrl = TrajectoryCloudConfig.getServerUrl()
            NetworkService.initialize(serverUrl, context)
            val apiService = NetworkService.getApiService()
            if (apiService == null) {
                Log.e(TAG, "ApiService未初始化，云侧重命名失败")
                return@withContext false
            }
            val response = apiService.renameTrajectorySession(
                oldSessionId = oldSessionId,
                newSessionId = newSessionId
            )
            if (response.isSuccessful) {
                Log.d(TAG, "云侧轨迹重命名成功: $oldSessionId -> $newSessionId")
                true
            } else {
                Log.w(TAG, "云侧轨迹重命名失败: ${response.code()} - ${response.message()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "云侧轨迹重命名异常: ${e.message}", e)
            false
        }
    }
    
    /**
     * 测试服务器连接
     */
    suspend fun testConnection(context: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val serverUrl = TrajectoryCloudConfig.getServerUrl()
            if (!TrajectoryCloudConfig.isValid()) {
                Log.w(TAG, "服务器地址无效: $serverUrl")
                return@withContext false
            }
            
            // 初始化NetworkService
            NetworkService.initialize(serverUrl, context)
            val apiService = NetworkService.getApiService()
            
            if (apiService == null) {
                Log.e(TAG, "ApiService未初始化")
                return@withContext false
            }
            
            // 创建一个测试事件
            val testEvent = TrajectoryEvent(
                type = TrajectoryEventType.CLICK,
                timestamp = System.currentTimeMillis(),
                x = 0,
                y = 0
            )
            
            val eventJson = gson.toJson(testEvent)
            val eventData = eventJson.toRequestBody("application/json".toMediaType())
            val sessionIdBody = "test".toRequestBody("text/plain".toMediaType())
            
            // 尝试上传测试事件（不包含截图）
            val response = apiService.uploadTrajectoryEvent(
                sessionId = sessionIdBody,
                eventData = eventData,
                screenshot = null
            )
            
            val success = response.isSuccessful
            if (success) {
                Log.d(TAG, "服务器连接测试成功")
            } else {
                Log.w(TAG, "服务器连接测试失败: ${response.code()} - ${response.message()}")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "测试服务器连接异常: ${e.message}", e)
            false
        }
    }
}

