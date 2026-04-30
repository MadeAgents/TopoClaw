package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 将 Base64 图片数据落盘（与 ChatFragment.saveImageFromBase64 行为一致），供端云「我的电脑」等场景复用。
 */
object ChatImageUtils {

    private const val TAG = "ChatImageUtils"

    suspend fun saveImageFromBase64(context: Context, base64: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val imagesDir = File(context.getExternalFilesDir(null), "images")
                if (!imagesDir.exists()) imagesDir.mkdirs()

                val timestamp = System.currentTimeMillis()

                var cleanBase64 = base64.trim()
                if (cleanBase64.startsWith("data:image/")) {
                    val base64Index = cleanBase64.indexOf(";base64,")
                    if (base64Index != -1) {
                        cleanBase64 = cleanBase64.substring(base64Index + 8)
                    } else {
                        Log.w(TAG, "Base64 含 data:image 前缀但缺少 ;base64,")
                    }
                }
                cleanBase64 = cleanBase64.replace("\n", "").replace("\r", "").replace(" ", "")

                val remainder = cleanBase64.length % 4
                if (remainder != 0) {
                    cleanBase64 += "=".repeat(4 - remainder)
                }

                val imageBytes = try {
                    Base64.decode(cleanBase64, Base64.DEFAULT)
                } catch (e: Exception) {
                    try {
                        Base64.decode(cleanBase64, Base64.NO_WRAP)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Base64 解码失败: ${e2.message}", e2)
                        return@withContext null
                    }
                }

                if (imageBytes.isEmpty()) {
                    Log.e(TAG, "Base64 解码后数据为空")
                    return@withContext null
                }

                val ext = when {
                    imageBytes.size >= 3 && imageBytes[0] == 0xFF.toByte() &&
                        imageBytes[1] == 0xD8.toByte() && imageBytes[2] == 0xFF.toByte() -> "jpg"
                    imageBytes.size >= 4 && imageBytes[0] == 0x89.toByte() &&
                        imageBytes[1] == 0x50.toByte() && imageBytes[2] == 0x4E.toByte() &&
                        imageBytes[3] == 0x47.toByte() -> "png"
                    imageBytes.size >= 12 && imageBytes[0] == 0x52.toByte() &&
                        imageBytes[1] == 0x49.toByte() && imageBytes[2] == 0x46.toByte() &&
                        imageBytes[3] == 0x46.toByte() &&
                        imageBytes[8] == 0x57.toByte() && imageBytes[9] == 0x45.toByte() &&
                        imageBytes[10] == 0x42.toByte() && imageBytes[11] == 0x50.toByte() -> "webp"
                    imageBytes.size >= 6 && imageBytes[0] == 0x47.toByte() &&
                        imageBytes[1] == 0x49.toByte() && imageBytes[2] == 0x46.toByte() &&
                        imageBytes[3] == 0x38.toByte() &&
                        (imageBytes[4] == 0x37.toByte() || imageBytes[4] == 0x39.toByte()) &&
                        imageBytes[5] == 0x61.toByte() -> "gif"
                    else -> null
                }

                if (ext != null) {
                    val imageFile = File(imagesDir, "received_image_$timestamp.$ext")
                    imageFile.writeBytes(imageBytes)
                    if (!imageFile.exists() || imageFile.length() == 0L) {
                        Log.e(TAG, "图片文件写入失败或为空")
                        return@withContext null
                    }
                    Log.d(TAG, "图片已保存: ${imageFile.absolutePath}, size=${imageFile.length()}")
                    return@withContext imageFile.absolutePath
                }

                val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bmp == null) {
                    Log.e(TAG, "无法识别常见图片头且 BitmapFactory 解码失败（长度=${imageBytes.size}）")
                    return@withContext null
                }
                try {
                    val imageFile = File(imagesDir, "received_image_$timestamp.png")
                    imageFile.outputStream().use { out ->
                        if (!bmp.compress(Bitmap.CompressFormat.PNG, 92, out)) {
                            Log.e(TAG, "PNG 压缩失败")
                            return@withContext null
                        }
                    }
                    if (!imageFile.exists() || imageFile.length() == 0L) {
                        Log.e(TAG, "图片文件写入失败或为空")
                        return@withContext null
                    }
                    Log.d(TAG, "图片已保存(解码转PNG): ${imageFile.absolutePath}, size=${imageFile.length()}")
                    imageFile.absolutePath
                } finally {
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveImageFromBase64 失败: ${e.message}", e)
                null
            }
        }
}

/**
 * 「我的电脑」会话：仅在 prefs 中追加消息并写入 [conversations] 的最后一条预览。
 * 不得在此处刷新 RecyclerView：本方法可能从 WebSocket 后台线程调用；
 * 会话列表 UI 须由调用方在主线程执行（例如 [MainActivity.notifyInboundSessionMessage]）。
 */
object CrossDeviceMeMessageStore {

    fun appendMessage(context: Context, sender: String, caption: String, timestamp: Long, imagePath: String?) {
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val key = "chat_messages_${ConversationListFragment.CONVERSATION_ID_ME}"
            val existing = prefs.getString(key, null)
            val arr = if (existing != null) JSONArray(existing) else JSONArray()

            val messageText = if (imagePath != null) {
                val name = File(imagePath).name
                if (caption.isNotEmpty()) "$caption\n[图片: $name]" else "[图片: $name]"
            } else caption

            val msgObj = JSONObject().apply {
                put("sender", sender)
                put("message", messageText)
                if (imagePath != null) {
                    put("type", "image")
                    put("imagePath", imagePath)
                } else {
                    put("type", "text")
                }
                put("timestamp", timestamp)
                put("uuid", UUID.randomUUID().toString())
            }
            arr.put(msgObj)
            prefs.edit().putString(key, arr.toString()).apply()

            val lastPreview = if (imagePath != null) {
                if (caption.isNotEmpty()) "$caption [图片]" else "[图片]"
            } else caption

            val convPrefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
            convPrefs.edit()
                .putString("${ConversationListFragment.CONVERSATION_ID_ME}_last_message", lastPreview)
                .putLong("${ConversationListFragment.CONVERSATION_ID_ME}_last_time", timestamp)
                .apply()
        } catch (e: Exception) {
            Log.e("CrossDeviceMeMessageStore", "appendMessage: ${e.message}", e)
        }
    }
}
