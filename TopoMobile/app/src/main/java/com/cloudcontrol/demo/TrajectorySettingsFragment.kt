package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentTrajectorySettingsBinding
import kotlinx.coroutines.*

/**
 * 轨迹采集设置Fragment
 * 提供轨迹采集相关的设置入口
 */
class TrajectorySettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "TrajectorySettingsFragment"
        private const val REQUEST_MEDIA_PROJECTION_TRAJECTORY = 3101
    }
    
    private var _binding: FragmentTrajectorySettingsBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 记录键盘高度的原始值，用于判断是否真的改变了
    private var originalKeyboardHeight: Int = 1480
    // 记录状态栏高度的原始值
    private var originalStatusBarHeight: Int = -1
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrajectorySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（轨迹设置页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        setupUI()
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
            // 轨迹设置页面不显示底部导航栏
            mainActivity.setBottomNavigationVisibility(false)
        }
        
        // 加载轨迹采集设置
        loadTrajectoryRecordingSetting()
        
        // 加载显示覆盖层设置
        loadShowOverlaySetting()
        
        // 加载采集 Activity 设置
        loadCollectActivitySetting()

        // 加载轨迹剪切板监视设置
        loadTrajectoryClipboardMonitorSetting()
        
        // 加载键盘高度设置
        loadKeyboardHeightSetting()
        
        // 加载状态栏高度设置
        loadStatusBarHeightSetting()
        
        // 加载云侧服务配置
        loadCloudServiceSettings()
        
        // 加载采集前等待设置
        loadCaptureDelaySetting()
        
        // 更新轨迹记录数量
        updateTrajectoryRecordCount()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        mainScope.cancel()
        _binding = null
    }
    
    /**
     * 设置UI
     */
    private fun setupUI() {
        // 初始化云侧配置
        TrajectoryCloudConfig.initialize(requireContext())
        
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 加载轨迹采集设置
        loadTrajectoryRecordingSetting()
        
        // 加载显示覆盖层设置
        loadShowOverlaySetting()
        
        // 加载采集 Activity 设置
        loadCollectActivitySetting()

        // 加载轨迹剪切板监视设置
        loadTrajectoryClipboardMonitorSetting()
        
        // 加载键盘高度设置
        loadKeyboardHeightSetting()
        
        // 加载状态栏高度设置
        loadStatusBarHeightSetting()
        
        // 加载云侧服务配置
        loadCloudServiceSettings()
        
        // 加载采集前等待设置
        loadCaptureDelaySetting()
        
        // 键盘高度保存按钮
        binding.btnSaveKeyboardHeight.setOnClickListener {
            if (saveKeyboardHeightSetting()) {
                Toast.makeText(requireContext(), "键盘高度已保存", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 状态栏高度保存按钮
        binding.btnSaveStatusBarHeight.setOnClickListener {
            if (saveStatusBarHeightSetting()) {
                Toast.makeText(requireContext(), "状态栏高度已保存", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 显示覆盖层开关
        binding.switchShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            saveShowOverlaySetting(isChecked)
            TrajectoryOverlayManager.refreshKeyboardHeight()
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：显示覆盖层" else "已关闭：显示覆盖层")
        }
        
        // 采集 Activity 开关
        binding.switchCollectActivity.setOnCheckedChangeListener { _, isChecked ->
            TrajectoryCloudConfig.setCollectActivityEnabled(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：采集 Activity" else "已关闭：采集 Activity")
        }

        // 轨迹采集时监视剪切板开关
        binding.switchTrajectoryClipboardMonitor.setOnCheckedChangeListener { _, isChecked ->
            saveTrajectoryClipboardMonitorSetting(isChecked)
            if (TrajectoryRecorder.isRecording()) {
                if (isChecked) {
                    TrajectoryClipboardMonitor.startForSession(
                        requireContext(),
                        TrajectoryRecorder.getCurrentSessionId()
                    )
                } else {
                    TrajectoryClipboardMonitor.stop()
                }
            }
            (activity as? MainActivity)?.addLog(
                if (isChecked) "已开启：轨迹采集时监视剪切板" else "已关闭：轨迹采集时监视剪切板"
            )
        }
        
        // 设置轨迹采集开关监听器
        binding.switchTrajectoryRecording.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!ensureTrajectoryPrerequisites()) {
                    binding.switchTrajectoryRecording.isChecked = false
                    return@setOnCheckedChangeListener
                }

                // 检查悬浮窗权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(requireContext())) {
                        Toast.makeText(requireContext(), "需要悬浮窗权限才能使用轨迹采集功能", Toast.LENGTH_LONG).show()
                        binding.switchTrajectoryRecording.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                }

                saveTrajectoryRecordingSetting(true)
                (activity as? MainActivity)?.addLog("已开启：轨迹采集")
                // 显示覆盖层并开始记录
                TrajectoryOverlayManager.show(requireContext())
                TrajectoryRecorder.startRecording(requireContext())
            } else {
                saveTrajectoryRecordingSetting(false)
                (activity as? MainActivity)?.addLog("已关闭：轨迹采集")
                // 隐藏覆盖层并停止记录
                TrajectoryOverlayManager.hide()
                TrajectoryRecorder.stopRecording(requireContext())
            }
        }
        
        // 设置键盘高度输入框监听器
        binding.etKeyboardHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                // 失去焦点时，只有值真正改变时才保存设置
                saveKeyboardHeightSettingIfChanged()
            } else {
                // 获得焦点时，记录当前值作为原始值（用于后续比较）
                try {
                    val currentText = binding.etKeyboardHeight.text.toString()
                    if (currentText.isNotEmpty()) {
                        originalKeyboardHeight = currentText.toInt()
                    }
                } catch (e: Exception) {
                    // 忽略解析错误，使用已保存的值
                }
            }
        }
        
        // 设置云侧服务配置监听器
        setupCloudServiceSettings()

        // 设置轨迹采集依赖权限入口
        setupTrajectoryPermissionEntries()
        
        // 设置轨迹记录入口
        setupTrajectoryRecordEntry()
    }
    
    /**
     * 设置轨迹记录入口
     */
    private fun setupTrajectoryRecordEntry() {
        binding.llTrajectoryRecords.setOnClickListener {
            // 导航到轨迹记录页面
            val trajectoryRecordFragment = TrajectoryRecordFragment()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,
                    R.anim.slide_out_to_left,
                    R.anim.slide_in_from_left,
                    R.anim.slide_out_to_right
                )
                .replace(R.id.fragmentContainer, trajectoryRecordFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        
        // 更新记录数量
        updateTrajectoryRecordCount()
    }
    
    /**
     * 更新轨迹记录数量
     */
    private fun updateTrajectoryRecordCount() {
        try {
            val files = TrajectoryRecorder.getRecordedSessions(requireContext())
            binding.tvTrajectoryRecordCount.text = "${files.size}条"
        } catch (e: Exception) {
            Log.e(TAG, "更新轨迹记录数量失败: ${e.message}", e)
            binding.tvTrajectoryRecordCount.text = "0条"
        }
    }
    
    /**
     * 加载"轨迹采集"设置
     */
    private fun loadTrajectoryRecordingSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("trajectory_recording_enabled", false)
        binding.switchTrajectoryRecording.isChecked = enabled
        updateTrajectoryPermissionButtons()
        // 如果之前是开启的，恢复状态
        if (enabled) {
            if (ensureTrajectoryPrerequisites(showDialog = false) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(requireContext()))
            ) {
                TrajectoryOverlayManager.show(requireContext())
                TrajectoryRecorder.startRecording(requireContext())
            } else {
                binding.switchTrajectoryRecording.isChecked = false
            }
        }
    }
    
    /**
     * 保存"轨迹采集"设置
     */
    private fun saveTrajectoryRecordingSetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("trajectory_recording_enabled", isChecked).apply()
    }
    
    /**
     * 加载"显示覆盖层"设置
     */
    private fun loadShowOverlaySetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        binding.switchShowOverlay.isChecked = prefs.getBoolean("show_overlay", false) // 默认关闭
    }
    
    /**
     * 保存"显示覆盖层"设置
     */
    private fun saveShowOverlaySetting(isChecked: Boolean) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("show_overlay", isChecked).apply()
    }
    
    /**
     * 加载"采集 Activity"设置
     */
    private fun loadCollectActivitySetting() {
        binding.switchCollectActivity.isChecked = TrajectoryCloudConfig.isCollectActivityEnabled()
    }

    /**
     * 加载"轨迹采集时监视剪切板"设置
     */
    private fun loadTrajectoryClipboardMonitorSetting() {
        binding.switchTrajectoryClipboardMonitor.isChecked =
            TrajectoryClipboardMonitor.isEnabled(requireContext())
    }

    /**
     * 保存"轨迹采集时监视剪切板"设置
     */
    private fun saveTrajectoryClipboardMonitorSetting(isChecked: Boolean) {
        TrajectoryClipboardMonitor.setEnabled(requireContext(), isChecked)
    }
    
    /**
     * 加载键盘高度设置
     */
    private fun loadKeyboardHeightSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val keyboardHeight = prefs.getInt("keyboard_height", 1480) // 默认1480
        originalKeyboardHeight = keyboardHeight // 记录原始值
        binding.etKeyboardHeight.setText(keyboardHeight.toString())
    }
    
    /**
     * 保存键盘高度设置（仅在值真正改变时保存）
     */
    private fun saveKeyboardHeightSettingIfChanged() {
        try {
            val heightText = binding.etKeyboardHeight.text.toString()
            if (heightText.isNotEmpty()) {
                val keyboardHeight = heightText.toInt().coerceIn(100, 3000) // 限制在100-3000之间
                
                // 只有值真正改变时才保存
                if (keyboardHeight != originalKeyboardHeight) {
                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putInt("keyboard_height", keyboardHeight).apply()
                    
                    // 更新原始值
                    originalKeyboardHeight = keyboardHeight
                    
                    // 更新显示值（如果被限制）
                    if (keyboardHeight != heightText.toInt()) {
                        binding.etKeyboardHeight.setText(keyboardHeight.toString())
                    }
                    
                    // 通知覆盖层刷新键盘高度缓存
                    TrajectoryOverlayManager.refreshKeyboardHeight()
                    
                    Log.d(TAG, "键盘高度设置已保存: $keyboardHeight")
                } else {
                    // 值没有改变，可能是用户撤销了修改，恢复显示值
                    binding.etKeyboardHeight.setText(originalKeyboardHeight.toString())
                    Log.d(TAG, "键盘高度未改变，保持原值: $originalKeyboardHeight")
                }
            } else {
                // 输入为空，恢复原始值
                binding.etKeyboardHeight.setText(originalKeyboardHeight.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存键盘高度设置失败: ${e.message}", e)
            // 发生错误时，恢复原始值
            binding.etKeyboardHeight.setText(originalKeyboardHeight.toString())
            Toast.makeText(requireContext(), "键盘高度设置失败，请输入有效数字", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 保存键盘高度设置（强制保存，不检查是否改变）
     * @return 是否保存成功
     */
    private fun saveKeyboardHeightSetting(): Boolean {
        return try {
            val heightText = binding.etKeyboardHeight.text.toString()
            if (heightText.isNotEmpty()) {
                val keyboardHeight = heightText.toInt().coerceIn(100, 3000) // 限制在100-3000之间
                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("keyboard_height", keyboardHeight).apply()
                
                // 更新原始值
                originalKeyboardHeight = keyboardHeight
                
                // 更新显示值（如果被限制）
                if (keyboardHeight != heightText.toInt()) {
                    binding.etKeyboardHeight.setText(keyboardHeight.toString())
                }
                
                // 通知覆盖层刷新
                TrajectoryOverlayManager.refreshKeyboardHeight()
                
                Log.d(TAG, "键盘高度设置已保存: $keyboardHeight")
                true
            } else {
                Toast.makeText(requireContext(), "请输入键盘高度", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存键盘高度设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "键盘高度设置失败，请输入有效数字", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * 获取系统默认状态栏高度（用于未设置时显示）
     */
    private fun getSystemStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        if (result == 0) {
            result = (24 * resources.displayMetrics.density).toInt()
        }
        return result
    }
    
    /**
     * 加载状态栏高度设置
     */
    private fun loadStatusBarHeightSetting() {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val statusBarHeight = prefs.getInt("status_bar_height", -1)
        val displayValue = if (statusBarHeight >= 0) statusBarHeight else getSystemStatusBarHeight()
        originalStatusBarHeight = if (statusBarHeight >= 0) statusBarHeight else -1
        binding.etStatusBarHeight.setText(displayValue.toString())
        binding.etStatusBarHeight.hint = getSystemStatusBarHeight().toString()
    }
    
    /**
     * 保存状态栏高度设置
     * @return 是否保存成功
     */
    private fun saveStatusBarHeightSetting(): Boolean {
        return try {
            val heightText = binding.etStatusBarHeight.text.toString()
            if (heightText.isNotEmpty()) {
                val statusBarHeight = heightText.toInt().coerceIn(0, 500) // 限制在0-500之间
                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putInt("status_bar_height", statusBarHeight).apply()
                
                originalStatusBarHeight = statusBarHeight
                
                if (statusBarHeight != heightText.toInt()) {
                    binding.etStatusBarHeight.setText(statusBarHeight.toString())
                }
                
                TrajectoryOverlayManager.refreshKeyboardHeight()
                
                Log.d(TAG, "状态栏高度设置已保存: $statusBarHeight")
                true
            } else {
                Toast.makeText(requireContext(), "请输入状态栏高度", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存状态栏高度设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "状态栏高度设置失败，请输入有效数字", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * 设置云侧服务配置监听器
     */
    private fun setupCloudServiceSettings() {
        // 启用云侧上传开关
        binding.switchCloudUpload.setOnCheckedChangeListener { _, isChecked ->
            TrajectoryCloudConfig.setEnabled(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：云侧上传" else "已关闭：云侧上传")
            updateConnectionStatus()
        }
        
        // 服务器地址输入框
        binding.etCloudServerUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveCloudServerUrl()
            }
        }
        
        // 上传截图开关
        binding.switchUploadScreenshot.setOnCheckedChangeListener { _, isChecked ->
            TrajectoryCloudConfig.setUploadScreenshot(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：上传截图" else "已关闭：上传截图")
        }
        
        // 上传 XML 开关
        binding.switchUploadXml.setOnCheckedChangeListener { _, isChecked ->
            TrajectoryCloudConfig.setUploadXml(isChecked)
            (activity as? MainActivity)?.addLog(if (isChecked) "已开启：上传 XML" else "已关闭：上传 XML")
        }
        
        // 采集前等待保存按钮
        binding.btnSaveCaptureDelay.setOnClickListener {
            if (saveCaptureDelaySetting()) {
                Toast.makeText(requireContext(), "采集前等待已保存", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 测试连接按钮
        binding.btnTestConnection.setOnClickListener {
            testCloudConnection()
        }
    }
    
    /**
     * 加载云侧服务配置
     */
    private fun loadCloudServiceSettings() {
        binding.switchCloudUpload.isChecked = TrajectoryCloudConfig.isEnabled()
        binding.etCloudServerUrl.setText(TrajectoryCloudConfig.getServerUrl())
        binding.switchUploadScreenshot.isChecked = TrajectoryCloudConfig.shouldUploadScreenshot()
        binding.switchUploadXml.isChecked = TrajectoryCloudConfig.isXmlEnabled()
        updateConnectionStatus()
    }
    
    /**
     * 加载采集前等待设置
     */
    private fun loadCaptureDelaySetting() {
        val delayMs = TrajectoryCloudConfig.getCaptureDelayMs()
        binding.etCaptureDelay.setText(delayMs.toString())
    }
    
    /**
     * 保存采集前等待设置
     * @return 是否保存成功
     */
    private fun saveCaptureDelaySetting(): Boolean {
        return try {
            val text = binding.etCaptureDelay.text.toString()
            val delayMs = if (text.isEmpty()) 0 else text.toInt().coerceIn(0, 2000)
            TrajectoryCloudConfig.setCaptureDelayMs(delayMs)
            binding.etCaptureDelay.setText(delayMs.toString())
            Log.d(TAG, "采集前等待设置已保存: ${delayMs}ms")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存采集前等待设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "请输入有效数字(0-2000)", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * 保存服务器地址
     */
    private fun saveCloudServerUrl() {
        val url = binding.etCloudServerUrl.text.toString().trim()
        if (url.isNotEmpty()) {
            TrajectoryCloudConfig.setServerUrl(url)
            Log.d(TAG, "服务器地址已保存: $url")
            updateConnectionStatus()
        }
    }
    
    /**
     * 测试云侧连接
     */
    private fun testCloudConnection() {
        val url = binding.etCloudServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 先保存服务器地址
        TrajectoryCloudConfig.setServerUrl(url)
        
        // 显示测试中状态
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "测试中..."
        binding.tvConnectionStatus.visibility = View.VISIBLE
        binding.tvConnectionStatus.text = "正在测试连接..."
        binding.tvConnectionStatus.setTextColor(0xFF666666.toInt())
        
        mainScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    TrajectoryCloudService.testConnection(requireContext())
                }
                
                if (success) {
                    binding.tvConnectionStatus.text = "连接成功"
                    binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
                    Toast.makeText(requireContext(), "连接成功", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvConnectionStatus.text = "连接失败，请检查服务器地址"
                    binding.tvConnectionStatus.setTextColor(0xFFF44336.toInt())
                    Toast.makeText(requireContext(), "连接失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "测试连接异常: ${e.message}", e)
                binding.tvConnectionStatus.text = "连接异常: ${e.message}"
                binding.tvConnectionStatus.setTextColor(0xFFF44336.toInt())
                Toast.makeText(requireContext(), "连接异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.text = "测试连接"
            }
        }
    }
    
    /**
     * 更新连接状态显示
     */
    private fun updateConnectionStatus() {
        if (TrajectoryCloudConfig.isEnabled() && TrajectoryCloudConfig.isValid()) {
            binding.tvConnectionStatus.visibility = View.VISIBLE
            binding.tvConnectionStatus.text = "已启用"
            binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
        } else if (TrajectoryCloudConfig.isEnabled()) {
            binding.tvConnectionStatus.visibility = View.VISIBLE
            binding.tvConnectionStatus.text = "服务器地址无效"
            binding.tvConnectionStatus.setTextColor(0xFFFF9800.toInt())
        } else {
            binding.tvConnectionStatus.visibility = View.GONE
        }
    }

    private fun setupTrajectoryPermissionEntries() {
        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        binding.btnRequestTrajectoryScreenshotPermission.setOnClickListener {
            requestTrajectoryScreenshotPermission()
        }
        updateTrajectoryPermissionButtons()
    }

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

    private fun requestTrajectoryScreenshotPermission() {
        val mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION_TRAJECTORY)
        (activity as? MainActivity)?.addLog("正在请求轨迹采集截图权限...")
    }

    private fun isAccessibilityGranted(): Boolean {
        return (activity as? MainActivity)?.isAccessibilityServiceEnabled() == true
    }

    private fun isScreenshotPermissionGranted(): Boolean {
        val chatService = ChatScreenshotService.getInstance()
        return chatService != null && chatService.isReady()
    }

    private fun updateTrajectoryPermissionButtons() {
        val accessibilityGranted = isAccessibilityGranted()
        binding.btnOpenAccessibility.isEnabled = !accessibilityGranted
        binding.btnOpenAccessibility.alpha = if (accessibilityGranted) 0.5f else 1.0f

        val screenshotGranted = isScreenshotPermissionGranted()
        binding.btnRequestTrajectoryScreenshotPermission.isEnabled = !screenshotGranted
        binding.btnRequestTrajectoryScreenshotPermission.alpha = if (screenshotGranted) 0.5f else 1.0f
    }

    private fun ensureTrajectoryPrerequisites(showDialog: Boolean = true): Boolean {
        val missingPermissions = mutableListOf<String>()
        if (!isAccessibilityGranted()) {
            missingPermissions.add("无障碍服务")
        }
        if (!isScreenshotPermissionGranted()) {
            missingPermissions.add("截图权限")
        }
        if (missingPermissions.isEmpty()) {
            return true
        }

        if (showDialog) {
            val permissionText = missingPermissions.joinToString("、")
            AlertDialog.Builder(requireContext())
                .setTitle("无法开始轨迹采集")
                .setMessage("请先授权：$permissionText")
                .setPositiveButton("去授权") { _, _ ->
                    if (!isAccessibilityGranted()) {
                        openAccessibilitySettings()
                    } else if (!isScreenshotPermissionGranted()) {
                        requestTrajectoryScreenshotPermission()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        (activity as? MainActivity)?.addLog("轨迹采集启动失败：缺少权限 ${missingPermissions.joinToString("、")}")
        updateTrajectoryPermissionButtons()
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION_TRAJECTORY) {
            return
        }

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

            (activity as? MainActivity)?.addLog("轨迹采集截图权限已授权")
            Toast.makeText(requireContext(), "截图权限已授权", Toast.LENGTH_SHORT).show()
        } else {
            (activity as? MainActivity)?.addLog("轨迹采集截图权限授权失败")
            Toast.makeText(requireContext(), "截图权限授权失败", Toast.LENGTH_SHORT).show()
        }
        updateTrajectoryPermissionButtons()
    }
}

