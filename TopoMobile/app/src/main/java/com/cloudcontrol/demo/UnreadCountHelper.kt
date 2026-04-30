package com.cloudcontrol.demo

import android.content.Context
import android.util.Log

/**
 * 未读消息数量辅助工具
 * 用于计算所有未读消息的总数
 */
object UnreadCountHelper {
    private const val TAG = "UnreadCountHelper"
    
    /**
     * 获取所有未读消息的总数
     * 包括：客服未读消息 + 所有好友未读消息 + 所有群组未读消息
     */
    fun getTotalUnreadCount(context: Context): Int {
        var totalCount = 0
        
        try {
            // 1. 客服未读消息
            totalCount += CustomerServiceUnreadManager.getUnreadCount(context)
            
            // 2. 所有好友未读消息
            val friends = FriendManager.getFriends(context)
            friends.forEach { friend ->
                if (friend.status == "accepted") {
                    val friendConversationId = "friend_${friend.imei}"
                    totalCount += FriendUnreadManager.getUnreadCount(context, friendConversationId)
                }
            }
            
            // 3. 所有群组未读消息
            // 先添加默认群组
            totalCount += GroupUnreadManager.getUnreadCount(context, ConversationListFragment.CONVERSATION_ID_GROUP)
            
            // 再添加其他群组
            val groups = GroupManager.getGroups(context)
            groups.forEach { group ->
                val groupConversationId = "group_${group.groupId}"
                totalCount += GroupUnreadManager.getUnreadCount(context, groupConversationId)
            }
            
            // 4. 所有自定义小助手未读消息
            CustomAssistantManager.getVisibleAll(context).forEach { assistant ->
                totalCount += AssistantUnreadManager.getUnreadCount(context, assistant.id)
            }
            
            // 5. 内置会话等（端云、TopoClaw等）兜底未读
            totalCount += ConversationSessionNotifier.sumFallbackUnreadForTotal(context)
            
            Log.d(TAG, "总未读消息数: $totalCount")
        } catch (e: Exception) {
            Log.e(TAG, "计算总未读消息数失败: ${e.message}", e)
        }
        
        return totalCount
    }
    
    /**
     * 格式化未读消息数量显示文本
     * 最大显示99，超过显示"99+"
     */
    fun formatUnreadCount(count: Int): String {
        return when {
            count <= 0 -> ""
            count <= 99 -> count.toString()
            else -> "99+"
        }
    }
}

