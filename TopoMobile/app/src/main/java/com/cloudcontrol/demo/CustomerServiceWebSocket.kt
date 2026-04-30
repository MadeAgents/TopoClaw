package com.cloudcontrol.demo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 人工客服WebSocket客户端
 * 负责与服务器建立WebSocket连接，接收消息并显示通知
 */
class CustomerServiceWebSocket(private val context: Context) {
    companion object {
        private const val TAG = "CustomerServiceWebSocket"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "customer_service_notification_channel"
        private const val CHANNEL_NAME = "人工客服消息"
        private const val RECONNECT_DELAY = 3000L // 3秒重连延迟
        private const val HEARTBEAT_INTERVAL = 30000L // 30秒心跳间隔
        private const val HEARTBEAT_TIMEOUT = 45000L // 45秒心跳超时（比心跳间隔长15秒，给服务器响应时间）
    }
    
    private var websocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var baseUrl: String? = null
    private var imei: String? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var heartbeatTimeoutJob: Job? = null // 心跳超时检测Job
    private var lastPingTime: Long = 0 // 最后一次发送ping的时间
    private var lastPongTime: Long = 0 // 最后一次收到pong的时间
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingGuiStepResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingModelProfilesResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingCronResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingMobileExecResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    
    private var onMessageReceived: ((String, Int) -> Unit)? = null
    
    // 全局消息监听器（用于处理应用内弹窗和未读计数，不会被ChatFragment覆盖）
    private var globalMessageListener: ((String, String, Int) -> Unit)? = null
    
    /**
     * 设置消息接收回调
     */
    fun setOnMessageReceived(callback: (String, Int) -> Unit) {
        this.onMessageReceived = callback
    }
    
    /**
     * 清除消息接收回调
     */
    fun clearOnMessageReceived() {
        this.onMessageReceived = null
    }
    
    /**
     * 获取消息接收回调（供外部调用）
     */
    fun getOnMessageReceived(): ((String, Int) -> Unit)? {
        return onMessageReceived
    }
    
    /**
     * 设置全局消息监听器（用于应用内弹窗，不会被ChatFragment覆盖）
     * @param listener 回调参数：(消息内容, 发送者名称, 消息数量)
     */
    fun setGlobalMessageListener(listener: (String, String, Int) -> Unit) {
        Log.d(TAG, "设置globalMessageListener，旧监听器: ${this.globalMessageListener != null}")
        this.globalMessageListener = listener
        Log.d(TAG, "globalMessageListener已设置，新监听器: ${this.globalMessageListener != null}")
    }
    
    /**
     * 清除全局消息监听器
     */
    fun clearGlobalMessageListener() {
        this.globalMessageListener = null
    }
    
    /**
     * 连接WebSocket
     */
    fun connect(imei: String, baseUrl: String = ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL) {
        this.imei = imei
        this.baseUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        
        // 如果已经连接，先断开旧连接
        if (isConnected) {
            Log.d(TAG, "检测到已有连接，先断开旧连接")
            disconnect()
        }
        
        // 创建OkHttp客户端
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSocket需要无限制读取
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        connectWebSocket()
    }
    
    /**
     * 强制重连（用于检测到连接问题时）
     */
    fun forceReconnect() {
        Log.d(TAG, "强制重连WebSocket")
        isConnected = false
        websocket = null
        stopHeartbeat()
        lastPingTime = 0
        lastPongTime = 0
        scheduleReconnect()
    }
    
    private fun connectWebSocket() {
        val imei = this.imei ?: return
        var baseUrl = this.baseUrl ?: return
        
        // 确保 baseUrl 以斜杠结尾
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        
        val wsUrl = "${baseUrl}ws/customer-service/$imei"
        Log.d(TAG, "连接WebSocket: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        websocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接已建立，响应码: ${response.code}, 响应消息: ${response.message}")
                isConnected = true
                reconnectJob?.cancel()
                lastPingTime = 0
                lastPongTime = System.currentTimeMillis() // 连接建立时初始化pong时间
                // 启动心跳机制
                startHeartbeat()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "========== 收到WebSocket消息 ==========")
                Log.d(TAG, "消息长度: ${text.length}")
                Log.d(TAG, "消息内容（前200字符）: ${text.take(200)}")
                Log.d(TAG, "完整消息: $text")
                Log.d(TAG, "WebSocket状态: isConnected=$isConnected")
                Log.d(TAG, "当前onMessageReceived回调: ${onMessageReceived != null}")
                try {
                    handleMessage(text)
                    Log.d(TAG, "消息处理完成")
                } catch (e: Exception) {
                    Log.e(TAG, "处理消息时发生异常: ${e.message}", e)
                    e.printStackTrace()
                }
                Log.d(TAG, "========================================")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket连接已关闭: $code - $reason")
                isConnected = false
                stopHeartbeat()
                scheduleReconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败: ${t.message}", t)
                Log.e(TAG, "响应码: ${response?.code}, 响应消息: ${response?.message}")
                t.printStackTrace()
                isConnected = false
                stopHeartbeat()
                scheduleReconnect()
            }
        })
    }
    
    private fun handleMessage(text: String) {
        try {
            Log.d(TAG, "开始解析消息JSON...")
            val json = JSONObject(text)
            val type = json.optString("type", "unknown")
            Log.d(TAG, "消息类型: $type")
            Log.d(TAG, "消息JSON对象: $json")
            
            when (type) {
                "offline_messages" -> {
                    // 收到离线消息
                    Log.d(TAG, "处理离线消息类型")
                    val messagesArray = json.getJSONArray("messages")
                    val count = json.getInt("count")
                    Log.d(TAG, "收到 $count 条离线消息")
                    
                    // 将JSONArray转换为List<JSONObject>
                    val messages = mutableListOf<JSONObject>()
                    for (i in 0 until messagesArray.length()) {
                        messages.add(messagesArray.getJSONObject(i))
                    }
                    
                    // 按子消息类型分别展示系统通知（好友/群组/端云/人工客服），避免一律显示「人工客服」
                    Log.d(TAG, "准备显示离线消息通知")
                    showOfflineNotifications(messages, count)
                    
                    // 仅对离线包内「人工客服」候选条目触发全局监听（未读、待处理队列、会话预览）：
                    // - type=service_message
                    // - type 为空且无 groupId/senderImei（历史兼容的纯客服条）
                    // 避免把群聊/好友等误记到人工客服。
                    if (messages.isNotEmpty()) {
                        val serviceMessages = messages.filter {
                            val t = it.optString("type", "")
                            when {
                                t == "service_message" -> true
                                t.isEmpty() && it.optString("groupId", "").isEmpty() &&
                                    it.optString("senderImei", "").isEmpty() -> true
                                else -> false
                            }
                        }
                        if (serviceMessages.isNotEmpty()) {
                            val lastService = serviceMessages.last()
                            val content = lastService.optString("content", "您有 ${serviceMessages.size} 条未读消息")
                            val sender = lastService.optString("sender", "人工客服")
                            Log.d(TAG, "准备触发全局消息监听器（人工客服离线候选条数=${serviceMessages.size}），监听器是否为空: ${globalMessageListener == null}")
                            globalMessageListener?.invoke(content, sender, serviceMessages.size)
                            Log.d(TAG, "全局消息监听器已触发（离线人工客服消息）")
                        } else {
                            Log.d(TAG, "离线包内无人工客服候选子消息，跳过 globalMessageListener")
                        }
                    }
                    
                    // 触发回调（ChatFragment使用）
                    Log.d(TAG, "准备触发离线消息回调，回调是否为空: ${onMessageReceived == null}")
                    onMessageReceived?.invoke(text, count)
                    Log.d(TAG, "离线消息回调已触发")
                }
                "service_message" -> {
                    // 收到实时消息
                    Log.d(TAG, "处理实时消息类型")
                    val content = json.optString("content", "")
                    val sender = json.optString("sender", "人工客服")
                    Log.d(TAG, "收到实时消息内容: $content")
                    Log.d(TAG, "消息完整JSON: $json")
                    
                    // 显示系统通知（后台时使用）
                    Log.d(TAG, "准备显示实时消息通知")
                    showNotification(listOf(json), 1)
                    
                    // 触发全局消息监听器（用于应用内弹窗）
                    Log.d(TAG, "准备触发全局消息监听器，监听器是否为空: ${globalMessageListener == null}")
                    if (globalMessageListener != null) {
                        try {
                            Log.d(TAG, "调用globalMessageListener.invoke()，参数: content长度=${content.length}, sender=$sender")
                            // 直接调用，不使用!!操作符，避免空指针异常
                            val listener = globalMessageListener
                            if (listener != null) {
                                listener.invoke(content, sender, 1)
                                Log.d(TAG, "globalMessageListener.invoke()调用完成")
                            } else {
                                Log.w(TAG, "globalMessageListener在调用时变为null")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "globalMessageListener.invoke()调用失败: ${e.message}", e)
                            e.printStackTrace()
                        } catch (e: Throwable) {
                            Log.e(TAG, "globalMessageListener.invoke()调用严重错误: ${e.message}", e)
                            e.printStackTrace()
                        }
                    } else {
                        Log.w(TAG, "globalMessageListener为null，无法触发")
                    }
                    Log.d(TAG, "全局消息监听器处理完成")
                    
                    // 触发回调（ChatFragment使用）
                    Log.d(TAG, "准备触发实时消息回调，回调是否为空: ${onMessageReceived == null}")
                    onMessageReceived?.invoke(text, 1)
                    Log.d(TAG, "实时消息回调已触发")
                }
                "friend_message" -> {
                    // 收到好友消息
                    Log.d(TAG, "========== 处理好友消息类型 ==========")
                    val content = json.optString("content", "")
                    val senderImei = json.optString("senderImei", "")
                    val sender = json.optString("sender", "好友")
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    Log.d(TAG, "收到好友消息: senderImei=$senderImei, content=$content")
                    Log.d(TAG, "消息完整JSON: $json")
                    Log.d(TAG, "onMessageReceived回调是否为空: ${onMessageReceived == null}")
                    
                    // 显示系统通知（后台时使用）
                    showFriendNotification(senderImei, content, sender)
                    
                    // 注意：好友消息不应该触发globalMessageListener
                    // globalMessageListener只用于人工客服消息（service_message）
                    // 好友消息由MainActivity的onMessageReceived回调处理
                    Log.d(TAG, "好友消息不触发globalMessageListener（由onMessageReceived处理）")
                    
                    // 触发回调（ChatFragment使用）
                    if (onMessageReceived != null) {
                        Log.d(TAG, "准备触发onMessageReceived回调")
                        try {
                            onMessageReceived?.invoke(text, 1)
                            Log.d(TAG, "onMessageReceived回调已触发")
                        } catch (e: Exception) {
                            Log.e(TAG, "触发onMessageReceived回调时发生异常: ${e.message}", e)
                            e.printStackTrace()
                        }
                    } else {
                        Log.w(TAG, "onMessageReceived回调为null，消息可能丢失！")
                    }
                    Log.d(TAG, "=========================================")
                }
                "cross_device_message" -> {
                    Log.d(TAG, "收到端云互发消息")
                    val content = json.optString("content", "")
                    val sender = json.optString("sender", "我的电脑")
                    val messageType = json.optString("message_type", "text")
                    val fileBase64 = json.optString("fileBase64", null).takeIf { it?.isNotEmpty() == true }
                        ?: json.optString("file_base64", null).takeIf { it?.isNotEmpty() == true }
                    val fileName = json.optString("fileName", null).takeIf { it?.isNotEmpty() == true }
                        ?: json.optString("file_name", null).takeIf { it?.isNotEmpty() == true }
                    onMessageReceived?.invoke(text, 1)
                }
                "pc_execute_command" -> {
                    Log.d(TAG, "收到PC端执行指令")
                    onMessageReceived?.invoke(text, 1)
                }
                "mobile_execute_pc_result" -> {
                    Log.d(TAG, "收到mobile_execute_pc_result，PC 执行完成")
                    val msgId = json.optString("message_id", "").trim()
                    val waiter = if (msgId.isNotBlank()) pendingMobileExecResponses.remove(msgId) else null
                    if (waiter != null && !waiter.isCompleted) {
                        Log.d(TAG, "mobile_execute_pc_result 匹配到同步等待者, msgId=$msgId")
                        waiter.complete(json)
                    } else {
                        Log.d(TAG, "mobile_execute_pc_result 转发给 MainActivity")
                        onMessageReceived?.invoke(text, 1)
                    }
                }
                "mobile_execute_pc_thinking" -> {
                    Log.d(TAG, "收到mobile_execute_pc_thinking，转发给 MainActivity")
                    onMessageReceived?.invoke(text, 1)
                }
                "mobile_execute_pc_error" -> {
                    Log.d(TAG, "收到mobile_execute_pc_error")
                    val msgId = json.optString("message_id", "").trim()
                    val waiter = if (msgId.isNotBlank()) pendingMobileExecResponses.remove(msgId) else null
                    if (waiter != null && !waiter.isCompleted) {
                        Log.d(TAG, "mobile_execute_pc_error 匹配到同步等待者, msgId=$msgId")
                        waiter.complete(json)
                    } else {
                        onMessageReceived?.invoke(text, 1)
                    }
                }
                "assistant_stop_task" -> {
                    Log.d(TAG, "收到 assistant_stop_task（对端请求停止任务），转发给 MainActivity")
                    onMessageReceived?.invoke(text, 1)
                }
                "gui_execute_request" -> {
                    // 收到来自中转的 GUI 执行请求（兜底通道）
                    Log.d(TAG, "收到gui_execute_request（customer ws fallback），转发给 MainActivity")
                    onMessageReceived?.invoke(text, 1)
                }
                "gui_step_response" -> {
                    val stepRequestId = json.optString("step_request_id", "").trim()
                    if (stepRequestId.isBlank()) {
                        Log.w(TAG, "收到gui_step_response但缺少step_request_id")
                        return
                    }
                    val waiter = pendingGuiStepResponses.remove(stepRequestId)
                    if (waiter != null && !waiter.isCompleted) {
                        waiter.complete(json)
                    } else {
                        Log.w(TAG, "gui_step_response未找到等待者: step_request_id=$stepRequestId")
                    }
                }
                "builtin_model_profiles_result" -> {
                    val requestId = json.optString("request_id", "").trim()
                    if (requestId.isBlank()) {
                        Log.w(TAG, "收到builtin_model_profiles_result但缺少request_id")
                        return
                    }
                    val waiter = pendingModelProfilesResponses.remove(requestId)
                    if (waiter != null && !waiter.isCompleted) {
                        waiter.complete(json)
                    } else {
                        Log.w(TAG, "builtin_model_profiles_result未找到等待者: request_id=$requestId")
                    }
                }
                "cron_jobs_listed", "cron_job_created", "cron_job_deleted" -> {
                    val requestId = json.optString("request_id", "").trim()
                    if (requestId.isBlank()) {
                        Log.w(TAG, "收到${type}但缺少request_id")
                        return
                    }
                    val waiter = pendingCronResponses.remove(requestId)
                    if (waiter != null && !waiter.isCompleted) {
                        waiter.complete(json)
                    } else {
                        Log.w(TAG, "${type}未找到等待者: request_id=$requestId")
                    }
                }
                "assistant_user_message" -> {
                    // PC 端用户消息同步（跨设备实时同步），由 onMessageReceived 转发给 MainActivity/ChatFragment 处理
                    Log.d(TAG, "收到assistant_user_message，PC 端用户消息同步")
                    onMessageReceived?.invoke(text, 1)
                }
                "assistant_sync_message" -> {
                    // PC 端小助手/系统消息同步（跨设备实时同步），由 onMessageReceived 转发给 MainActivity/ChatFragment 处理
                    Log.d(TAG, "收到assistant_sync_message，PC 端小助手/系统消息同步")
                    onMessageReceived?.invoke(text, 1)
                }
                "assistant_thinking_sync" -> {
                    Log.d(TAG, "收到assistant_thinking_sync，转发给 MainActivity")
                    onMessageReceived?.invoke(text, 1)
                }
                "done" -> {
                    // cron 镜像到手机时，服务端发送的是 done/delta 协议。
                    // 为避免影响现有聊天协议，仅接管 source=cron 的 done，并转换为已支持的 assistant_sync_message。
                    val source = json.optString("source", "").trim()
                    if (source == "cron") {
                        val normalized = normalizeCronDoneToAssistantSync(json)
                        Log.d(
                            TAG,
                            "收到cron done，转换为assistant_sync_message: conv=${normalized.optString("conversation_id")}, " +
                                "session=${normalized.optString("session_id")}, req=${normalized.optString("request_id")}"
                        )
                        onMessageReceived?.invoke(normalized.toString(), 1)
                    } else {
                        Log.w(TAG, "收到done但非cron source，保持忽略: source=$source")
                    }
                }
                "friend_sync_message" -> {
                    // PC 发好友消息后服务端镜像到手机（同 IMEI），与 PC 端 friend_sync_message 一致
                    Log.d(TAG, "收到 friend_sync_message，多设备好友会话同步")
                    onMessageReceived?.invoke(text, 1)
                }
                "friend_request" -> {
                    // 收到好友请求
                    Log.d(TAG, "处理好友请求类型")
                    val senderImei = json.optString("senderImei", "")
                    val sender = json.optString("sender", "用户")
                    Log.d(TAG, "收到好友请求: senderImei=$senderImei")
                    
                    // 保存好友申请到本地
                    if (senderImei.isNotEmpty()) {
                        val request = FriendRequestManager.FriendRequest(
                            senderImei = senderImei,
                            senderName = sender,
                            timestamp = System.currentTimeMillis(),
                            status = "pending"
                        )
                        FriendRequestManager.addRequest(context, request)
                        Log.d(TAG, "好友申请已保存: $senderImei")
                    }
                    
                    // 触发全局消息监听器（用于应用内弹窗）
                    globalMessageListener?.invoke("收到好友请求", sender, 1)
                    
                    // 触发回调（ChatFragment使用）
                    onMessageReceived?.invoke(text, 1)
                }
                "group_changed" -> {
                    // 收到群组变更通知
                    Log.d(TAG, "========== 处理群组变更通知 ==========")
                    val groupId = json.optString("groupId", "")
                    val groupName = json.optString("groupName", "")
                    val action = json.optString("action", "")
                    val message = json.optString("message", "")
                    Log.d(TAG, "群组变更: groupId=$groupId, groupName=$groupName, action=$action")
                    Log.d(TAG, "变更消息: $message")
                    
                    // 立即同步群组列表
                    try {
                        // 使用协程在后台同步群组列表
                        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                            try {
                                Log.d(TAG, "开始强制同步群组列表...")
                                GroupManager.syncGroupsFromServer(context)
                                Log.d(TAG, "群组列表同步完成")
                                
                                // 切换到主线程刷新UI
                                withContext(Dispatchers.Main) {
                                    // 通知FriendFragment刷新群组列表
                                    try {
                                        val activity = context as? FragmentActivity
                                        val fragmentManager = activity?.supportFragmentManager
                                        fragmentManager?.fragments?.forEach { fragment ->
                                            if (fragment is FriendFragment) {
                                                fragment.refreshGroupsList()
                                                Log.d(TAG, "已通知FriendFragment刷新群组列表")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "通知FriendFragment刷新失败: ${e.message}")
                                    }
                                    
                                    // 通知ConversationListFragment刷新对话列表
                                    try {
                                        val activity = context as? FragmentActivity
                                        val fragmentManager = activity?.supportFragmentManager
                                        fragmentManager?.fragments?.forEach { fragment ->
                                            if (fragment is ConversationListFragment) {
                                                fragment.loadConversations()
                                                Log.d(TAG, "已通知ConversationListFragment刷新对话列表")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "通知ConversationListFragment刷新失败: ${e.message}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "同步群组列表失败: ${e.message}", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理群组变更通知失败: ${e.message}", e)
                    }
                    
                    // 显示系统通知
                    if (message.isNotEmpty()) {
                        showNotification(listOf(json), 1)
                    }
                    
                    // 触发全局消息监听器（用于应用内弹窗）
                    if (message.isNotEmpty()) {
                        globalMessageListener?.invoke(message, "系统", 1)
                    }
                    
                    Log.d(TAG, "=========================================")
                }
                "group_message" -> {
                    // 收到群组消息
                    Log.d(TAG, "========== 处理群组消息类型 ==========")
                    val groupId = json.optString("groupId", "")
                    val content = json.optString("content", "")
                    val senderImei = json.optString("senderImei", "")
                    val sender = json.optString("sender", "群成员")
                    val isAssistantReply = json.optBoolean("is_assistant_reply", false)
                    Log.d(TAG, "收到群组消息: groupId=$groupId, senderImei=$senderImei, sender=$sender, isAssistantReply=$isAssistantReply")
                    Log.d(TAG, "消息内容（前100字符）: ${content.take(100)}")
                    Log.d(TAG, "完整消息JSON: $json")
                    
                    // 显示系统通知（后台时使用）
                    showGroupNotification(groupId, sender, content)
                    
                    // 触发回调（ChatFragment使用）
                    Log.d(TAG, "检查onMessageReceived回调: ${onMessageReceived != null}")
                    Log.d(TAG, "回调函数引用: $onMessageReceived")
                    if (onMessageReceived != null) {
                        Log.d(TAG, "准备触发onMessageReceived回调（群组消息），消息长度: ${text.length}")
                        try {
                            val callback = onMessageReceived
                            Log.d(TAG, "获取回调引用成功，准备调用invoke")
                            callback?.invoke(text, 1)
                            Log.d(TAG, "onMessageReceived回调已触发（群组消息），调用完成")
                        } catch (e: Exception) {
                            Log.e(TAG, "触发onMessageReceived回调时发生异常: ${e.message}", e)
                            e.printStackTrace()
                            Log.e(TAG, "异常堆栈: ${e.stackTraceToString()}")
                        } catch (e: Throwable) {
                            Log.e(TAG, "触发onMessageReceived回调时发生严重错误: ${e.message}", e)
                            e.printStackTrace()
                        }
                    } else {
                        Log.e(TAG, "onMessageReceived回调为null，群组消息可能丢失！")
                        Log.e(TAG, "这是严重问题，消息无法传递给ChatFragment！")
                        Log.e(TAG, "请检查MainActivity或ChatFragment是否设置了回调")
                    }
                    Log.d(TAG, "=========================================")
                }
                "remote_assistant_command" -> {
                    // 收到远程执行指令
                    Log.d(TAG, "========== 处理远程执行指令类型 ==========")
                    val groupId = json.optString("groupId", "")
                    val targetImei = json.optString("targetImei", "")
                    val command = json.optString("command", "")
                    val senderImei = json.optString("senderImei", "")
                    Log.d(TAG, "收到远程执行指令: groupId=$groupId, targetImei=$targetImei, command=$command, senderImei=$senderImei")
                    
                    // 触发回调（ChatFragment使用）
                    if (onMessageReceived != null) {
                        try {
                            onMessageReceived?.invoke(text, 1)
                            Log.d(TAG, "远程执行指令回调已触发")
                        } catch (e: Exception) {
                            Log.e(TAG, "触发远程执行指令回调时发生异常: ${e.message}", e)
                            e.printStackTrace()
                        }
                    } else {
                        Log.w(TAG, "onMessageReceived回调为null，远程执行指令可能丢失")
                    }
                    Log.d(TAG, "=========================================")
                }
                "mobile_tool_invoke", "mobile_tool_cancel", "mobile_tool_manifest" -> {
                    // mobile_tool/v1：转发给 ChatFragment 处理
                    Log.d(TAG, "收到${type}，转发给 onMessageReceived")
                    onMessageReceived?.invoke(text, 1)
                }
                "error" -> {
                    // 收到错误消息
                    Log.d(TAG, "处理错误消息类型")
                    val content = json.optString("content", "发生错误")
                    Log.d(TAG, "收到错误消息: $content")

                    val requestId = json.optString("request_id", "").trim()
                    if (requestId.isNotBlank()) {
                        val waiter = pendingCronResponses.remove(requestId)
                        if (waiter != null && !waiter.isCompleted) {
                            waiter.complete(json)
                            return
                        }
                    }
                    
                    // 触发回调（ChatFragment使用），显示错误消息
                    onMessageReceived?.invoke(text, 1)
                }
                "pong" -> {
                    // 收到心跳响应
                    Log.d(TAG, "收到心跳响应（pong）")
                    lastPongTime = System.currentTimeMillis()
                    // 取消超时检测，因为已经收到响应
                    heartbeatTimeoutJob?.cancel()
                    heartbeatTimeoutJob = null
                    // 不需要特殊处理，心跳机制会继续运行
                }
                else -> {
                    Log.w(TAG, "收到未知消息类型: $type")
                    Log.w(TAG, "完整消息内容: $text")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理消息失败: ${e.message}", e)
            Log.e(TAG, "原始消息内容: $text")
            e.printStackTrace()
        }
    }
    
    /**
     * 离线补发包 [offline_messages] 的通知：按每条子消息的 [type] 走好友/群组/端云/客服，与实时单条行为一致。
     */
    private fun showOfflineNotifications(messages: List<JSONObject>, totalCount: Int) {
        if (messages.isEmpty()) return
        val csMessages = mutableListOf<JSONObject>()
        for (msg in messages) {
            val t = msg.optString("type", "")
            when {
                t == "friend_message" -> {
                    val imei = msg.optString("senderImei", "")
                    val content = msg.optString("content", "您有新的消息")
                    val senderHint = msg.optString("sender", "")
                    showFriendNotification(imei, content, senderHint.takeIf { it.isNotBlank() })
                }
                t == "group_message" -> {
                    val groupId = msg.optString("groupId", "")
                    val content = msg.optString("content", "您有新的消息")
                    val sender = msg.optString("sender", "群成员")
                    if (groupId.isNotEmpty()) {
                        showGroupNotification(groupId, sender, content)
                    }
                }
                t == "cross_device_message" -> {
                    val content = msg.optString("content", "您有新的消息")
                    showCrossDeviceOfflineNotification(content)
                }
                t == "service_message" -> csMessages.add(msg)
                t.isEmpty() &&
                    msg.optString("groupId", "").isEmpty() &&
                    msg.optString("senderImei", "").isEmpty() -> {
                    // 与 globalMessageListener 一致：无 type 且无群/好友字段时视为人工客服补发
                    csMessages.add(msg)
                }
                else -> {
                    Log.d(TAG, "离线通知未单独展示 type=$t（非好友/群/端云/客服候选）")
                }
            }
        }
        if (csMessages.isNotEmpty()) {
            showNotification(csMessages, csMessages.size)
        }
    }
    
    /** 离线包中的端云消息：点击进入「我的电脑」会话 */
    private fun showCrossDeviceOfflineNotification(content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(notificationManager)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", ConversationListFragment.CONVERSATION_ID_ME)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, ConversationListFragment.CONVERSATION_ID_ME.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("我的电脑")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                .build()
            notificationManager.notify(NOTIFICATION_ID + 31, notification)
        } catch (e: Exception) {
            Log.e(TAG, "端云离线通知失败: ${e.message}", e)
        }
    }

    private fun normalizeCronDoneToAssistantSync(json: JSONObject): JSONObject {
        val baseConversationId = "custom_topoclaw"
        val sessions = SessionStorage.loadSessions(context, baseConversationId)
        val threadId = json.optString("thread_id", "").trim()
        val matchedSessionId = sessions.firstOrNull { it.id == threadId }?.id
        val latestSessionId = sessions.maxByOrNull { it.createdAt }?.id
        val targetSessionId = matchedSessionId ?: latestSessionId
        val conversationId =
            if (!targetSessionId.isNullOrBlank()) "${baseConversationId}__${targetSessionId}" else baseConversationId

        val content = json.optString("response", "").ifBlank { json.optString("content", "") }
        val rawSender = json.optString("agent_id", "").ifBlank { "TopoClaw" }
        val sender = ChatConstants.normalizeAssistantSenderForConversation(rawSender, conversationId)
        val requestId = json.optString("request_id", "").trim()
        val messageId = if (requestId.isNotBlank()) requestId else "cron_done_${System.currentTimeMillis()}"
        val timestamp = json.opt("timestamp")

        return JSONObject().apply {
            put("type", "assistant_sync_message")
            put("sender", sender)
            put("content", content)
            put("message_id", messageId)
            put("conversation_id", conversationId)
            if (!targetSessionId.isNullOrBlank()) put("session_id", targetSessionId)
            if (threadId.isNotBlank()) put("thread_id", threadId)
            if (requestId.isNotBlank()) put("request_id", requestId)
            if (json.has("source")) put("source", json.opt("source"))
            if (timestamp != null) put("timestamp", timestamp) else put("timestamp", System.currentTimeMillis())
        }
    }
    
    private fun showNotification(messages: List<JSONObject>, count: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 创建通知渠道
            createNotificationChannel(notificationManager)
            
            // 构建Intent（点击通知打开人工客服聊天界面）
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // 获取最后一条消息内容
            val lastMessage = messages.lastOrNull()
            val contentText = if (lastMessage != null) {
                try {
                    lastMessage.getString("content")
                } catch (e: Exception) {
                    "您有新的消息"
                }
            } else {
                "您有新的消息"
            }
            
            val title = if (count > 1) "人工客服 (${count}条新消息)" else "人工客服"
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "通知已显示: $title - $contentText")
        } catch (e: Exception) {
            Log.e(TAG, "显示通知失败: ${e.message}", e)
        }
    }
    
    /**
     * @param senderHint 服务端下发的展示名（如 JSON [sender]），在本地备注名不存在时使用
     */
    private fun showFriendNotification(senderImei: String, content: String, senderHint: String? = null) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(notificationManager)
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", "friend_$senderImei")
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val nickname = try {
                FriendManager.getFriend(context, senderImei)?.nickname?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
            val title = nickname
                ?: senderHint?.takeIf { it.isNotBlank() }
                ?: if (senderImei.length >= 8) "好友 ${senderImei.take(8)}..." else "好友消息"
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID + senderImei.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "显示好友通知失败: ${e.message}", e)
        }
    }
    
    private fun showGroupNotification(groupId: String, sender: String, content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(notificationManager)
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversation_id", "group_$groupId")
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("群组消息 - $sender")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID + groupId.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "显示群组通知失败: ${e.message}", e)
        }
    }
    
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "人工客服消息通知"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (!isConnected && imei != null) {
                Log.d(TAG, "尝试重新连接WebSocket...")
                // 清理旧的WebSocket实例
                websocket = null
                connectWebSocket()
            }
        }
    }
    
    /**
     * 启动心跳机制，定期检测连接状态
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        lastPongTime = System.currentTimeMillis() // 初始化时设置为当前时间
        heartbeatJob = scope.launch {
            while (isConnected) {
                delay(HEARTBEAT_INTERVAL)
                if (isConnected && websocket != null) {
                    try {
                        // 发送心跳消息（ping）
                        val pingMessage = JSONObject().apply {
                            put("type", "ping")
                            put("timestamp", System.currentTimeMillis())
                        }
                        lastPingTime = System.currentTimeMillis()
                        val success = websocket?.send(pingMessage.toString()) ?: false
                        if (!success) {
                            Log.w(TAG, "心跳发送失败，连接可能已断开")
                            isConnected = false
                            websocket = null
                            scheduleReconnect()
                            break
                        } else {
                            Log.d(TAG, "心跳发送成功，等待pong响应...")
                            // 启动超时检测
                            startHeartbeatTimeoutCheck()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "发送心跳失败: ${e.message}", e)
                        isConnected = false
                        websocket = null
                        scheduleReconnect()
                        break
                    }
                } else {
                    Log.d(TAG, "连接已断开，停止心跳")
                    break
                }
            }
        }
    }
    
    /**
     * 启动心跳超时检测
     * 如果在一定时间内没有收到pong响应，认为连接已断开，触发重连
     */
    private fun startHeartbeatTimeoutCheck() {
        heartbeatTimeoutJob?.cancel()
        heartbeatTimeoutJob = scope.launch {
            delay(HEARTBEAT_TIMEOUT)
            // 检查是否在超时时间内收到了pong响应
            val timeSincePing = System.currentTimeMillis() - lastPingTime
            val timeSinceLastPong = System.currentTimeMillis() - lastPongTime
            
            // 如果距离最后一次ping超过超时时间，且距离最后一次pong也超过超时时间，认为连接已断开
            if (timeSincePing >= HEARTBEAT_TIMEOUT && timeSinceLastPong >= HEARTBEAT_TIMEOUT) {
                Log.w(TAG, "心跳超时：${timeSincePing}ms未收到pong响应，连接可能已断开，触发重连")
                Log.w(TAG, "距离最后一次ping: ${timeSincePing}ms, 距离最后一次pong: ${timeSinceLastPong}ms")
                isConnected = false
                websocket = null
                stopHeartbeat()
                scheduleReconnect()
            } else {
                Log.d(TAG, "心跳超时检测：已收到pong响应，连接正常")
            }
        }
    }
    
    /**
     * 停止心跳机制
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatTimeoutJob?.cancel()
        heartbeatTimeoutJob = null
    }
    
    /**
     * 检查WebSocket是否已连接
     */
    fun isConnected(): Boolean {
        return isConnected
    }
    
    /**
     * 发送消息给服务器
     */
    fun sendMessage(content: String) {
        sendMessage(content, "user_message")
    }
    
    /**
     * 发送好友消息给服务器
     */
    fun sendFriendMessage(targetImei: String, content: String, imageBase64: String? = null, skillId: String? = null) {
        Log.d(TAG, "sendFriendMessage调用: targetImei=$targetImei, content=$content, hasImage=${imageBase64 != null}, skillId=$skillId")
        
        if (!isConnected) {
            Log.w(TAG, "WebSocket未连接，尝试重新连接...")
            scheduleReconnect()
            return
        }
        
        if (websocket == null) {
            Log.e(TAG, "WebSocket实例为null，尝试重新连接...")
            isConnected = false
            scheduleReconnect()
            return
        }
        
        try {
            val message = JSONObject().apply {
                put("type", "friend_message")
                put("targetImei", targetImei)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
                if (imageBase64 != null) {
                    put("message_type", "image")
                    put("imageBase64", imageBase64)
                } else {
                    put("message_type", "text")
                }
                // 如果是技能消息，添加skillId字段
                if (skillId != null) {
                    put("skillId", skillId)
                }
            }
            val messageStr = message.toString()
            val messageSize = messageStr.length
            Log.d(TAG, "准备发送好友消息: 消息大小=${messageSize}字节, hasImage=${imageBase64 != null}")
            if (imageBase64 != null) {
                Log.d(TAG, "imageBase64长度: ${imageBase64.length}字节")
                // 不打印包含base64的完整消息，避免日志过大
                val messagePreview = JSONObject().apply {
                    put("type", "friend_message")
                    put("targetImei", targetImei)
                    put("content", content)
                    put("message_type", "image")
                    put("imageBase64", "[已省略，长度: ${imageBase64.length}字节]")
                }
                Log.d(TAG, "消息预览: ${messagePreview.toString()}")
            } else {
                Log.d(TAG, "消息内容: $messageStr")
            }
            
            val success = websocket?.send(messageStr) ?: false
            if (success) {
                Log.d(TAG, "好友消息发送成功: $targetImei - $content, hasImage=${imageBase64 != null}, skillId=$skillId, 消息大小=${messageSize}字节")
            } else {
                Log.e(TAG, "好友消息发送失败：WebSocket连接已断开，触发强制重连")
                forceReconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送好友消息时发生异常: ${e.message}", e)
            e.printStackTrace()
            forceReconnect()
        }
    }
    
    /**
     * 发送执行结果到 PC（手机端执行小助手任务完成后调用）
     * @param conversationId 会话 ID，自定义小助手时传入以便 PC 正确路由；为 null 时表示TopoClaw
     */
    fun sendExecuteResult(content: String, messageId: String, execUuid: String, conversationId: String? = null) {
        Log.d(TAG, "sendExecuteResult: content_len=${content.length}, messageId=$messageId, execUuid=$execUuid, conversationId=$conversationId")
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送执行结果")
            return
        }
        try {
            val message = org.json.JSONObject().apply {
                put("type", "execute_result")
                put("content", content)
                put("message_id", messageId)
                put("uuid", execUuid)
                put("timestamp", System.currentTimeMillis())
                if (conversationId != null) put("conversation_id", conversationId)
            }
            val success = websocket?.send(message.toString()) ?: false
            if (success) Log.d(TAG, "执行结果已发送到 PC")
            else forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "发送执行结果异常: ${e.message}", e)
            forceReconnect()
        }
    }
    
    /**
     * 发送小助手回复到后端（用于端云同步，无论任务来自哪里都会上报）
     * 与 execute_result 相同格式，后端会写入存储并推送给 PC
     */
    fun sendAssistantMessage(content: String) {
        val msgId = java.util.UUID.randomUUID().toString()
        val execUuid = java.util.UUID.randomUUID().toString()
        sendExecuteResult(content, msgId, execUuid)
        Log.d(TAG, "sendAssistantMessage: 端云同步已上报小助手回复, msgId=$msgId")
    }
    
    /**
     * 发送小助手/系统消息到后端（用于端云同步，PC 需展示 sender=小助手 和 sender=系统）
     * @param conversationId 会话 id，多 session 时格式为 assistantId__sessionId
     */
    fun sendAssistantSyncMessage(sender: String, content: String, conversationId: String = "assistant") {
        Log.d(TAG, "sendAssistantSyncMessage: sender=$sender, content_len=${content.length}, convId=$conversationId")
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法同步$sender 消息")
            return
        }
        try {
            val msgId = java.util.UUID.randomUUID().toString()
            val message = org.json.JSONObject().apply {
                put("type", "assistant_sync_message")
                put("sender", sender)
                put("content", content)
                put("message_id", msgId)
                put("conversation_id", conversationId)
                put("timestamp", System.currentTimeMillis())
            }
            val success = websocket?.send(message.toString()) ?: false
            if (success) Log.d(TAG, "$sender 消息已同步到后端, msgId=$msgId")
            else forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "发送$sender 消息同步异常: ${e.message}", e)
            forceReconnect()
        }
    }
    
    /**
     * 发送用户消息到后端（用于端云同步，手机端发送时上报）
     * @param conversationId 会话 id，多 session 时格式为 assistantId__sessionId
     */
    fun sendAssistantUserMessage(
        content: String,
        messageId: String? = null,
        conversationId: String = "assistant",
        fileBase64: String? = null,
        fileName: String? = null
    ) {
        Log.d(
            TAG,
            "sendAssistantUserMessage: content_len=${content.length}, convId=$conversationId, hasImage=${!fileBase64.isNullOrBlank()}"
        )
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法同步用户消息")
            return
        }
        try {
            val msgId = messageId ?: java.util.UUID.randomUUID().toString()
            val message = org.json.JSONObject().apply {
                put("type", "assistant_user_message")
                put("content", content)
                put("message_id", msgId)
                put("sender", "我")
                put("conversation_id", conversationId)
                put("timestamp", System.currentTimeMillis())
                if (!fileBase64.isNullOrBlank()) {
                    put("file_base64", fileBase64)
                    put("message_type", "image")
                }
                if (!fileName.isNullOrBlank()) {
                    put("file_name", fileName)
                }
            }
            val success = websocket?.send(message.toString()) ?: false
            if (success) Log.d(TAG, "用户消息已同步到后端, msgId=$msgId")
            else forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "发送用户消息同步异常: ${e.message}", e)
            forceReconnect()
        }
    }

    /**
     * 发送「仅电脑端执行」指令到 PC（手机发起 -> 桥接到 PC 执行）
     */
    fun sendMobileExecutePcCommand(
        query: String,
        assistantBaseUrl: String,
        conversationId: String,
        messageId: String? = null,
        chatSummary: String? = null,
        imageBase64: String? = null,
        imageName: String? = null
    ) {
        Log.d(
            TAG,
            "sendMobileExecutePcCommand: query=${query.take(50)}..., baseUrl=${assistantBaseUrl.take(50)}..., convId=$conversationId, hasImage=${!imageBase64.isNullOrBlank()}"
        )
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送电脑端执行指令")
            return
        }
        try {
            val msgId = messageId ?: java.util.UUID.randomUUID().toString()
            val message = org.json.JSONObject().apply {
                put("type", "mobile_execute_pc_command")
                put("query", query)
                put("assistant_base_url", assistantBaseUrl)
                put("conversation_id", conversationId)
                put("message_id", msgId)
                put("timestamp", System.currentTimeMillis())
                if (!chatSummary.isNullOrBlank()) put("chat_summary", chatSummary)
                if (!imageBase64.isNullOrBlank()) {
                    put("file_base64", imageBase64)
                    put("message_type", "image")
                }
                if (!imageName.isNullOrBlank()) {
                    put("file_name", imageName)
                }
            }
            val success = websocket?.send(message.toString()) ?: false
            if (success) Log.d(TAG, "电脑端执行指令已发送, msgId=$msgId")
            else forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "发送电脑端执行指令异常: ${e.message}", e)
            forceReconnect()
        }
    }

    /**
     * 同步版：发送「仅电脑端执行」指令并等待 PC 返回结果（用于群组管理小助手等需要同步获取回复的场景）
     */
    suspend fun sendMobileExecutePcCommandAndWait(
        query: String,
        assistantBaseUrl: String,
        conversationId: String,
        threadId: String? = null,
        messageId: String? = null,
        imageBase64: String? = null,
        timeoutMs: Long = 180000L
    ): JSONObject {
        if (!isConnected || websocket == null) {
            throw IllegalStateException("WebSocket未连接，无法发送电脑端执行指令")
        }
        val msgId = messageId ?: UUID.randomUUID().toString()
        val waiter = CompletableDeferred<JSONObject>()
        pendingMobileExecResponses[msgId] = waiter
        try {
            val message = JSONObject().apply {
                put("type", "mobile_execute_pc_command")
                put("query", query)
                put("assistant_base_url", assistantBaseUrl)
                put("conversation_id", conversationId)
                put("message_id", msgId)
                put("timestamp", System.currentTimeMillis())
                if (!threadId.isNullOrBlank()) put("thread_id", threadId)
                if (!imageBase64.isNullOrBlank()) {
                    put("file_base64", imageBase64)
                    put("message_type", "image")
                }
            }
            val sent = websocket?.send(message.toString()) ?: false
            if (!sent) {
                pendingMobileExecResponses.remove(msgId)
                throw IllegalStateException("mobile_execute_pc_command 发送失败")
            }
            Log.d(TAG, "sendMobileExecutePcCommandAndWait: 已发送, msgId=$msgId, 等待PC回复...")
            return withTimeout(timeoutMs) { waiter.await() }
        } catch (e: Exception) {
            pendingMobileExecResponses.remove(msgId)
            throw e
        }
    }

    /**
     * 发送停止任务指令（通知对端停止当前自定义小助手任务）
     */
    fun sendAssistantStopTask(conversationId: String) {
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送assistant_stop_task")
            return
        }
        try {
            val message = org.json.JSONObject().apply {
                put("type", "assistant_stop_task")
                put("conversation_id", conversationId)
                put("timestamp", System.currentTimeMillis())
            }
            val success = websocket?.send(message.toString()) ?: false
            if (success) Log.d(TAG, "assistant_stop_task 已发送, convId=$conversationId")
            else forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "发送assistant_stop_task异常: ${e.message}", e)
        }
    }

    /**
     * 发送 GUI 执行结果（mobile -> customer_service -> topomobile -> TopoClaw）
     */
    fun sendGuiExecuteResult(
        requestId: String,
        success: Boolean,
        content: String? = null,
        error: String? = null,
        threadId: String? = null
    ) {
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送gui_execute_result")
            return
        }
        try {
            val message = org.json.JSONObject().apply {
                put("type", "gui_execute_result")
                put("request_id", requestId)
                put("success", success)
                put("content", content ?: "")
                put("error", error ?: "")
                if (!threadId.isNullOrBlank()) put("thread_id", threadId)
                put("timestamp", System.currentTimeMillis())
            }
            val sent = websocket?.send(message.toString()) ?: false
            if (sent) {
                Log.d(TAG, "gui_execute_result 已发送: request_id=$requestId, success=$success")
            } else {
                Log.w(TAG, "gui_execute_result 发送失败，尝试重连")
                forceReconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送gui_execute_result异常: ${e.message}", e)
            forceReconnect()
        }
    }

    suspend fun sendGuiStepRequest(
        guiRequestId: String,
        query: String,
        screenshotBase64: String,
        threadId: String? = null,
        packageName: String? = null,
        userResponse: String? = null,
        timeoutMs: Long = 120000L
    ): JSONObject {
        if (!isConnected || websocket == null) {
            throw IllegalStateException("WebSocket未连接，无法发送gui_step_request")
        }
        val stepRequestId = "gui-step-${java.util.UUID.randomUUID()}"
        val waiter = CompletableDeferred<JSONObject>()
        pendingGuiStepResponses[stepRequestId] = waiter
        try {
            val message = JSONObject().apply {
                put("type", "gui_step_request")
                put("step_request_id", stepRequestId)
                put("gui_request_id", guiRequestId)
                put("query", query)
                put("screenshot", screenshotBase64)
                if (!threadId.isNullOrBlank()) put("thread_id", threadId)
                if (!packageName.isNullOrBlank()) put("package_name", packageName)
                if (!userResponse.isNullOrBlank()) put("user_response", userResponse)
                put("timestamp", System.currentTimeMillis())
            }
            val sent = websocket?.send(message.toString()) ?: false
            if (!sent) {
                pendingGuiStepResponses.remove(stepRequestId)
                throw IllegalStateException("gui_step_request发送失败")
            }
            return withTimeout(timeoutMs) { waiter.await() }
        } catch (e: Exception) {
            pendingGuiStepResponses.remove(stepRequestId)
            throw e
        }
    }

    /**
     * 拉取 TopoClaw 内置模型下拉配置（仅模型名，不包含密钥）
     */
    suspend fun fetchTopoclawModelProfiles(timeoutMs: Long = 10000L): JSONObject {
        if (!isConnected || websocket == null) {
            throw IllegalStateException("WebSocket未连接，无法获取模型配置")
        }
        val requestId = "builtin-model-profiles-${java.util.UUID.randomUUID()}"
        val waiter = CompletableDeferred<JSONObject>()
        pendingModelProfilesResponses[requestId] = waiter
        try {
            val message = JSONObject().apply {
                put("type", "get_builtin_model_profiles")
                put("request_id", requestId)
                put("timestamp", System.currentTimeMillis())
            }
            val sent = websocket?.send(message.toString()) ?: false
            if (!sent) {
                pendingModelProfilesResponses.remove(requestId)
                throw IllegalStateException("get_builtin_model_profiles 发送失败")
            }
            return withTimeout(timeoutMs) { waiter.await() }
        } catch (e: Exception) {
            pendingModelProfilesResponses.remove(requestId)
            throw e
        }
    }

    private suspend fun sendTopoclawCronRequest(
        payload: JSONObject,
        timeoutMs: Long = 12000L
    ): JSONObject {
        if (!isConnected || websocket == null) {
            throw IllegalStateException("WebSocket未连接，无法操作定时任务")
        }
        val requestId = payload.optString("request_id", "").trim().ifBlank {
            "cron-${java.util.UUID.randomUUID()}"
        }
        payload.put("request_id", requestId)
        payload.put("timestamp", System.currentTimeMillis())
        val waiter = CompletableDeferred<JSONObject>()
        pendingCronResponses[requestId] = waiter
        try {
            val sent = websocket?.send(payload.toString()) ?: false
            if (!sent) {
                pendingCronResponses.remove(requestId)
                throw IllegalStateException("cron请求发送失败")
            }
            return withTimeout(timeoutMs) { waiter.await() }
        } catch (e: Exception) {
            pendingCronResponses.remove(requestId)
            throw e
        }
    }

    suspend fun listTopoclawCronJobs(
        includeDisabled: Boolean = false,
        timeoutMs: Long = 12000L
    ): JSONObject {
        val payload = JSONObject().apply {
            put("type", "cron_list_jobs")
            put("include_disabled", includeDisabled)
        }
        return sendTopoclawCronRequest(payload, timeoutMs)
    }

    suspend fun createTopoclawCronJob(
        message: String,
        name: String? = null,
        everySeconds: Int? = null,
        cronExpr: String? = null,
        at: String? = null,
        tz: String? = null,
        deliver: Boolean = true,
        deleteAfterRun: Boolean? = null,
        timeoutMs: Long = 12000L
    ): JSONObject {
        val payload = JSONObject().apply {
            put("type", "cron_create_job")
            put("message", message)
            put("deliver", deliver)
            if (!name.isNullOrBlank()) put("name", name)
            if (everySeconds != null) put("every_seconds", everySeconds)
            if (!cronExpr.isNullOrBlank()) put("cron_expr", cronExpr)
            if (!at.isNullOrBlank()) put("at", at)
            if (!tz.isNullOrBlank()) put("tz", tz)
            if (deleteAfterRun != null) put("delete_after_run", deleteAfterRun)
        }
        return sendTopoclawCronRequest(payload, timeoutMs)
    }

    suspend fun deleteTopoclawCronJob(
        jobId: String,
        timeoutMs: Long = 12000L
    ): JSONObject {
        val payload = JSONObject().apply {
            put("type", "cron_delete_job")
            put("job_id", jobId)
        }
        return sendTopoclawCronRequest(payload, timeoutMs)
    }

    /**
     * 发送端云互发消息（手机 -> PC）
     */
    fun sendCrossDeviceMessage(content: String, messageType: String = "text", fileBase64: String? = null, fileName: String? = null) {
        Log.d(TAG, "sendCrossDeviceMessage: content=$content, type=$messageType, hasFile=${fileBase64 != null}")
        if (!isConnected) {
            Log.w(TAG, "WebSocket未连接，尝试重新连接...")
            scheduleReconnect()
            return
        }
        if (websocket == null) {
            Log.e(TAG, "WebSocket实例为null")
            isConnected = false
            scheduleReconnect()
            return
        }
        try {
            val message = org.json.JSONObject().apply {
                put("type", "cross_device_message")
                put("content", content)
                put("message_type", messageType)
                put("timestamp", System.currentTimeMillis())
                if (fileBase64 != null) put("imageBase64", fileBase64)
                if (fileBase64 != null) put("fileBase64", fileBase64)
                if (fileName != null) put("fileName", fileName)
                if (fileName != null) put("file_name", fileName)
            }
            val success = websocket?.send(message.toString()) ?: false
            if (success) Log.d(TAG, "端云消息发送成功")
            else forceReconnect()
        } catch (e: Exception) {
            Log.e(TAG, "发送端云消息异常: ${e.message}", e)
            forceReconnect()
        }
    }
    
    /**
     * 发送群组消息给服务器
     */
    fun sendGroupMessage(groupId: String, content: String, imageBase64: String? = null): String? {
        Log.d(TAG, "sendGroupMessage调用: groupId=$groupId, content=$content, hasImage=${imageBase64 != null}")
        
        if (!isConnected) {
            Log.w(TAG, "WebSocket未连接，尝试重新连接...")
            scheduleReconnect()
            return null
        }
        
        if (websocket == null) {
            Log.e(TAG, "WebSocket实例为null，尝试重新连接...")
            isConnected = false
            scheduleReconnect()
            return null
        }
        
        try {
            val messageId = UUID.randomUUID().toString()
            val message = JSONObject().apply {
                put("type", "group_message")
                put("groupId", groupId)
                put("content", content)
                put("message_id", messageId)
                put("sender_origin", "mobile")
                put("timestamp", System.currentTimeMillis())
                if (imageBase64 != null) {
                    put("message_type", "image")
                    put("imageBase64", imageBase64)
                } else {
                    put("message_type", "text")
                }
            }
            val messageStr = message.toString()
            val messageSize = messageStr.length
            Log.d(TAG, "准备发送群组消息: 消息大小=${messageSize}字节, hasImage=${imageBase64 != null}")
            if (imageBase64 != null) {
                Log.d(TAG, "imageBase64长度: ${imageBase64.length}字节")
                // 不打印包含base64的完整消息，避免日志过大
                val messagePreview = JSONObject().apply {
                    put("type", "group_message")
                    put("groupId", groupId)
                    put("content", content)
                    put("message_type", "image")
                    put("imageBase64", "[已省略，长度: ${imageBase64.length}字节]")
                }
                Log.d(TAG, "消息预览: ${messagePreview.toString()}")
            } else {
                Log.d(TAG, "消息内容: $messageStr")
            }
            
            val success = websocket?.send(messageStr) ?: false
            if (success) {
                Log.d(TAG, "群组消息发送成功: $groupId - $content, hasImage=${imageBase64 != null}, messageId=$messageId")
                return messageId
            } else {
                Log.e(TAG, "群组消息发送失败：WebSocket连接已断开，触发强制重连")
                forceReconnect()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送群组消息时发生异常: ${e.message}", e)
            e.printStackTrace()
            forceReconnect()
            return null
        }
    }
    
    /**
     * 发送远程执行指令给服务器
     */
    fun sendRemoteAssistantCommand(groupId: String, targetImei: String, command: String, senderImei: String) {
        Log.d(TAG, "sendRemoteAssistantCommand调用: groupId=$groupId, targetImei=$targetImei, command=$command")
        
        if (!isConnected) {
            Log.w(TAG, "WebSocket未连接，尝试重新连接...")
            scheduleReconnect()
            return
        }
        
        if (websocket == null) {
            Log.e(TAG, "WebSocket实例为null，尝试重新连接...")
            isConnected = false
            scheduleReconnect()
            return
        }
        
        try {
            val message = JSONObject().apply {
                put("type", "remote_assistant_command")
                put("groupId", groupId)
                put("targetImei", targetImei)
                put("command", command)
                put("senderImei", senderImei)
                put("timestamp", System.currentTimeMillis())
            }
            val messageStr = message.toString()
            Log.d(TAG, "准备发送远程指令: $messageStr")
            
            val success = websocket?.send(messageStr) ?: false
            if (success) {
                Log.d(TAG, "远程指令发送成功: $groupId -> $targetImei - $command")
            } else {
                Log.e(TAG, "远程指令发送失败：WebSocket连接已断开，触发强制重连")
                forceReconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送远程指令时发生异常: ${e.message}", e)
            e.printStackTrace()
            forceReconnect()
        }
    }

    /**
     * 发送自定义 JSON 消息（mobile_tool_v1 等扩展协议复用）
     */
    fun sendCustomMessage(message: JSONObject): Boolean {
        if (!isConnected || websocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送自定义消息: type=${message.optString("type", "unknown")}")
            return false
        }
        return try {
            val messageStr = message.toString()
            val success = websocket?.send(messageStr) ?: false
            if (!success) {
                Log.e(TAG, "自定义消息发送失败，触发重连: type=${message.optString("type", "unknown")}")
                forceReconnect()
            } else {
                Log.d(TAG, "自定义消息发送成功: type=${message.optString("type", "unknown")}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "发送自定义消息异常: ${e.message}", e)
            forceReconnect()
            false
        }
    }

    /**
     * 通过中转服务请求 TopoClaw 切换 Chat/GUI 模型（与桌面端 set_*_provider 协议一致）
     */
    fun sendTopoclawModelSwitchRequest(
        providerType: String,
        model: String,
        requestId: String = "model-switch-${java.util.UUID.randomUUID()}"
    ): Boolean {
        val normalizedType = providerType.trim().lowercase()
        val wsType = when (normalizedType) {
            "chat" -> "set_llm_provider"
            "gui" -> "set_gui_provider"
            else -> return false
        }
        val modelValue = model.trim()
        if (modelValue.isBlank()) return false
        val msg = JSONObject().apply {
            put("type", wsType)
            put("model", modelValue)
            put("request_id", requestId)
            put("timestamp", System.currentTimeMillis())
        }
        return sendCustomMessage(msg)
    }
    
    /**
     * 发送消息给服务器（内部方法）
     */
    private fun sendMessage(content: String, messageType: String) {
        Log.d(TAG, "sendMessage调用: content=$content, type=$messageType, isConnected=$isConnected, websocket=${websocket != null}")
        
        if (!isConnected) {
            Log.w(TAG, "WebSocket未连接，尝试重新连接...")
            scheduleReconnect()
            return
        }
        
        if (websocket == null) {
            Log.e(TAG, "WebSocket实例为null，尝试重新连接...")
            isConnected = false
            scheduleReconnect()
            return
        }
        
        try {
            val message = JSONObject().apply {
                put("type", messageType)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
            }
            val messageStr = message.toString()
            Log.d(TAG, "准备发送消息: $messageStr")
            
            // OkHttp的WebSocket.send()返回boolean，false表示连接已断开
            val success = websocket?.send(messageStr) ?: false
            if (success) {
                Log.d(TAG, "消息发送成功: $content")
            } else {
                Log.e(TAG, "消息发送失败：WebSocket连接已断开，触发强制重连")
                // 连接已断开，更新状态并触发重连
                forceReconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送消息时发生异常: ${e.message}", e)
            e.printStackTrace()
            // 发送失败，可能是连接断开，触发强制重连
            forceReconnect()
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        reconnectJob?.cancel()
        stopHeartbeat()
        websocket?.close(1000, "正常关闭")
        websocket = null
        isConnected = false
        lastPingTime = 0
        lastPongTime = 0
        Log.d(TAG, "WebSocket已断开")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}

