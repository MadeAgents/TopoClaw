package com.cloudcontrol.demo

import android.graphics.Rect
import org.json.JSONObject

/**
 * 动作记录数据模型
 */
data class ActionRecord(
    val actionId: Int,
    val type: String,  // click, swipe, scroll, long_click, window_changed 等
    val eventType: Int? = null,  // AccessibilityEvent 的事件类型
    val packageName: String? = null,
    val className: String? = null,
    val viewText: String? = null,
    val contentDescription: String? = null,
    val bounds: Rect? = null,  // 控件边界
    val centerX: Int? = null,  // 中心点X坐标
    val centerY: Int? = null,  // 中心点Y坐标
    val scrollDeltaX: Int? = null,  // 滚动X偏移
    val scrollDeltaY: Int? = null,  // 滚动Y偏移
    val gestureType: String? = null,  // 手势类型（如 swipe_right）
    val timestamp: Long,  // 绝对时间戳
    val relativeTimeMs: Long  // 相对于录制开始的时间（毫秒）
) {
    /**
     * 转换为JSON对象
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("action_id", actionId)
        json.put("type", type)
        eventType?.let { json.put("event_type", it) }
        packageName?.let { json.put("package_name", it) }
        className?.let { json.put("class_name", it) }
        viewText?.let { json.put("view_text", it) }
        contentDescription?.let { json.put("content_description", it) }
        bounds?.let {
            val boundsJson = JSONObject()
            boundsJson.put("left", it.left)
            boundsJson.put("top", it.top)
            boundsJson.put("right", it.right)
            boundsJson.put("bottom", it.bottom)
            json.put("bounds", boundsJson)
        }
        centerX?.let { json.put("center_x", it) }
        centerY?.let { json.put("center_y", it) }
        scrollDeltaX?.let { json.put("scroll_delta_x", it) }
        scrollDeltaY?.let { json.put("scroll_delta_y", it) }
        gestureType?.let { json.put("gesture_type", it) }
        json.put("timestamp", timestamp)
        json.put("relative_time_ms", relativeTimeMs)
        return json
    }
    
    companion object {
        /**
         * 从JSON对象创建ActionRecord
         */
        fun fromJson(json: JSONObject): ActionRecord {
            val bounds = json.optJSONObject("bounds")?.let {
                Rect(
                    it.getInt("left"),
                    it.getInt("top"),
                    it.getInt("right"),
                    it.getInt("bottom")
                )
            }
            
            return ActionRecord(
                actionId = json.getInt("action_id"),
                type = json.getString("type"),
                eventType = if (json.has("event_type")) json.getInt("event_type") else null,
                packageName = json.optString("package_name").takeIf { it.isNotEmpty() },
                className = json.optString("class_name").takeIf { it.isNotEmpty() },
                viewText = json.optString("view_text").takeIf { it.isNotEmpty() },
                contentDescription = json.optString("content_description").takeIf { it.isNotEmpty() },
                bounds = bounds,
                centerX = if (json.has("center_x")) json.getInt("center_x") else null,
                centerY = if (json.has("center_y")) json.getInt("center_y") else null,
                scrollDeltaX = if (json.has("scroll_delta_x")) json.getInt("scroll_delta_x") else null,
                scrollDeltaY = if (json.has("scroll_delta_y")) json.getInt("scroll_delta_y") else null,
                gestureType = json.optString("gesture_type").takeIf { it.isNotEmpty() },
                timestamp = json.getLong("timestamp"),
                relativeTimeMs = json.getLong("relative_time_ms")
            )
        }
    }
}

/**
 * 录制会话数据模型
 */
data class RecordingSession(
    val videoPath: String,
    val recordingStartTime: Long,
    val recordingEndTime: Long? = null,
    val actions: MutableList<ActionRecord> = mutableListOf()
) {
    /**
     * 转换为JSON对象
     */
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("video_path", videoPath)
        json.put("recording_start_time", recordingStartTime)
        recordingEndTime?.let { json.put("recording_end_time", it) }
        val actionsArray = org.json.JSONArray()
        actions.forEach { action ->
            actionsArray.put(action.toJson())
        }
        json.put("actions", actionsArray)
        return json
    }
    
    companion object {
        /**
         * 从JSON对象创建RecordingSession
         */
        fun fromJson(json: JSONObject): RecordingSession {
            val session = RecordingSession(
                videoPath = json.getString("video_path"),
                recordingStartTime = json.getLong("recording_start_time"),
                recordingEndTime = if (json.has("recording_end_time")) json.getLong("recording_end_time") else null
            )
            val actionsArray = json.getJSONArray("actions")
            for (i in 0 until actionsArray.length()) {
                session.actions.add(ActionRecord.fromJson(actionsArray.getJSONObject(i)))
            }
            return session
        }
    }
}

