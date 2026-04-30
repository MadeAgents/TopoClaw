package com.cloudcontrol.demo

/**
 * 视频分析结果数据类
 */
data class VideoAnalysisResult(
    val formattedMessage: String,           // 格式化后的消息文本
    val userPurpose: String?,               // 用户操作目的
    val actionSteps: List<String>,          // 操作步骤列表
    val originalVideoAnalysis: List<VideoAnalysisItem>  // 原始数据
)

/**
 * @提及候选对象数据类
 */
data class MentionCandidate(
    val imei: String,
    val nickname: String?,
    val avatar: String?,
    val isAssistant: Boolean = false  // 是否为TopoClaw
)

/**
 * 聊天记录数据类（用于保存状态）
 * sender, message, type, timestamp, uuid，type可以是 "user", "system", "complete", "answer"
 */
data class ChatMessage(
    val sender: String,
    val message: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val uuid: String = "",
    val imagePath: String? = null,  // 图片文件路径（用于图片消息）
    val skillId: String? = null,     // 技能ID（用于技能卡消息）
    val senderImei: String? = null   // 发送者IMEI（用于接收到的图片消息）
)
