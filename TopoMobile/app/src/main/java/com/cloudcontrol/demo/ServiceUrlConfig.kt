package com.cloudcontrol.demo

import android.content.Context

object ServiceUrlConfig {
    private const val PREFS_NAME = "app_prefs"

    private const val SAFE_BASE_URL = "https://example.invalid/"

    private fun normalizeBase(url: String): String {
        val trimmed = url.trim()
        val candidate = if (trimmed.isEmpty()) SAFE_BASE_URL else trimmed
        return if (candidate.endsWith("/")) candidate else "$candidate/"
    }

    private fun withPath(base: String, path: String): String {
        val normalizedBase = normalizeBase(base)
        val normalizedPath = path.trim().trimStart('/')
        return "$normalizedBase$normalizedPath"
    }

    val DEFAULT_SERVER_URL: String =
        normalizeBase(BuildConfig.MOBILE_AGENT_BASE_URL)
    val DEFAULT_SKILL_LEARNING_URL: String =
        normalize(
            BuildConfig.MOBILE_AGENT_SKILL_LEARNING_URL,
            withPath(DEFAULT_SERVER_URL, "v2/")
        )
    val DEFAULT_CUSTOMER_SERVICE_URL: String =
        normalize(
            BuildConfig.MOBILE_AGENT_CUSTOMER_SERVICE_URL,
            withPath(DEFAULT_SERVER_URL, "v4/")
        )
    val DEFAULT_CHAT_ASSISTANT_URL: String =
        normalize(
            BuildConfig.MOBILE_AGENT_CHAT_ASSISTANT_URL,
            withPath(DEFAULT_SERVER_URL, "v10/")
        )
    val DEFAULT_SKILL_COMMUNITY_URL: String =
        normalize(
            BuildConfig.MOBILE_AGENT_SKILL_COMMUNITY_URL,
            withPath(DEFAULT_SERVER_URL, "v9/")
        )

    private const val KEY_CUSTOMER_SERVICE_URL_LEGACY = "customer_service_url"
    private const val KEY_SKILL_COMMUNITY_URL = "skill_community_url"

    private fun normalize(url: String, defaultUrl: String): String {
        val trimmed = url.trim()
        val candidate = if (trimmed.isEmpty()) defaultUrl else trimmed
        return if (candidate.endsWith("/")) candidate else "$candidate/"
    }

    fun getCustomerServiceUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_${ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE}"
        val specific = prefs.getString(specificKey, null)
        val legacy = prefs.getString(KEY_CUSTOMER_SERVICE_URL_LEGACY, null)
        return normalize(specific ?: legacy ?: DEFAULT_CUSTOMER_SERVICE_URL, DEFAULT_CUSTOMER_SERVICE_URL)
    }

    fun setCustomerServiceUrl(context: Context, url: String) {
        val normalized = normalize(url, DEFAULT_CUSTOMER_SERVICE_URL)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_${ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE}"
        prefs.edit()
            .putString(specificKey, normalized)
            .putString(KEY_CUSTOMER_SERVICE_URL_LEGACY, normalized)
            .apply()
    }

    fun getChatAssistantUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_${ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT}"
        val specific = prefs.getString(specificKey, null)
        return normalize(specific ?: DEFAULT_CHAT_ASSISTANT_URL, DEFAULT_CHAT_ASSISTANT_URL)
    }

    fun setChatAssistantUrl(context: Context, url: String) {
        val normalized = normalize(url, DEFAULT_CHAT_ASSISTANT_URL)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_${ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT}"
        prefs.edit().putString(specificKey, normalized).apply()
    }

    fun getSkillCommunityUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SKILL_COMMUNITY_URL, null)
        return normalize(saved ?: DEFAULT_SKILL_COMMUNITY_URL, DEFAULT_SKILL_COMMUNITY_URL)
    }

    fun setSkillCommunityUrl(context: Context, url: String) {
        val normalized = normalize(url, DEFAULT_SKILL_COMMUNITY_URL)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SKILL_COMMUNITY_URL, normalized).apply()
    }
}
