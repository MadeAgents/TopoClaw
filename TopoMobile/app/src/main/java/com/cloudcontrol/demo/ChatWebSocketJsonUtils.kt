package com.cloudcontrol.demo

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WebSocket / JSON 消息中的时间戳解析（与原 ChatFragment.parseTimestamp 一致）
 */
object ChatWebSocketJsonUtils {

    fun parseMessageTimestamp(json: JSONObject, key: String): Long {
        return try {
            if (json.has(key)) {
                val value = json.get(key)
                when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Double -> value.toLong()
                    is String -> {
                        value.toLongOrNull()?.let { return@parseMessageTimestamp it }
                        if (value.contains('T')) {
                            try {
                                val cleanValue = value.replace("Z", "").trim()
                                val hasTimezoneOffset = value.contains('+') ||
                                    (value.indexOf('T') > 0 && value.substring(value.indexOf('T') + 1).matches(Regex(".*-\\d{2}:\\d{2}.*")))
                                val parsedTime = if (hasTimezoneOffset) {
                                    val normalizedValue = if (value.contains('Z')) {
                                        value.replace("Z", "+00:00")
                                    } else {
                                        value
                                    }
                                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(normalizedValue)?.time
                                } else {
                                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
                                        timeZone = java.util.TimeZone.getDefault()
                                    }.parse(cleanValue)?.time
                                }
                                parsedTime ?: System.currentTimeMillis()
                            } catch (e: Exception) {
                                Log.w(ChatConstants.TAG, "解析ISO timestamp失败: $value, 使用当前时间", e)
                                System.currentTimeMillis()
                            }
                        } else {
                            value.toLongOrNull() ?: System.currentTimeMillis()
                        }
                    }
                    else -> System.currentTimeMillis()
                }
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(ChatConstants.TAG, "解析timestamp失败，使用当前时间", e)
            System.currentTimeMillis()
        }
    }

    /**
     * 端云 cross_device_message：若含图片负载则返回 Base64（否则 null）。文件类消息不当作图片。
     */
    fun extractCrossDeviceImageBase64(json: JSONObject): String? {
        if (json.optString("message_type") == "file") return null
        val b64 = json.optString("imageBase64", null)?.takeIf { it.isNotEmpty() }
            ?: json.optString("fileBase64", null)?.takeIf { it.isNotEmpty() }
            ?: json.optString("file_base64", null)?.takeIf { it.isNotEmpty() }
            ?: return null
        val mt = json.optString("message_type", json.optString("messageType", ""))
        val explicitImage = mt == "image"
        val hasImageField = json.has("imageBase64") || json.has("fileBase64") || json.has("file_base64")
        return if (explicitImage || hasImageField || mt.isEmpty()) b64 else null
    }
}
