package com.cloudcontrol.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cloudcontrol.demo.databinding.FragmentFriendBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

/**
 * 通讯录Fragment
 * 展示好友列表
 */
class FriendFragment : Fragment() {
    
    companion object {
        private const val TAG = "FriendFragment"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5分钟同步间隔
        private const val PREFS_NAME = "friend_fragment_prefs"
        private const val KEY_LAST_FRIENDS_SYNC = "last_friends_sync_time"
        private const val KEY_LAST_GROUPS_SYNC = "last_groups_sync_time"
        private const val KEY_ASSISTANT_EXPANDED = "assistant_expanded"
        private const val KEY_GROUP_EXPANDED = "group_expanded"
        private const val KEY_FRIEND_EXPANDED = "friend_expanded"
        private val HIDDEN_BUILTIN_ASSISTANT_IDS = setOf(
            ConversationListFragment.CONVERSATION_ID_ASSISTANT,
            ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING,
            ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE
        )
    }
    
    private var _binding: FragmentFriendBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isGroupsListInitialized = false

    // 数据指纹：跳过数据未变时的无效重绘
    private var lastFriendsFingerprint: String = ""
    private var lastGroupsFingerprint: String = ""

    // 分组展开/收起状态
    private var isAssistantExpanded = true
    private var isGroupExpanded = true
    private var isFriendExpanded = true
    
    // 在线状态监听器：只做轻量级 UI 刷新（图标 + 标题），不做全量列表重建
    private val onlineStatusListener = object : OnlineStatusManager.OnlineStatusListener {
        override fun onOnlineStatusChanged(onlineFriends: Set<String>) {
            if (isAdded && _binding != null) {
                updateOnlineStatusIcons()
                updateFriendGroupTitle()
            }
        }
    }
    
    // 添加自定义小助手广播接收器
    private val customAssistantAddedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConversationListFragment.ACTION_CUSTOM_ASSISTANT_ADDED -> {
                    Log.d(TAG, "收到添加小助手广播，刷新助手列表")
                    if (isAdded && _binding != null) {
                        loadAndDisplayCustomAssistants()
                    }
                }
                ConversationListFragment.ACTION_SERVICE_DOMAIN_CHANGED -> {
                    Log.d(TAG, "收到服务域名切换广播，静默刷新通讯录")
                    if (isAdded && _binding != null) {
                        refreshFriendsAndGroupsSilently()
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
        _binding = FragmentFriendBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（通讯录页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 设置标题栏背景颜色为浅灰色
        binding.llTitleBar.setBackgroundColor(0xFFF5F5F5.toInt())
        
        // 设置Fragment层级的底部导航栏
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.setupFragmentBottomNavigation(binding.bottomNavigation, R.id.nav_friend)
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            // 初始化并更新聊天图标徽章
            binding.root.post {
                mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
            }
        }
        
        setupFriendsList()
        setupSwipeRefresh()
        setupGroupHeaders()
        loadAndDisplayFriends()
        loadAndDisplayGroups()
        loadAndDisplayCustomAssistants()
        isGroupsListInitialized = true // 标记群组列表已初始化
        updateBadge()
        // 注册在线状态监听器并启动检查
        OnlineStatusManager.addListener(onlineStatusListener)
        OnlineStatusManager.startChecking(requireContext())
        // 注册添加小助手广播
        val filter = IntentFilter().apply {
            addAction(ConversationListFragment.ACTION_CUSTOM_ASSISTANT_ADDED)
            addAction(ConversationListFragment.ACTION_SERVICE_DOMAIN_CHANGED)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(customAssistantAddedReceiver, filter)
    }
    
    override fun onResume() {
        super.onResume()
        if (!isAdded || context == null || _binding == null || isHidden) return
        
        // 确保ActionBar隐藏（通讯录页面有自己的标题栏，不需要ActionBar）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 设置Fragment层级的底部导航栏背景颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            // 更新Fragment层级的底部导航栏选中状态
            binding.bottomNavigation.selectedItemId = R.id.nav_friend
            // 更新聊天图标徽章
            binding.root.post {
                if (_binding != null) {
                    mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                }
            }
        }
        
        // 更新红点显示
        updateBadge()
        
        // 只在群组列表未初始化时才加载（避免切换页面时重复刷新）
        // 如果需要刷新数据，可以通过下拉刷新或外部调用 refreshGroupsList() 来实现
        if (!isGroupsListInitialized) {
            loadAndDisplayFriends()
            loadAndDisplayGroups()
            loadAndDisplayCustomAssistants()
            isGroupsListInitialized = true
        }
        
        // 恢复在线状态检查
        OnlineStatusManager.startChecking(requireContext())
    }
    
    /**
     * 当Fragment的隐藏状态改变时调用（使用hide/show时）
     * 当从ChatFragment返回时，Fragment会从隐藏变为显示
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)

        if (!hidden && isAdded && context != null && _binding != null) {
            Log.d(TAG, "Fragment从隐藏变为显示，只更新UI状态")

            (activity as? MainActivity)?.let { mainActivity ->
                if (mainActivity.supportActionBar?.isShowing == true) {
                    mainActivity.hideActionBarInstantly()
                }
                mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
                binding.bottomNavigation.selectedItemId = R.id.nav_friend
                binding.root.post {
                    if (_binding != null) {
                        mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                    }
                }
            }

            updateBadge()
            updateOnlineStatusIcons()
            updateFriendGroupTitle()
        }
    }
    
    /**
     * 设置下拉刷新
     */
    private fun setupSwipeRefresh() {
        if (!isAdded || _binding == null) return
        
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshFriendsAndGroups()
        }
        
        // 设置刷新指示器颜色
        binding.swipeRefreshLayout.setColorSchemeColors(
            0xFF10AEFF.toInt(), // 主色调蓝色
            0xFF666666.toInt()  // 灰色
        )
    }
    
    /**
     * 刷新好友和群组列表（下拉刷新时调用）
     */
    private fun refreshFriendsAndGroups() {
        if (!isAdded || _binding == null) return
        
        mainScope.launch {
            try {
                val context = requireContext()
                
                // 强制同步服务器好友列表
                try {
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                        ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                    
                    CustomerServiceNetwork.initialize(customerServiceUrl)
                    FriendManager.syncFriendsFromServer(context)
                    recordFriendsSyncTime()
                    Log.d(TAG, "刷新：好友列表同步完成")
                } catch (e: Exception) {
                    Log.w(TAG, "刷新：同步好友列表失败: ${e.message}")
                }
                
                // 强制同步服务器群组列表
                try {
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                        ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                    
                    CustomerServiceNetwork.initialize(customerServiceUrl)
                    GroupManager.syncGroupsFromServer(context)
                    recordGroupsSyncTime()
                    Log.d(TAG, "刷新：群组列表同步完成")
                } catch (e: Exception) {
                    Log.w(TAG, "刷新：同步群组列表失败: ${e.message}")
                }
                
                // 重新加载并显示好友和群组列表
                loadAndDisplayFriends()
                loadAndDisplayGroups()
                loadAndDisplayCustomAssistants()
                updateBadge()
                // 更新好友分组标题（在线数）
                updateFriendGroupTitle()
                
                Log.d(TAG, "下拉刷新完成")
                
                // 显示刷新成功提示
                withContext(Dispatchers.Main) {
                    if (isAdded && context != null) {
                        Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "刷新好友和群组列表失败: ${e.message}", e)
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

    /**
     * 域名切换后的静默刷新：强制拉新域名下好友/群组/助手，不弹提示。
     */
    private fun refreshFriendsAndGroupsSilently() {
        if (!isAdded || _binding == null) return
        mainScope.launch {
            try {
                val context = requireContext()
                withContext(Dispatchers.IO) {
                    val customerServiceUrl = ServiceUrlConfig.getCustomerServiceUrl(context)
                    CustomerServiceNetwork.initialize(customerServiceUrl)
                    FriendManager.syncFriendsFromServer(context)
                    GroupManager.syncGroupsFromServer(context)
                }
                recordFriendsSyncTime()
                recordGroupsSyncTime()
                loadAndDisplayFriends()
                loadAndDisplayGroups()
                loadAndDisplayCustomAssistants()
                updateBadge()
                updateFriendGroupTitle()
            } catch (e: Exception) {
                Log.w(TAG, "静默刷新通讯录失败: ${e.message}")
            }
        }
    }
    
    /**
     * 设置分组标题的点击事件和展开/收起状态
     */
    private fun setupGroupHeaders() {
        // 加载保存的展开/收起状态
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        isAssistantExpanded = prefs.getBoolean(KEY_ASSISTANT_EXPANDED, true)
        isGroupExpanded = prefs.getBoolean(KEY_GROUP_EXPANDED, true)
        isFriendExpanded = prefs.getBoolean(KEY_FRIEND_EXPANDED, true)
        
        // 助手分组
        binding.llAssistantHeader.setOnClickListener {
            toggleAssistantGroup()
        }
        updateAssistantGroupUI()
        
        // 群组分组
        binding.llGroupHeader.setOnClickListener {
            toggleGroupGroup()
        }
        updateGroupGroupUI()
        
        // 好友分组
        binding.llFriendHeader.setOnClickListener {
            toggleFriendGroup()
        }
        updateFriendGroupUI()
    }
    
    /**
     * 切换助手分组展开/收起状态
     */
    private fun toggleAssistantGroup() {
        isAssistantExpanded = !isAssistantExpanded
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ASSISTANT_EXPANDED, isAssistantExpanded).apply()
        updateAssistantGroupUI()
    }
    
    /**
     * 更新助手分组UI
     */
    private fun updateAssistantGroupUI() {
        binding.llAssistantContent.visibility = if (isAssistantExpanded) View.VISIBLE else View.GONE
        binding.ivAssistantArrow.setImageResource(
            if (isAssistantExpanded) R.drawable.ic_arrow_drop_down else R.drawable.ic_arrow_drop_up
        )
    }
    
    /**
     * 切换群组分组展开/收起状态
     */
    private fun toggleGroupGroup() {
        isGroupExpanded = !isGroupExpanded
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_GROUP_EXPANDED, isGroupExpanded).apply()
        updateGroupGroupUI()
    }
    
    /**
     * 更新群组分组UI
     */
    private fun updateGroupGroupUI() {
        binding.llGroupContent.visibility = if (isGroupExpanded) View.VISIBLE else View.GONE
        binding.ivGroupArrow.setImageResource(
            if (isGroupExpanded) R.drawable.ic_arrow_drop_down else R.drawable.ic_arrow_drop_up
        )
    }
    
    /**
     * 切换好友分组展开/收起状态
     */
    private fun toggleFriendGroup() {
        isFriendExpanded = !isFriendExpanded
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FRIEND_EXPANDED, isFriendExpanded).apply()
        updateFriendGroupUI()
    }
    
    /**
     * 更新好友分组UI（包括在线数显示）
     */
    private fun updateFriendGroupUI() {
        binding.llFriendContent.visibility = if (isFriendExpanded) View.VISIBLE else View.GONE
        binding.ivFriendArrow.setImageResource(
            if (isFriendExpanded) R.drawable.ic_arrow_drop_down else R.drawable.ic_arrow_drop_up
        )
        
        // 更新好友分组标题，显示在线好友数
        updateFriendGroupTitle()
    }
    
    /**
     * 更新好友分组标题，显示在线好友数
     */
    private fun updateFriendGroupTitle() {
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" }
        val totalCount = friends.size
        val onlineCount = friends.count { OnlineStatusManager.isFriendOnline(it.imei) }
        
        binding.tvFriendTitle.text = if (totalCount > 0) {
            "${getString(R.string.friend)} ($onlineCount/$totalCount)"
        } else {
            getString(R.string.friend)
        }
    }
    
    /**
     * 设置好友列表的头像和名字
     */
    private fun setupFriendsList() {
        // 内置三个入口仅隐藏，不改后端能力与数据
        binding.llFriendAssistant.visibility = View.GONE
        binding.llFriendSkillLearning.visibility = View.GONE
        binding.llFriendCustomerService.visibility = View.GONE

        // 设置TopoClaw头像和名字
        binding.ivFriendAssistantAvatar.setImageResource(R.drawable.ic_assistant_avatar)
        binding.tvFriendAssistantName.text = getString(R.string.topoclaw_assistant)
        
        // 设置技能学习小助手头像和名字
        binding.ivFriendSkillLearningAvatar.setImageResource(R.drawable.ic_skill_learning_avatar)
        binding.tvFriendSkillLearningName.text = getString(R.string.skill_learn_assistant)
        
        // 设置人工智能头像和名字
        binding.ivFriendCustomerServiceAvatar.setImageResource(R.drawable.ic_customer_service_avatar)
        binding.tvFriendCustomerServiceName.text = getString(R.string.ai_assistant)
        
        // 搜索按钮
        binding.btnSearch.setOnClickListener {
            // 调用MainActivity的搜索对话框
            (activity as? MainActivity)?.showSearchDialog()
        }
        
        // 添加好友按钮 - 显示菜单（添加好友/创建群组）
        binding.btnAddFriend.setOnClickListener {
            showAddMenu(it)
        }
        
        // 朋友入口点击事件
        binding.llFriendRecord.setOnClickListener {
            // 跳转到好友记录页面
            val friendRecordFragment = FriendRecordFragment()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(0, 0, 0, 0)  // 禁用所有动画
                .replace(R.id.fragmentContainer, friendRecordFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        
        // 好友列表点击事件
        binding.llFriendAssistant.setOnClickListener {
            // 跳转到TopoClaw聊天界面
            val assistantConv = Conversation(
                id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                name = ChatConstants.ASSISTANT_LEGACY_TOPOCLAW_NAME,
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            // 使用复用机制切换到聊天页面
            (activity as? MainActivity)?.switchToChatFragment(assistantConv)
        }
        
        binding.llFriendSkillLearning.setOnClickListener {
            // 跳转到技能学习小助手聊天界面
            val skillConv = Conversation(
                id = ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING,
                name = "技能学习小助手",
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            // 使用复用机制切换到聊天页面
            (activity as? MainActivity)?.switchToChatFragment(skillConv)
        }
        
        binding.llFriendCustomerService.setOnClickListener {
            // 跳转到人工智能聊天界面
            val customerServiceConv = Conversation(
                id = ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE,
                name = getString(R.string.ai_assistant),
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            // 使用复用机制切换到聊天页面
            (activity as? MainActivity)?.switchToChatFragment(customerServiceConv)
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
     * 构建可加入群组的小助手列表
     */
    private fun buildGroupAssistantList(context: Context): List<Pair<String, String>> {
        val list = linkedMapOf<String, String>()
        list[ConversationListFragment.CONVERSATION_ID_GROUP_MANAGER] = "GroupManager"
        if (ConversationListFragment.CONVERSATION_ID_ASSISTANT !in HIDDEN_BUILTIN_ASSISTANT_IDS) {
            list[ConversationListFragment.CONVERSATION_ID_ASSISTANT] = context.getString(R.string.auto_execute_assistant)
        }
        if (ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING !in HIDDEN_BUILTIN_ASSISTANT_IDS) {
            list[ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING] = context.getString(R.string.skill_learn_assistant)
        }
        list[ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT] = context.getString(R.string.chat_assistant)
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
        
        val selectedFriends = mutableSetOf<String>()
        val selectedAssistants = mutableSetOf<String>()
        
        fun updateSelectedCount() {
            var text = getString(R.string.selected_count_people_format, selectedFriends.size)
            if (selectedAssistants.isNotEmpty()) text += "，${selectedAssistants.size} 个小助手"
            tvSelectedCount.text = text
        }
        
        // 构建小助手列表
        buildGroupAssistantList(requireContext()).forEach { (id, name) ->
            val itemView = layoutInflater.inflate(android.R.layout.simple_list_item_multiple_choice, llAssistantsList, false) as android.widget.CheckedTextView
            itemView.text = name
            itemView.textSize = 16f
            itemView.setPadding((16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (16 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt())
            itemView.setOnClickListener {
                if (id == ConversationListFragment.CONVERSATION_ID_GROUP_MANAGER) return@setOnClickListener
                itemView.isChecked = !itemView.isChecked
                if (itemView.isChecked) selectedAssistants.add(id) else selectedAssistants.remove(id)
                updateSelectedCount()
            }
            if (id == ConversationListFragment.CONVERSATION_ID_GROUP_MANAGER) {
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
                    tvSelectedCount.text = getString(R.string.selected_count_people_format, selectedFriends.size)
                }
                
                llFriendsList.addView(friendItemView)
            }
        }
        
        // 更新选中数量显示
        tvSelectedCount.text = getString(R.string.selected_count_people_format, selectedFriends.size)
        
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
            createGroup(groupName, selectedFriends.toList(), selectedAssistants.contains(ConversationListFragment.CONVERSATION_ID_ASSISTANT))
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
                            assistants = groupInfo.assistants?.takeIf { it.isNotEmpty() }
                                ?: if (groupInfo.assistant_enabled) listOf(ConversationListFragment.CONVERSATION_ID_ASSISTANT) else emptyList()
                        )
                        GroupManager.addGroup(requireContext(), group)
                        
                        // 刷新群组列表显示
                        loadAndDisplayGroups()
                        
                        // 刷新对话列表
                        try {
                            val fragmentManager = parentFragmentManager
                            val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
                            conversationListFragment?.loadConversations()
                        } catch (e: Exception) {
                            Log.w(TAG, "刷新对话列表失败: ${e.message}")
                        }
                        
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
            
                        // 可选：自动跳转到新创建的群组聊天界面
                        // val groupConv = Conversation(
                        //     id = "group_$groupId",
                        //     name = groupName,
                        //     avatar = null,
                        //     lastMessage = null,
                        //     lastMessageTime = System.currentTimeMillis()
                        // )
                        // (activity as? MainActivity)?.switchToChatFragment(groupConv)
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
                        recordFriendsSyncTime()
                    } catch (e: Exception) {
                        Log.w(TAG, "同步好友列表失败: ${e.message}")
                    }
                    
                    // 刷新好友列表显示
                    loadAndDisplayFriends()
                    // 更新好友分组标题（在线数）
                    updateFriendGroupTitle()
                    
                    // 刷新对话列表
                    try {
                        val fragmentManager = parentFragmentManager
                        val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
                        conversationListFragment?.loadConversations()
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新对话列表失败: ${e.message}")
                    }
                    
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
    
    /**
     * 检查是否需要同步好友列表
     */
    private fun shouldSyncFriends(): Boolean {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val lastSyncTime = prefs.getLong(KEY_LAST_FRIENDS_SYNC, 0L)
        return System.currentTimeMillis() - lastSyncTime > SYNC_INTERVAL_MS
    }
    
    /**
     * 记录好友列表同步时间
     */
    private fun recordFriendsSyncTime() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_FRIENDS_SYNC, System.currentTimeMillis()).apply()
    }
    
    /**
     * 加载并显示好友列表
     * 先立即渲染本地缓存数据，再按需后台同步服务器数据并刷新 UI。
     */
    private fun loadAndDisplayFriends() {
        mainScope.launch {
            try {
                renderFriendsList()

                if (shouldSyncFriends()) {
                    launch(Dispatchers.IO) {
                        try {
                            val ctx = requireContext()
                            val prefs = ctx.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                            CustomerServiceNetwork.initialize(customerServiceUrl)
                            FriendManager.syncFriendsFromServer(ctx)
                            recordFriendsSyncTime()
                            withContext(Dispatchers.Main) {
                                if (isAdded && _binding != null) {
                                    renderFriendsList()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "后台同步好友列表失败: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载好友列表失败: ${e.message}", e)
            }
        }
    }

    private fun renderFriendsList() {
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" }
            .sortedWith { a, b ->
                val aOnline = OnlineStatusManager.isFriendOnline(a.imei)
                val bOnline = OnlineStatusManager.isFriendOnline(b.imei)
                when {
                    aOnline && !bOnline -> -1
                    !aOnline && bOnline -> 1
                    else -> b.addedAt.compareTo(a.addedAt)
                }
            }

        val fingerprint = friends.joinToString(",") { "${it.imei}:${it.nickname}:${it.status}:${it.avatar?.hashCode()}" }
        if (fingerprint == lastFriendsFingerprint && binding.llFriendsList.childCount > 0) {
            updateFriendGroupTitle()
            return
        }
        lastFriendsFingerprint = fingerprint

        binding.llFriendsList.removeAllViews()

        if (friends.isEmpty()) {
            val emptyView = android.widget.TextView(requireContext()).apply {
                text = "暂无好友"
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 32)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            binding.llFriendsList.addView(emptyView)
        } else {
            friends.forEach { friend ->
                val friendItemView = createFriendItemView(friend)
                binding.llFriendsList.addView(friendItemView)
            }
        }

        updateFriendGroupTitle()
    }
    
    /**
     * 检查是否需要同步群组列表
     */
    private fun shouldSyncGroups(): Boolean {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val lastSyncTime = prefs.getLong(KEY_LAST_GROUPS_SYNC, 0L)
        return System.currentTimeMillis() - lastSyncTime > SYNC_INTERVAL_MS
    }
    
    /**
     * 记录群组列表同步时间
     */
    private fun recordGroupsSyncTime() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_GROUPS_SYNC, System.currentTimeMillis()).apply()
    }
    
    /**
     * 加载并显示群组列表
     * 先立即渲染本地缓存数据，再按需后台同步服务器数据并刷新 UI。
     * @param forceSync 是否强制同步（忽略时间间隔限制）
     */
    private fun loadAndDisplayGroups(forceSync: Boolean = false) {
        mainScope.launch {
            try {
                renderGroupsList()

                if (forceSync || shouldSyncGroups()) {
                    launch(Dispatchers.IO) {
                        try {
                            val ctx = requireContext()
                            val prefs = ctx.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                            CustomerServiceNetwork.initialize(customerServiceUrl)
                            GroupManager.syncGroupsFromServer(ctx)
                            recordGroupsSyncTime()
                            withContext(Dispatchers.Main) {
                                if (isAdded && _binding != null) {
                                    renderGroupsList()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "后台同步群组列表失败: ${e.message}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载群组列表失败: ${e.message}", e)
            }
        }
    }

    private fun renderGroupsList() {
        val groups = GroupManager.getGroups(requireContext())
        val fingerprint = groups.joinToString(",") { "${it.groupId}:${it.name}:${it.members.size}:${it.assistants.hashCode()}" }
        if (fingerprint == lastGroupsFingerprint && binding.llGroupsList.childCount > 0) {
            return
        }
        lastGroupsFingerprint = fingerprint

        binding.llGroupsList.removeAllViews()
        if (groups.isNotEmpty()) {
            groups.forEach { group ->
                val groupItemView = createGroupItemView(group)
                binding.llGroupsList.addView(groupItemView)
            }
        }
    }
    
    /**
     * 加载并显示自定义小助手列表（通讯录-助手分组）
     */
    private fun loadAndDisplayCustomAssistants() {
        if (!isAdded || _binding == null) return
        binding.llCustomAssistantsList.removeAllViews()
        val customAssistants = CustomAssistantManager.getVisibleAll(requireContext())
        customAssistants.forEach { assistant ->
            val itemView = layoutInflater.inflate(R.layout.item_contact_assistant, binding.llCustomAssistantsList, false)
            val avatarView = itemView.findViewById<android.widget.ImageView>(R.id.ivAssistantAvatar)
            val nameView = itemView.findViewById<TextView>(R.id.tvAssistantName)
            nameView.text = assistant.name
            AvatarCacheManager.loadCustomAssistantAvatar(
                context = requireContext(),
                imageView = avatarView,
                assistant = assistant,
                cacheKey = "contact_${assistant.id}",
                validationTag = assistant.id
            )
            itemView.setOnClickListener {
                val conv = Conversation(
                    id = assistant.id,
                    name = assistant.name,
                    avatar = assistant.avatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                (activity as? MainActivity)?.switchToChatFragment(conv)
            }
            binding.llCustomAssistantsList.addView(itemView)
        }
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
                loadAndDisplayCustomAssistants()
                LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent(ConversationListFragment.ACTION_CUSTOM_ASSISTANT_ADDED))
                Toast.makeText(requireContext(), getString(R.string.add_assistant_success), Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(requireContext(), getString(R.string.add_assistant_invalid_link), Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }
    
    /**
     * 刷新群组列表（外部调用，强制同步）
     * 用于收到群组变更通知时立即刷新
     */
    fun refreshGroupsList() {
        if (!isAdded || context == null) {
            Log.w(TAG, "Fragment未附加，无法刷新群组列表")
            return
        }
        Log.d(TAG, "收到刷新群组列表请求，强制同步")
        loadAndDisplayGroups(forceSync = true)
    }
    
    /**
     * 创建群组项视图
     */
    private fun createGroupItemView(group: GroupManager.Group): View {
        val inflater = LayoutInflater.from(requireContext())
        val itemView = inflater.inflate(R.layout.item_friend_record, binding.llGroupsList, false)
        
        // 头像
        val avatarView = itemView.findViewById<android.widget.ImageView>(R.id.ivFriendAvatar)
        // 先设置默认头像
        avatarView.setImageResource(R.drawable.ic_person)
        
        // 设置圆形头像
        avatarView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatarView.clipToOutline = true
        
        // 加载群组头像：先检查缓存，如果有缓存直接设置，避免闪烁
        val avatarSize = (48 * resources.displayMetrics.density).toInt()
        val cachedAvatar = GroupAvatarHelper.getCachedGroupAvatarFromMembers(
            group.members,
            avatarSize,
            group.assistants
        )
        
        if (cachedAvatar != null) {
            // 缓存命中，直接设置，无闪烁
            avatarView.setImageBitmap(cachedAvatar)
        } else {
            // 缓存未命中，先设置默认头像，然后异步加载
            avatarView.setImageResource(R.drawable.ic_system_avatar)
            mainScope.launch {
                try {
                    val groupAvatar = withContext(Dispatchers.IO) {
                        // 根据群组成员生成群组头像（最多显示4个成员）
                        GroupAvatarHelper.createGroupAvatarFromMembers(
                            requireContext(),
                            group.members,
                            avatarSize,
                            group.assistants
                        )
                    }
                    // 切换到主线程更新UI
                    if (_binding != null && groupAvatar != null) {
                        avatarView.setImageBitmap(groupAvatar)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载群组头像失败: ${e.message}", e)
                    // 保持默认头像
                }
            }
        }
        
        // 名字
        val nameView = itemView.findViewById<TextView>(R.id.tvFriendName)
        nameView.text = group.name
        nameView.maxLines = 2
        nameView.ellipsize = android.text.TextUtils.TruncateAt.END
        
        // IMEI - 隐藏
        val imeiView = itemView.findViewById<TextView>(R.id.tvFriendImei)
        imeiView.visibility = View.GONE
        
        // 时间 - 隐藏
        val timeView = itemView.findViewById<TextView>(R.id.tvFriendTime)
        timeView.visibility = View.GONE
        
        // 点击事件：跳转到群组聊天界面
        itemView.setOnClickListener {
            val groupConv = Conversation(
                id = "group_${group.groupId}",
                name = group.name,
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            (activity as? MainActivity)?.switchToChatFragment(groupConv)
        }
        
        return itemView
    }
    
    /**
     * 创建好友项视图
     */
    private fun createFriendItemView(friend: Friend): View {
        val inflater = LayoutInflater.from(requireContext())
        val itemView = inflater.inflate(R.layout.item_friend_record, binding.llFriendsList, false)
        
        // 头像
        val avatarView = itemView.findViewById<android.widget.ImageView>(R.id.ivFriendAvatar)
        
        // 设置圆形头像
        avatarView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatarView.clipToOutline = true
        
        // 使用AvatarCacheManager加载好友头像，优先使用缓存避免闪烁
        val cacheKey = "friend_${friend.imei}"
        // 使用friend.imei作为validationTag，确保View被回收后不会更新错误的ImageView
        AvatarCacheManager.loadBase64Avatar(
            context = requireContext(),
            imageView = avatarView,
            base64String = friend.avatar,
            defaultResId = R.drawable.ic_person,
            cacheKey = cacheKey,
            validationTag = friend.imei
        )
        
        // 名字 - 显示完整IMEI（如果没有昵称）
        val nameView = itemView.findViewById<TextView>(R.id.tvFriendName)
        nameView.text = friend.nickname ?: friend.imei
        // 设置TextView属性，允许自动换行或省略（如果达到屏幕边缘）
        nameView.maxLines = 2
        nameView.ellipsize = android.text.TextUtils.TruncateAt.END
        
        // 在线状态图标
        val onlineStatusView = itemView.findViewById<android.widget.ImageView>(R.id.ivOnlineStatus)
        // 根据在线状态显示/隐藏灯泡图标
        onlineStatusView.visibility = if (OnlineStatusManager.isFriendOnline(friend.imei)) View.VISIBLE else View.GONE
        // 设置tag以便后续更新时能找到对应的好友
        itemView.tag = friend.imei
        
        // IMEI - 隐藏
        val imeiView = itemView.findViewById<TextView>(R.id.tvFriendImei)
        imeiView.visibility = View.GONE
        
        // 时间 - 隐藏
        val timeView = itemView.findViewById<TextView>(R.id.tvFriendTime)
        timeView.visibility = View.GONE
        
        // 点击事件：跳转到好友聊天界面
        itemView.setOnClickListener {
            val friendConv = Conversation(
                id = "friend_${friend.imei}",
                name = friend.nickname ?: friend.imei,
                avatar = friend.avatar,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            (activity as? MainActivity)?.switchToChatFragment(friendConv)
        }
        
        return itemView
    }
    
    /**
     * 更新所有好友项的在线状态图标
     */
    private fun updateOnlineStatusIcons() {
        if (!isAdded || _binding == null) return
        
        // 遍历LinearLayout中的所有子视图，更新在线状态图标
        for (i in 0 until binding.llFriendsList.childCount) {
            val itemView = binding.llFriendsList.getChildAt(i)
            val friendImei = itemView.tag as? String
            
            if (friendImei != null) {
                val onlineStatusView = itemView.findViewById<android.widget.ImageView>(R.id.ivOnlineStatus)
                if (onlineStatusView != null) {
                    // 根据在线状态显示/隐藏灯泡图标
                    onlineStatusView.visibility = if (OnlineStatusManager.isFriendOnline(friendImei)) View.VISIBLE else View.GONE
                }
            }
        }
    }
    
    /**
     * 更新"新的朋友"入口的红点数字
     */
    fun updateBadge() {
        if (_binding == null || !isAdded) return
        
        val unreadCount = FriendRequestManager.getUnreadCount(requireContext())
        
        // 查找或创建红点TextView
        val badgeTag = "friend_request_badge"
        var badgeView = binding.llFriendRecord.findViewWithTag<android.widget.TextView>(badgeTag)
        
        if (unreadCount > 0) {
            if (badgeView == null) {
                // 创建红点TextView
                badgeView = android.widget.TextView(requireContext()).apply {
                    tag = badgeTag
                    textSize = 10f
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    setPadding(
                        (4 * resources.displayMetrics.density).toInt(),
                        (2 * resources.displayMetrics.density).toInt(),
                        (4 * resources.displayMetrics.density).toInt(),
                        (2 * resources.displayMetrics.density).toInt()
                    )
                    minWidth = (16 * resources.displayMetrics.density).toInt()
                    minHeight = (16 * resources.displayMetrics.density).toInt()
                    
                    // 创建红色圆形背景
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xFFFF4444.toInt())
                    }
                    background = drawable
                }
                
                // 添加到"新的朋友"TextView旁边
                val layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                binding.llFriendRecord.addView(badgeView, 1, layoutParams)
            }
            
            badgeView.text = if (unreadCount > 99) "99+" else unreadCount.toString()
            badgeView.visibility = View.VISIBLE
        } else {
            badgeView?.visibility = View.GONE
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        isGroupsListInitialized = false
        lastFriendsFingerprint = ""
        lastGroupsFingerprint = ""
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(customAssistantAddedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "取消注册添加小助手广播失败: ${e.message}")
        }
        OnlineStatusManager.removeListener(onlineStatusListener)
        _binding = null
        mainScope.cancel()
    }
}

