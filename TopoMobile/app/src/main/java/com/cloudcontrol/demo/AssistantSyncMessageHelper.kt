package com.cloudcontrol.demo

import android.content.Context

/**
 * PC/跨端 [assistant_sync_message] 的展示与匹配逻辑。
 * 自定义小助手名称可能不含「助手」（如英文昵称），不能仅用 sender.contains("助手") 判断是否为助手回复。
 */
object AssistantSyncMessageHelper {

    /**
     * 是否应按助手气泡（answer）保存/展示：与「系统」提示区分。
     */
    fun isAnswerSyncMessage(sender: String, targetConvId: String, context: Context): Boolean {
        if (sender == "系统") return false
        if (sender == "小助手" || sender.contains("助手")) return true
        if (CustomAssistantManager.isCustomAssistantId(targetConvId)) {
            val assistantName = CustomAssistantManager.getById(context, targetConvId)?.name
            if (assistantName != null && sender == assistantName) return true
            // PC 端同步的助手显示名可能与本地一致；非系统、非用户侧即视为助手回复
            return sender != "我" && sender != "用户"
        }
        return false
    }

    /**
     * 当前是否正在查看该同步消息所属的 session。
     * [currentSessionIdForMultiSession] 在初始化完成前可能为 null，若要求严格相等会误走「仅落盘不刷新 UI」分支。
     */
    fun isViewingAssistantSyncSession(
        targetSessionId: String?,
        currentSessionIdForMultiSession: String?
    ): Boolean {
        return targetSessionId == null ||
            currentSessionIdForMultiSession == null ||
            currentSessionIdForMultiSession == targetSessionId
    }

    /**
     * 为多 session 自定义小助手解析正确的 prefs 存储 key。
     * 当 PC 同步消息不含 session_id（targetSessionId == null）且目标是多 session 助手时，
     * 查找最新活跃 session 并使用带后缀的 key，避免存入裸 key 导致加载时找不到。
     */
    fun resolveAssistantMsgKey(
        context: Context,
        targetConvId: String,
        targetSessionId: String?
    ): String {
        if (targetSessionId != null) {
            return "chat_messages_${targetConvId}_$targetSessionId"
        }
        if (CustomAssistantManager.isCustomAssistantId(targetConvId)) {
            val assistant = CustomAssistantManager.getById(context, targetConvId)
            if (assistant != null && assistant.multiSession) {
                val sessions = SessionStorage.loadSessions(context, targetConvId)
                if (sessions.isNotEmpty()) {
                    val preferredSessionId =
                        pickSessionIdPreferringNonEmptyPrefs(context, targetConvId, sessions)
                            ?: sessions.maxByOrNull { it.createdAt }?.id
                    if (preferredSessionId != null) {
                        return "chat_messages_${targetConvId}_$preferredSessionId"
                    }
                }
            }
        }
        return "chat_messages_$targetConvId"
    }

    private fun pickSessionIdPreferringNonEmptyPrefs(
        context: Context,
        conversationId: String,
        sessions: List<SessionStorage.ChatSession>
    ): String? {
        if (sessions.isEmpty()) return null
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val prefix = "chat_messages_${conversationId}_"
        val withData = sessions.filter { session ->
            val json = prefs.getString("$prefix${session.id}", null)
            json != null && json.isNotBlank() && json != "[]"
        }
        return when {
            withData.size == 1 -> withData.first().id
            withData.size > 1 -> withData.maxByOrNull { it.createdAt }?.id
            else -> null
        }
    }
}
