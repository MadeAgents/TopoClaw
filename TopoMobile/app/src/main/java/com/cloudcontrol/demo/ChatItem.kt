package com.cloudcontrol.demo

/**
 * RecyclerView Item类型
 */
sealed class ChatItem {
    data class SystemMessagesHeader(
        val count: Int,
        val isExpanded: Boolean
    ) : ChatItem()

    data class SystemMessage(
        val sender: String,
        val message: String
    ) : ChatItem()

    data class TimeStamp(
        val timestamp: Long
    ) : ChatItem()

    data class Message(
        val sender: String,
        val message: String,
        val isUserMessage: Boolean,
        val isComplete: Boolean,
        val isAnswer: Boolean,
        val timestamp: Long,
        val recommendations: List<String>? = null,
        val senderImei: String? = null
    ) : ChatItem()

    data class ImageMessage(
        val imagePath: String,
        val query: String,
        val timestamp: Long,
        val isUserMessage: Boolean = true,  // 默认为用户消息（右侧对齐）
        val senderName: String? = null  // 接收到的消息的发送者名称
    ) : ChatItem()

    // 接收到的图片消息（左侧对齐）
    data class ReceivedImageMessage(
        val imagePath: String,
        val query: String,
        val senderName: String,
        val timestamp: Long,
        val senderImei: String? = null  // 发送者IMEI，用于获取头像
    ) : ChatItem()

    data class VideoAnalysisMessage(
        val analysisResult: VideoAnalysisResult,
        val timestamp: Long
    ) : ChatItem()

    data class SkillMessage(
        val skill: Skill,
        val sender: String,
        val timestamp: Long,
        val isUserMessage: Boolean = true,  // 默认为用户消息（右侧对齐）
        val senderImei: String? = null  // 发送者IMEI，用于获取头像
    ) : ChatItem()

    data class Recommendations(
        val recommendations: List<String>,
        val timestamp: Long
    ) : ChatItem()

    data class FeedbackRequest(
        val timestamp: Long,
        val taskUuid: String = "",
        val taskQuery: String = "",
        val isException: Boolean = false  // 是否为异常情况（用户中断或达到最大限制）
    ) : ChatItem()

    data class NewTopicHint(
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatItem()

    data class ActionButtons(
        val message: String,  // 消息内容（用于复制）
        val query: String,    // 原始query（用于重新执行）
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatItem()

    /**
     * 小助手思考中状态（非普通消息气泡）
     */
    data class AssistantThinking(
        val sender: String,
        val text: String = "正在思考",
        val details: List<String> = emptyList(),
        val isExpanded: Boolean = true,
        val isCompleted: Boolean = false,
        val assistantId: String? = null,
        val avatarBase64: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val key: String = "assistant_thinking"
    ) : ChatItem()

    /**
     * 获取唯一标识符，用于 DiffUtil 比较
     */
    fun getUniqueId(): String {
        return when (this) {
            is SystemMessagesHeader -> "header_${count}_${isExpanded}"
            is SystemMessage -> "system_${sender}_${message.hashCode()}"
            is TimeStamp -> "timestamp_$timestamp"
            is Message -> "message_$timestamp"
            is ImageMessage -> "image_$timestamp"
            is ReceivedImageMessage -> "received_image_$timestamp"
            is VideoAnalysisMessage -> "video_$timestamp"
            is SkillMessage -> "skill_${skill.id}_$timestamp"
            is Recommendations -> "recommendations_$timestamp"
            is FeedbackRequest -> "feedback_$timestamp"
            is NewTopicHint -> "new_topic_$timestamp"
            is ActionButtons -> "action_buttons_$timestamp"
            is AssistantThinking -> "assistant_thinking_${key}"
        }
    }
}
