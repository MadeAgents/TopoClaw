package com.cloudcontrol.demo

import android.content.Context

/**
 * 兼容入口：好友离线文本落盘已统一至 [FriendChatMessagePrefsStore]（全局锁 + 合并）。
 */
object OfflineFriendMessagePersistence {

    fun appendTextFriendMessageIfNew(
        context: Context,
        senderImei: String,
        senderName: String,
        content: String,
        timestamp: Long,
        skillId: String?
    ): Boolean = FriendChatMessagePrefsStore.appendTextFriendMessageIfNew(
        context, senderImei, senderName, content, timestamp, skillId
    )
}
