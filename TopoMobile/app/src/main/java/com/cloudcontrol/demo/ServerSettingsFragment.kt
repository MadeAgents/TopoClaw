package com.cloudcontrol.demo

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentServerSettingsBinding

/**
 * 服务器设置Fragment
 * 用于为不同的小助手设置专用的服务器地址
 */
class ServerSettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "ServerSettingsFragment"
        private const val ARG_CONVERSATION_ID = "conversation_id"
        private const val ARG_ASSISTANT_NAME = "assistant_name"
        
        fun newInstance(conversationId: String, assistantName: String): ServerSettingsFragment {
            return ServerSettingsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONVERSATION_ID, conversationId)
                    putString(ARG_ASSISTANT_NAME, assistantName)
                }
            }
        }
    }
    
    private var _binding: FragmentServerSettingsBinding? = null
    private val binding get() = _binding!!
    
    private var conversationId: String? = null
    private var assistantName: String? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        conversationId = arguments?.getString(ARG_CONVERSATION_ID)
        assistantName = arguments?.getString(ARG_ASSISTANT_NAME) ?: "小助手"
        
        // 隐藏ActionBar
        (activity as? MainActivity)?.supportActionBar?.hide()
        
        setupUI()
        loadServerUrl()
        loadContextRecordingSetting()
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
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private fun setupUI() {
        // 显示小助手名称
        binding.tvAssistantName.text = "$assistantName 服务器设置"
        
        // 返回按钮
        binding.btnBack.setOnClickListener {
            // 检查是否有ConversationProfileFragment被隐藏，如果有则显式show它
            val profileFragment = parentFragmentManager.fragments.find { it is com.cloudcontrol.demo.ConversationProfileFragment && it.isHidden }
            if (profileFragment != null) {
                // 先移除当前Fragment，然后show回ConversationProfileFragment
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                        R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
                    )
                    .remove(this)
                    .show(profileFragment)
                    .commitAllowingStateLoss()
            } else {
                // 没有隐藏的ConversationProfileFragment，使用正常的popBackStack
                parentFragmentManager.popBackStack()
            }
        }
        
        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveServerUrl()
        }
        
        // 清除专用设置按钮
        binding.btnClear.setOnClickListener {
            clearServerUrl()
        }
        
        // 上下文记录开关
        binding.switchContextRecording.setOnCheckedChangeListener { _, isChecked ->
            saveContextRecordingSetting(isChecked)
        }
    }
    
    /**
     * 加载服务器地址
     */
    private fun loadServerUrl() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val conversationId = this.conversationId ?: return
        
        // 优先加载小助手专用地址
        val specificKey = "chat_server_url_$conversationId"
        val specificUrl = prefs.getString(specificKey, null)
        
        // 如果有专用地址，直接使用
        if (specificUrl != null) {
            binding.etServerUrl.setText(specificUrl)
            return
        }
        
        // 如果没有专用地址，根据小助手类型使用对应的默认地址
        val defaultUrl = when (conversationId) {
            ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> {
                // 技能学习小助手使用专用默认地址
                ServiceUrlConfig.DEFAULT_SKILL_LEARNING_URL
            }
            ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> {
                // 聊天小助手使用专用默认地址（v10）
                ServiceUrlConfig.DEFAULT_CHAT_ASSISTANT_URL
            }
            ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> {
                // 人工客服使用专用默认地址（v4）
                ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            }
            else -> {
                // 其他小助手使用全局默认地址
                prefs.getString("chat_server_url", ServiceUrlConfig.DEFAULT_SERVER_URL)
                    ?: ServiceUrlConfig.DEFAULT_SERVER_URL
            }
        }
        
        binding.etServerUrl.setText(defaultUrl)
    }
    
    /**
     * 保存服务器地址
     */
    private fun saveServerUrl() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        val conversationId = this.conversationId ?: return
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_$conversationId"
        
        // 保存小助手专用地址
        prefs.edit().putString(specificKey, serverUrl).apply()
        
        Toast.makeText(requireContext(), "服务器地址已保存", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "已保存 $assistantName 的服务器地址: $serverUrl")
    }
    
    /**
     * 清除专用设置
     */
    private fun clearServerUrl() {
        val conversationId = this.conversationId ?: return
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val specificKey = "chat_server_url_$conversationId"
        
        // 清除小助手专用地址
        prefs.edit().remove(specificKey).apply()
        
        // 重新加载（会显示对应的默认地址）
        loadServerUrl()
        
        val defaultType = when (conversationId) {
            ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> "技能学习小助手默认地址"
            ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> "人工客服默认地址"
            else -> "全局默认地址"
        }
        Toast.makeText(requireContext(), "已清除专用设置，将使用$defaultType", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "已清除 $assistantName 的专用服务器地址")
    }
    
    /**
     * 加载上下文记录设置
     */
    private fun loadContextRecordingSetting() {
        val conversationId = this.conversationId ?: return
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val key = "enable_context_recording_$conversationId"
        // 群组聊天和好友聊天默认不开启上下文记录
        val defaultValue = when {
            conversationId.startsWith("group_") -> false  // 群组聊天默认关闭
            conversationId.startsWith("friend_") -> false  // 好友聊天默认关闭
            else -> true  // 其他情况默认打开
        }
        val isEnabled = prefs.getBoolean(key, defaultValue)
        binding.switchContextRecording.isChecked = isEnabled
    }
    
    /**
     * 保存上下文记录设置
     */
    private fun saveContextRecordingSetting(isEnabled: Boolean) {
        val conversationId = this.conversationId ?: return
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val key = "enable_context_recording_$conversationId"
        prefs.edit().putBoolean(key, isEnabled).apply()
        val status = if (isEnabled) "已开启" else "已关闭"
        Toast.makeText(requireContext(), "上下文记录$status", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "已${status} $assistantName 的上下文记录")
    }
}

