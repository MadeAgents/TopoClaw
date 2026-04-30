package com.cloudcontrol.demo

import android.util.Log

/**
 * conversationId 校验与规范化（与 ChatFragment 原逻辑一致）
 */
object ConversationIdNormalizer {

    fun normalize(conversationId: String?): String {
        if (conversationId.isNullOrBlank()) {
            Log.w(ChatConstants.TAG, "conversationId 为空或空白，使用 default")
            return "default"
        }

        val normalized = conversationId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val maxLength = 100
        val finalId = if (normalized.length > maxLength) {
            Log.w(ChatConstants.TAG, "conversationId 过长（${normalized.length}），截断到 $maxLength")
            normalized.take(maxLength)
        } else {
            normalized
        }

        if (finalId != conversationId) {
            Log.d(ChatConstants.TAG, "conversationId 已规范化：$conversationId -> $finalId")
        }

        return finalId
    }
}
