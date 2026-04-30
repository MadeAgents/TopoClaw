package com.cloudcontrol.demo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityManagerCompat
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentServiceBinding
import android.view.accessibility.AccessibilityManager
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * 服务Fragment
 * 负责服务管理、权限设置、日志显示等
 */
class ServiceFragment : Fragment() {
    
    companion object {
        private const val TAG = "ServiceFragment"
        private const val REQUEST_MEDIA_PROJECTION_CHAT = 1002
        private const val REQUEST_OVERLAY_PERMISSION = 1003
        private val DEFAULT_SERVER_URL = ServiceUrlConfig.DEFAULT_SERVER_URL
        private const val MAX_HISTORY_SIZE = 20 // 最多保存20条历史地址
    }
    
    private var _binding: FragmentServiceBinding? = null
    private val binding get() = _binding!!
    
    // 服务器地址历史记录
    private val serverUrlHistory = mutableListOf<String>()
    
    // 保存PopupWindow引用
    private var popupWindow: PopupWindow? = null
    
    // 无障碍服务检查重试Handler
    private val accessibilityCheckHandler = Handler(Looper.getMainLooper())
    private var accessibilityCheckRunnable: Runnable? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServiceBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（服务页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 加载服务器地址历史记录
        loadServerUrlHistory()
        
        setupUI()
        checkAccessibilityService()
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否已附加到Activity
        if (!isAdded || context == null) return
        
        // 确保ActionBar隐藏（服务页面有自己的标题栏，不需要ActionBar）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 确保底部导航栏显示（延迟执行，确保在updateUIForCurrentFragment之后，避免冲突）
            mainActivity.window?.decorView?.postDelayed({
                mainActivity.setBottomNavigationVisibility(true)
            }, 100) // 100ms延迟，确保updateUIForCurrentFragment先执行
        }
        
        checkAccessibilityService()
        updateOverlayPermissionButton()
        updateChatPermissionButton()
        // 尝试重新初始化覆盖层（如果权限已授权）
        try {
            InteractionOverlayManager.initialize(requireContext())
        } catch (e: Exception) {
            Log.w(TAG, "重新初始化覆盖层失败: ${e.message}")
        }
    }
    
    private fun setupUI() {
        // 加载保存的云侧服务地址
        loadSavedServerUrl()
        
        // 服务器地址下拉箭头按钮
        binding.btnServerUrlDropdown.setOnClickListener {
            showServerUrlMenu(it)
        }
        
        // 应用云侧服务地址按钮（用于聊天功能）
        binding.btnApplyServerUrl.setOnClickListener {
            applyServerUrl()
        }
        
        // 开启无障碍服务按钮
        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        
        // 授权截图权限按钮（用于聊天）
        binding.btnRequestChatPermission.setOnClickListener {
            requestChatScreenshotPermission()
        }
        
        // 授权悬浮窗权限按钮（用于交互点可视化）
        binding.btnRequestOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }
        
        // 更新悬浮窗权限按钮状态
        updateOverlayPermissionButton()
        
        // 更新截图权限按钮状态
        updateChatPermissionButton()
        
        // 加载并设置"默认允许好友控制手机"开关
        loadAllowFriendControlByDefaultSetting()
        
        // 设置"默认允许好友控制手机"开关监听器
        binding.switchAllowFriendControlByDefault.setOnCheckedChangeListener { _, isChecked ->
            saveAllowFriendControlByDefaultSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：默认允许好友控制手机" else "已关闭：默认允许好友控制手机")
        }
        
        // 加载并设置"打开应用时回到主页"开关
        loadOpenActionClearTopSetting()
        
        // 设置"打开应用时回到主页"开关监听器
        binding.switchOpenActionClearTop.setOnCheckedChangeListener { _, isChecked ->
            saveOpenActionClearTopSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：打开应用时回到主页" else "已关闭：打开应用时保持当前页面")
        }
        
        // 加载并设置"新任务开始前清除后台应用"开关
        loadClearBackgroundBeforeTaskSetting()
        
        // 设置"新任务开始前清除后台应用"开关监听器
        binding.switchClearBackgroundBeforeTask.setOnCheckedChangeListener { _, isChecked ->
            saveClearBackgroundBeforeTaskSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：新任务开始前清除后台应用" else "已关闭：新任务开始前不清除后台应用")
        }
        
        // 加载并设置"录制前清除后台应用"开关
        loadClearBackgroundBeforeRecordingSetting()
        
        // 设置"录制前清除后台应用"开关监听器
        binding.switchClearBackgroundBeforeRecording.setOnCheckedChangeListener { _, isChecked ->
            saveClearBackgroundBeforeRecordingSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：录制前清除后台应用" else "已关闭：录制前不清除后台应用")
        }
        
        // 加载并设置"任务运行悬浮窗"开关
        loadTaskIndicatorOverlaySetting()
        
        // 设置"任务运行悬浮窗"开关监听器
        binding.switchTaskIndicatorOverlay.setOnCheckedChangeListener { _, isChecked ->
            saveTaskIndicatorOverlaySetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：任务运行悬浮窗" else "已关闭：任务运行悬浮窗")
            // 如果关闭开关且悬浮窗正在显示，立即隐藏
            if (!isChecked) {
                TaskIndicatorOverlayManager.hide()
            }
        }
        
        // 加载并设置"开启伴随模式"开关
        loadCompanionModeSetting()
        
        // 设置"开启伴随模式"开关监听器
        binding.switchCompanionMode.setOnCheckedChangeListener { _, isChecked ->
            saveCompanionModeSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：伴随模式" else "已关闭：伴随模式")
            // 如果开启伴随模式，显示悬浮球；如果关闭，且没有任务运行，隐藏悬浮球
            if (isChecked) {
                TaskIndicatorOverlayManager.showCompanionMode(requireContext())
                // 延迟显示教程动画，确保悬浮球已显示
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.util.Log.d("ServiceFragment", "准备显示教程")
                    // 每次开启伴随模式时都显示教程（除非用户明确点击了"我知道了"）
                    // 注意：如果用户之前点击过"我知道了"，则不会显示
                    CompanionModeTutorialOverlayManager.show(requireContext())
                }, 500)
            } else {
                // 只有在没有任务运行时才隐藏
                val mainActivity = activity as? MainActivity
                if (mainActivity?.getTaskRunningStatus() != true) {
                    TaskIndicatorOverlayManager.forceHide()
                }
                // 隐藏教程（如果正在显示）
                CompanionModeTutorialOverlayManager.hide()
            }
        }
        
        // 加载并设置"端侧异常检测模型"开关
        loadEdgeModelSetting()
        
        // 设置"端侧异常检测模型"开关监听器
        binding.switchEdgeModel.setOnCheckedChangeListener { _, isChecked ->
            saveEdgeModelSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：端侧异常检测模型" else "已关闭：端侧异常检测模型")
        }
        
        // 加载并设置"端侧变化检测"开关
        loadSendBeforeActionImageSetting()
        
        // 设置"端侧变化检测"开关监听器
        binding.switchSendBeforeActionImage.setOnCheckedChangeListener { _, isChecked ->
            saveSendBeforeActionImageSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：端侧变化检测" else "已关闭：端侧变化检测")
        }
        
        // 加载并设置"边框特效"开关
        loadBorderEffectSetting()
        
        // 设置"边框特效"开关监听器
        binding.switchBorderEffect.setOnCheckedChangeListener { _, isChecked ->
            saveBorderEffectSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：边框特效" else "已关闭：边框特效")
            // 如果关闭开关且边框正在显示，立即隐藏
            if (!isChecked) {
                BorderEffectOverlayManager.hide()
            }
        }
        
        // 加载并设置"网格激活特效"开关
        loadGridActivationEffectSetting()
        
        // 设置"网格激活特效"开关监听器
        binding.switchGridActivationEffect.setOnCheckedChangeListener { _, isChecked ->
            saveGridActivationEffectSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：网格激活特效" else "已关闭：网格激活特效")
            // 如果关闭开关且特效正在显示，立即隐藏
            if (!isChecked) {
                GridActivationOverlayManager.hide()
            }
        }
        
        // 加载并设置"展示推理过程"开关
        loadReasoningOverlaySetting()
        
        // 设置"展示推理过程"开关监听器
        binding.switchReasoningOverlay.setOnCheckedChangeListener { _, isChecked ->
            saveReasoningOverlaySetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：展示推理过程" else "已关闭：展示推理过程")
            // 如果关闭开关且弹窗正在显示，立即隐藏
            if (!isChecked) {
                ReasoningOverlayManager.hide()
            }
        }
        
        // 测试按钮
        binding.btnTest.setOnClickListener {
            // 导航到测试页面（TestFragment）
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(0, 0, 0, 0)  // 禁用所有动画
                .replace(R.id.fragmentContainer, TestFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        
        // 测试网格激活特效按钮（长按测试按钮）
        binding.btnTest.setOnLongClickListener {
            Log.d(TAG, "长按测试按钮，触发网格激活特效测试")
            (activity as? MainActivity)?.addLog("测试网格激活特效...")
            try {
                GridActivationOverlayManager.showOnce(requireContext())
                (activity as? MainActivity)?.addLog("✓ 网格激活特效已触发")
            } catch (e: Exception) {
                Log.e(TAG, "测试网格激活特效失败: ${e.message}", e)
                (activity as? MainActivity)?.addLog("✗ 测试失败: ${e.message}")
            }
            true  // 返回true表示已处理长按事件
        }
    }
    
    /**
     * 加载保存的云侧服务地址
     */
    private fun loadSavedServerUrl() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("chat_server_url", DEFAULT_SERVER_URL)
        binding.etServerUrl.setText(savedUrl)
    }
    
    /**
     * 加载"默认允许好友控制手机"设置
     */
    private fun loadAllowFriendControlByDefaultSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("allow_friend_control_by_default", false) // 默认false（关闭）
        binding.switchAllowFriendControlByDefault.isChecked = enabled
    }
    
    /**
     * 保存"默认允许好友控制手机"设置
     */
    private fun saveAllowFriendControlByDefaultSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_friend_control_by_default", isChecked).apply()
    }
    
    /**
     * 加载"打开应用时回到主页"设置
     */
    private fun loadOpenActionClearTopSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val clearTop = prefs.getBoolean("open_action_clear_top", false) // 默认false（关闭）
        binding.switchOpenActionClearTop.isChecked = clearTop
    }
    
    /**
     * 保存"打开应用时回到主页"设置
     */
    private fun saveOpenActionClearTopSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("open_action_clear_top", isChecked).apply()
    }
    
    /**
     * 加载"新任务开始前清除后台应用"设置
     */
    private fun loadClearBackgroundBeforeTaskSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val clearBackground = prefs.getBoolean("clear_background_before_task", false) // 默认false
        binding.switchClearBackgroundBeforeTask.isChecked = clearBackground
    }
    
    /**
     * 保存"新任务开始前清除后台应用"设置
     */
    private fun saveClearBackgroundBeforeTaskSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("clear_background_before_task", isChecked).apply()
    }
    
    /**
     * 加载"录制前清除后台应用"设置
     */
    private fun loadClearBackgroundBeforeRecordingSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val clearBackground = prefs.getBoolean("clear_background_before_recording", false) // 默认false
        binding.switchClearBackgroundBeforeRecording.isChecked = clearBackground
    }
    
    /**
     * 保存"录制前清除后台应用"设置
     */
    private fun saveClearBackgroundBeforeRecordingSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("clear_background_before_recording", isChecked).apply()
    }
    
    /**
     * 加载"任务运行悬浮窗"设置
     */
    private fun loadTaskIndicatorOverlaySetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("task_indicator_overlay_enabled", true) // 默认true（打开）
        binding.switchTaskIndicatorOverlay.isChecked = enabled
    }
    
    /**
     * 保存"任务运行悬浮窗"设置
     */
    private fun saveTaskIndicatorOverlaySetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("task_indicator_overlay_enabled", isChecked).apply()
    }
    
    /**
     * 加载"开启伴随模式"设置
     */
    private fun loadCompanionModeSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("companion_mode_enabled", true) // 默认true（打开）
        binding.switchCompanionMode.isChecked = enabled
    }
    
    /**
     * 保存"开启伴随模式"设置
     */
    private fun saveCompanionModeSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("companion_mode_enabled", isChecked).apply()
    }
    
    /**
     * 加载"端侧异常检测模型"设置
     */
    private fun loadEdgeModelSetting() {
        val enabled = ScreenshotService.isEdgeModelEnabled(requireContext())
        binding.switchEdgeModel.isChecked = enabled
    }
    
    /**
     * 保存"端侧异常检测模型"设置
     */
    private fun saveEdgeModelSetting(isChecked: Boolean) {
        ScreenshotService.setEdgeModelEnabled(requireContext(), isChecked)
    }
    
    /**
     * 加载"端侧变化检测"设置
     */
    private fun loadSendBeforeActionImageSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("send_before_action_image", false) // 默认false（关闭）
        binding.switchSendBeforeActionImage.isChecked = enabled
    }
    
    /**
     * 保存"端侧变化检测"设置
     */
    private fun saveSendBeforeActionImageSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("send_before_action_image", isChecked).apply()
    }
    
    /**
     * 加载"边框特效"设置
     */
    private fun loadBorderEffectSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("border_effect_enabled", false) // 默认false（关闭）
        binding.switchBorderEffect.isChecked = enabled
    }
    
    /**
     * 保存"边框特效"设置
     */
    private fun saveBorderEffectSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("border_effect_enabled", isChecked).apply()
    }
    
    /**
     * 加载"网格激活特效"设置
     */
    private fun loadGridActivationEffectSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("grid_activation_effect_enabled", false)  // 默认关闭
        binding.switchGridActivationEffect.isChecked = enabled
    }
    
    /**
     * 保存"网格激活特效"设置
     */
    private fun saveGridActivationEffectSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("grid_activation_effect_enabled", isChecked).apply()
    }
    
    /**
     * 加载"展示推理过程"设置
     */
    private fun loadReasoningOverlaySetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("reasoning_overlay_enabled", true) // 默认true（打开）
        binding.switchReasoningOverlay.isChecked = enabled
    }
    
    /**
     * 保存"展示推理过程"设置
     */
    private fun saveReasoningOverlaySetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("reasoning_overlay_enabled", isChecked).apply()
    }
    
    /**
     * 应用云侧服务地址（用于聊天功能）
     */
    private fun applyServerUrl() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请输入云侧服务地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 保存到SharedPreferences
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("chat_server_url", serverUrl).apply()
        
        // 添加到历史记录（排除默认地址，避免重复）
        if (serverUrl != DEFAULT_SERVER_URL) {
            addServerUrlToHistory(serverUrl)
        }
        
        // 更新NetworkService
        NetworkService.initialize(serverUrl, requireContext())
        
        (activity as? MainActivity)?.addLog("已更新云侧服务地址: $serverUrl")
        Toast.makeText(requireContext(), "云侧服务地址已更新", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示服务器地址下拉菜单（可滚动，包含默认地址和历史地址）
     */
    private fun showServerUrlMenu(anchor: View) {
        // 如果已有下拉菜单打开，先关闭
        popupWindow?.dismiss()
        
        // 合并默认地址和历史地址（默认地址在前，历史地址在后）
        val allUrls = mutableListOf<String>()
        allUrls.add(DEFAULT_SERVER_URL)
        allUrls.addAll(serverUrlHistory)
        
        if (allUrls.isEmpty()) {
            Toast.makeText(requireContext(), "暂无地址记录", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 创建ListView用于显示地址列表
        val listView = ListView(requireContext())
        
        // 创建适配器
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            allUrls
        )
        listView.adapter = adapter
        
        // 设置列表项点击监听
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position >= 0 && position < allUrls.size) {
                val selectedUrl = allUrls[position]
                // 将选中的地址填入输入框
                binding.etServerUrl.setText(selectedUrl)
                // 将光标移到末尾
                binding.etServerUrl.setSelection(selectedUrl.length)
                // 关闭下拉菜单
                popupWindow?.dismiss()
            }
        }
        
        // 计算下拉菜单的高度（单页显示5个，每个约48dp）
        val itemHeight = (48 * resources.displayMetrics.density).toInt()
        val maxHeight = itemHeight * 5 // 单页显示5个
        val totalHeight = itemHeight * allUrls.size
        val listViewHeight = if (totalHeight > maxHeight) maxHeight else totalHeight
        
        // 设置ListView高度
        listView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            listViewHeight
        )
        
        // 计算宽度（与输入框宽度一致）
        val inputWidth = binding.etServerUrl.width
        val popupWidth = if (inputWidth > 0) inputWidth else ViewGroup.LayoutParams.WRAP_CONTENT
        
        // 创建PopupWindow
        popupWindow = PopupWindow(
            listView,
            popupWidth,
            listViewHeight,
            true // focusable
        )
        
        // 设置背景和样式
        popupWindow?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), android.R.drawable.dialog_holo_light_frame)
        )
        popupWindow?.elevation = 8f
        
        // 设置外部点击关闭
        popupWindow?.isOutsideTouchable = true
        
        // 显示在输入框下方
        popupWindow?.showAsDropDown(binding.etServerUrl, 0, 0, Gravity.START)
    }
    
    /**
     * 添加服务器地址到历史记录
     */
    private fun addServerUrlToHistory(url: String) {
        // 如果已存在，先移除（避免重复）
        serverUrlHistory.remove(url)
        // 添加到最前面（最新的在前面）
        serverUrlHistory.add(0, url)
        // 限制历史记录数量
        if (serverUrlHistory.size > MAX_HISTORY_SIZE) {
            serverUrlHistory.removeAt(serverUrlHistory.size - 1)
        }
        // 保存到SharedPreferences
        saveServerUrlHistory()
    }
    
    /**
     * 保存服务器地址历史到SharedPreferences
     */
    private fun saveServerUrlHistory() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        // 使用Set保存，避免重复
        editor.putStringSet("server_url_history", serverUrlHistory.toSet())
        editor.apply()
        Log.d(TAG, "已保存服务器地址历史，共${serverUrlHistory.size}条")
    }
    
    /**
     * 从SharedPreferences加载服务器地址历史
     */
    private fun loadServerUrlHistory() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val historySet = prefs.getStringSet("server_url_history", null)
        if (historySet != null) {
            serverUrlHistory.clear()
            serverUrlHistory.addAll(historySet)
            Log.d(TAG, "已加载服务器地址历史，共${serverUrlHistory.size}条")
        }
    }
    
    /**
     * 检查无障碍服务状态（带重试机制）
     * 区分权限状态和服务连接状态，避免误判
     */
    private fun checkAccessibilityService() {
        // 取消之前的重试任务
        accessibilityCheckRunnable?.let { accessibilityCheckHandler.removeCallbacks(it) }
        
        var retryCount = 0
        val maxRetries = 20  // 增加重试次数（10秒），应对服务延迟连接
        val delayMs = 500L
        
        accessibilityCheckRunnable = object : Runnable {
            override fun run() {
                val hasPermission = isAccessibilityServiceEnabled()
                val serviceConnected = MyAccessibilityService.getInstance() != null
                
                when {
                    // 权限已授予且服务已连接
                    hasPermission && serviceConnected -> {
                        updateAccessibilityButton(enabled = false, text = "无障碍服务已开启")
                    }
                    // 权限已授予但服务未连接（可能正在连接中）
                    hasPermission && !serviceConnected && retryCount < maxRetries -> {
                        updateAccessibilityButton(enabled = false, text = "正在连接...")
                        retryCount++
                        accessibilityCheckHandler.postDelayed(this, delayMs)
                    }
                    // 权限未授予
                    else -> {
                        updateAccessibilityButton(enabled = true, text = "开启无障碍服务")
                    }
                }
            }
        }
        
        accessibilityCheckRunnable?.let { accessibilityCheckHandler.post(it) }
    }
    
    /**
     * 更新无障碍服务按钮状态
     */
    private fun updateAccessibilityButton(enabled: Boolean, text: String) {
        binding.btnOpenAccessibility.text = text
        binding.btnOpenAccessibility.isEnabled = enabled
        binding.btnOpenAccessibility.alpha = if (enabled) 1.0f else 0.5f
    }
    
    /**
     * 检查无障碍服务权限是否已授予（系统设置级别）
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = AccessibilityManagerCompat.getEnabledAccessibilityServiceList(
            accessibilityManager,
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == requireContext().packageName 
        }
    }
    
    /**
     * 打开无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            (activity as? MainActivity)?.addLog("已打开无障碍设置，请找到\"TopoClaw\"并开启")
            Toast.makeText(requireContext(), "请找到\"TopoClaw\"并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "打开无障碍设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "打开设置失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_MEDIA_PROJECTION_CHAT) {
            // 处理聊天截图权限
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                val intent = Intent(requireContext(), ChatScreenshotService::class.java).apply {
                    action = ChatScreenshotService.ACTION_START
                    putExtra(ChatScreenshotService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ChatScreenshotService.EXTRA_RESULT_DATA, data)
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
                }
                
                (activity as? MainActivity)?.addLog("聊天截图权限已授权，正在启动服务...")
                Toast.makeText(requireContext(), "聊天截图权限已授权", Toast.LENGTH_SHORT).show()
                
                // 延迟检查服务是否就绪（给服务一些时间初始化）
                view?.postDelayed({
                    val chatService = ChatScreenshotService.getInstance()
                    val isReady = chatService != null && chatService.isReady()
                    if (isReady) {
                        (activity as? MainActivity)?.addLog("✓ 聊天截图服务已就绪")
                    } else {
                        (activity as? MainActivity)?.addLog("⚠ 聊天截图服务未就绪，请检查日志")
                        Log.w(TAG, "服务启动后检查：chatService=${chatService != null}, isReady=${chatService?.isReady()}")
                    }
                    // 更新按钮状态
                    updateChatPermissionButton()
                }, 1000)
            } else {
                (activity as? MainActivity)?.addLog("聊天截图权限授权失败")
                Toast.makeText(requireContext(), "聊天截图权限授权失败", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // 处理悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(requireContext())) {
                    (activity as? MainActivity)?.addLog("悬浮窗权限已授权")
                    Toast.makeText(requireContext(), "悬浮窗权限已授权，正在初始化交互覆盖层...", Toast.LENGTH_SHORT).show()
                    // 重新初始化覆盖层
                    InteractionOverlayManager.initialize(requireContext())
                    updateOverlayPermissionButton()
                } else {
                    (activity as? MainActivity)?.addLog("悬浮窗权限授权失败")
                    Toast.makeText(requireContext(), "悬浮窗权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * 请求聊天截图权限
     */
    private fun requestChatScreenshotPermission() {
        val mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION_CHAT)
        (activity as? MainActivity)?.addLog("正在请求聊天截图权限...")
    }
    
    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                (activity as? MainActivity)?.addLog("正在请求悬浮窗权限...")
                Toast.makeText(requireContext(), "请在设置中开启'显示在其他应用的上层'权限", Toast.LENGTH_LONG).show()
            } else {
                (activity as? MainActivity)?.addLog("悬浮窗权限已授权")
                Toast.makeText(requireContext(), "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                // 重新初始化覆盖层
                InteractionOverlayManager.initialize(requireContext())
            }
        } else {
            (activity as? MainActivity)?.addLog("Android版本过低，不需要悬浮窗权限")
            Toast.makeText(requireContext(), "Android版本过低，不需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 更新悬浮窗权限按钮状态
     */
    private fun updateOverlayPermissionButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermission = Settings.canDrawOverlays(requireContext())
            if (hasPermission) {
                binding.btnRequestOverlayPermission.text = "授权悬浮窗权限"
                binding.btnRequestOverlayPermission.isEnabled = false
                // 设置为灰色表示已开启
                binding.btnRequestOverlayPermission.alpha = 0.5f
            } else {
                binding.btnRequestOverlayPermission.text = "授权悬浮窗权限"
                binding.btnRequestOverlayPermission.isEnabled = true
                binding.btnRequestOverlayPermission.alpha = 1.0f
            }
        } else {
            binding.btnRequestOverlayPermission.text = "授权悬浮窗权限"
            binding.btnRequestOverlayPermission.isEnabled = false
            binding.btnRequestOverlayPermission.alpha = 0.5f
        }
    }
    
    /**
     * 更新截图权限按钮状态
     */
    private fun updateChatPermissionButton() {
        val chatService = ChatScreenshotService.getInstance()
        val isReady = chatService != null && chatService.isReady()
        if (isReady) {
            binding.btnRequestChatPermission.text = "授权截图权限"
            binding.btnRequestChatPermission.isEnabled = false
            // 设置为灰色表示已开启
            binding.btnRequestChatPermission.alpha = 0.5f
        } else {
            binding.btnRequestChatPermission.text = "授权截图权限"
            binding.btnRequestChatPermission.isEnabled = true
            binding.btnRequestChatPermission.alpha = 1.0f
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 取消无障碍服务检查重试任务
        accessibilityCheckRunnable?.let { accessibilityCheckHandler.removeCallbacks(it) }
        accessibilityCheckRunnable = null
        _binding = null
    }
}

