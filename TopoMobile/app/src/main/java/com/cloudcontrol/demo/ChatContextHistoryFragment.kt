package com.cloudcontrol.demo

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.cloudcontrol.demo.databinding.FragmentChatContextHistoryBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 聊天上下文历史记录Fragment
 * 显示保存的聊天上下文记录，使用气泡样式展示
 */
class ChatContextHistoryFragment : Fragment() {
    
    companion object {
        private const val TAG = "ChatContextHistoryFragment"
        private const val ARG_CONVERSATION_ID = "conversation_id"
        private const val ARG_ASSISTANT_NAME = "assistant_name"
        
        fun newInstance(conversationId: String, assistantName: String): ChatContextHistoryFragment {
            return ChatContextHistoryFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONVERSATION_ID, conversationId)
                    putString(ARG_ASSISTANT_NAME, assistantName)
                }
            }
        }
    }
    
    private var _binding: FragmentChatContextHistoryBinding? = null
    private val binding get() = _binding!!
    
    private var conversationId: String? = null
    private var assistantName: String? = null
    private lateinit var adapter: HistoryAdapter
    private var markwon: Markwon? = null
    
    // 图片缩略图缓存（使用LRU缓存）
    private val imageThumbnailCache = android.util.LruCache<String, android.graphics.Bitmap>(50) // 最多缓存50张图片
    
    private data class HistoryItem(
        val sender: String,
        val message: String,
        val timestamp: Long
    ) {
        // 确保数据类自动生成equals和hashCode
    }
    
    /**
     * DiffUtil回调，用于高效更新RecyclerView
     */
    private class HistoryItemDiffCallback(
        private val oldList: List<HistoryItem>,
        private val newList: List<HistoryItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].timestamp == newList[newItemPosition].timestamp &&
                   oldList[oldItemPosition].sender == newList[newItemPosition].sender
        }
        
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition] == newList[newItemPosition]
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatContextHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        conversationId = arguments?.getString(ARG_CONVERSATION_ID)
        assistantName = arguments?.getString(ARG_ASSISTANT_NAME) ?: "小助手"
        
        // 初始化Markwon
        markwon = MarkdownRenderer.createMarkwon(requireContext())
        
        // 隐藏ActionBar
        (activity as? MainActivity)?.supportActionBar?.hide()
        
        setupUI()
        loadHistory()
    }
    
    override fun onResume() {
        super.onResume()
        // 隐藏ActionBar
        try {
            (activity as? MainActivity)?.supportActionBar?.hide()
        } catch (e: Exception) {
            Log.w(TAG, "隐藏ActionBar失败: ${e.message}", e)
        }
        // 隐藏底部导航栏
        try {
            (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        } catch (e: Exception) {
            Log.w(TAG, "隐藏底部导航栏失败: ${e.message}", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 注意：不在这里恢复ActionBar和底部导航栏，让下一个Fragment自己管理
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            // 检查是否有ChatFragment被隐藏，如果有则显式show它
            val chatFragment = parentFragmentManager.fragments.find { it is com.cloudcontrol.demo.ChatFragment && it.isHidden }
            if (chatFragment != null) {
                // 先移除当前Fragment，然后show回ChatFragment
                parentFragmentManager.beginTransaction()
                    .remove(this)
                    .show(chatFragment)
                    .commitAllowingStateLoss()
            } else {
                // 没有隐藏的ChatFragment，使用正常的popBackStack
                parentFragmentManager.popBackStack()
            }
        }
        
        // 历史总结按钮
        binding.btnHistorySummary.setOnClickListener {
            conversationId?.let { convId ->
                try {
                    // 读取保存的总结
                    val summary = loadChatSummary(convId)
                    // 创建Conversation对象用于传递
                    val conversation = Conversation(
                        id = convId,
                        name = assistantName ?: "小助手",
                        avatar = null,
                        lastMessage = null,
                        lastMessageTime = System.currentTimeMillis()
                    )
                    val summaryFragment = ChatSummaryFragment.newInstance(conversation, summary)
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(0, 0, 0, 0)  // 禁用所有动画
                        .replace(R.id.fragmentContainer, summaryFragment)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                } catch (e: Exception) {
                    Log.e(TAG, "导航到历史总结页面失败: ${e.message}", e)
                }
            }
        }
        
        // 更新历史总结按钮的可见性
        updateHistorySummaryButtonVisibility()
        
        // 清空历史记录按钮
        binding.btnClearHistory.setOnClickListener {
            showClearHistoryConfirmDialog()
        }
        
        // 设置RecyclerView（向上对齐，从顶部开始显示）
        adapter = HistoryAdapter()
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext()).apply {
            reverseLayout = false  // 正常顺序，最旧的在上，最新的在下
            stackFromEnd = false   // 从顶部开始显示
        }
        binding.rvHistory.adapter = adapter
    }
    
    /**
     * 加载聊天总结
     */
    private fun loadChatSummary(conversationId: String): String? {
        return try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val key = "chat_summary_$conversationId"
            prefs.getString(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "加载聊天总结失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 更新历史总结按钮的可见性
     */
    private fun updateHistorySummaryButtonVisibility() {
        val convId = conversationId ?: return
        val summary = loadChatSummary(convId)
        binding.btnHistorySummary.visibility = if (summary != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
    
    /**
     * 显示清空历史记录确认对话框
     */
    private fun showClearHistoryConfirmDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("清空历史记录")
            .setMessage("确定要清空历史记录和对话总结吗？此操作不可恢复。")
            .setPositiveButton("确定") { _, _ ->
                clearHistory()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 清空历史记录和对话总结
     */
    private fun clearHistory() {
        val conversationId = this.conversationId ?: return
        
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            
            // 删除历史记录
            val contextKey = "chat_context_$conversationId"
            prefs.edit().remove(contextKey).apply()
            Log.d(TAG, "已删除历史记录: $contextKey")
            
            // 删除对话总结
            val summaryKey = "chat_summary_$conversationId"
            prefs.edit().remove(summaryKey).apply()
            Log.d(TAG, "已删除对话总结: $summaryKey")
            
            // 清空列表并显示空状态
            adapter.submitList(emptyList())
            binding.rvHistory.visibility = View.GONE
            binding.llEmpty.visibility = View.VISIBLE
            
            // 隐藏历史总结按钮
            binding.btnHistorySummary.visibility = View.GONE
            
            // 显示成功提示
            android.widget.Toast.makeText(
                requireContext(),
                "历史记录和对话总结已清空",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "清空历史记录失败: ${e.message}", e)
            android.widget.Toast.makeText(
                requireContext(),
                "清空失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    /**
     * 加载历史记录（异步加载，避免阻塞主线程）
     */
    private fun loadHistory() {
        val conversationId = this.conversationId ?: return
        
        // 在IO线程加载和解析数据
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val key = "chat_context_$conversationId"
                val contextJson = prefs.getString(key, null)
                
                if (contextJson == null || contextJson.isEmpty()) {
                    // 没有历史记录，显示空状态
                    withContext(Dispatchers.Main) {
                        binding.rvHistory.visibility = View.GONE
                        binding.llEmpty.visibility = View.VISIBLE
                    }
                    return@launch
                }
                
                // 在IO线程解析JSON
                val historyItems = mutableListOf<HistoryItem>()
                val jsonArray = org.json.JSONArray(contextJson)
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val sender = obj.getString("sender")
                    val message = obj.getString("message")
                    val timestamp = if (obj.has("timestamp")) {
                        obj.getLong("timestamp")
                    } else {
                        System.currentTimeMillis()
                    }
                    historyItems.add(HistoryItem(sender, message, timestamp))
                }
                
                // 按时间正序排列（最旧的在前，最新的在后，类似聊天界面）
                historyItems.sortBy { it.timestamp }
                
                // 切换到主线程更新UI
                withContext(Dispatchers.Main) {
                    if (isAdded) { // 检查Fragment是否还存在
                        adapter.submitList(historyItems)
                        
                        // 显示列表，隐藏空状态
                        binding.rvHistory.visibility = View.VISIBLE
                        binding.llEmpty.visibility = View.GONE
                        
                        // 滚动到顶部（显示最早的消息）
                        binding.rvHistory.post {
                            if (historyItems.isNotEmpty()) {
                                binding.rvHistory.scrollToPosition(0)
                            }
                        }
                        
                        Log.d(TAG, "加载了 ${historyItems.size} 条历史记录")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载历史记录失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        binding.rvHistory.visibility = View.GONE
                        binding.llEmpty.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
    
    /**
     * 转换消息为Markdown格式（简化版，从ChatFragment复制）
     */
    private fun convertToMarkdown(message: String): String =
        MarkdownTextFormatter.toDisplayMarkdown(message)
    
    /**
     * 提取图片路径（从消息中提取 [图片: 文件名] 格式）
     */
    private fun extractImagePath(message: String): String? {
        val pattern = Regex("\\[图片: (.+?)\\]")
        val match = pattern.find(message)
        return match?.groupValues?.get(1)?.let { fileName ->
            // 图片保存在 getExternalFilesDir(null)/images/ 目录下
            val imagesDir = java.io.File(requireContext().getExternalFilesDir(null), "images")
            val imageFile = java.io.File(imagesDir, fileName)
            if (imageFile.exists()) {
                imageFile.absolutePath
            } else {
                null
            }
        }
    }
    
    /**
     * 移除消息中的图片标记，返回纯文本
     */
    private fun removeImageMarkers(message: String): String {
        return message.replace(Regex("\\[图片: .+?\\]"), "").trim()
    }
    
    /**
     * 加载图片缩略图（带缓存）
     */
    private fun loadImageThumbnail(imagePath: String, maxHeight: Int): android.graphics.Bitmap? {
        // 先检查缓存
        val cacheKey = "${imagePath}_${maxHeight}"
        val cachedBitmap = imageThumbnailCache.get(cacheKey)
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            return cachedBitmap
        }
        
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(imagePath, options)
            
            val imageHeight = options.outHeight
            val imageWidth = options.outWidth
            val scale = if (imageHeight > maxHeight) {
                (imageHeight / maxHeight.toFloat()).toInt().coerceAtLeast(1)
            } else {
                1
            }
            
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            
            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath, decodeOptions)
            if (bitmap != null) {
                // 进一步压缩到目标尺寸
                val targetWidth = (maxHeight * (imageWidth.toFloat() / imageHeight)).toInt()
                val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, targetWidth, maxHeight, true)
                
                // 回收原始bitmap以节省内存
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                }
                
                // 存入缓存
                imageThumbnailCache.put(cacheKey, scaledBitmap)
                
                scaledBitmap
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载图片缩略图失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 创建消息气泡（类似ChatFragment的实现）
     */
    private fun createMessageBubble(
        sender: String,
        message: String,
        isUserMessage: Boolean
    ): android.widget.LinearLayout {
        val messageContainer = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            gravity = if (isUserMessage) {
                android.view.Gravity.END or android.view.Gravity.TOP
            } else {
                android.view.Gravity.START or android.view.Gravity.TOP
            }
        }
        
        val avatarSize = (36 * resources.displayMetrics.density).toInt()
        val avatarMargin = (8 * resources.displayMetrics.density).toInt()
        val sideMargin = (16 * resources.displayMetrics.density).toInt()
        val leftRightFixedWidth = avatarSize + avatarMargin + sideMargin
        val textBubbleCornerRadius = 16f * resources.displayMetrics.density
        val textBubbleHorizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val textBubbleVerticalPadding = (6 * resources.displayMetrics.density).toInt()
        
        if (isUserMessage) {
            // 用户消息：右对齐
            // 使用垂直容器来包含图片和文本（如果有）
            val verticalContainer = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.END
            }
            
            // 检查是否包含图片
            val imagePath = extractImagePath(message)
            val textMessage = removeImageMarkers(message)
            
            // 如果有图片，显示图片缩略图
            if (imagePath != null) {
                val imageHeight = (resources.displayMetrics.widthPixels * 0.3).toInt()
                val imageWidth = imageHeight
                
                val imageContainer = android.widget.FrameLayout(requireContext()).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        imageWidth,
                        imageHeight
                    ).apply {
                        setMargins(0, 0, 0, if (textMessage.isNotEmpty()) (8 * resources.displayMetrics.density).toInt() else 0)
                    }
                    
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF95EC69.toInt())
                        cornerRadius = 16f * resources.displayMetrics.density
                    }
                    background = drawable
                }
                
                val thumbnailImageView = android.widget.ImageView(requireContext()).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                }
                
                // 异步加载图片缩略图
                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                    val thumbnail = withContext(Dispatchers.IO) {
                        loadImageThumbnail(imagePath, imageHeight)
                    }
                    if (thumbnail != null && isAdded) {
                        thumbnailImageView.setImageBitmap(thumbnail)
                    } else {
                        thumbnailImageView.setBackgroundColor(0xFFCCCCCC.toInt())
                    }
                }
                
                imageContainer.addView(thumbnailImageView)
                verticalContainer.addView(imageContainer)
            }
            
            // 如果有文本消息，显示文本气泡
            if (textMessage.isNotEmpty()) {
                val bubble = android.widget.TextView(requireContext()).apply {
                    val markdownMessage = convertToMarkdown(textMessage)
                    markwon?.setMarkdown(this, markdownMessage)
                    
                    textSize = 16f
                    setPadding(
                        textBubbleHorizontalPadding,
                        textBubbleVerticalPadding,
                        textBubbleHorizontalPadding,
                        textBubbleVerticalPadding
                    )
                    maxWidth = (resources.displayMetrics.widthPixels * 0.76f).toInt()
                    minWidth = 0
                    setTextIsSelectable(true)
                    
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF95EC69.toInt()) // 浅绿色，用户消息
                        cornerRadius = textBubbleCornerRadius
                    }
                    background = drawable
                    setTextColor(0xFF000000.toInt())
                    
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                verticalContainer.addView(bubble)
            }
            
            val contentContainer = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.TOP
            }
            contentContainer.addView(verticalContainer)
            
            // 添加用户头像
            val avatarImageView = android.widget.ImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    setMargins(avatarMargin, 0, 0, 0)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                
                // 加载用户头像
                val userProfile = ProfileManager.loadProfileLocally(requireContext())
                val avatarBase64 = userProfile?.avatar
                if (!avatarBase64.isNullOrEmpty()) {
                    try {
                        val decodedBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        if (bitmap != null) {
                            setImageBitmap(bitmap)
                        } else {
                            setImageResource(R.drawable.ic_person)
                            background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
                        }
                    } catch (e: Exception) {
                        setImageResource(R.drawable.ic_person)
                        background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
                    }
                } else {
                    setImageResource(R.drawable.ic_person)
                    background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
                }
            }
            contentContainer.addView(avatarImageView)
            
            // 添加左侧占位符
            val leftSpacer = android.view.View(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    leftRightFixedWidth,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            messageContainer.addView(leftSpacer)
            messageContainer.addView(contentContainer)
        } else {
            // 助手消息：左对齐
            val avatarImageView = android.widget.ImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    setMargins(sideMargin, 0, avatarMargin, 0)
                }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                
                // 根据conversationId设置头像
                when (conversationId) {
                    ConversationListFragment.CONVERSATION_ID_ASSISTANT -> {
                        setImageResource(R.drawable.ic_assistant_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> {
                        setImageResource(R.drawable.ic_skill_learning_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> {
                        setImageResource(R.drawable.ic_chat_assistant_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> {
                        setImageResource(R.drawable.ic_system_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_GROUP -> {
                        setImageResource(R.drawable.ic_system_avatar)
                    }
                    else -> {
                        setImageResource(R.drawable.ic_system_avatar)
                    }
                }
            }
            messageContainer.addView(avatarImageView)
            
            // 创建消息气泡
            val bubble = android.widget.TextView(requireContext()).apply {
                val markdownMessage = convertToMarkdown(message)
                markwon?.setMarkdown(this, markdownMessage)
                
                textSize = 16f
                setPadding(
                    textBubbleHorizontalPadding,
                    textBubbleVerticalPadding,
                    textBubbleHorizontalPadding,
                    textBubbleVerticalPadding
                )
                maxWidth = (resources.displayMetrics.widthPixels * 0.76f).toInt()
                minWidth = 0
                setTextIsSelectable(true)
                
                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFF0F0F0.toInt()) // 助手消息（与主会话一致浅灰）
                    cornerRadius = textBubbleCornerRadius
                }
                background = drawable
                setTextColor(0xFF000000.toInt())
                
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            messageContainer.addView(bubble)
            
            // 添加右侧占位符
            val rightSpacer = android.view.View(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            messageContainer.addView(rightSpacer)
        }
        
        return messageContainer
    }
    
    /**
     * 历史记录Adapter
     */
    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        
        private var items = listOf<HistoryItem>()
        
        fun submitList(newList: List<HistoryItem>) {
            val oldList = items
            val diffResult = DiffUtil.calculateDiff(HistoryItemDiffCallback(oldList, newList))
            items = newList
            diffResult.dispatchUpdatesTo(this)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(parent)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }
        
        override fun getItemCount() = items.size
        
        inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
            android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        ) {
            
            fun bind(item: HistoryItem) {
                val container = itemView as android.widget.LinearLayout
                container.removeAllViews()
                
                val isUserMessage = item.sender == "我" || item.sender == "用户"
                
                // 创建消息气泡
                val messageBubble = createMessageBubble(item.sender, item.message, isUserMessage)
                container.addView(messageBubble)
            }
        }
    }
}
