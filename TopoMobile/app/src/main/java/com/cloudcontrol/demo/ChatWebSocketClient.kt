package com.cloudcontrol.demo

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 聊天 WebSocket 客户端
 * 将 baseUrl (http(s)://...) 转为 ws(s)://.../ws，发送聊天消息并接收完整回复
 */
object ChatWebSocketClient {
    private const val TAG = "ChatWebSocketClient"

    /** 安全从 JsonElement 获取字符串，避免 JsonNull 导致 asString 抛异常 */
    private fun JsonElement?.asStringSafe(): String? = when {
        this == null || isJsonNull -> null
        else -> try { asString } catch (_: Exception) { null }
    }

    /** 将 HTTP baseUrl 转为 WebSocket URL */
    fun getWebSocketUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.startsWith("https://") -> base.replace("https://", "wss://") + "/ws"
            base.startsWith("http://") -> base.replace("http://", "ws://") + "/ws"
            else -> base + "/ws"
        }
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 通过 WebSocket 发送聊天消息，等待完整回复（非流式，与旧 POST /chat 行为一致）
     */
    suspend fun sendChatMessage(
        baseUrl: String,
        threadId: String,
        message: String,
        images: List<String>? = null
    ): ChatAssistantChatResponse = suspendCancellableCoroutine { cont ->
        val wsUrl = getWebSocketUrl(baseUrl)
        Log.d(TAG, "连接 WebSocket: $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        val filteredImages = images?.filter { it.length > 100 }?.take(3)
        val payload = mapOf(
            "type" to "chat",
            "thread_id" to threadId,
            "message" to (message.ifBlank { if (!filteredImages.isNullOrEmpty()) "[图片]" else "" }),
            "images" to (filteredImages ?: emptyList<String>())
        )
        val bodyJson = gson.toJson(payload)
        var ws: WebSocket? = null
        var fullText = ""
        var needExecution = false
        var chatSummary: String? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接")
                webSocket.send(bodyJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = gson.fromJson(text, JsonObject::class.java)
                    val type = obj.get("type").asStringSafe() ?: ""
                    when (type) {
                        "delta" -> obj.get("content").asStringSafe()?.let { fullText += it }
                        "done" -> {
                            obj.get("response").asStringSafe()?.let { fullText = it }
                            needExecution = try {
                                obj.get("need_execution")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                            } catch (_: Exception) { false }
                            chatSummary = obj.get("chat_summary").asStringSafe()
                            webSocket.close(1000, null)
                            if (cont.isActive) {
                                cont.resume(
                                    ChatAssistantChatResponse(
                                        response = fullText.ifBlank { "收到空回复" },
                                        thread_id = threadId,
                                        need_execution = needExecution,
                                        chat_summary = chatSummary
                                    )
                                )
                            }
                        }
                        "error" -> {
                            val err = obj.get("error").asStringSafe() ?: "未知错误"
                            webSocket.close(1000, null)
                            if (cont.isActive) {
                                cont.resumeWithException(RuntimeException(err))
                            }
                        }
                        "pong" -> { /* ignore */ }
                        else -> { /* ignore other types */ }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析消息失败: ${e.message}", e)
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message}", t)
                if (cont.isActive) {
                    cont.resumeWithException(t)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000 && fullText.isEmpty() && cont.isActive) {
                    cont.resumeWithException(RuntimeException("连接关闭: $code $reason"))
                }
            }
        }

        ws = client.newWebSocket(request, listener)

        cont.invokeOnCancellation {
            try {
                ws?.close(1000, "cancelled")
            } catch (_: Exception) {}
        }
    }
}
