package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatViewModel
 * 管理聊天消息数据和状态，避免 Fragment 销毁重建时数据丢失
 */
class ChatViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "ChatViewModel"
        private const val PREFS_NAME = "app_prefs"
    }
    
    // 聊天消息列表（使用 StateFlow 实现响应式更新）
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
    
    // 是否正在恢复消息
    private val _isRestoringMessages = MutableStateFlow(false)
    val isRestoringMessages: StateFlow<Boolean> = _isRestoringMessages.asStateFlow()
    
    // 消息去重集合（用于快速去重检查）
    private val messageHashSet = mutableSetOf<String>()
    
    // 当前对话ID（用于区分不同对话的数据）
    @Volatile
    private var currentConversationId: String? = null
    
    // 保存防抖相关
    private var saveJob: kotlinx.coroutines.Job? = null
    
    // 并发控制：使用 Mutex 保护关键操作
    private val saveMutex = kotlinx.coroutines.sync.Mutex()
    private val loadMutex = kotlinx.coroutines.sync.Mutex()
    
    /**
     * 初始化 ViewModel，设置当前对话ID
     * @param conversationId 对话ID
     */
    fun initialize(conversationId: String) {
        if (currentConversationId != conversationId) {
            Log.d(TAG, "初始化 ViewModel，对话ID: $conversationId（之前: $currentConversationId）")
            val hadData = _chatMessages.value.isNotEmpty()
            currentConversationId = conversationId
            // 如果已有数据，清空（切换对话时）
            if (hadData) {
                _chatMessages.value = emptyList()
                messageHashSet.clear()
                Log.d(TAG, "切换对话，已清空旧数据")
            }
        } else {
            Log.d(TAG, "ViewModel 已初始化，对话ID: $conversationId")
        }
    }
    
    /**
     * 获取当前对话ID（用于校验）
     */
    fun getCurrentConversationId(): String? = currentConversationId
    
    /**
     * 获取当前消息列表（同步方法，用于兼容现有代码）
     */
    fun getMessages(): List<ChatMessage> = _chatMessages.value
    
    /**
     * 添加消息到列表
     */
    fun addMessage(message: ChatMessage) {
        val messageHash = messageHashForDedup(message)
        if (!messageHashSet.contains(messageHash)) {
            val currentList = _chatMessages.value.toMutableList()
            currentList.add(message)
            _chatMessages.value = currentList
            messageHashSet.add(messageHash)
            Log.d(TAG, "添加消息，当前总数: ${currentList.size}")
        } else {
            Log.d(TAG, "消息已存在，跳过添加: ${message.message.take(50)}")
        }
    }
    
    /**
     * 批量添加消息
     */
    fun addMessages(messages: List<ChatMessage>) {
        val currentList = _chatMessages.value.toMutableList()
        var addedCount = 0
        messages.forEach { message ->
            val messageHash = messageHashForDedup(message)
            if (!messageHashSet.contains(messageHash)) {
                currentList.add(message)
                messageHashSet.add(messageHash)
                addedCount++
            }
        }
        if (addedCount > 0) {
            _chatMessages.value = currentList
            Log.d(TAG, "批量添加消息，新增: $addedCount，当前总数: ${currentList.size}")
        }
    }
    
    /**
     * 清空消息列表
     */
    fun clearMessages() {
        _chatMessages.value = emptyList()
        messageHashSet.clear()
        Log.d(TAG, "清空消息列表")
    }
    
    /**
     * 从 SharedPreferences 加载消息（仅在必要时调用）
     * @param sessionId 多 session 时传 sessionId，使用 chat_messages_${conversationId}_${sessionId}
     */
    fun loadMessagesFromPrefs(context: Context, conversationId: String, sessionId: String? = null) {
        // 如果 conversationId 不一致，重新初始化 ViewModel（而不是跳过）
        if (currentConversationId != null && currentConversationId != conversationId) {
            Log.w(TAG, "conversationId 不一致！ViewModel: $currentConversationId, 参数: $conversationId，重新初始化 ViewModel")
            initialize(conversationId)
        }
        
        // 使用 Mutex 防止并发加载
        viewModelScope.launch {
            if (!loadMutex.tryLock()) {
                Log.w(TAG, "正在加载消息，跳过重复调用")
                return@launch
            }
            
            try {
                // 双重检查：如果已经在恢复，跳过
                if (_isRestoringMessages.value) {
                    Log.w(TAG, "正在恢复消息，跳过重复调用")
                    return@launch
                }
                
                // 确保 ViewModel 已初始化
                if (currentConversationId == null) {
                    initialize(conversationId)
                }
                
                _isRestoringMessages.value = true
                try {
                    val messages = withContext(Dispatchers.IO) {
                        loadMessagesFromPrefsInternal(context, conversationId, sessionId)
                    }
                    if (messages.isNotEmpty()) {
                        // 校验数据完整性
                        val validatedMessages = validateMessages(messages, conversationId)
                        if (validatedMessages.size != messages.size) {
                            Log.w(TAG, "数据校验：${messages.size - validatedMessages.size} 条消息被过滤，保留 ${validatedMessages.size} 条")
                        }
                        
                        _chatMessages.value = validatedMessages
                        // 重建去重集合
                        messageHashSet.clear()
                        validatedMessages.forEach { message ->
                            messageHashSet.add(messageHashForDedup(message))
                        }
                        Log.d(TAG, "从 SharedPreferences 加载了 ${validatedMessages.size} 条消息，对话ID: $conversationId")
                    } else {
                        Log.d(TAG, "SharedPreferences 中没有消息，对话ID: $conversationId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载消息失败: ${e.message}", e)
                } finally {
                    _isRestoringMessages.value = false
                }
            } finally {
                loadMutex.unlock()
            }
        }
    }
    
    /**
     * 内部方法：从 SharedPreferences 加载消息
     * @param sessionId 多 session 时使用，key 为 chat_messages_${conversationId}_${sessionId}
     */
    private suspend fun loadMessagesFromPrefsInternal(context: Context, conversationId: String, sessionId: String? = null): List<ChatMessage> = withContext(Dispatchers.IO) {
        val messagesToRestore = mutableListOf<ChatMessage>()
        val msgKey = if (sessionId != null) "chat_messages_${conversationId}_$sessionId" else "chat_messages_$conversationId"
        val timeKey = if (sessionId != null) "chat_messages_start_time_${conversationId}_$sessionId" else "chat_messages_start_time_$conversationId"
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // 检查应用是否重启
            var currentAppStartTime = prefs.getLong("app_start_time", 0)
            val savedChatStartTime = prefs.getLong(timeKey, 0)
            
            // 如果 app_start_time 未设置，快速重试几次
            if (currentAppStartTime == 0L) {
                repeat(3) {
                    kotlinx.coroutines.delay(10)
                    currentAppStartTime = prefs.getLong("app_start_time", 0)
                    if (currentAppStartTime != 0L) return@repeat
                }
                if (currentAppStartTime == 0L) {
                    currentAppStartTime = System.currentTimeMillis()
                    prefs.edit().putLong("app_start_time", currentAppStartTime).apply()
                }
            }
            
            // 如果时间戳不匹配且时间差超过1小时，说明应用已重启
            val timeDiff = kotlin.math.abs(currentAppStartTime - savedChatStartTime)
            if (savedChatStartTime != 0L && timeDiff > 3600000) {
                prefs.edit().putLong(timeKey, currentAppStartTime).apply()
            }
            
            // 加载消息
            val messagesJson = prefs.getString(msgKey, null)
            if (messagesJson != null) {
                val jsonArray = JSONArray(messagesJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val sender = obj.getString("sender")
                    val message = obj.getString("message")
                    
                    // 如果是人工客服对话，过滤掉欢迎消息
                    if (conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE && 
                        sender == "人工客服" && 
                        message.contains("我是人工客服") && 
                        (message.contains("使用说明") || message.contains("清除数据"))) {
                        continue
                    }
                    
                    // 兼容旧格式（无 type）：自定义小助手会话中助手侧不应默认为 system，否则恢复后会进顶部系统区而非气泡
                    val type = if (obj.has("type")) obj.getString("type") else {
                        inferLegacyMessageTypeWithoutStoredType(conversationId, sender, message)
                    }
                    
                    var timestamp = if (obj.has("timestamp")) {
                        val timestampValue = obj.get("timestamp")
                        when (timestampValue) {
                            is Long -> timestampValue
                            is Int -> timestampValue.toLong()
                            is Double -> timestampValue.toLong()
                            is String -> parseTimestamp(obj, "timestamp")
                            else -> System.currentTimeMillis()
                        }
                    } else {
                        System.currentTimeMillis()
                    }
                    
                    val uuid = if (obj.has("uuid")) obj.getString("uuid") else ""
                    val imagePath = if (obj.has("imagePath")) {
                        val path = obj.optString("imagePath", null)
                        if (path != null && path.isNotEmpty()) path else null
                    } else null
                    val skillId = if (obj.has("skillId")) {
                        val id = obj.optString("skillId", null)
                        if (id != null && id.isNotEmpty()) id else null
                    } else null
                    val senderImei = if (obj.has("senderImei")) {
                        val imei = obj.optString("senderImei", null)
                        if (imei != null && imei.isNotEmpty()) imei else null
                    } else null
                    
                    val chatMessage = ChatMessage(sender, message, type, timestamp, uuid, imagePath, skillId, senderImei)
                    messagesToRestore.add(chatMessage)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败: ${e.message}", e)
        }
        messagesToRestore
    }
    
    /**
     * 保存消息到 SharedPreferences（防抖保存）
     * @param sessionId 多 session 时传 sessionId，null 表示非多 session
     */
    fun saveMessagesToPrefsDebounced(context: Context, conversationId: String, sessionId: String? = null) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            saveMessagesToPrefs(context, conversationId, sync = false, sessionId)
        }
    }
    
    /**
     * 保存消息到 SharedPreferences
     * @param context 上下文
     * @param conversationId 对话ID（如果与 ViewModel 的 currentConversationId 不一致，会先保存旧数据，再重新初始化）
     * @param sync 是否同步保存
     * @param sessionId 多 session 时的会话 ID（thread_id = sessionId），null 表示非多 session
     */
    fun saveMessagesToPrefs(context: Context, conversationId: String, sync: Boolean = false, sessionId: String? = null) {
        // 如果 conversationId 不一致，先保存旧数据，再重新初始化
        val oldConversationId = currentConversationId
        if (oldConversationId != null && oldConversationId != conversationId) {
            Log.w(TAG, "conversationId 不一致！ViewModel: $oldConversationId, 参数: $conversationId")
            
            // 先保存旧数据（如果有的话）
            val oldMessages = _chatMessages.value
            if (oldMessages.isNotEmpty()) {
                Log.w(TAG, "检测到 conversationId 变化，先保存旧数据（${oldMessages.size}条消息）到旧对话ID: $oldConversationId")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        saveMessagesToPrefsInternal(context, oldConversationId, oldMessages, sync = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "保存旧数据失败: ${e.message}", e)
                    }
                }
            }
            
            // 然后重新初始化 ViewModel
            Log.w(TAG, "重新初始化 ViewModel，切换到新对话ID: $conversationId")
            initialize(conversationId)
        }
        
        // 确保 ViewModel 已初始化
        if (currentConversationId == null) {
            initialize(conversationId)
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            // 使用 Mutex 防止并发保存
            saveMutex.lock()
            try {
                val messages = _chatMessages.value
                
                // 保存前校验数据完整性
                if (!validateBeforeSave(messages)) {
                    Log.w(TAG, "数据校验失败，跳过保存")
                    return@launch
                }
                
                saveMessagesToPrefsInternal(context, conversationId, messages, sync, sessionId)
                Log.d(TAG, "保存消息到 SharedPreferences，对话ID: $conversationId，消息数量: ${messages.size}")
            } catch (e: Exception) {
                Log.e(TAG, "保存消息失败: ${e.message}", e)
                // 如果是同步保存失败，可以考虑重试或通知用户
                if (sync) {
                    Log.e(TAG, "同步保存失败，数据可能丢失！对话ID: $conversationId")
                }
            } finally {
                saveMutex.unlock()
            }
        }
    }
    
    /**
     * 内部方法：保存消息到 SharedPreferences
     * @param sessionId 多 session 时使用，key 为 chat_messages_${conversationId}_${sessionId}
     */
    private suspend fun saveMessagesToPrefsInternal(
        context: Context,
        conversationId: String,
        messages: List<ChatMessage>,
        sync: Boolean,
        sessionId: String? = null
    ) = withContext(Dispatchers.IO) {
        val msgKey = if (sessionId != null) "chat_messages_${conversationId}_$sessionId" else "chat_messages_$conversationId"
        val timeKey = if (sessionId != null) "chat_messages_start_time_${conversationId}_$sessionId" else "chat_messages_start_time_$conversationId"
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val messagesJson = JSONArray().apply {
                messages.forEach { chatMessage ->
                    put(JSONObject().apply {
                        // 好友会话：空 uuid 不再随机生成，否则与 MainActivity 追加/合并去重不一致，易产生「同一条两条 uuid」
                        val uuidOut = when {
                            chatMessage.uuid.isNotBlank() -> chatMessage.uuid
                            sessionId == null && conversationId.startsWith("friend_") -> ""
                            else -> chatMessage.uuid
                        }
                        put("sender", chatMessage.sender)
                        put("message", chatMessage.message)
                        put("type", chatMessage.type)
                        put("timestamp", chatMessage.timestamp)
                        put("uuid", uuidOut)
                        if (chatMessage.imagePath != null) {
                            put("imagePath", chatMessage.imagePath)
                        }
                        if (chatMessage.skillId != null) {
                            put("skillId", chatMessage.skillId)
                        }
                        if (chatMessage.senderImei != null) {
                            put("senderImei", chatMessage.senderImei)
                        }
                    })
                }
            }

            // 好友会话：与磁盘合并写入，避免与 MainActivity 仅写 prefs 的路径互相覆盖
            if (sessionId == null && conversationId.startsWith("friend_")) {
                FriendChatMessagePrefsStore.saveViewModelMerged(context, conversationId, messagesJson, sync)
                return@withContext
            }

            val editor = prefs.edit()
            editor.putString(msgKey, messagesJson.toString())
            val appStartTime = prefs.getLong("app_start_time", 0)
            editor.putLong(timeKey, appStartTime)

            if (sync) {
                val success = editor.commit()
                if (!success) {
                    throw Exception("SharedPreferences commit 失败")
                }
            } else {
                editor.apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存消息到 SharedPreferences 失败: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 生成消息哈希值（用于去重，与 ChatFragment 保持一致）
     */
    private fun generateMessageHash(message: ChatMessage): String {
        // 不包含 uuid：adapter 中的 ChatItem 不存储 uuid，重建后 uuid 为空，
        // 导致 adapter 侧与 ViewModel 侧哈希不一致、dedup 失效、消息重复。
        val messageHash = message.message.hashCode()
        return "${message.timestamp}_${message.sender}_$messageHash"
    }

    /** 好友会话用逻辑键去重，避免同一内容多条 uuid；其它会话沿用原哈希 */
    private fun messageHashForDedup(message: ChatMessage): String {
        return if (currentConversationId?.startsWith("friend_") == true) {
            FriendChatMessagePrefsStore.logicalDedupKeyFromMessage(message)
        } else {
            generateMessageHash(message)
        }
    }
    
    /**
     * 解析时间戳（兼容旧格式，与 ChatFragment 保持一致）
     */
    private fun parseTimestamp(obj: JSONObject, key: String): Long {
        return try {
            if (obj.has(key)) {
                val value = obj.get(key)
                when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Double -> value.toLong()
                    is String -> {
                        // 优先尝试解析为数字字符串（毫秒时间戳）
                        value.toLongOrNull()?.let { return@parseTimestamp it }
                        
                        // 如果是ISO格式字符串，统一按本地时区解析
                        if (value.contains('T')) {
                            try {
                                val cleanValue = value.replace("Z", "").trim()
                                
                                // 检查是否包含时区偏移（+08:00, -05:00等）
                                val hasTimezoneOffset = value.contains('+') || 
                                    (value.indexOf('T') > 0 && value.substring(value.indexOf('T') + 1).matches(Regex(".*-\\d{2}:\\d{2}.*")))
                                
                                val parsedTime = if (hasTimezoneOffset) {
                                    // 包含时区偏移，使用带时区的格式解析
                                    val normalizedValue = if (value.contains('Z')) {
                                        value.replace("Z", "+00:00")
                                    } else {
                                        value
                                    }
                                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).parse(normalizedValue)?.time
                                } else {
                                    // 不包含时区信息，按本地时区解析
                                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).apply {
                                        timeZone = java.util.TimeZone.getDefault()
                                    }.parse(cleanValue)?.time
                                }
                                
                                parsedTime ?: System.currentTimeMillis()
                            } catch (e: Exception) {
                                Log.w(TAG, "解析ISO timestamp失败: $value, 使用当前时间", e)
                                System.currentTimeMillis()
                            }
                        } else {
                            // 非ISO格式字符串，尝试解析为数字
                            value.toLongOrNull() ?: System.currentTimeMillis()
                        }
                    }
                    else -> System.currentTimeMillis()
                }
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析时间戳失败: ${e.message}", e)
            System.currentTimeMillis()
        }
    }
    
    /**
     * 旧版 prefs 未写入 type 时的推断（与历史逻辑兼容，并修正自定义小助手会话默认值）。
     */
    private fun inferLegacyMessageTypeWithoutStoredType(conversationId: String, sender: String, message: String): String {
        return when {
            sender == "我" || sender == "用户" -> "user"
            message == "任务已完成" || message == "任务已完成 ✓" -> "complete"
            CustomAssistantManager.isCustomAssistantId(conversationId) -> when (sender) {
                "系统" -> "system"
                else -> "answer"
            }
            else -> "system"
        }
    }

    /**
     * 将误存为 system 的自定义小助手助手侧消息纠正为 answer（仅影响内存与后续保存，不改动其它会话）。
     */
    private fun normalizeCustomAssistantMessageType(conversationId: String, message: ChatMessage): ChatMessage {
        if (!CustomAssistantManager.isCustomAssistantId(conversationId)) return message
        val t = message.type
        if (t == "user" || t == "answer" || t == "complete" || t == "image" || t == "video") return message
        val s = message.sender
        val newType = when {
            s == "我" || s == "用户" -> "user"
            s == "系统" -> "system"
            else -> "answer"
        }
        return if (newType == t) message else message.copy(type = newType)
    }

    /**
     * 校验消息数据的完整性
     * @param messages 待校验的消息列表
     * @param conversationId 对话ID（用于日志）
     * @return 校验后的消息列表
     */
    private fun validateMessages(messages: List<ChatMessage>, conversationId: String): List<ChatMessage> {
        val validatedMessages = mutableListOf<ChatMessage>()
        val normalized = messages.map { normalizeCustomAssistantMessageType(conversationId, it) }
        normalized.forEachIndexed { index, message ->
            try {
                // 校验基本字段
                if (message.sender.isBlank()) {
                    Log.w(TAG, "消息[$index] sender 为空，跳过")
                    return@forEachIndexed
                }
                if (message.message.isBlank() && message.imagePath == null) {
                    Log.w(TAG, "消息[$index] message 和 imagePath 都为空，跳过")
                    return@forEachIndexed
                }
                if (message.timestamp <= 0) {
                    Log.w(TAG, "消息[$index] timestamp 无效: ${message.timestamp}，使用当前时间")
                    // 不跳过，而是修复时间戳
                    val fixedMessage = ChatMessage(
                        message.sender,
                        message.message,
                        message.type,
                        System.currentTimeMillis(),
                        message.uuid,
                        message.imagePath,
                        message.skillId,
                        message.senderImei
                    )
                    validatedMessages.add(fixedMessage)
                    return@forEachIndexed
                }
                
                // 校验通过
                validatedMessages.add(message)
            } catch (e: Exception) {
                Log.e(TAG, "校验消息[$index] 失败: ${e.message}", e)
                // 跳过无效消息
            }
        }
        return if (conversationId.startsWith("friend_")) {
            dedupeFriendMessages(validatedMessages)
        } else {
            validatedMessages
        }
    }

    private fun dedupeFriendMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ChatMessage>()
        for (m in messages) {
            val k = FriendChatMessagePrefsStore.logicalDedupKeyFromMessage(m)
            if (k !in seen) {
                seen.add(k)
                out.add(m)
            }
        }
        if (out.size != messages.size) {
            Log.w(TAG, "好友消息去重: ${messages.size} -> ${out.size}")
        }
        return out
    }
    
    /**
     * 校验保存前的数据完整性
     * @param messages 待保存的消息列表
     * @return 是否通过校验
     */
    private fun validateBeforeSave(messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) {
            Log.d(TAG, "消息列表为空，跳过保存")
            return false
        }
        
        // 检查是否有无效消息
        val invalidCount = messages.count { 
            it.sender.isBlank() && (it.message.isBlank() && it.imagePath == null)
        }
        if (invalidCount > 0) {
            Log.w(TAG, "检测到 $invalidCount 条无效消息，但继续保存")
        }
        
        return true
    }
    
    override fun onCleared() {
        super.onCleared()
        saveJob?.cancel()
        Log.d(TAG, "ViewModel 已清除")
    }
}

