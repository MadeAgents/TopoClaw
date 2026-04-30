package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 人工客服未读消息管理器
 * 管理未读消息数量、状态和离线接收的消息
 */
object CustomerServiceUnreadManager {
    private const val TAG = "CustomerServiceUnreadManager"
    private const val PREFS_NAME = "customer_service_unread"
    private const val KEY_UNREAD_COUNT = "unread_count"
    private const val KEY_PENDING_MESSAGES = "pending_messages"
    
    /**
     * 获取未读消息数量
     */
    fun getUnreadCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_UNREAD_COUNT, 0)
    }
    
    /**
     * 增加未读消息数量
     */
    fun incrementUnreadCount(context: Context, count: Int = 1) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCount = prefs.getInt(KEY_UNREAD_COUNT, 0)
        val newCount = currentCount + count
        prefs.edit().putInt(KEY_UNREAD_COUNT, newCount).apply()
        Log.d(TAG, "未读消息数量增加: $currentCount -> $newCount")
        
        // 发送广播通知UI更新
        notifyUnreadCountChanged(context)
    }
    
    /**
     * 清除未读消息数量
     */
    fun clearUnreadCount(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_UNREAD_COUNT, 0).apply()
        Log.d(TAG, "未读消息数量已清除")
        
        // 发送广播通知UI更新
        notifyUnreadCountChanged(context)
    }
    
    /**
     * 设置未读消息数量
     */
    fun setUnreadCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_UNREAD_COUNT, count).apply()
        Log.d(TAG, "未读消息数量设置为: $count")
        
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
            Log.d(TAG, "已发送未读消息更新广播")
        } catch (e: Exception) {
            Log.e(TAG, "发送广播失败: ${e.message}", e)
        }
    }
    
    /**
     * 保存待处理的消息（当不在聊天页面时收到的消息）
     * 这些消息会在用户进入聊天页面时显示
     */
    fun addPendingMessage(context: Context, sender: String, content: String) {
        try {
            Log.d(TAG, "========== 开始保存待处理消息 ==========")
            Log.d(TAG, "sender=$sender, content=${content.take(100)}")
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val messagesJson = prefs.getString(KEY_PENDING_MESSAGES, "[]")
            val messagesArray = JSONArray(messagesJson)
            Log.d(TAG, "当前待处理消息数量: ${messagesArray.length()}")
            
            val messageObj = JSONObject().apply {
                put("sender", sender)
                put("content", content)
                put("timestamp", System.currentTimeMillis())
            }
            messagesArray.put(messageObj)
            
            prefs.edit().putString(KEY_PENDING_MESSAGES, messagesArray.toString()).apply()
            Log.d(TAG, "已保存待处理消息，新数量: ${messagesArray.length()}")
            Log.d(TAG, "========== 待处理消息保存完成 ==========")
        } catch (e: Exception) {
            Log.e(TAG, "保存待处理消息失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 获取并清除所有待处理的消息
     * @return 待处理消息列表，每个元素是 Triple<sender, content, timestamp>
     */
    fun getAndClearPendingMessages(context: Context): List<Triple<String, String, Long>> {
        val messages = mutableListOf<Triple<String, String, Long>>()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val messagesJson = prefs.getString(KEY_PENDING_MESSAGES, "[]")
            val messagesArray = JSONArray(messagesJson)
            
            for (i in 0 until messagesArray.length()) {
                val msgObj = messagesArray.getJSONObject(i)
                val sender = msgObj.getString("sender")
                val content = msgObj.getString("content")
                val timestamp = msgObj.optLong("timestamp", System.currentTimeMillis())
                messages.add(Triple(sender, content, timestamp))
            }
            
            // 清除已获取的消息
            prefs.edit().remove(KEY_PENDING_MESSAGES).apply()
            Log.d(TAG, "已获取并清除 ${messages.size} 条待处理消息")
        } catch (e: Exception) {
            Log.e(TAG, "获取待处理消息失败: ${e.message}", e)
        }
        return messages
    }
    
    /**
     * 检查是否有待处理的消息
     */
    fun hasPendingMessages(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val messagesJson = prefs.getString(KEY_PENDING_MESSAGES, "[]")
            val messagesArray = JSONArray(messagesJson)
            messagesArray.length() > 0
        } catch (e: Exception) {
            false
        }
    }
}

