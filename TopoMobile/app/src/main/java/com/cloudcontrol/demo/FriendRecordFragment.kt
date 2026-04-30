package com.cloudcontrol.demo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentFriendRecordBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 好友记录Fragment
 * 显示最近添加和被添加的好友记录
 */
class FriendRecordFragment : Fragment() {
    
    companion object {
        private const val TAG = "FriendRecordFragment"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
    
    private var _binding: FragmentFriendRecordBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendRecordBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 设置标题栏背景颜色为浅灰色
        binding.llTitleBar.setBackgroundColor(0xFFF5F5F5.toInt())
        
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 加载好友记录
        loadFriendRecords()
        
        // 加载待处理的好友申请
        loadPendingFriendRequests()
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否已附加到Activity
        if (!isAdded || context == null) return
        
        // 确保ActionBar隐藏（不需要TopoClaw顶部导航栏）
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.hideActionBarWithoutAnimation()
            // 设置状态栏颜色为浅灰色，与标题栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 隐藏底部导航栏（因为这是详情页）
            mainActivity.setBottomNavigationVisibility(false)
        }
        
        // 刷新待处理的好友申请列表
        loadPendingFriendRequests()
    }
    
    /**
     * 加载好友记录
     */
    private fun loadFriendRecords() {
        mainScope.launch {
            try {
                // 获取当前用户IMEI
                val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                
                // 从本地加载好友列表
                val localFriends = FriendManager.getFriends(requireContext())
                
                // 尝试从服务器同步好友列表
                try {
                    val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                        ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                    
                    CustomerServiceNetwork.initialize(customerServiceUrl)
                    FriendManager.syncFriendsFromServer(requireContext())
                } catch (e: Exception) {
                    Log.w(TAG, "同步好友列表失败: ${e.message}")
                }
                
                // 重新获取好友列表（可能已更新）
                val allFriends = FriendManager.getFriends(requireContext())
                
                // 区分最近添加的好友和被添加的好友
                // 尝试从本地存储中获取好友关系的发起方信息
                val prefs = requireContext().getSharedPreferences("friends_prefs", android.content.Context.MODE_PRIVATE)
                val friendInitiatorJson = prefs.getString("friend_initiator", null)
                
                val addedFriends = mutableListOf<Friend>()
                val receivedFriends = mutableListOf<Friend>()
                
                // 如果没有发起方记录，将所有好友作为"最近添加的好友"
                if (friendInitiatorJson == null) {
                    val allAcceptedFriends = allFriends
                        .filter { it.status == "accepted" }
                        .sortedByDescending { it.addedAt }
                    addedFriends.addAll(allAcceptedFriends)
                } else {
                    // 解析发起方记录
                    try {
                        val type = object : TypeToken<Map<String, String>>() {}.type
                        val initiatorMap = Gson().fromJson<Map<String, String>>(friendInitiatorJson, type) ?: emptyMap()
                        
                        allFriends.filter { it.status == "accepted" }.forEach { friend ->
                            val initiator = initiatorMap[friend.imei]
                            if (initiator == currentImei) {
                                // 我发起的，属于"最近添加的好友"
                                addedFriends.add(friend)
                            } else if (initiator == "received" || initiator != null) {
                                // 对方发起的（initiator为"received"或其他值），属于"被添加的好友"
                                receivedFriends.add(friend)
                            } else {
                                // 没有发起方记录，默认作为"最近添加的好友"
                                addedFriends.add(friend)
                            }
                        }
                        
                        // 按时间排序
                        addedFriends.sortByDescending { it.addedAt }
                        receivedFriends.sortByDescending { it.addedAt }
                    } catch (e: Exception) {
                        Log.w(TAG, "解析好友发起方记录失败: ${e.message}")
                        // 解析失败，将所有好友作为"最近添加的好友"
                        val allAcceptedFriends = allFriends
                            .filter { it.status == "accepted" }
                            .sortedByDescending { it.addedAt }
                        addedFriends.addAll(allAcceptedFriends)
                    }
                }
                
                // 显示被添加的好友（交换位置，先显示被添加的）
                displayReceivedFriends(receivedFriends)
                
                // 显示添加的好友（交换位置，后显示添加的）
                displayAddedFriends(addedFriends)
                
            } catch (e: Exception) {
                Log.e(TAG, "加载好友记录失败: ${e.message}", e)
                Toast.makeText(requireContext(), "加载好友记录失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 显示最近添加的好友
     */
    private fun displayAddedFriends(friends: List<Friend>) {
        binding.llAddedFriends.removeAllViews()
        
        if (friends.isEmpty()) {
            val emptyView = createEmptyView("暂无添加的好友")
            binding.llAddedFriends.addView(emptyView)
        } else {
            friends.forEach { friend ->
                val friendItemView = createFriendItemView(friend, isAdded = true)
                binding.llAddedFriends.addView(friendItemView)
            }
        }
    }
    
    /**
     * 显示被添加的好友
     */
    private fun displayReceivedFriends(friends: List<Friend>) {
        binding.llReceivedFriends.removeAllViews()
        
        if (friends.isEmpty()) {
            val emptyView = createEmptyView("暂无被添加的好友记录")
            binding.llReceivedFriends.addView(emptyView)
        } else {
            friends.forEach { friend ->
                val friendItemView = createFriendItemView(friend, isAdded = false)
                binding.llReceivedFriends.addView(friendItemView)
            }
        }
    }
    
    /**
     * 创建好友项视图
     */
    private fun createFriendItemView(friend: Friend, isAdded: Boolean): View {
        val inflater = LayoutInflater.from(requireContext())
        val itemView = inflater.inflate(R.layout.item_friend_record, binding.llAddedFriends, false)
        
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
        
        // 异步加载好友头像（如果有的话）
        if (!friend.avatar.isNullOrEmpty()) {
            mainScope.launch {
                try {
                    val avatarBitmap = withContext(Dispatchers.IO) {
                        GroupAvatarHelper.loadBitmapFromBase64(friend.avatar)
                    }
                    // 切换到主线程更新UI
                    if (_binding != null && avatarBitmap != null && !avatarBitmap.isRecycled) {
                        avatarView.setImageBitmap(avatarBitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载好友头像失败: ${e.message}", e)
                    // 保持默认头像
                }
            }
        }
        
        // 名字
        val nameView = itemView.findViewById<TextView>(R.id.tvFriendName)
        nameView.text = friend.nickname ?: friend.imei.take(8) + "..."
        
        // 时间
        val timeView = itemView.findViewById<TextView>(R.id.tvFriendTime)
        val timeText = DATE_FORMAT.format(Date(friend.addedAt))
        timeView.text = timeText
        
        // IMEI（可选显示）
        val imeiView = itemView.findViewById<TextView>(R.id.tvFriendImei)
        imeiView.text = "IMEI: ${friend.imei.take(8)}..."
        
        return itemView
    }
    
    /**
     * 加载待处理的好友申请
     */
    private fun loadPendingFriendRequests() {
        mainScope.launch {
            try {
                val pendingRequests = FriendRequestManager.getPendingRequests(requireContext())
                    .filter { it.status == "pending" }
                    .sortedByDescending { it.timestamp }
                
                displayPendingRequests(pendingRequests)
            } catch (e: Exception) {
                Log.e(TAG, "加载待处理好友申请失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 显示待处理的好友申请
     */
    private fun displayPendingRequests(requests: List<FriendRequestManager.FriendRequest>) {
        binding.llPendingRequests.removeAllViews()
        
        if (requests.isEmpty()) {
            val emptyView = createEmptyView("暂无待处理的好友申请")
            binding.llPendingRequests.addView(emptyView)
        } else {
            requests.forEach { request ->
                val requestItemView = createPendingRequestItemView(request)
                binding.llPendingRequests.addView(requestItemView)
            }
        }
    }
    
    /**
     * 创建待处理好友申请项视图
     */
    private fun createPendingRequestItemView(request: FriendRequestManager.FriendRequest): View {
        val inflater = LayoutInflater.from(requireContext())
        val itemView = inflater.inflate(R.layout.item_friend_request, binding.llPendingRequests, false)
        
        // 头像
        val avatarView = itemView.findViewById<android.widget.ImageView>(R.id.ivFriendAvatar)
        avatarView.setImageResource(R.drawable.ic_person)
        
        // 设置圆形头像
        avatarView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatarView.clipToOutline = true
        
        // 名字
        val nameView = itemView.findViewById<TextView>(R.id.tvFriendName)
        nameView.text = request.senderName ?: request.senderImei.take(8) + "..."
        
        // IMEI
        val imeiView = itemView.findViewById<TextView>(R.id.tvFriendImei)
        imeiView.text = "IMEI: ${request.senderImei.take(8)}..."
        
        // 时间
        val timeView = itemView.findViewById<TextView>(R.id.tvFriendTime)
        val timeText = DATE_FORMAT.format(Date(request.timestamp))
        timeView.text = timeText
        
        // 同意按钮
        val btnAccept = itemView.findViewById<android.widget.Button>(R.id.btnAccept)
        btnAccept.setOnClickListener {
            acceptFriendRequest(request)
        }
        
        // 拒绝按钮
        val btnReject = itemView.findViewById<android.widget.Button>(R.id.btnReject)
        btnReject.setOnClickListener {
            rejectFriendRequest(request)
        }
        
        // 查看信息按钮
        val btnViewInfo = itemView.findViewById<android.widget.Button>(R.id.btnViewInfo)
        btnViewInfo.setOnClickListener {
            showFriendInfoDialog(request)
        }
        
        return itemView
    }
    
    /**
     * 同意好友申请
     */
    private fun acceptFriendRequest(request: FriendRequestManager.FriendRequest) {
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
                val response = apiService.addFriend(AddFriendRequest(request.senderImei, currentImei))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    // 更新好友申请状态
                    FriendRequestManager.updateRequestStatus(requireContext(), request.senderImei, "accepted")
                    
                    // 添加好友到本地
                    val friend = Friend(
                        imei = request.senderImei,
                        nickname = null,
                        avatar = null,
                        status = "accepted",
                        addedAt = System.currentTimeMillis()
                    )
                    FriendManager.addFriend(requireContext(), friend)
                    
                    // 记录好友关系的发起方（对方发起的）
                    val friendPrefs = requireContext().getSharedPreferences("friends_prefs", android.content.Context.MODE_PRIVATE)
                    val initiatorJson = friendPrefs.getString("friend_initiator", null)
                    val initiatorMap = if (initiatorJson != null) {
                        try {
                            val type = object : com.google.gson.reflect.TypeToken<MutableMap<String, String>>() {}.type
                            com.google.gson.Gson().fromJson<MutableMap<String, String>>(initiatorJson, type) ?: mutableMapOf()
                        } catch (e: Exception) {
                            mutableMapOf()
                        }
                    } else {
                        mutableMapOf()
                    }
                    initiatorMap[request.senderImei] = "received"
                    friendPrefs.edit().putString("friend_initiator", com.google.gson.Gson().toJson(initiatorMap)).apply()
                    
                    // 刷新列表
                    loadPendingFriendRequests()
                    loadFriendRecords()
                    
                    // 刷新FriendFragment的红点
                    try {
                        val fragmentManager = parentFragmentManager
                        val friendFragment = fragmentManager.fragments.find { it is FriendFragment } as? FriendFragment
                        friendFragment?.updateBadge()
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新好友Fragment红点失败: ${e.message}")
                    }
                    
                    // 刷新对话列表
                    try {
                        val fragmentManager = parentFragmentManager
                        val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
                        conversationListFragment?.loadConversations()
                    } catch (e: Exception) {
                        Log.w(TAG, "刷新对话列表失败: ${e.message}")
                    }
                    
                    Toast.makeText(requireContext(), "已同意好友申请", Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = response.body()?.message ?: "同意失败"
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "同意好友申请失败: ${e.message}", e)
                Toast.makeText(requireContext(), "同意好友申请失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 拒绝好友申请
     */
    private fun rejectFriendRequest(request: FriendRequestManager.FriendRequest) {
        // 更新好友申请状态
        FriendRequestManager.updateRequestStatus(requireContext(), request.senderImei, "rejected")
        
        // 刷新列表
        loadPendingFriendRequests()
        
        // 刷新FriendFragment的红点
        try {
            val fragmentManager = parentFragmentManager
            val friendFragment = fragmentManager.fragments.find { it is FriendFragment } as? FriendFragment
            friendFragment?.updateBadge()
        } catch (e: Exception) {
            Log.w(TAG, "刷新好友Fragment红点失败: ${e.message}")
        }
        
        Toast.makeText(requireContext(), "已拒绝好友申请", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示好友信息对话框
     */
    private fun showFriendInfoDialog(request: FriendRequestManager.FriendRequest) {
        mainScope.launch {
            try {
                // 初始化 CustomerServiceNetwork
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(customerServiceUrl)
                
                val apiService = CustomerServiceNetwork.getApiService()
                var profile: UserProfile? = null
                
                if (apiService != null) {
                    val response = apiService.getProfile(request.senderImei)
                    if (response.isSuccessful && response.body()?.success == true) {
                        profile = response.body()?.profile
                    }
                }
                
                // 在主线程显示对话框
                withContext(Dispatchers.Main) {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_friend_info, null)
                    val tvName = dialogView.findViewById<TextView>(R.id.tvName)
                    val tvImei = dialogView.findViewById<TextView>(R.id.tvImei)
                    val tvGender = dialogView.findViewById<TextView>(R.id.tvGender)
                    val tvAddress = dialogView.findViewById<TextView>(R.id.tvAddress)
                    val tvPhone = dialogView.findViewById<TextView>(R.id.tvPhone)
                    val tvBirthday = dialogView.findViewById<TextView>(R.id.tvBirthday)
                    val tvPreferences = dialogView.findViewById<TextView>(R.id.tvPreferences)
                    val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
                    
                    // 设置信息
                    tvName.text = profile?.name ?: request.senderName ?: "用户"
                    tvImei.text = "IMEI: ${request.senderImei}"
                    tvGender.text = profile?.gender ?: getString(R.string.not_filled)
                    tvAddress.text = profile?.address ?: getString(R.string.not_filled)
                    tvPhone.text = profile?.phone ?: getString(R.string.not_filled)
                    tvBirthday.text = profile?.birthday ?: getString(R.string.not_filled)
                    tvPreferences.text = profile?.preferences ?: getString(R.string.not_filled)
                    
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
                    
                    btnClose.setOnClickListener {
                        dialog.dismiss()
                    }
                    
                    dialog.show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取好友信息失败: ${e.message}", e)
                // 如果获取失败，只显示IMEI
                withContext(Dispatchers.Main) {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("用户信息")
                        .setMessage("IMEI: ${request.senderImei}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }
    
    /**
     * 创建空状态视图
     */
    private fun createEmptyView(text: String): View {
        val textView = TextView(requireContext())
        textView.text = text
        textView.textSize = 14f
        textView.setTextColor(0xFF999999.toInt())
        textView.gravity = android.view.Gravity.CENTER
        textView.setPadding(0, 32, 0, 32)
        textView.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return textView
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        mainScope.cancel()
    }
}

