package com.cloudcontrol.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentConversationProfileBinding
import kotlinx.coroutines.*

/**
 * 用户主页Fragment
 * 显示用户自己的头像、名字和简介（类似助手详情页，但没有设置按钮）
 */
class UserProfileFragment : Fragment() {
    
    companion object {
        private const val TAG = "UserProfileFragment"
        
        fun newInstance(): UserProfileFragment {
            return UserProfileFragment()
        }
    }
    
    private var _binding: FragmentConversationProfileBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var onBackPressedCallback: OnBackPressedCallback? = null
    
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
        
        // 注册系统返回键回调
        onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback!!)
        
        setupUI()
        loadProfile()
    }
    
    override fun onResume() {
        super.onResume()
        // 隐藏底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
    }
    
    override fun onPause() {
        super.onPause()
        // 显示底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(true)
    }
    
    /**
     * 当Fragment的隐藏状态改变时调用（使用hide/show时）
     * 当从ProfileDetailFragment返回时，Fragment会从隐藏变为显示
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        
        if (!hidden && isAdded && activity != null) {
            // Fragment变为可见时，刷新数据（可能用户在编辑页面修改了资料）
            loadProfile()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 移除系统返回键回调
        onBackPressedCallback?.remove()
        onBackPressedCallback = null
        mainScope.cancel()
        _binding = null
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            navigateBack()
        }
        
        // 显示编辑按钮（用户主页需要编辑按钮）
        binding.btnEdit.visibility = View.VISIBLE
        binding.btnEdit.setOnClickListener {
            navigateToEditProfile()
        }
        
        // 隐藏发消息按钮（用户自己的主页不需要发消息按钮）
        binding.btnSendMessage.visibility = View.GONE
        
        // 隐藏群成员列表（用户主页不需要）
        binding.llGroupMembers.visibility = View.GONE
    }
    
    private fun loadProfile() {
        mainScope.launch {
            try {
                // 从本地加载用户资料
                val profile = ProfileManager.loadProfileLocally(requireContext())
                
                if (profile != null) {
                    // 设置名字
                    binding.tvName.text = profile.name ?: "未设置"
                    
                    // 设置简介（使用用户的名字或默认文本）
                    val description = if (!profile.name.isNullOrEmpty()) {
                        "这是您的个人主页"
                    } else {
                        "点击右上角编辑按钮建立您的个人资料"
                    }
                    binding.tvDescription.text = description
                    
                    // 加载头像
                    loadAvatar(profile.avatar)
                } else {
                    // 没有资料，显示默认信息
                    binding.tvName.text = "未设置"
                    binding.tvDescription.text = "点击右上角编辑按钮建立您的个人资料"
                    binding.ivAvatar.setImageResource(R.drawable.ic_person)
                    binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载用户资料失败: ${e.message}", e)
            }
        }
    }
    
    private fun loadAvatar(avatarBase64: String?) {
        if (!avatarBase64.isNullOrEmpty()) {
            try {
                val decodedBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    binding.ivAvatar.setImageBitmap(bitmap)
                    Log.d(TAG, "用户头像已加载")
                } else {
                    binding.ivAvatar.setImageResource(R.drawable.ic_person)
                    binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载用户头像失败: ${e.message}", e)
                binding.ivAvatar.setImageResource(R.drawable.ic_person)
                binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
            }
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_person)
            binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
        }
        
        // 设置圆形裁剪
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatar.clipToOutline = true
    }
    
    /**
     * 导航到编辑个人资料页面
     */
    private fun navigateToEditProfile() {
        try {
            // 从本地加载用户资料
            val profile = ProfileManager.loadProfileLocally(requireContext())
            
            // 如果本地也没有，创建一个默认的 profile
            val profileToShow = profile ?: UserProfile(
                imei = ProfileManager.getOrGenerateImei(requireContext()),
                name = null,
                gender = null,
                address = null,
                phone = null,
                birthday = null,
                preferences = null,
                avatar = null
            )
            
            val detailFragment = ProfileDetailFragment.newInstance(profileToShow)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(0, 0, 0, 0)  // 禁用所有动画
                .hide(this@UserProfileFragment)  // 隐藏当前Fragment，而不是replace
                .add(R.id.fragmentContainer, detailFragment)  // 添加新的Fragment
                .addToBackStack(null)
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "跳转到编辑页面失败: ${e.message}", e)
        }
    }
    
    /**
     * 返回到之前的ChatFragment
     * 手动恢复ChatFragment的显示，而不是依赖popBackStack
     * 因为ChatFragment可能不在back stack中（通过show()显示）
     */
    private fun navigateBack() {
        try {
            // 查找隐藏的ChatFragment
            val chatFragment = parentFragmentManager.fragments.find { it is ChatFragment && it.isHidden }
            if (chatFragment != null) {
                // 如果找到了隐藏的ChatFragment，手动显示它
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                        R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
                    )
                    .hide(this@UserProfileFragment)
                    .show(chatFragment)
                    .commitAllowingStateLoss()
            } else {
                // 如果没有找到ChatFragment，使用popBackStack
                if (parentFragmentManager.backStackEntryCount > 0) {
                    parentFragmentManager.popBackStack()
                } else {
                    Log.d(TAG, "返回栈为空，返回到聊天主页面")
                    (activity as? MainActivity)?.let { mainActivity ->
                        mainActivity.switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                        mainActivity.supportActionBar?.show()
                        mainActivity.setBottomNavigationVisibility(true)
                        mainActivity.setBottomNavigationSelectedItem(R.id.nav_chat)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "返回失败: ${e.message}", e)
            // 如果出错，尝试使用popBackStack
            try {
                if (parentFragmentManager.backStackEntryCount > 0) {
                    parentFragmentManager.popBackStack()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "popBackStack也失败: ${e2.message}", e2)
            }
        }
    }
}

