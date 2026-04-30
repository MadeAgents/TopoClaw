package com.cloudcontrol.demo

import java.io.Serializable

/**
 * 数据模型类
 * 用于定义与云侧通信的数据结构
 */

/**
 * 发送到云侧的请求数据
 */
data class ScreenshotRequest(
    val image: String,  // Base64编码的图片
    val query: String? = null,  // 可选的查询参数
    val screen_width: Int = 1080,  // 固定截图宽度
    val screen_height: Int = 1920  // 固定截图高度
)

/**
 * 云侧返回的响应数据
 */
data class CloudResponse(
    val action: String,  // 操作类型，如 "click"
    val x: Int? = null,  // 点击的x坐标
    val y: Int? = null,  // 点击的y坐标
    val message: String? = null  // 可选的消息
)

/**
 * 聊天请求数据
 */
data class ChatRequest(
    val query: String,      // 用户输入的查询
    val uuid: String,       // 唯一标识
    val images: List<String>  // Base64截图数组
)

/**
 * 视频分析项数据
 */
data class VideoAnalysisItem(
    val action_type: String? = null,  // 操作类型，如 "click", "type", "open", "summary" 等
    val reason: String? = null  // 操作原因或描述
)

/**
 * 聊天响应数据
 */
data class ChatResponse(
    val message: String? = null,  // 云侧返回的消息
    val action: String? = null,  // 可选的操作指令（旧版兼容）
    val action_type: String? = null,  // 操作类型，如 "open", "click", "type", "swipe", "wait", "long_press", "answer", "call_user", "complete", "learning" 等
    val reason: String? = null,  // 操作原因
    val thought: String? = null,  // 思考过程
    val app_name: String? = null,  // 应用名称（用于open操作）
    val text: String? = null,  // 操作文本（用于type和answer操作）
    val params: String? = null,  // 参数（用于answer操作）
    val x: Int? = null,  // 点击坐标x（旧版兼容）
    val y: Int? = null,  // 点击坐标y（旧版兼容）
    val click: List<Int>? = null,  // 点击坐标数组 [x, y]（用于click操作）
    val coordinates: List<Int>? = null,  // 通用坐标数组 [x, y]（兼容部分新响应格式）
    val swipe: List<Int>? = null,  // 滑动坐标数组 [x1, y1, x2, y2]（用于swipe操作）
    val type: List<Any>? = null,  // 输入操作数组，支持两种格式：1) [x, y, text] 或 [x, y, "text"] - 先点击坐标再输入；2) [text] - 直接输入到当前聚焦的输入框（用于type操作）
    val long_press: List<Int>? = null,  // 长按坐标数组 [x, y]（用于long_press操作）
    val drag: List<Int>? = null,  // 拖拽参数数组 [x1, y1, x2, y2]（用于drag操作），x1,y1为起始坐标，x2,y2为目标坐标
    val long_screenshot: Map<String, Any>? = null,  // 长截图参数 {direction: "down"/"up", steps: Int}（用于long_screenshot操作）
    val video_analysis: List<VideoAnalysisItem>? = null,  // 视频分析结果数组（用于learning操作）
    val planner_message: String? = null,  // 规划智能体的内部消息（用户不可见，但端侧可以接收用于日志、调试等）
    val auto_submit: Boolean? = null,  // 是否自动提交（用于type操作，在搜索页面输入后自动回车）
    val swipe_search_context: Map<String, Any>? = null  // 滑动搜索上下文（用于swipe_search操作）
)

/**
 * next_action 接口请求体（JSON）
 */
data class NextActionRequest(
    val task_id: String,
    val duid: String,
    val image_url: String,
    val query: String,
    val user_response: String? = null,
    val package_name: String? = null,
    val class_name: String? = null,
    val install_apps: List<String>? = null
)

/**
 * next_action 接口返回的单个动作
 */
data class NextActionMessage(
    val action: String? = null,
    val reason: String? = null,
    val thought: String? = null,
    val arguments: Any? = null
)

/**
 * next_action 接口响应
 */
data class NextActionResponse(
    val status: Boolean,
    val message: List<NextActionMessage>? = null,
    val task_id: String? = null,
    val error_msg: String? = null
) {
    /**
     * 转换为 ChatResponse 格式，供端侧统一处理
     */
    fun toChatResponse(): ChatResponse? {
        if (!status || message.isNullOrEmpty()) return null
        val first = message.first()
        val actionType = first.action ?: return null
        val args = first.arguments
        var click: List<Int>? = null
        var swipe: List<Int>? = null
        var text: String? = null
        var appName: String? = null
        var params: String? = null
        var longPress: List<Int>? = null
        var drag: List<Int>? = null
        var typeList: List<Any>? = null
        when (args) {
            is List<*> -> {
                val nums = args.filterIsInstance<Number>().map { it.toInt() }
                when (actionType) {
                    "click" -> if (nums.size >= 2) click = nums.take(2)
                    "swipe" -> if (nums.size >= 4) swipe = nums.take(4)
                    "long_press" -> if (nums.size >= 2) longPress = nums.take(2)
                    "drag" -> if (nums.size >= 4) drag = nums.take(4)
                    "type" -> {
                        if (args.size >= 3) {
                            text = args.lastOrNull()?.toString()
                            typeList = args.mapNotNull { it }
                        } else if (args.isNotEmpty()) {
                            text = args.lastOrNull()?.toString()
                        }
                    }
                    "get_wechat_link" -> if (args.isNotEmpty()) params = args.firstOrNull()?.toString()
                    else -> if (nums.size >= 2) click = nums.take(2)
                }
            }
            is Number -> {
                if (actionType == "get_wechat_link") params = args.toString()
            }
            is String -> {
                when (actionType) {
                    "open" -> appName = args
                    "complete", "answer" -> params = args
                    "type" -> {
                        text = args
                        val parts = args.split(",", limit = 3)
                        if (parts.size >= 3) {
                            val x = parts[0].trim().toIntOrNull()
                            val y = parts[1].trim().toIntOrNull()
                            if (x != null && y != null) {
                                typeList = listOf(x, y, parts[2].trim())
                            }
                        }
                    }
                    else -> params = args
                }
            }
        }
        return ChatResponse(
            action_type = actionType,
            reason = first.reason,
            thought = first.thought,
            click = click,
            swipe = swipe,
            text = text,
            app_name = appName,
            params = params,
            long_press = longPress,
            drag = drag,
            type = typeList
        )
    }
}

/**
 * 批测查询响应数据
 */
data class BatchQueriesResponse(
    val queries: List<String>  // 查询列表
)

/**
 * 动态批测第一个任务响应数据
 */
data class FirstTaskResponse(
    val query: String?,  // 第一个任务（如果列表为空则为null）
    val index: Int,  // 任务索引（总是0）
    val total: Int  // 总任务数
)

/**
 * 个性化档案数据
 */
/**
 * 对话数据类
 */
data class Conversation(
    val id: String,
    val name: String,
    val avatar: String? = null,  // Base64编码的头像
    val lastMessage: String? = null,
    val lastMessageTime: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isPinned: Boolean = false  // 是否置顶
) : java.io.Serializable

data class UserProfile(
    val imei: String,
    val name: String? = null,
    val gender: String? = null,  // "男"/"女"/"其他"
    val address: String? = null,
    val phone: String? = null,
    val birthday: String? = null,  // 格式：YYYY-MM-DD
    val preferences: String? = null,  // 喜好（自由文本）
    val avatar: String? = null,  // 头像（Base64编码）
    val updatedAt: Long = System.currentTimeMillis()
) : java.io.Serializable

/**
 * 个性化档案响应数据
 */
data class ProfileResponse(
    val success: Boolean,
    val message: String? = null,
    val profile: UserProfile? = null
)

/**
 * 删除响应数据
 */
data class DeleteResponse(
    val success: Boolean,
    val message: String? = null
)

/**
 * 定时类型枚举
 */
enum class ScheduleType {
    ONCE,        // 单次
    DAILY,       // 每天
    WEEKLY,      // 每周
    MONTHLY      // 每月
}

/**
 * 技能定时配置
 */
data class SkillScheduleConfig(
    val isEnabled: Boolean,              // 是否启用定时
    val scheduleType: ScheduleType,      // 定时类型
    val targetTime: Long,                // 目标时间戳（毫秒）
    val repeatDays: List<Int>? = null,   // 重复的星期几（0=周日，1=周一...）
    val nextTriggerTime: Long? = null    // 下次触发时间
) : java.io.Serializable

/**
 * 技能数据类
 */
data class Skill(
    val id: String,                    // 唯一标识（UUID）
    val title: String,                 // 卡片名称（简化后的用户操作目的）
    val steps: List<String>,           // 操作步骤列表
    val createdAt: Long,               // 创建时间戳
    val originalPurpose: String? = null, // 原始操作目的（可选，用于调试）
    val isHot: Boolean = false,         // 是否为热门技能（默认false）
    val hotSetAt: Long? = null,         // 设置为热门的时间戳（可选）
    val scheduleConfig: SkillScheduleConfig? = null  // 定时配置（可选）
) : java.io.Serializable

/**
 * 技能服务响应数据（新格式）
 */
data class SkillServiceResponse(
    val skills: List<Skill>
)

/**
 * 技能服务原始响应数据（旧格式，用于兼容）
 */
data class OldSkillServiceResponse(
    val skills: List<OldSkill>
)

/**
 * 旧格式技能数据
 */
data class OldSkill(
    val skill_id: String,
    val skill_name: String,
    val skill_type: String?,
    val skill_desc: String,
    val skill_actions: List<OldSkillAction>
)

/**
 * 旧格式技能动作
 */
data class OldSkillAction(
    val action_type: String,
    val reason: String,
    val app_name: String? = null,
    val click: String? = null,
    val text: String? = null,
    val wait: String? = null,
    val swipe: String? = null
)

/**
 * 技能上传请求数据
 */
data class SkillUploadRequest(
    val skill_name: String,
    val skill_type: String? = null,
    val skill_desc: String,
    val skill_actions: List<SkillAction>
)

/**
 * 技能动作数据（用于上传）
 */
data class SkillAction(
    val action_type: String,
    val reason: String,
    val app_name: String? = null,
    val click: String? = null,
    val text: String? = null,
    val wait: String? = null,
    val swipe: String? = null
)

/**
 * 技能上传响应数据
 */
data class SkillUploadResponse(
    val skill_id: String,
    val message: String
)

/**
 * 总结响应数据
 */
data class SummaryResponse(
    val success: Boolean,
    val summary: String? = null,
    val message: String? = null,
    val recommendations: List<String>? = null
)

/**
 * 聊天小助手（Chat_Assistants 后端）请求/响应
 * 对应 POST /chat 接口
 */
data class ChatAssistantChatRequest(
    val thread_id: String,
    val message: String
)

data class ChatAssistantChatResponse(
    val response: String,
    val thread_id: String,
    val need_execution: Boolean = false,
    val reason: String? = null,
    val chat_summary: String? = null
)

/** 聊天小助手历史 GET /chat/history */
data class ChatAssistantHistoryResponse(
    val messages: List<ChatAssistantHistoryMessage> = emptyList(),
    val thread_id: String? = null
)

data class ChatAssistantHistoryMessage(
    val role: String,
    val content: String,
    val order: Int = 0
)

/**
 * SOPs.json 任务数据类（用于解析本地技能库）
 */
data class SOPTask(
    val query: String,
    val task_plan: TaskPlan
)

/**
 * 任务规划数据类
 */
data class TaskPlan(
    val initial_task_breakdown: List<String>
)

/**
 * 版本信息响应数据
 */
data class VersionInfoResponse(
    val success: Boolean,
    val version: String? = null,
    val message: String? = null
)

/**
 * 好友数据类
 */
data class Friend(
    val imei: String,
    val nickname: String? = null,  // 好友备注名
    val avatar: String? = null,    // 好友头像
    val status: String = "accepted", // pending/accepted/blocked
    val addedAt: Long = System.currentTimeMillis()
) : java.io.Serializable

/**
 * 好友请求响应数据
 */
data class FriendRequestResponse(
    val success: Boolean,
    val message: String? = null
)

/**
 * 好友列表响应数据
 */
data class FriendListResponse(
    val success: Boolean,
    val friends: List<Friend>? = null,
    val message: String? = null
)

/**
 * 轨迹采集相关数据模型
 */

/**
 * 轨迹事件类型
 */
enum class TrajectoryEventType {
    CLICK,           // 点击
    LONG_CLICK,      // 长按
    SWIPE,           // 滑动
    SCROLL,          // 滚动
    TEXT_INPUT,      // 文本输入
    TEXT_CHANGE,     // 文本变化
    WINDOW_CHANGE,   // 窗口切换
    TOUCH,           // 触摸事件（原始）
    KEYBOARD_SHOW,   // 键盘弹出
    KEYBOARD_HIDE,   // 键盘收起
    CLIPBOARD_CHANGE, // 剪切板变化
    BACK_BUTTON,     // 返回按钮（悬浮球）
    HOME_BUTTON,     // 主页按钮（悬浮球）
    SESSION_END      // 会话结束（点击结束采集按钮时的最后一张截图）
}

/**
 * 轨迹事件数据
 */
data class TrajectoryEvent(
    val type: TrajectoryEventType,           // 事件类型
    val timestamp: Long,                     // 时间戳（毫秒）- 统一使用 System.currentTimeMillis()
    val sequenceNumber: Long? = null,         // 序列号（用于保证事件顺序）
    val x: Int? = null,                       // X坐标
    val y: Int? = null,                       // Y坐标
    val startX: Int? = null,                  // 起始X坐标（用于滑动）
    val startY: Int? = null,                  // 起始Y坐标（用于滑动）
    val endX: Int? = null,                    // 结束X坐标（用于滑动）
    val endY: Int? = null,                    // 结束Y坐标（用于滑动）
    val duration: Long? = null,               // 持续时间（毫秒，用于长按）
    val text: String? = null,                 // 文本内容（用于输入）
    val packageName: String? = null,         // 应用包名
    val className: String? = null,            // 类名
    val contentDescription: String? = null,   // 内容描述
    val viewId: String? = null,               // View ID
    val scrollX: Int? = null,                 // 滚动X偏移（用于滚动）
    val scrollY: Int? = null,                 // 滚动Y偏移（用于滚动）
    val action: Int? = null                   // MotionEvent action（用于触摸事件）
) : java.io.Serializable

/**
 * 设备信息
 */
data class DeviceInfo(
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val densityDpi: Int
) : java.io.Serializable

/**
 * 轨迹会话数据
 */
data class TrajectorySession(
    val sessionId: String,                    // 会话ID（格式：yyyyMMdd_HHmmss）
    val startTime: Long,                     // 开始时间戳（毫秒）
    val endTime: Long? = null,                // 结束时间戳（毫秒）
    val deviceInfo: DeviceInfo,              // 设备信息
    val events: MutableList<TrajectoryEvent>  // 事件列表
) : java.io.Serializable

