package com.cloudcontrol.demo

internal object ChatResponseDisplayHelper {

    fun isMaxStepTriggeredReason(reason: String?): Boolean {
        val r = reason ?: ""
        return r.contains("执行步数已达到最大限制") || r.contains("超过最大执行步数限制")
    }

    /**
     * complete 动作的展示文本。当 next_action 开关打开时，优先使用 params；
     * 否则使用 thought/reason。
     */
    fun getCompleteDisplayText(
        chatResponse: ChatResponse,
        useNextActionApi: Boolean,
        isMaxStepTriggered: Boolean = false,
    ): String {
        val params = chatResponse.params?.takeIf { it.isNotBlank() }
        val thought = chatResponse.thought?.takeIf { it.isNotBlank() }
        val reason = chatResponse.reason?.takeIf { it.isNotBlank() }
        return when {
            useNextActionApi && params != null -> params
            isMaxStepTriggered && reason != null -> reason + (if (thought != null) "\n$thought" else "")
            thought != null -> thought
            reason != null -> reason
            else -> "任务已完成"
        }
    }

    fun getCompleteResultTextForOverlay(chatResponse: ChatResponse, useNextActionApi: Boolean): String {
        val text = getCompleteDisplayText(
            chatResponse,
            useNextActionApi,
            isMaxStepTriggered = isMaxStepTriggeredReason(chatResponse.reason),
        )
        return text.ifBlank { "任务已完成" }
    }

    fun hasCompleteAnswerContent(chatResponse: ChatResponse, useNextActionApi: Boolean): Boolean {
        if (useNextActionApi && !chatResponse.params.isNullOrBlank()) return true
        val reason = chatResponse.reason ?: ""
        val isMaxStepTriggered = isMaxStepTriggeredReason(chatResponse.reason)
        return (isMaxStepTriggered && (reason.isNotBlank() || !chatResponse.thought.isNullOrBlank())) ||
            (!isMaxStepTriggered && !chatResponse.thought.isNullOrBlank())
    }
}
