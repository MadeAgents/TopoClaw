package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 好友管理器
 * 负责好友列表的本地存储和管理
 */
object FriendManager {
    private const val TAG = "FriendManager"
    private const val PREFS_NAME = "friends_prefs"
    private const val KEY_FRIENDS = "friends_list"
    private val gson = Gson()
    
    /**
     * 获取所有好友列表
     */
    fun getFriends(context: Context): List<Friend> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val friendsJson = prefs.getString(KEY_FRIENDS, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<Friend>>() {}.type
            gson.fromJson<List<Friend>>(friendsJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "解析好友列表失败: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 保存好友列表
     */
    private fun saveFriends(context: Context, friends: List<Friend>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val friendsJson = gson.toJson(friends)
        prefs.edit().putString(KEY_FRIENDS, friendsJson).apply()
    }
    
    /**
     * 添加好友
     */
    fun addFriend(context: Context, friend: Friend) {
        val friends = getFriends(context).toMutableList()
        // 检查是否已存在
        if (friends.any { it.imei == friend.imei }) {
            Log.d(TAG, "好友已存在: ${friend.imei}")
            return
        }
        friends.add(friend)
        saveFriends(context, friends)
        Log.d(TAG, "添加好友成功: ${friend.imei}")
    }
    
    /**
     * 更新好友信息
     */
    fun updateFriend(context: Context, imei: String, nickname: String? = null, avatar: String? = null) {
        val friends = getFriends(context).toMutableList()
        val index = friends.indexOfFirst { it.imei == imei }
        if (index >= 0) {
            val friend = friends[index]
            friends[index] = friend.copy(
                nickname = nickname ?: friend.nickname,
                avatar = avatar ?: friend.avatar
            )
            saveFriends(context, friends)
            Log.d(TAG, "更新好友信息成功: $imei")
        }
    }
    
    /**
     * 删除好友
     */
    fun removeFriend(context: Context, imei: String) {
        val friends = getFriends(context).toMutableList()
        friends.removeAll { it.imei == imei }
        saveFriends(context, friends)
        Log.d(TAG, "删除好友成功: $imei")
    }
    
    /**
     * 检查是否为好友
     */
    fun isFriend(context: Context, imei: String): Boolean {
        return getFriends(context).any { it.imei == imei && it.status == "accepted" }
    }
    
    /**
     * 获取好友信息
     */
    fun getFriend(context: Context, imei: String): Friend? {
        return getFriends(context).find { it.imei == imei }
    }

    /**
     * 从 WebSocket `friend_message` JSON 中提取昵称/头像提示（兼容多种字段名；占位文案「好友」忽略）。
     */
    fun parseFriendIdentityHintsFromMessageJson(json: JSONObject): Pair<String?, String?> {
        val nickCandidates = listOf(
            json.optString("senderNickname", ""),
            json.optString("sender_nickname", ""),
            json.optString("nickname", ""),
            json.optString("senderNick", ""),
            json.optString("sender_name", ""),
            json.optString("senderName", ""),
            json.optString("sender", "")
        )
        val nick = nickCandidates
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "好友" }
        val avatarCandidates = listOf(
            json.optString("senderAvatar", ""),
            json.optString("sender_avatar", ""),
            json.optString("avatar", ""),
            json.optString("avatarUrl", ""),
            json.optString("avatar_base64", "")
        )
        val avatar = avatarCandidates.map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return nick to avatar
    }

    /**
     * 收到好友消息时保证本地存在 [accepted] 好友记录，便于会话列表与弹窗展示昵称/头像。
     * 若本地尚无记录则插入占位；若有记录但昵称为空则用语义化昵称/头像提示补全。
     */
    fun ensureFriendForIncomingMessage(
        context: Context,
        imei: String,
        nicknameHint: String?,
        avatarHint: String?
    ) {
        if (imei.isBlank()) return
        val cleanedNick = nicknameHint?.trim()?.takeIf { it.isNotEmpty() && it != "好友" }
        val cleanedAvatar = avatarHint?.trim()?.takeIf { it.isNotEmpty() }
        val existing = getFriend(context, imei)
        if (existing == null) {
            addFriend(
                context,
                Friend(
                    imei = imei,
                    nickname = cleanedNick,
                    avatar = cleanedAvatar,
                    status = "accepted"
                )
            )
            Log.d(TAG, "ensureFriendForIncomingMessage: 新增本地好友占位 imei=$imei")
            return
        }
        val needNick = existing.nickname.isNullOrBlank() && cleanedNick != null
        val needAvatar = existing.avatar.isNullOrBlank() && cleanedAvatar != null
        if (needNick || needAvatar) {
            updateFriend(
                context,
                imei,
                nickname = if (needNick) cleanedNick else null,
                avatar = if (needAvatar) cleanedAvatar else null
            )
        }
    }
    
    /**
     * 同步好友列表（从服务器）
     * friends/list 已包含 profiles_storage 中的 nickname/avatar，无需逐一请求 profile。
     * 缺失的昵称/头像会在收到好友消息时由 enqueueEnrichFriendFromProfile 按需补全。
     */
    suspend fun syncFriendsFromServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val imei = ProfileManager.getOrGenerateImei(context)
            val apiService = CustomerServiceNetwork.getApiService()
            val response = apiService?.getFriends(imei)
            if (response?.isSuccessful == true && response.body()?.success == true) {
                val serverFriends = response.body()?.friends ?: emptyList()
                val localFriends = getFriends(context).toMutableList()

                serverFriends.forEach { serverFriend ->
                    val localIndex = localFriends.indexOfFirst { it.imei == serverFriend.imei }
                    if (localIndex >= 0) {
                        val localFriend = localFriends[localIndex]
                        localFriends[localIndex] = serverFriend.copy(
                            avatar = serverFriend.avatar ?: localFriend.avatar,
                            nickname = serverFriend.nickname ?: localFriend.nickname
                        )
                    } else {
                        localFriends.add(serverFriend)
                    }
                }
                saveFriends(context, localFriends)
                Log.d(TAG, "同步好友列表成功，共 ${localFriends.size} 个好友")
                return@withContext true
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "同步好友列表失败: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * 异步拉取用户资料并补全本地好友昵称/头像。
     * [friend_sync_message] 等 WS 载荷通常不含 senderNickname/senderAvatar，仅靠此接口与通讯录/会话列表一致。
     */
    fun enqueueEnrichFriendFromProfile(context: Context, imei: String, scope: CoroutineScope) {
        if (imei.isBlank()) return
        val cur = getFriend(context, imei)
        if (cur != null &&
            !cur.nickname.isNullOrBlank() &&
            !cur.avatar.isNullOrBlank()
        ) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val api = CustomerServiceNetwork.getApiService() ?: return@launch
                val response = api.getProfile(imei)
                if (!response.isSuccessful || response.body()?.success != true) return@launch
                val profile = response.body()?.profile ?: return@launch
                val nick = profile.name?.trim()?.takeIf { it.isNotEmpty() }
                val avatar = profile.avatar?.trim()?.takeIf { it.isNotEmpty() }
                if (nick == null && avatar == null) return@launch
                withContext(Dispatchers.Main) {
                    val existing = getFriend(context, imei)
                    if (existing == null) {
                        ensureFriendForIncomingMessage(context, imei, nick, avatar)
                    } else {
                        val needNick = existing.nickname.isNullOrBlank() && nick != null
                        val needAv = existing.avatar.isNullOrBlank() && avatar != null
                        if (needNick || needAv) {
                            updateFriend(
                                context,
                                imei,
                                nickname = if (needNick) nick else null,
                                avatar = if (needAv) avatar else null
                            )
                        }
                    }
                    (context as? MainActivity)?.refreshConversationListPublic()
                }
            } catch (e: Exception) {
                Log.w(TAG, "enqueueEnrichFriendFromProfile($imei): ${e.message}")
            }
        }
    }
}
