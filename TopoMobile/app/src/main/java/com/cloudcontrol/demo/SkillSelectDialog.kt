package com.cloudcontrol.demo

import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudcontrol.demo.databinding.DialogSkillSelectBinding
import com.cloudcontrol.demo.databinding.ItemSkillSelectBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 技能选择弹窗
 * 用于在聊天页面选择技能
 */
class SkillSelectDialog(
    private val context: Context,
    private val fragmentManager: FragmentManager? = null,
    private val onSkillSend: ((Skill) -> Unit)? = null  // 技能发送回调
) {
    
    companion object {
        private const val TAG = "SkillSelectDialog"
    }
    
    private var dialog: AlertDialog? = null
    private var binding: DialogSkillSelectBinding? = null
    private var currentTab = 0 // 0: 热门技能, 1: 我的技能
    private var hotSkills: List<Skill> = emptyList()
    private var mySkills: List<Skill> = emptyList()
    private var isRefreshing = false // 是否正在刷新
    private var refreshAnimator: ObjectAnimator? = null // 刷新动画
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    /**
     * 显示弹窗
     */
    fun show() {
        try {
            // 先加载本地技能数据（立即显示）
            loadSkills()
            
            // 创建对话框视图
            val inflater = LayoutInflater.from(context)
            binding = DialogSkillSelectBinding.inflate(inflater)
            
            // 创建对话框
            dialog = AlertDialog.Builder(context)
                .setView(binding?.root)
                .setCancelable(true)
                .create()
            
            // 设置窗口背景透明以显示圆角
            dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            // 设置窗口大小
            dialog?.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                (context.resources.displayMetrics.heightPixels * 0.7).toInt()
            )
            
            // 初始化视图
            setupViews()
            
            // 显示对话框
            dialog?.show()
            
            // 异步刷新技能列表（特别是热门技能）
            refreshSkills()
            
            Log.d(TAG, "技能选择弹窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示技能选择弹窗失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 隐藏弹窗
     */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        binding = null
    }
    
    /**
     * 加载技能数据
     */
    private fun loadSkills() {
        try {
            // 加载我的技能
            mySkills = SkillManager.loadSkills(context)
            
            // 加载技能社区技能，筛选出热门技能
            val communitySkills = SkillManager.loadCommunitySkills(context)
            hotSkills = communitySkills.filter { it.isHot }
            
            Log.d(TAG, "加载技能完成: 热门技能=${hotSkills.size}, 我的技能=${mySkills.size}")
        } catch (e: Exception) {
            Log.e(TAG, "加载技能失败: ${e.message}", e)
            hotSkills = emptyList()
            mySkills = emptyList()
        }
    }
    
    /**
     * 刷新技能列表（异步）
     */
    private fun refreshSkills() {
        if (isRefreshing) {
            Log.d(TAG, "正在刷新中，跳过重复请求")
            return
        }
        
        coroutineScope.launch {
            try {
                isRefreshing = true
                Log.d(TAG, "开始刷新技能列表...")
                
                // 更新刷新按钮状态（显示旋转动画）
                val b = binding
                if (b != null && dialog?.isShowing == true) {
                    // 开始旋转动画
                    refreshAnimator = ObjectAnimator.ofFloat(b.btnRefresh, "rotation", 0f, 360f).apply {
                        duration = 1000
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
                }
                
                // 从云端同步技能（特别是热门技能）
                val result = withContext(Dispatchers.IO) {
                    SkillManager.syncSkillsFromService(
                        context = context,
                        skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(context)
                    )
                }
                
                if (result.success) {
                    Log.d(TAG, "技能刷新完成: 同步${result.syncedCount}个, 跳过${result.skippedCount}个")
                    
                    // 重新加载技能数据
                    loadSkills()
                    
                    // 更新UI（如果弹窗还在显示）
                    if (b != null && dialog?.isShowing == true) {
                        // 刷新当前显示的Tab
                        switchTab(currentTab)
                        
                        // 显示刷新成功提示
                        Toast.makeText(context, "刷新成功", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w(TAG, "技能刷新失败: ${result.message}")
                    // 显示刷新失败提示
                    if (b != null && dialog?.isShowing == true) {
                        Toast.makeText(context, "刷新失败: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "刷新技能列表异常: ${e.message}", e)
                // 显示刷新异常提示
                val b = binding
                if (b != null && dialog?.isShowing == true) {
                    Toast.makeText(context, "刷新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isRefreshing = false
                // 停止旋转动画
                refreshAnimator?.cancel()
                refreshAnimator = null
                val b = binding
                if (b != null && dialog?.isShowing == true) {
                    b.btnRefresh.rotation = 0f
                }
            }
        }
    }
    
    /**
     * 初始化视图
     */
    private fun setupViews() {
        val b = binding ?: return
        
        // 关闭按钮
        b.btnClose.setOnClickListener {
            dismiss()
        }
        
        // 刷新按钮
        b.btnRefresh.setOnClickListener {
            if (!isRefreshing) {
                refreshSkills()
            }
        }
        
        // Tab按钮
        b.btnHotSkills.setOnClickListener {
            switchTab(0)
        }
        
        b.btnMySkills.setOnClickListener {
            switchTab(1)
        }
        
        // 设置RecyclerView
        b.recyclerViewSkills.layoutManager = LinearLayoutManager(context)
        
        // 初始显示热门技能
        switchTab(0)
    }
    
    /**
     * 切换Tab
     */
    private fun switchTab(tabIndex: Int) {
        val b = binding ?: return
        
        currentTab = tabIndex
        
        // 更新Tab按钮样式
        if (tabIndex == 0) {
            // 热门技能
            b.btnHotSkills.setTextColor(0xFF1A1A1A.toInt())
            b.btnHotSkills.setTypeface(null, android.graphics.Typeface.BOLD)
            b.btnMySkills.setTextColor(0xFF999999.toInt())
            b.btnMySkills.setTypeface(null, android.graphics.Typeface.NORMAL)
            
            // 移动指示器
            moveIndicator(b.btnHotSkills)
            
            // 显示热门技能列表
            showSkillsList(hotSkills, b)
        } else {
            // 我的技能
            b.btnHotSkills.setTextColor(0xFF999999.toInt())
            b.btnHotSkills.setTypeface(null, android.graphics.Typeface.NORMAL)
            b.btnMySkills.setTextColor(0xFF1A1A1A.toInt())
            b.btnMySkills.setTypeface(null, android.graphics.Typeface.BOLD)
            
            // 移动指示器
            moveIndicator(b.btnMySkills)
            
            // 显示我的技能列表
            showSkillsList(mySkills, b)
        }
    }
    
    /**
     * 移动指示器到指定按钮下方
     */
    private fun moveIndicator(targetButton: TextView) {
        val b = binding ?: return
        val indicator = b.viewIndicator
        
        targetButton.post {
            val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
            val buttonWidth = screenWidth / 2f
            
            val layoutParams = indicator.layoutParams as? ViewGroup.MarginLayoutParams
            if (layoutParams != null) {
                val targetMarginStart = if (targetButton.id == b.btnHotSkills.id) {
                    0
                } else {
                    buttonWidth.toInt()
                }
                
                layoutParams.marginStart = targetMarginStart
                layoutParams.width = buttonWidth.toInt()
                indicator.layoutParams = layoutParams
            }
            
            // 使用动画移动指示器
            indicator.animate()
                .translationX(0f) // 重置位置
                .setDuration(200)
                .start()
        }
    }
    
    /**
     * 显示技能列表
     */
    private fun showSkillsList(skills: List<Skill>, b: DialogSkillSelectBinding) {
        if (skills.isEmpty()) {
            b.recyclerViewSkills.visibility = View.GONE
            b.llEmpty.visibility = View.VISIBLE
            b.recyclerViewSkills.adapter = null
        } else {
            b.recyclerViewSkills.visibility = View.VISIBLE
            b.llEmpty.visibility = View.GONE
            
            val adapter = SkillAdapter(skills, fragmentManager, this@SkillSelectDialog, onSkillSend) { skill ->
                // 点击技能时的回调（已移除，现在通过按钮处理）
            }
            b.recyclerViewSkills.adapter = adapter
        }
    }
    
    /**
     * 技能列表适配器
     */
    private class SkillAdapter(
        private val skills: List<Skill>,
        private val fragmentManager: FragmentManager?,
        private val dialog: SkillSelectDialog,
        private val onSkillSend: ((Skill) -> Unit)?,
        private val onItemClick: (Skill) -> Unit
    ) : RecyclerView.Adapter<SkillAdapter.SkillViewHolder>() {
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SkillViewHolder {
            val binding = ItemSkillSelectBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return SkillViewHolder(binding, fragmentManager, dialog, onSkillSend)
        }
        
        override fun onBindViewHolder(holder: SkillViewHolder, position: Int) {
            holder.bind(skills[position])
        }
        
        override fun getItemCount(): Int = skills.size
        
        inner class SkillViewHolder(
            private val binding: ItemSkillSelectBinding,
            private val fragmentManager: FragmentManager?,
            private val dialog: SkillSelectDialog,
            private val onSkillSend: ((Skill) -> Unit)?
        ) : RecyclerView.ViewHolder(binding.root) {
            
            fun bind(skill: Skill) {
                // 设置标题
                binding.tvSkillTitle.text = skill.title
                
                // 移除热门标识（不再显示）
                
                // 查看详情按钮点击事件
                binding.btnViewDetail.setOnClickListener {
                    // 先关闭弹窗
                    dialog.dismiss()
                    // 然后跳转到详情页
                    navigateToSkillDetail(skill)
                }
                
                // 发送按钮点击事件
                binding.btnSend.setOnClickListener {
                    Log.d(TAG, "点击发送技能: ${skill.title}")
                    // 先关闭弹窗
                    dialog.dismiss()
                    // 调用回调发送技能
                    onSkillSend?.invoke(skill)
                }
                
                // 移除卡片根布局的点击事件（现在通过按钮处理）
            }
            
            /**
             * 跳转到技能详情页
             */
            private fun navigateToSkillDetail(skill: Skill) {
                try {
                    val fm = fragmentManager
                    if (fm == null) {
                        Log.w(TAG, "FragmentManager为空，无法跳转到技能详情页")
                        Toast.makeText(binding.root.context, binding.root.context.getString(R.string.cannot_open_skill_detail), Toast.LENGTH_SHORT).show()
                        return
                    }
                    
                    val detailFragment = SkillDetailFragment.newInstance(skill.id, allowEdit = false)
                    fm.beginTransaction()
                        .setCustomAnimations(0, 0, 0, 0)
                        .replace(R.id.fragmentContainer, detailFragment)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                    
                    Log.d(TAG, "跳转到技能详情页: ${skill.title}")
                } catch (e: Exception) {
                    Log.e(TAG, "跳转到技能详情页失败: ${e.message}", e)
                    Toast.makeText(binding.root.context, binding.root.context.getString(R.string.open_skill_detail_failed_only), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

