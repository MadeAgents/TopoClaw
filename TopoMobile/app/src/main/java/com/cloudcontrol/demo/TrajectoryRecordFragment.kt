package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudcontrol.demo.databinding.FragmentTrajectoryRecordBinding
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 轨迹记录列表Fragment
 * 显示所有已保存的轨迹会话记录
 */
class TrajectoryRecordFragment : Fragment() {
    
    companion object {
        private const val TAG = "TrajectoryRecord"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
    
    private var _binding: FragmentTrajectoryRecordBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var adapter: TrajectoryRecordAdapter
    private val sessions = mutableListOf<TrajectorySessionInfo>()
    
    /**
     * 会话信息数据类
     */
    data class TrajectorySessionInfo(
        val fileName: String,
        val file: File,
        val sessionId: String,
        val startTime: Long,
        val endTime: Long,
        val eventCount: Int,
        val duration: Long
    )
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrajectoryRecordBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（轨迹记录页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 设置标题栏背景颜色为浅灰色
        binding.llTitleBar.setBackgroundColor(0xFFF5F5F5.toInt())
        
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 设置RecyclerView
        adapter = TrajectoryRecordAdapter(
            sessions,
            onItemClick = { sessionInfo ->
                showSessionDetails(sessionInfo)
            },
            onItemLongClick = { sessionInfo ->
                showDeleteDialog(sessionInfo)
            },
            onShareClick = { sessionInfo ->
                shareSession(sessionInfo)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        
        // 加载轨迹记录
        loadTrajectoryRecords()
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否已附加到Activity
        if (!isAdded || context == null) return
        
        // 确保ActionBar隐藏（轨迹记录页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 轨迹记录页面不显示底部导航栏
            mainActivity.setBottomNavigationVisibility(false)
        }
        
        // 刷新列表
        loadTrajectoryRecords()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        mainScope.cancel()
        _binding = null
    }
    
    /**
     * 加载轨迹记录
     */
    private fun loadTrajectoryRecords() {
        mainScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    TrajectoryRecorder.getRecordedSessions(requireContext())
                }
                
                sessions.clear()
                sessions.addAll(files.mapNotNull { file ->
                    parseSessionInfo(file)
                })
                
                // 按开始时间倒序排序（最新的在前）
                sessions.sortByDescending { it.startTime }
                
                adapter.notifyDataSetChanged()
                
                // 更新空状态显示
                if (sessions.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "加载轨迹记录失败: ${e.message}", e)
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            }
        }
    }
    
    /**
     * 解析会话信息
     */
    private fun parseSessionInfo(file: File): TrajectorySessionInfo? {
        return try {
            val json = file.readText()
            val gson = Gson()
            val session = gson.fromJson(json, TrajectorySession::class.java)
            
            TrajectorySessionInfo(
                fileName = file.name,
                file = file,
                sessionId = session.sessionId,
                startTime = session.startTime,
                endTime = session.endTime ?: session.startTime,
                eventCount = session.events.size,
                duration = (session.endTime ?: System.currentTimeMillis()) - session.startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析会话文件失败: ${file.name}, ${e.message}", e)
            null
        }
    }
    
    /**
     * 显示会话详情
     */
    private fun showSessionDetails(sessionInfo: TrajectorySessionInfo) {
        // 导航到事件详情页面
        val eventDetailFragment = TrajectoryEventDetailFragment.newInstance(sessionInfo.fileName)
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,
                R.anim.slide_out_to_left,
                R.anim.slide_in_from_left,
                R.anim.slide_out_to_right
            )
            .replace(R.id.fragmentContainer, eventDetailFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteDialog(sessionInfo: TrajectorySessionInfo) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("删除轨迹记录")
            .setMessage("确定要删除这条轨迹记录吗？\n\n会话ID: ${sessionInfo.sessionId}\n时间: ${DATE_FORMAT.format(Date(sessionInfo.startTime))}")
            .setPositiveButton("删除") { _, _ ->
                try {
                    val success = TrajectoryRecorder.deleteSession(requireContext(), sessionInfo.fileName)
                    if (success) {
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                        loadTrajectoryRecords()
                    } else {
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "删除会话失败: ${e.message}", e)
                    Toast.makeText(requireContext(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 分享会话文件
     */
    private fun shareSession(sessionInfo: TrajectorySessionInfo) {
        try {
            val file = sessionInfo.file
            if (!file.exists()) {
                Toast.makeText(requireContext(), "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 尝试使用FileProvider，如果失败则使用普通URI
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                // 如果没有配置FileProvider，使用普通URI（需要文件访问权限）
                Uri.fromFile(file)
            }
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "轨迹记录_${sessionInfo.sessionId}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "分享轨迹记录"))
        } catch (e: Exception) {
            Log.e(TAG, "分享会话失败: ${e.message}", e)
            Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 格式化时长
     */
    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> "${hours}时${minutes % 60}分"
            minutes > 0 -> "${minutes}分${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }
    
    /**
     * RecyclerView适配器
     */
    class TrajectoryRecordAdapter(
        private val sessions: List<TrajectorySessionInfo>,
        private val onItemClick: (TrajectorySessionInfo) -> Unit,
        private val onItemLongClick: (TrajectorySessionInfo) -> Unit,
        private val onShareClick: (TrajectorySessionInfo) -> Unit
    ) : RecyclerView.Adapter<TrajectoryRecordAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvSessionId: TextView = view.findViewById(android.R.id.text1)
            val tvTime: TextView = view.findViewById(android.R.id.text2)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val session = sessions[position]
            
            holder.tvSessionId.text = "会话: ${session.sessionId}"
            holder.tvTime.text = "${DATE_FORMAT.format(Date(session.startTime))}\n${session.eventCount} 个事件 | ${formatDuration(session.duration)}"
            
            holder.itemView.setOnClickListener {
                onItemClick(session)
            }
            
            holder.itemView.setOnLongClickListener {
                onItemLongClick(session)
                true
            }
        }
        
        override fun getItemCount() = sessions.size
        
        private fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            
            return when {
                hours > 0 -> "${hours}时${minutes % 60}分"
                minutes > 0 -> "${minutes}分${seconds % 60}秒"
                else -> "${seconds}秒"
            }
        }
    }
}

