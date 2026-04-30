package com.cloudcontrol.demo

import android.util.Log

/**
 * 技能步骤解析器
 * 将技能步骤文本转换为云侧需要的 user_example 格式
 */
object SkillStepParser {
    private const val TAG = "SkillStepParser"
    
    /**
     * 将技能步骤列表转换为 user_example 字符串
     * 格式：'[ActionBean(action_type=open, reason=打开AutoGLM, app_name=, click=, text=, wait=, swipe=)]'
     * 
     * @param steps 技能步骤列表
     * @return user_example 字符串
     */
    fun parseStepsToUserExample(steps: List<String>): String {
        val actionBeans = mutableListOf<String>()
        
        steps.forEachIndexed { index, step ->
            val actionBean = parseStepToActionBean(step.trim(), index)
            if (actionBean.isNotEmpty()) {
                actionBeans.add(actionBean)
            }
        }
        
        val result = "[${actionBeans.joinToString(", ")}]"
        Log.d(TAG, "解析技能步骤完成，共${actionBeans.size}个动作: $result")
        return result
    }
    
    /**
     * 将单个步骤文本解析为 ActionBean 字符串
     * @param step 步骤文本
     * @param index 步骤索引
     * @return ActionBean 字符串
     */
    private fun parseStepToActionBean(step: String, index: Int): String {
        if (step.isEmpty()) {
            return ""
        }
        
        // 移除步骤编号（如 "1. "、"step1: " 等）
        val cleanStep = step.replace(Regex("^(\\d+[.、]?|step\\d+[:：]?)\\s*"), "").trim()
        
        // 识别动作类型和参数
        val actionType = detectActionType(cleanStep)
        val reason = cleanStep // 使用原始步骤作为reason
        
        // 提取应用名称（如果是open动作）
        val appName = if (actionType == "open") {
            extractAppName(cleanStep)
        } else {
            ""
        }
        
        // 构建 ActionBean 字符串
        return "ActionBean(action_type=$actionType, reason=$reason, app_name=$appName, click=, text=, wait=, swipe=)"
    }
    
    /**
     * 检测动作类型
     * @param step 步骤文本
     * @return 动作类型（open, click, type, swipe, wait等）
     */
    private fun detectActionType(step: String): String {
        val lowerStep = step.lowercase()
        
        return when {
            // 打开应用
            lowerStep.contains("打开") || lowerStep.contains("启动") || lowerStep.startsWith("open") -> "open"
            // 点击
            lowerStep.contains("点击") || lowerStep.contains("点") || lowerStep.contains("选择") || 
            lowerStep.contains("点击") || lowerStep.startsWith("click") -> "click"
            // 输入文本
            lowerStep.contains("输入") || lowerStep.contains("输入文本") || lowerStep.contains("输入内容") ||
            lowerStep.contains("填写") || lowerStep.startsWith("type") || lowerStep.startsWith("input") -> "type"
            // 滑动
            lowerStep.contains("滑动") || lowerStep.contains("划") || lowerStep.contains("滚动") ||
            lowerStep.startsWith("swipe") || lowerStep.startsWith("scroll") -> "swipe"
            // 等待
            lowerStep.contains("等待") || lowerStep.contains("暂停") || lowerStep.startsWith("wait") -> "wait"
            // 长按
            lowerStep.contains("长按") || lowerStep.contains("按住") || lowerStep.startsWith("long_press") -> "long_press"
            // 返回
            lowerStep.contains("返回") || lowerStep.contains("后退") || lowerStep.startsWith("back") -> "back"
            // 默认：根据上下文判断，如果包含应用名可能是open，否则是click
            else -> {
                // 如果包含常见应用名，可能是open
                if (containsAppName(step)) {
                    "open"
                } else {
                    "click" // 默认是点击
                }
            }
        }
    }
    
    /**
     * 提取应用名称
     * @param step 步骤文本
     * @return 应用名称
     */
    private fun extractAppName(step: String): String {
        // 常见应用名称列表
        val appNames = listOf(
            "小红书", "微信", "支付宝", "淘宝", "京东", "抖音", "快手", "微博", "QQ", "钉钉",
            "设置", "浏览器", "相机", "相册", "音乐", "视频", "地图", "天气", "时钟", "日历",
            "AutoGLM", "Chrome", "Firefox", "Safari", "Edge"
        )
        
        for (appName in appNames) {
            if (step.contains(appName)) {
                return appName
            }
        }
        
        // 如果没有匹配到，尝试从"打开XXX"中提取
        val openMatch = Regex("打开([^，,。.\\s]+)").find(step)
        if (openMatch != null) {
            return openMatch.groupValues[1]
        }
        
        return ""
    }
    
    /**
     * 检查步骤是否包含应用名称
     * @param step 步骤文本
     * @return 是否包含应用名称
     */
    private fun containsAppName(step: String): Boolean {
        val appNames = listOf(
            "小红书", "微信", "支付宝", "淘宝", "京东", "抖音", "快手", "微博", "QQ", "钉钉",
            "设置", "浏览器", "相机", "相册", "音乐", "视频", "地图", "天气", "时钟", "日历"
        )
        return appNames.any { step.contains(it) }
    }
}

