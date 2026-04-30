package com.cloudcontrol.demo

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.cloudcontrol.demo.databinding.FragmentServiceSettingsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 服务设置Fragment
 * 提供云侧服务地址配置和各种功能开关
 */
class ServiceSettingsFragment : Fragment() {
    private data class AppOption(val appName: String, val packageName: String)
    
    companion object {
        private const val TAG = "ServiceSettingsFragment"
        private const val REQUEST_MEDIA_PROJECTION_CHAT = 1002
        private const val REQUEST_OVERLAY_PERMISSION = 1003
        private const val REQUEST_NOTIFICATION_PERMISSION = 1004
        private const val REQUEST_MICROPHONE_PERMISSION = 1005
        private const val REQUEST_LOCATION_PERMISSION = 1009
        private const val REQUEST_NETWORK_LOCATION_PERMISSION = 1010
        private val DEFAULT_SERVER_URL = ServiceUrlConfig.DEFAULT_SERVER_URL
        private const val MAX_HISTORY_SIZE = 20 // 最多保存20条历史地址
    }
    
    private var _binding: FragmentServiceSettingsBinding? = null
    private val binding get() = _binding!!
    
    // 服务器地址历史记录
    private val serverUrlHistory = mutableListOf<String>()
    
    // 保存PopupWindow引用
    private var popupWindow: PopupWindow? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServiceSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（服务设置页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 加载服务器地址历史记录
        loadServerUrlHistory()
        
        setupServiceUI()
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否已附加到Activity
        if (!isAdded || context == null) return
        
        // 确保ActionBar隐藏
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 服务页面不显示底部导航栏
            mainActivity.setBottomNavigationVisibility(false)
        }
        
        // 更新快捷设置按钮状态
        updateQuickSettingsButtons()
        refreshLocationMonitorLogs()
        
        checkAccessibilityService()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    /**
     * 设置服务UI
     */
    private fun setupServiceUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 加载保存的云侧服务地址
        loadSavedServerUrl()
        loadExtraServiceUrls()
        
        // 服务器地址下拉箭头按钮
        binding.btnServerUrlDropdown.setOnClickListener {
            showServerUrlMenu(it)
        }
        
        // 应用云侧服务地址按钮（用于聊天功能）
        binding.btnApplyServerUrl.setOnClickListener {
            applyServerUrl()
        }
        binding.btnApplyExtraServiceUrls.setOnClickListener {
            applyExtraServiceUrls()
        }
        
        // 加载并设置各种开关
        loadAllowFriendControlByDefaultSetting()
        loadOpenActionClearTopSetting()
        loadClearBackgroundBeforeTaskSetting()
        loadClearBackgroundBeforeRecordingSetting()
        loadTaskIndicatorOverlaySetting()
        loadCompanionModeSetting()
        loadSendBeforeActionImageSetting()
        loadBorderEffectSetting()
        loadGridActivationEffectSetting()
        loadReasoningOverlaySetting()
        loadShowKeyboardUISetting()
        loadActionCompletionDelaySetting()
        loadScreenshotCompressionRatioSetting()
        loadCloudApiReadTimeoutSetting()
        loadClipboardMonitorSetting()
        loadNotificationMonitorSetting()
        loadFetchInstalledAppsForCloudSetting()
        loadUseNextActionApiSetting()
        loadLocationMonitorTestSettings()
        
        // 设置开关监听器
        binding.switchAllowFriendControlByDefault.setOnCheckedChangeListener { _, isChecked ->
            saveAllowFriendControlByDefaultSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：默认允许好友控制手机" else "已关闭：默认允许好友控制手机")
        }
        
        binding.switchOpenActionClearTop.setOnCheckedChangeListener { _, isChecked ->
            saveOpenActionClearTopSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：打开应用时回到主页" else "已关闭：打开应用时保持当前页面")
        }
        
        binding.switchClearBackgroundBeforeTask.setOnCheckedChangeListener { _, isChecked ->
            saveClearBackgroundBeforeTaskSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：新任务开始前清除后台应用" else "已关闭：新任务开始前不清除后台应用")
        }
        
        binding.switchClearBackgroundBeforeRecording.setOnCheckedChangeListener { _, isChecked ->
            saveClearBackgroundBeforeRecordingSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：录制前清除后台应用" else "已关闭：录制前不清除后台应用")
        }
        
        binding.switchTaskIndicatorOverlay.setOnCheckedChangeListener { _, isChecked ->
            saveTaskIndicatorOverlaySetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：任务运行悬浮窗" else "已关闭：任务运行悬浮窗")
            if (!isChecked) {
                TaskIndicatorOverlayManager.hide()
            }
        }
        
        binding.switchCompanionMode.setOnCheckedChangeListener { _, isChecked ->
            saveCompanionModeSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：伴随模式" else "已关闭：伴随模式")
            if (isChecked) {
                TaskIndicatorOverlayManager.showCompanionMode(requireContext())
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    CompanionModeTutorialOverlayManager.show(requireContext())
                }, 500)
            } else {
                val mainActivity = activity as? MainActivity
                if (mainActivity?.getTaskRunningStatus() != true) {
                    TaskIndicatorOverlayManager.forceHide()
                }
                CompanionModeTutorialOverlayManager.hide()
            }
        }
        
        binding.switchSendBeforeActionImage.setOnCheckedChangeListener { _, isChecked ->
            saveSendBeforeActionImageSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：端侧变化检测" else "已关闭：端侧变化检测")
        }
        
        binding.switchBorderEffect.setOnCheckedChangeListener { _, isChecked ->
            saveBorderEffectSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：边框特效" else "已关闭：边框特效")
            if (!isChecked) {
                BorderEffectOverlayManager.hide()
            }
        }
        
        binding.switchGridActivationEffect.setOnCheckedChangeListener { _, isChecked ->
            saveGridActivationEffectSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：网格激活特效" else "已关闭：网格激活特效")
            if (!isChecked) {
                GridActivationOverlayManager.hide()
            }
        }
        
        binding.switchReasoningOverlay.setOnCheckedChangeListener { _, isChecked ->
            saveReasoningOverlaySetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：展示推理过程" else "已关闭：展示推理过程")
            if (!isChecked) {
                ReasoningOverlayManager.hide()
            }
        }
        
        binding.switchShowKeyboardUI.setOnCheckedChangeListener { _, isChecked ->
            saveShowKeyboardUISetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：显示键盘UI" else "已关闭：显示键盘UI（键盘功能仍可用）")
        }
        
        binding.switchClipboardMonitor.setOnCheckedChangeListener { _, isChecked ->
            saveClipboardMonitorSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：任务执行时监视剪切板" else "已关闭：任务执行时监视剪切板")
        }

        binding.switchNotificationMonitor.setOnCheckedChangeListener { _, isChecked ->
            saveNotificationMonitorSetting(isChecked)
            (activity as? MainActivity)?.addLog(
                if (isChecked) "已开启：监视通知栏（自动发给TopoClaw）" else "已关闭：监视通知栏"
            )
            if (isChecked && !NotificationMonitorService.isNotificationAccessEnabled(requireContext())) {
                openNotificationListenerSettings()
            }
        }
        
        binding.switchFetchInstalledAppsForCloud.setOnCheckedChangeListener { _, isChecked ->
            saveFetchInstalledAppsForCloudSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：任务开始时获取已安装应用并传给云侧" else "已关闭：不传递已安装应用列表")
        }
        
        binding.switchUseNextActionApi.setOnCheckedChangeListener { _, isChecked ->
            saveUseNextActionApiSetting(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：使用 next_action 接口（支持 install_apps）" else "已关闭：使用 upload 接口")
        }

        binding.switchLocationMonitorTestMode.setOnCheckedChangeListener { _, isChecked ->
            saveLocationMonitorTestModeSetting(isChecked)
            updateLocationMonitorTestStartAddressVisibility(isChecked)
            (activity as? MainActivity)?.addLog(
                if (isChecked) "已开启：地理位置变化监视测试模式" else "已关闭：地理位置变化监视测试模式"
            )
        }
        
        // 动作完成后延迟时间输入框监听器（失去焦点时保存）
        binding.etActionCompletionDelay.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveActionCompletionDelaySetting()
            }
        }

        binding.etScreenshotCompressionRatio.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveScreenshotCompressionRatioSetting()
            }
        }

        binding.etCloudApiReadTimeout.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveCloudApiReadTimeoutSetting()
            }
        }
        
        // 测试按钮
        binding.btnTest.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,  // enter: 测试页面从右侧滑入
                    R.anim.slide_out_to_left,    // exit: 服务页面向左滑出
                    R.anim.slide_in_from_left,   // popEnter: 返回时，服务页面从左侧滑入
                    R.anim.slide_out_to_right    // popExit: 返回时，测试页面向右滑出
                )
                .replace(R.id.fragmentContainer, TestFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        // 剪切板图片测试入口
        binding.btnClipboardImageTest.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,
                    R.anim.slide_out_to_left,
                    R.anim.slide_in_from_left,
                    R.anim.slide_out_to_right
                )
                .replace(R.id.fragmentContainer, ClipboardImageTestFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        
        // 轨迹采集设置按钮
        binding.btnTrajectorySettings.setOnClickListener {
            val trajectorySettingsFragment = TrajectorySettingsFragment()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,  // enter: 轨迹设置页面从右侧滑入
                    R.anim.slide_out_to_left,    // exit: 服务页面向左滑出
                    R.anim.slide_in_from_left,   // popEnter: 返回时，服务页面从左侧滑入
                    R.anim.slide_out_to_right    // popExit: 返回时，轨迹设置页面向右滑出
                )
                .replace(R.id.fragmentContainer, trajectorySettingsFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        // Deep Link 入口
        binding.btnDeepLink.setOnClickListener {
            val deepLinkFragment = DeepLinkFragment()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,
                    R.anim.slide_out_to_left,
                    R.anim.slide_in_from_left,
                    R.anim.slide_out_to_right
                )
                .replace(R.id.fragmentContainer, deepLinkFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }

        binding.btnGetCurrentLocation.setOnClickListener {
            requestCurrentLocationForTest()
        }

        binding.btnSaveLocationMonitorTestStartAddress.setOnClickListener {
            saveLocationMonitorTestStartAddress()
        }
        binding.btnTriggerLocationMonitorCheckNow.setOnClickListener {
            triggerLocationMonitorCheckNow()
        }
        binding.btnRefreshLocationMonitorLogs.setOnClickListener {
            refreshLocationMonitorLogs()
        }
        binding.btnClearLocationMonitorLogs.setOnClickListener {
            clearLocationMonitorLogs()
        }
        
        // 已安装应用包名入口
        binding.btnInstalledPackages.setOnClickListener {
            showInstalledPackagesDialog()
        }

        // 通知监视白名单设置入口
        binding.btnNotificationMonitorExcludedApps.setOnClickListener {
            showNotificationMonitorExcludedAppsDialog()
        }
        
        // 设置快捷设置按钮
        setupQuickSettings()
        
        // 检查无障碍服务
        checkAccessibilityService()
    }
    
    private fun loadSavedServerUrl() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("chat_server_url", ServiceUrlConfig.DEFAULT_SERVER_URL)
        binding.etServerUrl.setText(savedUrl)
    }

    private fun loadExtraServiceUrls() {
        val context = requireContext()
        binding.etCustomerServiceUrl.setText(ServiceUrlConfig.getCustomerServiceUrl(context))
        binding.etChatAssistantUrl.setText(ServiceUrlConfig.getChatAssistantUrl(context))
        binding.etSkillCommunityUrl.setText(ServiceUrlConfig.getSkillCommunityUrl(context))
    }
    
    private fun loadAllowFriendControlByDefaultSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("allow_friend_control_by_default", false) // 默认false（关闭）
        binding.switchAllowFriendControlByDefault.isChecked = enabled
    }

    private fun loadLocationMonitorTestSettings() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("location_change_test_mode_enabled", false)
        val startAddress = prefs.getString("location_change_test_start_address", "") ?: ""
        binding.switchLocationMonitorTestMode.isChecked = enabled
        binding.etLocationMonitorTestStartAddress.setText(startAddress)
        updateLocationMonitorTestStartAddressVisibility(enabled)
        refreshLocationMonitorLogs()
    }
    
    private fun saveAllowFriendControlByDefaultSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_friend_control_by_default", isChecked).apply()
    }

    private fun saveLocationMonitorTestModeSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("location_change_test_mode_enabled", isChecked).apply()
    }

    private fun saveLocationMonitorTestStartAddress() {
        val address = binding.etLocationMonitorTestStartAddress.text?.toString()?.trim().orEmpty()
        if (address.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.location_monitor_test_start_address_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("location_change_test_start_address", address)
            .apply()
        Toast.makeText(requireContext(), getString(R.string.location_monitor_test_start_address_saved), Toast.LENGTH_SHORT).show()
    }

    private fun triggerLocationMonitorCheckNow() {
        val mainActivity = activity as? MainActivity
        val ok = mainActivity?.triggerLocationMonitorCheckNow { result ->
            val prefix = if (result.sent) "检测完成" else "检测未触发"
            val finalMsg = "$prefix：${result.reason}"
            Toast.makeText(requireContext(), finalMsg, Toast.LENGTH_LONG).show()
            mainActivity.addLog("位置监视立即检测结果：$finalMsg")
        } == true
        if (ok) {
            Toast.makeText(requireContext(), getString(R.string.location_monitor_check_now_sent), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), getString(R.string.location_monitor_check_now_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLocationMonitorTestStartAddressVisibility(enabled: Boolean) {
        binding.llLocationMonitorTestStartAddress.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun refreshLocationMonitorLogs() {
        val logs = LocationMonitorManager.getDebugLogs(requireContext())
        binding.tvLocationMonitorLogs.text = if (logs.isEmpty()) {
            getString(R.string.location_monitor_logs_empty)
        } else {
            logs.asReversed().joinToString("\n")
        }
    }

    private fun clearLocationMonitorLogs() {
        LocationMonitorManager.clearDebugLogs(requireContext())
        refreshLocationMonitorLogs()
        Toast.makeText(requireContext(), getString(R.string.location_monitor_logs_cleared), Toast.LENGTH_SHORT).show()
    }
    
    private fun loadOpenActionClearTopSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val clearTop = prefs.getBoolean("open_action_clear_top", false)
        binding.switchOpenActionClearTop.isChecked = clearTop
    }
    
    private fun saveOpenActionClearTopSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("open_action_clear_top", isChecked).apply()
    }
    
    private fun loadClearBackgroundBeforeTaskSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val clearBackground = prefs.getBoolean("clear_background_before_task", false)
        binding.switchClearBackgroundBeforeTask.isChecked = clearBackground
    }
    
    private fun saveClearBackgroundBeforeTaskSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("clear_background_before_task", isChecked).apply()
    }
    
    private fun loadClearBackgroundBeforeRecordingSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val clearBackground = prefs.getBoolean("clear_background_before_recording", false)
        binding.switchClearBackgroundBeforeRecording.isChecked = clearBackground
    }
    
    private fun saveClearBackgroundBeforeRecordingSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("clear_background_before_recording", isChecked).apply()
    }
    
    private fun loadTaskIndicatorOverlaySetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("task_indicator_overlay_enabled", true)
        binding.switchTaskIndicatorOverlay.isChecked = enabled
    }
    
    private fun saveTaskIndicatorOverlaySetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("task_indicator_overlay_enabled", isChecked).apply()
    }
    
    private fun loadCompanionModeSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("companion_mode_enabled", true)
        binding.switchCompanionMode.isChecked = enabled
    }
    
    private fun saveCompanionModeSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("companion_mode_enabled", isChecked).apply()
    }
    
    private fun loadSendBeforeActionImageSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("send_before_action_image", false)
        binding.switchSendBeforeActionImage.isChecked = enabled
    }
    
    private fun saveSendBeforeActionImageSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("send_before_action_image", isChecked).apply()
    }
    
    private fun loadBorderEffectSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("border_effect_enabled", false)
        binding.switchBorderEffect.isChecked = enabled
    }
    
    private fun saveBorderEffectSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("border_effect_enabled", isChecked).apply()
    }
    
    private fun loadGridActivationEffectSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("grid_activation_effect_enabled", false)
        binding.switchGridActivationEffect.isChecked = enabled
    }
    
    private fun saveGridActivationEffectSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("grid_activation_effect_enabled", isChecked).apply()
    }
    
    private fun loadReasoningOverlaySetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("reasoning_overlay_enabled", true)
        binding.switchReasoningOverlay.isChecked = enabled
    }
    
    private fun saveReasoningOverlaySetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("reasoning_overlay_enabled", isChecked).apply()
    }
    
    private fun loadShowKeyboardUISetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("show_keyboard_ui", true)  // 默认开启
        binding.switchShowKeyboardUI.isChecked = enabled
    }
    
    private fun saveShowKeyboardUISetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_keyboard_ui", isChecked).apply()
    }
    
    private fun loadActionCompletionDelaySetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val delayMs = prefs.getInt("action_completion_delay_ms", 500)  // 默认500ms
        binding.etActionCompletionDelay.setText(delayMs.toString())
    }
    
    private fun loadCloudApiReadTimeoutSetting() {
        val sec = CloudApiTimeoutPrefs.getReadTimeoutSeconds(requireContext())
        binding.etCloudApiReadTimeout.setText(sec.toString())
    }

    private fun saveCloudApiReadTimeoutSetting() {
        val raw = binding.etCloudApiReadTimeout.text.toString().trim()
        if (raw.isEmpty()) {
            CloudApiTimeoutPrefs.putReadTimeoutSeconds(requireContext(), CloudApiTimeoutPrefs.DEFAULT_READ_SEC)
            loadCloudApiReadTimeoutSetting()
            reapplyNetworkServiceWithCurrentServerUrl()
            (activity as? MainActivity)?.addLog("云侧 API 读取超时已恢复默认：${CloudApiTimeoutPrefs.DEFAULT_READ_SEC} 秒")
            return
        }
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            Toast.makeText(requireContext(), "请输入有效整数（秒）", Toast.LENGTH_SHORT).show()
            loadCloudApiReadTimeoutSetting()
            return
        }
        if (parsed < 10 || parsed > 600) {
            Toast.makeText(requireContext(), "请输入 10～600 之间的秒数", Toast.LENGTH_SHORT).show()
            loadCloudApiReadTimeoutSetting()
            return
        }
        CloudApiTimeoutPrefs.putReadTimeoutSeconds(requireContext(), parsed)
        loadCloudApiReadTimeoutSetting()
        reapplyNetworkServiceWithCurrentServerUrl()
        (activity as? MainActivity)?.addLog("云侧 API 读取超时已设为：${CloudApiTimeoutPrefs.getReadTimeoutSeconds(requireContext())} 秒")
    }

    private fun reapplyNetworkServiceWithCurrentServerUrl() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("chat_server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        if (serverUrl.isNotBlank()) {
            NetworkService.initialize(serverUrl, requireContext())
        }
    }

    private fun loadScreenshotCompressionRatioSetting() {
        val r = ScreenshotCompressionPrefs.getRatio(requireContext())
        binding.etScreenshotCompressionRatio.setText(String.format(java.util.Locale.US, "%.4f", r).trimEnd('0').trimEnd('.').ifEmpty { "0" })
    }

    private fun saveScreenshotCompressionRatioSetting() {
        val raw = binding.etScreenshotCompressionRatio.text.toString().trim().replace(',', '.')
        if (raw.isEmpty()) {
            ScreenshotCompressionPrefs.putRatio(requireContext(), ScreenshotCompressionPrefs.DEFAULT_RATIO)
            loadScreenshotCompressionRatioSetting()
            (activity as? MainActivity)?.addLog("截图上云缩放系数已恢复默认：${ScreenshotCompressionPrefs.DEFAULT_RATIO}")
            return
        }
        val parsed = raw.toFloatOrNull()
        if (parsed == null) {
            Toast.makeText(requireContext(), "请输入有效数字", Toast.LENGTH_SHORT).show()
            loadScreenshotCompressionRatioSetting()
            return
        }
        if (parsed < 0.05f || parsed > 1.0f) {
            Toast.makeText(requireContext(), "请输入 0.05～1.0 之间的数", Toast.LENGTH_SHORT).show()
            loadScreenshotCompressionRatioSetting()
            return
        }
        ScreenshotCompressionPrefs.putRatio(requireContext(), parsed)
        loadScreenshotCompressionRatioSetting()
        (activity as? MainActivity)?.addLog("截图上云缩放系数已设为：${ScreenshotCompressionPrefs.getRatio(requireContext())}")
    }

    private fun saveActionCompletionDelaySetting() {
        val delayText = binding.etActionCompletionDelay.text.toString().trim()
        if (delayText.isEmpty()) {
            // 如果为空，使用默认值
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("action_completion_delay_ms", 500).apply()
            binding.etActionCompletionDelay.setText("500")
            return
        }
        
        try {
            val delayMs = delayText.toInt()
            // 验证范围：0-5000ms
            val validDelayMs = when {
                delayMs < 0 -> {
                    Toast.makeText(requireContext(), "延迟时间不能小于0，已设置为0", Toast.LENGTH_SHORT).show()
                    0
                }
                delayMs > 5000 -> {
                    Toast.makeText(requireContext(), "延迟时间不能大于5000ms，已设置为5000", Toast.LENGTH_SHORT).show()
                    5000
                }
                else -> delayMs
            }
            
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("action_completion_delay_ms", validDelayMs).apply()
            if (validDelayMs != delayMs) {
                binding.etActionCompletionDelay.setText(validDelayMs.toString())
            }
            (activity as? MainActivity)?.addLog("动作完成后延迟时间已设置为：${validDelayMs}ms")
        } catch (e: NumberFormatException) {
            Toast.makeText(requireContext(), "请输入有效的数字", Toast.LENGTH_SHORT).show()
            // 恢复为默认值
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val defaultDelay = prefs.getInt("action_completion_delay_ms", 500)
            binding.etActionCompletionDelay.setText(defaultDelay.toString())
        }
    }
    
    /**
     * 加载"任务执行时监视剪切板"设置
     */
    private fun loadClipboardMonitorSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("clipboard_monitor_enabled", false) // 默认false（关闭）
        binding.switchClipboardMonitor.isChecked = enabled
    }
    
    /**
     * 保存"任务执行时监视剪切板"设置
     */
    private fun saveClipboardMonitorSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("clipboard_monitor_enabled", isChecked).apply()
    }

    /**
     * 加载"监视通知栏"设置
     */
    private fun loadNotificationMonitorSetting() {
        binding.switchNotificationMonitor.isChecked =
            NotificationMonitorService.isEnabled(requireContext())
    }

    /**
     * 保存"监视通知栏"设置
     */
    private fun saveNotificationMonitorSetting(isChecked: Boolean) {
        NotificationMonitorService.setEnabled(requireContext(), isChecked)
    }

    /**
     * 打开通知使用权设置页（通知监听权限）
     */
    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            Toast.makeText(requireContext(), "请在系统设置中为 TopoClaw 开启通知使用权", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "打开通知使用权设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "无法打开通知使用权设置", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 加载"是否获取已安装应用"设置
     */
    private fun loadFetchInstalledAppsForCloudSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("fetch_installed_apps_for_cloud", false)
        binding.switchFetchInstalledAppsForCloud.isChecked = enabled
    }
    
    /**
     * 保存"是否获取已安装应用"设置
     */
    private fun saveFetchInstalledAppsForCloudSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("fetch_installed_apps_for_cloud", isChecked).apply()
    }
    
    /**
     * 加载"切换到 next_action 接口"设置
     */
    private fun loadUseNextActionApiSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("use_next_action_api", false)
        binding.switchUseNextActionApi.isChecked = enabled
    }
    
    /**
     * 保存"切换到 next_action 接口"设置
     */
    private fun saveUseNextActionApiSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("use_next_action_api", isChecked).apply()
    }
    
    private fun applyServerUrl() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请输入云侧服务地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("chat_server_url", serverUrl).apply()
        
        // 添加到历史记录（排除默认地址，避免重复）
        if (serverUrl != DEFAULT_SERVER_URL) {
            addServerUrlToHistory(serverUrl)
        }
        
        NetworkService.initialize(serverUrl, requireContext())
        
        (activity as? MainActivity)?.addLog("已更新云侧服务地址: $serverUrl")
        Toast.makeText(requireContext(), "云侧服务地址已更新", Toast.LENGTH_SHORT).show()
    }

    private fun applyExtraServiceUrls() {
        val customerServiceUrl = binding.etCustomerServiceUrl.text?.toString()?.trim().orEmpty()
        val chatAssistantUrl = binding.etChatAssistantUrl.text?.toString()?.trim().orEmpty()
        val skillCommunityUrl = binding.etSkillCommunityUrl.text?.toString()?.trim().orEmpty()

        val context = requireContext()
        ServiceUrlConfig.setCustomerServiceUrl(context, customerServiceUrl)
        ServiceUrlConfig.setChatAssistantUrl(context, chatAssistantUrl)
        ServiceUrlConfig.setSkillCommunityUrl(context, skillCommunityUrl)

        // 立即生效：客服网络地址重建
        CustomerServiceNetwork.initialize(ServiceUrlConfig.getCustomerServiceUrl(context))

        loadExtraServiceUrls()
        (activity as? MainActivity)?.addLog(
            "已更新扩展服务地址：客服=${ServiceUrlConfig.getCustomerServiceUrl(context)}，聊天助手=${ServiceUrlConfig.getChatAssistantUrl(context)}，技能社区=${ServiceUrlConfig.getSkillCommunityUrl(context)}"
        )
        Toast.makeText(requireContext(), "扩展服务地址已更新", Toast.LENGTH_SHORT).show()
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
     * 设置快捷设置按钮
     */
    private fun setupQuickSettings() {
        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        
        binding.btnRequestChatPermission.setOnClickListener {
            requestChatScreenshotPermission()
        }
        
        binding.btnRequestOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }
        
        binding.btnRequestNotificationPermission.setOnClickListener {
            requestNotificationPermission()
        }
        
        binding.btnRequestMicrophonePermission.setOnClickListener {
            requestMicrophonePermission()
        }
        binding.btnRequestNetworkLocationPermission.setOnClickListener {
            requestNetworkLocationPermission()
        }
        
        // 更新按钮状态
        updateQuickSettingsButtons()
    }
    
    /**
     * 更新快捷设置按钮状态
     */
    private fun updateQuickSettingsButtons() {
        // 更新无障碍服务按钮
        val isAccessibilityEnabled = (activity as? MainActivity)?.isAccessibilityServiceEnabled() ?: false
        binding.btnOpenAccessibility.isEnabled = !isAccessibilityEnabled
        binding.btnOpenAccessibility.alpha = if (isAccessibilityEnabled) 0.5f else 1.0f
        
        // 更新截图权限按钮
        val chatService = ChatScreenshotService.getInstance()
        val isScreenshotReady = chatService != null && chatService.isReady()
        binding.btnRequestChatPermission.isEnabled = !isScreenshotReady
        binding.btnRequestChatPermission.alpha = if (isScreenshotReady) 0.5f else 1.0f
        
        // 更新悬浮窗权限按钮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasOverlayPermission = Settings.canDrawOverlays(requireContext())
            binding.btnRequestOverlayPermission.isEnabled = !hasOverlayPermission
            binding.btnRequestOverlayPermission.alpha = if (hasOverlayPermission) 0.5f else 1.0f
        } else {
            binding.btnRequestOverlayPermission.isEnabled = false
            binding.btnRequestOverlayPermission.alpha = 0.5f
        }
        
        // 更新通知权限按钮
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.areNotificationsEnabled() ?: false
        }
        binding.btnRequestNotificationPermission.isEnabled = !hasNotificationPermission
        binding.btnRequestNotificationPermission.alpha = if (hasNotificationPermission) 0.5f else 1.0f
        
        // 更新麦克风权限按钮
        val hasMicrophonePermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        binding.btnRequestMicrophonePermission.isEnabled = !hasMicrophonePermission
        binding.btnRequestMicrophonePermission.alpha = if (hasMicrophonePermission) 0.5f else 1.0f

        // 更新 NETWORK 定位权限按钮（仅 coarse）
        val hasNetworkLocationPermission =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        binding.btnRequestNetworkLocationPermission.isEnabled = !hasNetworkLocationPermission
        binding.btnRequestNetworkLocationPermission.alpha = if (hasNetworkLocationPermission) 0.5f else 1.0f
    }
    
    /**
     * 显示已安装桌面应用包名弹窗：仅获取桌面有图标的应用，展示在弹窗中，支持一键复制
     */
    private fun showInstalledPackagesDialog() {
        val loadingDialog = android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.installed_packages_loading)
            .setCancelable(false)
            .create()
        loadingDialog.show()
        
        Thread {
            try {
                val pm = requireContext().packageManager
                // 仅获取桌面应用（有启动图标的），非桌面应用直接排除
                val resolveInfos = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
                val displayItems = resolveInfos
                    .distinctBy { it.activityInfo.packageName }
                    .map { resolveInfo ->
                        val pkgName = resolveInfo.activityInfo.packageName
                        val appName = try {
                            resolveInfo.activityInfo.applicationInfo.loadLabel(pm).toString().trim()
                        } catch (e: Exception) {
                            ""
                        }
                        if (appName.isNotEmpty()) "$appName ($pkgName)" else pkgName
                    }
                    .sortedBy { it.lowercase() }
                
                requireActivity().runOnUiThread {
                    loadingDialog.dismiss()
                    if (!isAdded) return@runOnUiThread
                    showInstalledPackagesResultDialog(displayItems)
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取已安装应用列表失败: ${e.message}", e)
                requireActivity().runOnUiThread {
                    loadingDialog.dismiss()
                    if (isAdded) {
                        Toast.makeText(requireContext(), "获取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }
    
    /**
     * 展示获取到的桌面应用列表弹窗（格式：应用名 (包名)），含一键复制按钮
     */
    private fun showInstalledPackagesResultDialog(displayItems: List<String>) {
        val contentView = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
        }
        
        val countText = android.widget.TextView(requireContext()).apply {
            text = getString(R.string.installed_packages_count, displayItems.size)
            textSize = 14f
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }
        contentView.addView(countText)
        
        val listView = ListView(requireContext())
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, displayItems)
        listView.adapter = adapter
        
        val itemHeight = (48 * resources.displayMetrics.density).toInt()
        val maxHeight = itemHeight * 8
        val listHeight = minOf(displayItems.size * itemHeight, maxHeight)
        listView.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            listHeight
        )
        contentView.addView(listView)
        
        val copyButton = Button(requireContext()).apply {
            text = getString(R.string.copy_all)
            setOnClickListener {
                val text = displayItems.joinToString("\n")
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("installed_packages", text))
                Toast.makeText(requireContext(), R.string.copy_success, Toast.LENGTH_SHORT).show()
            }
        }
        val copyButtonParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (16 * resources.displayMetrics.density).toInt()
        }
        contentView.addView(copyButton, copyButtonParams)
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.installed_packages_title)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 弹窗设置通知监视白名单：展示本机桌面应用并支持多选。
     * 仅选中的应用会被通知监视器处理。
     */
    private fun showNotificationMonitorExcludedAppsDialog() {
        val loadingDialog = android.app.AlertDialog.Builder(requireContext())
            .setMessage(R.string.notification_monitor_excluded_apps_loading)
            .setCancelable(false)
            .create()
        loadingDialog.show()

        Thread {
            try {
                val pm = requireContext().packageManager
                val resolveInfos = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )

                val appOptions = resolveInfos
                    .distinctBy { it.activityInfo.packageName }
                    .map { resolveInfo ->
                        val pkgName = resolveInfo.activityInfo.packageName
                        val appName = try {
                            resolveInfo.activityInfo.applicationInfo.loadLabel(pm).toString().trim()
                        } catch (_: Exception) {
                            pkgName
                        }
                        AppOption(
                            appName = if (appName.isBlank()) pkgName else appName,
                            packageName = pkgName
                        )
                    }
                    .sortedWith(compareBy<AppOption> { it.appName.lowercase() }.thenBy { it.packageName })

                requireActivity().runOnUiThread {
                    loadingDialog.dismiss()
                    if (!isAdded) return@runOnUiThread
                    showNotificationMonitorExcludedAppsSelectionDialog(
                        appOptions = appOptions,
                        selectedPackages = NotificationMonitorService.getWhitelistedPackages(requireContext()).toMutableSet()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取通知监视白名单应用列表失败: ${e.message}", e)
                requireActivity().runOnUiThread {
                    loadingDialog.dismiss()
                    if (isAdded) {
                        Toast.makeText(requireContext(), "获取失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showNotificationMonitorExcludedAppsSelectionDialog(
        appOptions: List<AppOption>,
        selectedPackages: MutableSet<String>
    ) {
        val context = requireContext()
        var filteredOptions = appOptions

        val density = resources.displayMetrics.density
        val rootContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }

        val searchInput = EditText(context).apply {
            hint = getString(R.string.notification_monitor_whitelist_search_hint)
            setSingleLine(true)
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        rootContainer.addView(
            searchInput,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        )

        val listView = ListView(context).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            dividerHeight = (1 * density).toInt()
        }
        rootContainer.addView(
            listView,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (420 * density).toInt()
            )
        )

        fun renderList() {
            val labels = filteredOptions.map { "${it.appName} (${it.packageName})" }
            val adapter = ArrayAdapter(
                context,
                android.R.layout.simple_list_item_multiple_choice,
                labels
            )
            listView.adapter = adapter
            filteredOptions.forEachIndexed { index, option ->
                listView.setItemChecked(index, selectedPackages.contains(option.packageName))
            }
        }

        renderList()

        listView.setOnItemClickListener { _, _, position, _ ->
            val option = filteredOptions.getOrNull(position) ?: return@setOnItemClickListener
            if (listView.isItemChecked(position)) {
                selectedPackages.add(option.packageName)
            } else {
                selectedPackages.remove(option.packageName)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString()?.trim().orEmpty()
                filteredOptions = if (keyword.isEmpty()) {
                    appOptions
                } else {
                    appOptions.filter {
                        it.appName.contains(keyword, ignoreCase = true) ||
                            it.packageName.contains(keyword, ignoreCase = true)
                    }
                }
                renderList()
            }
        })

        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.notification_monitor_excluded_apps_title)
            .setView(rootContainer)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                NotificationMonitorService.setWhitelistedPackages(context, selectedPackages)
                Toast.makeText(context, R.string.notification_monitor_excluded_apps_saved, Toast.LENGTH_SHORT).show()
                (activity as? MainActivity)?.addLog("通知监视白名单已更新，共${selectedPackages.size}个应用")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    private fun checkAccessibilityService() {
        val hasPermission = isAccessibilityServiceEnabled()
        val serviceConnected = MyAccessibilityService.getInstance() != null
        
        // 按钮已移至 ProfileFragment，不再需要更新按钮状态
        // when {
        //     hasPermission && serviceConnected -> {
        //         updateAccessibilityButton(enabled = false, text = "无障碍服务已开启")
        //     }
        //     hasPermission && !serviceConnected -> {
        //         updateAccessibilityButton(enabled = false, text = "正在连接...")
        //     }
        //     else -> {
        //         updateAccessibilityButton(enabled = true, text = "开启无障碍服务")
        //     }
        // }
    }
    
    // 按钮已移至 ProfileFragment，此方法不再需要
    // private fun updateAccessibilityButton(enabled: Boolean, text: String) {
    //     binding.btnOpenAccessibility.text = text
    //     binding.btnOpenAccessibility.isEnabled = enabled
    //     binding.btnOpenAccessibility.alpha = if (enabled) 1.0f else 0.5f
    // }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = androidx.core.view.accessibility.AccessibilityManagerCompat.getEnabledAccessibilityServiceList(
            accessibilityManager,
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == requireContext().packageName 
        }
    }
    
    private fun openAccessibilitySettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            (activity as? MainActivity)?.addLog("已打开无障碍设置，请找到\"TopoClaw\"并开启")
            Toast.makeText(requireContext(), "请找到\"TopoClaw\"并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "打开无障碍设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "打开设置失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun requestChatScreenshotPermission() {
        val mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION_CHAT)
        (activity as? MainActivity)?.addLog("正在请求聊天截图权限...")
    }
    
    private fun requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(requireContext())) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                (activity as? MainActivity)?.addLog("正在请求悬浮窗权限...")
                Toast.makeText(requireContext(), "请在设置中开启'显示在其他应用的上层'权限", Toast.LENGTH_LONG).show()
            } else {
                (activity as? MainActivity)?.addLog("悬浮窗权限已授权")
                Toast.makeText(requireContext(), "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                InteractionOverlayManager.initialize(requireContext())
            }
        } else {
            (activity as? MainActivity)?.addLog("Android版本过低，不需要悬浮窗权限")
            Toast.makeText(requireContext(), "Android版本过低，不需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 按钮已移至 ProfileFragment，此方法不再需要
    // private fun updateOverlayPermissionButton() {
    //     if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
    //         val hasPermission = android.provider.Settings.canDrawOverlays(requireContext())
    //         if (hasPermission) {
    //             binding.btnRequestOverlayPermission.text = "授权悬浮窗权限"
    //             binding.btnRequestOverlayPermission.isEnabled = false
    //             binding.btnRequestOverlayPermission.alpha = 0.5f
    //         } else {
    //             binding.btnRequestOverlayPermission.text = "授权悬浮窗权限"
    //             binding.btnRequestOverlayPermission.isEnabled = true
    //             binding.btnRequestOverlayPermission.alpha = 1.0f
    //         }
    //     } else {
    //         binding.btnRequestOverlayPermission.text = "授权悬浮窗权限"
    //         binding.btnRequestOverlayPermission.isEnabled = false
    //         binding.btnRequestOverlayPermission.alpha = 0.5f
    //     }
    // }
    
    // 按钮已移至 ProfileFragment，此方法不再需要
    // private fun updateChatPermissionButton() {
    //     val chatService = ChatScreenshotService.getInstance()
    //     val isReady = chatService != null && chatService.isReady()
    //     if (isReady) {
    //         binding.btnRequestChatPermission.text = "授权截图权限"
    //         binding.btnRequestChatPermission.isEnabled = false
    //         binding.btnRequestChatPermission.alpha = 0.5f
    //     } else {
    //         binding.btnRequestChatPermission.text = "授权截图权限"
    //         binding.btnRequestChatPermission.isEnabled = true
    //         binding.btnRequestChatPermission.alpha = 1.0f
    //     }
    // }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) 
                == PackageManager.PERMISSION_GRANTED) {
                (activity as? MainActivity)?.addLog("通知权限已授权")
                Toast.makeText(requireContext(), "通知权限已授权", Toast.LENGTH_SHORT).show()
                // 更新快捷设置按钮状态
                updateQuickSettingsButtons()
            } else {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
                (activity as? MainActivity)?.addLog("正在请求通知权限...")
            }
        } else {
            // Android 12及以下，跳转到应用通知设置
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
                startActivity(intent)
                (activity as? MainActivity)?.addLog("已打开通知设置页面")
                Toast.makeText(requireContext(), "请在设置中开启通知权限", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "打开通知设置失败: ${e.message}", e)
                Toast.makeText(requireContext(), "无法打开通知设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            (activity as? MainActivity)?.addLog("麦克风权限已授权")
            Toast.makeText(requireContext(), "麦克风权限已授权", Toast.LENGTH_SHORT).show()
            // 更新快捷设置按钮状态
            updateQuickSettingsButtons()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE_PERMISSION)
            (activity as? MainActivity)?.addLog("正在请求麦克风权限...")
            Toast.makeText(requireContext(), "请在弹窗中授予麦克风权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNetworkLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            (activity as? MainActivity)?.addLog("地理位置权限已授权")
            Toast.makeText(requireContext(), "地理位置权限已授权", Toast.LENGTH_SHORT).show()
            updateQuickSettingsButtons()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_NETWORK_LOCATION_PERMISSION)
            (activity as? MainActivity)?.addLog("正在请求地理位置权限...")
            Toast.makeText(requireContext(), "请在弹窗中授予地理位置权限", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestCurrentLocationForTest() {
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            Toast.makeText(requireContext(), getString(R.string.location_permission_required), Toast.LENGTH_SHORT).show()
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }
        fetchCurrentLocationForTest()
    }

    private fun fetchCurrentLocationForTest() {
        Toast.makeText(requireContext(), getString(R.string.location_fetching), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val location = getCurrentLocationCompat(timeoutMs = 10_000L, requiresFine = false)
            if (!isAdded) return@launch
            if (location == null) {
                Toast.makeText(requireContext(), getString(R.string.location_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val message = getString(
                R.string.location_result_content,
                location.latitude,
                location.longitude,
                location.accuracy.toDouble(),
                location.provider ?: "unknown"
            )
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.location_result_title))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            (activity as? MainActivity)?.addLog(
                "定位测试成功: lat=${location.latitude}, lng=${location.longitude}, provider=${location.provider ?: "unknown"}"
            )
        }
    }

    private suspend fun getCurrentLocationCompat(timeoutMs: Long, requiresFine: Boolean): Location? {
        val context = requireContext()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = mutableListOf<String>()
        val gpsEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)

        if (requiresFine && gpsEnabled) providers.add(LocationManager.GPS_PROVIDER)
        if (networkEnabled) providers.add(LocationManager.NETWORK_PROVIDER)
        if (!requiresFine && gpsEnabled) providers.add(LocationManager.GPS_PROVIDER)
        if (providers.isEmpty()) return null

        val provider = providers.first()
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var completed = false
            var listenerRef: LocationListener? = null

            fun complete(location: Location?) {
                if (completed) return
                completed = true
                listenerRef?.let {
                    runCatching { locationManager.removeUpdates(it) }
                }
                handler.removeCallbacksAndMessages(null)
                if (cont.isActive) cont.resume(location)
            }

            handler.postDelayed({ complete(null) }, timeoutMs)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val executor = ContextCompat.getMainExecutor(context)
                runCatching {
                    locationManager.getCurrentLocation(provider, null, executor) { loc ->
                        complete(loc)
                    }
                }.onFailure {
                    complete(null)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        complete(location)
                    }
                }
                listenerRef = listener
                runCatching {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure {
                    complete(null)
                }
            }

            cont.invokeOnCancellation {
                listenerRef?.let { listener ->
                    runCatching { locationManager.removeUpdates(listener) }
                }
                handler.removeCallbacksAndMessages(null)
            }
        }
    }
    
    // 按钮已移至 ProfileFragment，此方法不再需要
    // private fun updateNotificationPermissionButton() {
    //     val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    //         ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    //     } else {
    //         // Android 12及以下，检查通知是否启用
    //         val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    //         notificationManager?.areNotificationsEnabled() ?: false
    //     }
    //     
    //     if (hasPermission) {
    //         binding.btnRequestNotificationPermission.text = "授权通知权限"
    //         binding.btnRequestNotificationPermission.isEnabled = false
    //         binding.btnRequestNotificationPermission.alpha = 0.5f
    //     } else {
    //         binding.btnRequestNotificationPermission.text = "授权通知权限"
    //         binding.btnRequestNotificationPermission.isEnabled = true
    //         binding.btnRequestNotificationPermission.alpha = 1.0f
    //     }
    // }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                (activity as? MainActivity)?.addLog("通知权限已授权")
                Toast.makeText(requireContext(), "通知权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                (activity as? MainActivity)?.addLog("通知权限授权失败")
                Toast.makeText(requireContext(), "通知权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
            }
            // 更新快捷设置按钮状态
            updateQuickSettingsButtons()
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                fetchCurrentLocationForTest()
            } else {
                Toast.makeText(requireContext(), getString(R.string.location_permission_required), Toast.LENGTH_SHORT).show()
            }
            updateQuickSettingsButtons()
        } else if (requestCode == REQUEST_NETWORK_LOCATION_PERMISSION) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                (activity as? MainActivity)?.addLog("地理位置权限已授权")
                Toast.makeText(requireContext(), "地理位置权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                (activity as? MainActivity)?.addLog("地理位置权限授权失败")
                Toast.makeText(requireContext(), "地理位置权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
            }
            updateQuickSettingsButtons()
        } else if (requestCode == REQUEST_MICROPHONE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                (activity as? MainActivity)?.addLog("麦克风权限已授权")
                Toast.makeText(requireContext(), "麦克风权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                (activity as? MainActivity)?.addLog("麦克风权限授权失败")
                Toast.makeText(requireContext(), "麦克风权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
            }
            // 更新快捷设置按钮状态
            updateQuickSettingsButtons()
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
                    // 更新快捷设置按钮状态
                    updateQuickSettingsButtons()
                }, 1000)
            } else {
                (activity as? MainActivity)?.addLog("聊天截图权限授权失败")
                Toast.makeText(requireContext(), "聊天截图权限授权失败", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            // 处理悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.canDrawOverlays(requireContext())) {
                    (activity as? MainActivity)?.addLog("悬浮窗权限已授权")
                    Toast.makeText(requireContext(), "悬浮窗权限已授权，正在初始化交互覆盖层...", Toast.LENGTH_SHORT).show()
                    // 重新初始化覆盖层
                    InteractionOverlayManager.initialize(requireContext())
                    // 更新快捷设置按钮状态
                    updateQuickSettingsButtons()
                } else {
                    (activity as? MainActivity)?.addLog("悬浮窗权限授权失败")
                    Toast.makeText(requireContext(), "悬浮窗权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

