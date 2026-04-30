package com.cloudcontrol.demo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentLogBinding

/**
 * 日志Fragment
 * 显示应用运行日志
 */
class LogFragment : Fragment() {
    
    companion object {
        private const val TAG = "LogFragment"
    }
    
    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 隐藏ActionBar（标题栏）
        (activity as? MainActivity)?.supportActionBar?.hide()
        
        // 隐藏底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        
        setupUI()
        // 从MainActivity同步已有的日志
        syncLogsFromMainActivity()
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
        _binding = null
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 清空日志按钮
        binding.btnClearLog.setOnClickListener {
            clearLog()
        }
    }
    
    /**
     * 从MainActivity同步已有的日志
     */
    private fun syncLogsFromMainActivity() {
        val mainActivity = activity as? MainActivity
        val existingLogs = mainActivity?.getAllLogs() ?: return
        
        if (existingLogs.isNotEmpty()) {
            // 清空当前显示，然后重新添加所有日志
            binding.tvLog.text = ""
            existingLogs.forEach { log ->
                addLogInternal(log)
            }
        }
    }
    
    /**
     * 内部添加日志方法（不保存到MainActivity，用于同步已有日志）
     */
    private fun addLogInternal(message: String) {
        activity?.runOnUiThread {
            val currentText = binding.tvLog.text.toString()
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val newMessage = if (currentText.isEmpty()) {
                "[$timestamp] $message"
            } else {
                "$currentText\n[$timestamp] $message"
            }
            binding.tvLog.text = newMessage
            
            // 滚动到底部
            binding.svLog.post {
                binding.svLog.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    
    /**
     * 添加日志（公共方法，供MainActivity调用）
     */
    fun addLog(message: String) {
        addLogInternal(message)
    }
    
    /**
     * 清空日志
     */
    private fun clearLog() {
        binding.tvLog.text = ""
        (activity as? MainActivity)?.clearLogMessages()
        addLog("日志已清空")
    }
}

