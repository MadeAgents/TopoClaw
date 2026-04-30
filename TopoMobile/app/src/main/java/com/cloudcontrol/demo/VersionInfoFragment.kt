package com.cloudcontrol.demo

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentVersionInfoBinding
import kotlinx.coroutines.*
import com.cloudcontrol.demo.BuildConfig

/**
 * 版本信息Fragment
 * 显示版本信息和更新说明
 */
class VersionInfoFragment : Fragment() {
    
    companion object {
        private const val TAG = "VersionInfoFragment"
    }
    
    private var _binding: FragmentVersionInfoBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVersionInfoBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 隐藏ActionBar（标题栏）
        (activity as? MainActivity)?.supportActionBar?.hide()
        
        // 隐藏底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        
        setupUI()
        loadVersionInfo()
    }
    
    override fun onResume() {
        super.onResume()
        // 隐藏ActionBar和底部导航栏
        (activity as? MainActivity)?.supportActionBar?.hide()
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
    }
    
    override fun onPause() {
        super.onPause()
        // 不恢复ActionBar，因为返回的设置页面也不需要ActionBar
        // 底部导航栏由目标Fragment自己管理
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        mainScope.cancel()
        _binding = null
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 检查更新按钮
        binding.btnCheckUpdate.setOnClickListener {
            checkVersionUpdate()
        }
    }
    
    /**
     * 加载版本信息
     */
    private fun loadVersionInfo() {
        // 显示当前版本号
        try {
            val versionName = BuildConfig.VERSION_NAME
            binding.tvVersion.text = versionName
        } catch (e: Exception) {
            Log.e(TAG, "获取版本号失败: ${e.message}", e)
            binding.tvVersion.text = "未知"
        }
        
        // 初始显示默认消息
        binding.tvUpdateMessage.text = "当前为最新版本"
        
        // 不再自动检查版本更新，用户需要手动点击"检查更新"按钮
    }
    
    /**
     * 检查版本更新
     * @param showToast 是否显示Toast提示
     */
    private fun checkVersionUpdate(showToast: Boolean = true) {
        mainScope.launch {
            try {
                // 显示加载状态
                binding.btnCheckUpdate.isEnabled = false
                binding.btnCheckUpdate.text = "检查中..."
                
                val currentVersion = BuildConfig.VERSION_NAME
                Log.d(TAG, "开始检查版本更新，当前版本: $currentVersion")
                
                // 初始化 CustomerServiceNetwork
                val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(customerServiceUrl)
                
                val apiService = CustomerServiceNetwork.getApiService()
                if (apiService == null) {
                    Log.w(TAG, "CustomerServiceNetwork未初始化，跳过版本检查")
                    if (showToast) {
                        Toast.makeText(requireContext(), "无法连接到服务器", Toast.LENGTH_SHORT).show()
                    }
                    binding.btnCheckUpdate.isEnabled = true
                    binding.btnCheckUpdate.text = "检查更新"
                    return@launch
                }
                
                // 调用版本检查API
                val response = withContext(Dispatchers.IO) {
                    apiService.checkVersion(currentVersion)
                }
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val versionInfo = response.body()!!
                    val latestVersion = versionInfo.latest_version
                    val hasUpdate = versionInfo.has_update
                    val forceUpdate = versionInfo.force_update
                    val updateMessage = versionInfo.update_message
                    val updateUrl = versionInfo.update_url
                    
                    Log.d(TAG, "版本检查结果: currentVersion=$currentVersion, latestVersion=$latestVersion, hasUpdate=$hasUpdate, forceUpdate=$forceUpdate")
                    
                    // 更新UI
                    binding.tvUpdateMessage.text = updateMessage
                    
                    if (hasUpdate) {
                        // 如果有更新，显示更新提示弹窗
                        VersionUpdateOverlayManager.show(
                            context = requireContext(),
                            currentVersion = currentVersion,
                            latestVersion = latestVersion,
                            updateMessage = updateMessage,
                            updateUrl = updateUrl,
                            forceUpdate = forceUpdate,
                            onUpdate = {
                                Log.d(TAG, "用户选择立即更新")
                            },
                            onSkip = {
                                Log.d(TAG, "用户选择暂不更新")
                            }
                        )
                        
                        if (showToast) {
                            Toast.makeText(requireContext(), "发现新版本 $latestVersion", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        if (showToast) {
                            Toast.makeText(requireContext(), "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.w(TAG, "版本检查API调用失败: ${response.code()} - ${response.message()}")
                    if (showToast) {
                        Toast.makeText(requireContext(), "检查更新失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查版本更新失败: ${e.message}", e)
                if (showToast) {
                    Toast.makeText(requireContext(), "检查更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                // 恢复按钮状态
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = "检查更新"
            }
        }
    }
}

