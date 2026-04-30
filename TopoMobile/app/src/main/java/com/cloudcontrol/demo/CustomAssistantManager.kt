package com.cloudcontrol.demo

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID
import kotlin.random.Random

/**
 * 自定义小助手管理
 * 协议：assistant://add?type={type}&url={base_url}&name={name}
 * type 支持：execution | chat（旧格式）或 execution_mobile,execution_pc,chat（新格式，逗号分隔）
 */
object CustomAssistantManager {
    private const val TAG = "CustomAssistantManager"
    private const val PREFS_NAME = "conversations"
    private const val KEY_CUSTOM_ASSISTANTS = "custom_assistants"
    const val PREFIX_ID = "custom_"
    const val DEFAULT_TOPOCLAW_ASSISTANT_ID = "custom_topoclaw"
    const val DEFAULT_GROUP_MANAGER_ASSISTANT_ID = "custom_groupmanager"
    const val TYPE_EXECUTION = "execution"
    const val TYPE_CHAT = "chat"
    const val CAP_EXECUTION_MOBILE = "execution_mobile"
    const val CAP_EXECUTION_PC = "execution_pc"
    const val CAP_CHAT = "chat"
    /** 群组管理者：群内未 @ 任何小助手时，消息统一由此助手回复 */
    const val CAP_GROUP_MANAGER = "group_manager"
    private const val TOPOCLAW_RELAY_BASE_URL = "topoclaw://relay"

    data class CustomAssistant(
        val id: String,
        val name: String,
        val intro: String = "",
        val baseUrl: String,
        val capabilities: List<String> = emptyList(),
        val avatar: String? = null,
        val multiSession: Boolean = true
    ) {
        /** 兼容旧格式：type 为 execution 或 chat */
        val type: String
            get() = when {
                capabilities.contains(CAP_EXECUTION_MOBILE) || capabilities.contains(CAP_EXECUTION_PC) -> TYPE_EXECUTION
                capabilities.contains(CAP_CHAT) -> TYPE_CHAT
                else -> TYPE_CHAT
            }

        fun hasExecutionMobile(): Boolean = capabilities.contains(CAP_EXECUTION_MOBILE)
        fun hasExecutionPc(): Boolean = capabilities.contains(CAP_EXECUTION_PC)
        fun hasChat(): Boolean = capabilities.contains(CAP_CHAT)
        /** 是否为群组管理者（群内未 @ 时统一由此助手回复，需同时具备聊天能力） */
        fun hasGroupManager(): Boolean = capabilities.contains(CAP_GROUP_MANAGER) && hasChat()

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("intro", intro)
            put("baseUrl", baseUrl)
            put("capabilities", JSONArray(capabilities))
            put("avatar", avatar ?: "")
            put("multiSession", multiSession)
        }
        companion object {
            fun fromJson(obj: JSONObject): CustomAssistant {
                val capsRaw = obj.optJSONArray("capabilities")
                val capabilities = if (capsRaw != null) {
                    (0 until capsRaw.length()).mapNotNull { capsRaw.optString(it, "").takeIf { s -> s.isNotBlank() } }
                } else {
                    val legacyType = obj.optString("type", TYPE_CHAT)
                    when (legacyType) {
                        TYPE_EXECUTION -> listOf(CAP_EXECUTION_MOBILE)
                        TYPE_CHAT -> listOf(CAP_CHAT)
                        else -> listOf(CAP_CHAT)
                    }
                }
                return CustomAssistant(
                    id = obj.optString("id", ""),
                    name = obj.optString("name", "小助手"),
                    intro = obj.optString("intro", ""),
                    baseUrl = obj.optString("baseUrl", ""),
                    capabilities = capabilities.ifEmpty { listOf(CAP_CHAT) },
                    avatar = obj.optString("avatar", "").takeIf { it.isNotBlank() },
                    multiSession = obj.optBoolean("multiSession", true)
                )
            }
        }
    }

    /**
     * 构建分享链接
     */
    fun buildAssistantUrl(name: String, baseUrl: String, capabilities: List<String>): String {
        val typeParam = when {
            capabilities.isEmpty() -> CAP_CHAT
            capabilities.size == 1 -> capabilities.first()
            else -> capabilities.joinToString(",")
        }
        val urlEncoded = java.net.URLEncoder.encode(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/", "UTF-8")
        val nameEncoded = java.net.URLEncoder.encode(name, "UTF-8")
        return "assistant://add?type=$typeParam&url=$urlEncoded&name=$nameEncoded"
    }

    fun buildAssistantUrl(
        name: String,
        baseUrl: String,
        capabilities: List<String>,
        assistantId: String? = null
    ): String {
        val base = buildAssistantUrl(name, baseUrl, capabilities)
        val sid = assistantId?.trim().orEmpty()
        return if (sid.isNotEmpty()) "$base&id=${java.net.URLEncoder.encode(sid, "UTF-8")}" else base
    }

    /**
     * 解析 assistant://add?type=...&url=...&name=...
     * @return CustomAssistant 或 null（解析失败）
     */
    @JvmOverloads
    fun parseAssistantUrl(uriString: String, context: Context? = null): CustomAssistant? {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "assistant" || uri.host != "add") return null
            val typeParam = uri.getQueryParameter("type") ?: return null
            val urlEncoded = uri.getQueryParameter("url") ?: return null
            val baseUrl = URLDecoder.decode(urlEncoded, "UTF-8")
                .let { if (it.endsWith("/")) it else "$it/" }
            val name = uri.getQueryParameter("name")?.let { URLDecoder.decode(it, "UTF-8") }
                ?: "小助手"
            val capabilities = typeParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .let { list ->
                    if (list.isEmpty()) listOf(CAP_CHAT)
                    else list.map { cap ->
                        when (cap) {
                            TYPE_EXECUTION -> CAP_EXECUTION_MOBILE
                            TYPE_CHAT -> CAP_CHAT
                            else -> cap
                        }
                    }.distinct()
                }
            val idFromLink = uri.getQueryParameter("id")?.trim().orEmpty()
            val id = if (idFromLink.isNotEmpty()) idFromLink else buildCustomAssistantId(context)
            CustomAssistant(id = id, name = name, baseUrl = baseUrl, capabilities = capabilities)
        } catch (e: Exception) {
            Log.e(TAG, "解析小助手链接失败: $uriString", e)
            null
        }
    }

    private fun safeImeiToken(context: Context?): String {
        val imei = try {
            if (context != null) ProfileManager.getOrGenerateImei(context) else ""
        } catch (_: Exception) {
            ""
        }
        return imei.trim().lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "imei" }
    }

    private fun randomDigits(n: Int = 8): String {
        val sb = StringBuilder()
        repeat(maxOf(1, n)) { sb.append(Random.nextInt(0, 10)) }
        return sb.toString()
    }

    @JvmOverloads
    fun buildCustomAssistantId(context: Context? = null, nowMs: Long = System.currentTimeMillis()): String {
        return "${safeImeiToken(context)}_${nowMs}_${randomDigits(8)}"
    }

    fun getAll(context: Context): List<CustomAssistant> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_ASSISTANTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CustomAssistant.fromJson(it) }
                    ?.takeIf { it.baseUrl.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载自定义小助手失败", e)
            emptyList()
        }
    }

    private fun normalizeBaseUrl(url: String): String =
        url.trim().lowercase().trimEnd('/')

    /** 内部 relay 助手仅用于路由，不在 APK 前端列表中展示。 */
    fun isHiddenRelayAssistant(assistant: CustomAssistant): Boolean =
        normalizeBaseUrl(assistant.baseUrl) == TOPOCLAW_RELAY_BASE_URL

    /** 面向界面的可见小助手列表（隐藏 topoclaw://relay）。 */
    fun getVisibleAll(context: Context): List<CustomAssistant> =
        getAll(context).filterNot { isHiddenRelayAssistant(it) }

    fun add(context: Context, assistant: CustomAssistant) {
        val list = getAll(context).toMutableList()
        if (list.any { it.id == assistant.id }) return
        list.add(assistant)
        save(context, list)
    }

    fun remove(context: Context, id: String) {
        val list = getAll(context).filter { it.id != id }
        save(context, list)
    }

    /** 用云侧列表替换本地列表（端云同步拉取后使用） */
    fun replaceAll(context: Context, list: List<CustomAssistant>) {
        save(context, list.filter { it.baseUrl.isNotBlank() })
    }

    fun getById(context: Context, id: String): CustomAssistant? =
        getAll(context).find { it.id == id }

    private fun save(context: Context, list: List<CustomAssistant>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_ASSISTANTS, arr.toString())
            .apply()
    }

    fun isCustomAssistantId(id: String?): Boolean =
        id != null && id.startsWith(PREFIX_ID)

    /**
     * 转为 API 使用的 CustomAssistantItem（发布到广场、同步云端时使用）
     */
    fun toApiItem(a: CustomAssistant): com.cloudcontrol.demo.CustomAssistantItem {
        return com.cloudcontrol.demo.CustomAssistantItem(
            id = a.id,
            name = a.name,
            intro = a.intro.ifEmpty { null },
            baseUrl = a.baseUrl,
            capabilities = a.capabilities.ifEmpty { null },
            avatar = a.avatar,
            multiSessionEnabled = a.multiSession
        )
    }

    /**
     * 从广场/API 返回的 CustomAssistantItem 转为本地 CustomAssistant
     */
    fun fromApiItem(item: com.cloudcontrol.demo.CustomAssistantItem): CustomAssistant {
        return CustomAssistant(
            id = item.id,
            name = item.name,
            intro = item.intro ?: "",
            baseUrl = item.baseUrl,
            capabilities = item.capabilities ?: listOf(CAP_CHAT),
            avatar = item.avatar,
            multiSession = item.multiSessionEnabled ?: true
        )
    }

    /**
     * 从广场列表项 PlazaAssistantItem 转为本地 CustomAssistant（添加时使用）
     */
    fun fromPlazaItem(item: com.cloudcontrol.demo.PlazaAssistantItem): CustomAssistant {
        return CustomAssistant(
            id = item.id,
            name = item.name,
            intro = item.intro ?: "",
            baseUrl = item.baseUrl,
            capabilities = item.capabilities ?: listOf(CAP_CHAT),
            avatar = item.avatar,
            multiSession = item.multiSessionEnabled ?: true
        )
    }

    /** 将已有小助手设为/取消群组管理者（需具备聊天能力） */
    fun setAssistantGroupManager(context: Context, id: String, enabled: Boolean): Boolean {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val a = list[idx]
        val caps = a.capabilities.toMutableList()
        val hasGroup = caps.contains(CAP_GROUP_MANAGER)
        if (enabled) {
            if (!caps.contains(CAP_CHAT)) caps.add(CAP_CHAT)
            if (!hasGroup) caps.add(CAP_GROUP_MANAGER)
        } else {
            if (hasGroup) caps.remove(CAP_GROUP_MANAGER)
        }
        list[idx] = a.copy(capabilities = caps)
        save(context, list)
        return true
    }

    /** 内置小助手 id -> 简介（无自定义 intro 时使用） */
    private val BUILTIN_ASSISTANT_INTROS = mapOf(
        "assistant" to "支持手机端自动化任务，如打开应用、操作界面等",
        "skill_learning" to "负责记录和学习技能",
        "chat_assistant" to "支持对话聊天"
    )

    /**
     * 为群组管理者构建群内小助手上下文
     * 格式：【群组小助手列表】当前群组内有以下小助手，请根据用户问题推荐合适的小助手：\n- 名称：简介\n\n
     */
    fun buildGroupAssistantContext(context: Context, assistants: List<Pair<String, String>>): String {
        if (assistants.isEmpty()) return ""
        val list = assistants.map { (id, name) ->
            val custom = getById(context, id)
            val intro = custom?.intro?.trim()?.takeIf { it.isNotEmpty() }
                ?: BUILTIN_ASSISTANT_INTROS[id]
                ?: ""
            if (intro.isNotEmpty()) "- $name：$intro" else "- $name"
        }
        return "【群组小助手列表】当前群组内有以下小助手，请根据用户问题推荐合适的小助手：\n${list.joinToString("\n")}\n\n"
    }

    /**
     * 为群组管理者构建群成员显示名上下文（与 [buildGroupAssistantContext] 拼接使用）
     */
    fun buildGroupMembersContext(memberNames: List<String>): String {
        if (memberNames.isEmpty()) return ""
        return "【群成员】${memberNames.joinToString("、")}\n\n"
    }

    /**
     * 解析群组管理者回复中的 @小助手名 指令，用于自动触发对应小助手执行
     * 返回首个匹配的 Pair(assistantId, command)，若无则 null
     * 支持内置「TopoClaw」（id=assistant）
     */
    fun parseGroupManagerMention(
        context: Context,
        reply: String,
        assistants: List<Pair<String, String>>
    ): Pair<String, String>? {
        if (reply.isBlank()) return null
        val text = reply.trim()
        val extended = assistants.toMutableList()
        if (!extended.any { it.first == ConversationListFragment.CONVERSATION_ID_ASSISTANT }) {
            extended.add(ConversationListFragment.CONVERSATION_ID_ASSISTANT to ChatConstants.ASSISTANT_DISPLAY_NAME)
        }
        val sorted = extended.sortedByDescending { it.second.length }
        for ((aid, name) in sorted) {
            val atName = "@$name"
            if (!text.contains(atName)) continue
            val startIdx = text.indexOf(atName)
            val afterAt = text.substring(startIdx + atName.length).trimStart()
            val sepRegex = Regex("^[\\s,，：:、]+")
            val cmd = afterAt.replace(sepRegex, "").trim()
            if (cmd.isNotEmpty()) return aid to cmd
        }
        return null
    }

    /** 群组管理小助手最大跟进轮次（与 PC 侧一致） */
    const val MAX_GROUP_MANAGER_FOLLOW_UP_ROUNDS = 100

    /**
     * 构建执行结果反馈消息，供群组管理小助手判断任务完成情况并决定总结或继续调度
     * 格式与 PC 侧一致，以【执行结果反馈】开头
     */
    fun buildExecutionFeedbackMessage(
        context: Context,
        userQuery: String,
        executedAssistant: String,
        executedCommand: String,
        resultContent: String,
        round: Int,
        assistants: List<Pair<String, String>>,
        memberNames: List<String> = emptyList(),
        senderName: String? = null,
    ): String {
        val groupContext = if (assistants.isEmpty()) "" else buildGroupAssistantContext(context, assistants)
        val memberLine = if (memberNames.isNotEmpty()) {
            "群成员：${memberNames.joinToString("、")}\n"
        } else ""
        val senderLine = if (!senderName.isNullOrBlank()) {
            "发起人：$senderName\n"
        } else ""
        return "【执行结果反馈】\n\n${groupContext}${memberLine}${senderLine}用户原始请求：${userQuery}\n执行小助手：${executedAssistant}\n执行指令：${executedCommand}\n执行结果：${resultContent}\n当前轮次：${round}/$MAX_GROUP_MANAGER_FOLLOW_UP_ROUNDS"
    }
}
