package com.cloudcontrol.demo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentSettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置Fragment
 * 提供日志和版本信息入口
 */
class SettingsFragment : Fragment() {
    
    companion object {
        private const val TAG = "SettingsFragment"
    }
    
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（设置页面有自己的标题栏）
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
        
        // 确保ActionBar隐藏（设置页面有自己的标题栏，不需要ActionBar）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 设置页面不显示底部导航栏
            mainActivity.setBottomNavigationVisibility(false)
        }
        if (_binding != null) {
            updateLanguageDisplay()
        }
    }

    private fun updateLanguageDisplay() {
        binding.tvLanguageValue.text = when (LanguageManager.getSavedLanguage(requireContext())) {
            LanguageManager.LANG_EN -> getString(R.string.language_en)
            else -> getString(R.string.language_zh)
        }
    }

    private fun showLanguageSelectionDialog() {
        val currentLang = LanguageManager.getSavedLanguage(requireContext())
        val options = arrayOf(getString(R.string.language_zh), getString(R.string.language_en))
        val checkedItem = if (currentLang == LanguageManager.LANG_EN) 1 else 0

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.language_select_title))
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                dialog.dismiss()
                val newLang = if (which == 1) LanguageManager.LANG_EN else LanguageManager.LANG_ZH
                if (newLang != currentLang) {
                    LanguageManager.setLanguage(requireContext(), newLang)
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.language_changed),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    activity?.recreate()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 语言选项
        binding.llLanguageOption.setOnClickListener {
            showLanguageSelectionDialog()
        }
        updateLanguageDisplay()

        // 使用说明选项
        binding.llTutorialOption.setOnClickListener {
            showTutorial()
        }
        
        // 日志选项
        binding.llLogOption.setOnClickListener {
            navigateToLog()
        }
        
        // 版本信息选项
        binding.llVersionInfoOption.setOnClickListener {
            navigateToVersionInfo()
        }

        // 开源声明选项
        binding.llOpenSourceNoticeOption.setOnClickListener {
            showOpenSourceNotices()
        }
        
        // 清理缓存选项
        binding.llClearCacheOption.setOnClickListener {
            showClearCacheDialog()
        }
        
        // 管理员模式选项
        binding.llAdminModeOption.setOnClickListener {
            handleAdminMode()
        }
        
        // 加载并显示缓存大小
        loadCacheSize()
    }
    
    /**
     * 加载缓存大小
     */
    private fun loadCacheSize() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheSize = CacheCleaner.getCacheSize(requireContext())
                val formattedSize = CacheCleaner.formatSize(cacheSize)
                
                withContext(Dispatchers.Main) {
                    binding.tvCacheSize.text = formattedSize
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载缓存大小失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.tvCacheSize.text = getString(R.string.unknown)
                }
            }
        }
    }
    
    /**
     * 显示清理缓存确认对话框
     */
    private fun showClearCacheDialog() {
        // 先显示加载对话框
        val loadingDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.clear_cache_title))
            .setMessage(getString(R.string.clear_cache_calculating))
            .setCancelable(false)
            .create()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheSize = CacheCleaner.getCacheSize(requireContext())
                val formattedSize = CacheCleaner.formatSize(cacheSize)

                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()

                    if (cacheSize == 0L) {
                        android.widget.Toast.makeText(
                            requireContext(),
                            getString(R.string.no_cache_to_clear),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }

                    val message = getString(R.string.clear_cache_confirm_message, formattedSize)
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.clear_cache_title))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.clear)) { _, _ ->
                            clearCache()
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "显示清理缓存对话框失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    loadingDialog.dismiss()
                    android.widget.Toast.makeText(
                        requireContext(),
                        getString(R.string.clear_cache_dialog_failed, e.message ?: ""),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        
        loadingDialog.show()
    }
    
    /**
     * 执行清理缓存
     */
    private fun clearCache() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = CacheCleaner.cleanCache(requireContext())
                
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        val message = if (result.filesDeleted > 0) {
                            getString(R.string.clear_cache_success_format, result.filesDeleted, CacheCleaner.formatSize(result.bytesDeleted))
                        } else {
                            getString(R.string.clear_cache_success_no_files)
                        }

                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.clear_cache_success_title))
                            .setMessage(message)
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()

                        loadCacheSize()
                    } else {
                        val errorMsg = result.errorMessage ?: getString(R.string.unknown)
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.clear_cache_failed))
                            .setMessage(getString(R.string.clear_cache_error_message, errorMsg))
                            .setPositiveButton(getString(R.string.ok), null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理缓存失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.clear_cache_failed))
                        .setMessage(getString(R.string.clear_cache_error_message, e.message ?: ""))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
            }
        }
    }
    
    /**
     * 显示教程弹窗
     */
    private fun showTutorial() {
        try {
            val tutorialDialog = TutorialDialog(requireContext())
            tutorialDialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示教程弹窗失败: ${e.message}", e)
        }
    }
    
    /**
     * 导航到日志页面
     */
    private fun navigateToLog() {
        val logFragment = LogFragment()
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,  // enter: 日志页面从右侧滑入
                R.anim.slide_out_to_left,    // exit: 设置页面向左滑出
                R.anim.slide_in_from_left,   // popEnter: 返回时，设置页面从左侧滑入
                R.anim.slide_out_to_right    // popExit: 返回时，日志页面向右滑出
            )
            .replace(R.id.fragmentContainer, logFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
    
    /**
     * 导航到版本信息页面
     */
    private fun navigateToVersionInfo() {
        val versionInfoFragment = VersionInfoFragment()
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,  // enter: 版本信息页面从右侧滑入
                R.anim.slide_out_to_left,    // exit: 设置页面向左滑出
                R.anim.slide_in_from_left,   // popEnter: 返回时，设置页面从左侧滑入
                R.anim.slide_out_to_right    // popExit: 返回时，版本信息页面向右滑出
            )
            .replace(R.id.fragmentContainer, versionInfoFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    /**
     * 显示开源声明
     */
    private fun showOpenSourceNotices() {
        val noticeText = try {
            requireContext().assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "读取开源声明失败: ${e.message}", e)
            getString(R.string.open_source_notices_load_failed, e.message ?: getString(R.string.unknown))
        }

        val textView = TextView(requireContext()).apply {
            text = noticeText
            textSize = 13f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }

        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.open_source_notices_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }
    
    /**
     * 处理管理员模式
     */
    private fun handleAdminMode() {
        val isEnabled = AdminModeManager.isAdminModeEnabled(requireContext())
        
        if (isEnabled) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.admin_mode_title))
                .setMessage(getString(R.string.admin_mode_exit_confirm))
                .setPositiveButton(getString(R.string.exit)) { _, _ ->
                    AdminModeManager.disableAdminMode(requireContext())
                    android.widget.Toast.makeText(requireContext(), getString(R.string.admin_mode_disabled), android.widget.Toast.LENGTH_SHORT).show()
                    notifyConversationListRefresh()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            // 未启用，显示密码输入对话框
            showAdminPasswordDialog()
        }
    }
    
    /**
     * 显示管理员密码输入对话框
     */
    private fun showAdminPasswordDialog() {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = getString(R.string.admin_password_hint)

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.admin_mode_title))
            .setMessage(getString(R.string.admin_password_hint))
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val password = input.text.toString()
                if (AdminModeManager.verifyPassword(password)) {
                    AdminModeManager.enableAdminMode(requireContext())
                    android.widget.Toast.makeText(requireContext(), getString(R.string.admin_mode_enabled), android.widget.Toast.LENGTH_SHORT).show()
                    notifyConversationListRefresh()
                } else {
                    android.widget.Toast.makeText(requireContext(), getString(R.string.password_error), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 通知对话列表刷新
     */
    private fun notifyConversationListRefresh() {
        try {
            val fragmentManager = parentFragmentManager
            val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
            conversationListFragment?.loadConversations()
        } catch (e: Exception) {
            Log.w(TAG, "通知对话列表刷新失败: ${e.message}")
        }
    }
}

