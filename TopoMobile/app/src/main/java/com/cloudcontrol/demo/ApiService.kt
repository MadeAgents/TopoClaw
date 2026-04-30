package com.cloudcontrol.demo

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * 定义与云侧通信的API接口
 * 使用Retrofit库进行网络请求
 */
interface ApiService {
    
    /**
     * 发送截图到云侧
     * @param image 图片的Base64编码字符串
     * @param query 可选的查询参数
     * @param screenWidth 屏幕宽度（固定1080）
     * @param screenHeight 屏幕高度（固定1920）
     * @param packageName 当前 Activity 的包名
     * @param className 当前 Activity 的类名
     * @return 云侧返回的指令
     */
    @POST("/api/analyze-screenshot")
    @FormUrlEncoded
    suspend fun sendScreenshot(
        @Field("image") image: String,
        @Field("query") query: String? = null,
        @Field("screen_width") screenWidth: Int = 1080,
        @Field("screen_height") screenHeight: Int = 1920,
        @Field("package_name") packageName: String? = null,
        @Field("class_name") className: String? = null
    ): Response<CloudResponse>
    
    /**
     * 使用Multipart方式发送（备选方案，如果云侧需要文件上传）
     */
    @Multipart
    @POST("/api/analyze-screenshot")
    suspend fun sendScreenshotMultipart(
        @Part image: MultipartBody.Part,
        @Part("query") query: RequestBody? = null,
        @Part("screen_width") screenWidth: RequestBody,
        @Part("screen_height") screenHeight: RequestBody
    ): Response<CloudResponse>
    
    /**
     * 发送聊天消息到云侧
     * @param query 用户输入的查询
     * @param uuid 唯一标识
     * @param image Base64编码的截图（第一张图片）
     * @param image2 可选的第二张图片（Base64编码，用于长截图的当前屏幕截图）
     * @param userResponse 用户回复（用于call_user动作）
     * @param userExample 用户示例（用于技能执行，格式：'[ActionBean(...)]'）
     * @param chatSummary 对话总结（用于规划智能体）
     * @param packageName 当前 Activity 的包名
     * @param className 当前 Activity 的类名
     * @return 云侧返回的响应
     * 注意：路径不使用前导斜杠，以便Retrofit正确拼接baseUrl中的路径部分（如/v2/）
     */
    @POST("upload")
    @FormUrlEncoded
    suspend fun sendChatMessage(
        @Field("uuid") uuid: String,
        @Field("query") query: String,
        @Field(value = "images[0]", encoded = true) image: String,  // encoded=true 表示不进行URL编码
        @Field(value = "images[1]", encoded = true) image2: String? = null,  // 可选的第二张图片
        @Field("user_response") userResponse: String? = null,  // 用户回复（用于call_user动作）
        @Field("user_example") userExample: String? = null,  // 用户示例（用于技能执行）
        @Field("chat_summary") chatSummary: String? = null,  // 对话总结（用于规划智能体）
        @Field("package_name") packageName: String? = null,  // 当前 Activity 的包名
        @Field("class_name") className: String? = null,  // 当前 Activity 的类名
        @Field("imei") imei: String? = null,  // 用户设备标识
        @Field("pro_mode") proMode: Boolean? = null,  // Beta模式标识
        @Field("batch_test_mode") batchTestMode: Boolean? = null,  // 批测模式标识
        @Field("stream") stream: Boolean? = null,  // 是否启用流式返回（SSE）
        @Field("swipe_search_context") swipeSearchContext: String? = null,  // 滑动搜索上下文（JSON格式）
        @Field("output_language") outputLanguage: String? = null,  // 输出语言：en=英语，zh=中文，用于 task summary 等回复内容
        @Field("install_apps") installApps: String? = null  // 已安装应用列表（MVP格式，JSON数组字符串，如 ["微信","支付宝"]）
    ): Response<ChatResponse>
    
    /**
     * 调用 next_action 接口（JSON Body，支持 install_apps）
     * 当「切换到 next_action 接口」开关开启时使用
     */
    @POST("next_action")
    suspend fun sendNextActionRequest(
        @Body body: NextActionRequest
    ): Response<NextActionResponse>
    
    /**
     * 获取批测查询列表
     * @return 批测查询响应
     * 注意：路径不使用前导斜杠，以便Retrofit正确拼接baseUrl中的路径部分（如/v5/）
     */
    @GET("get_batch_queries")
    suspend fun getBatchQueries(): Response<BatchQueriesResponse>
    
    /**
     * 获取动态批测的第一个任务
     * @return 第一个任务响应
     * 注意：路径不使用前导斜杠，以便Retrofit正确拼接baseUrl中的路径部分（如/v5/）
     */
    @GET("get_first_task")
    suspend fun getFirstTask(): Response<FirstTaskResponse>
    
    /**
     * 获取个性化档案
     * @param imei 用户IMEI
     * @return 个性化档案响应
     */
    @GET("/api/profile/{imei}")
    suspend fun getProfile(@Path("imei") imei: String): Response<ProfileResponse>
    
    /**
     * 更新个性化档案
     * @param imei 用户IMEI
     * @param name 姓名
     * @param gender 性别
     * @param address 地址
     * @param phone 电话号码
     * @param birthday 生日
     * @param preferences 喜好
     * @param avatar 头像（Base64编码）
     * @return 个性化档案响应
     */
    @POST("/api/profile/{imei}")
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
     * 删除个性化档案
     * @param imei 用户IMEI
     * @return 删除响应
     */
    @DELETE("/api/profile/{imei}")
    suspend fun deleteProfile(@Path("imei") imei: String): Response<DeleteResponse>
    
    /**
     * 上传视频和动作数据到云侧
     * @param uuid 任务唯一标识符
     * @param query 用户查询文本
     * @param video 视频文件
     * @return 云侧返回的响应
     * 注意：不再发送 user_example 字段，云侧将使用基于均匀取帧的视频解析方式
     * 注意：路径不使用前导斜杠，以便Retrofit正确拼接baseUrl中的路径部分（如/v2/）
     */
    @Multipart
    @POST("upload")
    suspend fun uploadVideo(
        @Part("uuid") uuid: RequestBody,
        @Part("query") query: RequestBody,
        @Part video: MultipartBody.Part,
        @Part("imei") imei: RequestBody? = null  // 用户设备标识
    ): Response<ChatResponse>
    
    /**
     * 获取聊天小助手历史（跨设备同步）
     * @param threadId 会话 ID，需与发送时一致（uuid5(imei_chat_assistant)）
     */
    @GET("chat/history")
    suspend fun getChatAssistantHistory(
        @Query("thread_id") threadId: String,
        @Query("limit") limit: Int = 100
    ): Response<ChatAssistantHistoryResponse>

    /**
     * 请求总结聊天历史
     * @param uuid 任务唯一标识符
     * @param chatHistory 聊天历史记录（JSON格式）
     * @return 云侧返回的总结结果
     */
    @POST("/api/summarize-chat")
    @FormUrlEncoded
    suspend fun summarizeChatHistory(
        @Field("uuid") uuid: String,
        @Field("chat_history") chatHistory: String,
        @Field("request_type") requestType: String = "summary"
    ): Response<SummaryResponse>
    
    /**
     * 获取云侧版本信息
     * @return 版本信息响应
     */
    @GET("/api/version")
    suspend fun getVersionInfo(): Response<VersionInfoResponse>
    
    /**
     * 发送用户反馈到云侧
     * @param uuid 任务唯一标识符
     * @param feedback 反馈评分（0-5，0表示未评分）
     * @param feedbackText 反馈文本内容（可选）
     * @return 云侧返回的响应
     * 注意：路径不使用前导斜杠，以便Retrofit正确拼接baseUrl中的路径部分（如/v2/）
     */
    @POST("upload")
    @FormUrlEncoded
    suspend fun sendFeedback(
        @Field("uuid") uuid: String,
        @Field("query") query: String = "",  // 必需字段，但可以为空
        @Field(value = "images[0]", encoded = true) image: String = "data:image/png;base64,",  // 必需字段，但可以为空Base64
        @Field("feedback") feedback: Int,
        @Field("feedback_text") feedbackText: String? = null
    ): Response<ChatResponse>
    
    /**
     * 保存动作对比截图（只保存动作执行前）
     * @param uuid 任务唯一标识符
     * @param step 步骤号
     * @param actionType 动作类型
     * @param image Base64编码的图片
     * @return 云侧返回的响应
     */
    @POST("/api/save-action-comparison-screenshot")
    @FormUrlEncoded
    suspend fun saveActionComparisonScreenshot(
        @Field("uuid") uuid: String,
        @Field("step") step: Int,
        @Field("action_type") actionType: String,
        @Field(value = "image", encoded = true) image: String
    ): Response<Map<String, Any>>
    
    /**
     * 上传剪切板内容到云侧
     * @param uuid 任务唯一标识符
     * @param clipboardContent 剪切板文本内容
     * @param clipboardTimestamp 剪切板内容的时间戳
     * @return 云侧返回的响应
     * 注意：路径不使用前导斜杠，以便Retrofit正确拼接baseUrl中的路径部分（如/v2/）
     */
    @POST("upload")
    @FormUrlEncoded
    suspend fun uploadClipboardContent(
        @Field("uuid") uuid: String,
        @Field("clipboard_content") clipboardContent: String,
        @Field("clipboard_timestamp") clipboardTimestamp: String
    ): Response<ChatResponse>
    
    /**
     * 上传轨迹事件到云侧
     * @param sessionId 会话ID
     * @param eventData 事件数据（JSON格式）
     * @param screenshot 可选的截图文件
     * @return 云侧返回的响应
     */
    @Multipart
    @POST("api/trajectory/upload-event")
    suspend fun uploadTrajectoryEvent(
        @Part("session_id") sessionId: RequestBody,
        @Part("event_data") eventData: RequestBody,
        @Part screenshot: MultipartBody.Part? = null,
        @Part xml: MultipartBody.Part? = null
    ): Response<Map<String, Any>>
    
    /**
     * 重命名云侧轨迹会话目录
     * 将 service_tmp/trajectory_data/{old_session_id} 重命名为 service_tmp/trajectory_data/{new_session_id}
     * @param oldSessionId 原会话ID（如 20260221_010435）
     * @param newSessionId 新会话ID（如 20260221_010435_打开设置）
     */
    @FormUrlEncoded
    @POST("api/trajectory/rename-session")
    suspend fun renameTrajectorySession(
        @Field("old_session_id") oldSessionId: String,
        @Field("new_session_id") newSessionId: String
    ): Response<Map<String, Any>>
}

