package com.cloudcontrol.demo

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 将聊天中查看的图片保存到系统相册（Pictures/TopoClaw聊天）。
 */
object ChatImageGallerySaver {

    private const val TAG = "ChatImageGallerySaver"
    private const val ALBUM_RELATIVE = "TopoClaw聊天"

    /**
     * 从任意可读 [Uri]（含 FileProvider）复制到相册，避免整图解码导致 OOM。
     */
    suspend fun saveImageFromUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") }
                ?: guessMimeFromUri(uri)
            val ext = extensionForMime(mime)
            val fileName = "chat_${System.currentTimeMillis()}.$ext"
            val saved = resolver.openInputStream(uri)?.use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveStreamToMediaStoreQ(context, input, fileName, mime) != null
                } else {
                    saveStreamToLegacyPublicPictures(context, input, fileName, mime) != null
                }
            } ?: return@withContext false
            saved
        } catch (e: Exception) {
            Log.e(TAG, "saveImageFromUri: ${e.message}", e)
            false
        }
    }

    /**
     * 将本地文件（通常是 base64 解码后的缓存文件）复制到系统相册。
     */
    suspend fun saveImageFromFilePath(
        context: Context,
        filePath: String,
        displayName: String? = null,
        albumRelative: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile || file.length() <= 0L) {
                Log.e(TAG, "saveImageFromFilePath: 文件不存在或为空: $filePath")
                return@withContext null
            }
            val mime = guessMimeFromPath(file.path)
            val ext = extensionForMime(mime)
            val finalFileName = normalizeDisplayName(displayName, ext)
            val targetAlbum = normalizeAlbumRelative(albumRelative)

            FileInputStream(file).use { input ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveStreamToMediaStoreQ(context, input, finalFileName, mime, targetAlbum)
                } else {
                    saveStreamToLegacyPublicPictures(context, input, finalFileName, mime, targetAlbum)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveImageFromFilePath: ${e.message}", e)
            null
        }
    }

    private fun guessMimeFromUri(uri: Uri): String {
        val path = uri.path?.lowercase() ?: return "image/jpeg"
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun extensionForMime(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        else -> "jpg"
    }

    private fun guessMimeFromPath(path: String): String {
        val lowered = path.lowercase()
        return when {
            lowered.endsWith(".png") -> "image/png"
            lowered.endsWith(".webp") -> "image/webp"
            lowered.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    private fun normalizeDisplayName(displayName: String?, ext: String): String {
        val raw = displayName?.trim().orEmpty()
        if (raw.isEmpty()) {
            return "chat_${System.currentTimeMillis()}.$ext"
        }
        val lowered = raw.lowercase()
        return if (lowered.endsWith(".$ext")) raw else "$raw.$ext"
    }

    private fun normalizeAlbumRelative(albumRelative: String?): String {
        val candidate = albumRelative?.trim()?.replace("\\", "/").orEmpty()
        if (candidate.isEmpty()) return ALBUM_RELATIVE
        return candidate.trim('/').ifEmpty { ALBUM_RELATIVE }
    }

    private fun saveStreamToMediaStoreQ(
        context: Context,
        input: InputStream,
        fileName: String,
        mime: String,
        albumRelative: String = ALBUM_RELATIVE
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + albumRelative)
        }
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(outUri)?.use { output ->
            input.copyTo(output)
        } ?: return null
        return outUri
    }

    private fun saveStreamToLegacyPublicPictures(
        context: Context,
        input: InputStream,
        fileName: String,
        mime: String,
        albumRelative: String = ALBUM_RELATIVE
    ): Uri? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(picturesDir, albumRelative)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "无法创建目录: ${dir.absolutePath}")
            return null
        }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> input.copyTo(out) }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mime),
            null
        )
        return Uri.fromFile(file)
    }
}
