package com.cloudcontrol.demo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 人工客服 / 好友等 [CustomerServiceWebSocket] 消息分发，从 [ChatFragment] 拆出以降低 Fragment 体积。
 */
object ChatCustomerServiceWebSocketHandler {

    /** 离线包中单条好友消息：展示或写入 chat_messages_friend_* */
    private fun processOfflineFriendItem(
        fragment: ChatFragment,
        msg: org.json.JSONObject,
        index: Int,
        reloadConversationIds: MutableSet<String>
    ) {
        with(fragment) {
            val senderImei = msg.optString("senderImei", "")
            val skillId = msg.optString("skillId", null)
            val content = msg.optString("content", "")
            val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(msg, "timestamp")
            Log.d(ChatConstants.TAG, "收到好友离线消息[$index]: senderImei=$senderImei, content=$content, skillId=$skillId, timestamp=$timestamp")
            val conversationId = currentConversation?.id
            val friendConversationId = "friend_$senderImei"
            if (conversationId == friendConversationId) {
                view?.post {
                    if (isAdded && hasChatBinding) {
                        val friend = FriendManager.getFriend(requireContext(), senderImei)
                        val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                        addChatMessage(senderName, content, skipSystemMessageContainer = true, timestamp = timestamp, skillId = skillId)
                    }
                }
            } else {
                try {
                    val context = requireContext()
                    val friend = FriendManager.getFriend(context, senderImei)
                    val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                    val wasNew = OfflineFriendMessagePersistence.appendTextFriendMessageIfNew(
                        context, senderImei, senderName, content, timestamp, skillId
                    )
                    if (!wasNew) return
                    updateConversationLastMessage(friendConversationId, content)
                    reloadConversationIds.add(friendConversationId)
                } catch (e: Exception) {
                    Log.e(ChatConstants.TAG, "保存好友离线消息失败: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 处理 [offline_messages] 整包：按子项 [type] 路由到对应会话，避免未识别类型落入人工客服。
     * WebSocket 回调与 [MainActivity] 转发共用。
     */
    fun processOfflineMessages(fragment: ChatFragment, messageText: String) {
        val reloadConversationIds = mutableSetOf<String>()
        with(fragment) {
            try {
                val json = org.json.JSONObject(messageText)
                if (json.optString("type") != "offline_messages") return
                val messagesArray = json.getJSONArray("messages")
                var hadCustomerServiceMessage = false
                for (i in 0 until messagesArray.length()) {
                    val msg = messagesArray.getJSONObject(i)
                    val msgType = msg.optString("type", "")
                    val content = msg.optString("content", "")

                    when (msgType) {
                        "friend_message" -> {
                            processOfflineFriendItem(this, msg, i, reloadConversationIds)
                        }
                        "friend_request" -> {
                            val senderImei = msg.optString("senderImei", "")
                            Log.d(ChatConstants.TAG, "收到好友请求[$i]: senderImei=$senderImei, content=$content")
                            if (senderImei.isNotEmpty() && context != null) {
                                try {
                                    val prefs = requireContext().getSharedPreferences("friends_prefs", android.content.Context.MODE_PRIVATE)
                                    val initiatorJson = prefs.getString("friend_initiator", null)
                                    val initiatorMap = if (initiatorJson != null) {
                                        try {
                                            val t = object : com.google.gson.reflect.TypeToken<MutableMap<String, String>>() {}.type
                                            com.google.gson.Gson().fromJson<MutableMap<String, String>>(initiatorJson, t) ?: mutableMapOf()
                                        } catch (e: Exception) {
                                            mutableMapOf()
                                        }
                                    } else {
                                        mutableMapOf()
                                    }
                                    if (!initiatorMap.containsKey(senderImei)) {
                                        initiatorMap[senderImei] = "received"
                                        prefs.edit().putString("friend_initiator", com.google.gson.Gson().toJson(initiatorMap)).apply()
                                    }
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "记录好友关系发起方失败: ${e.message}", e)
                                }
                            }
                            view?.post {
                                if (isAdded && hasChatBinding) addChatMessage("系统", content)
                            }
                        }
                        "service_message" -> {
                            hadCustomerServiceMessage = true
                            handleServiceMessageDirectly(msg.toString(), 1)
                        }
                        "group_message" -> {
                            handleGroupMessageDirectly(msg.toString(), 1)
                        }
                        "cross_device_message" -> {
                            handleCrossDeviceMessageDirectly(msg.toString(), 1)
                        }
                        "assistant_user_message", "assistant_sync_message" -> {
                            handleAssistantSyncMessageDirectly(msg.toString())
                        }
                        "friend_sync_message" -> {
                            processFriendSyncMessage(this, msg)
                        }
                        "" -> {
                            // 历史兼容：未带 type 的离线项旧逻辑视为人工客服
                            hadCustomerServiceMessage = true
                            val legacy = org.json.JSONObject(msg.toString())
                            legacy.put("type", "service_message")
                            handleServiceMessageDirectly(legacy.toString(), 1)
                        }
                        else -> {
                            // 服务端可能漏填 type：按字段启发式路由，避免消息被静默丢弃
                            when {
                                msg.optString("groupId", "").isNotEmpty() -> {
                                    val o = org.json.JSONObject(msg.toString())
                                    o.put("type", "group_message")
                                    handleGroupMessageDirectly(o.toString(), 1)
                                }
                                msg.optString("senderImei", "").isNotEmpty() -> {
                                    val o = org.json.JSONObject(msg.toString())
                                    o.put("type", "friend_message")
                                    processOfflineFriendItem(this, o, i, reloadConversationIds)
                                }
                                else -> {
                                    Log.w(ChatConstants.TAG, "离线消息未知 type=$msgType，按人工客服兼容处理: ${content.take(80)}")
                                    hadCustomerServiceMessage = true
                                    val legacy = org.json.JSONObject(msg.toString())
                                    legacy.put("type", "service_message")
                                    handleServiceMessageDirectly(legacy.toString(), 1)
                                }
                            }
                        }
                    }
                }
                // 会话列表立即刷新；若当前正在查看的会话刚写入了 prefs，则从 prefs 重载气泡（避免仅点进会话才出现）
                view?.post {
                    if (isAdded && context != null) {
                        if (hadCustomerServiceMessage &&
                            currentConversation?.id == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                            CustomerServiceUnreadManager.clearUnreadCount(requireContext())
                            updateUnreadBadge()
                        }
                        val cid = currentConversation?.id
                        if (cid != null && reloadConversationIds.contains(cid)) {
                            reloadMessagesFromPrefsAfterExternalWrite()
                        }
                        (activity as? MainActivity)?.refreshConversationListPublic()
                    }
                }
            } catch (e: Exception) {
                Log.e(ChatConstants.TAG, "processOfflineMessages 失败: ${e.message}", e)
            }
        }
    }

    /**
     * WebSocket [friend_sync_message]：PC 与同 IMEI 手机间好友单聊镜像（含 [is_from_me]）。
     * 当前会话匹配则上屏；否则写入 prefs 并刷新会话列表（与 [friend_message] 离屏逻辑一致）。
     */
    fun processFriendSyncMessage(fragment: ChatFragment, json: org.json.JSONObject) {
        with(fragment) {
            val conversationIdRaw = json.optString("conversation_id", "").trim()
            if (!conversationIdRaw.startsWith("friend_")) {
                Log.w(ChatConstants.TAG, "friend_sync_message: 无效 conversation_id=$conversationIdRaw")
                return
            }
            val content = json.optString("content", "")
            val messageId = json.optString("message_id", "")
            val messageType = json.optString("message_type", "text")
            val imageBase64 = json.optString("imageBase64", null)
            val isFromMe = json.optBoolean("is_from_me", false)
            val senderImei = json.optString("sender_imei", "")
            val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
            val uuid = if (messageId.isNotEmpty()) messageId else java.util.UUID.randomUUID().toString()
            val peerImei = conversationIdRaw.removePrefix("friend_")

            if (isFromMe) {
                FriendManager.ensureFriendForIncomingMessage(requireContext(), peerImei, null, null)
            } else {
                val (nickHint, avatarHint) = FriendManager.parseFriendIdentityHintsFromMessageJson(json)
                FriendManager.ensureFriendForIncomingMessage(requireContext(), senderImei, nickHint, avatarHint)
            }

            val conversationId = currentConversation?.id
            Log.d(
                ChatConstants.TAG,
                "friend_sync_message: conv=$conversationIdRaw current=$conversationId isFromMe=$isFromMe"
            )

            if (conversationId == conversationIdRaw) {
                view?.post {
                    if (!isAdded || !hasChatBinding) return@post
                    if (isFromMe) {
                        if (imageBase64 != null && messageType == "image") {
                            mainScope.launch {
                                try {
                                    val imagePath = saveImageFromBase64(imageBase64)
                                    withContext(Dispatchers.Main) {
                                        addChatMessage(
                                            "我",
                                            if (content.isNotEmpty()) content else "[图片]",
                                            skipSystemMessageContainer = true,
                                            timestamp = timestamp
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "friend_sync 己方图片失败: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        addChatMessage(
                                            "我",
                                            if (content.isNotEmpty()) content else "[图片]",
                                            skipSystemMessageContainer = true,
                                            timestamp = timestamp
                                        )
                                    }
                                }
                            }
                        } else {
                            addChatMessage("我", content, skipSystemMessageContainer = true, timestamp = timestamp)
                        }
                    } else {
                        val friend = FriendManager.getFriend(requireContext(), senderImei)
                        val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                        val skillId = json.optString("skillId", null)
                        if (imageBase64 != null && messageType == "image") {
                            mainScope.launch {
                                try {
                                    val imagePath = saveImageFromBase64(imageBase64)
                                    withContext(Dispatchers.Main) {
                                        if (imagePath != null) {
                                            addReceivedImageMessage(imagePath, content, senderName, timestamp, senderImei)
                                        } else {
                                            addChatMessage(
                                                senderName,
                                                if (content.isNotEmpty()) content else "[图片]",
                                                skipSystemMessageContainer = true,
                                                timestamp = timestamp,
                                                skillId = skillId
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        addChatMessage(
                                            senderName,
                                            if (content.isNotEmpty()) content else "[图片]",
                                            skipSystemMessageContainer = true,
                                            timestamp = timestamp,
                                            skillId = skillId
                                        )
                                    }
                                }
                            }
                        } else {
                            addChatMessage(
                                senderName,
                                content,
                                skipSystemMessageContainer = true,
                                timestamp = timestamp,
                                skillId = skillId,
                                senderImei = senderImei
                            )
                        }
                    }
                    Log.d(ChatConstants.TAG, "friend_sync_message 已上屏")
                }
            } else {
                try {
                    val context = requireContext()
                    if (isFromMe) {
                        if (imageBase64 != null && messageType == "image") {
                            mainScope.launch {
                                try {
                                    val imagePath = saveImageFromBase64(imageBase64)
                                    withContext(Dispatchers.Main) {
                                        val displayContent = if (imagePath != null) {
                                            val f = java.io.File(imagePath)
                                            if (content.isNotEmpty()) "$content\n[图片：${f.name}]" else "[图片：${f.name}]"
                                        } else {
                                            if (content.isNotEmpty()) content else "[图片]"
                                        }
                                        val appended = FriendChatMessagePrefsStore.appendMessage(
                                            context,
                                            conversationIdRaw,
                                            org.json.JSONObject().apply {
                                                put("sender", "我")
                                                put("message", displayContent)
                                                put("type", "text")
                                                put("timestamp", timestamp)
                                                put("uuid", uuid)
                                            }
                                        )
                                        if (appended) {
                                            updateConversationLastMessage(conversationIdRaw, displayContent)
                                        }
                                        view?.post {
                                            (activity as? MainActivity)?.refreshConversationListPublic()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "friend_sync 己方离屏图片失败: ${e.message}", e)
                                }
                            }
                        } else {
                            val appended = FriendChatMessagePrefsStore.appendMessage(
                                context,
                                conversationIdRaw,
                                org.json.JSONObject().apply {
                                    put("sender", "我")
                                    put("message", content)
                                    put("type", "text")
                                    put("timestamp", timestamp)
                                    put("uuid", uuid)
                                }
                            )
                            if (appended) {
                                updateConversationLastMessage(conversationIdRaw, content)
                            }
                            view?.post {
                                (activity as? MainActivity)?.refreshConversationListPublic()
                            }
                        }
                    } else {
                        val friend = FriendManager.getFriend(context, senderImei)
                        val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                        val skillId = json.optString("skillId", null)
                        if (imageBase64 != null && messageType == "image") {
                            mainScope.launch {
                                try {
                                    val imagePath = saveImageFromBase64(imageBase64)
                                    withContext(Dispatchers.Main) {
                                        val messageText = if (imagePath != null) {
                                            val imageFile = java.io.File(imagePath)
                                            if (content.isNotEmpty()) {
                                                "$content\n[图片：${imageFile.name}]"
                                            } else {
                                                "[图片：${imageFile.name}]"
                                            }
                                        } else {
                                            if (content.isNotEmpty()) content else "[图片]"
                                        }
                                        val appended = FriendChatMessagePrefsStore.appendMessage(
                                            context,
                                            conversationIdRaw,
                                            org.json.JSONObject().apply {
                                                put("sender", senderName)
                                                put("message", messageText)
                                                put("type", "text")
                                                put("timestamp", timestamp)
                                                put("uuid", uuid)
                                                if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                                                if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
                                            }
                                        )
                                        if (appended) {
                                            updateConversationLastMessage(conversationIdRaw, content)
                                            ConversationSessionNotifier.incrementUnread(context, conversationIdRaw, 1)
                                        }
                                        view?.post {
                                            (activity as? MainActivity)?.refreshConversationListPublic()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "friend_sync 对方离屏图片失败: ${e.message}", e)
                                }
                            }
                        } else {
                            val appended = FriendChatMessagePrefsStore.appendMessage(
                                context,
                                conversationIdRaw,
                                org.json.JSONObject().apply {
                                    put("sender", senderName)
                                    put("message", content)
                                    put("type", "text")
                                    put("timestamp", timestamp)
                                    put("uuid", uuid)
                                    if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                                    if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
                                }
                            )
                            if (appended) {
                                updateConversationLastMessage(conversationIdRaw, content)
                                ConversationSessionNotifier.incrementUnread(context, conversationIdRaw, 1)
                            }
                            view?.post {
                                (activity as? MainActivity)?.refreshConversationListPublic()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(ChatConstants.TAG, "friend_sync_message 离屏保存失败: ${e.message}", e)
                }
            }

            FriendManager.enqueueEnrichFriendFromProfile(requireContext(), peerImei, mainScope)
        }
    }

    /**
     * 无 ChatFragment 或不在目标会话时：[friend_sync_message] 仅落盘 + 会话列表/弹窗（对方消息走 [handleFriendMessage]）。
     */
    fun persistFriendSyncOffline(activity: MainActivity, json: org.json.JSONObject) {
        val conversationIdRaw = json.optString("conversation_id", "").trim()
        if (!conversationIdRaw.startsWith("friend_")) return
        val content = json.optString("content", "")
        val messageId = json.optString("message_id", "")
        val messageType = json.optString("message_type", "text")
        val imageBase64 = json.optString("imageBase64", null)
        val isFromMe = json.optBoolean("is_from_me", false)
        val senderImei = json.optString("sender_imei", "")
        val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
        val uuid = if (messageId.isNotEmpty()) messageId else java.util.UUID.randomUUID().toString()
        val peerImei = conversationIdRaw.removePrefix("friend_")

        if (isFromMe) {
            FriendManager.ensureFriendForIncomingMessage(activity, peerImei, null, null)
        } else {
            val (nickHint, avatarHint) = FriendManager.parseFriendIdentityHintsFromMessageJson(json)
            FriendManager.ensureFriendForIncomingMessage(activity, senderImei, nickHint, avatarHint)
        }
        FriendManager.enqueueEnrichFriendFromProfile(activity, peerImei, activity.testScope)

        if (isFromMe) {
            if (imageBase64 != null && messageType == "image") {
                activity.testScope.launch {
                    try {
                        val imagePath = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            ChatImageUtils.saveImageFromBase64(activity, imageBase64)
                        }
                        val displayContent = if (imagePath != null) {
                            val f = java.io.File(imagePath)
                            if (content.isNotEmpty()) "$content\n[图片：${f.name}]" else "[图片：${f.name}]"
                        } else {
                            if (content.isNotEmpty()) content else "[图片]"
                        }
                        val appended = FriendChatMessagePrefsStore.appendMessage(
                            activity,
                            conversationIdRaw,
                            org.json.JSONObject().apply {
                                put("sender", "我")
                                put("message", displayContent)
                                put("type", "text")
                                put("timestamp", timestamp)
                                put("uuid", uuid)
                            }
                        )
                        if (appended) {
                            activity.onFriendSyncSelfMessagePersisted(conversationIdRaw, displayContent)
                        }
                    } catch (e: Exception) {
                        Log.e(ChatConstants.TAG, "persistFriendSyncOffline 己方图片: ${e.message}", e)
                    }
                }
            } else {
                val appended = FriendChatMessagePrefsStore.appendMessage(
                    activity,
                    conversationIdRaw,
                    org.json.JSONObject().apply {
                        put("sender", "我")
                        put("message", content)
                        put("type", "text")
                        put("timestamp", timestamp)
                        put("uuid", uuid)
                    }
                )
                if (appended) {
                    activity.onFriendSyncSelfMessagePersisted(conversationIdRaw, content)
                }
            }
            return
        }

        val friend = FriendManager.getFriend(activity, senderImei)
        val senderName = friend?.nickname ?: senderImei.take(8) + "..."
        val skillId = json.optString("skillId", null)
        if (imageBase64 != null && messageType == "image") {
            activity.testScope.launch {
                try {
                    val imagePath = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        ChatImageUtils.saveImageFromBase64(activity, imageBase64)
                    }
                    val messageText = if (imagePath != null) {
                        val imageFile = java.io.File(imagePath)
                        if (content.isNotEmpty()) {
                            "$content\n[图片：${imageFile.name}]"
                        } else {
                            "[图片：${imageFile.name}]"
                        }
                    } else {
                        if (content.isNotEmpty()) content else "[图片]"
                    }
                    val lastForList = if (imagePath != null) {
                        if (content.isNotEmpty()) content else "[图片]"
                    } else messageText
                    val appended = FriendChatMessagePrefsStore.appendMessage(
                        activity,
                        conversationIdRaw,
                        org.json.JSONObject().apply {
                            put("sender", senderName)
                            put("message", messageText)
                            put("type", "text")
                            put("timestamp", timestamp)
                            put("uuid", uuid)
                            if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                            if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
                        }
                    )
                    if (appended) {
                        activity.onFriendSyncPeerMessagePersisted(senderImei, senderName, content, conversationIdRaw, lastForList)
                    }
                } catch (e: Exception) {
                    Log.e(ChatConstants.TAG, "persistFriendSyncOffline 对方图片: ${e.message}", e)
                }
            }
        } else {
            val appended = FriendChatMessagePrefsStore.appendMessage(
                activity,
                conversationIdRaw,
                org.json.JSONObject().apply {
                    put("sender", senderName)
                    put("message", content)
                    put("type", "text")
                    put("timestamp", timestamp)
                    put("uuid", uuid)
                    if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                    if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
                }
            )
            if (appended) {
                activity.onFriendSyncPeerMessagePersisted(senderImei, senderName, content, conversationIdRaw, null)
            }
        }
    }

    fun install(webSocket: CustomerServiceWebSocket, fragment: ChatFragment) {
        Log.d(ChatConstants.TAG, "setupCustomerServiceMessageCallback: 设置消息回调，当前对话: ${fragment.currentConversation?.id}")
        webSocket.setOnMessageReceived { messageText, count ->
            with(fragment) {
            Log.d(ChatConstants.TAG, "========== ChatFragment收到WebSocket消息回调 ==========")
            Log.d(ChatConstants.TAG, "消息长度: ${messageText.length}, 数量: $count")
            Log.d(ChatConstants.TAG, "当前对话ID: ${currentConversation?.id}")
            try {
                val json = org.json.JSONObject(messageText)
                val type = json.getString("type")
                
                when (type) {
                    "cross_device_message" -> {
                        dispatchCrossDeviceMessage(json)
                    }
                    "custom_assistant_active_session" -> {
                        val convId = json.optString("conversation_id", "")
                        val bu = json.optString("base_url", "")
                        val activeSid = json.optString("active_session_id", "")
                        val updatedAt = json.optLong("updated_at", 0L)
                        if (convId.isNotEmpty() && activeSid.isNotEmpty()) {
                            view?.post {
                                if (isAdded) {
                                    applyRemoteCustomAssistantActiveSession(convId, bu, activeSid, updatedAt)
                                }
                            }
                        }
                    }
                    "service_message" -> {
                        // 收到实时消息
                        val content = json.getString("content")
                        val conversationId = currentConversation?.id
                        // 解析timestamp：可能是ISO字符串或Long数字
                        val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
                        Log.d(ChatConstants.TAG, "收到人工客服消息: $content, 当前对话ID: $conversationId, timestamp: $timestamp")
                        
                        // 只有在人工客服聊天页面时才显示消息
                        // 如果不在人工客服页面，消息会由MainActivity的handleCustomerServiceMessage处理
                        if (conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                            Log.d(ChatConstants.TAG, "当前在人工客服对话页面，直接显示消息")
                            // 在人工客服聊天页面，直接显示消息并清除未读数
                            // 注意：欢迎消息由ensureWelcomeMessageAtTop()管理，不会通过WebSocket接收
                            // 传递timestamp确保消息能正确去重和显示
                            view?.post {
                                if (isAdded && hasChatBinding) {
                                    addChatMessage("人工客服", content, skipSystemMessageContainer = true, timestamp = timestamp)
                                    CustomerServiceUnreadManager.clearUnreadCount(requireContext())
                                    updateUnreadBadge()
                                } else {
                                    Log.w(ChatConstants.TAG, "Fragment未添加或binding为null，无法显示消息")
                                }
                            }
                        } else {
                            Log.d(ChatConstants.TAG, "当前不在人工客服对话页面（对话ID: $conversationId），消息由MainActivity处理")
                        }
                    }
                    "group_message" -> {
                        // 收到群组消息
                        val content = json.getString("content")
                        val groupId = json.optString("groupId", "")
                        val senderImei = json.optString("senderImei", "")
                        val sender = json.optString("sender", "群成员")
                        val messageType = json.optString("message_type", "text")
                        val messageId = json.optString("message_id", "")
                        val senderOrigin = json.optString("sender_origin", "")
                        val imageBase64 = json.optString("imageBase64", null)
                        val isAssistantReply = json.optBoolean("is_assistant_reply", false)
                        val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
                        Log.d(ChatConstants.TAG, "========== 收到群组消息 ==========")
                        val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                        Log.d(ChatConstants.TAG, "收到群组消息: groupId=$groupId, senderImei=$senderImei, sender=$sender, content=$content")
                        Log.d(ChatConstants.TAG, "收到群组消息: messageType=$messageType, messageId=$messageId, senderOrigin=$senderOrigin, hasImage=${imageBase64 != null}, isAssistantReply=$isAssistantReply")
                        Log.d(ChatConstants.TAG, "收到群组消息: 当前对话ID=${currentConversation?.id}, currentImei=$currentImei, 是否自己发送=${senderImei == currentImei}")
                        Log.d(ChatConstants.TAG, "收到群组消息: assistantGroupContext=${if (assistantGroupContext != null) "存在(groupId=${assistantGroupContext?.groupId})" else "不存在"}")
                        
                        // 重要：如果是小助手的回复消息，不应该再次触发指令执行
                        // 即使消息内容中包含@TopoClaw，也应该忽略
                        if (isAssistantReply && ChatConstants.isMainAssistantSender(sender)) {
                            Log.d(ChatConstants.TAG, "这是小助手的回复消息，跳过指令检测，仅显示消息")
                        }
                        
                        // 检查当前对话是否为该群组的对话
                        val conversationId = currentConversation?.id
                        val groupConversationIds = buildGroupConversationIds(groupId)
                        val groupConversationId = groupConversationIds.firstOrNull() ?: "group_$groupId"
                        
                        // 检查是否有群组上下文（@小助手的情况）
                        val groupContext = assistantGroupContext
                        val isInGroupContext = groupContext != null &&
                            groupContext.groupId.removePrefix("group_") == groupId.removePrefix("group_")
                        
                        // 检查当前会话是否是该群组的自定义助手
                        val isCustomAssistantInGroup = conversationId != null &&
                            CustomAssistantManager.isCustomAssistantId(conversationId) &&
                            run {
                                val bareGroupId = groupId.removePrefix("group_")
                                val group = GroupManager.getGroup(requireContext(), bareGroupId)
                                    ?: GroupManager.getGroup(requireContext(), groupId)
                                group != null && conversationId in group.assistants
                            }
                        
                        // 如果是TopoClaw的回复，即使当前不在群组对话页面，也应该显示
                        // 这样可以确保发起任务的用户能看到小助手的回复
                        val shouldShowMessage = (conversationId != null && conversationId in groupConversationIds) ||
                            conversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                            isInGroupContext ||
                            isCustomAssistantInGroup ||
                            (isAssistantReply && ChatConstants.isMainAssistantSender(sender))
                        
                        Log.d(ChatConstants.TAG, "检查对话匹配 - conversationId: $conversationId, groupConversationId: $groupConversationId, isInGroupContext: $isInGroupContext, isCustomAssistantInGroup: $isCustomAssistantInGroup, isAssistantReply: $isAssistantReply, shouldShowMessage: $shouldShowMessage")
                        
                        // 如果是自己发送的消息，已在 performSendChatMessage 中本地添加，无需再通过 WebSocket 回调添加，避免重复显示
                        if (shouldShowMessage && senderImei == currentImei && !isAssistantReply &&
                            shouldSkipOwnGroupEchoMessage(
                                groupId = groupId,
                                content = content,
                                messageType = messageType,
                                messageId = messageId,
                                senderOrigin = senderOrigin
                            )
                        ) {
                            Log.d(ChatConstants.TAG, "收到自己发送的群组消息，判定为本机回声，跳过添加")
                        } else if (shouldShowMessage && isAssistantReply && shouldSkipReceivedAssistantMessage(groupId, content)) {
                            Log.d(ChatConstants.TAG, "收到本端已发送的小助手消息广播，跳过添加，避免复述")
                        } else if (shouldShowMessage) {
                            // 在当前群组对话页面，或者有群组上下文（@小助手的情况），直接显示消息
                            Log.d(ChatConstants.TAG, "在当前群组对话页面或有群组上下文，直接显示消息")
                            
                            // 如果有群组上下文、当前是群组内自定义助手、或者是TopoClaw回复但当前不在群组对话页面，
                            // 需要临时切换对话上下文来保存消息
                            val originalConversation = if ((isInGroupContext || isCustomAssistantInGroup || (isAssistantReply && ChatConstants.isMainAssistantSender(sender))) && 
                                conversationId != groupConversationId && 
                                conversationId != ConversationListFragment.CONVERSATION_ID_GROUP) {
                                Log.d(ChatConstants.TAG, "临时切换对话上下文以保存消息到正确的群组对话: $groupConversationId (isInGroupContext=$isInGroupContext, isCustomAssistantInGroup=$isCustomAssistantInGroup, isAssistantReply=$isAssistantReply)")
                                val original = currentConversation
                                currentConversation = Conversation(
                                    id = groupConversationId,
                                    name = "群组",
                                    lastMessage = content,
                                    lastMessageTime = timestamp
                                )
                                Log.d(ChatConstants.TAG, "对话上下文已切换: ${original?.id} -> ${currentConversation?.id}")
                                original
                            } else {
                                null
                            }
                            
                            view?.post {
                                if (isAdded && hasChatBinding) {
                                    // 获取当前用户IMEI
                                    val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                                    // 确定发送者名称：如果是自己显示"我"，如果是小助手显示小助手名称，如果是系统显示"系统"，否则从好友列表获取或使用IMEI前8位
                                    val senderName = when {
                                        senderImei == currentImei -> "我"
                                        sender == "系统" -> "系统"
                                        isAssistantReply && CustomAssistantManager.isCustomAssistantId(senderImei) -> {
                                            // 自定义小助手：服务端 sender 已含正确展示名（如 "TopoClaw（主人昵称）"）
                                            if (sender.contains("（") && sender.endsWith("）")) sender
                                            else CustomAssistantManager.getById(requireContext(), senderImei)?.name ?: sender
                                        }
                                        ChatConstants.isMainAssistantSender(sender) ->
                                            DisplayNameHelper.getDisplayName(requireContext(), sender)
                                        else -> {
                                            if (CustomAssistantManager.isCustomAssistantId(senderImei)) {
                                                CustomAssistantManager.getById(requireContext(), senderImei)?.name ?: sender
                                            } else {
                                                val friend = FriendManager.getFriend(requireContext(), senderImei)
                                                friend?.nickname ?: (senderImei.take(8) + "...")
                                            }
                                        }
                                    }
                                    
                                    // 判断显示方式：只有sender为"TopoClaw"且isAssistantReply为true时才显示为小助手消息
                                    // 如果sender是"系统"，即使isAssistantReply为true，也应该显示为系统消息
                                    val isAssistantMessage = isAssistantReply && ChatConstants.isMainAssistantSender(sender)
                                    val skipSystemMessageContainer = if (isAssistantMessage) {
                                        // 小助手回复统一以气泡形式显示
                                        val lastActionType = groupActionTypeMap[groupId]
                                        Log.d(ChatConstants.TAG, "小助手回复消息 - groupId: $groupId, lastActionType: $lastActionType, 显示为气泡")
                                        true  // 小助手回复统一显示为气泡
                                    } else {
                                        // 系统消息或其他消息：如果sender是"系统"，显示在系统消息容器中
                                        val isSystemMessage = sender == "系统"
                                        Log.d(ChatConstants.TAG, "非小助手消息 - sender: $sender, isSystemMessage: $isSystemMessage")
                                        !isSystemMessage  // 系统消息不跳过系统消息容器，其他消息跳过
                                    }
                                    
                                    Log.d(ChatConstants.TAG, "准备添加群组消息到界面，发送者: $senderName, 内容长度: ${content.length}, 当前对话ID: ${currentConversation?.id}, skipSystemMessageContainer: $skipSystemMessageContainer, isAssistantMessage: $isAssistantMessage")
                                    
                                    // 如果有图片，保存并显示图片消息
                                    if (imageBase64 != null && messageType == "image") {
                                        mainScope.launch {
                                            try {
                                                val imagePath = saveImageFromBase64(imageBase64)
                                                if (imagePath != null) {
                                                    withContext(Dispatchers.Main) {
                                                        // 显示图片消息（左侧对齐，因为是接收到的消息）
                                                        addReceivedImageMessage(imagePath, content, senderName, timestamp, senderImei)
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        // 小助手回复使用isAnswer=true确保显示为气泡消息
                                                        addChatMessage(senderName, if (content.isNotEmpty()) content else "[图片]", isAnswer = isAssistantMessage, skipSystemMessageContainer = skipSystemMessageContainer, timestamp = timestamp)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(ChatConstants.TAG, "保存接收到的群组图片失败: ${e.message}", e)
                                                withContext(Dispatchers.Main) {
                                                    // 小助手回复使用isAnswer=true确保显示为气泡消息
                                                    addChatMessage(senderName, if (content.isNotEmpty()) content else "[图片]", isAnswer = isAssistantMessage, skipSystemMessageContainer = skipSystemMessageContainer, timestamp = timestamp)
                                                }
                                            }
                                        }
                                    } else {
                                        // 添加消息（此时currentConversation已切换到群组对话，消息会保存到正确的对话记录）
                                        // 小助手回复使用isAnswer=true确保显示为气泡消息
                                        addChatMessage(senderName, content, isAnswer = isAssistantMessage, skipSystemMessageContainer = skipSystemMessageContainer, timestamp = timestamp)
                                    }
                                    Log.d(ChatConstants.TAG, "群组消息已添加到聊天界面，发送者: $senderName, 保存到的对话ID: ${currentConversation?.id}")
                                    
                                    // 如果是小助手回复，从临时Map中清除动作类型，避免影响后续消息
                                    if (isAssistantReply && ChatConstants.isMainAssistantSender(sender)) {
                                        groupActionTypeMap.remove(groupId)
                                        Log.d(ChatConstants.TAG, "已从临时Map中清除动作类型: groupId=$groupId")
                                    }
                                    
                                    // 恢复原始对话上下文（如果有临时切换）
                                    if (originalConversation != null) {
                                        Log.d(ChatConstants.TAG, "恢复原始对话上下文: ${originalConversation.id}")
                                        currentConversation = originalConversation
                                    }
                                } else {
                                    // 即使无法添加消息，也要恢复对话上下文
                                    if (originalConversation != null) {
                                        currentConversation = originalConversation
                                    }
                                }
                            }
                        } else {
                            Log.d(ChatConstants.TAG, "当前不在群组对话页面且没有群组上下文（对话ID: $conversationId），跳过显示")
                            // 即使不显示，如果是TopoClaw回复，也应该保存到群组对话记录中
                            // 这样用户切换到群组对话时可以看到
                            if (isAssistantReply && ChatConstants.isMainAssistantSender(sender)) {
                                Log.d(ChatConstants.TAG, "TopoClaw回复但不在群组对话页面，保存消息到群组对话记录")
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                    val existingMessagesJson = prefs.getString("chat_messages_$groupConversationId", null)
                                    val messagesArray = if (existingMessagesJson != null) {
                                        org.json.JSONArray(existingMessagesJson)
                                    } else {
                                        org.json.JSONArray()
                                    }
                                    
                                    messagesArray.put(org.json.JSONObject().apply {
                                        put("sender", sender)
                                        put("message", content)
                                        put("type", "text")
                                        put("timestamp", timestamp)
                                        put("uuid", java.util.UUID.randomUUID().toString())
                                    })
                                    
                                    prefs.edit().putString("chat_messages_$groupConversationId", messagesArray.toString()).apply()
                                    updateConversationLastMessage(groupConversationId, content)
                                    ConversationSessionNotifier.incrementUnread(requireContext(), groupConversationId, 1)
                                    Log.d(ChatConstants.TAG, "TopoClaw回复已保存到群组对话记录: $groupConversationId")
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "保存TopoClaw回复到群组对话记录失败: ${e.message}", e)
                                }
                            }
                        }
                    }
                    "friend_message" -> {
                        // 收到好友消息
                        val content = json.getString("content")
                        val senderImei = json.optString("senderImei", "")
                        val messageType = json.optString("message_type", "text")
                        val imageBase64 = json.optString("imageBase64", null)
                        // 提取skillId（如果是技能分享消息）
                        val skillId = json.optString("skillId", null)
                        // 解析timestamp：可能是ISO字符串或Long数字
                        val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
                        Log.d(ChatConstants.TAG, "========== 收到好友消息 ==========")
                        Log.d(ChatConstants.TAG, "消息JSON所有字段: ${json.keys().asSequence().toList()}")
                        Log.d(ChatConstants.TAG, "senderImei=$senderImei, content=$content, messageType=$messageType, hasImage=${imageBase64 != null}, skillId=$skillId, imageBase64长度=${imageBase64?.length ?: 0} bytes, timestamp=$timestamp")
                        if (imageBase64 == null) {
                            Log.d(ChatConstants.TAG, "imageBase64字段不存在或为null")
                        }
                        Log.d(ChatConstants.TAG, "当前对话ID: ${currentConversation?.id}")
                        Log.d(ChatConstants.TAG, "期望的好友对话ID: friend_$senderImei")
                        
                        // 检查当前对话是否为该好友的对话
                        val conversationId = currentConversation?.id
                        val friendConversationId = "friend_$senderImei"
                        val (nickHint, avatarHint) = FriendManager.parseFriendIdentityHintsFromMessageJson(json)
                        FriendManager.ensureFriendForIncomingMessage(requireContext(), senderImei, nickHint, avatarHint)
                        FriendManager.enqueueEnrichFriendFromProfile(requireContext(), senderImei, mainScope)

                        if (conversationId == friendConversationId) {
                            // 在当前好友对话页面，直接显示消息
                            Log.d(ChatConstants.TAG, "在当前好友对话页面，直接显示消息")
                            view?.post {
                                if (isAdded && hasChatBinding) {
                                    val friend = FriendManager.getFriend(requireContext(), senderImei)
                                    val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                                    
                                    // 如果有图片，保存并显示图片消息
                                    if (imageBase64 != null && messageType == "image") {
                                        mainScope.launch {
                                            try {
                                                val imagePath = saveImageFromBase64(imageBase64)
                                                if (imagePath != null) {
                                                    withContext(Dispatchers.Main) {
                                                        // 显示图片消息（左侧对齐，因为是接收到的消息）
                                                        addReceivedImageMessage(imagePath, content, senderName, timestamp, senderImei)
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        addChatMessage(senderName, if (content.isNotEmpty()) content else "[图片]", skipSystemMessageContainer = true, timestamp = timestamp)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e(ChatConstants.TAG, "保存接收到的图片失败: ${e.message}", e)
                                                withContext(Dispatchers.Main) {
                                                    addChatMessage(senderName, if (content.isNotEmpty()) content else "[图片]", skipSystemMessageContainer = true, timestamp = timestamp)
                                                }
                                            }
                                        }
                                    } else {
                                        // 好友消息应该显示为普通消息气泡，不是系统消息
                                        // 如果消息包含skillId，传递给addChatMessage以便正确识别技能消息
                                        addChatMessage(senderName, content, skipSystemMessageContainer = true, timestamp = timestamp, skillId = skillId)
                                    }
                                    Log.d(ChatConstants.TAG, "好友消息已添加到聊天界面")
                                } else {
                                    Log.w(ChatConstants.TAG, "Fragment未添加或binding为null，无法显示消息")
                                }
                            }
                        } else {
                            // 不在当前对话页面，保存消息到该好友的对话记录中
                            Log.d(ChatConstants.TAG, "不在当前对话页面，保存消息到好友对话记录")
                            try {
                                val context = requireContext()
                                val friendConversationIdForStorage = friendConversationId
                                val friend = FriendManager.getFriend(context, senderImei)
                                val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                                val appended = FriendChatMessagePrefsStore.appendMessage(
                                    context,
                                    friendConversationIdForStorage,
                                    org.json.JSONObject().apply {
                                        put("sender", senderName)
                                        put("message", content)
                                        put("type", "text")
                                        put("timestamp", timestamp)
                                        put("uuid", java.util.UUID.randomUUID().toString())
                                        if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                                        if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
                                    }
                                )
                                Log.d(ChatConstants.TAG, "好友消息已保存到对话记录，对话ID: $friendConversationIdForStorage, appended=$appended")
                                if (appended) {
                                    updateConversationLastMessage(friendConversationIdForStorage, content)
                                    ConversationSessionNotifier.incrementUnread(context, friendConversationIdForStorage, 1)
                                    Log.d(ChatConstants.TAG, "好友对话列表已更新: $friendConversationIdForStorage")
                                }
                            } catch (e: Exception) {
                                Log.e(ChatConstants.TAG, "保存好友消息失败: ${e.message}", e)
                            }
                        }
                        Log.d(ChatConstants.TAG, "=====================================")
                    }
                    "assistant_user_message" -> {
                        // PC 端用户消息同步（跨设备实时同步）
                        val content = json.optString("content", "")
                        val rawSender = json.optString("sender", "我")
                        val convIdRaw = json.optString("conversation_id", "assistant").trim()
                        val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
                        val imageBase64 = json.optString("file_base64", null)?.takeIf { it.isNotEmpty() }
                            ?: json.optString("fileBase64", null)?.takeIf { it.isNotEmpty() }
                            ?: json.optString("imageBase64", null)?.takeIf { it.isNotEmpty() }
                        val messageType = json.optString("message_type", "")
                        val hasImage = imageBase64 != null && (
                            messageType == "image" || messageType == "file" || messageType.isEmpty()
                        )
                        Log.d(ChatConstants.TAG, "收到 assistant_user_message: conv=$convIdRaw, hasImage=$hasImage, content=$content")
                        val (targetConvId, targetSessionId) = if (convIdRaw.contains("__")) {
                            val parts = convIdRaw.split("__", limit = 2)
                            parts[0] to parts.getOrNull(1)
                        } else {
                            convIdRaw to null
                        }
                        val sender = ChatConstants.normalizeAssistantSenderForConversation(rawSender, targetConvId)
                        val isViewing = currentConversation?.id == targetConvId &&
                                AssistantSyncMessageHelper.isViewingAssistantSyncSession(
                                    targetSessionId,
                                    currentSessionIdForMultiSession
                                )
                        if (isViewing) {
                            if (hasImage && imageBase64 != null) {
                                mainScope.launch {
                                    try {
                                        val imagePath = saveImageFromBase64(imageBase64)
                                        if (imagePath != null) {
                                            withContext(Dispatchers.Main) {
                                                addReceivedImageMessage(imagePath, content, sender, timestamp)
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                addChatMessage(sender, content.ifEmpty { "[图片]" }, skipSystemMessageContainer = true, timestamp = timestamp)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "assistant_user_message 图片保存失败: ${e.message}", e)
                                        withContext(Dispatchers.Main) {
                                            addChatMessage(sender, content.ifEmpty { "[图片]" }, skipSystemMessageContainer = true, timestamp = timestamp)
                                        }
                                    }
                                }
                            } else {
                                view?.post {
                                    if (isAdded && hasChatBinding) {
                                        addChatMessage(sender, content, skipSystemMessageContainer = true, timestamp = timestamp)
                                    }
                                }
                            }
                        } else {
                            if (hasImage && imageBase64 != null) {
                                mainScope.launch {
                                    try {
                                        val imagePath = saveImageFromBase64(imageBase64)
                                        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                        val msgKey = AssistantSyncMessageHelper.resolveAssistantMsgKey(requireContext(), targetConvId, targetSessionId)
                                        val existingMessagesJson = prefs.getString(msgKey, null)
                                        val messagesArray = if (existingMessagesJson != null) org.json.JSONArray(existingMessagesJson) else org.json.JSONArray()
                                        val displayMsg = if (imagePath != null) {
                                            val imgFile = java.io.File(imagePath)
                                            val msgText = if (content.isNotEmpty()) "$content\n[图片: ${imgFile.name}]" else "[图片: ${imgFile.name}]"
                                            org.json.JSONObject().apply {
                                                put("sender", sender)
                                                put("message", msgText)
                                                put("type", "image")
                                                put("timestamp", timestamp)
                                                put("uuid", java.util.UUID.randomUUID().toString())
                                                put("imagePath", imgFile.absolutePath)
                                            }
                                        } else {
                                            org.json.JSONObject().apply {
                                                put("sender", sender)
                                                put("message", content.ifEmpty { "[图片]" })
                                                put("type", "text")
                                                put("timestamp", timestamp)
                                                put("uuid", java.util.UUID.randomUUID().toString())
                                            }
                                        }
                                        messagesArray.put(displayMsg)
                                        prefs.edit().putString(msgKey, messagesArray.toString()).apply()
                                        val previewText = if (imagePath != null) "[图片]" else content
                                        updateConversationLastMessage(targetConvId, previewText)
                                        ConversationSessionNotifier.incrementUnread(requireContext(), targetConvId, 1)
                                        Log.d(ChatConstants.TAG, "assistant_user_message(含图片) 已保存: $msgKey")
                                        switchToSessionIfNeededForIncomingSync(targetConvId, targetSessionId)
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "保存 assistant_user_message 图片失败: ${e.message}", e)
                                    }
                                }
                            } else {
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                    val msgKey = AssistantSyncMessageHelper.resolveAssistantMsgKey(requireContext(), targetConvId, targetSessionId)
                                    val existingMessagesJson = prefs.getString(msgKey, null)
                                    val messagesArray = if (existingMessagesJson != null) org.json.JSONArray(existingMessagesJson) else org.json.JSONArray()
                                    messagesArray.put(org.json.JSONObject().apply {
                                        put("sender", sender)
                                        put("message", content)
                                        put("type", "text")
                                        put("timestamp", timestamp)
                                        put("uuid", java.util.UUID.randomUUID().toString())
                                    })
                                    prefs.edit().putString(msgKey, messagesArray.toString()).apply()
                                    updateConversationLastMessage(targetConvId, content)
                                    ConversationSessionNotifier.incrementUnread(requireContext(), targetConvId, 1)
                                    Log.d(ChatConstants.TAG, "assistant_user_message 已保存: $msgKey")
                                    switchToSessionIfNeededForIncomingSync(targetConvId, targetSessionId)
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "保存 assistant_user_message 失败: ${e.message}", e)
                                }
                            }
                        }
                    }
                    "assistant_sync_message" -> {
                        // PC 端小助手/系统消息同步（跨设备实时同步）
                        val content = json.optString("content", "")
                        val rawSender = json.optString("sender", "系统")
                        val syncMessageId = json.optString("message_id", "").trim().ifBlank { null }
                        val convIdRaw = json.optString("conversation_id", "assistant").trim()
                        val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(json, "timestamp")
                        val (targetConvId, targetSessionId) = if (convIdRaw.contains("__")) {
                            val parts = convIdRaw.split("__", limit = 2)
                            parts[0] to parts.getOrNull(1)
                        } else {
                            convIdRaw to null
                        }
                        val sender = ChatConstants.normalizeAssistantSenderForConversation(rawSender, targetConvId)
                        val isAnswer = AssistantSyncMessageHelper.isAnswerSyncMessage(
                            sender,
                            targetConvId,
                            requireContext()
                        )
                        val imagePayloads = parseAssistantSyncImagePayloads(json)
                        val hasImage = imagePayloads.isNotEmpty()
                        Log.d(ChatConstants.TAG, "收到 assistant_sync_message: conv=$convIdRaw, sender=$sender, hasImage=$hasImage, content=${content.take(50)}")
                        val isViewing = currentConversation?.id == targetConvId &&
                                AssistantSyncMessageHelper.isViewingAssistantSyncSession(
                                    targetSessionId,
                                    currentSessionIdForMultiSession
                                )
                        if (isViewing) {
                            if (hasImage) {
                                mainScope.launch {
                                    try {
                                        val savedImages = withContext(Dispatchers.IO) {
                                            imagePayloads.mapIndexed { index, payload ->
                                                index to saveImageFromBase64(payload.base64)
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            var hasRenderedImage = false
                                            savedImages.forEach { (index, imagePath) ->
                                                if (imagePath != null) {
                                                    hasRenderedImage = true
                                                    val imageContent = if (index == 0) content else ""
                                                    addReceivedImageMessage(
                                                        imagePath,
                                                        imageContent,
                                                        sender,
                                                        timestamp,
                                                        clearThinking = isAnswer && index == 0
                                                    )
                                                }
                                            }
                                            if (!hasRenderedImage) {
                                                addChatMessage(sender, content.ifEmpty { "[图片]" }, isAnswer = isAnswer, skipSystemMessageContainer = true, timestamp = timestamp, clearThinking = isAnswer)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "assistant_sync_message 图片保存失败: ${e.message}", e)
                                        withContext(Dispatchers.Main) {
                                            addChatMessage(sender, content.ifEmpty { "[图片]" }, isAnswer = isAnswer, skipSystemMessageContainer = true, timestamp = timestamp, clearThinking = isAnswer)
                                        }
                                    }
                                }
                            } else {
                                view?.post {
                                    if (isAdded && hasChatBinding) {
                                        addChatMessage(sender, content, isAnswer = isAnswer, skipSystemMessageContainer = true, timestamp = timestamp, clearThinking = isAnswer)
                                    }
                                }
                            }
                        } else {
                            if (hasImage) {
                                mainScope.launch {
                                    try {
                                        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                        val msgKey = AssistantSyncMessageHelper.resolveAssistantMsgKey(requireContext(), targetConvId, targetSessionId)
                                        val existingMessagesJson = prefs.getString(msgKey, null)
                                        val messagesArray = if (existingMessagesJson != null) org.json.JSONArray(existingMessagesJson) else org.json.JSONArray()
                                        var hasPersistedImage = false
                                        imagePayloads.forEachIndexed { index, payload ->
                                            val imagePath = saveImageFromBase64(payload.base64)
                                            if (imagePath != null) {
                                                hasPersistedImage = true
                                                val imgFile = java.io.File(imagePath)
                                                val msgText = if (index == 0 && content.isNotEmpty()) "$content\n[图片: ${imgFile.name}]" else "[图片: ${imgFile.name}]"
                                                upsertAssistantSyncPersistMessage(
                                                    messagesArray,
                                                    org.json.JSONObject().apply {
                                                        put("sender", sender)
                                                        put("message", msgText)
                                                        put("type", "image")
                                                        put("timestamp", timestamp)
                                                        put("uuid", java.util.UUID.randomUUID().toString())
                                                        put("imagePath", imgFile.absolutePath)
                                                    },
                                                    syncMessageId,
                                                    imageIndex = index,
                                                )
                                            }
                                        }
                                        if (!hasPersistedImage) {
                                            upsertAssistantSyncPersistMessage(
                                                messagesArray,
                                                org.json.JSONObject().apply {
                                                    put("sender", sender)
                                                    put("message", content.ifEmpty { "[图片]" })
                                                    put("type", if (isAnswer) "answer" else "system")
                                                    put("timestamp", timestamp)
                                                    put("uuid", java.util.UUID.randomUUID().toString())
                                                },
                                                syncMessageId,
                                            )
                                        }
                                        prefs.edit().putString(msgKey, messagesArray.toString()).apply()
                                        val previewText = if (hasPersistedImage) "[图片]" else content
                                        updateConversationLastMessage(targetConvId, previewText)
                                        ConversationSessionNotifier.incrementUnread(requireContext(), targetConvId, 1)
                                        Log.d(ChatConstants.TAG, "assistant_sync_message(含图片) 已保存: $msgKey")
                                        switchToSessionIfNeededForIncomingSync(targetConvId, targetSessionId)
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "保存 assistant_sync_message 图片失败: ${e.message}", e)
                                    }
                                }
                            } else {
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                    val msgKey = AssistantSyncMessageHelper.resolveAssistantMsgKey(requireContext(), targetConvId, targetSessionId)
                                    val existingMessagesJson = prefs.getString(msgKey, null)
                                    val messagesArray = if (existingMessagesJson != null) org.json.JSONArray(existingMessagesJson) else org.json.JSONArray()
                                    val msgType = if (isAnswer) "answer" else "system"
                                    upsertAssistantSyncPersistMessage(
                                        messagesArray,
                                        org.json.JSONObject().apply {
                                            put("sender", sender)
                                            put("message", content)
                                            put("type", msgType)
                                            put("timestamp", timestamp)
                                            put("uuid", java.util.UUID.randomUUID().toString())
                                        },
                                        syncMessageId,
                                    )
                                    prefs.edit().putString(msgKey, messagesArray.toString()).apply()
                                    updateConversationLastMessage(targetConvId, content)
                                    ConversationSessionNotifier.incrementUnread(requireContext(), targetConvId, 1)
                                    Log.d(ChatConstants.TAG, "assistant_sync_message 已保存: $msgKey")
                                    switchToSessionIfNeededForIncomingSync(targetConvId, targetSessionId)
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "保存 assistant_sync_message 失败: ${e.message}", e)
                                }
                            }
                        }
                    }
                    "error" -> {
                        // 收到错误消息（例如：发送好友消息失败）
                        val content = json.optString("content", "发生错误")
                        Log.d(ChatConstants.TAG, "收到错误消息: $content")
                        
                        // 检查当前对话是否为好友对话
                        val conversationId = currentConversation?.id
                        if (conversationId?.startsWith("friend_") == true) {
                            // 在好友对话页面，显示错误消息
                            view?.post {
                                if (isAdded && hasChatBinding) {
                                    addChatMessage("系统", content)
                                }
                            }
                        }
                    }
                    "remote_assistant_command" -> {
                        // 收到远程执行指令
                        val groupId = json.optString("groupId", "")
                        val targetImei = json.optString("targetImei", "")
                        val command = json.optString("command", "")
                        val senderImei = json.optString("senderImei", "")
                        Log.d(ChatConstants.TAG, "========== 收到远程执行指令 ==========")
                        Log.d(ChatConstants.TAG, "groupId=$groupId, targetImei=$targetImei, command=$command, senderImei=$senderImei")
                        
                        // 验证是否是自己
                        val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                        if (targetImei != currentImei) {
                            Log.d(ChatConstants.TAG, "远程指令目标不是自己，忽略: targetImei=$targetImei, currentImei=$currentImei")
                            return@setOnMessageReceived
                        }
                        
                        Log.d(ChatConstants.TAG, "确认是发给自己的远程指令，开始执行: $command")
                        
                        // 远程指令需要自动添加 @TopoClaw 前缀（因为这是由其他任务指派的）
                        val finalCommand = if (!ChatConstants.containsRemoteStyleAssistantMention(command)) {
                            "@${ChatConstants.ASSISTANT_DISPLAY_NAME} $command"
                        } else {
                            command
                        }
                        
                        // 切换到TopoClaw模式并执行
                        // 使用主线程的协程作用域，确保Fragment已附加
                        mainScope.launch(Dispatchers.Main) {
                            if (isAdded && hasChatBinding) {
                                // 显示提示消息
                                addChatMessage("系统", "收到远程执行指令，开始执行...")
                                
                                // 切换到TopoClaw对话上下文
                                val originalConversation = currentConversation
                                val assistantConversation = Conversation(
                                    id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                                    name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                                    lastMessage = finalCommand,
                                    lastMessageTime = System.currentTimeMillis()
                                )
                                currentConversation = assistantConversation
                                
                                // 保存群组上下文，用于后续发送结果回群组（使用finalCommand）
                                assistantGroupContext = AssistantGroupContext(groupId, originalConversation, finalCommand, senderImei)
                                Log.d(ChatConstants.TAG, "已设置远程指令群组上下文 - groupId: $groupId, originalQuery: $finalCommand, senderImei: $senderImei")
                                
                                // 执行指令（使用finalCommand）
                                executeQueryInternal(finalCommand)
                            } else {
                                Log.w(ChatConstants.TAG, "Fragment未附加或binding为null，延迟处理远程指令")
                                // 如果Fragment未附加，延迟一下再试
                                delay(500)
                                if (isAdded && hasChatBinding) {
                                    addChatMessage("系统", "收到远程执行指令，开始执行...")
                                    
                                    // 远程指令需要自动添加 @TopoClaw 前缀（因为这是由其他任务指派的）
                                    val finalCommand = if (!ChatConstants.containsRemoteStyleAssistantMention(command)) {
                                        "@${ChatConstants.ASSISTANT_DISPLAY_NAME} $command"
                                    } else {
                                        command
                                    }
                                    
                                    val originalConversation = currentConversation
                                    val assistantConversation = Conversation(
                                        id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                                        name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                                        lastMessage = finalCommand,
                                        lastMessageTime = System.currentTimeMillis()
                                    )
                                    currentConversation = assistantConversation
                                    assistantGroupContext = AssistantGroupContext(groupId, originalConversation, finalCommand, senderImei)
                                    executeQueryInternal(finalCommand)
                                }
                            }
                        }
                    }
                    "mobile_tool_invoke" -> {
                        Log.d(ChatConstants.TAG, "收到 mobile_tool_invoke，交给 ChatFragment 处理")
                        handleMobileToolInvoke(json)
                    }
                    "mobile_tool_cancel" -> {
                        Log.d(ChatConstants.TAG, "收到 mobile_tool_cancel，交给 ChatFragment 处理")
                        handleMobileToolCancel(json)
                    }
                    "friend_sync_message" -> {
                        Log.d(ChatConstants.TAG, "ChatFragment WS: friend_sync_message")
                        processFriendSyncMessage(this, json)
                    }
                    "offline_messages" -> {
                        (activity as? MainActivity)?.applyOfflineFriendMessagesFromOfflineBundle(messageText)
                        handleOfflineMessagesBundle(messageText, count)
                    }
                }
            } catch (e: Exception) {
                Log.e(ChatConstants.TAG, "处理WebSocket消息失败: ${e.message}", e)
                e.printStackTrace()
            }
            Log.d(ChatConstants.TAG, "=========================================")

            }
        }
        (fragment.activity as? MainActivity)?.consumePendingOfflineMessagesIfAny()
        Log.d(ChatConstants.TAG, "已注册人工客服WebSocket消息监听")
    }
}
