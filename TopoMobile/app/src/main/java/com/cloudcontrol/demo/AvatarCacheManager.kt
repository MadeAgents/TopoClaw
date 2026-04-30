package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 头像缓存管理器
 * 实现时间窗口缓存机制（5分钟内不重新加载）
 * 使用BitmapFactory加载图片，通过内存缓存提升性能
 */
object AvatarCacheManager {
    private const val TAG = "AvatarCacheManager"
    
    // 缓存时间窗口：5分钟（毫秒）
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L
    
    // 缓存项：包含Bitmap和时间戳
    private data class CacheItem(
        val bitmap: Bitmap,
        val timestamp: Long
    )
    
    // 内存缓存：使用ConcurrentHashMap保证线程安全
    private val memoryCache = ConcurrentHashMap<String, CacheItem>()
    
    // 清理任务：定期清理过期缓存
    private var cleanupJob: Job? = null
    
    /**
     * 检查缓存是否有效（5分钟内）
     */
    private fun isCacheValid(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        return (currentTime - timestamp) < CACHE_DURATION_MS
    }
    
    /**
     * 获取缓存的头像
     * @param key 缓存key（通常是conversationId或avatarBase64的hash）
     * @return 缓存的Bitmap，如果不存在或已过期则返回null
     */
    fun getCachedAvatar(key: String): Bitmap? {
        val item = memoryCache[key]
        return if (item != null && isCacheValid(item.timestamp) && !item.bitmap.isRecycled) {
            Log.d(TAG, "使用缓存的头像: $key")
            item.bitmap
        } else {
            // 缓存过期或无效，移除
            if (item != null) {
                memoryCache.remove(key)
                if (!item.bitmap.isRecycled) {
                    item.bitmap.recycle()
                }
            }
            null
        }
    }
    
    /**
     * 保存头像到缓存
     * @param key 缓存key
     * @param bitmap 要缓存的Bitmap（会创建副本）
     */
    fun putCachedAvatar(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.w(TAG, "尝试缓存已回收的Bitmap: $key")
            return
        }
        
        // 创建副本避免被回收
        val cachedCopy = bitmap.copy(bitmap.config, false)
        memoryCache[key] = CacheItem(cachedCopy, System.currentTimeMillis())
        Log.d(TAG, "保存头像到缓存: $key")
        
        // 启动清理任务（如果未启动）
        startCleanupTask()
    }
    
    /**
     * 启动定期清理任务
     */
    private fun startCleanupTask() {
        if (cleanupJob?.isActive == true) {
            return
        }
        
        cleanupJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(60000) // 每分钟检查一次
                cleanupExpiredCache()
            }
        }
    }
    
    /**
     * 清理过期的缓存
     */
    private fun cleanupExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()
        
        memoryCache.forEach { (key, item) ->
            if (!isCacheValid(item.timestamp) || item.bitmap.isRecycled) {
                keysToRemove.add(key)
            }
        }
        
        keysToRemove.forEach { key ->
            val item = memoryCache.remove(key)
            item?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        
        if (keysToRemove.isNotEmpty()) {
            Log.d(TAG, "清理了 ${keysToRemove.size} 个过期缓存")
        }
    }
    
    /**
     * 加载Base64头像（带缓存机制）
     * 使用BitmapFactory解码Base64字符串，并通过AvatarCacheManager缓存
     * 优先使用缓存，如果缓存未命中则异步加载，避免阻塞主线程
     * @param context 上下文
     * @param imageView 目标ImageView
     * @param base64String Base64字符串
     * @param defaultResId 默认资源ID（可选）
     * @param cacheKey 缓存key（可选，如果不提供则使用base64的hash）
     * @param validationTag 验证tag（可选），用于验证ImageView是否仍然有效（如RecyclerView的ViewHolder标识）
     */
    fun loadBase64Avatar(
        context: Context,
        imageView: android.widget.ImageView,
        base64String: String?,
        defaultResId: Int? = null,
        cacheKey: String? = null,
        validationTag: String? = null
    ) {
        if (base64String.isNullOrEmpty()) {
            defaultResId?.let { imageView.setImageResource(it) }
            return
        }
        
        // 生成缓存key
        val key = cacheKey ?: base64String.hashCode().toString()
        
        // 先检查内存缓存（5分钟内有效）
        val cachedBitmap = getCachedAvatar(key)
        if (cachedBitmap != null) {
            // 缓存命中，直接设置，无闪烁
            imageView.setImageBitmap(cachedBitmap)
            return
        }
        
        // 缓存未命中，先设置默认头像，然后异步加载
        defaultResId?.let { imageView.setImageResource(it) }
        
        // 设置验证tag（如果提供），用于后续验证ImageView是否仍然有效
        if (validationTag != null) {
            imageView.tag = validationTag
        }
        
        // 异步加载Base64头像，避免阻塞主线程
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = Base64.decode(base64String, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                
                if (bitmap != null) {
                    // 保存到缓存
                    putCachedAvatar(key, bitmap)
                    
                    // 切换到主线程更新UI
                    withContext(Dispatchers.Main) {
                        // 检查ImageView是否仍然有效
                        try {
                            // 如果提供了validationTag，检查tag是否匹配
                            val isValid = if (validationTag != null) {
                                imageView.tag == validationTag
                            } else {
                                // 如果没有提供tag，总是尝试更新（由调用方负责处理回收情况）
                                true
                            }
                            
                            if (isValid) {
                                imageView.setImageBitmap(bitmap)
                            } else {
                                Log.d(TAG, "ImageView tag不匹配，跳过更新（可能已被回收）")
                            }
                        } catch (e: Exception) {
                            // ImageView可能已被回收，忽略错误
                            Log.w(TAG, "更新头像失败，ImageView可能已被回收: ${e.message}")
                        }
                    }
                } else {
                    // 解码失败，保持默认头像
                    Log.w(TAG, "Base64解码失败，保持默认头像")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载Base64头像失败: ${e.message}", e)
                // 加载失败，保持默认头像
            }
        }
    }
    
    /**
     * 加载资源头像（带缓存机制）
     * 对于资源文件，直接使用BitmapFactory加载，比Glide更简单高效
     * @param context 上下文
     * @param imageView 目标ImageView
     * @param resId 资源ID
     * @param cacheKey 缓存key（可选）
     */
    fun loadResourceAvatar(
        context: Context,
        imageView: android.widget.ImageView,
        resId: Int,
        cacheKey: String? = null
    ) {
        val key = cacheKey ?: "res_$resId"
        
        // 先检查内存缓存（5分钟内有效）
        val cachedBitmap = getCachedAvatar(key)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            return
        }
        
        // 缓存未命中，加载资源并缓存
        try {
            val bitmap = BitmapFactory.decodeResource(context.resources, resId)
            if (bitmap != null) {
                // 保存到缓存
                putCachedAvatar(key, bitmap)
                imageView.setImageBitmap(bitmap)
            } else {
                Log.e(TAG, "加载资源头像失败: $resId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载资源头像失败: $resId, ${e.message}", e)
        }
    }
    
    /**
     * 清除指定key的缓存
     */
    fun clearCache(key: String) {
        val item = memoryCache.remove(key)
        item?.bitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
    
    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        memoryCache.values.forEach { item ->
            if (!item.bitmap.isRecycled) {
                item.bitmap.recycle()
            }
        }
        memoryCache.clear()
        Log.d(TAG, "已清除所有头像缓存")
    }
    
    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): String {
        val validCount = memoryCache.values.count { isCacheValid(it.timestamp) && !it.bitmap.isRecycled }
        return "总缓存数: ${memoryCache.size}, 有效缓存数: $validCount"
    }

    /**
     * 创建首字头像 Bitmap（与助手页面风格一致：圆形背景 + 首字居中）
     * 用于自定义小助手无头像时的默认展示
     * @param context 上下文
     * @param name 名称，取首字；若为空则用"助"
     * @param sizePx 输出尺寸（像素）
     * @return 首字头像 Bitmap
     */
    fun createFirstCharAvatarBitmap(context: Context, name: String, sizePx: Int): Bitmap {
        val letter = name.trim().take(1).ifEmpty { "助" }
        val cacheKey = "first_char_${letter}_$sizePx"
        val cached = getCachedAvatar(cacheKey)
        if (cached != null) return cached

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 背景：浅灰圆形（与 item_assistant_plaza_card 的 circle_background 风格一致）
        val bgPaint = Paint().apply {
            color = 0xFFE8E8E8.toInt()
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bgPaint)

        // 文字：居中，与助手页面一致（18sp、bold、#666666）
        val textPaint = Paint().apply {
            color = 0xFF666666.toInt()
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = sizePx * 0.375f  // 约 18sp 比例
        }
        val bounds = Rect()
        textPaint.getTextBounds(letter, 0, letter.length, bounds)
        val x = sizePx / 2f
        val y = sizePx / 2f - (bounds.top + bounds.bottom) / 2f
        canvas.drawText(letter, x, y, textPaint)

        putCachedAvatar(cacheKey, bitmap)
        return bitmap
    }

    /**
     * 加载自定义小助手头像：
     * - 有头像用 Base64
     * - 默认内置 TopoClaw / GroupManager 无头像时固定使用专用头像
     * - 其他无头像用首字
     */
    fun loadCustomAssistantAvatar(
        context: Context,
        imageView: android.widget.ImageView,
        assistant: CustomAssistantManager.CustomAssistant,
        cacheKey: String? = null,
        validationTag: String? = null,
        sizePx: Int = (48 * context.resources.displayMetrics.density).toInt()
    ) {
        val key = cacheKey ?: "custom_assistant_${assistant.id}"
        if (!assistant.avatar.isNullOrBlank()) {
            loadBase64Avatar(
                context = context,
                imageView = imageView,
                base64String = assistant.avatar,
                defaultResId = null,
                cacheKey = key,
                validationTag = validationTag ?: assistant.id
            )
        } else if (assistant.id == CustomAssistantManager.DEFAULT_TOPOCLAW_ASSISTANT_ID) {
            loadResourceAvatar(
                context = context,
                imageView = imageView,
                resId = R.drawable.ic_assistant_avatar,
                cacheKey = "${key}_topoclaw_default"
            )
        } else if (assistant.id == CustomAssistantManager.DEFAULT_GROUP_MANAGER_ASSISTANT_ID) {
            loadResourceAvatar(
                context = context,
                imageView = imageView,
                resId = R.drawable.ic_groupmanager_avatar,
                cacheKey = "${key}_groupmanager_default"
            )
        } else {
            val firstCharKey = "first_char_${assistant.name.trim().take(1).ifEmpty { "助" }}_$sizePx"
            val cached = getCachedAvatar(firstCharKey)
            if (cached != null) {
                imageView.setImageBitmap(cached)
                return
            }
            val tag = validationTag ?: assistant.id
            imageView.tag = tag
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = createFirstCharAvatarBitmap(context, assistant.name, sizePx)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == tag) {
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
}

