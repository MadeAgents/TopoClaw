package com.cloudcontrol.demo

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 人工客服API接口
 */
interface CustomerServiceApi {
    /**
     * 用户注册
     */
    @POST("api/customer-service/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>
    
    /**
     * 发送消息给用户（客服端使用）
     */
    @POST("api/customer-service/send-message")
    suspend fun sendMessage(@Body request: SendMessageRequest): Response<SendMessageResponse>
    
    /**
     * 获取所有用户与人工客服的聊天记录（管理员模式）
     */
    @GET("api/customer-service/all-chats")
    suspend fun getAllChats(): Response<AllChatsResponse>
    
    /**
     * 获取所有在线用户的IMEI列表
     */
    @GET("api/customer-service/online-users")
    suspend fun getOnlineUsers(): Response<OnlineUsersResponse>

    /**
     * 获取指定用户 PC 在线状态
     */
    @GET("api/customer-service/pc-status/{imei}")
    suspend fun getPcStatus(@Path("imei") imei: String): Response<PcStatusResponse>
    
    /**
     * 添加好友
     */
    @POST("api/friends/add")
    suspend fun addFriend(@Body request: AddFriendRequest): Response<FriendRequestResponse>
    
    /**
     * 接受好友请求
     */
    @POST("api/friends/accept")
    suspend fun acceptFriend(@Body request: AcceptFriendRequest): Response<FriendRequestResponse>
    
    /**
     * 获取好友列表
     */
    @GET("api/friends/list")
    suspend fun getFriends(@retrofit2.http.Query("imei") imei: String): Response<FriendListResponse>
    
    /**
     * 发送好友消息
     */
    @POST("api/friends/send-message")
    suspend fun sendFriendMessage(@Body request: SendFriendMessageRequest): Response<SendMessageResponse>
    
    /**
     * 删除好友
     */
    @POST("api/friends/remove")
    suspend fun removeFriend(@Body request: RemoveFriendRequest): Response<FriendRequestResponse>
    
    /**
     * 创建群组
     */
    @POST("api/groups/create")
    suspend fun createGroup(@Body request: CreateGroupRequest): Response<CreateGroupResponse>
    
    /**
     * 获取用户群组列表
     */
    @GET("api/groups/list")
    suspend fun getGroups(@retrofit2.http.Query("imei") imei: String): Response<GroupListResponse>
    
    /**
     * 获取群组详情
     */
    @GET("api/groups/{group_id}")
    suspend fun getGroup(@retrofit2.http.Path("group_id") groupId: String): Response<GroupDetailResponse>
    
    /**
     * 添加群组成员
     */
    @POST("api/groups/add-member")
    suspend fun addGroupMember(@Body request: AddGroupMemberRequest): Response<GroupResponse>
    
    /**
     * 移除群组成员
     */
    @POST("api/groups/remove-member")
    suspend fun removeGroupMember(@Body request: RemoveGroupMemberRequest): Response<GroupResponse>
    
    /**
     * 设置群组小助手（添加/移除）。仅群主可操作。
     */
    @POST("api/groups/set-assistant")
    suspend fun setGroupAssistant(@Body request: SetGroupAssistantRequest): Response<GroupDetailResponse>
    
    @POST("api/groups/add-assistant")
    suspend fun addGroupAssistant(@Body request: AddGroupAssistantRequest): Response<GroupDetailResponse>
    
    @POST("api/groups/remove-assistant")
    suspend fun removeGroupAssistant(@Body request: RemoveGroupAssistantRequest): Response<GroupDetailResponse>
    
    /**
     * 以小助手身份发送群组消息
     */
    @POST("api/groups/send-assistant-message")
    suspend fun sendAssistantGroupMessage(@Body request: SendAssistantGroupMessageRequest): Response<GroupResponse>
    
    /**
     * 获取用户资料
     */
    @GET("api/profile/{imei}")
    suspend fun getProfile(@Path("imei") imei: String): Response<ProfileResponse>
    
    /**
     * 更新用户资料
     */
    @POST("api/profile/{imei}")
    @FormUrlEncoded
    suspend fun updateProfile(
        @Path("imei") imei: String,
        @Field("name") name: String? = null,
        @Field("gender") gender: String? = null,
        @Field("address") address: String? = null,
        @Field("phone") phone: String? = null,
        @Field("birthday") birthday: String? = null,
        @Field("preferences") preferences: String? = null,
        @Field("avatar") avatar: String? = null
    ): Response<ProfileResponse>
    
    /**
     * 删除用户资料
     */
    @DELETE("api/profile/{imei}")
    suspend fun deleteProfile(@Path("imei") imei: String): Response<DeleteResponse>
    
    /**
     * 检查版本更新
     */
    @GET("api/version/check")
    suspend fun checkVersion(@retrofit2.http.Query("current_version") currentVersion: String? = null): Response<VersionCheckResponse>
    
    /**
     * 扫码绑定时上报 IMEI（PC 生成 token，手机扫后上报自己的 IMEI）
     */
    @POST("api/binding/{token}")
    suspend fun submitBinding(@Path("token") token: String, @Body request: BindingSubmitRequest): Response<BindingSubmitResponse>

    /**
     * 统一消息接口：获取任意会话历史（含自定义小助手）
     */
    @GET("api/messages")
    suspend fun getMessages(
        @Query("imei") imei: String,
        @Query("conversation_id") conversationId: String,
        @Query("before_id") beforeId: String? = null,
        @Query("limit") limit: Int = 100,
        @Query("since_timestamp") sinceTimestamp: Long? = null
    ): Response<UnifiedMessagesResponse>

    /**
     * 追加自定义小助手聊天消息（跨设备同步）
     */
    @POST("api/custom-assistant-chat/append")
    suspend fun appendCustomAssistantChat(@Body request: CustomAssistantChatAppendRequest): Response<CustomAssistantChatAppendResponse>

    /**
     * 获取用户的自定义小助手列表（端云同步）
     */
    @GET("api/custom-assistants")
    suspend fun getCustomAssistants(@Query("imei") imei: String): Response<CustomAssistantsResponse>

    /**
     * 同步用户的自定义小助手列表（端云同步）
     */
    @POST("api/custom-assistants")
    suspend fun syncCustomAssistants(@Body request: CustomAssistantsSyncRequest): Response<CommonSuccessResponse>

    /**
     * 多 session 拉取：从服务端获取 session 列表（用于加载时先拉取，支持删除同步）
     */
    @GET("api/sessions")
    suspend fun getSessions(
        @Query("imei") imei: String,
        @Query("conversation_id") conversationId: String,
        @Query("base_url") baseUrl: String? = null
    ): Response<SessionSyncResponse>

    /**
     * 多 session 同步：上传本地 sessions，服务端合并后返回
     */
    @POST("api/sessions/sync")
    suspend fun syncSessions(@Body request: SessionSyncRequest): Response<SessionSyncResponse>

    /** 获取跨端当前活跃 session（多 session 自定义小助手跟切） */
    @GET("api/sessions/active")
    suspend fun getActiveSession(
        @Query("imei") imei: String,
        @Query("conversation_id") conversationId: String,
        @Query("base_url") baseUrl: String? = null
    ): Response<ActiveSessionGetResponse>

    @POST("api/sessions/active")
    suspend fun setActiveSession(@Body request: ActiveSessionSetRequest): Response<ActiveSessionSetResponse>

    // ------------ 小助手广场 API ------------

    /**
     * 分页获取广场小助手列表
     */
    @GET("api/plaza-assistants")
    suspend fun getPlazaAssistants(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("imei") imei: String? = null,
        @Query("sort") sort: String? = null,
    ): Response<PlazaAssistantsResponse>

    /**
     * 将小助手上架到广场
     */
    @POST("api/plaza-assistants")
    suspend fun submitToPlaza(@Body request: PlazaSubmitRequest): Response<PlazaSubmitResponse>

    /**
     * 将广场小助手添加到当前用户的自定义列表
     */
    @POST("api/plaza-assistants/{plaza_id}/add")
    suspend fun addPlazaAssistantToMine(
        @Path("plaza_id") plazaId: String,
        @Body request: PlazaAddRequest
    ): Response<PlazaAddResponse>

    /** 创建者将广场条目下架 */
    @POST("api/plaza-assistants/{plaza_id}/remove")
    suspend fun removePlazaAssistantFromPlaza(
        @Path("plaza_id") plazaId: String,
        @Body request: PlazaAddRequest
    ): Response<PlazaRemoveResponse>

    /** 切换点赞（再点取消） */
    @POST("api/plaza-assistants/{plaza_id}/like")
    suspend fun togglePlazaLike(
        @Path("plaza_id") plazaId: String,
        @Body request: PlazaAddRequest
    ): Response<PlazaLikeResponse>
}

data class UnifiedMessagesResponse(
    val success: Boolean,
    val messages: List<UnifiedMessage>? = null,
    val has_more: Boolean = false
)

data class UnifiedMessage(
    val id: String? = null,
    val sender: String? = null,
    val content: String? = null,
    val type: String? = null,
    val created_at: String? = null,
    val sender_imei: String? = null,
    val file_base64: String? = null,
    val file_name: String? = null
)

data class CustomAssistantChatAppendRequest(
    val imei: String,
    val assistant_id: String,
    val user_content: String,
    val assistant_content: String,
    val assistant_name: String = "小助手",
    val file_base64: String? = null,
    val file_name: String? = null,
    val session_id: String? = null  // 多 session 时传入
)

data class CustomAssistantChatAppendResponse(
    val success: Boolean,
    val message_id: String? = null
)

data class CustomAssistantsResponse(
    val success: Boolean,
    val assistants: List<CustomAssistantItem>? = null
)

data class CustomAssistantItem(
    val id: String,
    val name: String,
    val intro: String? = null,
    val baseUrl: String,
    val capabilities: List<String>? = null,
    val avatar: String? = null,
    val multiSessionEnabled: Boolean? = null  // 是否支持多 session，同步/广场使用
)

data class CustomAssistantsSyncRequest(
    val imei: String,
    val assistants: List<CustomAssistantItem>
)

/** 多 session 同步请求。base_url 可选，传入时用 baseUrl 作存储 key（解决 PC/手机 assistant id 不一致） */
data class SessionSyncRequest(
    val imei: String,
    val conversation_id: String,
    val sessions: List<SessionItem>,
    val base_url: String? = null
)

data class SessionItem(
    val id: String,
    val title: String,
    val createdAt: Long
)

/** 多 session 同步响应 */
data class SessionSyncResponse(
    val success: Boolean,
    val sessions: List<SessionItem>? = null
)

data class ActiveSessionSetRequest(
    val imei: String,
    val conversation_id: String,
    val active_session_id: String,
    val base_url: String? = null
)

data class ActiveSessionSetResponse(
    val success: Boolean,
    val active_session_id: String? = null,
    val updated_at: Long? = null,
    val message: String? = null
)

data class ActiveSessionGetResponse(
    val success: Boolean,
    val active_session_id: String? = null,
    val updated_at: Long? = null
)

data class CommonSuccessResponse(
    val success: Boolean
)

// ------------ 小助手广场数据类 ------------

data class PlazaAssistantItem(
    val id: String,
    val creator_imei: String? = null,
    val creator_avatar: String? = null,
    val is_creator: Boolean? = null,
    val name: String,
    val intro: String? = null,
    val baseUrl: String,
    val capabilities: List<String>? = null,
    val avatar: String? = null,
    val multiSessionEnabled: Boolean? = null,
    val created_at: String? = null,
    val likes_count: Int? = null,
    val liked_by_me: Boolean? = null,
)

data class PlazaAssistantsResponse(
    val success: Boolean,
    val assistants: List<PlazaAssistantItem>? = null,
    val has_more: Boolean = false
)

data class PlazaSubmitRequest(
    val imei: String,
    val assistant: CustomAssistantItem
)

data class PlazaSubmitResponse(
    val success: Boolean,
    val assistant: PlazaAssistantItem? = null
)

data class PlazaAddRequest(
    val imei: String
)

data class PlazaAddResponse(
    val success: Boolean,
    val assistant: CustomAssistantItem? = null
)

data class PlazaRemoveResponse(
    val success: Boolean
)

data class PlazaLikeResponse(
    val success: Boolean,
    val likes_count: Int = 0,
    val liked_by_me: Boolean = false,
)

data class AddFriendRequest(
    val friendImei: String,
    val imei: String
)

data class AcceptFriendRequest(
    val friendImei: String,
    val imei: String
)

data class SendFriendMessageRequest(
    val targetImei: String,
    val content: String,
    val message_type: String = "text",
    val imei: String
)

data class RemoveFriendRequest(
    val friendImei: String,
    val imei: String
)

data class RegisterRequest(
    val imei: String,
    val device_info: Map<String, Any>? = null,
    val user_profile: Map<String, Any>? = null
)

data class RegisterResponse(
    val success: Boolean,
    val message: String,
    val imei: String
)

data class SendMessageRequest(
    val imei: String,
    val content: String,
    val message_type: String = "text"
)

data class SendMessageResponse(
    val success: Boolean,
    val message: String
)

data class AllChatsResponse(
    val success: Boolean,
    val message: String? = null,
    val chats: List<UserChat>? = null
)

data class UserChat(
    val imei: String,
    val messages: List<CustomerServiceChatMessage>? = null
)

data class CustomerServiceChatMessage(
    val sender: String,
    val content: String,
    val timestamp: Long,
    val message_type: String = "text"
)

data class OnlineUsersResponse(
    val success: Boolean,
    val count: Int? = null,
    val users: List<String>? = null
)

data class PcStatusResponse(
    val success: Boolean,
    val imei: String? = null,
    val is_pc_online: Boolean? = null
)

// 群组相关数据类
data class CreateGroupRequest(
    val name: String,
    val memberImeis: List<String>,
    val imei: String,
    val assistantEnabled: Boolean = true  // 是否添加小助手，可通过@小助手下达指令
)

data class SetGroupAssistantRequest(
    val groupId: String,
    val imei: String,
    val enabled: Boolean  // true=添加小助手，false=移除小助手
)

data class AddGroupAssistantRequest(
    val groupId: String,
    val imei: String,
    val assistantId: String  // assistant/skill_learning/chat_assistant/自定义ID
)

data class RemoveGroupAssistantRequest(
    val groupId: String,
    val imei: String,
    val assistantId: String
)

data class CreateGroupResponse(
    val success: Boolean,
    val message: String,
    val groupId: String? = null,
    val group: GroupInfo? = null
)

data class GroupInfo(
    val group_id: String,
    val name: String,
    val creator_imei: String,
    val members: List<String>,
    val created_at: String,
    val assistant_enabled: Boolean,
    val assistants: List<String>? = null,  // 小助手 ID 列表，空表示兼容旧数据
    val is_default_group: Boolean? = null,
    val group_manager_assistant_id: String? = null
)

data class GroupListResponse(
    val success: Boolean,
    val groups: List<GroupInfo>? = null
)

data class GroupDetailResponse(
    val success: Boolean,
    val group: GroupInfo? = null,
    val message: String? = null  // 部分 API（如 set-assistant）返回的错误/成功消息
)

data class AddGroupMemberRequest(
    val groupId: String,
    val memberImei: String,
    val imei: String
)

data class RemoveGroupMemberRequest(
    val groupId: String,
    val memberImei: String,
    val imei: String
)

data class GroupResponse(
    val success: Boolean,
    val message: String
)

data class SendAssistantGroupMessageRequest(
    val groupId: String,
    val content: String,
    val imei: String,
    val sender: String = ChatConstants.ASSISTANT_DISPLAY_NAME  // 发送者身份，默认为主助手显示名，可设置为"系统"等
)

// 绑定相关数据类
data class BindingSubmitRequest(
    val imei: String
)

data class BindingSubmitResponse(
    val success: Boolean,
    val message: String? = null
)

// 版本检查相关数据类
data class VersionCheckResponse(
    val success: Boolean,
    val current_version: String? = null,
    val latest_version: String,
    val min_supported_version: String,
    val update_url: String,
    val has_update: Boolean,
    val force_update: Boolean,
    val update_message: String,
    val last_updated: String? = null
)

