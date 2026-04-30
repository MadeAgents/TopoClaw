package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Base64
import android.util.LruCache
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * 群头像拼接工具类
 * 将多个头像拼接成2x2网格
 * 支持缓存机制，避免重复生成
 */
object GroupAvatarHelper {
    private const val TAG = "GroupAvatarHelper"
    
    // 群头像缓存（群组级别，按成员组合 + 尺寸缓存）
    private val groupAvatarCache: LruCache<String, Bitmap> = LruCache(30)

    // 成员头像缓存（IMEI -> Bitmap），避免同一成员在多个群里反复 Base64 解码
    private val memberAvatarCache: LruCache<String, Bitmap> = LruCache(50)
    
    /**
     * 清除群头像缓存
     * 当用户头像变化时调用
     */
    fun clearGroupAvatarCache() {
        groupAvatarCache.evictAll()
        memberAvatarCache.evictAll()
        Log.d(TAG, "已清除群头像缓存")
    }
    
    /**
     * 生成缓存key
     * key = 用户头像hash + size
     */
    private fun generateCacheKey(userAvatarHash: String, size: Int): String {
        return "group_avatar_${userAvatarHash}_${size}"
    }
    
    /**
     * 快速检查好友群头像缓存（不执行耗时操作）
     * @param context 上下文
     * @param size 头像尺寸
     * @return 缓存的Bitmap，如果不存在则返回null
     */
    fun getCachedFriendsGroupAvatar(context: Context, size: Int): Bitmap? {
        val userProfile = ProfileManager.loadProfileLocally(context)
        val userAvatarHash = getStringHash(userProfile?.avatar)
        val cacheKey = generateCacheKey(userAvatarHash, size)
        val cachedAvatar = groupAvatarCache.get(cacheKey)
        return if (cachedAvatar != null && !cachedAvatar.isRecycled) {
            cachedAvatar
        } else {
            null
        }
    }
    
    /**
     * 快速检查群组头像缓存（不执行耗时操作）
     * @param memberImeis 群组成员IMEI列表
     * @param size 头像尺寸
     * @param assistantIds 群内助手列表（可选）
     * @return 缓存的Bitmap，如果不存在则返回null
     */
    fun getCachedGroupAvatarFromMembers(
        memberImeis: List<String>,
        size: Int,
        assistantIds: List<String> = emptyList()
    ): Bitmap? {
        val membersKey = memberImeis.take(4).sorted().joinToString("_")
        val assistantsKey = assistantIds.take(4).sorted().joinToString("_")
        val cacheKey = "group_avatar_members_${getStringHash("${membersKey}|${assistantsKey}")}_$size"
        val cachedAvatar = groupAvatarCache.get(cacheKey)
        return if (cachedAvatar != null && !cachedAvatar.isRecycled) {
            cachedAvatar
        } else {
            null
        }
    }
    
    /**
     * 计算字符串的MD5 hash（用于标识用户头像）
     */
    private fun getStringHash(str: String?): String {
        if (str.isNullOrEmpty()) return "default"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(str.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "计算hash失败: ${e.message}", e)
            "default"
        }
    }
    
    /**
     * 创建群头像
     * @param context 上下文
     * @param avatars 头像列表，最多3个（左上、右上、左下，右下留空）
     * @param size 输出头像尺寸（像素）
     * @return 拼接后的Bitmap
     */
    fun createGroupAvatar(context: Context, avatars: List<Bitmap?>, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // 绘制白色背景
        canvas.drawColor(ContextCompat.getColor(context, android.R.color.white))
        
        // 计算每个小头像的尺寸和位置
        val halfSize = size / 2
        val padding = 2 // 小头像之间的间距（像素）
        
        // 绘制左上角头像（索引0）
        if (avatars.size > 0 && avatars[0] != null) {
            drawAvatar(canvas, avatars[0]!!, 0, 0, halfSize - padding, halfSize - padding)
        }
        
        // 绘制右上角头像（索引1）
        if (avatars.size > 1 && avatars[1] != null) {
            drawAvatar(canvas, avatars[1]!!, halfSize + padding, 0, halfSize - padding, halfSize - padding)
        }
        
        // 绘制左下角头像（索引2）
        if (avatars.size > 2 && avatars[2] != null) {
            drawAvatar(canvas, avatars[2]!!, 0, halfSize + padding, halfSize - padding, halfSize - padding)
        }
        
        // 右下角留空
        
        return result
    }
    
    /**
     * 绘制单个头像（正方形）
     */
    private fun drawAvatar(canvas: Canvas, bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int) {
        // 缩放bitmap到目标尺寸（使用centerCrop方式）
        val scaledBitmap = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            // 计算缩放比例，使用centerCrop
            val scale = Math.max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).toInt()
            val scaledHeight = (bitmap.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            
            // 裁剪中间部分
            val startX = (scaledWidth - width) / 2
            val startY = (scaledHeight - height) / 2
            Bitmap.createBitmap(scaled, startX, startY, width, height)
        }
        
        // 直接绘制正方形头像（不需要圆形裁剪）
        canvas.drawBitmap(scaledBitmap, x.toFloat(), y.toFloat(), null)
        
        // 回收临时bitmap
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
    }
    
    /**
     * 从资源ID加载Bitmap
     */
    fun loadBitmapFromResource(context: Context, resId: Int): Bitmap? {
        return try {
            BitmapFactory.decodeResource(context.resources, resId)
        } catch (e: Exception) {
            Log.e(TAG, "从资源加载Bitmap失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 从Base64字符串加载Bitmap
     */
    fun loadBitmapFromBase64(base64: String?): Bitmap? {
        if (base64.isNullOrEmpty()) return null
        
        return try {
            val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "从Base64加载Bitmap失败: ${e.message}", e)
            null
        }
    }

    /**
     * 带缓存的成员头像加载
     * 同一 IMEI 只做一次 Base64 解码，后续直接命中缓存
     */
    private fun loadMemberAvatar(context: Context, imei: String, fallbackSize: Int): Bitmap? {
        val cached = memberAvatarCache.get(imei)
        if (cached != null && !cached.isRecycled) return cached

        val friend = FriendManager.getFriend(context, imei)
        val bitmap: Bitmap? = if (friend != null && !friend.avatar.isNullOrEmpty()) {
            loadBitmapFromBase64(friend.avatar)
        } else {
            val profile = ProfileManager.loadProfileLocally(context)
            if (profile?.imei == imei && !profile.avatar.isNullOrEmpty()) {
                loadBitmapFromBase64(profile.avatar)
            } else {
                val fallbackName = when {
                    profile?.imei == imei -> profile.name ?: "我"
                    friend?.nickname?.isNotBlank() == true -> friend.nickname
                    else -> imei.take(8)
                }
                createLetterAvatar(fallbackName, fallbackSize)
            }
        }

        if (bitmap != null) {
            memberAvatarCache.put(imei, bitmap)
        }
        return bitmap
    }

    /**
     * 生成首字母头像（用于无默认头像场景）
     */
    fun createLetterAvatar(text: String?, size: Int): Bitmap {
        val safeSize = size.coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D8DEE9")
        }
        canvas.drawRect(0f, 0f, safeSize.toFloat(), safeSize.toFloat(), bgPaint)

        val letter = text?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.substring(0, 1)
            ?.uppercase()
            ?: "#"
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4C566A")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = safeSize * 0.42f
        }
        val fm = textPaint.fontMetrics
        val centerY = safeSize / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(letter, safeSize / 2f, centerY, textPaint)

        return bitmap
    }
    
    /**
     * 创建好友群的群头像
     * 包含：用户头像、TopoClaw头像、技能学习小助手头像、人工客服头像
     * 顺序：左上=用户，右上=TopoClaw，左下=技能学习小助手，右下=人工客服
     * 支持缓存机制，避免重复生成
     */
    fun createFriendsGroupAvatar(context: Context, size: Int): Bitmap {
        // 获取用户头像hash（用于缓存key）
        val userProfile = ProfileManager.loadProfileLocally(context)
        val userAvatarHash = getStringHash(userProfile?.avatar)
        val cacheKey = generateCacheKey(userAvatarHash, size)
        
        // 先检查缓存
        val cachedAvatar = groupAvatarCache.get(cacheKey)
        if (cachedAvatar != null && !cachedAvatar.isRecycled) {
            Log.d(TAG, "使用缓存的群头像: $cacheKey")
            return cachedAvatar
        }
        
        // 缓存未命中，生成新头像
        Log.d(TAG, "生成新的群头像: $cacheKey")
        val avatars = mutableListOf<Bitmap?>()
        
        // 左上：用户头像
        val userAvatar = if (!userProfile?.avatar.isNullOrEmpty()) {
            loadBitmapFromBase64(userProfile?.avatar)
        } else {
            // 使用默认头像
            loadBitmapFromResource(context, R.drawable.ic_person)
        }
        avatars.add(userAvatar)
        
        // 右上：TopoClaw头像
        val assistantAvatar = loadBitmapFromResource(context, R.drawable.ic_assistant_avatar)
        avatars.add(assistantAvatar)
        
        // 左下：技能学习小助手头像
        val skillLearningAvatar = loadBitmapFromResource(context, R.drawable.ic_skill_learning_avatar)
        avatars.add(skillLearningAvatar)
        
        // 右下：人工客服头像
        val customerServiceAvatar = loadBitmapFromResource(context, R.drawable.ic_customer_service_avatar)
        avatars.add(customerServiceAvatar)
        
        // 修改createGroupAvatar以支持4个头像（2x2网格）
        val groupAvatar = createGroupAvatar4Members(context, avatars, size)
        
        // 存入缓存（创建副本，避免被回收）
        val cachedCopy = groupAvatar.copy(groupAvatar.config, false)
        groupAvatarCache.put(cacheKey, cachedCopy)
        
        return groupAvatar
    }
    
    private fun getAssistantDisplayName(context: Context, assistantId: String): String {
        return when (assistantId) {
            ConversationListFragment.CONVERSATION_ID_ASSISTANT -> context.getString(R.string.auto_execute_assistant)
            ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> context.getString(R.string.skill_learn_assistant)
            ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> context.getString(R.string.chat_assistant)
            ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> context.getString(R.string.customer_service)
            else -> CustomAssistantManager.getById(context, assistantId)?.name ?: assistantId
        }
    }

    private fun createAssistantFallbackAvatar(context: Context, assistantId: String, size: Int): Bitmap? {
        val custom = CustomAssistantManager.getById(context, assistantId)
        val customAvatar = loadBitmapFromBase64(custom?.avatar)
        if (customAvatar != null) return customAvatar
        val name = getAssistantDisplayName(context, assistantId)
        return createLetterAvatar(name, size)
    }

    /**
     * 根据群组成员IMEI列表创建群组头像
     * 群组头像包含：群成员头像（优先真实头像，无头像使用首字母）+ 无头像助手首字母兜底
     * @param context 上下文
     * @param memberImeis 群组成员IMEI列表
     * @param size 输出头像尺寸（像素）
     * @param assistantIds 群内助手列表（可选）
     * @return 拼接后的Bitmap
     */
    fun createGroupAvatarFromMembers(
        context: Context,
        memberImeis: List<String>,
        size: Int,
        assistantIds: List<String> = emptyList()
    ): Bitmap {
        // 生成缓存key（基于成员列表 + 助手列表）
        val membersKey = memberImeis.take(4).sorted().joinToString("_")
        val assistantsKey = assistantIds.take(4).sorted().joinToString("_")
        val cacheKey = "group_avatar_members_${getStringHash("${membersKey}|${assistantsKey}")}_$size"
        
        // 先检查缓存
        val cachedAvatar = groupAvatarCache.get(cacheKey)
        if (cachedAvatar != null && !cachedAvatar.isRecycled) {
            Log.d(TAG, "使用缓存的群组头像: $cacheKey")
            return cachedAvatar
        }
        
        val avatars = mutableListOf<Bitmap?>()
        
        // 成员头像（最多4个），通过 memberAvatarCache 避免重复 Base64 解码
        val membersToShow = memberImeis.take(4)
        val fallbackSize = (size / 2).coerceAtLeast(40)

        membersToShow.forEach { imei ->
            val avatar = try {
                loadMemberAvatar(context, imei, fallbackSize)
            } catch (e: Exception) {
                Log.e(TAG, "加载成员头像失败: $imei, ${e.message}")
                createLetterAvatar(imei.take(8), fallbackSize)
            }
            avatars.add(avatar)
        }
        
        // 如果成员少于4个，优先补充无头像助手的“昵称首字母头像”
        if (avatars.size < 4 && assistantIds.isNotEmpty()) {
            val avatarCellSize = (size / 2).coerceAtLeast(40)
            assistantIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(4)
                .forEach { assistantId ->
                    if (avatars.size >= 4) return@forEach
                    avatars.add(createAssistantFallbackAvatar(context, assistantId, avatarCellSize))
                }
        }

        // 仍不足4个时，空位保持留白
        while (avatars.size < 4) {
            avatars.add(null)
        }
        
        // 生成群组头像
        val groupAvatar = createGroupAvatar4Members(context, avatars, size)
        
        // 存入缓存（创建副本，避免被回收）
        val cachedCopy = groupAvatar.copy(groupAvatar.config, false)
        groupAvatarCache.put(cacheKey, cachedCopy)
        
        return groupAvatar
    }
    
    /**
     * 创建4成员群头像（2x2网格）
     * @param context 上下文
     * @param avatars 头像列表，最多4个（左上、右上、左下、右下）
     * @param size 输出头像尺寸（像素）
     * @return 拼接后的Bitmap
     */
    private fun createGroupAvatar4Members(context: Context, avatars: List<Bitmap?>, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // 绘制白色背景
        canvas.drawColor(ContextCompat.getColor(context, android.R.color.white))
        
        // 计算每个小头像的尺寸和位置
        val halfSize = size / 2
        val padding = 2 // 小头像之间的间距（像素）
        
        // 绘制左上角头像（索引0）
        if (avatars.size > 0 && avatars[0] != null) {
            drawAvatar(canvas, avatars[0]!!, 0, 0, halfSize - padding, halfSize - padding)
        }
        
        // 绘制右上角头像（索引1）
        if (avatars.size > 1 && avatars[1] != null) {
            drawAvatar(canvas, avatars[1]!!, halfSize + padding, 0, halfSize - padding, halfSize - padding)
        }
        
        // 绘制左下角头像（索引2）
        if (avatars.size > 2 && avatars[2] != null) {
            drawAvatar(canvas, avatars[2]!!, 0, halfSize + padding, halfSize - padding, halfSize - padding)
        }
        
        // 绘制右下角头像（索引3）
        if (avatars.size > 3 && avatars[3] != null) {
            drawAvatar(canvas, avatars[3]!!, halfSize + padding, halfSize + padding, halfSize - padding, halfSize - padding)
        }
        
        return result
    }
}

