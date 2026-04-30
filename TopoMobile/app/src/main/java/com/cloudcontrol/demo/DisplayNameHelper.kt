package com.cloudcontrol.demo

import android.content.Context

/**
 * 对话/助手名称的显示名称转换
 * 将内部固定的中文名称映射为当前语言下的显示文本
 */
object DisplayNameHelper {

    /**
     * 获取本地化后的显示名称
     * @param context 用于 getString
     * @param internalName 内部存储的名称（如 TopoClaw 历史名 "自动执行小助手"、"好友群"）
     * @return 本地化后的显示名称，未知名称返回原样
     */
    fun getDisplayName(context: Context, internalName: String?): String {
        if (internalName.isNullOrBlank()) return ""
        return when (internalName) {
            ChatConstants.ASSISTANT_LEGACY_INTERNAL_NAME,
            ChatConstants.ASSISTANT_DISPLAY_NAME -> context.getString(R.string.auto_execute_assistant)
            ChatConstants.ASSISTANT_LEGACY_TOPOCLAW_NAME -> context.getString(R.string.topoclaw_assistant)
            "技能学习小助手" -> context.getString(R.string.skill_learn_assistant)
            "聊天小助手" -> context.getString(R.string.chat_assistant)
            "人工客服" -> context.getString(R.string.customer_service)
            "好友群" -> context.getString(R.string.friend_group)
            else -> internalName
        }
    }
}
