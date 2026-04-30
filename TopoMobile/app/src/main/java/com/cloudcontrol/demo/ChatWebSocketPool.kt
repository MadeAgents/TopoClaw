package com.cloudcontrol.demo

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 聊天 WebSocket 连接池
 * 应用启动时仅预连接默认聊天小助手；自定义小助手由 customer_service 中转。
 */
object ChatWebSocketPool {
    private const val TAG = "ChatWebSocketPool"

    /** 安全从 JsonElement 获取字符串，避免 JsonNull 导致 asString 抛异常 */
    private fun JsonElement?.asStringSafe(): String? = when {
        this == null || isJsonNull -> null
        else -> try { asString } catch (_: Exception) { null }
    }
    private const val HEARTBEAT_INTERVAL_MS = 30_000L
    private const val PONG_TIMEOUT_MS = 10_000L
    private val DEFAULT_CHAT_ASSISTANT_SERVER_URL = ServiceUrlConfig.DEFAULT_CHAT_ASSISTANT_URL

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatThread = HandlerThread("ChatWsPoolHeartbeat").apply { start() }
    private val heartbeatHandler = Handler(heartbeatThread.looper)
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, ConnState>()
    private val pushCallbacks = ConcurrentHashMap<String, (String) -> Unit>()
    private val heartbeatState = ConcurrentHashMap<String, HeartbeatState>()

    /** GUI 执行请求回调：收到 gui_execute_request 时调用。由 MainActivity 设置。 */
    var onGuiExecuteRequest: ((baseUrl: String, threadId: String, requestId: String, query: String, chatSummary: String?, sendResult: (content: String?, error: String?) -> Unit) -> Unit)? = null

    private data class PendingSend(
        val cont: CancellableContinuation<ChatAssistantChatResponse>,
        var fullText: String = "",
        var needExecution: Boolean = false,
        var chatSummary: String? = null
    )

    private class ConnState(
        val threadId: String,
        val baseUrl: String,
        val webSocket: WebSocket
    ) {
        val pendingSendRef = AtomicReference<PendingSend?>(null)
    }

    private class HeartbeatState {
        var pingRunnable: Runnable? = null
        var pongTimeoutRunnable: Runnable? = null
    }

    private fun getWebSocketUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.startsWith("https://") -> base.replace("https://", "wss://") + "/ws"
            base.startsWith("http://") -> base.replace("http://", "ws://") + "/ws"
            else -> base + "/ws"
        }
    }

    /**
     * 仅允许标准网络 URL，避免如 topoclaw:// 这类自定义协议导致 OkHttp 抛异常并引发启动崩溃。
     */
    private fun isSupportedBaseUrl(baseUrl: String): Boolean {
        val normalized = baseUrl.trim().lowercase()
        return normalized.startsWith("http://") ||
            normalized.startsWith("https://") ||
            normalized.startsWith("ws://") ||
            normalized.startsWith("wss://")
    }

    internal fun getDefaultChatBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_${ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT}"
        return prefs.getString(specificKey, null) ?: DEFAULT_CHAT_ASSISTANT_SERVER_URL
    }

    /**
     * 确保指定小助手的 WebSocket 连接存在；若不存在或已断开则建立连接并注册。
     * 进入某小助手的聊天时调用，作为 connectAll 的兜底，解决启动时连接失败或断线后的恢复。
     */
    fun ensureConnected(context: Context, baseUrl: String, threadId: String, multiSession: Boolean) {
        if (connections.containsKey(threadId)) {
            Log.d(TAG, "ensureConnected: 已连接 threadId=$threadId，跳过")
            return
        }
        if (!isSupportedBaseUrl(baseUrl)) {
            Log.w(TAG, "ensureConnected: 非法 baseUrl，跳过连接 threadId=$threadId baseUrl=$baseUrl")
            return
        }
        if (multiSession && getConnectionByBaseUrl(baseUrl) != null) {
            Log.d(TAG, "ensureConnected: 多 session 已有 baseUrl 连接，跳过 threadId=$threadId")
            return
        }
        val imei = ProfileManager.getOrGenerateImei(context)
        Log.d(TAG, "ensureConnected: 连接不存在或已断开，建立连接 threadId=$threadId")
        connectOne(baseUrl, threadId, imei, "mobile", multiSession)
    }

    /** (baseUrl, threadId, multiSession) */
    private fun getAssistantTargets(context: Context): List<Triple<String, String, Boolean>> {
        val imei = ProfileManager.getOrGenerateImei(context)
        val targets = mutableListOf<Triple<String, String, Boolean>>()

        val defaultBaseUrl = getDefaultChatBaseUrl(context)
        val defaultThreadId = Uuid5Util.chatAssistantThreadId(imei)
        targets.add(Triple(defaultBaseUrl, defaultThreadId, false))
        return targets
    }

    fun connectAll(context: Context) {
        val imei = ProfileManager.getOrGenerateImei(context)
        val targets = getAssistantTargets(context)

        for ((baseUrl, threadId, multiSession) in targets) {
            if (connections.containsKey(threadId)) {
                Log.d(TAG, "已连接 threadId=$threadId，跳过")
                continue
            }
            if (!isSupportedBaseUrl(baseUrl)) {
                Log.w(TAG, "connectAll: 非法 baseUrl，跳过连接 threadId=$threadId baseUrl=$baseUrl")
                continue
            }
            connectOne(baseUrl, threadId, imei, "mobile", multiSession)
        }
    }

    fun disconnectAll() {
        for ((_, state) in connections) {
            try {
                state.webSocket.close(1000, "disconnect")
            } catch (_: Exception) {}
            stopHeartbeat(state)
            val pending = state.pendingSendRef.getAndSet(null)
            if (pending?.cont?.isActive == true) {
                pending.cont.resumeWithException(RuntimeException("连接已断开"))
            }
        }
        connections.clear()
        heartbeatState.clear()
    }

    fun setOnAssistantPush(threadId: String, callback: ((String) -> Unit)?) {
        if (callback != null) {
            pushCallbacks[threadId] = callback
        } else {
            pushCallbacks.remove(threadId)
        }
    }

    fun isConnected(threadId: String): Boolean = connections.containsKey(threadId)

    /** 按 baseUrl 查找连接（用于多 session 时 thread_id=sessionId） */
    private fun getConnectionByBaseUrl(baseUrl: String): ConnState? {
        val normalized = baseUrl.trim().trimEnd('/')
        return connections.values.find { it.baseUrl.trim().trimEnd('/') == normalized }
    }

    /** 多 session：订阅 thread，接收 assistant_push */
    fun subscribeThread(baseUrl: String, threadId: String) {
        val state = connections[threadId] ?: getConnectionByBaseUrl(baseUrl)
        if (state != null) {
            try {
                state.webSocket.send(gson.toJson(mapOf("type" to "subscribe_thread", "thread_id" to threadId)))
                Log.d(TAG, "subscribe_thread: $threadId")
            } catch (e: Exception) {
                Log.e(TAG, "subscribe_thread 发送失败: ${e.message}")
            }
        }
    }

    /** 多 session：取消订阅 thread */
    fun unsubscribeThread(baseUrl: String, threadId: String) {
        val state = connections[threadId] ?: getConnectionByBaseUrl(baseUrl)
        if (state != null) {
            try {
                state.webSocket.send(gson.toJson(mapOf("type" to "unsubscribe_thread", "thread_id" to threadId)))
                Log.d(TAG, "unsubscribe_thread: $threadId")
            } catch (e: Exception) {
                Log.e(TAG, "unsubscribe_thread 发送失败: ${e.message}")
            }
        }
    }

    suspend fun sendChat(
        baseUrl: String,
        threadId: String,
        message: String,
        images: List<String>? = null
    ): ChatAssistantChatResponse {
        val state = connections[threadId] ?: getConnectionByBaseUrl(baseUrl)
        if (state != null) {
            return sendViaConnection(state, message, images, threadId)
        }
        return ChatWebSocketClient.sendChatMessage(baseUrl, threadId, message, images)
    }

    private suspend fun sendViaConnection(
        state: ConnState,
        message: String,
        images: List<String>?,
        threadIdInPayload: String
    ): ChatAssistantChatResponse = suspendCancellableCoroutine { cont ->
        val pending = PendingSend(cont)
        if (!state.pendingSendRef.compareAndSet(null, pending)) {
            cont.resumeWithException(RuntimeException("已有请求进行中，请稍后重试"))
            return@suspendCancellableCoroutine
        }
        val filteredImages = images?.filter { it.length > 100 }?.take(3)
        val payload = mapOf(
            "type" to "chat",
            "thread_id" to threadIdInPayload,
            "message" to (message.ifBlank { if (!filteredImages.isNullOrEmpty()) "[图片]" else "" }),
            "images" to (filteredImages ?: emptyList<String>())
        )
        state.webSocket.send(gson.toJson(payload))

        cont.invokeOnCancellation {
            state.pendingSendRef.compareAndSet(pending, null)
            if (pending.cont.isActive) {
                pending.cont.resumeWithException(RuntimeException("已取消"))
            }
        }
    }

    private fun connectOne(
        baseUrl: String,
        threadId: String,
        deviceId: String,
        deviceType: String,
        multiSession: Boolean = false
    ) {
        val wsUrl = getWebSocketUrl(baseUrl)
        Log.d(TAG, "长连接 WebSocket: $wsUrl threadId=$threadId multiSession=$multiSession")
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "connectOne: 无效 WebSocket URL，跳过连接 threadId=$threadId wsUrl=$wsUrl: ${e.message}")
            return
        }

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接 threadId=$threadId")
                // 多 session：register 时 thread_id 传空，走多 thread 模式，后续 subscribe_thread(sessionId) 才能生效
                val registerThreadId = if (multiSession) "" else threadId
                val register = buildMap<String, Any> {
                    put("type", "register")
                    put("thread_id", registerThreadId)
                    put("device_id", deviceId)
                    put("device_type", deviceType)
                    put("supports_gui_execute", true)  // 手机端支持 GUI 执行
                    put("base_url", baseUrl.trim().trimEnd('/'))
                    if (deviceId.isNotBlank()) put("imei", deviceId)  // 用于 fallback 用户隔离
                }
                webSocket.send(gson.toJson(register))
                val state = ConnState(threadId, baseUrl, webSocket)
                connections[threadId] = state
                startHeartbeat(state)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = gson.fromJson(text, JsonObject::class.java)
                    val type = obj.get("type").asStringSafe() ?: ""
                    when (type) {
                        "registered" -> Log.d(TAG, "已注册 threadId=$threadId")
                        "assistant_push" -> {
                            val content = obj.get("content").asStringSafe() ?: ""
                            if (content.isNotBlank()) {
                                val pushTid = obj.get("thread_id").asStringSafe() ?: threadId
                                mainHandler.post { pushCallbacks[pushTid]?.invoke(content) }
                            }
                        }
                        "delta" -> {
                            val content = obj.get("content").asStringSafe() ?: ""
                            connections[threadId]?.pendingSendRef?.get()?.let { it.fullText += content }
                        }
                        "done" -> {
                            val response = obj.get("response").asStringSafe()
                            val needExec = try {
                                obj.get("need_execution")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                            } catch (_: Exception) { false }
                            val summary = obj.get("chat_summary").asStringSafe()
                            val state = connections[threadId] ?: return
                            val pending = state.pendingSendRef.getAndSet(null)
                            pending?.let {
                                if (response != null) it.fullText = response
                                it.needExecution = needExec
                                it.chatSummary = summary
                                if (it.cont.isActive) {
                                    it.cont.resume(
                                        ChatAssistantChatResponse(
                                            response = it.fullText.ifBlank { "收到空回复" },
                                            thread_id = threadId,
                                            need_execution = it.needExecution,
                                            chat_summary = it.chatSummary
                                        )
                                    )
                                }
                            }
                        }
                        "error" -> {
                            val err = obj.get("error").asStringSafe() ?: "未知错误"
                            val state = connections[threadId] ?: return
                            val pending = state.pendingSendRef.getAndSet(null)
                            if (pending?.cont?.isActive == true) {
                                pending.cont.resumeWithException(RuntimeException(err))
                            }
                        }
                        "pong" -> {
                            val hs = heartbeatState[threadId] ?: return
                            hs.pongTimeoutRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                            hs.pongTimeoutRunnable = null
                        }
                        "gui_execute_request" -> {
                            val requestId = obj.get("request_id").asStringSafe() ?: ""
                            val query = obj.get("query").asStringSafe() ?: ""
                            val chatSummary = obj.get("chat_summary").asStringSafe()
                            // payload.thread_id 缺省时为连接级 threadId；手机端 GUI 在当前选中 session 执行，不因该值切换 session
                            val sessionId = obj.get("thread_id").asStringSafe() ?: threadId
                            val state = connections[threadId] ?: connections.values.find { it.baseUrl == baseUrl } ?: return
                            if (requestId.isBlank() || query.isBlank()) {
                                Log.w(TAG, "gui_execute_request 缺少 request_id 或 query")
                                return
                            }
                            val sendResult: (content: String?, error: String?) -> Unit = { content, error ->
                                try {
                                    val payload = mapOf(
                                        "type" to "gui_execute_result",
                                        "request_id" to requestId,
                                        "success" to (error == null),
                                        "content" to (content ?: ""),
                                        "error" to (error ?: "")
                                    )
                                    state.webSocket.send(gson.toJson(payload))
                                } catch (e: Exception) {
                                    Log.e(TAG, "gui_execute_result 发送失败: ${e.message}")
                                }
                            }
                            mainHandler.post {
                                onGuiExecuteRequest?.invoke(state.baseUrl, sessionId, requestId, query, chatSummary, sendResult)
                                    ?: run {
                                        Log.w(TAG, "onGuiExecuteRequest 未设置，无法处理 gui_execute_request")
                                        sendResult(null, "回调未设置")
                                    }
                            }
                        }
                        else -> { /* ignore */ }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析消息失败: ${e.message}", e)
                    val state = connections[threadId]
                    val pending = state?.pendingSendRef?.getAndSet(null)
                    if (pending?.cont?.isActive == true) {
                        pending.cont.resumeWithException(e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败 threadId=$threadId: ${t.message}", t)
                val state = connections.remove(threadId)
                if (state != null) {
                    stopHeartbeat(state)
                    val pending = state.pendingSendRef.getAndSet(null)
                    if (pending?.cont?.isActive == true) {
                        pending.cont.resumeWithException(t)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val state = connections.remove(threadId)
                if (state != null) stopHeartbeat(state)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val state = connections.remove(threadId)
                if (state != null) stopHeartbeat(state)
            }
        })
    }

    private fun startHeartbeat(state: ConnState) {
        val hs = HeartbeatState()
        heartbeatState[state.threadId] = hs
        hs.pingRunnable = Runnable {
            val conn = connections[state.threadId] ?: run {
                heartbeatState.remove(state.threadId)
                return@Runnable
            }
            try {
                conn.webSocket.send(gson.toJson(mapOf("type" to "ping")))
                hs.pongTimeoutRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                hs.pongTimeoutRunnable = Runnable {
                    Log.w(TAG, "心跳 pong 超时 threadId=${state.threadId}，断开连接")
                    try { conn.webSocket.close(1000, "pong timeout") } catch (_: Exception) {}
                    connections.remove(state.threadId)
                    heartbeatState.remove(state.threadId)
                }.also { heartbeatHandler.postDelayed(it, PONG_TIMEOUT_MS) }
            } catch (e: Exception) {
                Log.e(TAG, "心跳 ping 发送失败: ${e.message}")
            }
            if (connections.containsKey(state.threadId)) {
                hs.pingRunnable?.let { heartbeatHandler.postDelayed(it, HEARTBEAT_INTERVAL_MS) }
            }
        }
        heartbeatHandler.post(hs.pingRunnable!!)
    }

    private fun stopHeartbeat(state: ConnState) {
        heartbeatState[state.threadId]?.let { hs ->
            hs.pingRunnable?.let { heartbeatHandler.removeCallbacks(it) }
            hs.pongTimeoutRunnable?.let { heartbeatHandler.removeCallbacks(it) }
            heartbeatState.remove(state.threadId)
        }
    }
}
