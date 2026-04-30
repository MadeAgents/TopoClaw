package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * 好友未读消息管理器
 * 管理每个好友的未读消息数量
 */
object FriendUnreadManager {
    private const val TAG = "FriendUnreadManager"
    private const val PREFS_NAME = "friend_unread"
    private const val KEY_PREFIX = "unread_count_"
    
    /**
     * 获取指定好友的未读消息数量
     */
    fun getUnreadCount(context: Context, friendConversationId: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("$KEY_PREFIX$friendConversationId", 0)
    }
    
    /**
     * 增加指定好友的未读消息数量
     */
    fun incrementUnreadCount(context: Context, friendConversationId: String, count: Int = 1) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCount = prefs.getInt("$KEY_PREFIX$friendConversationId", 0)
        val newCount = currentCount + count
        prefs.edit().putInt("$KEY_PREFIX$friendConversationId", newCount).apply()
        Log.d(TAG, "好友未读消息数量增加: $friendConversationId, $currentCount -> $newCount")
        
        // 发送广播通知UI更新
        notifyUnreadCountChanged(context)
    }
    
    /**
     * 清除指定好友的未读消息数量
     */
    fun clearUnreadCount(context: Context, friendConversationId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("$KEY_PREFIX$friendConversationId", 0).apply()
        Log.d(TAG, "好友未读消息数量已清除: $friendConversationId")
        
        // 发送广播通知UI更新
        notifyUnreadCountChanged(context)
    }
    
    /**
     * 设置指定好友的未读消息数量
     */
    fun setUnreadCount(context: Context, friendConversationId: String, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt("$KEY_PREFIX$friendConversationId", count).apply()
        Log.d(TAG, "好友未读消息数量设置为: $friendConversationId, $count")
        
        // 发送广播通知UI更新
        notifyUnreadCountChanged(context)
    }
    
    /**
     * 发送广播通知未读消息数量变化
     */
    private fun notifyUnreadCountChanged(context: Context) {
        try {
            val intent = Intent(ConversationListFragment.ACTION_UNREAD_COUNT_UPDATED)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            Log.d(TAG, "已发送好友未读消息更新广播")
        } catch (e: Exception) {
            Log.e(TAG, "发送广播失败: ${e.message}", e)
        }
    }
}

