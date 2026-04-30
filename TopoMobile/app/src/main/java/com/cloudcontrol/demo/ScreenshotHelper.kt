package com.cloudcontrol.demo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.media.MediaScannerConnection
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天等功能上云前截图的宽高缩放系数，持久化在 [ScreenshotCompressionPrefs.PREFS_NAME]。
 * 默认 [ScreenshotCompressionPrefs.DEFAULT_RATIO]（与历史行为一致）。
 */
object ScreenshotCompressionPrefs {
    const val PREFS_NAME = "app_prefs"
    private const val KEY = "screenshot_compression_ratio"
    const val DEFAULT_RATIO = 0.4f
    private const val MIN_RATIO = 0.05f
    private const val MAX_RATIO = 1.0f

    fun getRatio(context: Context): Float {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY, DEFAULT_RATIO)
        return raw.coerceIn(MIN_RATIO, MAX_RATIO)
    }

    fun putRatio(context: Context, ratio: Float) {
        val clamped = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY, clamped).apply()
    }
}

/**
 * 截图工具类
 * 提供截图相关的工具方法
 */
object ScreenshotHelper {
    private const val TAG = "ScreenshotHelper"
    private const val JPEG_QUALITY = 75
    
    /**
     * 压缩Bitmap到指定比例
     */
    private fun compressBitmapSize(bitmap: Bitmap, ratio: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val newWidth = (width * ratio).toInt().coerceAtLeast(1)
        val newHeight = (height * ratio).toInt().coerceAtLeast(1)
        
        if (newWidth == width && newHeight == height) {
            return bitmap
        }
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * 将Bitmap转换为Base64字符串（按 [ScreenshotCompressionPrefs] 中的宽高缩放系数压缩）
     * 返回格式：data:image/jpeg;base64,<base64字符串>
     */
    fun bitmapToBase64(bitmap: Bitmap, context: Context): String {
        return bitmapToBase64(bitmap, ScreenshotCompressionPrefs.getRatio(context))
    }

    /**
     * 将Bitmap转换为Base64字符串（按指定宽高缩放系数）
     */
    fun bitmapToBase64(bitmap: Bitmap, compressionRatio: Float): String {
        val ratio = compressionRatio.coerceIn(0.05f, 1f)
        val compressedBitmap = compressBitmapSize(bitmap, ratio)
        val shouldRecycle = compressedBitmap != bitmap
        
        val outputStream = ByteArrayOutputStream()
        compressedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val byteArray = outputStream.toByteArray()
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        
        Log.d(TAG, "Base64编码完成，长度: ${base64String.length}")
        
        if (shouldRecycle) {
            compressedBitmap.recycle()
        }
        
        return "data:image/jpeg;base64,$base64String"
    }
    
    /**
     * 保存Bitmap到相册
     * 支持Android 10+（使用MediaStore）和Android 9及以下（使用传统方式）
     * 
     * @param context 上下文
     * @param bitmap 要保存的Bitmap
     * @param displayName 显示名称（可选，默认带时间戳）
     * @param saveToScreenshotsFolder 是否保存到Screenshots文件夹（默认true）
     * @return 保存后的Uri，失败返回null
     */
    fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String? = null,
        saveToScreenshotsFolder: Boolean = true
    ): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore API
                saveBitmapToGalleryQ(context, bitmap, displayName, saveToScreenshotsFolder)
            } else {
                // Android 9及以下使用传统方式
                saveBitmapToGalleryLegacy(context, bitmap, displayName, saveToScreenshotsFolder)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存截图到相册失败: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Android 10+ 保存到相册（使用MediaStore API）
     */
    private fun saveBitmapToGalleryQ(
        context: Context,
        bitmap: Bitmap,
        displayName: String?,
        saveToScreenshotsFolder: Boolean
    ): Uri? {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = displayName ?: "Screenshot_${dateFormat.format(Date(timestamp))}.jpg"
        
        // 确定保存路径
        val relativePath = if (saveToScreenshotsFolder) {
            "${Environment.DIRECTORY_PICTURES}/Screenshots"
        } else {
            Environment.DIRECTORY_PICTURES
        }
        
        // 创建ContentValues
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1) // 标记为待处理
        }
        
        // 插入到MediaStore
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: run {
            Log.e(TAG, "插入MediaStore失败，返回null")
            return null
        }
        
        Log.d(TAG, "MediaStore插入成功，Uri: $uri")
        
        // 写入图片数据
        return try {
            val outputStream = context.contentResolver.openOutputStream(uri)
            if (outputStream == null) {
                Log.e(TAG, "无法打开OutputStream")
                // 删除失败的记录
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (deleteException: Exception) {
                    Log.e(TAG, "删除失败的MediaStore记录失败: ${deleteException.message}")
                }
                null
            } else {
                outputStream.use { stream ->
                    val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    if (!success) {
                        Log.e(TAG, "Bitmap压缩失败")
                        // 删除失败的记录
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (deleteException: Exception) {
                            Log.e(TAG, "删除失败的MediaStore记录失败: ${deleteException.message}")
                        }
                        null
                    } else {
                        stream.flush()
                        
                        // 标记为已完成
                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        context.contentResolver.update(uri, contentValues, null, null)
                        
                        Log.d(TAG, "截图已保存到相册: $uri, 文件名: $fileName")
                        
                        // 通知媒体库刷新、
                        try {
                            val intent = android.content.Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                            intent.data = uri
                            context.sendBroadcast(intent)
                        } catch (e: Exception) {
                            Log.w(TAG, "发送媒体扫描广播失败: ${e.message}")
                        }
                        
                        uri
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入图片数据失败: ${e.message}", e)
            // 删除失败的记录
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (deleteException: Exception) {
                Log.e(TAG, "删除失败的MediaStore记录失败: ${deleteException.message}")
            }
            null
        }
    }
    
    /**
     * Android 9及以下保存到相册（使用传统方式）
     */
    private fun saveBitmapToGalleryLegacy(
        context: Context,
        bitmap: Bitmap,
        displayName: String?,
        saveToScreenshotsFolder: Boolean
    ): Uri? {
        // 检查存储权限（Android 9及以下需要）
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Android 9及以下需要WRITE_EXTERNAL_STORAGE权限才能保存到相册")
            return null
        }
        
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = displayName ?: "Screenshot_${dateFormat.format(Date(timestamp))}.jpg"
        
        // 确定保存目录
        val picturesDir = if (saveToScreenshotsFolder) {
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES + "/Screenshots"
            )
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        
        // 确保目录存在
        if (!picturesDir.exists()) {
            picturesDir.mkdirs()
        }
        
        val imageFile = java.io.File(picturesDir, fileName)
        
        // 保存Bitmap
        return try {
            imageFile.outputStream().use { outputStream ->
                val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                if (!success) {
                    Log.e(TAG, "Bitmap压缩失败")
                    null
                } else {
                    outputStream.flush()
                    
                    Log.d(TAG, "截图已保存到: ${imageFile.absolutePath}")
                    
                    // 通知媒体库扫描文件
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(imageFile.absolutePath),
                        arrayOf("image/jpeg")
                    ) { path, uri ->
                        Log.d(TAG, "媒体库扫描完成: $path -> $uri")
                    }
                    
                    // 返回Uri
                    Uri.fromFile(imageFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存文件失败: ${e.message}", e)
            null
        }
    }
}

