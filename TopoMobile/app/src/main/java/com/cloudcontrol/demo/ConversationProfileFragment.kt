package com.cloudcontrol.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.cloudcontrol.demo.databinding.FragmentConversationProfileBinding
import kotlinx.coroutines.*
import com.cloudcontrol.demo.CustomerServiceNetwork
import com.cloudcontrol.demo.RemoveFriendRequest
import com.cloudcontrol.demo.AddGroupMemberRequest
import com.cloudcontrol.demo.SetGroupAssistantRequest
import com.cloudcontrol.demo.AddGroupAssistantRequest
import com.cloudcontrol.demo.RemoveGroupAssistantRequest
import com.cloudcontrol.demo.AddFriendRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天对象主页Fragment
 * 显示聊天对象的头像、名字和简介
 */
class ConversationProfileFragment : Fragment() {
    
    companion object {
        private const val TAG = "ConversationProfileFragment"
        private const val ARG_CONVERSATION = "conversation"
        private const val REQUEST_CODE_CHAT_BACKGROUND_GALLERY = 1007  // 从相册选择背景
        private const val REQUEST_CODE_CHAT_BACKGROUND_CAMERA = 1008  // 拍照设置背景
        private const val BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID = "custom_topoclaw"
        
        fun newInstance(conversation: Conversation): ConversationProfileFragment {
            return ConversationProfileFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_CONVERSATION, conversation)
                }
            }
        }
    }
    
    private var _binding: FragmentConversationProfileBinding? = null
    private val binding get() = _binding!!
    
    private var conversation: Conversation? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var onBackPressedCallback: OnBackPressedCallback? = null
    private var cameraImageUri: Uri? = null  // 拍照时保存的图片URI
    
    // 在线状态监听器
    private val onlineStatusListener = object : OnlineStatusManager.OnlineStatusListener {
        override fun onOnlineStatusChanged(onlineFriends: Set<String>) {
            if (isAdded && _binding != null) {
                updateOnlineStatus()
            }
        }
    }
    
    private fun updateOnlineStatus() {
        val conv = conversation ?: return
        if (conv.id.startsWith("friend_")) {
            val friendImei = conv.id.removePrefix("friend_")
            binding.ivOnlineStatus.visibility = if (OnlineStatusManager.isFriendOnline(friendImei)) View.VISIBLE else View.GONE
        } else {
            binding.ivOnlineStatus.visibility = View.GONE
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationProfileBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        conversation = arguments?.getSerializable(ARG_CONVERSATION) as? Conversation
        
        // 注册系统返回键回调，使其与返回按钮行为一致
        onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBackToConversationList()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback!!)
        
        setupUI()
        loadProfile()
        
        // 注册在线状态监听器并启动检查
        OnlineStatusManager.addListener(onlineStatusListener)
        OnlineStatusManager.startChecking(requireContext())
    }
    
    private fun setupUI() {
        // 返回按钮
        // 从助手主页返回时，直接返回到聊天主页面（ConversationListFragment），而不是返回到之前的ChatFragment
        binding.btnBack.setOnClickListener {
            navigateBackToConversationList()
        }
        
        // 设置按钮（对两个小助手、人工客服、自定义小助手和群组显示）
        val conversationId = conversation?.id
        val isGroup = conversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                     (conversationId?.startsWith("group_") == true)
        val isCustomAssistant = CustomAssistantManager.isCustomAssistantId(conversationId)
        val isBuiltinDefaultCustomAssistant = conversationId == BUILTIN_DEFAULT_CUSTOM_ASSISTANT_ID
        val isAssistant = conversationId == ConversationListFragment.CONVERSATION_ID_ASSISTANT ||
                conversationId == ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING ||
                conversationId == ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT ||
                conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE ||
                isCustomAssistant ||
                isGroup
        
        // 内置默认自定义小助手主页不展示右上角设置按钮
        if ((isAssistant || isGroup) && !isBuiltinDefaultCustomAssistant) {
            binding.btnSettings.visibility = View.VISIBLE
            binding.btnSettings.setOnClickListener {
                conversation?.let { conv ->
                    val assistantName = when {
                        conv.id == ConversationListFragment.CONVERSATION_ID_ASSISTANT -> getString(R.string.auto_execute_assistant)
                        conv.id == ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> getString(R.string.skill_learn_assistant)
                        conv.id == ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> getString(R.string.chat_assistant)
                        conv.id == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> getString(R.string.customer_service)
                        conv.id == ConversationListFragment.CONVERSATION_ID_GROUP -> getString(R.string.friend_group)
                        CustomAssistantManager.isCustomAssistantId(conv.id) -> CustomAssistantManager.getById(requireContext(), conv.id)?.name ?: conv.name
                        conv.id.startsWith("group_") -> {
                            val groupId = conv.id.removePrefix("group_")
                            val group = GroupManager.getGroup(requireContext(), groupId)
                            group?.name ?: getString(R.string.groups)
                        }
                        else -> getString(R.string.assistant_short)
                    }
                    val settingsFragment = ServerSettingsFragment.newInstance(conv.id, assistantName)
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(
                            R.anim.slide_in_from_right,  // enter: 新Fragment从右侧滑入
                            R.anim.slide_out_to_left,    // exit: 旧Fragment向左滑出
                            R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                            R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
                        )
                        .hide(this@ConversationProfileFragment)  // 隐藏当前ConversationProfileFragment，而不是replace
                        .add(R.id.fragmentContainer, settingsFragment)  // 添加新的Fragment
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            }
        } else {
            binding.btnSettings.visibility = View.GONE
        }
        
        // 发消息按钮
        binding.btnSendMessage.setOnClickListener {
            conversation?.let { conv ->
                // 跳转到聊天界面
            // 使用复用机制切换到聊天页面
            (activity as? MainActivity)?.switchToChatFragment(conv)
            }
        }
        
        // 查看历史记录按钮（仅对支持上下文记录的聊天对象显示）
        // 内置默认自定义小助手主页不展示「查看历史记录」
        if ((isAssistant || isGroup) && !isBuiltinDefaultCustomAssistant) {
            binding.btnViewHistory.visibility = View.VISIBLE
            binding.btnViewHistory.setOnClickListener {
                conversation?.let { conv ->
                    val assistantName = when {
                        conv.id == ConversationListFragment.CONVERSATION_ID_ASSISTANT -> getString(R.string.auto_execute_assistant)
                        conv.id == ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> getString(R.string.skill_learn_assistant)
                        conv.id == ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> getString(R.string.chat_assistant)
                        conv.id == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> getString(R.string.customer_service)
                        conv.id == ConversationListFragment.CONVERSATION_ID_GROUP -> getString(R.string.friend_group)
                        CustomAssistantManager.isCustomAssistantId(conv.id) -> CustomAssistantManager.getById(requireContext(), conv.id)?.name ?: conv.name
                        conv.id.startsWith("group_") -> {
                            val groupId = conv.id.removePrefix("group_")
                            val group = GroupManager.getGroup(requireContext(), groupId)
                            group?.name ?: getString(R.string.groups)
                        }
                        else -> getString(R.string.assistant_short)
                    }
                    val historyFragment = ChatContextHistoryFragment.newInstance(conv.id, assistantName)
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(0, 0, 0, 0)
                        .replace(R.id.fragmentContainer, historyFragment)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            }
        } else {
            binding.btnViewHistory.visibility = View.GONE
        }

        // 内置默认自定义小助手主页：显示模型切换入口（走中转到 TopoClaw）
        if (isBuiltinDefaultCustomAssistant) {
            binding.btnSwitchTopoclawModel.visibility = View.VISIBLE
            binding.btnSwitchTopoclawModel.setOnClickListener {
                requestAndShowTopoclawModelSwitchDialog()
            }
        } else {
            binding.btnSwitchTopoclawModel.visibility = View.GONE
        }
        
        // 设置聊天背景按钮（仅对小助手显示）
        if (isAssistant) {
            binding.btnChatBackground.visibility = View.VISIBLE
            binding.btnChatBackground.setOnClickListener {
                showChatBackgroundDialog()
            }
        } else {
            binding.btnChatBackground.visibility = View.GONE
        }
        
        // 删除好友按钮（仅对好友显示）
        val isFriend = conversationId?.startsWith("friend_") == true
        val isNonFriend = conversationId?.startsWith("non_friend_") == true
        if (isFriend) {
            binding.btnDeleteFriend.visibility = View.VISIBLE
            binding.btnDeleteFriend.setOnClickListener {
                showDeleteFriendDialog()
            }
            binding.btnAddFriend.visibility = View.GONE
            binding.btnDeleteAssistant.visibility = View.GONE
        } else if (isNonFriend) {
            // 非好友：显示添加好友按钮
            binding.btnAddFriend.visibility = View.VISIBLE
            binding.btnAddFriend.setOnClickListener {
                val friendImei = conversationId?.removePrefix("non_friend_") ?: return@setOnClickListener
                addFriend(friendImei)
            }
            binding.btnDeleteFriend.visibility = View.GONE
            binding.btnDeleteAssistant.visibility = View.GONE
        } else if (isCustomAssistant) {
            // 自定义小助手：显示删除小助手按钮
            binding.btnDeleteAssistant.visibility = View.VISIBLE
            binding.btnDeleteAssistant.setOnClickListener {
                showDeleteAssistantDialog()
            }
            binding.btnDeleteFriend.visibility = View.GONE
            binding.btnAddFriend.visibility = View.GONE
        } else {
            binding.btnDeleteFriend.visibility = View.GONE
            binding.btnAddFriend.visibility = View.GONE
            binding.btnDeleteAssistant.visibility = View.GONE
        }
        
        // 群成员点击事件现在在addMemberItem方法中处理
    }

    private data class TopoclawModelProfiles(
        val chatModels: List<String>,
        val guiModels: List<String>,
        val activeChatModel: String,
        val activeGuiModel: String
    )

    private fun requestAndShowTopoclawModelSwitchDialog() {
        mainScope.launch {
            try {
                val ws = (activity as? MainActivity)?.getCustomerServiceWebSocket()
                if (ws == null || !ws.isConnected()) {
                    Toast.makeText(requireContext(), "连接未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val response = withContext(Dispatchers.IO) { ws.fetchTopoclawModelProfiles() }
                val profiles = parseTopoclawModelProfiles(response)
                if (profiles.chatModels.isEmpty() || profiles.guiModels.isEmpty()) {
                    Toast.makeText(requireContext(), "未获取到模型列表", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                showTopoclawModelSwitchDialog(profiles)
            } catch (e: Exception) {
                Log.w(TAG, "获取模型列表失败: ${e.message}")
                Toast.makeText(requireContext(), "获取模型列表失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun parseTopoclawModelProfiles(json: org.json.JSONObject): TopoclawModelProfiles {
        val chatModels = mutableListOf<String>()
        val guiModels = mutableListOf<String>()

        json.optJSONArray("non_gui_profiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val model = arr.optString(i, "").trim()
                if (model.isNotEmpty() && model !in chatModels) chatModels.add(model)
            }
        }
        json.optJSONArray("gui_profiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val model = arr.optString(i, "").trim()
                if (model.isNotEmpty() && model !in guiModels) guiModels.add(model)
            }
        }

        return TopoclawModelProfiles(
            chatModels = chatModels,
            guiModels = guiModels,
            activeChatModel = json.optString("active_non_gui_model", "").trim(),
            activeGuiModel = json.optString("active_gui_model", "").trim()
        )
    }

    private fun showTopoclawModelSwitchDialog(profiles: TopoclawModelProfiles) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val chatLabel = TextView(requireContext()).apply { text = "Chat 模型" }
        val chatSpinner = Spinner(requireContext())
        val guiLabel = TextView(requireContext()).apply { text = "GUI 模型" }
        val guiSpinner = Spinner(requireContext())

        chatSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            profiles.chatModels
        )
        guiSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            profiles.guiModels
        )

        val chatIdx = profiles.chatModels.indexOf(profiles.activeChatModel).takeIf { it >= 0 } ?: 0
        val guiIdx = profiles.guiModels.indexOf(profiles.activeGuiModel).takeIf { it >= 0 } ?: 0
        chatSpinner.setSelection(chatIdx)
        guiSpinner.setSelection(guiIdx)

        content.addView(chatLabel)
        content.addView(chatSpinner)
        content.addView(guiLabel)
        content.addView(guiSpinner)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("切换模型")
            .setView(content)
            .setPositiveButton("应用") { _, _ ->
                val selectedChat = profiles.chatModels.getOrNull(chatSpinner.selectedItemPosition).orEmpty()
                val selectedGui = profiles.guiModels.getOrNull(guiSpinner.selectedItemPosition).orEmpty()
                if (selectedChat.isBlank() || selectedGui.isBlank()) {
                    Toast.makeText(requireContext(), "请选择模型", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ws = (activity as? MainActivity)?.getCustomerServiceWebSocket()
                if (ws == null || !ws.isConnected()) {
                    Toast.makeText(requireContext(), "连接未就绪，请稍后再试", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val chatOk = ws.sendTopoclawModelSwitchRequest(providerType = "chat", model = selectedChat)
                val guiOk = ws.sendTopoclawModelSwitchRequest(providerType = "gui", model = selectedGui)
                if (chatOk && guiOk) {
                    Toast.makeText(requireContext(), "模型切换请求已发送", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "发送失败，请检查连接状态", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun loadProfile() {
        conversation?.let { conv ->
            // 设置名字
            binding.tvName.text = DisplayNameHelper.getDisplayName(requireContext(), conv.name)
            
            // 设置头像
            when (conv.id) {
                ConversationListFragment.CONVERSATION_ID_ASSISTANT -> {
                    binding.ivAvatar.setImageResource(R.drawable.ic_assistant_avatar)
                    binding.llGroupMembers.visibility = View.GONE
                    binding.llFriendInfo.visibility = View.GONE
                }
                ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> {
                    binding.ivAvatar.setImageResource(R.drawable.ic_skill_learning_avatar)
                    binding.llGroupMembers.visibility = View.GONE
                    binding.llFriendInfo.visibility = View.GONE
                }
                ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> {
                    binding.ivAvatar.setImageResource(R.drawable.ic_customer_service_avatar)
                    binding.llGroupMembers.visibility = View.GONE
                    binding.llFriendInfo.visibility = View.GONE
                }
                ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> {
                    binding.ivAvatar.setImageResource(R.drawable.ic_chat_assistant_avatar)
                    binding.llGroupMembers.visibility = View.GONE
                    binding.llFriendInfo.visibility = View.GONE
                }
                ConversationListFragment.CONVERSATION_ID_GROUP_MANAGER -> {
                    binding.ivAvatar.setImageResource(R.drawable.ic_groupmanager_avatar)
                    binding.llGroupMembers.visibility = View.GONE
                    binding.llFriendInfo.visibility = View.GONE
                }
                ConversationListFragment.CONVERSATION_ID_GROUP -> {
                    // 固定的"好友群"
                    binding.tvName.text = getString(R.string.friend_group)
                    
                    // 群头像：先显示默认头像，异步加载拼接头像
                    binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                    
                    // 显示群成员列表
                    binding.llGroupMembers.visibility = View.VISIBLE
                    binding.llFriendInfo.visibility = View.GONE
                    setupFixedFriendsGroupMembers()
                    
                    // 异步加载群头像，避免阻塞UI
                    mainScope.launch {
                        val avatarSize = (120 * resources.displayMetrics.density).toInt()
                        val groupAvatar = withContext(Dispatchers.IO) {
                            GroupAvatarHelper.createFriendsGroupAvatar(requireContext(), avatarSize)
                        }
                        // 切换到主线程更新UI
                        if (_binding != null && groupAvatar != null) {
                            binding.ivAvatar.setImageBitmap(groupAvatar)
                        }
                    }
                }
                else -> {
                    // 动态群组（group_xxx）
                    if (conv.id.startsWith("group_")) {
                        val groupId = conv.id.removePrefix("group_")
                        val group = GroupManager.getGroup(requireContext(), groupId)
                        
                        Log.d(TAG, "加载群组信息: groupId=$groupId, group=${group?.name}, members=${group?.members?.size}")
                        
                        if (group != null) {
                            binding.tvName.text = group.name
                            
                            // 群头像：先显示默认头像，异步加载拼接头像
                            binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                            
                            // 保存当前群组
                            currentGroup = group
                            
                            // 显示群成员列表
                            binding.llGroupMembers.visibility = View.VISIBLE
                            binding.llFriendInfo.visibility = View.GONE
                            Log.d(TAG, "设置群成员列表可见性: VISIBLE, llGroupMembers存在=${binding.llGroupMembers != null}, llMembersList存在=${binding.llMembersList != null}")
                            setupGroupMembers(group)
                            Log.d(TAG, "setupGroupMembers完成后，成员列表子视图数: ${binding.llMembersList.childCount}, llGroupMembers可见性: ${binding.llGroupMembers.visibility}, llGroupMembers高度: ${binding.llGroupMembers.height}, llMembersList高度: ${binding.llMembersList.height}")
                            
                            // 确保成员列表可见
                            binding.llGroupMembers.post {
                                Log.d(TAG, "post后，llGroupMembers可见性: ${binding.llGroupMembers.visibility}, 高度: ${binding.llGroupMembers.height}, 子视图数: ${binding.llMembersList.childCount}")
                            }
                            
                            // 显示管理按钮和邀请成员按钮（只有群主可以管理）
                            val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                            if (group.creatorImei == currentImei) {
                                binding.btnManageMembers.visibility = View.VISIBLE
                                binding.btnManageMembers.setOnClickListener {
                                    toggleManageMode(group)
                                }
                                
                                // 显示邀请成员按钮（加号图标）
                                binding.btnInviteMember.visibility = View.VISIBLE
                                binding.btnInviteMember.setOnClickListener {
                                    showInviteMemberDialog(group)
                                }
                            }
                            
                            // 异步加载群头像，避免阻塞UI
                            mainScope.launch {
                                val avatarSize = (120 * resources.displayMetrics.density).toInt()
                                val groupAvatar = withContext(Dispatchers.IO) {
                                    GroupAvatarHelper.createGroupAvatarFromMembers(
                                        requireContext(),
                                        group.members,
                                        avatarSize,
                                        group.assistants
                                    )
                                }
                                // 切换到主线程更新UI
                                if (_binding != null && groupAvatar != null) {
                                    binding.ivAvatar.setImageBitmap(groupAvatar)
                                }
                            }
                        } else {
                            binding.tvName.text = DisplayNameHelper.getDisplayName(requireContext(), conv.name)
                            binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                            binding.llGroupMembers.visibility = View.GONE
                            binding.llFriendInfo.visibility = View.GONE
                        }
                    }
                    // 好友对话：加载好友头像和姓名
                    else if (conv.id.startsWith("friend_")) {
                        val friendImei = conv.id.removePrefix("friend_")
                        val friend = FriendManager.getFriend(requireContext(), friendImei)
                        
                        // 设置名字
                        binding.tvName.text = friend?.nickname ?: friendImei
                        
                        // 设置在线状态图标
                        binding.ivOnlineStatus.visibility = if (OnlineStatusManager.isFriendOnline(friendImei)) View.VISIBLE else View.GONE
                        
                        // 使用AvatarCacheManager加载好友头像，优先使用缓存避免闪烁
                        val cacheKey = "friend_${friendImei}"
                        AvatarCacheManager.loadBase64Avatar(
                            context = requireContext(),
                            imageView = binding.ivAvatar,
                            base64String = friend?.avatar,
                            defaultResId = R.drawable.ic_person,
                            cacheKey = cacheKey,
                            validationTag = friendImei
                        )
                        
                        // 显示好友详细信息
                        binding.llFriendInfo.visibility = View.VISIBLE
                        
                        // 设置IMEI
                        binding.tvFriendImei.text = friendImei
                        
                        // 设置IMEI长按复制功能
                        binding.tvFriendImei.setOnLongClickListener {
                            copyToClipboard(friendImei, "IMEI")
                            Toast.makeText(requireContext(), "IMEI已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            true
                        }
                        
                        // 初始状态下隐藏所有字段，等待从服务器获取数据
                        hideAllFriendInfoFields()
                        
                        // 从服务器获取用户资料并显示
                        mainScope.launch {
                            try {
                                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                                
                                CustomerServiceNetwork.initialize(customerServiceUrl)
                                val apiService = CustomerServiceNetwork.getApiService()
                                
                                if (apiService != null) {
                                    val response = withContext(Dispatchers.IO) {
                                        apiService.getProfile(friendImei)
                                    }
                                    
                                    if (response.isSuccessful && response.body()?.success == true) {
                                        val profile = response.body()?.profile
                                        
                                        if (profile != null && _binding != null) {
                                            // 显示性别
                                            if (!profile.gender.isNullOrEmpty()) {
                                                binding.llFriendGender.visibility = View.VISIBLE
                                                binding.tvFriendGender.text = profile.gender
                                            } else {
                                                binding.llFriendGender.visibility = View.GONE
                                            }
                                            
                                            // 显示地址
                                            if (!profile.address.isNullOrEmpty()) {
                                                binding.llFriendAddress.visibility = View.VISIBLE
                                                binding.tvFriendAddress.text = profile.address
                                            } else {
                                                binding.llFriendAddress.visibility = View.GONE
                                            }
                                            
                                            // 显示电话
                                            if (!profile.phone.isNullOrEmpty()) {
                                                binding.llFriendPhone.visibility = View.VISIBLE
                                                binding.tvFriendPhone.text = profile.phone
                                            } else {
                                                binding.llFriendPhone.visibility = View.GONE
                                            }
                                            
                                            // 显示生日
                                            if (!profile.birthday.isNullOrEmpty()) {
                                                binding.llFriendBirthday.visibility = View.VISIBLE
                                                binding.tvFriendBirthday.text = profile.birthday
                                            } else {
                                                binding.llFriendBirthday.visibility = View.GONE
                                            }
                                            
                                            // 显示喜好
                                            if (!profile.preferences.isNullOrEmpty()) {
                                                binding.llFriendPreferences.visibility = View.VISIBLE
                                                binding.tvFriendPreferences.text = profile.preferences
                                            } else {
                                                binding.llFriendPreferences.visibility = View.GONE
                                            }
                                            
                                            // 如果有头像，更新头像
                                            if (!profile.avatar.isNullOrEmpty()) {
                                                val avatarBitmap = withContext(Dispatchers.IO) {
                                                    GroupAvatarHelper.loadBitmapFromBase64(profile.avatar)
                                                }
                                                if (_binding != null && avatarBitmap != null && !avatarBitmap.isRecycled) {
                                                    binding.ivAvatar.setImageBitmap(avatarBitmap)
                                                }
                                            }
                                            
                                            // 如果有昵称，更新名字
                                            if (!profile.name.isNullOrEmpty() && (friend?.nickname.isNullOrEmpty())) {
                                                binding.tvName.text = profile.name
                                            }
                                        } else {
                                            // 如果没有资料，隐藏所有字段
                                            hideAllFriendInfoFields()
                                        }
                                    } else {
                                        // 获取资料失败，隐藏所有字段
                                        hideAllFriendInfoFields()
                                    }
                                } else {
                                    // API服务未初始化，隐藏所有字段
                                    hideAllFriendInfoFields()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "获取好友资料失败: ${e.message}", e)
                                hideAllFriendInfoFields()
                            }
                        }
                        
                        // 好友对话不显示群成员列表
                        binding.llGroupMembers.visibility = View.GONE
                    }
                    // 非好友：显示成员基本信息（仅显示姓名和头像）
                    else if (conv.id.startsWith("non_friend_")) {
                        val memberImei = conv.id.removePrefix("non_friend_")
                        
                        // 设置名字（使用传入的名称或IMEI）
                        binding.tvName.text = DisplayNameHelper.getDisplayName(requireContext(), conv.name) ?: memberImei
                        
                        // 先设置默认头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_person)
                        
                        // 异步加载成员头像（如果有的话）
                        if (!conv.avatar.isNullOrEmpty()) {
                            mainScope.launch {
                                try {
                                    val avatarBitmap = withContext(Dispatchers.IO) {
                                        GroupAvatarHelper.loadBitmapFromBase64(conv.avatar)
                                    }
                                    if (_binding != null && avatarBitmap != null && !avatarBitmap.isRecycled) {
                                        binding.ivAvatar.setImageBitmap(avatarBitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "加载成员头像失败: ${e.message}", e)
                                }
                            }
                        } else {
                            // 如果没有头像，尝试从服务器获取用户资料
                            mainScope.launch {
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                    val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                                        ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                                    
                                    CustomerServiceNetwork.initialize(customerServiceUrl)
                                    val apiService = CustomerServiceNetwork.getApiService()
                                    
                                    if (apiService != null) {
                                        val response = apiService.getProfile(memberImei)
                                        if (response.isSuccessful && response.body()?.success == true) {
                                            val profile = response.body()?.profile
                                            if (profile != null && !profile.avatar.isNullOrEmpty()) {
                                                val avatarBitmap = withContext(Dispatchers.IO) {
                                                    GroupAvatarHelper.loadBitmapFromBase64(profile.avatar)
                                                }
                                                if (_binding != null && avatarBitmap != null && !avatarBitmap.isRecycled) {
                                                    binding.ivAvatar.setImageBitmap(avatarBitmap)
                                                }
                                                // 如果有昵称，更新名字
                                                if (!profile.name.isNullOrEmpty() && conv.name == memberImei) {
                                                    binding.tvName.text = profile.name
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "获取用户资料失败: ${e.message}", e)
                                }
                            }
                        }
                        
                        // 非好友不显示详细信息
                        binding.llFriendInfo.visibility = View.GONE
                        binding.llGroupMembers.visibility = View.GONE
                    } else if (CustomAssistantManager.isCustomAssistantId(conv.id)) {
                        // 自定义小助手：有头像用其头像，无头像用首字母头像（与聊天页一致）
                        val assistant = CustomAssistantManager.getById(requireContext(), conv.id)
                        if (assistant != null) {
                            val avatarSize = (72 * resources.displayMetrics.density).toInt()
                            AvatarCacheManager.loadCustomAssistantAvatar(
                                context = requireContext(),
                                imageView = binding.ivAvatar,
                                assistant = assistant,
                                cacheKey = "profile_${conv.id}",
                                validationTag = conv.id,
                                sizePx = avatarSize
                            )
                        } else {
                            binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                        }
                        binding.llGroupMembers.visibility = View.GONE
                        binding.llFriendInfo.visibility = View.GONE
                    } else {
                        binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                        // 其他情况也不显示群成员列表
                        binding.llGroupMembers.visibility = View.GONE
                        // 隐藏好友信息区域
                        binding.llFriendInfo.visibility = View.GONE
                    }
                }
            }
            
            // 设置简介
            val description = when (conv.id) {
                ConversationListFragment.CONVERSATION_ID_ASSISTANT -> getString(R.string.assistant_signature)
                ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> getString(R.string.skill_learn_signature)
                ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> getString(R.string.chat_assistant_signature)
                ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> getString(R.string.customer_service_signature)
                ConversationListFragment.CONVERSATION_ID_GROUP -> getString(R.string.friend_group_signature)
                else -> {
                    if (CustomAssistantManager.isCustomAssistantId(conv.id)) {
                        CustomAssistantManager.getById(requireContext(), conv.id)?.intro?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.custom_assistant_signature)
                    } else if (conv.id.startsWith("group_")) {
                        val groupId = conv.id.removePrefix("group_")
                        val group = GroupManager.getGroup(requireContext(), groupId)
                        group?.let {
                            val totalMembers = it.members.size + 1 // +1 是TopoClaw
                            getString(R.string.member_count_format, totalMembers)
                        } ?: getString(R.string.groups)
                    } else if (conv.id.startsWith("friend_")) {
                        getString(R.string.friend)
                    } else if (conv.id.startsWith("non_friend_")) {
                        getString(R.string.group_members)
                    } else {
                        ""
                    }
                }
            }
            binding.tvDescription.text = description
        }
    }
    
    /**
     * 设置固定的"好友群"成员
     */
    private fun setupFixedFriendsGroupMembers() {
        // 清空现有成员列表
        binding.llMembersList.removeAllViews()
        
        // 添加TopoClaw
        addMemberItem(ChatConstants.ASSISTANT_DISPLAY_NAME, R.drawable.ic_assistant_avatar, isAssistant = true)
        
        // 添加技能学习小助手
        addMemberItem("技能学习小助手", R.drawable.ic_skill_learning_avatar, isAssistant = true)
        
        // 添加人工客服
        addMemberItem("人工客服", R.drawable.ic_customer_service_avatar, isAssistant = true)
    }
    
    /**
     * 设置动态群组的成员
     */
    private fun setupGroupMembers(group: GroupManager.Group) {
        Log.d(TAG, "setupGroupMembers: 开始设置群组成员，群组ID=${group.groupId}, 成员数=${group.members.size}, assistantEnabled=${group.assistantEnabled}")
        
        // 清空现有成员列表
        binding.llMembersList.removeAllViews()
        Log.d(TAG, "清空成员列表，当前子视图数: ${binding.llMembersList.childCount}")
        
        val currentImei = ProfileManager.getOrGenerateImei(requireContext())
        val isCreator = currentImei == group.creatorImei
        
        // 显示群组内所有小助手，每个可单独移除
        val assistantIds = group.assistants.ifEmpty {
            if (group.assistantEnabled) listOf(ConversationListFragment.CONVERSATION_ID_ASSISTANT) else emptyList()
        }
        assistantIds.forEach { assistantId ->
            val name = getAssistantDisplayName(assistantId)
            val custom = CustomAssistantManager.getById(requireContext(), assistantId)
            val showGroupManagerToggle = isCreator && custom != null && custom.hasChat()
            val isGroupManager = custom?.hasGroupManager() == true
            val assistantAvatarResId = getAssistantAvatarResId(assistantId)
            val assistantAvatarBase64 = custom?.avatar
            addMemberItem(name, assistantAvatarResId, avatarBase64 = assistantAvatarBase64, isAssistant = true,
                showRemoveAssistant = isCreator, group = group, assistantId = assistantId,
                showGroupManagerToggle = showGroupManagerToggle, isGroupManager = isGroupManager)
        }
        if (isCreator) {
            addAddAssistantRow(group)
        }
        
        // 添加所有群成员（排序：先显示自己，再显示群主，最后显示其他成员）
        Log.d(TAG, "当前用户IMEI: $currentImei, 群组成员: ${group.members}")
        
        // 排序成员：自己 -> 群主 -> 其他成员
        val sortedMembers = group.members.sortedWith { a, b ->
            val aIsMe = a == currentImei
            val bIsMe = b == currentImei
            val aIsCreator = a == group.creatorImei
            val bIsCreator = b == group.creatorImei
            
            when {
                aIsMe && !bIsMe -> -1  // 自己排在最前面
                !aIsMe && bIsMe -> 1
                aIsCreator && !bIsCreator && !aIsMe -> -1  // 群主排在第二位（如果不是自己）
                !aIsCreator && bIsCreator && !bIsMe -> 1
                else -> a.compareTo(b)  // 其他成员按IMEI排序
            }
        }
        
        sortedMembers.forEach { memberImei ->
            val isCreator = memberImei == group.creatorImei
            val isMe = memberImei == currentImei
            
            // 获取成员信息
            val friend = FriendManager.getFriend(requireContext(), memberImei)
            val memberName: String
            val memberAvatar: String?
            
            if (isMe) {
                // 如果是自己，从ProfileManager获取信息
                val profile = ProfileManager.loadProfileLocally(requireContext())
                memberName = profile?.name ?: "我"
                memberAvatar = profile?.avatar
            } else {
                // 如果是好友，从FriendManager获取信息
                memberName = friend?.nickname ?: memberImei.take(8) + "..."
                memberAvatar = friend?.avatar
            }
            
            // 显示名称：如果是自己显示"我"，如果是群主显示"群主"
            val displayName = when {
                isMe -> "我"
                isCreator -> "$memberName (群主)"
                else -> memberName
            }
            
            addMemberItem(displayName, null, memberImei, memberAvatar, isAssistant = false, isCreator = isCreator, isMe = isMe)
        }
        
        Log.d(TAG, "setupGroupMembers: 完成，共添加 ${binding.llMembersList.childCount} 个成员项")
        
        // 更新管理按钮文字
        updateManageButtonText()
    }
    
    /**
     * 添加"添加小助手"行（当群组未添加小助手且当前用户为群主时显示）
     */
    private fun addAddAssistantRow(group: GroupManager.Group) {
        val row = layoutInflater.inflate(R.layout.item_friend_record, binding.llMembersList, false)
        val avatarView = row.findViewById<android.widget.ImageView>(R.id.ivFriendAvatar)
        val nameView = row.findViewById<TextView>(R.id.tvFriendName)
        val imeiView = row.findViewById<TextView>(R.id.tvFriendImei)
        val timeView = row.findViewById<TextView>(R.id.tvFriendTime)
        
        avatarView.setImageResource(R.drawable.ic_assistant_avatar)
        nameView.text = getString(R.string.add_assistant_to_group)
        imeiView.visibility = View.GONE
        timeView.visibility = View.GONE
        
        row.setOnClickListener {
            showInviteMemberDialog(group)
        }
        binding.llMembersList.addView(row)
    }
    
    /**
     * 调用 API 添加或移除群组小助手
     */
    private fun performSetGroupAssistant(group: GroupManager.Group, enabled: Boolean) {
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
                val response = apiService.setGroupAssistant(SetGroupAssistantRequest(group.groupId, currentImei, enabled))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val groupInfo = response.body()?.group
                    if (groupInfo != null) {
                        val assistants = groupInfo.assistants?.let { if (it.isNotEmpty()) it else null }
                            ?: if (groupInfo.assistant_enabled) listOf(ConversationListFragment.CONVERSATION_ID_ASSISTANT) else emptyList()
                        val updatedGroup = GroupManager.Group(
                            groupId = groupInfo.group_id,
                            name = groupInfo.name,
                            creatorImei = groupInfo.creator_imei,
                            members = groupInfo.members,
                            createdAt = groupInfo.created_at,
                            assistantEnabled = groupInfo.assistant_enabled,
                            assistants = assistants
                        )
                        GroupManager.updateGroup(requireContext(), updatedGroup)
                        setupGroupMembers(updatedGroup)
                    }
                    Toast.makeText(requireContext(), if (enabled) "已添加小助手" else "已移除小助手", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), response.body()?.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置群组小助手失败: ${e.message}", e)
                Toast.makeText(requireContext(), "操作失败: ${e.localizedMessage ?: e.toString()}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 移除群组中的指定小助手
     */
    private fun performRemoveAssistant(group: GroupManager.Group, assistantId: String) {
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
                val response = apiService.removeGroupAssistant(RemoveGroupAssistantRequest(group.groupId, currentImei, assistantId))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val groupInfo = response.body()?.group
                    if (groupInfo != null) {
                        val assistants = groupInfo.assistants ?: if (groupInfo.assistant_enabled) listOf(ConversationListFragment.CONVERSATION_ID_ASSISTANT) else emptyList()
                        val updatedGroup = GroupManager.Group(
                            groupId = groupInfo.group_id,
                            name = groupInfo.name,
                            creatorImei = groupInfo.creator_imei,
                            members = groupInfo.members,
                            createdAt = groupInfo.created_at,
                            assistantEnabled = groupInfo.assistant_enabled,
                            assistants = assistants
                        )
                        GroupManager.updateGroup(requireContext(), updatedGroup)
                        setupGroupMembers(updatedGroup)
                    }
                    Toast.makeText(requireContext(), "已移除小助手", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), response.body()?.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "移除群组小助手失败: ${e.message}", e)
                Toast.makeText(requireContext(), "操作失败: ${e.localizedMessage ?: e.toString()}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 添加成员项到列表
     */
    private fun addMemberItem(
        name: String,
        avatarResId: Int? = null,
        imei: String? = null,
        avatarBase64: String? = null,
        isAssistant: Boolean = false,
        isCreator: Boolean = false,
        isMe: Boolean = false,
        showRemoveAssistant: Boolean = false,
        group: GroupManager.Group? = null,
        assistantId: String? = null,
        showGroupManagerToggle: Boolean = false,
        isGroupManager: Boolean = false
    ) {
        val inflater = LayoutInflater.from(requireContext())
        val memberItemView = inflater.inflate(R.layout.item_friend_record, binding.llMembersList, false)
        
        // 头像
        val avatarView = memberItemView.findViewById<android.widget.ImageView>(R.id.ivFriendAvatar)
        val fallbackSizePx = (40 * resources.displayMetrics.density).toInt().coerceAtLeast(40)
        val fallbackLetterAvatar = GroupAvatarHelper.createLetterAvatar(name, fallbackSizePx)
        if (avatarResId != null) {
            avatarView.setImageResource(avatarResId)
        } else if (!avatarBase64.isNullOrEmpty()) {
            avatarView.setImageBitmap(fallbackLetterAvatar)
            // 异步加载头像
            mainScope.launch {
                try {
                    val avatarBitmap = withContext(Dispatchers.IO) {
                        GroupAvatarHelper.loadBitmapFromBase64(avatarBase64)
                    }
                    if (_binding != null && avatarBitmap != null && !avatarBitmap.isRecycled) {
                        avatarView.setImageBitmap(avatarBitmap)
                    } else if (_binding != null) {
                        avatarView.setImageBitmap(fallbackLetterAvatar)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载成员头像失败: ${e.message}", e)
                    if (_binding != null) {
                        avatarView.setImageBitmap(fallbackLetterAvatar)
                    }
                }
            }
        } else {
            avatarView.setImageBitmap(fallbackLetterAvatar)
        }
        
        // 设置圆形头像
        avatarView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatarView.clipToOutline = true
        
        // 名字
        val nameView = memberItemView.findViewById<android.widget.TextView>(R.id.tvFriendName)
        nameView.text = DisplayNameHelper.getDisplayName(requireContext(), name)
        nameView.maxLines = 2
        nameView.ellipsize = android.text.TextUtils.TruncateAt.END
        
        // IMEI - 隐藏
        val imeiView = memberItemView.findViewById<android.widget.TextView>(R.id.tvFriendImei)
        imeiView.visibility = View.GONE
        
        // 时间 - 隐藏
        val timeView = memberItemView.findViewById<android.widget.TextView>(R.id.tvFriendTime)
        timeView.visibility = View.GONE
        
        // 添加选择框（用于管理模式）
        val checkBox = android.widget.CheckBox(requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 16
            }
            visibility = if (isManageMode && !isAssistant && imei != null) View.VISIBLE else View.GONE
            isChecked = if (imei != null) selectedMembers.contains(imei) else false
            setOnCheckedChangeListener { _, isChecked ->
                if (imei != null) {
                    if (isChecked) {
                        selectedMembers.add(imei)
                    } else {
                        selectedMembers.remove(imei)
                    }
                    // 更新管理按钮文字
                    updateManageButtonText()
                }
            }
        }
        (memberItemView as? android.view.ViewGroup)?.addView(checkBox)
        
        // 群组管理者开关（仅群主可见，仅对具备聊天能力的自定义小助手显示）
        if (showGroupManagerToggle && group != null && assistantId != null) {
            val groupManagerSwitch = com.google.android.material.switchmaterial.SwitchMaterial(requireContext()).apply {
                isChecked = isGroupManager
                text = getString(R.string.group_manager_role)
                setPadding(
                    (8 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt()
                )
                setOnCheckedChangeListener { _, checked ->
                    val success = CustomAssistantManager.setAssistantGroupManager(requireContext(), assistantId, checked)
                    if (success) {
                        Toast.makeText(requireContext(),
                            if (checked) getString(R.string.set_as_group_manager) else getString(R.string.unset_group_manager),
                            Toast.LENGTH_SHORT).show()
                        setupGroupMembers(group)
                    }
                }
            }
            (memberItemView as? android.view.ViewGroup)?.addView(groupManagerSwitch)
        }
        
        // 小助手"移除"按钮（仅群主可见）
        if (showRemoveAssistant && group != null) {
            val removeBtn = TextView(requireContext()).apply {
                text = "移除"
                setTextColor(0xFF666666.toInt())
                textSize = 14f
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt()
                )
                setOnClickListener {
                    if (assistantId != null) {
                        performRemoveAssistant(group, assistantId)
                    } else {
                        performSetGroupAssistant(group, false)
                    }
                }
            }
            (memberItemView as? android.view.ViewGroup)?.addView(removeBtn)
        }
        
        // 点击事件
        memberItemView.setOnClickListener {
            // 如果是管理模式且不是小助手，则切换选择状态
            if (isManageMode && !isAssistant && imei != null) {
                checkBox.isChecked = !checkBox.isChecked
                return@setOnClickListener
            }
            
            // 非管理模式下的原有逻辑
            if (isAssistant) {
                // 小助手：跳转到对应的小助手主页
                val resolvedAssistantId = assistantId ?: when (name) {
                    getString(R.string.auto_execute_assistant) -> ConversationListFragment.CONVERSATION_ID_ASSISTANT
                    getString(R.string.topoclaw_assistant) -> ConversationListFragment.CONVERSATION_ID_ASSISTANT
                    getString(R.string.skill_learn_assistant) -> ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING
                    getString(R.string.chat_assistant) -> ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT
                    getString(R.string.customer_service) -> ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE
                    else -> null
                }
                if (resolvedAssistantId != null) {
                    val assistantConv = Conversation(
                        id = resolvedAssistantId,
                        name = name,
                        avatar = null,
                        lastMessage = null,
                        lastMessageTime = System.currentTimeMillis()
                    )
                    val profileFragment = ConversationProfileFragment.newInstance(assistantConv)
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(0, 0, 0, 0)
                        .replace(R.id.fragmentContainer, profileFragment)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            } else if (imei != null) {
                // 成员：跳转到成员详情（无论是否是好友）
                val friend = FriendManager.getFriend(requireContext(), imei)
                val isFriend = friend != null
                
                // 根据是否是好友使用不同的ID格式
                val convId = if (isFriend) "friend_$imei" else "non_friend_$imei"
                val convName = if (isFriend) {
                    friend?.nickname ?: imei
                } else {
                    // 如果不是好友，使用显示的名称（可能是从群成员信息中获取的）
                    name
                }
                val convAvatar = if (isFriend) friend?.avatar else avatarBase64
                
                val memberConv = Conversation(
                    id = convId,
                    name = convName,
                    avatar = convAvatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                val profileFragment = ConversationProfileFragment.newInstance(memberConv)
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(0, 0, 0, 0)
                    .replace(R.id.fragmentContainer, profileFragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
        }
        
        binding.llMembersList.addView(memberItemView)
        Log.d(TAG, "addMemberItem: 添加成员项 - $name, 当前成员项数: ${binding.llMembersList.childCount}, 成员项视图高度: ${memberItemView.height}")
        
        // 确保成员列表可见
        binding.llGroupMembers.visibility = View.VISIBLE
    }
    
    /**
     * 获取小助手的显示名称
     */
    private fun getAssistantDisplayName(assistantId: String): String = when (assistantId) {
        ConversationListFragment.CONVERSATION_ID_ASSISTANT -> getString(R.string.auto_execute_assistant)
        ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> getString(R.string.skill_learn_assistant)
        ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> getString(R.string.chat_assistant)
        else -> CustomAssistantManager.getById(requireContext(), assistantId)?.name ?: assistantId
    }

    private fun getAssistantAvatarResId(assistantId: String): Int? = when (assistantId) {
        ConversationListFragment.CONVERSATION_ID_ASSISTANT -> R.drawable.ic_assistant_avatar
        ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> R.drawable.ic_skill_learning_avatar
        ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> R.drawable.ic_chat_assistant_avatar
        ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> R.drawable.ic_customer_service_avatar
        ConversationListFragment.CONVERSATION_ID_GROUP_MANAGER -> R.drawable.ic_groupmanager_avatar
        else -> null
    }

    /**
     * 构建可邀请加入群组的小助手列表（与创建群组时相同）
     */
    private fun buildInviteAssistantList(context: Context): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        list.add(ConversationListFragment.CONVERSATION_ID_ASSISTANT to context.getString(R.string.auto_execute_assistant))
        list.add(ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING to context.getString(R.string.skill_learn_assistant))
        list.add(ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT to context.getString(R.string.chat_assistant))
        CustomAssistantManager.getVisibleAll(context).forEach { a -> list.add(a.id to a.name) }
        return list
    }

    /**
     * 显示邀请成员对话框
     */
    private fun showInviteMemberDialog(group: GroupManager.Group) {
        // 创建对话框布局（复用创建群组的对话框布局）
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_group, null)
        val etGroupName = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etGroupName)
        val llAssistantsList = dialogView.findViewById<LinearLayout>(R.id.llAssistantsList)
        val llAssistantSection = dialogView.findViewById<ViewGroup>(R.id.llAssistantSection)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btnCreate)
        val llFriendsList = dialogView.findViewById<LinearLayout>(R.id.llFriendsList)
        val tvSelectedCount = dialogView.findViewById<TextView>(R.id.tvSelectedCount)
        
        // 隐藏群组名称输入框（邀请成员不需要）
        etGroupName.visibility = View.GONE
        etGroupName.parent?.let { (it as? ViewGroup)?.visibility = View.GONE }
        
        // 邀请模式：标题改为「邀请成员」
        dialogView.findViewById<TextView>(R.id.tvDialogTitle)?.text = getString(R.string.invite_member)
        
        // 存储选中的好友和小助手
        val selectedFriends = mutableSetOf<String>()
        val selectedAssistants = group.assistants.toMutableSet()
        
        fun updateInviteSelectedCount() {
            var text = getString(R.string.selected_count_people_format, selectedFriends.size)
            if (selectedAssistants.isNotEmpty()) text += "，${selectedAssistants.size} 个小助手"
            tvSelectedCount.text = text
        }
        
        // 始终显示小助手列表，布局与创建群组一致
        val assistantItems = buildInviteAssistantList(requireContext())
        val groupAssistantIds = group.assistants.toSet()
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
            if (id in groupAssistantIds) {
                itemView.isChecked = true
                itemView.isEnabled = false
                itemView.alpha = 0.7f
            } else {
                itemView.setOnClickListener {
                    itemView.isChecked = !itemView.isChecked
                    if (itemView.isChecked) selectedAssistants.add(id) else selectedAssistants.remove(id)
                    updateInviteSelectedCount()
                }
            }
            llAssistantsList.addView(itemView)
        }
        
        // 修改按钮文字
        btnCreate.text = "邀请"
        
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
        
        // 获取已存在的群成员
        val existingMembers = group.members.toSet()
        
        // 加载好友列表并显示（排除已在群中的好友）
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" && it.imei !in existingMembers }
        
        if (friends.isEmpty()) {
            // 如果没有可邀请的好友，显示提示
            val emptyView = TextView(requireContext()).apply {
                text = "没有可邀请的好友"
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
                    
                    // 更新选中数量（包含小助手）
                    updateInviteSelectedCount()
                }
                
                llFriendsList.addView(friendItemView)
            }
        }
        
        // 更新选中数量显示（包含小助手）
        updateInviteSelectedCount()
        
        // 设置关闭按钮点击事件
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // 设置取消按钮点击事件
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        // 设置邀请按钮点击事件
        btnCreate.setOnClickListener {
            val assistantIdsToAdd = selectedAssistants.filter { it !in group.assistants }
            if (selectedFriends.isEmpty() && assistantIdsToAdd.isEmpty()) {
                Toast.makeText(requireContext(), "请至少选择一位好友或一个新小助手", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            dialog.dismiss()
            inviteMembersToGroup(group.groupId, selectedFriends.toList(), assistantIdsToAdd)
        }
        
        dialog.show()
    }
    
    /**
     * 邀请成员加入群组
     * @param assistantIdsToAdd 要添加的小助手 ID 列表（不在群组中的）
     */
    private fun inviteMembersToGroup(groupId: String, memberImeis: List<String>, assistantIdsToAdd: List<String> = emptyList()) {
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
                var currentGroup = GroupManager.getGroup(requireContext(), groupId)
                
                // 逐个邀请好友
                var successCount = 0
                var failCount = 0
                
                for (memberImei in memberImeis) {
                    try {
                        val response = apiService.addGroupMember(AddGroupMemberRequest(groupId, memberImei, currentImei))
                        if (response.isSuccessful && response.body()?.success == true) {
                            successCount++
                        } else {
                            failCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "邀请成员失败: $memberImei, ${e.message}", e)
                        failCount++
                    }
                }
                
                // 逐个添加小助手（仅添加尚未在群组中的）
                for (aid in assistantIdsToAdd) {
                    if (aid !in (currentGroup?.assistants ?: emptyList())) {
                        try {
                            val addResp = apiService.addGroupAssistant(AddGroupAssistantRequest(groupId, currentImei, aid))
                            if (addResp.isSuccessful && addResp.body()?.success == true) {
                                val gi = addResp.body()?.group
                                if (gi != null) {
                                    currentGroup = GroupManager.Group(
                                        groupId = gi.group_id,
                                        name = gi.name,
                                        creatorImei = gi.creator_imei,
                                        members = gi.members,
                                        createdAt = gi.created_at,
                                        assistantEnabled = gi.assistant_enabled,
                                        assistants = gi.assistants ?: if (gi.assistant_enabled) listOf(ConversationListFragment.CONVERSATION_ID_ASSISTANT) else emptyList()
                                    )
                                    GroupManager.updateGroup(requireContext(), currentGroup!!)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "添加小助手失败: $aid, ${e.message}", e)
                        }
                    }
                }
                
                // 刷新群组信息
                try {
                    val groupResponse = apiService.getGroup(groupId)
                    if (groupResponse.isSuccessful && groupResponse.body()?.success == true) {
                        val groupInfo = groupResponse.body()?.group
                        if (groupInfo != null) {
                            val assistants = groupInfo.assistants?.let { if (it.isNotEmpty()) it else null }
                                ?: if (groupInfo.assistant_enabled) listOf(ConversationListFragment.CONVERSATION_ID_ASSISTANT) else emptyList()
                            val group = GroupManager.Group(
                                groupId = groupInfo.group_id,
                                name = groupInfo.name,
                                creatorImei = groupInfo.creator_imei,
                                members = groupInfo.members,
                                createdAt = groupInfo.created_at,
                                assistantEnabled = groupInfo.assistant_enabled,
                                assistants = assistants
                            )
                            GroupManager.updateGroup(requireContext(), group)
                            
                            // 刷新成员列表显示
                            setupGroupMembers(group)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "刷新群组信息失败: ${e.message}", e)
                }
                
                // 显示结果
                val assistantAdded = assistantIdsToAdd.isNotEmpty()
                when {
                    successCount > 0 && failCount == 0 -> {
                        val msg = if (assistantAdded) "成功邀请 $successCount 位成员，${assistantIdsToAdd.size} 个小助手" else "成功邀请 $successCount 位成员"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                    successCount > 0 && failCount > 0 -> {
                        Toast.makeText(requireContext(), "成功邀请 $successCount 位成员，$failCount 位失败", Toast.LENGTH_SHORT).show()
                    }
                    assistantAdded -> {
                        Toast.makeText(requireContext(), "已添加 ${assistantIdsToAdd.size} 个小助手", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(requireContext(), "邀请失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "邀请成员失败: ${e.message}", e)
                Toast.makeText(requireContext(), "邀请成员失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 切换管理模式
     */
    private fun toggleManageMode(group: GroupManager.Group) {
        // 如果处于管理模式且有选中成员，执行删除操作
        if (isManageMode && selectedMembers.isNotEmpty()) {
            showDeleteConfirmationDialog(group)
            return
        }
        
        // 切换管理模式
        isManageMode = !isManageMode
        selectedMembers.clear()
        
        if (isManageMode) {
            binding.btnManageMembers.text = "取消"
            // 刷新成员列表以显示选择框
            setupGroupMembers(group)
        } else {
            binding.btnManageMembers.text = "管理"
            // 刷新成员列表以隐藏选择框
            setupGroupMembers(group)
        }
    }
    
    /**
     * 更新管理按钮文字
     */
    private fun updateManageButtonText() {
        if (isManageMode) {
            if (selectedMembers.isNotEmpty()) {
                binding.btnManageMembers.text = "删除选中(${selectedMembers.size})"
            } else {
                binding.btnManageMembers.text = "取消"
            }
        } else {
            binding.btnManageMembers.text = "管理"
        }
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmationDialog(group: GroupManager.Group) {
        if (selectedMembers.isEmpty()) {
            return
        }
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("删除成员")
            .setMessage("确定要删除选中的 ${selectedMembers.size} 位成员吗？")
            .setPositiveButton("删除") { _, _ ->
                performDeleteMembers(group)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 删除选中的成员（内部方法，由确认对话框调用）
     */
    private fun deleteSelectedMembers(group: GroupManager.Group) {
        performDeleteMembers(group)
    }
    
    /**
     * 执行删除成员操作
     */
    private fun performDeleteMembers(group: GroupManager.Group) {
        mainScope.launch {
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    Toast.makeText(requireContext(), "API服务未初始化", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                var successCount = 0
                var failCount = 0
                
                // 逐个删除成员
                for (memberImei in selectedMembers) {
                    try {
                        val response = apiService.removeGroupMember(
                            RemoveGroupMemberRequest(group.groupId, memberImei, currentImei)
                        )
                        if (response.isSuccessful && response.body()?.success == true) {
                            successCount++
                        } else {
                            failCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "删除成员失败: $memberImei, ${e.message}", e)
                        failCount++
                    }
                }
                
                // 刷新群组信息
                GroupManager.syncGroupsFromServer(requireContext())
                val updatedGroup = GroupManager.getGroup(requireContext(), group.groupId)
                if (updatedGroup != null) {
                    currentGroup = updatedGroup
                    setupGroupMembers(updatedGroup)
                }
                
                // 显示结果
                when {
                    successCount > 0 && failCount == 0 -> {
                        Toast.makeText(requireContext(), "成功删除 $successCount 位成员", Toast.LENGTH_SHORT).show()
                    }
                    successCount > 0 && failCount > 0 -> {
                        Toast.makeText(requireContext(), "成功删除 $successCount 位成员，$failCount 位失败", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(requireContext(), "删除成员失败", Toast.LENGTH_SHORT).show()
                    }
                }
                
                // 退出管理模式
                isManageMode = false
                selectedMembers.clear()
                binding.btnManageMembers.text = "管理"
                setupGroupMembers(updatedGroup ?: group)
                
            } catch (e: Exception) {
                Log.e(TAG, "删除成员失败: ${e.message}", e)
                Toast.makeText(requireContext(), "删除成员失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 用于跟踪ActionBar和导航栏的状态，避免重复操作
    private var isActionBarHidden = false
    private var isNavigationBarHidden = false
    
    // 管理模式相关变量
    private var isManageMode = false
    private var selectedMembers = mutableSetOf<String>() // 选中的成员IMEI集合
    private var currentGroup: GroupManager.Group? = null // 当前群组
    
    override fun onResume() {
        super.onResume()
        // 只有在Fragment可见且未隐藏时才更新UI（使用hide/show时，onResume可能被调用但Fragment仍被隐藏）
        if (!isHidden) {
            updateActionBarAndNavigation()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 只有在Fragment可见且未隐藏时才恢复UI
        if (!isHidden) {
            restoreActionBarAndNavigation()
        }
    }
    
    /**
     * 当Fragment的隐藏状态改变时调用（使用hide/show时）
     * 当从ChatFragment返回时，Fragment会从隐藏变为显示
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        
        if (hidden) {
            // Fragment被隐藏时，禁用回退回调（避免拦截系统返回键）
            onBackPressedCallback?.isEnabled = false
            // 恢复ActionBar和导航栏
            restoreActionBarAndNavigation()
            Log.d(TAG, "Fragment被隐藏，禁用回退回调")
        } else {
            // Fragment显示时，确保回退回调已注册并启用
            if (isAdded && activity != null && view != null) {
                if (onBackPressedCallback == null) {
                    // 如果回调未注册，重新注册
                    Log.d(TAG, "Fragment显示，回退回调未注册，重新注册")
                    onBackPressedCallback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            navigateBackToConversationList()
                        }
                    }
                    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback!!)
                } else {
                    // 如果回调已存在，确保启用
                    onBackPressedCallback?.isEnabled = true
                    Log.d(TAG, "Fragment显示，启用回退回调")
                }
            }
            // 延迟更新ActionBar和导航栏，避免阻塞Fragment切换动画
            view?.post {
                updateActionBarAndNavigation()
            }
        }
    }
    
    /**
     * 更新ActionBar和导航栏的显示状态（隐藏）
     */
    private fun updateActionBarAndNavigation() {
        try {
            val mainActivity = activity as? MainActivity ?: return
            
            // 检查ActionBar状态，避免重复操作
            val actionBar = mainActivity.supportActionBar
            if (actionBar != null && actionBar.isShowing && !isActionBarHidden) {
                actionBar.hide()
                isActionBarHidden = true
            }
            
            // 检查导航栏状态，避免重复操作
            if (!isNavigationBarHidden) {
                mainActivity.setBottomNavigationVisibility(false)
                isNavigationBarHidden = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "更新ActionBar和导航栏失败: ${e.message}", e)
        }
    }
    
    /**
     * 恢复ActionBar和导航栏的显示状态（显示）
     */
    private fun restoreActionBarAndNavigation() {
        try {
            val mainActivity = activity as? MainActivity ?: return
            
            // 检查ActionBar状态，避免重复操作
            val actionBar = mainActivity.supportActionBar
            if (actionBar != null && !actionBar.isShowing && isActionBarHidden) {
                actionBar.show()
                isActionBarHidden = false
            }
            
            // 检查导航栏状态，避免重复操作
            if (isNavigationBarHidden) {
                mainActivity.setBottomNavigationVisibility(true)
                isNavigationBarHidden = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复ActionBar和导航栏失败: ${e.message}", e)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 移除在线状态监听器
        OnlineStatusManager.removeListener(onlineStatusListener)
        // 移除系统返回键回调
        onBackPressedCallback?.remove()
        onBackPressedCallback = null
        _binding = null
        mainScope.cancel()
    }
    
    /**
     * 返回到之前的ChatFragment
     * 从助手主页返回时，返回到和具体助手的聊天页面（ChatFragment）
     * 这个聊天页面必须和从一级聊天页面点进去的是同一个实例
     * 由于使用了hide/add而不是replace，ChatFragment不会被销毁，返回时是同一个实例
     * 注意：从助手主页返回时，不使用任何过渡动画
     */
    private fun navigateBackToConversationList() {
        try {
            conversation?.let { conv ->
                Log.d(TAG, "开始返回，当前conversation: ${conv.id}, 返回栈数量: ${parentFragmentManager.backStackEntryCount}")
                
                // 打印所有fragments的状态用于调试
                parentFragmentManager.fragments.forEachIndexed { index, fragment ->
                    val fragmentConv = when (fragment) {
                        is ChatFragment -> fragment.arguments?.getSerializable("conversation") as? Conversation
                        is ConversationProfileFragment -> fragment.conversation
                        else -> null
                    }
                    Log.d(TAG, "Fragment[$index]: ${fragment.javaClass.simpleName}, isHidden=${fragment.isHidden}, isAdded=${fragment.isAdded}, conversationId=${fragmentConv?.id}")
                }
                
                // 查找隐藏的ChatFragment，确保对应当前的conversation
                val chatFragment = parentFragmentManager.fragments.find { fragment ->
                    if (fragment is ChatFragment && fragment.isHidden) {
                        // 验证ChatFragment是否对应当前的conversation
                        val fragmentConv = fragment.arguments?.getSerializable("conversation") as? Conversation
                        val matches = fragmentConv?.id == conv.id
                        Log.d(TAG, "检查ChatFragment: conversationId=${fragmentConv?.id}, 匹配=${matches}")
                        matches
                    } else {
                        false
                    }
                } as? ChatFragment
                
                if (chatFragment != null) {
                    Log.d(TAG, "找到对应当前conversation(${conv.id})的ChatFragment，返回聊天详情页")
                    
                    // 在Fragment切换之前立即隐藏导航栏，避免动画
                    (activity as? MainActivity)?.setBottomNavigationVisibility(false)
                    
                    // 如果找到了隐藏的ChatFragment，隐藏所有其他Fragment，只显示ChatFragment
                    val transaction = parentFragmentManager.beginTransaction()
                        .setCustomAnimations(
                            R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                            R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
                        )
                    
                    // 隐藏当前Fragment
                    transaction.hide(this@ConversationProfileFragment)
                    
                    // 隐藏所有其他Fragment（包括ConversationListFragment）
                    parentFragmentManager.fragments.forEach { fragment ->
                        if (fragment != chatFragment && fragment != this@ConversationProfileFragment) {
                            transaction.hide(fragment)
                        }
                    }
                    
                    // 显示ChatFragment
                    transaction.show(chatFragment)
                    transaction.commitAllowingStateLoss()
                    
                    // 更新MainActivity的UI状态（确保ActionBar也隐藏）
                    (activity as? MainActivity)?.let { mainActivity ->
                        mainActivity.window?.decorView?.post {
                            mainActivity.updateUIForCurrentFragment()
                        }
                    }
                } else {
                    // 如果没有找到隐藏的ChatFragment，尝试查找所有ChatFragment（包括未隐藏的）
                    Log.w(TAG, "未找到对应当前conversation(${conv.id})的隐藏ChatFragment，尝试查找所有ChatFragment")
                    
                    val allChatFragments = parentFragmentManager.fragments.filterIsInstance<ChatFragment>()
                    Log.d(TAG, "找到${allChatFragments.size}个ChatFragment")
                    
                    // 查找对应当前conversation的ChatFragment（不管是否隐藏）
                    val matchingChatFragment = allChatFragments.find { fragment ->
                        val fragmentConv = fragment.arguments?.getSerializable("conversation") as? Conversation
                        fragmentConv?.id == conv.id
                    }
                    
                    if (matchingChatFragment != null) {
                        Log.d(TAG, "找到对应当前conversation的ChatFragment（isHidden=${matchingChatFragment.isHidden}），显示它")
                        // 先隐藏当前Fragment
                        parentFragmentManager.beginTransaction()
                            .setCustomAnimations(
                                R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                                R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
                            )
                            .hide(this@ConversationProfileFragment)
                            .show(matchingChatFragment)
                            .commitAllowingStateLoss()
                    } else {
                        // 如果还是找不到，使用MainActivity的方法切换到正确的对话
                        Log.w(TAG, "未找到对应当前conversation的ChatFragment，使用MainActivity切换")
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null) {
                            // 先移除当前Fragment
                            parentFragmentManager.beginTransaction()
                                .setCustomAnimations(0, 0, 0, 0)
                                .remove(this@ConversationProfileFragment)
                                .commitAllowingStateLoss()
                            // 使用MainActivity的方法切换到正确的ChatFragment
                            mainActivity.switchToChatFragment(conv)
                        } else {
                            // 如果MainActivity不可用，尝试使用popBackStack
                            if (parentFragmentManager.backStackEntryCount > 0) {
                                Log.d(TAG, "MainActivity不可用，使用popBackStack")
                                parentFragmentManager.popBackStack()
                            } else {
                                Log.w(TAG, "MainActivity不可用且返回栈为空，无法返回")
                            }
                        }
                    }
                }
            } ?: run {
                // 如果conversation为null，使用popBackStack
                if (parentFragmentManager.backStackEntryCount > 0) {
                    parentFragmentManager.popBackStack()
                } else {
                    // 如果返回栈为空，返回到聊天主页面
                    Log.d(TAG, "返回栈为空，返回到聊天主页面")
                    val conversationListFragment = ConversationListFragment()
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(0, 0, 0, 0)
                        .replace(R.id.fragmentContainer, conversationListFragment)
                        .commitAllowingStateLoss()
                    
                    // 显示ActionBar和底部导航栏
                    (activity as? MainActivity)?.supportActionBar?.show()
                    (activity as? MainActivity)?.setBottomNavigationVisibility(true)
                    // 设置底部导航栏选中聊天项
                    (activity as? MainActivity)?.setBottomNavigationSelectedItem(R.id.nav_chat)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "返回失败: ${e.message}", e)
            // 如果出错，尝试使用MainActivity的方法
            conversation?.let { conv ->
                val mainActivity = activity as? MainActivity
                if (mainActivity != null) {
                    try {
                        mainActivity.switchToChatFragment(conv)
                    } catch (e2: Exception) {
                        Log.e(TAG, "使用MainActivity切换也失败: ${e2.message}", e2)
                    }
                } else {
                    // 如果MainActivity不可用，尝试使用popBackStack
                    try {
                        parentFragmentManager.popBackStack()
                    } catch (e2: Exception) {
                        Log.e(TAG, "popBackStack也失败: ${e2.message}", e2)
                    }
                }
            } ?: run {
                // conversation为null，尝试使用popBackStack
                try {
                    parentFragmentManager.popBackStack()
                } catch (e2: Exception) {
                    Log.e(TAG, "popBackStack也失败: ${e2.message}", e2)
                }
            }
        }
    }
    
    /**
     * 显示聊天背景设置对话框（图片选择界面）
     */
    private fun showChatBackgroundDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_chat_background_select, null)
        
        val btnDefaultBackground = dialogView.findViewById<android.widget.Button>(R.id.btnDefaultBackground)
        val btnPickFromGallery = dialogView.findViewById<android.widget.Button>(R.id.btnPickFromGallery)
        val btnTakePhoto = dialogView.findViewById<android.widget.Button>(R.id.btnTakePhoto)
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .show()
        
        // 使用默认背景按钮
        btnDefaultBackground.setOnClickListener {
            dialog.dismiss()
            setDefaultChatBackground()
        }
        
        // 从相册选择按钮
        btnPickFromGallery.setOnClickListener {
            dialog.dismiss()
            pickImageFromGallery()
        }
        
        // 拍照按钮
        btnTakePhoto.setOnClickListener {
            dialog.dismiss()
            takePhotoForBackground()
        }
    }
    
    /**
     * 设置默认背景
     */
    private fun setDefaultChatBackground() {
        val conversationId = conversation?.id ?: return
        val prefs = requireContext().getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
        
        // 删除旧的自定义背景文件（如果存在）
        val backgroundPath = prefs.getString("chat_background_path_$conversationId", null)
        if (backgroundPath != null) {
            try {
                val backgroundFile = java.io.File(backgroundPath)
                if (backgroundFile.exists()) {
                    backgroundFile.delete()
                    Log.d(TAG, "已删除自定义背景图片文件: $backgroundPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除自定义背景图片文件失败: ${e.message}", e)
            }
        }
        
        // 设置为默认背景
        prefs.edit()
            .putString("chat_background_type_$conversationId", "default")
            .remove("chat_background_preset_$conversationId")
            .remove("chat_background_uri_$conversationId")  // 兼容旧版本
            .remove("chat_background_path_$conversationId")
            .apply()
        
        Toast.makeText(requireContext(), "已设置为默认背景", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "已设置为默认背景")
    }
    
    /**
     * 从相册选择图片
     */
    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_CODE_CHAT_BACKGROUND_GALLERY)
    }
    
    /**
     * 拍照设置背景
     */
    private fun takePhotoForBackground() {
        try {
            // 创建临时文件保存拍照图片
            val imageFile = java.io.File(requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "chat_background_${System.currentTimeMillis()}.jpg")
            cameraImageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    imageFile
                )
            } else {
                @Suppress("DEPRECATION")
                android.net.Uri.fromFile(imageFile)
            }
            
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_CODE_CHAT_BACKGROUND_CAMERA)
        } catch (e: Exception) {
            Log.e(TAG, "启动相机失败: ${e.message}", e)
            Toast.makeText(requireContext(), "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 保存聊天背景
     * 将图片复制到应用私有目录，避免应用重启后URI失效
     */
    private fun saveChatBackground(imageUri: Uri) {
        mainScope.launch {
            try {
                val conversationId = conversation?.id ?: return@launch
                
                // 创建聊天背景目录
                val backgroundsDir = java.io.File(requireContext().filesDir, "chat_backgrounds")
                if (!backgroundsDir.exists()) {
                    backgroundsDir.mkdirs()
                }
                
                // 生成文件名（使用conversationId确保唯一性）
                val backgroundFile = java.io.File(backgroundsDir, "background_$conversationId.jpg")
                
                // 将图片从URI复制到应用私有目录
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(imageUri)?.use { inputStream ->
                        backgroundFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } ?: throw Exception("无法打开图片输入流")
                }
                
                // 保存文件路径到SharedPreferences
                val prefs = requireContext().getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("chat_background_type_$conversationId", "custom")
                    .putString("chat_background_path_$conversationId", backgroundFile.absolutePath)
                    .apply()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "聊天背景设置成功", Toast.LENGTH_SHORT).show()
                }
                
                Log.d(TAG, "聊天背景已保存到: ${backgroundFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "保存聊天背景失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 处理Activity结果（包括图片选择）
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_CHAT_BACKGROUND_GALLERY -> {
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    val imageUri = data.data
                    if (imageUri != null) {
                        saveChatBackground(imageUri)
                    }
                }
            }
            REQUEST_CODE_CHAT_BACKGROUND_CAMERA -> {
                if (resultCode == android.app.Activity.RESULT_OK) {
                    // 拍照后，图片URI在cameraImageUri中
                    cameraImageUri?.let { uri ->
                        saveChatBackground(uri)
                    }
                }
            }
        }
    }
    
    /**
     * 显示删除小助手确认对话框
     */
    private fun showDeleteAssistantDialog() {
        val conversationId = conversation?.id ?: return
        if (!CustomAssistantManager.isCustomAssistantId(conversationId)) return

        val assistantName = conversation?.name ?: "小助手"
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_assistant))
            .setMessage(getString(R.string.delete_assistant_confirm, assistantName))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteAssistant(conversationId)
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    /**
     * 删除自定义小助手
     */
    private fun deleteAssistant(assistantId: String) {
        mainScope.launch {
            try {
                if (!isAdded) return@launch
                val context = requireContext().applicationContext
                val syncOk = withContext(Dispatchers.IO) {
                    // 1. 先计算删除后的列表（避免 SharedPreferences apply 未完成时 getAll 读到旧数据）
                    val listToSync = CustomAssistantManager.getAll(context).filter { it.id != assistantId }
                    CustomAssistantManager.replaceAll(context, listToSync)

                    // 2. 清除本地聊天记录
                    val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    appPrefs.edit()
                        .remove("chat_messages_$assistantId")
                        .remove("chat_messages_start_time_$assistantId")
                        .apply()
                    val convPrefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
                    convPrefs.edit()
                        .remove("${assistantId}_last_message")
                        .remove("${assistantId}_last_time")
                        .apply()

                    // 3. 使用显式计算的 listToSync 同步到云端，不依赖 getAll 再读一次
                    syncCustomAssistantsToCloud(context, listToSync)
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    if (syncOk) {
                        Toast.makeText(context, getString(R.string.delete_assistant_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, getString(R.string.delete_assistant_sync_failed), Toast.LENGTH_LONG).show()
                    }
                    navigateBackToConversationListAfterDelete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除小助手失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * 同步自定义小助手列表到云端（挂起函数，可等待完成）
     * @param context 上下文
     * @param explicitList 可选，显式指定要同步的列表（删除场景下使用，避免 getAll 读到 apply 未完成的旧数据）
     * @return 是否同步成功
     */
    private suspend fun syncCustomAssistantsToCloud(
        context: Context,
        explicitList: List<CustomAssistantManager.CustomAssistant>? = null
    ): Boolean {
        // APK 侧禁用 custom_assistants 写入，仅保留拉取（GET）以避免覆盖 PC 配置。
        val list = explicitList ?: CustomAssistantManager.getAll(context)
        Log.d(TAG, "跳过自定义小助手云端写入，localCount=${list.size}")
        return true
    }

    /**
     * 删除小助手后返回到对话列表
     */
    private fun navigateBackToConversationListAfterDelete() {
        try {
            val mainActivity = activity as? MainActivity ?: return
            // 通知对话列表和通讯录刷新（删除后 CustomAssistantManager 已更新）
            LocalBroadcastManager.getInstance(mainActivity).sendBroadcast(Intent(ConversationListFragment.ACTION_CUSTOM_ASSISTANT_ADDED))
            val fm = mainActivity.supportFragmentManager
            // 清空返回栈，避免返回时进入已删除小助手的页面
            try {
                fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            } catch (e: Exception) {
                Log.w(TAG, "popBackStack 失败（可能栈已空）: ${e.message}")
            }
            val conversationListFragment = ConversationListFragment()
            fm.beginTransaction()
                .setCustomAnimations(0, 0, 0, 0)
                .replace(R.id.fragmentContainer, conversationListFragment)
                .commitAllowingStateLoss()
            mainActivity.supportActionBar?.show()
            mainActivity.setBottomNavigationVisibility(true)
            mainActivity.setBottomNavigationSelectedItem(R.id.nav_chat)
        } catch (e: Exception) {
            Log.e(TAG, "返回对话列表失败: ${e.message}", e)
        }
    }

    /**
     * 显示删除好友确认对话框
     */
    private fun showDeleteFriendDialog() {
        val conversationId = conversation?.id ?: return
        if (!conversationId.startsWith("friend_")) return
        
        val friendImei = conversationId.removePrefix("friend_")
        val friendName = conversation?.name ?: friendImei
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("删除好友")
            .setMessage("确定要删除好友 \"$friendName\" 吗？删除后对方将无法再向您发送消息。")
            .setPositiveButton("删除") { _, _ ->
                deleteFriend(friendImei)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 删除好友
     */
    private fun deleteFriend(friendImei: String) {
        mainScope.launch {
            try {
                val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService != null) {
                    val response = apiService.removeFriend(RemoveFriendRequest(friendImei, currentImei))
                    if (response.isSuccessful && response.body()?.success == true) {
                        // 删除本地好友数据
                        FriendManager.removeFriend(requireContext(), friendImei)
                        
                        // 从对话列表中删除该对话
                        val prefs = requireContext().getSharedPreferences("conversations_prefs", Context.MODE_PRIVATE)
                        val deletedSetJson = prefs.getString("deleted_conversations", null)
                        val deletedSet = if (deletedSetJson != null) {
                            try {
                                val gson = com.google.gson.Gson()
                                val type = com.google.gson.reflect.TypeToken.getParameterized(MutableSet::class.java, String::class.java).type
                                gson.fromJson<MutableSet<String>>(deletedSetJson, type) ?: mutableSetOf()
                            } catch (e: Exception) {
                                mutableSetOf()
                            }
                        } else {
                            mutableSetOf()
                        }
                        deletedSet.add("friend_$friendImei")
                        val gson = com.google.gson.Gson()
                        prefs.edit().putString("deleted_conversations", gson.toJson(deletedSet)).apply()
                        
                        // 显示成功提示
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "已删除好友", Toast.LENGTH_SHORT).show()
                            // 返回到对话列表
                            navigateBackToConversationList()
                        }
                    } else {
                        val errorMsg = response.body()?.message ?: "删除好友失败"
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    // API服务未初始化，只删除本地数据
                    FriendManager.removeFriend(requireContext(), friendImei)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "已删除好友（本地）", Toast.LENGTH_SHORT).show()
                        navigateBackToConversationList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除好友失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "删除好友失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                        nickname = conversation?.name,
                        avatar = conversation?.avatar,
                        status = "accepted",
                        addedAt = System.currentTimeMillis()
                    )
                    FriendManager.addFriend(requireContext(), friend)
                    
                    // 记录好友关系的发起方（我发起的）
                    val friendsPrefs = requireContext().getSharedPreferences("friends_prefs", Context.MODE_PRIVATE)
                    val initiatorJson = friendsPrefs.getString("friend_initiator", null)
                    val initiatorMap = if (initiatorJson != null) {
                        try {
                            val gson = com.google.gson.Gson()
                            val type = com.google.gson.reflect.TypeToken.getParameterized(MutableMap::class.java, String::class.java, String::class.java).type
                            gson.fromJson<MutableMap<String, String>>(initiatorJson, type) ?: mutableMapOf()
                        } catch (e: Exception) {
                            mutableMapOf()
                        }
                    } else {
                        mutableMapOf()
                    }
                    initiatorMap[friendImei] = currentImei
                    val gson = com.google.gson.Gson()
                    friendsPrefs.edit().putString("friend_initiator", gson.toJson(initiatorMap)).apply()
                    
                    // 同步服务器好友列表
                    try {
                        FriendManager.syncFriendsFromServer(requireContext())
                    } catch (e: Exception) {
                        Log.w(TAG, "同步好友列表失败: ${e.message}")
                    }
                    
                    // 显示成功提示并更新页面
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.add_friend_success), Toast.LENGTH_SHORT).show()
                        
                        // 更新conversation对象，将non_friend_转换为friend_
                        conversation?.let { conv ->
                            conversation = Conversation(
                                id = "friend_$friendImei",
                                name = conv.name,
                                avatar = conv.avatar,
                                lastMessage = conv.lastMessage,
                                lastMessageTime = conv.lastMessageTime
                            )
                            // 重新加载页面
                            loadProfile()
                            // 更新按钮状态
                            setupUI()
                        }
                    }
                } else {
                    val errorMsg = response.body()?.message ?: "添加失败"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "添加好友失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.add_friend_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 隐藏所有好友信息字段
     */
    private fun hideAllFriendInfoFields() {
        if (_binding == null) return
        binding.llFriendGender.visibility = View.GONE
        binding.llFriendAddress.visibility = View.GONE
        binding.llFriendPhone.visibility = View.GONE
        binding.llFriendBirthday.visibility = View.GONE
        binding.llFriendPreferences.visibility = View.GONE
    }
    
    /**
     * 复制文本到剪贴板
     */
    private fun copyToClipboard(text: String, label: String = "文本") {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "复制到剪贴板失败: ${e.message}", e)
        }
    }
}

