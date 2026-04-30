package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多 session 聊天会话存储
 * 与 PC 端 sessionStorage.ts 保持一致，用于支持多 session 的自定义小助手
 * session 仅影响 WebSocket（thread_id = sessionId）
 */
object SessionStorage {
    private const val TAG = "SessionStorage"
    private const val PREFIX = "chat_sessions_"
    private const val PREFS_NAME = "app_prefs"

    data class ChatSession(
        val id: String,
        val title: String,
        val createdAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(obj: JSONObject): ChatSession = ChatSession(
                id = obj.optString("id", ""),
                title = obj.optString("title", "新对话"),
                createdAt = obj.optLong("createdAt", 0L)
            )
        }
    }

    private fun getKey(conversationId: String): String = PREFIX + conversationId

    fun loadSessions(context: Context, conversationId: String): List<ChatSession> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(getKey(conversationId), null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    ChatSession.fromJson(arr.getJSONObject(i))
                } catch (e: Exception) {
                    Log.w(TAG, "解析 session 失败: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSessions 失败: ${e.message}", e)
            emptyList()
        }
    }

    fun saveSessions(context: Context, conversationId: String, sessions: List<ChatSession>) {
        try {
            val arr = JSONArray()
            sessions.forEach { arr.put(it.toJson()) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(getKey(conversationId), arr.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveSessions 失败: ${e.message}", e)
        }
    }

    fun addSession(context: Context, conversationId: String, session: ChatSession) {
        val list = loadSessions(context, conversationId).toMutableList()
        if (list.any { it.id == session.id }) return
        list.add(0, session)
        saveSessions(context, conversationId, list)
    }

    fun updateSessionTitle(
        context: Context,
        conversationId: String,
        sessionId: String,
        title: String
    ) {
        val list = loadSessions(context, conversationId).toMutableList()
        val idx = list.indexOfFirst { it.id == sessionId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(title = title)
            saveSessions(context, conversationId, list)
        }
    }

    fun removeSession(context: Context, conversationId: String, sessionId: String) {
        val list = loadSessions(context, conversationId).filter { it.id != sessionId }
        saveSessions(context, conversationId, list)
    }
}
