package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 好友会话 [chat_messages_friend_*] 的唯一落盘入口：全局按会话串行 + 合并写入，
 * 消除 MainActivity 按条追加与 ChatViewModel 整表保存之间的丢失更新竞态。
 */
object FriendChatMessagePrefsStore {

    private const val TAG = "FriendChatPrefsStore"
    private const val PREFS_NAME = "app_prefs"

    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockFor(friendConversationId: String): Any =
        locks.getOrPut(friendConversationId) { Any() }

    /**
     * 好友会话去重时统一文本类型：
     * 同一条消息在不同链路里可能被标成 system/user/answer/text，
     * 若直接把原 type 计入键，进出会话合并会出现重复。
     */
    private fun normalizeFriendTypeForLogicalDedup(rawType: String): String {
        return when (rawType.lowercase()) {
            "image", "video", "skill", "complete" -> rawType.lowercase()
            else -> "text"
        }
    }

    /** 与 ViewModel / 离线去重一致：优先 uuid，否则 timestamp + 正文 + 发送方标识（格式勿随意改，否则与已落盘数据去重不一致） */
    fun dedupKey(o: JSONObject): String {
        val u = o.optString("uuid", "")
        if (u.isNotEmpty()) return "u:$u"
        val ts = o.optLong("timestamp", 0L)
        val msg = o.optString("message", "")
        val imei = o.optString("senderImei", "")
        if (imei.isNotEmpty()) return "t:$ts|${msg.hashCode()}|$imei"
        return "t:$ts|${msg.hashCode()}|${o.optString("sender", "")}"
    }

    /**
     * 逻辑去重键（不含 uuid）：用于合并「同一内容、不同 uuid」的两条（MainActivity 追加与 ViewModel 保存各生成一套 uuid 时）。
     * 优先 senderImei，避免落盘 sender 为「好友」与界面展示昵称不一致导致键不同。
     */
    fun logicalDedupKeyFromJson(o: JSONObject): String {
        val ts = o.optLong("timestamp", 0L)
        val msg = o.optString("message", "")
        val type = normalizeFriendTypeForLogicalDedup(o.optString("type", ""))
        val imei = o.optString("senderImei", "")
        val identity = if (imei.isNotBlank()) imei else o.optString("sender", "")
        return "L:$ts|${msg.hashCode()}|$identity|$type"
    }

    fun logicalDedupKeyFromMessage(m: ChatMessage): String {
        val identity = when {
            !m.senderImei.isNullOrBlank() -> m.senderImei!!
            else -> m.sender
        }
        val type = normalizeFriendTypeForLogicalDedup(m.type)
        return "L:${m.timestamp}|${m.message.hashCode()}|$identity|$type"
    }

    /**
     * 合并磁盘与 ViewModel：先写入磁盘已有消息（MainActivity 追加的历史），再合并 VM，
     * 避免 VM 条目的去重键与磁盘「误撞」时把磁盘消息挤掉。
     */
    private fun mergeDiskAndViewModel(disk: JSONArray, vm: JSONArray): JSONArray {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<JSONObject>()
        fun tryAdd(o: JSONObject) {
            val k1 = dedupKey(o)
            val k2 = logicalDedupKeyFromJson(o)
            if (k1 in seen || k2 in seen) return
            seen.add(k1)
            seen.add(k2)
            merged.add(o)
        }
        for (i in 0 until disk.length()) {
            tryAdd(disk.getJSONObject(i))
        }
        for (i in 0 until vm.length()) {
            tryAdd(vm.getJSONObject(i))
        }
        merged.sortBy { it.optLong("timestamp", 0L) }
        val out = JSONArray()
        merged.forEach { out.put(it) }
        return out
    }

    /**
     * ViewModel 保存好友会话时调用：与磁盘已有 JSON 合并后再写入，避免覆盖 MainActivity / 离线仅写入 prefs 的消息。
     * @param sync 保留与调用方一致；合并写盘始终使用 [commit] 同步落盘。
     */
    fun saveViewModelMerged(
        context: Context,
        friendConversationId: String,
        vmMessagesJson: JSONArray,
        @Suppress("UNUSED_PARAMETER") sync: Boolean
    ): Boolean {
        val lock = lockFor(friendConversationId)
        synchronized(lock) {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val msgKey = "chat_messages_$friendConversationId"
                val timeKey = "chat_messages_start_time_$friendConversationId"
                val diskStr = prefs.getString(msgKey, null)
                val diskArr = if (diskStr != null) JSONArray(diskStr) else JSONArray()
                val merged = mergeDiskAndViewModel(diskArr, vmMessagesJson)
                val editor = prefs.edit()
                editor.putString(msgKey, merged.toString())
                val appStartTime = prefs.getLong("app_start_time", 0)
                editor.putLong(timeKey, appStartTime)
                // 必须使用 commit：apply() 异步落盘会在释放锁之后才写完，appendMessage 的 commit 可能先完成，
                // 随后 apply 写入旧合并结果，覆盖新追加的好友消息（表现为历史只剩最后几条）。
                val ok = editor.commit()
                if (!ok) Log.w(TAG, "commit failed merge save $friendConversationId")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "saveViewModelMerged: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 追加单条消息（MainActivity 实时好友、图片失败回退等），与 [saveViewModelMerged] 共用锁。
     * @return true 表示新追加了一条，false 表示已存在相同消息
     */
    fun appendMessage(context: Context, friendConversationId: String, newMsg: JSONObject): Boolean {
        val lock = lockFor(friendConversationId)
        synchronized(lock) {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val msgKey = "chat_messages_$friendConversationId"
                val timeKey = "chat_messages_start_time_$friendConversationId"
                val existing = prefs.getString(msgKey, null)
                val arr = if (existing != null) JSONArray(existing) else JSONArray()
                val kNew = dedupKey(newMsg)
                val lNew = logicalDedupKeyFromJson(newMsg)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (dedupKey(o) == kNew || logicalDedupKeyFromJson(o) == lNew) {
                        Log.d(TAG, "append skip duplicate key=$kNew logical=$lNew")
                        return false
                    }
                }
                arr.put(newMsg)
                val editor = prefs.edit()
                editor.putString(msgKey, arr.toString())
                val appStartTime = prefs.getLong("app_start_time", 0)
                editor.putLong(timeKey, appStartTime)
                val ok = editor.commit()
                if (!ok) Log.w(TAG, "commit failed append $friendConversationId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "appendMessage: ${e.message}", e)
                false
            }
        }
    }

    /** 文本好友离线 / 与 [OfflineFriendMessagePersistence] 兼容 */
    fun appendTextFriendMessageIfNew(
        context: Context,
        senderImei: String,
        senderName: String,
        content: String,
        timestamp: Long,
        skillId: String?
    ): Boolean {
        val friendConversationId = "friend_$senderImei"
        val obj = JSONObject().apply {
            put("sender", senderName)
            put("message", content)
            put("type", "text")
            put("timestamp", timestamp)
            put("uuid", java.util.UUID.randomUUID().toString())
            if (senderImei.isNotEmpty()) put("senderImei", senderImei)
            if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
        }
        return appendMessage(context, friendConversationId, obj)
    }
}
