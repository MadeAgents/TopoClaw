package com.cloudcontrol.demo

/** 自定义小助手 need_execution / gui_execute_request 等：权限未就绪时暂存，授权后在 onResume 继续执行 */
internal data class PendingCustomAssistantExecution(
    val assistant: CustomAssistantManager.CustomAssistant,
    val query: String,
    val chatSummary: String?,
    /** 仅 gui_execute_request：恢复执行前写回 [ChatFragment.assistantGuiExecuteContext]；取消授权时用于回传错误 */
    val guiExecuteContext: AssistantGuiExecuteContext? = null,
)

/** 群组 @ TopoClaw上下文（用于将结果发送回群组） */
internal data class AssistantGroupContext(
    val groupId: String,
    val originalConversation: Conversation?,
    val originalQuery: String,
    val senderImei: String,
)

/** 群组管理小助手反馈上下文：子助手执行完成后，由群组管理者总结或继续调度 */
internal data class GroupManagerFeedbackContext(
    val userQuery: String,
    val groupManagerAssistant: CustomAssistantManager.CustomAssistant,
    val round: Int,
    val assistantsWithNames: List<Pair<String, String>>,
    val memberNames: List<String> = emptyList(),
    val senderName: String? = null,
)

/** PC 端执行指令上下文（用于将结果回传 PC） */
internal data class AssistantPcExecuteContext(
    val uuid: String,
    val messageId: String,
    val conversationId: String?,
)

/** GUI 执行上下文（用于 gui_execute_request 结果回传 Chat WebSocket） */
internal data class AssistantGuiExecuteContext(
    val requestId: String,
    val threadId: String? = null,
    val sendResult: (content: String?, error: String?) -> Unit,
)

/** 远程 @ 用户指令解析结果 */
internal data class RemoteCommand(
    val targetUsername: String,
    val command: String,
)
