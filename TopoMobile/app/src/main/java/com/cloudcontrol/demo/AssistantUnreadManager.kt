package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * 自定义小助手未读消息管理器
 * 当用户在对话列表时收到小助手新消息，更新缩略图并显示未读红点
 */
object AssistantUnreadManager {
    private const val TAG = "AssistantUnreadManager"
    private const val PREFS_NAME = "assistant_unread"
    private const val KEY_PREFIX = "unread_count_"

    fun getUnreadCount(context: Context, assistantConversationId: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("$KEY_PREFIX$assistantConversationId", 0)
    }

    fun incrementUnreadCount(context: Context, assistantConversationId: String, count: Int = 1) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("$KEY_PREFIX$assistantConversationId", 0)
        val newCount = currentCount + count
        prefs.edit().putInt("$KEY_PREFIX$assistantConversationId", newCount).apply()
        Log.d(TAG, "小助手未读消息数量增加: $assistantConversationId, $currentCount -> $newCount")
        notifyUnreadCountChanged(context)
    }

    fun clearUnreadCount(context: Context, assistantConversationId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("$KEY_PREFIX$assistantConversationId", 0).apply()
        Log.d(TAG, "小助手未读消息数量已清除: $assistantConversationId")
        notifyUnreadCountChanged(context)
    }

    private fun notifyUnreadCountChanged(context: Context) {
        try {
            val intent = Intent(ConversationListFragment.ACTION_UNREAD_COUNT_UPDATED)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "发送广播失败: ${e.message}", e)
        }
    }
}
