package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * 会话列表未读与兜底未读的统一入口：好友 / 群 / 客服 / 自定义小助手走各自 Manager，
 * 其余会话（如端云「我的电脑」、内置小助手等）走 [SessionUnreadFallbackStore]。
 */
object ConversationSessionNotifier {
    private const val TAG = "ConversationSessionNotifier"

    fun getUnreadCountForList(context: Context, conversationId: String): Int {
        return when {
            conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE ->
                CustomerServiceUnreadManager.getUnreadCount(context)
            conversationId.startsWith("friend_") ->
                FriendUnreadManager.getUnreadCount(context, conversationId)
            conversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                conversationId.startsWith("group_") ->
                GroupUnreadManager.getUnreadCount(context, conversationId)
            CustomAssistantManager.isCustomAssistantId(conversationId) ->
                AssistantUnreadManager.getUnreadCount(context, conversationId)
            else ->
                SessionUnreadFallbackStore.getCount(context, conversationId)
        }
    }

    fun incrementUnread(context: Context, conversationId: String, count: Int = 1) {
        when {
            conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE ->
                CustomerServiceUnreadManager.incrementUnreadCount(context, count)
            conversationId.startsWith("friend_") ->
                FriendUnreadManager.incrementUnreadCount(context, conversationId, count)
            conversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                conversationId.startsWith("group_") ->
                GroupUnreadManager.incrementUnreadCount(context, conversationId, count)
            CustomAssistantManager.isCustomAssistantId(conversationId) ->
                AssistantUnreadManager.incrementUnreadCount(context, conversationId, count)
            else ->
                SessionUnreadFallbackStore.increment(context, conversationId, count)
        }
    }

    fun clearUnread(context: Context, conversationId: String) {
        when {
            conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE ->
                CustomerServiceUnreadManager.clearUnreadCount(context)
            conversationId.startsWith("friend_") ->
                FriendUnreadManager.clearUnreadCount(context, conversationId)
            conversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                conversationId.startsWith("group_") ->
                GroupUnreadManager.clearUnreadCount(context, conversationId)
            CustomAssistantManager.isCustomAssistantId(conversationId) ->
                AssistantUnreadManager.clearUnreadCount(context, conversationId)
            else ->
                SessionUnreadFallbackStore.clear(context, conversationId)
        }
    }

    /** 底部「聊天」角标：兜底会话未读之和 */
    fun sumFallbackUnreadForTotal(context: Context): Int =
        SessionUnreadFallbackStore.sumAll(context)

    private object SessionUnreadFallbackStore {
        private const val PREFS = "session_unread_fallback"
        private const val PREFIX = "c_"
        private const val LEGACY_ME_PREFS = "cross_device_me_unread"
        private const val LEGACY_ME_KEY = "unread_count"

        private fun key(conversationId: String) = "$PREFIX$conversationId"

        fun getCount(context: Context, conversationId: String): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var n = prefs.getInt(key(conversationId), 0)
            if (n == 0 && conversationId == ConversationListFragment.CONVERSATION_ID_ME) {
                val legacy = context.getSharedPreferences(LEGACY_ME_PREFS, Context.MODE_PRIVATE)
                    .getInt(LEGACY_ME_KEY, 0)
                if (legacy > 0) {
                    prefs.edit().putInt(key(conversationId), legacy).apply()
                    context.getSharedPreferences(LEGACY_ME_PREFS, Context.MODE_PRIVATE).edit()
                        .remove(LEGACY_ME_KEY).apply()
                    n = legacy
                    Log.d(TAG, "已从旧端云未读 prefs 迁移未读: $legacy")
                }
            }
            return n
        }

        fun increment(context: Context, conversationId: String, count: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val next = prefs.getInt(key(conversationId), 0) + count
            prefs.edit().putInt(key(conversationId), next).apply()
            notifyUnreadCountChanged(context)
        }

        fun clear(context: Context, conversationId: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(key(conversationId), 0).apply()
            notifyUnreadCountChanged(context)
        }

        fun sumAll(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var sum = 0
            for ((k, v) in prefs.all) {
                if (k.startsWith(PREFIX) && v is Number) sum += v.toInt()
            }
            if (sum == 0) {
                val legacy = context.getSharedPreferences(LEGACY_ME_PREFS, Context.MODE_PRIVATE)
                    .getInt(LEGACY_ME_KEY, 0)
                if (legacy > 0) sum += legacy
            }
            return sum
        }

        private fun notifyUnreadCountChanged(context: Context) {
            try {
                LocalBroadcastManager.getInstance(context).sendBroadcast(
                    Intent(ConversationListFragment.ACTION_UNREAD_COUNT_UPDATED)
                )
            } catch (e: Exception) {
                Log.e(TAG, "发送未读广播失败: ${e.message}", e)
            }
        }
    }
}
