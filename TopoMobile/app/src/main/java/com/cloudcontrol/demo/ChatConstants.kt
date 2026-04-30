package com.cloudcontrol.demo

import android.media.ToneGenerator

/**
 * 聊天相关常量
 */
object ChatConstants {
    private const val TOPOCLAW_CUSTOM_ASSISTANT_ID = "custom_topoclaw"
    /** 主助手对外显示名及新会话中存储的内部名 */
    const val ASSISTANT_DISPLAY_NAME = "自动执行小助手"

    /** 与 ASSISTANT_DISPLAY_NAME 同义的历史/服务端 sender，需一并识别与展示映射 */
    const val ASSISTANT_LEGACY_TOPOCLAW_NAME = "TopoClaw"

    /** 历史版本使用的内部名，兼容旧消息与仍返回旧 sender 的服务端 */
    const val ASSISTANT_LEGACY_INTERNAL_NAME = "自动执行小助手"

    /** 键盘布局监听节流（毫秒） */
    const val KEYBOARD_CHECK_THROTTLE_MS = 100L

    const val TAG = "ChatFragment"
    const val REQUEST_MEDIA_PROJECTION_CHAT = 1002
    const val REQUEST_CODE_PICK_IMAGE = 1003
    const val REQUEST_CODE_PICK_VIDEO = 1004
    /** 保存聊天全屏图片到相册（WRITE_EXTERNAL_STORAGE，仅 API 28 及以下） */
    const val REQUEST_CODE_SAVE_CHAT_IMAGE_GALLERY = 1007
    /** mobile_tool_v1: 获取地理位置权限请求码 */
    const val REQUEST_CODE_MOBILE_TOOL_LOCATION = 1008
    const val REQUEST_MEDIA_PROJECTION_RECORDING = 1005
    val DEFAULT_CHAT_SERVER_URL: String = ServiceUrlConfig.DEFAULT_SERVER_URL
    val DEFAULT_SKILL_LEARNING_SERVER_URL: String = ServiceUrlConfig.DEFAULT_SKILL_LEARNING_URL  // 技能学习小助手默认服务地址
    val DEFAULT_CHAT_ASSISTANT_SERVER_URL: String = ServiceUrlConfig.DEFAULT_CHAT_ASSISTANT_URL  // 聊天小助手默认服务地址
    // UnifiedAssistant need_execution 时追加的引导文案，APK 支持自动执行，展示时需剔除
    const val NEED_EXECUTION_GUIDE_SUFFIX = "请点击右上角「执行」按钮（或切换到执行模式），发送当前手机屏幕截图，我将根据画面继续操作。"

    // 预设查询列表（10个）
    val PRESET_QUERIES = listOf(
        "小红书搜索gui智能体，浏览2个内容并总结给我",
        "这是哪里，导航过去",
        "图中商品是啥，帮我去淘宝拼多多搜同款",
        "问微信好友Hh源晚上想吃啥，然后执行他的命令",
        "到12306分别搜索明天深圳到成都的高铁票和机票，然后对比一下价格和耗时谁有优势",
        "打开小红书，搜索上海旅行攻略",
        "在12306找到临时身份证",
        "打开TeamTalk，到班车模块看一下从我的位置到滨海湾的最近的班车是几点",
        "微信关闭朋友圈",
        "微博关闭自动清理存储"
    )

    // 常用音调类型常量（方便测试使用）
    val COMMON_TONES = mapOf(
        "标准哔声" to ToneGenerator.TONE_PROP_BEEP,
        "确认音" to ToneGenerator.TONE_PROP_ACK,
        "否定音" to ToneGenerator.TONE_PROP_NACK,
        "提示音" to ToneGenerator.TONE_PROP_PROMPT,
        "CDMA确认" to ToneGenerator.TONE_CDMA_CONFIRM,
        "CDMA呼叫保护" to ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
        "CDMA网络提示" to ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE,
        "CDMA紧急回铃" to ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK,
        "电话铃声" to ToneGenerator.TONE_SUP_RINGTONE,
        "忙音" to ToneGenerator.TONE_SUP_BUSY,
        "错误音" to ToneGenerator.TONE_SUP_ERROR
    )

    // 默认推荐任务
    val DEFAULT_RECOMMENDATIONS = listOf(
        "帮我查今天天气",
        "在京东搜索OPPO",
        "小红书搜索gui智能体，浏览2个内容并总结给我"
    )

    /**
     * 无实际说明时的占位文案（服务端/客户端常用），不应在图片下方再画一条文字气泡。
     */
    fun isImageOnlyPlaceholderCaption(text: String): Boolean {
        val t = text.trim()
        return t == "[图片]" || t == "[图片：]"
    }

    fun isMainAssistantSender(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val normalized = sender.trim()
        return normalized == ASSISTANT_DISPLAY_NAME ||
            normalized == ASSISTANT_LEGACY_INTERNAL_NAME ||
            normalized == ASSISTANT_LEGACY_TOPOCLAW_NAME ||
            normalized == "小助手" ||
            normalized.equals("assistant", ignoreCase = true) ||
            normalized.equals("custom_topoclaw", ignoreCase = true)
    }

    fun normalizeAssistantSenderForConversation(sender: String?, conversationId: String?): String {
        val normalizedSender = sender?.trim().orEmpty()
        if (normalizedSender.isEmpty()) return normalizedSender
        val baseConversationId = conversationId
            ?.substringBefore("__")
            ?.trim()
            .orEmpty()
        val isTopoClawAlias =
            normalizedSender.equals("assistant", ignoreCase = true) ||
                normalizedSender.equals(ASSISTANT_DISPLAY_NAME, ignoreCase = true) ||
                normalizedSender.equals(ASSISTANT_LEGACY_INTERNAL_NAME, ignoreCase = true) ||
                normalizedSender.equals(ASSISTANT_LEGACY_TOPOCLAW_NAME, ignoreCase = true)

        // TopoClaw 专用会话统一把历史 sender=assistant 归一为 custom_topoclaw
        if (baseConversationId.equals(TOPOCLAW_CUSTOM_ASSISTANT_ID, ignoreCase = true) && isTopoClawAlias) {
            return TOPOCLAW_CUSTOM_ASSISTANT_ID
        }

        // 兼容旧链路：会话 id 丢失/仍为 assistant 时，避免气泡 owner 回退成 assistant。
        // 统一收敛到 custom_topoclaw，确保展示与会话所有者一致。
        if (isTopoClawAlias && (
                baseConversationId.isBlank() ||
                    baseConversationId.equals("assistant", ignoreCase = true)
            )
        ) {
            return TOPOCLAW_CUSTOM_ASSISTANT_ID
        }
        return normalizedSender
    }

    fun containsMainAssistantMention(text: String): Boolean =
        text.contains("@$ASSISTANT_DISPLAY_NAME", ignoreCase = true) ||
            text.contains("@$ASSISTANT_LEGACY_INTERNAL_NAME", ignoreCase = true) ||
            text.contains("@$ASSISTANT_LEGACY_TOPOCLAW_NAME", ignoreCase = true) ||
            text.contains("@自动执行助手", ignoreCase = true)

    /** 远程指令场景：已 @ 主助手或其别名时不再自动加前缀 */
    fun containsRemoteStyleAssistantMention(text: String): Boolean =
        containsMainAssistantMention(text) ||
            text.contains("@小助手", ignoreCase = true) ||
            text.contains("@assistant", ignoreCase = true)

    fun stripMainAssistantMentions(text: String): String {
        var s = text
        s = s.replace("@$ASSISTANT_DISPLAY_NAME", "", ignoreCase = true)
        s = s.replace("@$ASSISTANT_LEGACY_INTERNAL_NAME", "", ignoreCase = true)
        s = s.replace("@$ASSISTANT_LEGACY_TOPOCLAW_NAME", "", ignoreCase = true)
        s = s.replace("@自动执行助手", "", ignoreCase = true)
        return s
    }
}
