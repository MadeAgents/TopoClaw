package com.cloudcontrol.demo

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 聊天图片路径安全校验：仅允许访问应用约定目录，防止路径穿越/越权读取。
 */
object ChatImagePathGuard {
    private const val TAG = "ChatImagePathGuard"

    fun resolveSafeLocalImageFile(context: Context, rawPath: String?): File? {
        val trimmed = rawPath?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val file = try {
            File(trimmed).canonicalFile
        } catch (e: Exception) {
            Log.w(TAG, "路径规范化失败: ${e.message}")
            return null
        }

        val allowedRoots = getAllowedRoots(context)
        val isAllowed = allowedRoots.any { root -> isUnderRoot(file, root) }
        if (!isAllowed) {
            Log.w(TAG, "图片路径不在白名单目录: $file")
            return null
        }
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            Log.w(TAG, "图片文件无效或不存在: $file")
            return null
        }
        return file
    }

    fun toSafeViewUri(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "构建 FileProvider Uri 失败: ${e.message}", e)
            null
        }
    }

    fun resolveSafeViewUri(context: Context, uri: Uri): Uri? {
        return when (uri.scheme) {
            "content" -> uri
            "file", null -> {
                val safeFile = resolveSafeLocalImageFile(context, uri.path) ?: return null
                toSafeViewUri(context, safeFile)
            }
            else -> null
        }
    }

    private fun getAllowedRoots(context: Context): List<File> {
        val base = context.getExternalFilesDir(null)?.canonicalFile ?: return emptyList()
        val roots = mutableListOf<File>()
        listOf("images", "task_records").forEach { sub ->
            try {
                roots.add(File(base, sub).canonicalFile)
            } catch (_: Exception) {
                // ignore
            }
        }
        return roots
    }

    private fun isUnderRoot(file: File, root: File): Boolean {
        val rootPath = root.path
        val filePath = file.path
        return filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")
    }
}
