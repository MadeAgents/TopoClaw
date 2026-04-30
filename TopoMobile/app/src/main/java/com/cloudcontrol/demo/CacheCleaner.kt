package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 缓存清理工具类
 * 只清理图片、视频和任务记录，不清理配置、技能、聊天记录等
 * 智能清理：保留聊天记录中正在使用的图片
 */
object CacheCleaner {
    private const val TAG = "CacheCleaner"
    
    /**
     * 清理缓存数据
     * 清理内容：
     * 1. images/ 目录下的所有图片
     * 2. recordings/ 目录下的所有视频
     * 3. video_thumbnails/ 目录下的所有缩略图
     * 4. task_records/ 目录下的所有任务记录（包含截图）
     * 
     * 不清理：
     * - SharedPreferences（配置、聊天记录等）
     * - 技能数据
     * - 其他配置数据
     * 
     * @param context 上下文
     * @return 清理结果，包含清理的文件数量和总大小
     */
    fun cleanCache(context: Context): CleanResult {
        val result = CleanResult()
        
        try {
            val externalFilesDir = context.getExternalFilesDir(null)
            val cacheDir = context.cacheDir
            
            if (externalFilesDir == null) {
                Log.w(TAG, "getExternalFilesDir返回null，无法清理缓存")
                return result
            }
            
            // 1. 清理图片目录（保留聊天记录中使用的图片）
            val imagesDir = File(externalFilesDir, "images")
            if (imagesDir.exists() && imagesDir.isDirectory) {
                // 获取聊天记录中使用的图片文件名集合
                val usedImageFiles = getUsedImageFiles(context)
                Log.d(TAG, "聊天记录中使用的图片文件数: ${usedImageFiles.size}")
                
                val imagesResult = deleteDirectorySelectively(imagesDir, usedImageFiles)
                result.filesDeleted += imagesResult.filesDeleted
                result.bytesDeleted += imagesResult.bytesDeleted
                Log.d(TAG, "清理图片目录: ${imagesResult.filesDeleted}个文件, ${formatSize(imagesResult.bytesDeleted)}")
            }
            
            // 2. 清理录制视频目录
            val recordingsDir = File(externalFilesDir, "recordings")
            if (recordingsDir.exists() && recordingsDir.isDirectory) {
                val recordingsResult = deleteDirectory(recordingsDir)
                result.filesDeleted += recordingsResult.filesDeleted
                result.bytesDeleted += recordingsResult.bytesDeleted
                Log.d(TAG, "清理录制视频目录: ${recordingsResult.filesDeleted}个文件, ${formatSize(recordingsResult.bytesDeleted)}")
            }
            
            // 3. 清理任务记录目录
            val taskRecordsDir = File(externalFilesDir, "task_records")
            if (taskRecordsDir.exists() && taskRecordsDir.isDirectory) {
                val taskRecordsResult = deleteDirectory(taskRecordsDir)
                result.filesDeleted += taskRecordsResult.filesDeleted
                result.bytesDeleted += taskRecordsResult.bytesDeleted
                Log.d(TAG, "清理任务记录目录: ${taskRecordsResult.filesDeleted}个文件, ${formatSize(taskRecordsResult.bytesDeleted)}")
            }
            
            // 4. 清理视频缩略图缓存
            val thumbnailsDir = File(cacheDir, "video_thumbnails")
            if (thumbnailsDir.exists() && thumbnailsDir.isDirectory) {
                val thumbnailsResult = deleteDirectory(thumbnailsDir)
                result.filesDeleted += thumbnailsResult.filesDeleted
                result.bytesDeleted += thumbnailsResult.bytesDeleted
                Log.d(TAG, "清理视频缩略图缓存: ${thumbnailsResult.filesDeleted}个文件, ${formatSize(thumbnailsResult.bytesDeleted)}")
            }
            
            Log.i(TAG, "缓存清理完成: 共删除${result.filesDeleted}个文件, 释放${formatSize(result.bytesDeleted)}空间")
            result.success = true
            
        } catch (e: Exception) {
            Log.e(TAG, "清理缓存失败: ${e.message}", e)
            result.errorMessage = e.message
        }
        
        return result
    }
    
    /**
     * 获取缓存大小
     * @param context 上下文
     * @return 缓存大小（字节）
     */
    fun getCacheSize(context: Context): Long {
        var totalSize = 0L
        
        try {
            val externalFilesDir = context.getExternalFilesDir(null)
            val cacheDir = context.cacheDir
            
            if (externalFilesDir == null) {
                return 0L
            }
            
            // 计算图片目录大小
            val imagesDir = File(externalFilesDir, "images")
            if (imagesDir.exists()) {
                totalSize += getDirectorySize(imagesDir)
            }
            
            // 计算录制视频目录大小
            val recordingsDir = File(externalFilesDir, "recordings")
            if (recordingsDir.exists()) {
                totalSize += getDirectorySize(recordingsDir)
            }
            
            // 计算任务记录目录大小
            val taskRecordsDir = File(externalFilesDir, "task_records")
            if (taskRecordsDir.exists()) {
                totalSize += getDirectorySize(taskRecordsDir)
            }
            
            // 计算视频缩略图缓存大小
            val thumbnailsDir = File(cacheDir, "video_thumbnails")
            if (thumbnailsDir.exists()) {
                totalSize += getDirectorySize(thumbnailsDir)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "获取缓存大小失败: ${e.message}", e)
        }
        
        return totalSize
    }
    
    /**
     * 获取聊天记录中使用的图片文件名集合
     * @param context 上下文
     * @return 图片文件名集合（不含路径，只有文件名）
     */
    private fun getUsedImageFiles(context: Context): Set<String> {
        val usedFiles = mutableSetOf<String>()
        
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            
            // 遍历所有对话的聊天记录
            val allKeys = prefs.all.keys
            for (key in allKeys) {
                if (key.startsWith("chat_messages_")) {
                    val messagesJson = prefs.getString(key, null)
                    if (messagesJson != null) {
                        try {
                            val messagesArray = JSONArray(messagesJson)
                            for (i in 0 until messagesArray.length()) {
                                val messageObj = messagesArray.getJSONObject(i)
                                val message = messageObj.getString("message")
                                val type = messageObj.optString("type", "")
                                
                                // 如果是图片消息，提取文件名
                                if (type == "image" || message.contains("[图片: ")) {
                                    // 优先从 imagePath 字段提取文件名（绝对路径）
                                    val imagePath = messageObj.optString("imagePath", null)
                                    if (imagePath != null && imagePath.isNotEmpty()) {
                                        val fileName = extractFileNameFromPath(imagePath)
                                        if (fileName.isNotEmpty()) {
                                            usedFiles.add(fileName)
                                            Log.d(TAG, "从imagePath字段提取文件名: $fileName")
                                        }
                                    }
                                    
                                    // 兼容旧格式：从消息文本中提取文件名
                                    val imageFileName = extractImageFileName(message)
                                    if (imageFileName.isNotEmpty()) {
                                        usedFiles.add(imageFileName)
                                        Log.d(TAG, "从消息文本提取文件名: $imageFileName")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "解析聊天记录失败: $key, ${e.message}")
                        }
                    }
                }
            }
            
            Log.d(TAG, "从聊天记录中提取到 ${usedFiles.size} 个图片文件引用")
        } catch (e: Exception) {
            Log.e(TAG, "获取使用的图片文件失败: ${e.message}", e)
        }
        
        return usedFiles
    }
    
    /**
     * 从消息文本中提取图片文件名
     * 格式: "[图片: image_xxx.jpg]" 或 "query\n[图片: image_xxx.jpg]"
     */
    private fun extractImageFileName(message: String): String {
        val pattern = Regex("\\[图片: ([^\\]]+)\\]")
        val match = pattern.find(message)
        return match?.groupValues?.get(1) ?: ""
    }
    
    /**
     * 从绝对路径中提取文件名
     * 例如: "/storage/emulated/0/Android/data/com.cloudcontrol.demo/files/images/image_1234567890.jpg" -> "image_1234567890.jpg"
     */
    private fun extractFileNameFromPath(imagePath: String): String {
        return try {
            val file = File(imagePath)
            file.name
        } catch (e: Exception) {
            Log.w(TAG, "从路径提取文件名失败: $imagePath, ${e.message}")
            ""
        }
    }
    
    /**
     * 选择性删除目录中的文件（保留指定文件）
     * @param directory 要删除的目录
     * @param filesToKeep 要保留的文件名集合（不含路径）
     * @return 删除结果
     */
    private fun deleteDirectorySelectively(directory: File, filesToKeep: Set<String>): CleanResult {
        val result = CleanResult()
        
        if (!directory.exists() || !directory.isDirectory) {
            return result
        }
        
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        // 递归删除子目录
                        val subResult = deleteDirectorySelectively(file, filesToKeep)
                        result.filesDeleted += subResult.filesDeleted
                        result.bytesDeleted += subResult.bytesDeleted
                    } else {
                        // 检查文件是否在保留列表中
                        val fileName = file.name
                        if (filesToKeep.contains(fileName)) {
                            Log.d(TAG, "保留文件（在聊天记录中使用）: $fileName")
                            continue
                        }
                        
                        // 删除文件
                        val fileSize = file.length()
                        if (file.delete()) {
                            result.filesDeleted++
                            result.bytesDeleted += fileSize
                        }
                    }
                }
            }
            
            // 如果目录为空，尝试删除（但可能还有其他文件，所以失败也没关系）
            val remainingFiles = directory.listFiles()
            if (remainingFiles == null || remainingFiles.isEmpty()) {
                directory.delete()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "选择性删除目录失败: ${directory.absolutePath}, ${e.message}", e)
        }
        
        return result
    }
    
    /**
     * 删除目录及其所有内容
     * @param directory 要删除的目录
     * @return 删除结果
     */
    private fun deleteDirectory(directory: File): CleanResult {
        val result = CleanResult()
        
        if (!directory.exists() || !directory.isDirectory) {
            return result
        }
        
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        // 递归删除子目录
                        val subResult = deleteDirectory(file)
                        result.filesDeleted += subResult.filesDeleted
                        result.bytesDeleted += subResult.bytesDeleted
                    } else {
                        // 删除文件
                        val fileSize = file.length()
                        if (file.delete()) {
                            result.filesDeleted++
                            result.bytesDeleted += fileSize
                        }
                    }
                }
            }
            
            // 删除空目录
            if (directory.delete()) {
                Log.d(TAG, "已删除目录: ${directory.absolutePath}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "删除目录失败: ${directory.absolutePath}, ${e.message}", e)
        }
        
        return result
    }
    
    /**
     * 计算目录大小
     * @param directory 目录
     * @return 目录大小（字节）
     */
    private fun getDirectorySize(directory: File): Long {
        var size = 0L
        
        if (!directory.exists() || !directory.isDirectory) {
            return size
        }
        
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        size += getDirectorySize(file)
                    } else {
                        size += file.length()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "计算目录大小失败: ${directory.absolutePath}, ${e.message}", e)
        }
        
        return size
    }
    
    /**
     * 格式化文件大小
     * @param bytes 字节数
     * @return 格式化后的字符串
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }
        val kb = bytes / 1024.0
        if (kb < 1024) {
            return String.format("%.2f KB", kb)
        }
        val mb = kb / 1024.0
        if (mb < 1024) {
            return String.format("%.2f MB", mb)
        }
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
    
    /**
     * 清理结果数据类
     */
    data class CleanResult(
        var success: Boolean = false,
        var filesDeleted: Int = 0,
        var bytesDeleted: Long = 0,
        var errorMessage: String? = null
    )
}

