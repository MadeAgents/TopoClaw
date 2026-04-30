package com.cloudcontrol.demo

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作数据转换工具类
 * 将 ActionRecord 转换为云侧需要的 user_example 格式
 */
object ActionConverter {
    
    /**
     * 将 ActionRecord 列表转换为 user_example JSON 数组
     * @param actions 动作记录列表
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return JSON 数组字符串
     */
    fun convertToUserExample(actions: List<ActionRecord>, screenWidth: Int, screenHeight: Int): String {
        val jsonArray = JSONArray()
        
        actions.forEach { action ->
            val userExample = convertActionToUserExample(action, screenWidth, screenHeight)
            if (userExample != null) {
                jsonArray.put(userExample)
            }
        }
        
        return jsonArray.toString()
    }
    
    /**
     * 将单个 ActionRecord 转换为 user_example JSON 对象
     * @param action 动作记录
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return JSON 对象，如果无法转换则返回 null
     */
    private fun convertActionToUserExample(action: ActionRecord, screenWidth: Int, screenHeight: Int): JSONObject? {
        // 只处理有点击坐标的动作
        if (action.centerX == null || action.centerY == null) {
            return null
        }
        
        // 映射动作类型
        val actionType = when (action.type) {
            "click" -> "ACTION_CLICK"
            "long_click" -> "ACTION_LONG_CLICK"
            "swipe" -> "ACTION_SWIPE"
            "scroll" -> "ACTION_SCROLL"
            else -> null
        }
        
        // 如果动作类型不支持，跳过
        if (actionType == null) {
            return null
        }
        
        val json = JSONObject()
        json.put("actionType", actionType)
        json.put("timestamp", action.relativeTimeMs.toInt()) // 使用相对时间（毫秒）
        json.put("screenWidth", screenWidth)
        json.put("screenHeight", screenHeight)
        
        // 构建 target 对象
        val target = JSONObject()
        action.packageName?.let { target.put("packageName", it) }
        action.className?.let { target.put("className", it) }
        
        // 构建 point 对象
        val point = JSONObject()
        point.put("x", action.centerX)
        point.put("y", action.centerY)
        target.put("point", point)
        
        // duration 字段：对于点击动作，使用默认值或从 bounds 计算
        // 这里使用一个合理的默认值（56ms，类似示例）
        val duration = if (action.type == "long_click") {
            // 长按动作可能需要更长的持续时间，但这里我们使用相对时间差
            // 由于无法准确获取，使用默认值
            500
        } else {
            56 // 默认点击持续时间
        }
        target.put("duration", duration)
        
        json.put("target", target)
        
        return json
    }
}

