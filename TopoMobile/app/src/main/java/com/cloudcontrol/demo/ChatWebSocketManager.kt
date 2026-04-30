package com.cloudcontrol.demo

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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 长连接聊天 WebSocket 管理器
 * 进入聊天小助手/自定义聊天小助手时连接并 register，离开时断开
 * 支持 assistant_push 主动推送、通过长连接发送消息
 */
object ChatWebSocketManager {
    private const val TAG = "ChatWebSocketManager"

    /** 安全从 JsonElement 获取字符串，避免 JsonNull 导致 asString 抛异常 */
    private fun JsonElement?.asStringSafe(): String? = when {
        this == null || isJsonNull -> null
        else -> try { asString } catch (_: Exception) { null }
    }
    private const val HEARTBEAT_INTERVAL_MS = 30_000L
    private const val PONG_TIMEOUT_MS = 10_000L

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatThread = HandlerThread("ChatWsHeartbeat").apply { start() }
    private val heartbeatHandler = Handler(heartbeatThread.looper)
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    private var onAssistantPushCallback: ((String) -> Unit)? = null

    @Volatile
    private var currentThreadId: String? = null

    private val pendingSendRef = AtomicReference<PendingSend?>(null)

    private var pingRunnable: Runnable? = null
    private var pongTimeoutRunnable: Runnable? = null

    private data class PendingSend(
        val cont: CancellableContinuation<ChatAssistantChatResponse>,
        var fullText: String = "",
        var needExecution: Boolean = false,
        var chatSummary: String? = null
    )

    fun getWebSocketUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.startsWith("https://") -> base.replace("https://", "wss://") + "/ws"
            base.startsWith("http://") -> base.replace("http://", "ws://") + "/ws"
            else -> base + "/ws"
        }
    }

    fun isConnected(): Boolean = webSocket != null

    fun setOnAssistantPush(callback: ((String) -> Unit)?) {
        onAssistantPushCallback = callback
    }

    private fun stopHeartbeat() {
        pingRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        pingRunnable = null
        pongTimeoutRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        pongTimeoutRunnable = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        pingRunnable = Runnable {
            val ws = webSocket ?: return@Runnable
            try {
                ws.send(gson.toJson(mapOf("type" to "ping")))
                pongTimeoutRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                pongTimeoutRunnable = Runnable {
                    Log.w(TAG, "心跳 pong 超时，断开连接")
                    disconnect()
                }.also { heartbeatHandler.postDelayed(it, PONG_TIMEOUT_MS) }
            } catch (e: Exception) {
                Log.e(TAG, "心跳 ping 发送失败: ${e.message}")
            }
            pingRunnable?.let { heartbeatHandler.postDelayed(it, HEARTBEAT_INTERVAL_MS) }
        }.also { heartbeatHandler.post(it) }
    }

    /**
     * 连接并注册，用于接收 assistant_push
     */
    fun connect(
        baseUrl: String,
        threadId: String,
        deviceId: String,
        deviceType: String = "mobile"
    ) {
        if (currentThreadId == threadId && webSocket != null) {
            Log.d(TAG, "已连接到相同 thread，跳过重连")
            return
        }
        disconnect()
        val wsUrl = getWebSocketUrl(baseUrl)
        Log.d(TAG, "长连接 WebSocket: $wsUrl threadId=$threadId")
        val request = Request.Builder().url(wsUrl).build()
        currentThreadId = threadId
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接，发送 register")
                val register = mutableMapOf<String, Any>(
                    "type" to "register",
                    "thread_id" to threadId,
                    "device_id" to deviceId,
                    "device_type" to deviceType
                )
                if (deviceId.isNotBlank()) register["imei"] = deviceId  // mobile 端 deviceId 即 IMEI
                webSocket.send(gson.toJson(register))
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = gson.fromJson(text, JsonObject::class.java)
                    val type = obj.get("type").asStringSafe() ?: ""
                    when (type) {
                        "registered" -> Log.d(TAG, "已注册 thread_id=$threadId")
                        "assistant_push" -> {
                            val content = obj.get("content").asStringSafe() ?: ""
                            if (content.isNotBlank()) {
                                mainHandler.post { onAssistantPushCallback?.invoke(content) }
                            }
                        }
                        "delta" -> {
                            val content = obj.get("content").asStringSafe() ?: ""
                            pendingSendRef.get()?.let { it.fullText += content }
                        }
                        "done" -> {
                            val response = obj.get("response").asStringSafe()
                            val needExec = try {
                                obj.get("need_execution")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                            } catch (_: Exception) { false }
                            val summary = obj.get("chat_summary").asStringSafe()
                            val pending = pendingSendRef.getAndSet(null)
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
                            val pending = pendingSendRef.getAndSet(null)
                            if (pending?.cont?.isActive == true) {
                                pending.cont.resumeWithException(RuntimeException(err))
                            }
                        }
                        "pong" -> {
                            pongTimeoutRunnable?.let { heartbeatHandler.removeCallbacks(it) }
                            pongTimeoutRunnable = null
                        }
                        else -> { /* ignore */ }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析消息失败: ${e.message}", e)
                    val pending = pendingSendRef.getAndSet(null)
                    if (pending?.cont?.isActive == true) {
                        pending.cont.resumeWithException(e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message}", t)
                stopHeartbeat()
                val pending = pendingSendRef.getAndSet(null)
                if (pending?.cont?.isActive == true) {
                    pending.cont.resumeWithException(t)
                }
                this@ChatWebSocketManager.webSocket = null
                currentThreadId = null
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                this@ChatWebSocketManager.webSocket = null
                currentThreadId = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                this@ChatWebSocketManager.webSocket = null
                currentThreadId = null
            }
        })
    }

    fun disconnect() {
        stopHeartbeat()
        try {
            webSocket?.close(1000, "disconnect")
        } catch (_: Exception) {}
        webSocket = null
        currentThreadId = null
        val pending = pendingSendRef.getAndSet(null)
        if (pending?.cont?.isActive == true) {
            pending.cont.resumeWithException(RuntimeException("连接已断开"))
        }
    }

    /**
     * 通过长连接发送消息；若未连接则回退到短连接
     */
    suspend fun sendChat(
        baseUrl: String,
        threadId: String,
        message: String,
        images: List<String>? = null
    ): ChatAssistantChatResponse {
        val ws = webSocket
        if (ws != null && currentThreadId == threadId) {
            return sendViaConnection(ws, threadId, message, images)
        }
        return ChatWebSocketClient.sendChatMessage(baseUrl, threadId, message, images)
    }

    private suspend fun sendViaConnection(
        ws: WebSocket,
        threadId: String,
        message: String,
        images: List<String>?
    ): ChatAssistantChatResponse = suspendCancellableCoroutine { cont ->
        val pending = PendingSend(cont)
        if (!pendingSendRef.compareAndSet(null, pending)) {
            cont.resumeWithException(RuntimeException("已有请求进行中，请稍后重试"))
            return@suspendCancellableCoroutine
        }
        val filteredImages = images?.filter { it.length > 100 }?.take(3)
        val payload = mapOf(
            "type" to "chat",
            "thread_id" to threadId,
            "message" to (message.ifBlank { if (!filteredImages.isNullOrEmpty()) "[图片]" else "" }),
            "images" to (filteredImages ?: emptyList<String>())
        )
        ws.send(gson.toJson(payload))

        cont.invokeOnCancellation {
            pendingSendRef.compareAndSet(pending, null)
            if (pending.cont.isActive) {
                pending.cont.resumeWithException(RuntimeException("已取消"))
            }
        }
    }
}
