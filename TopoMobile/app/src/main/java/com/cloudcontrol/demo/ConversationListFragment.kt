package com.cloudcontrol.demo

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cloudcontrol.demo.databinding.FragmentConversationListBinding
import com.cloudcontrol.demo.databinding.ItemConversationBinding
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 对话列表Fragment
 * 显示所有对话，类似微信的聊天列表
 */
class ConversationListFragment : Fragment() {
    
    companion object {
        private const val TAG = "ConversationListFragment"
        private const val TOPOCLAW_CUSTOM_ASSISTANT_ID = "custom_topoclaw"
        private const val BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID = "custom_topoclaw"
        private const val KEY_TOPICLAW_DEFAULT_CUSTOM_ASSISTANT_INITIALIZED = "topoclaw_default_custom_assistant_initialized"
        const val CONVERSATION_ID_ME = "_me"
        const val CONVERSATION_ID_ASSISTANT = "assistant"
        const val CONVERSATION_ID_SKILL_LEARNING = "skill_learning"
        const val CONVERSATION_ID_CHAT_ASSISTANT = "chat_assistant"
        const val CONVERSATION_ID_GROUP_MANAGER = "custom_groupmanager"
        const val CONVERSATION_ID_GROUP = "group"
        const val CONVERSATION_ID_CUSTOMER_SERVICE = "customer_service"
        const val CONVERSATION_ID_ADMIN_CUSTOMER_SERVICE = "admin_customer_service"
        private val HIDDEN_BUILTIN_CONVERSATION_IDS = setOf(
            CONVERSATION_ID_ASSISTANT,
            CONVERSATION_ID_SKILL_LEARNING,
            CONVERSATION_ID_CUSTOMER_SERVICE
        )
        
        // 广播Action，用于通知未读消息更新
        const val ACTION_UNREAD_COUNT_UPDATED = "com.cloudcontrol.demo.UNREAD_COUNT_UPDATED"
        /** 添加自定义小助手后通知列表刷新 */
        const val ACTION_CUSTOM_ASSISTANT_ADDED = "com.cloudcontrol.demo.CUSTOM_ASSISTANT_ADDED"
        /** 扫码切换服务域名后，通知会话与通讯录立即刷新 */
        const val ACTION_SERVICE_DOMAIN_CHANGED = "com.cloudcontrol.demo.SERVICE_DOMAIN_CHANGED"
        
        /**
         * 获取对话的简介（当没有消息时显示）
         * @param context 用于获取本地化字符串
         */
        fun getConversationDescription(context: Context, conversationId: String): String {
            if (CustomAssistantManager.isCustomAssistantId(conversationId)) {
                return CustomAssistantManager.getById(context, conversationId)?.name ?: ""
            }
            return when (conversationId) {
                CONVERSATION_ID_ME -> "与 PC 端互通消息"
                CONVERSATION_ID_ASSISTANT -> context.getString(R.string.assistant_signature)
                CONVERSATION_ID_SKILL_LEARNING -> context.getString(R.string.skill_learn_signature)
                CONVERSATION_ID_CHAT_ASSISTANT -> context.getString(R.string.chat_assistant_signature)
                CONVERSATION_ID_CUSTOMER_SERVICE -> context.getString(R.string.customer_service_signature)
                CONVERSATION_ID_GROUP -> context.getString(R.string.friend_group_signature)
                CONVERSATION_ID_ADMIN_CUSTOMER_SERVICE -> context.getString(R.string.admin_customer_service_desc)
                else -> ""
            }
        }
    }
    
    private var _binding: FragmentConversationListBinding? = null
    private val binding get() = _binding!!
    
    private val conversations = mutableListOf<Conversation>()
    private lateinit var adapter: ConversationAdapter
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isTipsExpanded = false
    
    // 在线状态监听器
    private val onlineStatusListener = object : OnlineStatusManager.OnlineStatusListener {
        override fun onOnlineStatusChanged(onlineFriends: Set<String>) {
            if (isAdded && _binding != null) {
                // 刷新对话列表以更新在线状态图标
                adapter.notifyDataSetChanged()
            }
        }
    }

    private val pcOnlineStatusListener = object : PcOnlineStatusManager.PcOnlineStatusListener {
        override fun onPcOnlineStatusChanged(isOnline: Boolean) {
            if (isAdded && _binding != null) {
                adapter.notifyDataSetChanged()
            }
        }
    }
    
    // 未读消息更新广播接收器
    private val unreadCountReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UNREAD_COUNT_UPDATED -> {
                    android.util.Log.d(TAG, "收到未读消息更新广播，刷新列表")
                    if (isAdded && _binding != null && ::adapter.isInitialized) {
                        adapter.notifyDataSetChanged()
                    }
                    if (isAdded && _binding != null) {
                        (activity as? MainActivity)?.let { mainActivity ->
                            mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                        }
                    }
                }
                ACTION_CUSTOM_ASSISTANT_ADDED -> {
                    android.util.Log.d(TAG, "收到小助手变更广播（添加/删除），从云端同步并刷新列表")
                    if (isAdded && _binding != null) {
                        fetchCustomAssistantsFromCloudAndRefresh()
                    }
                }
                ACTION_SERVICE_DOMAIN_CHANGED -> {
                    android.util.Log.d(TAG, "收到服务域名切换广播，刷新会话列表")
                    if (isAdded && _binding != null) {
                        loadConversations()
                        fetchCustomAssistantsFromCloudAndRefresh()
                    }
                }
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationListBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 标题栏文本已在布局文件中设置为"TopoClaw"，无需修改
        // 设置标题栏背景颜色为浅灰色
        binding.llTitleBar.setBackgroundColor(0xFFF5F5F5.toInt())
        
        // 设置 DrawerLayout 监听器，确保侧边栏打开时层级正确
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // 在侧边栏滑动时，确保它在最前面
                if (slideOffset > 0f) {
                    binding.navigationView.bringToFront()
                }
            }
            
            override fun onDrawerOpened(drawerView: View) {
                // 侧边栏完全打开时，确保它在最前面
                binding.navigationView.bringToFront()
            }
            
            override fun onDrawerClosed(drawerView: View) {
                // 侧边栏关闭时不需要特殊处理
            }
            
            override fun onDrawerStateChanged(newState: Int) {
                // 状态改变时不需要特殊处理
            }
        })
        
        // 菜单按钮（打开侧边栏）
        binding.btnMenu.setOnClickListener {
            // 确保侧边栏的层级正确，使用 bringToFront 确保侧边栏在最前面
            binding.navigationView.bringToFront()
            binding.drawerLayout.openDrawer(Gravity.START)
        }
        
        // 设置搜索按钮点击事件
        binding.btnSearch.setOnClickListener {
            // 调用MainActivity的搜索对话框
            (activity as? MainActivity)?.showSearchDialog()
        }
        
        // 添加好友按钮 - 显示菜单（添加好友/创建群组）
        binding.btnAddFriend.setOnClickListener {
            showAddMenu(it)
        }
        
        // 设置Fragment层级的底部导航栏
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.setupFragmentBottomNavigation(binding.bottomNavigation, R.id.nav_chat)
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            // 初始化并更新聊天图标徽章
            binding.root.post {
                mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
            }
        }
        
        // 设置服务入口点击事件
        setupServiceEntry()
        setupTipsSection()
        
        // 设置其他入口点击事件
        setupGuideEntries()
        
        setupRecyclerView()
        setupSwipeRefresh()
        loadConversations()
        fetchCustomAssistantsFromCloudAndRefresh()

        // 注册在线状态监听器并启动检查
        OnlineStatusManager.addListener(onlineStatusListener)
        OnlineStatusManager.startChecking(requireContext())
        PcOnlineStatusManager.addListener(pcOnlineStatusListener)
        PcOnlineStatusManager.startChecking(requireContext())
        
        // 确保 DrawerLayout 的层级关系正确
        // 使用 post 确保在布局完成后执行
        binding.root.post {
            if (isAdded && _binding != null) {
                // 确保侧边栏的 elevation 高于底部导航栏
                binding.navigationView.elevation = 16f * resources.displayMetrics.density
                binding.bottomNavigation.elevation = 8f * resources.displayMetrics.density
            }
        }
        
        // 注册广播接收器
        context?.let { ctx ->
            val filter = IntentFilter().apply {
                addAction(ACTION_UNREAD_COUNT_UPDATED)
                addAction(ACTION_CUSTOM_ASSISTANT_ADDED)
                addAction(ACTION_SERVICE_DOMAIN_CHANGED)
            }
            LocalBroadcastManager.getInstance(ctx).registerReceiver(unreadCountReceiver, filter)
        }
    }
    
    /**
     * 设置服务入口
     */
    private fun setupServiceEntry() {
        binding.navigationView.findViewById<LinearLayout>(R.id.llServiceEntry)?.setOnClickListener {
            navigateToService()
        }
    }

    /**
     * 侧边栏「小贴士」分组默认收起，点击标题行切换展开/收起。
     */
    private fun setupTipsSection() {
        updateTipsSectionUi()
        binding.navigationView.findViewById<LinearLayout>(R.id.llTipsHeader)?.setOnClickListener {
            isTipsExpanded = !isTipsExpanded
            updateTipsSectionUi()
        }
    }

    private fun updateTipsSectionUi() {
        val tipsContent = binding.navigationView.findViewById<LinearLayout>(R.id.llTipsContent)
        val tipsArrow = binding.navigationView.findViewById<ImageView>(R.id.ivTipsArrow)
        tipsContent?.visibility = if (isTipsExpanded) View.VISIBLE else View.GONE
        tipsArrow?.rotation = if (isTipsExpanded) 90f else 0f
    }
    
    /**
     * 设置其他入口点击事件
     */
    private fun setupGuideEntries() {
        // 服务配置入口
        binding.navigationView.findViewById<LinearLayout>(R.id.llAccessibilityGuideEntry)?.setOnClickListener {
            navigateToGuide(AccessibilityGuideFragment())
        }
        
        // 如何使用悬浮球入口
        binding.navigationView.findViewById<LinearLayout>(R.id.llFloatingBallGuideEntry)?.setOnClickListener {
            navigateToGuide(FloatingBallGuideFragment())
        }

        // 定时任务入口
        binding.navigationView.findViewById<LinearLayout>(R.id.llScheduledTaskEntry)?.setOnClickListener {
            binding.drawerLayout.closeDrawer(Gravity.START)
            val ws = (activity as? MainActivity)?.getCustomerServiceWebSocket()
            SkillScheduleListDialog.show(requireContext(), ws)
        }

        // 随手记入口（暂未支持）
        binding.navigationView.findViewById<LinearLayout>(R.id.llQuickNoteEntry)?.setOnClickListener {
            Toast.makeText(requireContext(), "暂不支持", Toast.LENGTH_SHORT).show()
        }
        
        // 联系我们入口
        binding.navigationView.findViewById<LinearLayout>(R.id.llContactUsEntry)?.setOnClickListener {
            navigateToGuide(ContactUsFragment())
        }
    }
    
    /**
     * 导航到指南页面
     */
    private fun navigateToGuide(fragment: Fragment) {
        // 关闭侧边栏
        binding.drawerLayout.closeDrawer(Gravity.START)
        
        // 导航到指南页面
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,  // enter: 指南页面从右侧滑入
                    R.anim.slide_out_to_left,    // exit: 对话列表向左滑出
                    R.anim.slide_in_from_left,   // popEnter: 返回时，对话列表从左侧滑入
                    R.anim.slide_out_to_right    // popExit: 返回时，指南页面向右滑出
                )
                .replace(R.id.fragmentContainer, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
    }
    
    /**
     * 导航到服务页面
     */
    private fun navigateToService() {
        // 关闭侧边栏
        binding.drawerLayout.closeDrawer(Gravity.START)
        
        // 导航到服务页面
        val serviceFragment = ServiceSettingsFragment()
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,  // enter: 服务页面从右侧滑入
                    R.anim.slide_out_to_left,    // exit: 对话列表向左滑出
                    R.anim.slide_in_from_left,   // popEnter: 返回时，对话列表从左侧滑入
                    R.anim.slide_out_to_right    // popExit: 返回时，服务页面向右滑出
                )
                .replace(R.id.fragmentContainer, serviceFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
    }
    
    /**
     * 设置下拉刷新
     */
    private fun setupSwipeRefresh() {
        if (!isAdded || _binding == null) return
        
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshConversations()
        }
        
        // 设置刷新指示器颜色
        binding.swipeRefreshLayout.setColorSchemeColors(
            0xFF10AEFF.toInt(), // 主色调蓝色
            0xFF666666.toInt()  // 灰色
        )
    }
    
    /**
     * 刷新对话列表（下拉刷新时调用）
     */
    private fun refreshConversations() {
        if (!isAdded || _binding == null) return
        
        mainScope.launch {
            try {
                // 同步服务器数据
                val context = context ?: return@launch
                
                // 同步好友列表
                try {
                    FriendManager.syncFriendsFromServer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "同步好友列表失败: ${e.message}")
                }
                
                // 同步群组列表
                try {
                    GroupManager.syncGroupsFromServer(context)
                } catch (e: Exception) {
                    Log.w(TAG, "同步群组列表失败: ${e.message}")
                }
                
                // 重新加载对话列表
                loadConversations()
                
                Log.d(TAG, "下拉刷新完成")
                
                // 显示刷新成功提示
                withContext(Dispatchers.Main) {
                    if (isAdded && context != null) {
                        Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "刷新对话列表失败: ${e.message}", e)
                // 显示刷新失败提示
                withContext(Dispatchers.Main) {
                    if (isAdded && context != null) {
                        Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                // 确保在主线程关闭刷新指示器
                if (isAdded && _binding != null) {
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }
    
    private fun setupRecyclerView() {
        // 检查Fragment状态
        val context = context ?: return
        if (!isAdded || _binding == null) return
        
        adapter = ConversationAdapter(
            onItemClick = { conversation ->
                // 检查Fragment状态，避免在Fragment已销毁时导航
                if (!isAdded || _binding == null) return@ConversationAdapter
                try {
                    val mainActivity = activity as? MainActivity
                    // 直接切换Fragment，不需要隐藏ActionBar（因为ActionBar已经隐藏，两个页面完全独立）
                    mainActivity?.setBottomNavigationVisibility(false)
                    // 切换Fragment（ActionBar状态不变，不会有"退去"动画）
                    mainActivity?.switchToChatFragment(conversation)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "导航到聊天页面失败: ${e.message}", e)
                }
            },
            onItemLongClick = { conversation, position ->
                // 检查Fragment状态
                if (!isAdded || _binding == null) return@ConversationAdapter
                // 长按对话项，显示操作菜单（删除和置顶）
                showConversationMenu(conversation, position)
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter
        
        // 不同聊天对象缩略窗口间添加浅浅的分割线，左侧与头像右边缘对齐
        ContextCompat.getDrawable(context, R.drawable.chat_divider)?.let { divider ->
            val leftMarginPx = ((16 + 48) * context.resources.displayMetrics.density).toInt()  // paddingStart + 头像宽度
            binding.recyclerView.addItemDecoration(ConversationDividerItemDecoration(divider, leftMarginPx))
        }
    }
    
    /**
     * 显示对话操作菜单（删除和置顶）
     */
    private fun showConversationMenu(conversation: Conversation, position: Int) {
        // 检查Fragment状态
        val context = context ?: return
        if (!isAdded) return
        
        val items = arrayOf(
            if (conversation.isPinned) getString(R.string.unpin) else getString(R.string.pinned),
            getString(R.string.delete)
        )
        
        try {
            android.app.AlertDialog.Builder(context)
                .setItems(items) { _, which ->
                    if (!isAdded) return@setItems
                    when (which) {
                        0 -> {
                            // 置顶/取消置顶
                            togglePinConversation(conversation.id, position)
                        }
                        1 -> {
                            // 删除
                            showDeleteConfirmDialog(conversation, position)
                        }
                    }
                }
                .show()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "显示对话菜单失败: ${e.message}", e)
        }
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog(conversation: Conversation, position: Int) {
        // 检查Fragment状态
        val context = context ?: return
        if (!isAdded) return
        
        try {
            android.app.AlertDialog.Builder(context)
                .setTitle("删除聊天框")
                .setMessage("确定要删除与\"${conversation.name}\"的聊天框吗？")
                .setPositiveButton("删除") { _, _ ->
                    if (!isAdded) return@setPositiveButton
                    deleteConversation(conversation.id, position)
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "显示删除确认对话框失败: ${e.message}", e)
        }
    }
    
    /**
     * 切换对话的置顶状态
     */
    private fun togglePinConversation(conversationId: String, position: Int) {
        // 检查Fragment状态
        val context = context ?: return
        if (!isAdded) return
        
        val prefs = context.getSharedPreferences("conversations", android.content.Context.MODE_PRIVATE)
        val currentPinnedSet = prefs.getStringSet("pinned_conversations", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        
        // 通过ID查找对话，而不是依赖position（因为排序后位置可能改变）
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return
        
        val conversation = conversations[index]
        val newPinnedState = !conversation.isPinned
        
        if (newPinnedState) {
            // 置顶
            currentPinnedSet.add(conversationId)
            context?.let { ctx ->
                android.widget.Toast.makeText(ctx, getString(R.string.pinned_toast), android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            // 取消置顶
            currentPinnedSet.remove(conversationId)
            context?.let { ctx ->
                android.widget.Toast.makeText(ctx, getString(R.string.unpinned_toast), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        // 保存置顶状态
        prefs.edit()
            .putStringSet("pinned_conversations", currentPinnedSet)
            .apply()
        
        // 更新对话的置顶状态
        conversations[index] = conversation.copy(isPinned = newPinnedState)
        
        // 重新排序并更新UI
        sortAndUpdateConversations()
    }
    
    /**
     * 删除对话
     */
    private fun deleteConversation(conversationId: String, position: Int) {
        val context = context ?: return
        if (!isAdded || _binding == null || !::adapter.isInitialized) return
        
        if (CustomAssistantManager.isCustomAssistantId(conversationId)) {
            CustomAssistantManager.remove(context, conversationId)
        } else {
            val prefs = context.getSharedPreferences("conversations", android.content.Context.MODE_PRIVATE)
            val deletedSet = prefs.getStringSet("deleted_conversations", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            deletedSet.add(conversationId)
            prefs.edit().putStringSet("deleted_conversations", deletedSet).apply()
        }
        
        if (position >= 0 && position < conversations.size) {
            conversations.removeAt(position)
            adapter.notifyItemRemoved(position)
        }
        
        android.widget.Toast.makeText(context, "已删除聊天框", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 从云端拉取自定义小助手列表并刷新（端云同步，实现跨平台）
     */
    private fun fetchCustomAssistantsFromCloudAndRefresh() {
        val context = context ?: return
        if (!isAdded || _binding == null) return
        mainScope.launch {
            try {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val csUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(csUrl)
                val api = CustomerServiceNetwork.getApiService() ?: return@launch
                val imei = ProfileManager.getOrGenerateImei(context)
                val resp = withContext(Dispatchers.IO) { api.getCustomAssistants(imei) }
                if (resp.isSuccessful) {
                    val body = resp.body()
                    val cloudList = body?.assistants ?: emptyList()
                    val cloudItems = cloudList.map { item ->
                        CustomAssistantManager.CustomAssistant(
                            id = item.id,
                            name = item.name,
                            intro = item.intro ?: "",
                            baseUrl = item.baseUrl,
                            capabilities = item.capabilities ?: emptyList(),
                            avatar = item.avatar
                        )
                    }
                    val cloudIds = cloudItems.map { it.id }.toSet()
                    val local = CustomAssistantManager.getAll(context)
                    // 云侧为空时以云为准（可能其他端已全部删除）；否则合并云+本地独有
                    val localOnly = if (cloudItems.isNotEmpty()) local.filter { it.id !in cloudIds } else emptyList()
                    CustomAssistantManager.replaceAll(context, cloudItems + localOnly)
                    loadConversations()
                    Log.d(TAG, "已从云端同步自定义小助手，数量=${cloudItems.size}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "拉取云端小助手失败: ${e.message}")
            }
        }
    }

    fun loadConversations() {
        // 检查Fragment状态，避免在Fragment未附加或已销毁时执行
        val context = context ?: return
        if (!isAdded || _binding == null) return

        // 加载对话列表
        // 当前有三个默认对话："TopoClaw"、"技能学习小助手"和"群"
        conversations.clear()
        
        val prefs = context.getSharedPreferences("conversations", android.content.Context.MODE_PRIVATE)
        
        // 获取已删除的对话ID集合
        var deletedSet = prefs.getStringSet("deleted_conversations", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        // 好友群默认删除（但仍留在好友列表，可以召回）
        // 如果之前没有设置过，默认添加到删除列表
        val hasGroupDeletedFlag = prefs.contains("group_default_deleted")
        if (!hasGroupDeletedFlag) {
            deletedSet.add(CONVERSATION_ID_GROUP)
            prefs.edit()
                .putStringSet("deleted_conversations", deletedSet)
                .putBoolean("group_default_deleted", true)  // 标记已设置默认删除
                .apply()
        }
        val hiddenBuiltinAdded = deletedSet.addAll(HIDDEN_BUILTIN_CONVERSATION_IDS)
        
        // 获取置顶的对话ID集合
        var pinnedSet = prefs.getStringSet("pinned_conversations", emptySet())?.toMutableSet() ?: mutableSetOf()
        val hiddenBuiltinUnpinned = pinnedSet.removeAll(HIDDEN_BUILTIN_CONVERSATION_IDS)
        if (hiddenBuiltinAdded || hiddenBuiltinUnpinned) {
            prefs.edit()
                .putStringSet("deleted_conversations", deletedSet)
                .putStringSet("pinned_conversations", pinnedSet)
                .apply()
        }
        
        // 如果"TopoClaw"不在置顶列表中，默认添加为置顶
        if (CONVERSATION_ID_ASSISTANT !in HIDDEN_BUILTIN_CONVERSATION_IDS && CONVERSATION_ID_ASSISTANT !in pinnedSet) {
            pinnedSet.add(CONVERSATION_ID_ASSISTANT)
            prefs.edit()
                .putStringSet("pinned_conversations", pinnedSet)
                .apply()
        }

        // 首次初始化：确保内置自定义小助手 custom_topoclaw 默认可见并置顶
        val hasInitializedBuiltinTopoclaw = prefs.getBoolean(
            KEY_TOPICLAW_DEFAULT_CUSTOM_ASSISTANT_INITIALIZED,
            false
        )
        if (!hasInitializedBuiltinTopoclaw) {
            val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val customAssistants = CustomAssistantManager.getAll(context)
            if (customAssistants.none { it.id == BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID }) {
                val baseUrl = appPrefs.getString("chat_server_url", ChatConstants.DEFAULT_CHAT_SERVER_URL)
                    ?: ChatConstants.DEFAULT_CHAT_SERVER_URL
                CustomAssistantManager.add(
                    context,
                    CustomAssistantManager.CustomAssistant(
                        id = BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID,
                        name = getString(R.string.topoclaw_assistant),
                        intro = getString(R.string.custom_assistant_signature),
                        baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/",
                        capabilities = listOf(
                            CustomAssistantManager.CAP_CHAT,
                            CustomAssistantManager.CAP_EXECUTION_MOBILE
                        )
                    )
                )
            }

            deletedSet.remove(BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID)
            pinnedSet.add(BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID)
            prefs.edit()
                .putStringSet("deleted_conversations", deletedSet)
                .putStringSet("pinned_conversations", pinnedSet)
                .putBoolean(KEY_TOPICLAW_DEFAULT_CUSTOM_ASSISTANT_INITIALIZED, true)
                .apply()
        } else if (BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID in pinnedSet) {
            // 初始化后，如果仍在置顶集合里，确保它不会被删除集合误伤
            if (BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID in deletedSet) {
                deletedSet.remove(BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID)
                prefs.edit().putStringSet("deleted_conversations", deletedSet).apply()
            }
        }
        
        // 验证对话是否有实际聊天消息的辅助方法
        // 优先用 lastMessage 判断（与 updateLastMessage 同步，更可靠）；若为空则解析 chat_messages
        // lastMessage 为可选参数，用于兼容调用方传入；未传时从 prefs 读取
        fun hasActualChatMessages(conversationId: String, lastMessage: String? = null): Boolean {
            if (!lastMessage.isNullOrBlank()) return true
            val lastMsg = prefs.getString("${conversationId}_last_message", null)
            if (!lastMsg.isNullOrBlank()) return true
            val appPrefs = context?.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) ?: return false
            val messagesJson = appPrefs.getString("chat_messages_$conversationId", null) ?: return false
            if (messagesJson.isEmpty() || messagesJson == "[]") return false
            try {
                val jsonArray = org.json.JSONArray(messagesJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val sender = obj.optString("sender", "")
                    val type = obj.optString("type", "")
                    val message = obj.optString("message", "")
                    if (message.isBlank()) continue
                    val isPureSystemHint = sender == "系统" && type != "complete" && type != "answer"
                    if (!isPureSystemHint) return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "验证聊天消息失败: ${e.message}")
            }
            return false
        }
        
        // 是否应显示：置顶的始终显示，非置顶的仅在有历史消息时显示
        fun shouldShowConversation(conversationId: String, isPinned: Boolean): Boolean =
            isPinned || hasActualChatMessages(conversationId)
        
        // 我（端云互发）
        if (CONVERSATION_ID_ME !in deletedSet) {
            val meLastMessage = prefs.getString("${CONVERSATION_ID_ME}_last_message", null)
            val meLastMessageTime = if (meLastMessage != null) {
                prefs.getLong("${CONVERSATION_ID_ME}_last_time", 0L)
            } else 0L
            conversations.add(
                Conversation(
                    id = CONVERSATION_ID_ME,
                    name = "我的电脑",
                    avatar = null,
                    lastMessage = meLastMessage,
                    lastMessageTime = meLastMessageTime,
                    isPinned = true
                )
            )
        }
        
        // TopoClaw
        if (CONVERSATION_ID_ASSISTANT !in deletedSet) {
            val assistantLastMessage = prefs.getString("${CONVERSATION_ID_ASSISTANT}_last_message", null)
            // 保留已保存的最后消息，即使消息记录暂时为空也不清除
            val assistantLastMessageTime = if (assistantLastMessage != null) {
                prefs.getLong("${CONVERSATION_ID_ASSISTANT}_last_time", 0L)
            } else {
                0L
            }
            conversations.add(
                Conversation(
                    id = CONVERSATION_ID_ASSISTANT,
                    name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                    avatar = null,
                    lastMessage = assistantLastMessage,
                    lastMessageTime = assistantLastMessageTime,
                    isPinned = true
                )
            )
        }
        
        // 技能学习小助手
        if (CONVERSATION_ID_SKILL_LEARNING !in deletedSet) {
            val skillIsPinned = CONVERSATION_ID_SKILL_LEARNING in pinnedSet
            if (shouldShowConversation(CONVERSATION_ID_SKILL_LEARNING, skillIsPinned)) {
            val skillLastMessage = prefs.getString("${CONVERSATION_ID_SKILL_LEARNING}_last_message", null)
            // 保留已保存的最后消息，即使消息记录暂时为空也不清除
            val skillLastMessageTime = if (skillLastMessage != null) {
                prefs.getLong("${CONVERSATION_ID_SKILL_LEARNING}_last_time", 0L)
            } else {
                0L
            }
            conversations.add(
                Conversation(
                    id = CONVERSATION_ID_SKILL_LEARNING,
                    name = "技能学习小助手",
                    avatar = null,
                    lastMessage = skillLastMessage,
                    lastMessageTime = skillLastMessageTime,
                    isPinned = skillIsPinned
                )
            )
            }
        }
        
        // 聊天小助手
        if (CONVERSATION_ID_CHAT_ASSISTANT !in deletedSet) {
            val chatAssistantIsPinned = CONVERSATION_ID_CHAT_ASSISTANT in pinnedSet
            if (shouldShowConversation(CONVERSATION_ID_CHAT_ASSISTANT, chatAssistantIsPinned)) {
            val chatAssistantLastMessage = prefs.getString("${CONVERSATION_ID_CHAT_ASSISTANT}_last_message", null)
            val chatAssistantLastMessageTime = if (chatAssistantLastMessage != null) {
                prefs.getLong("${CONVERSATION_ID_CHAT_ASSISTANT}_last_time", 0L)
            } else {
                0L
            }
            conversations.add(
                Conversation(
                    id = CONVERSATION_ID_CHAT_ASSISTANT,
                    name = "聊天小助手",
                    avatar = null,
                    lastMessage = chatAssistantLastMessage,
                    lastMessageTime = chatAssistantLastMessageTime,
                    isPinned = chatAssistantIsPinned
                )
            )
            }
        }
        
        // 人工客服
        if (CONVERSATION_ID_CUSTOMER_SERVICE !in deletedSet) {
            val customerServiceIsPinned = CONVERSATION_ID_CUSTOMER_SERVICE in pinnedSet
            if (shouldShowConversation(CONVERSATION_ID_CUSTOMER_SERVICE, customerServiceIsPinned)) {
            val customerServiceLastMessage = prefs.getString("${CONVERSATION_ID_CUSTOMER_SERVICE}_last_message", null)
            // 保留已保存的最后消息，即使消息记录暂时为空也不清除
            val customerServiceLastMessageTime = if (customerServiceLastMessage != null) {
                prefs.getLong("${CONVERSATION_ID_CUSTOMER_SERVICE}_last_time", 0L)
            } else {
                0L
            }
            conversations.add(
                Conversation(
                    id = CONVERSATION_ID_CUSTOMER_SERVICE,
                    name = "人工客服",
                    avatar = null,
                    lastMessage = customerServiceLastMessage,
                    lastMessageTime = customerServiceLastMessageTime,
                    isPinned = customerServiceIsPinned
                )
            )
            }
        }
        
        // 自定义小助手（通过扫码或粘贴链接添加）
        CustomAssistantManager.getVisibleAll(context).forEach { assistant ->
            val assistantIsPinned = assistant.id in pinnedSet
            if (shouldShowConversation(assistant.id, assistantIsPinned)) {
            val lastMessage = prefs.getString("${assistant.id}_last_message", null)
            val lastMessageTime = if (lastMessage != null) {
                prefs.getLong("${assistant.id}_last_time", 0L)
            } else 0L
            conversations.add(
                Conversation(
                    id = assistant.id,
                    name = assistant.name,
                    avatar = assistant.avatar,
                    lastMessage = lastMessage,
                    lastMessageTime = lastMessageTime,
                    isPinned = assistantIsPinned
                )
            )
            }
        }
        
        // 管理员模式：显示所有用户与人工客服的聊天记录入口
        if (AdminModeManager.isAdminModeEnabled(context)) {
            conversations.add(
                Conversation(
                    id = CONVERSATION_ID_ADMIN_CUSTOMER_SERVICE,
                    name = "管理员-所有客服记录",
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis(),
                    isPinned = true
                )
            )
        }
        
        // 加载所有其他群组对话列表
        val groups = GroupManager.getGroups(context)
        val defaultGroupConversationIds = groups
            .filter { it.isDefaultGroup }
            .map { "group_${it.groupId}" }
        if (defaultGroupConversationIds.isNotEmpty()) {
            var changed = false
            defaultGroupConversationIds.forEach { convId ->
                if (convId in deletedSet) {
                    deletedSet.remove(convId)
                    changed = true
                }
                if (convId !in pinnedSet) {
                    pinnedSet.add(convId)
                    changed = true
                }
            }
            if (changed) {
                prefs.edit()
                    .putStringSet("deleted_conversations", deletedSet)
                    .putStringSet("pinned_conversations", pinnedSet)
                    .apply()
            }
        }
        groups.forEach { group ->
            val groupConversationId = "group_${group.groupId}"
            if (groupConversationId !in deletedSet) {
                val groupIsPinned = groupConversationId in pinnedSet
                if (shouldShowConversation(groupConversationId, groupIsPinned)) {
                val groupLastMessage = prefs.getString("${groupConversationId}_last_message", null)
                // 保留已保存的最后消息，即使消息记录暂时为空也不清除
                val groupLastMessageTime = if (groupLastMessage != null) {
                    prefs.getLong("${groupConversationId}_last_time", 0L)
                } else {
                    0L
                }
                conversations.add(
                    Conversation(
                        id = groupConversationId,
                        name = group.name,
                        avatar = null,
                        lastMessage = groupLastMessage,
                        lastMessageTime = groupLastMessageTime,
                        isPinned = groupIsPinned
                    )
                )
                }
            }
        }
        
        // 加载好友对话列表
        val friends = FriendManager.getFriends(context)
        friends.forEach { friend ->
            if (friend.status == "accepted") {
                val friendConversationId = "friend_${friend.imei}"
                if (friendConversationId !in deletedSet) {
                    val friendIsPinned = friendConversationId in pinnedSet
                    if (shouldShowConversation(friendConversationId, friendIsPinned)) {
                    val friendLastMessage = prefs.getString("${friendConversationId}_last_message", null)
                    // 保留已保存的最后消息，即使消息记录暂时为空也不清除
                    val friendLastMessageTime = if (friendLastMessage != null) {
                        prefs.getLong("${friendConversationId}_last_time", 0L)
                    } else {
                        0L
                    }
                    conversations.add(
                        Conversation(
                            id = friendConversationId,
                            name = friend.nickname ?: friend.imei.take(8) + "...",
                            avatar = friend.avatar,
                            lastMessage = friendLastMessage,
                            lastMessageTime = friendLastMessageTime,
                            isPinned = friendIsPinned
                        )
                    )
                    }
                }
            }
        }
        
        // 兜底：prefs 里已有 friend_* 最后一条消息，但此前未出现在好友列表中（历史落盘或列表未合并）
        val listedIds = conversations.map { it.id }.toMutableSet()
        for (key in prefs.all.keys) {
            if (!key.endsWith("_last_message")) continue
            if (!key.startsWith("friend_")) continue
            val convId = key.removeSuffix("_last_message")
            if (convId in deletedSet || convId in listedIds) continue
            val imei = convId.removePrefix("friend_")
            if (imei.isBlank()) continue
            FriendManager.ensureFriendForIncomingMessage(context, imei, null, null)
            val friend = FriendManager.getFriend(context, imei) ?: continue
            if (friend.status != "accepted") continue
            val friendIsPinned = convId in pinnedSet
            if (!shouldShowConversation(convId, friendIsPinned)) continue
            val friendLastMessage = prefs.getString("${convId}_last_message", null)
            val friendLastMessageTime = if (friendLastMessage != null) {
                prefs.getLong("${convId}_last_time", 0L)
            } else {
                0L
            }
            conversations.add(
                Conversation(
                    id = convId,
                    name = friend.nickname ?: friend.imei.take(8) + "...",
                    avatar = friend.avatar,
                    lastMessage = friendLastMessage,
                    lastMessageTime = friendLastMessageTime,
                    isPinned = friendIsPinned
                )
            )
            listedIds.add(convId)
        }
        
        // 排序并更新UI
        sortAndUpdateConversations()
    }
    
    /**
     * 排序对话列表并更新UI
     * 排序规则：置顶的对话排在最前面，置顶内部按时间降序；未置顶的按时间降序
     * 注意：没有消息的对话（lastMessageTime为0或lastMessage为null）排在最后
     */
    private fun sortAndUpdateConversations() {
        // 检查Fragment状态和binding，避免在Fragment已销毁时更新UI
        if (!isAdded || _binding == null || !::adapter.isInitialized) return
        
        // 排序：置顶的在前，然后按时间降序
        // 置顶内优先级：custom_topoclaw > 其他置顶；再按时间降序
        // 没有消息的对话（lastMessageTime为0或lastMessage为null）排在最后（使用Long.MIN_VALUE）
        conversations.sortWith(compareByDescending<Conversation> { it.isPinned }
            .thenByDescending {
                when (it.id) {
                    BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID -> 2
                    CONVERSATION_ID_ASSISTANT -> 1
                    else -> 0
                }
            }
            .thenByDescending { 
                // 如果有消息，使用实际时间；如果没有消息，使用Long.MIN_VALUE排在最后
                if (it.lastMessage != null && it.lastMessageTime > 0) {
                    it.lastMessageTime
                } else {
                    Long.MIN_VALUE
                }
            })
        adapter.submitList(conversations.toList())
    }
    
    /**
     * 更新对话的最后消息
     * 当聊天页面有新消息时调用此方法
     */
    fun updateLastMessage(conversationId: String, message: String) {
        // 检查Fragment状态，避免在Fragment未附加或已销毁时执行
        val context = context ?: return
        if (!isAdded) return
        
        val prefs = context.getSharedPreferences("conversations", android.content.Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()
        
        // 隐藏的内置会话只更新存储，不恢复到会话列表
        if (conversationId in HIDDEN_BUILTIN_CONVERSATION_IDS) {
            prefs.edit()
                .putString("${conversationId}_last_message", message)
                .putLong("${conversationId}_last_time", currentTime)
                .apply()
            return
        }
        
        // 检查该对话是否在已删除列表中
        val deletedSet = prefs.getStringSet("deleted_conversations", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val wasDeleted = conversationId in deletedSet
        
        // 如果之前被删除了，需要恢复
        if (wasDeleted) {
            deletedSet.remove(conversationId)
            prefs.edit()
                .putStringSet("deleted_conversations", deletedSet)
                .apply()
            // 重新加载对话列表以恢复该对话
            loadConversations()
            return
        }
        
        // 如果对话已在列表中，更新它
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index >= 0) {
            val conversation = conversations[index]
            conversations[index] = conversation.copy(
                lastMessage = message,
                lastMessageTime = currentTime
            )
            
            // 保存到SharedPreferences
            prefs.edit()
                .putString("${conversationId}_last_message", message)
                .putLong("${conversationId}_last_time", currentTime)
                .apply()
            
            // 重新排序并更新UI
            sortAndUpdateConversations()
        } else {
            // 如果对话不在列表中（可能是新对话），重新加载列表
            loadConversations()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // 如果 Fragment 被隐藏（用户在其他页面如 ChatFragment），跳过所有 UI 操作，
        // 否则 BottomNavigationView.selectedItemId 会触发 listener 并把 ChatFragment 切走。
        if (!isAdded || context == null || isHidden) return
        
        // 确保 DrawerLayout 的层级关系正确（从聊天详情页返回后可能被破坏）
        // 使用 post 确保在布局完成后执行
        binding.root.post {
            if (isAdded && _binding != null) {
                // 确保侧边栏的 elevation 高于底部导航栏
                binding.navigationView.elevation = 16f * resources.displayMetrics.density
                binding.bottomNavigation.elevation = 8f * resources.displayMetrics.density
                // 如果侧边栏是打开的，确保它在最前面
                if (binding.drawerLayout.isDrawerOpen(Gravity.START)) {
                    binding.navigationView.bringToFront()
                }
            }
        }
        
        // 清除当前对话ID，确保弹窗能正常显示
        // 因为对话列表页面不是任何聊天页面，所以应该清除currentConversationId
        (activity as? MainActivity)?.setCurrentConversationId(null)
        Log.d(TAG, "ConversationListFragment onResume: 已清除currentConversationId")
        
        // 确保ActionBar隐藏（聊天列表页面有自己的标题栏，不需要ActionBar）
        // 注意：底部导航栏的显示由MainActivity的updateUIForCurrentFragment()统一管理，避免重复设置导致闪烁
        (activity as? MainActivity)?.let { mainActivity ->
            // ActionBar应该保持隐藏状态，因为Fragment有自己的标题栏
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 设置Fragment层级的底部导航栏背景颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            // 更新Fragment层级的底部导航栏选中状态
            binding.bottomNavigation.selectedItemId = R.id.nav_chat
            // 更新聊天图标徽章
            binding.root.post {
                mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
            }
        }
        // 每次返回时刷新对话列表
        loadConversations()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && _binding != null) {
            (activity as? MainActivity)?.let { mainActivity ->
                mainActivity.setCurrentConversationId(null)
                if (mainActivity.supportActionBar?.isShowing == true) {
                    mainActivity.hideActionBarInstantly()
                }
                mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
                binding.bottomNavigation.selectedItemId = R.id.nav_chat
                binding.root.post {
                    if (_binding != null) {
                        mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                    }
                }
            }
            loadConversations()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
        // 移除在线状态监听器
        OnlineStatusManager.removeListener(onlineStatusListener)
        PcOnlineStatusManager.removeListener(pcOnlineStatusListener)
        PcOnlineStatusManager.stopChecking()
        
        // 注销未读消息更新广播接收器
        context?.let { ctx ->
            try {
                LocalBroadcastManager.getInstance(ctx).unregisterReceiver(unreadCountReceiver)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "注销广播接收器失败: ${e.message}")
            }
        }
        
        // 取消协程作用域
        mainScope.cancel()
        
        _binding = null
    }
    
    /**
     * 对话列表Adapter
     */
    private class ConversationAdapter(
        private val onItemClick: (Conversation) -> Unit,
        private val onItemLongClick: (Conversation, Int) -> Unit
    ) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {
        
        private var items = listOf<Conversation>()
        
        fun submitList(newList: List<Conversation>) {
            items = newList
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemConversationBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }
        
        override fun getItemCount() = items.size
        
        inner class ViewHolder(
            private val binding: ItemConversationBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            
            fun bind(conversation: Conversation) {
                binding.tvName.text = DisplayNameHelper.getDisplayName(binding.root.context, conversation.name)
                // 如果没有最后消息，显示助手的简介
                binding.tvLastMessage.text = conversation.lastMessage ?: ConversationListFragment.getConversationDescription(binding.root.context, conversation.id)
                // 如果没有最后消息，时间显示为空（不显示"刚刚"）
                binding.tvTime.text = if (conversation.lastMessage != null && conversation.lastMessageTime > 0) {
                    formatTime(conversation.lastMessageTime)
                } else {
                    ""
                }
                
                // 设置置顶标签显示/隐藏
                binding.tvPin.visibility = if (conversation.isPinned) View.VISIBLE else View.GONE
                
                // 设置在线状态图标（仅好友对话显示）
                if (conversation.id.startsWith("friend_")) {
                    val friendImei = conversation.id.removePrefix("friend_")
                    binding.ivOnlineStatus.visibility = if (OnlineStatusManager.isFriendOnline(friendImei)) View.VISIBLE else View.GONE
                } else if (conversation.id == ConversationListFragment.TOPOCLAW_CUSTOM_ASSISTANT_ID) {
                    binding.ivOnlineStatus.visibility = if (PcOnlineStatusManager.isPcOnline()) View.VISIBLE else View.GONE
                } else {
                    binding.ivOnlineStatus.visibility = View.GONE
                }
                
                // 未读红点：客服 / 好友 / 群 / 自定义小助手 / 兜底（端云、内置小助手等）统一入口
                val unreadCount = ConversationSessionNotifier.getUnreadCountForList(binding.root.context, conversation.id)
                if (unreadCount > 0) {
                    binding.tvUnreadBadge.visibility = View.VISIBLE
                    binding.tvUnreadBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                } else {
                    binding.tvUnreadBadge.visibility = View.GONE
                }
                
                // 设置头像
                when (conversation.id) {
                    CONVERSATION_ID_ME -> {
                        binding.ivAvatar.setImageResource(R.drawable.ic_computer_avatar)
                    }
                    CONVERSATION_ID_ASSISTANT -> {
                        // TopoClaw使用专用头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_assistant_avatar)
                    }
                    CONVERSATION_ID_SKILL_LEARNING -> {
                        // 技能学习小助手使用专用头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_skill_learning_avatar)
                    }
                    CONVERSATION_ID_CUSTOMER_SERVICE -> {
                        // 人工客服使用专用头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_customer_service_avatar)
                    }
                    CONVERSATION_ID_CHAT_ASSISTANT -> {
                        // 聊天小助手使用专用头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_chat_assistant_avatar)
                    }
                    CONVERSATION_ID_GROUP -> {
                        // 群头像：先检查缓存，如果有缓存直接设置，避免闪烁
                        val avatarSize = (56 * binding.root.resources.displayMetrics.density).toInt()
                        val context = binding.root.context
                        val cachedAvatar = GroupAvatarHelper.getCachedFriendsGroupAvatar(context, avatarSize)
                        
                        if (cachedAvatar != null) {
                            // 缓存命中，直接设置，无闪烁
                            binding.ivAvatar.setImageBitmap(cachedAvatar)
                        } else {
                            // 缓存未命中，先显示默认头像，然后异步加载
                            binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                            val currentPosition = adapterPosition
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val groupAvatar = GroupAvatarHelper.createFriendsGroupAvatar(context, avatarSize)
                                    // 切换到主线程更新UI
                                    withContext(Dispatchers.Main) {
                                        // 检查 ViewHolder 是否仍然有效（位置未改变且未回收）
                                        if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION 
                                            && adapterPosition == currentPosition) {
                                            try {
                                                binding.ivAvatar.setImageBitmap(groupAvatar)
                                            } catch (e: Exception) {
                                                // ViewHolder 可能已被回收或 Fragment 已销毁，忽略错误
                                                android.util.Log.w(TAG, "更新群头像失败: ${e.message}")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // 加载头像失败，忽略错误
                                    android.util.Log.w(TAG, "加载群头像失败: ${e.message}")
                                }
                            }
                        }
                    }
                    CONVERSATION_ID_ADMIN_CUSTOMER_SERVICE -> {
                        // 管理员模式对话使用特殊头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_customer_service_avatar)
                    }
                    else -> {
                        // 检查是否是动态群组
                        if (conversation.id.startsWith("group_")) {
                            // 动态群组头像：使用群组成员头像拼接
                            val groupId = conversation.id.removePrefix("group_")
                            val group = GroupManager.getGroup(binding.root.context, groupId)
                            if (group != null) {
                                val avatarSize = (56 * binding.root.resources.displayMetrics.density).toInt()
                                val context = binding.root.context
                                
                                // 先检查缓存，如果有缓存直接设置，避免闪烁
                                val cachedAvatar = GroupAvatarHelper.getCachedGroupAvatarFromMembers(
                                    group.members,
                                    avatarSize,
                                    group.assistants
                                )
                                
                                if (cachedAvatar != null) {
                                    // 缓存命中，直接设置，无闪烁
                                    binding.ivAvatar.setImageBitmap(cachedAvatar)
                                } else {
                                    // 缓存未命中，先显示默认头像，然后异步加载
                                    binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                                    val currentPosition = adapterPosition
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val groupAvatar = GroupAvatarHelper.createGroupAvatarFromMembers(
                                                context,
                                                group.members,
                                                avatarSize,
                                                group.assistants
                                            )
                                            // 切换到主线程更新UI
                                            withContext(Dispatchers.Main) {
                                                // 检查 ViewHolder 是否仍然有效（位置未改变且未回收）
                                                if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION 
                                                    && adapterPosition == currentPosition) {
                                                    try {
                                                        binding.ivAvatar.setImageBitmap(groupAvatar)
                                                    } catch (e: Exception) {
                                                        // ViewHolder 可能已被回收或 Fragment 已销毁，忽略错误
                                                        android.util.Log.w(TAG, "更新群组头像失败: ${e.message}")
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // 加载头像失败，忽略错误
                                            android.util.Log.w(TAG, "加载群组头像失败: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                // 群组不存在，使用默认头像
                                binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                            }
                        } else {
                            // 其他对话（包括好友对话、自定义小助手）加载头像
                            if (CustomAssistantManager.isCustomAssistantId(conversation.id)) {
                                val assistant = CustomAssistantManager.getById(binding.root.context, conversation.id)
                                if (assistant != null) {
                                    val avatarSize = (56 * binding.root.resources.displayMetrics.density).toInt()
                                    AvatarCacheManager.loadCustomAssistantAvatar(
                                        context = binding.root.context,
                                        imageView = binding.ivAvatar,
                                        assistant = assistant,
                                        cacheKey = "conversation_${conversation.id}",
                                        validationTag = conversation.id,
                                        sizePx = avatarSize
                                    )
                                } else {
                                    binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                                }
                            } else {
                                val cacheKey = "conversation_${conversation.id}"
                                AvatarCacheManager.loadBase64Avatar(
                                    context = binding.root.context,
                                    imageView = binding.ivAvatar,
                                    base64String = conversation.avatar,
                                    defaultResId = R.drawable.ic_system_avatar,
                                    cacheKey = cacheKey,
                                    validationTag = conversation.id
                                )
                            }
                        }
                    }
                }
                
                // 点击事件
                binding.root.setOnClickListener {
                    onItemClick(conversation)
                }
                
                // 长按事件
                binding.root.setOnLongClickListener {
                    val position = adapterPosition
                    if (position != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        onItemLongClick(conversation, position)
                    }
                    true
                }
            }
            
            private fun formatTime(timestamp: Long): String {
                val ctx = binding.root.context
                val now = System.currentTimeMillis()
                val diff = now - timestamp
                
                return when {
                    diff < 60 * 1000 -> ctx.getString(R.string.just_now)
                    diff < 60 * 60 * 1000 -> ctx.getString(R.string.minutes_ago_format, (diff / (60 * 1000)).toInt())
                    diff < 24 * 60 * 60 * 1000 -> ctx.getString(R.string.hours_ago_format, (diff / (60 * 60 * 1000)).toInt())
                    else -> {
                        val sdf = SimpleDateFormat("MM/dd", Locale.getDefault())
                        sdf.format(Date(timestamp))
                    }
                }
            }
        }
    }
    
    /**
     * 显示添加菜单（添加好友/创建群组/扫一扫/添加小助手）
     */
    private fun showAddMenu(anchor: View) {
        val popupMenu = android.widget.PopupMenu(requireContext(), anchor)
        popupMenu.menu.add(0, 1, 1, getString(R.string.add_friend))
        popupMenu.menu.add(0, 2, 2, getString(R.string.create_group))
        popupMenu.menu.add(0, 0, 3, getString(R.string.scan_qr))
        popupMenu.menu.add(0, 3, 4, getString(R.string.add_assistant))
        
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> {
                    navigateToScanQRCode()
                    true
                }
                1 -> {
                    showAddFriendDialog()
                    true
                }
                2 -> {
                    showCreateGroupDialog()
                    true
                }
                3 -> {
                    showAddAssistantDialog()
                    true
                }
                else -> false
            }
        }
        
        popupMenu.show()
    }
    
    /**
     * 导航到扫一扫页面
     */
    private fun navigateToScanQRCode() {
        val scanFragment = ScanQRCodeFragment()
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,  // enter: 新Fragment从右侧滑入
                R.anim.slide_out_to_left,    // exit: 旧Fragment向左滑出
                R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
            )
            .replace(R.id.fragmentContainer, scanFragment)
            .addToBackStack(null)
            .commit()
    }
    
    /**
     * 显示添加小助手对话框（粘贴链接）
     */
    private fun showAddAssistantDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_assistant, null)
        val etLink = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAssistantLink)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnAdd = dialogView.findViewById<android.widget.Button>(R.id.btnAdd)
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
        dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
            }
        }
        dialogView.clipToOutline = true
        
        fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
        btnClose.setOnClickListener { dismiss() }
        btnCancel.setOnClickListener { dismiss() }
        btnAdd.setOnClickListener {
            val link = etLink.text?.toString()?.trim() ?: ""
            val assistant = CustomAssistantManager.parseAssistantUrl(link, requireContext())
            if (assistant != null) {
                CustomAssistantManager.add(requireContext(), assistant)
                loadConversations()
                android.widget.Toast.makeText(requireContext(), getString(R.string.add_assistant_success), android.widget.Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                android.widget.Toast.makeText(requireContext(), getString(R.string.add_assistant_invalid_link), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }
    
    /**
     * 构建可加入群组的小助手列表（id to name）
     * 包含：TopoClaw、技能学习小助手、聊天小助手、自定义小助手
     */
    private fun buildGroupAssistantList(context: Context): List<Pair<String, String>> {
        val list = linkedMapOf<String, String>()
        list[CONVERSATION_ID_GROUP_MANAGER] = "GroupManager"
        list[CONVERSATION_ID_ASSISTANT] = context.getString(R.string.auto_execute_assistant)
        list[CONVERSATION_ID_SKILL_LEARNING] = context.getString(R.string.skill_learn_assistant)
        list[CONVERSATION_ID_CHAT_ASSISTANT] = context.getString(R.string.chat_assistant)
        CustomAssistantManager.getVisibleAll(context).forEach { a ->
            if (!list.containsKey(a.id)) list[a.id] = a.name
        }
        return list.entries.map { it.toPair() }
    }
    
    /**
     * 显示创建群组对话框
     */
    private fun showCreateGroupDialog() {
        // 创建对话框布局
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_group, null)
        val etGroupName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etGroupName)
        val llAssistantsList = dialogView.findViewById<LinearLayout>(R.id.llAssistantsList)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btnCreate)
        val llFriendsList = dialogView.findViewById<LinearLayout>(R.id.llFriendsList)
        val tvSelectedCount = dialogView.findViewById<TextView>(R.id.tvSelectedCount)
        
        // 存储选中的好友和小助手
        val selectedFriends = mutableSetOf<String>()
        val selectedAssistants = mutableSetOf<String>()
        
        // 构建小助手列表（与好友列表风格一致）
        val assistantItems = buildGroupAssistantList(requireContext())
        fun updateSelectedCount() {
            var text = getString(R.string.selected_count_people_format, selectedFriends.size)
            if (selectedAssistants.isNotEmpty()) {
                text += "，${selectedAssistants.size} 个小助手"
            }
            tvSelectedCount.text = text
        }
        assistantItems.forEach { (id, name) ->
            val itemView = layoutInflater.inflate(
                android.R.layout.simple_list_item_multiple_choice,
                llAssistantsList,
                false
            ) as android.widget.CheckedTextView
            itemView.text = name
            itemView.textSize = 16f
            itemView.setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            itemView.setOnClickListener {
                if (id == CONVERSATION_ID_GROUP_MANAGER) return@setOnClickListener
                itemView.isChecked = !itemView.isChecked
                if (itemView.isChecked) selectedAssistants.add(id) else selectedAssistants.remove(id)
                updateSelectedCount()
            }
            // GroupManager 固定启用且不可取消；自动执行小助手默认不勾选。
            if (id == CONVERSATION_ID_GROUP_MANAGER) {
                itemView.isChecked = true
                selectedAssistants.add(id)
                itemView.isEnabled = false
            }
            llAssistantsList.addView(itemView)
        }
        
        // 创建对话框
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        // 设置窗口背景透明以显示圆角
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // 设置圆角outline以正确裁剪
        val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
        dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
            }
        }
        dialogView.clipToOutline = true
        
        // 加载好友列表并显示
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" }
        
        if (friends.isEmpty()) {
            // 如果没有好友，显示提示
            val emptyView = TextView(requireContext()).apply {
                text = getString(R.string.no_friends_add_first)
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            llFriendsList.addView(emptyView)
        } else {
            // 为每个好友创建选择项
            friends.forEach { friend ->
                val friendItemView = layoutInflater.inflate(
                    android.R.layout.simple_list_item_multiple_choice,
                    llFriendsList,
                    false
                ) as android.widget.CheckedTextView
                
                friendItemView.text = friend.nickname ?: friend.imei.take(8) + "..."
                friendItemView.textSize = 16f
                friendItemView.setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt()
                )
                
                // 设置点击事件
                friendItemView.setOnClickListener {
                    val isChecked = friendItemView.isChecked
                    friendItemView.isChecked = !isChecked
                    
                    if (friendItemView.isChecked) {
                        selectedFriends.add(friend.imei)
                    } else {
                        selectedFriends.remove(friend.imei)
                    }
                    
                    // 更新选中数量
                    updateSelectedCount()
                }
                
                llFriendsList.addView(friendItemView)
            }
        }
        
        // 更新选中数量显示
        updateSelectedCount()
        
        // 设置关闭按钮点击事件
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // 设置取消按钮点击事件
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        // 设置创建按钮点击事件
        btnCreate.setOnClickListener {
            val groupName = etGroupName.text?.toString()?.trim() ?: ""
            
            if (groupName.isEmpty()) {
                Toast.makeText(requireContext(), "请输入群组名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (selectedFriends.isEmpty()) {
                Toast.makeText(requireContext(), "请至少选择一个好友", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            dialog.dismiss()
            createGroup(groupName, selectedFriends.toList(), selectedAssistants.contains(CONVERSATION_ID_ASSISTANT))
        }
        
        dialog.show()
        
        // 自动聚焦输入框
        etGroupName.requestFocus()
        // 显示软键盘
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etGroupName, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 创建群组
     * @param assistantEnabled 是否添加小助手，可通过@小助手下达指令
     */
    private fun createGroup(groupName: String, memberImeis: List<String>, assistantEnabled: Boolean = true) {
        mainScope.launch {
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    Toast.makeText(requireContext(), "无法连接到服务器", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                val response = apiService.createGroup(CreateGroupRequest(groupName, memberImeis, currentImei, assistantEnabled))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val groupId = response.body()?.groupId
                    val groupInfo = response.body()?.group
                    
                    if (groupId != null && groupInfo != null) {
                        // 保存群组信息到本地
                        val group = GroupManager.Group(
                            groupId = groupInfo.group_id,
                            name = groupInfo.name,
                            creatorImei = groupInfo.creator_imei,
                            members = groupInfo.members,
                            createdAt = groupInfo.created_at,
                            assistantEnabled = groupInfo.assistant_enabled,
                            assistants = groupInfo.assistants ?: if (groupInfo.assistant_enabled) listOf("assistant") else emptyList()
                        )
                        GroupManager.addGroup(requireContext(), group)
                        
                        // 刷新对话列表
                        loadConversations()
                        
                        Toast.makeText(requireContext(), "群组创建成功", Toast.LENGTH_SHORT).show()
                        
                        // 可选：自动跳转到新创建的群组聊天界面
                        val groupConv = Conversation(
                            id = "group_$groupId",
                            name = groupName,
                            avatar = null,
                            lastMessage = null,
                            lastMessageTime = System.currentTimeMillis()
                        )
                        (activity as? MainActivity)?.switchToChatFragment(groupConv)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.create_group_failed_no_id), Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errorMsg = response.body()?.message ?: "创建失败"
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "创建群组失败: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.create_group_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 显示添加好友对话框
     */
    private fun showAddFriendDialog() {
        // 创建对话框布局
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_friend, null)
        val etFriendImei = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFriendImei)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnAdd = dialogView.findViewById<android.widget.Button>(R.id.btnAdd)
        
        // 创建对话框
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        // 设置窗口背景透明以显示圆角
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // 设置圆角outline以正确裁剪
        val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
        dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
            }
        }
        dialogView.clipToOutline = true
        
        // 设置关闭按钮点击事件
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // 设置取消按钮点击事件
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        // 设置添加按钮点击事件
        btnAdd.setOnClickListener {
            val friendImei = etFriendImei.text?.toString()?.trim() ?: ""
            if (friendImei.isNotEmpty()) {
                dialog.dismiss()
                addFriend(friendImei)
            } else {
                Toast.makeText(requireContext(), "请输入IMEI", Toast.LENGTH_SHORT).show()
            }
        }
        
        dialog.show()
        
        // 自动聚焦输入框
        etFriendImei.requestFocus()
        // 显示软键盘
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etFriendImei, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    
    /**
     * 添加好友
     */
    private fun addFriend(friendImei: String) {
        mainScope.launch {
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    Toast.makeText(requireContext(), "无法连接到服务器", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                val response = apiService.addFriend(AddFriendRequest(friendImei, currentImei))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    // 添加好友到本地
                    val friend = Friend(
                        imei = friendImei,
                        nickname = null,
                        avatar = null,
                        status = "accepted",
                        addedAt = System.currentTimeMillis()
                    )
                    FriendManager.addFriend(requireContext(), friend)
                    
                    // 记录好友关系的发起方（我发起的）
                    val prefs = requireContext().getSharedPreferences("friends_prefs", android.content.Context.MODE_PRIVATE)
                    val initiatorJson = prefs.getString("friend_initiator", null)
                    val initiatorMap = if (initiatorJson != null) {
                        try {
                            val type = object : TypeToken<MutableMap<String, String>>() {}.type
                            Gson().fromJson<MutableMap<String, String>>(initiatorJson, type) ?: mutableMapOf()
                        } catch (e: Exception) {
                            mutableMapOf()
                        }
                    } else {
                        mutableMapOf()
                    }
                    initiatorMap[friendImei] = currentImei
                    prefs.edit().putString("friend_initiator", Gson().toJson(initiatorMap)).apply()
                    
                    // 同步服务器好友列表
                    try {
                        FriendManager.syncFriendsFromServer(requireContext())
                    } catch (e: Exception) {
                        Log.w(TAG, "同步好友列表失败: ${e.message}")
                    }
                    
                    // 刷新对话列表
                    loadConversations()
                    
                    Toast.makeText(requireContext(), getString(R.string.add_friend_success), Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = response.body()?.message ?: "添加失败"
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "添加好友失败: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.add_friend_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
}

