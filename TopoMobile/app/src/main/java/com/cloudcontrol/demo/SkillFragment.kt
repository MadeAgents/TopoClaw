package com.cloudcontrol.demo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.cloudcontrol.demo.databinding.FragmentSkillBinding
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 技能Fragment
 * 展示【我的技能】列表
 */
class SkillFragment : Fragment() {
    
    companion object {
        private const val TAG = "SkillFragment"
        private const val MAX_VISIBLE_STEPS = 5 // 默认显示的最大步骤数
    }
    
    private var _binding: FragmentSkillBinding? = null
    private val binding get() = _binding!!
    private var skills: List<Skill> = emptyList() // 当前显示的技能（用于兼容其他代码）
    private var currentPage = 0 // 0: 我的技能, 1: 技能社区
    
    // 多选模式相关
    private var isMultiSelectMode = false // 是否处于多选模式
    private var multiSelectModeType = 0 // 0: 上传模式, 1: 下载模式
    private val selectedSkills = mutableSetOf<String>() // 选中的技能ID集合
    private val skillCardViews = mutableMapOf<String, View>() // 技能ID到卡片视图的映射
    
    // 性能优化：缓存已加载状态，避免重复加载
    private var isSkillsLoaded = false
    private var isCommunitySkillsLoaded = false
    private var lastSkillsLoadTime = 0L
    private var lastCommunitySkillsLoadTime = 0L
    private val REFRESH_INTERVAL_MS = 500L // 500ms内的重复刷新会被忽略
    
    // 分页加载相关
    private var allSkills: List<Skill> = emptyList() // 所有技能数据
    private var loadedSkillsCount = 0 // 已加载的技能数量
    private var isLoadingMore = false // 是否正在加载更多
    private val PAGE_SIZE = 10 // 每页加载的数量
    private var loadMoreView: View? = null // 加载更多的提示视图
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSkillBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（技能页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 设置Fragment层级的底部导航栏
        (activity as? MainActivity)?.let { mainActivity ->
            val currentBinding = _binding
            if (currentBinding != null) {
                mainActivity.setupFragmentBottomNavigation(currentBinding.bottomNavigation, R.id.nav_skill)
                mainActivity.setFragmentBottomNavigationBackgroundColor(currentBinding.bottomNavigation, 0xFFF5F5F5.toInt())
                // 初始化并更新聊天图标徽章和技能图标徽章
                currentBinding.root.post {
                    // 检查 Fragment 是否仍然 attached 且 binding 不为 null
                    if (isAdded && _binding != null) {
                        _binding?.let { binding ->
                            mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                            mainActivity.initAndUpdateFragmentSkillBadge(binding.bottomNavigation)
                        }
                    }
                }
            }
        }
        
        setupNavigationBar()
        setupSwipeGesture()
        setupSwipeRefresh()
        setupScrollListener() // 设置滚动监听，用于加载更多
        loadAndDisplaySkills()
        loadAndDisplayCommunitySkills()
        setupSearchButtons()
        setupHotSkillBadge()
        updateHotSkillBadge()
    }
    
    /**
     * 设置导航栏
     */
    private fun setupNavigationBar() {
        // 默认显示"我的技能"页面
        val currentBinding = _binding
        if (currentBinding != null) {
            currentBinding.btnMySkills.post {
                // 检查 Fragment 是否仍然 attached 且 binding 不为 null
                if (isAdded && _binding != null) {
                    switchToPage(isMySkillsPage = true, animated = false)
                }
            }
        }
        
        // 我的技能按钮
        binding.btnMySkills.setOnClickListener {
            switchToPage(isMySkillsPage = true, animated = true)
        }
        
        // 技能社区按钮
        binding.btnSkillCommunity.setOnClickListener {
            switchToPage(isMySkillsPage = false, animated = true)
        }
    }
    
    /**
     * 设置滑动手势
     * 在页面内容区域（ScrollView）上设置触摸监听，实现左右滑动切换
     */
    private fun setupSwipeGesture() {
        var startX = 0f
        var startY = 0f
        var isSwipeDetected = false
        
        // 初始化当前页面状态
        currentPage = if (binding.llMySkillsPage.visibility == View.VISIBLE) 0 else 1
        
        // 创建一个统一的触摸监听器
        val touchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwipeDetected = false
                    false // 不拦截，让ScrollView正常处理
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    val absDeltaX = kotlin.math.abs(deltaX)
                    val absDeltaY = kotlin.math.abs(deltaY)
                    
                    // 判断是否为水平滑动（水平距离大于垂直距离的2倍，且水平距离超过阈值）
                    // 使用2倍比例确保优先识别垂直滚动
                    val swipeThreshold = 30f * resources.displayMetrics.density // 30dp
                    if (absDeltaX > absDeltaY * 2f && absDeltaX > swipeThreshold) {
                        isSwipeDetected = true
                        // 阻止ScrollView拦截触摸事件，优先处理水平滑动
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    } else {
                        // 垂直滚动或小范围移动，允许ScrollView处理
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    
                    if (isSwipeDetected) {
                        val deltaX = event.rawX - startX
                        val swipeThreshold = 80f * resources.displayMetrics.density // 80dp
                        
                        // 向右滑动：切换到"我的技能"
                        if (deltaX > swipeThreshold && currentPage == 1) {
                            switchToPage(isMySkillsPage = true, animated = true)
                            currentPage = 0
                            true
                        }
                        // 向左滑动：切换到"技能社区"
                        else if (deltaX < -swipeThreshold && currentPage == 0) {
                            switchToPage(isMySkillsPage = false, animated = true)
                            currentPage = 1
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        
        // 在"我的技能"页面的ScrollView上设置触摸监听
        binding.scrollViewMySkills.setOnTouchListener(touchListener)
        
        // 在"技能社区"页面的ScrollView上设置触摸监听
        binding.scrollViewCommunity.setOnTouchListener(touchListener)
    }
    
    /**
     * 设置下拉刷新
     */
    private fun setupSwipeRefresh() {
        if (!isAdded || _binding == null) return
        
        // 设置"我的技能"页面的下拉刷新
        binding.swipeRefreshLayoutMySkills.setOnRefreshListener {
            refreshMySkills()
        }
        
        // 设置"技能社区"页面的下拉刷新（保留功能，但不再显示同步按钮）
        binding.swipeRefreshLayoutCommunity.setOnRefreshListener {
            refreshCommunitySkills()
        }
        
        // 设置刷新指示器颜色
        val refreshColors = intArrayOf(
            0xFF10AEFF.toInt(), // 主色调蓝色
            0xFF666666.toInt()  // 灰色
        )
        binding.swipeRefreshLayoutMySkills.setColorSchemeColors(*refreshColors)
        binding.swipeRefreshLayoutCommunity.setColorSchemeColors(*refreshColors)
    }
    
    /**
     * 刷新"我的技能"列表（下拉刷新时调用）
     */
    private fun refreshMySkills() {
        if (!isAdded || _binding == null) return
        
        lifecycleScope.launch {
            try {
                // 重置分页状态并重新加载我的技能列表
                loadedSkillsCount = 0
                isLoadingMore = false
                loadAndDisplaySkills()
                Log.d(TAG, "下拉刷新：我的技能列表刷新完成")
                
                // 显示刷新成功提示
                withContext(Dispatchers.Main) {
                    if (isAdded && context != null) {
                        Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "刷新我的技能列表失败: ${e.message}", e)
                // 显示刷新失败提示
                withContext(Dispatchers.Main) {
                    if (isAdded && context != null) {
                        Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                // 确保在主线程关闭刷新指示器
                if (isAdded && _binding != null) {
                    binding.swipeRefreshLayoutMySkills.isRefreshing = false
                }
            }
        }
    }
    
    /**
     * 刷新"技能社区"列表（下拉刷新时调用）
     */
    private fun refreshCommunitySkills() {
        if (!isAdded || _binding == null) return
        
        lifecycleScope.launch {
            try {
                // 从服务器同步技能到技能社区（该方法内部会显示同步结果的 Toast）
                syncSkillsFromService()
                
                // 重新加载技能社区列表
                loadAndDisplayCommunitySkills()
                Log.d(TAG, "下拉刷新：技能社区列表刷新完成")
                
                // syncSkillsFromService() 已经会显示详细的同步结果 Toast，这里不需要再显示
            } catch (e: Exception) {
                Log.e(TAG, "刷新技能社区列表失败: ${e.message}", e)
                // 显示刷新失败提示
                withContext(Dispatchers.Main) {
                    if (isAdded && context != null) {
                        Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                // 确保在主线程关闭刷新指示器
                if (isAdded && _binding != null) {
                    binding.swipeRefreshLayoutCommunity.isRefreshing = false
                }
            }
        }
    }
    
    /**
     * 设置热门技能徽章
     */
    private fun setupHotSkillBadge() {
        // 徽章已在布局文件中定义，这里可以添加额外的设置
    }
    
    /**
     * 更新热门技能徽章显示
     */
    fun updateHotSkillBadge() {
        try {
            if (!isAdded || _binding == null) return
            
            val newHotSkillsCount = HotSkillBadgeManager.getNewHotSkillsCount(requireContext())
            val badgeView = binding.tvHotSkillBadge
            
            if (newHotSkillsCount > 0) {
                badgeView.visibility = View.VISIBLE
                badgeView.text = if (newHotSkillsCount > 99) "99+" else newHotSkillsCount.toString()
            } else {
                badgeView.visibility = View.GONE
            }
            
            // 同时更新底部导航栏的徽章
            (activity as? MainActivity)?.updateSkillBadge()
        } catch (e: Exception) {
            Log.e(TAG, "更新热门技能徽章失败: ${e.message}", e)
        }
    }
    
    /**
     * 切换页面
     * @param isMySkillsPage true显示"我的技能"页面，false显示"技能社区"页面
     * @param animated 是否使用动画
     */
    private fun switchToPage(isMySkillsPage: Boolean, animated: Boolean = true) {
        // 更新当前页面状态
        currentPage = if (isMySkillsPage) 0 else 1
        
        // 如果切换到技能社区页面，清除徽章
        if (!isMySkillsPage) {
            HotSkillBadgeManager.clearBadge(requireContext())
            updateHotSkillBadge()
        }
        
        if (isMySkillsPage) {
            // 显示"我的技能"页面
            if (animated) {
                // 先隐藏要隐藏的页面，避免布局冲突
                binding.llSkillCommunityPage.visibility = View.GONE
                
                // 淡入淡出动画
                binding.llMySkillsPage.alpha = 0f
                binding.llMySkillsPage.visibility = View.VISIBLE
                binding.llMySkillsPage.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setListener(null)
                    .start()
            } else {
                binding.llMySkillsPage.visibility = View.VISIBLE
                binding.llSkillCommunityPage.visibility = View.GONE
            }
            
            // 更新按钮文字颜色
            binding.btnMySkills.setTextColor(0xFF000000.toInt())
            binding.btnSkillCommunity.setTextColor(0xFF999999.toInt())
            
            // 移动指示器到"我的技能"按钮下方
            moveIndicator(binding.btnMySkills, animated)
        } else {
            // 显示"技能社区"页面
            if (animated) {
                // 先隐藏要隐藏的页面，避免布局冲突
                binding.llMySkillsPage.visibility = View.GONE
                
                // 淡入淡出动画
                binding.llSkillCommunityPage.alpha = 0f
                binding.llSkillCommunityPage.visibility = View.VISIBLE
                binding.llSkillCommunityPage.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .setListener(null)
                    .start()
            } else {
                binding.llMySkillsPage.visibility = View.GONE
                binding.llSkillCommunityPage.visibility = View.VISIBLE
            }
            
            // 更新按钮文字颜色
            binding.btnMySkills.setTextColor(0xFF999999.toInt())
            binding.btnSkillCommunity.setTextColor(0xFF000000.toInt())
            
            // 移动指示器到"技能社区"按钮下方
            moveIndicator(binding.btnSkillCommunity, animated)
        }
    }
    
    /**
     * 移动指示器到指定按钮下方
     */
    private fun moveIndicator(targetButton: android.widget.Button, animated: Boolean = true) {
        val indicator = binding.viewIndicator
        
        // 获取目标按钮的位置和宽度
        targetButton.post {
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val buttonWidth = screenWidth / 2f // 每个按钮占一半宽度
            
            // 计算指示器的布局参数
            val layoutParams = indicator.layoutParams as? LinearLayout.LayoutParams
            if (layoutParams != null) {
                val targetMarginStart = if (targetButton.id == R.id.btnMySkills) {
                    0
                } else {
                    buttonWidth.toInt()
                }
                
                if (animated) {
                    // 使用动画移动指示器
                    val currentMarginStart = layoutParams.marginStart
                    val animator = android.animation.ValueAnimator.ofInt(currentMarginStart, targetMarginStart)
                    animator.duration = 200
                    animator.addUpdateListener { animation ->
                        val marginStart = animation.animatedValue as Int
                        layoutParams.marginStart = marginStart
                        indicator.layoutParams = layoutParams
                    }
                    animator.start()
                } else {
                    // 直接设置位置
                    layoutParams.width = buttonWidth.toInt()
                    layoutParams.marginStart = targetMarginStart
                    indicator.layoutParams = layoutParams
                }
            }
        }
    }
    
    /**
     * 设置搜索按钮
     */
    private fun setupSearchButtons() {
        // 我的技能搜索按钮
        binding.btnSearchMySkills.setOnClickListener {
            showSearchDialog(isMySkills = true)
        }
        
        // 上传技能按钮（端-->云）- 进入多选模式
        binding.btnUploadMySkills.setOnClickListener {
            if (isMultiSelectMode && multiSelectModeType == 0) {
                // 如果已经在多选模式且是上传模式，执行批量上传
                performBatchUploadFromSelection()
            } else {
                // 进入多选模式
                enterMultiSelectMode(isUploadMode = true)
            }
        }
        
        // 技能社区搜索按钮
        binding.btnSearchCommunity.setOnClickListener {
            showSearchDialog(isMySkills = false)
        }
        
        // 技能社区下载按钮（云-->端）- 进入多选模式
        binding.btnDownloadSkills.setOnClickListener {
            if (isMultiSelectMode && multiSelectModeType == 1) {
                // 如果已经在多选模式且是下载模式，执行批量下载
                performBatchDownloadFromSelection()
            } else {
                // 进入多选模式
                enterMultiSelectMode(isUploadMode = false)
            }
        }
        
        // 批量上传按钮
        binding.btnBatchUpload.setOnClickListener {
            performBatchUploadFromSelection()
        }
        
        // 批量下载按钮
        binding.btnBatchDownload.setOnClickListener {
            performBatchDownloadFromSelection()
        }
        
        // 取消多选按钮（我的技能）
        binding.btnCancelSelectionMySkills.setOnClickListener {
            exitMultiSelectMode()
        }
        
        // 取消多选按钮（技能社区）
        binding.btnCancelSelectionCommunity.setOnClickListener {
            exitMultiSelectMode()
        }
    }
    
    /**
     * 显示新建技能对话框
     */
    private fun showCreateSkillDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_skill, null)
        val etTitle = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSkillTitle)
        val etPurpose = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSkillPurpose)
        val etSteps = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSkillSteps)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnCreate = dialogView.findViewById<android.widget.Button>(R.id.btnCreate)
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnCreate.setOnClickListener {
            val title = etTitle.text?.toString()?.trim() ?: ""
            val purpose = etPurpose.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val stepsText = etSteps.text?.toString() ?: ""
            val steps = stepsText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            
            when {
                title.isEmpty() -> {
                    Toast.makeText(requireContext(), getString(R.string.create_skill_title_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                steps.isEmpty() -> {
                    Toast.makeText(requireContext(), getString(R.string.create_skill_steps_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                else -> {
                    val success = SkillManager.saveSkillFromData(
                        context = requireContext(),
                        title = title,
                        steps = steps,
                        originalPurpose = purpose
                    )
                    dialog.dismiss()
                    if (success) {
                        Toast.makeText(requireContext(), getString(R.string.create_skill_success), Toast.LENGTH_SHORT).show()
                        loadAndDisplaySkills()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    
    /**
     * 显示搜索对话框（实时搜索，无按钮）
     */
    private fun showSearchDialog(isMySkills: Boolean) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_search_skill, null)
        val searchInput = dialogView.findViewById<android.widget.EditText>(R.id.etSearchInput)
        val resultsList = dialogView.findViewById<android.widget.ListView>(R.id.lvSearchResults)
        val emptyResult = dialogView.findViewById<TextView>(R.id.tvEmptyResult)
        
        // 创建对话框，不设置按钮
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(if (isMySkills) getString(R.string.search_my_skills) else getString(R.string.search_skill_community))
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // 设置对话框窗口大小，确保有足够空间显示搜索结果
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
            titleView?.let {
                val currentSize = it.textSize / requireContext().resources.displayMetrics.scaledDensity
                it.textSize = (currentSize - 2).coerceAtLeast(12f)
            }
            
            // 设置对话框窗口大小，确保搜索结果列表可以滚动
            val window = dialog.window
            window?.let {
                val displayMetrics = requireContext().resources.displayMetrics
                val width = (displayMetrics.widthPixels * 0.9).toInt()
                val height = (displayMetrics.heightPixels * 0.7).toInt()
                it.setLayout(width, height)
            }
        }
        
        // 搜索结果适配器
        val adapter = object : android.widget.ArrayAdapter<Skill>(
            requireContext(),
            R.layout.item_search_result,
            android.R.id.text1,
            mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(
                    R.layout.item_search_result,
                    parent,
                    false
                )
                val skill = getItem(position) ?: return view
                
                val titleView = view.findViewById<TextView>(R.id.tvSkillTitle)
                val stepsView = view.findViewById<TextView>(R.id.tvSkillSteps)
                
                titleView.text = skill.title
                stepsView.text = if (skill.steps.isNotEmpty()) {
                    skill.steps.first()
                } else {
                    ""
                }
                
                return view
            }
        }
        resultsList.adapter = adapter
        
        // 点击搜索结果项
        resultsList.setOnItemClickListener { _, _, position, _ ->
            val selectedSkill = adapter.getItem(position)
            if (selectedSkill != null) {
                dialog.dismiss()
                // 跳转到技能详情页
                navigateToSkillDetail(selectedSkill.id, allowEdit = isMySkills)
            }
        }
        
        // 实时搜索
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    adapter.clear()
                    resultsList.visibility = android.view.View.GONE
                    emptyResult.visibility = android.view.View.GONE
                } else {
                    performRealtimeSearch(query, isMySkills, adapter, resultsList, emptyResult)
                }
            }
        })
        
        dialog.show()
    }
    
    /**
     * 执行实时搜索并显示结果
     */
    private fun performRealtimeSearch(
        query: String,
        isMySkills: Boolean,
        adapter: android.widget.ArrayAdapter<Skill>,
        resultsList: android.widget.ListView,
        emptyResult: TextView
    ) {
        try {
            // 加载技能列表
            val allSkills = if (isMySkills) {
                SkillManager.loadSkills(requireContext())
            } else {
                SkillManager.loadCommunitySkills(requireContext())
            }
            
            // 计算相似度并排序
            val scoredSkills = allSkills.map { skill ->
                val score = calculateSimilarityScore(query, skill)
                Pair(skill, score)
            }.filter { it.second > 0 } // 只保留有匹配的技能
                .sortedByDescending { it.second } // 按相似度降序排序
                .map { it.first } // 显示所有匹配的结果，不再限制数量
            
            adapter.clear()
            adapter.addAll(scoredSkills)
            adapter.notifyDataSetChanged()
            
            if (scoredSkills.isEmpty()) {
                resultsList.visibility = android.view.View.GONE
                emptyResult.visibility = android.view.View.VISIBLE
            } else {
                resultsList.visibility = android.view.View.VISIBLE
                emptyResult.visibility = android.view.View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "实时搜索失败: ${e.message}", e)
        }
    }
    
    /**
     * 计算相似度分数
     * 返回0-100的分数，分数越高表示越相似
     */
    private fun calculateSimilarityScore(query: String, skill: Skill): Float {
        var score = 0f
        val queryLower = query.lowercase()
        
        // 标题匹配（权重最高）
        val titleLower = skill.title.lowercase()
        if (titleLower.contains(queryLower)) {
            // 完全匹配
            if (titleLower == queryLower) {
                score += 100f
            } else if (titleLower.startsWith(queryLower)) {
                // 开头匹配
                score += 80f
            } else {
                // 包含匹配
                score += 60f
            }
        }
        
        // 步骤匹配（权重中等）
        skill.steps.forEach { step ->
            val stepLower = step.lowercase()
            if (stepLower.contains(queryLower)) {
                if (stepLower.startsWith(queryLower)) {
                    score += 30f
                } else {
                    score += 20f
                }
            }
        }
        
        // 原始目的匹配（权重较低）
        skill.originalPurpose?.let { purpose ->
            val purposeLower = purpose.lowercase()
            if (purposeLower.contains(queryLower)) {
                score += 10f
            }
        }
        
        return score
    }
    
    /**
     * 搜索我的技能
     */
    private fun searchMySkills(query: String) {
        try {
            if (_binding == null) return
            
            // 从 SkillManager 加载所有技能
            val allSkills = SkillManager.loadSkills(requireContext())
            
            // 过滤技能（不区分大小写）
            val filteredSkills = allSkills.filter { skill ->
                skill.title.contains(query, ignoreCase = true) ||
                skill.steps.any { it.contains(query, ignoreCase = true) } ||
                (skill.originalPurpose?.contains(query, ignoreCase = true) == true)
            }
            
            // 清空现有列表
            binding.llMySkillsList.removeAllViews()
            
            if (filteredSkills.isEmpty()) {
                // 显示空状态提示
                val emptyView = TextView(requireContext()).apply {
                    text = getString(R.string.no_matching_skill)
                    textSize = 14f
                    setTextColor(0xFF999999.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                binding.llMySkillsList.addView(emptyView)
            } else {
                // 显示过滤后的技能卡片
                filteredSkills.forEach { skill ->
                    val cardView = createSkillCard(skill)
                    binding.llMySkillsList.addView(cardView)
                }
            }
            
            Log.d(TAG, "搜索我的技能完成，查询: $query, 结果: ${filteredSkills.size} 个")
        } catch (e: Exception) {
            Log.e(TAG, "搜索我的技能失败: ${e.message}", e)
        }
    }
    
    /**
     * 搜索技能社区
     */
    private fun searchCommunitySkills(query: String) {
        try {
            if (_binding == null) return
            
            // 从 SkillManager 加载所有社区技能
            val allSkills = SkillManager.loadCommunitySkills(requireContext())
            
            // 过滤技能（不区分大小写）
            val filteredSkills = allSkills.filter { skill ->
                skill.title.contains(query, ignoreCase = true) ||
                skill.steps.any { it.contains(query, ignoreCase = true) } ||
                (skill.originalPurpose?.contains(query, ignoreCase = true) == true)
            }
            
            // 清空现有列表
            binding.llSkillCommunityList.removeAllViews()
            
            if (filteredSkills.isEmpty()) {
                // 显示空状态提示
                val emptyView = TextView(requireContext()).apply {
                    text = getString(R.string.no_matching_skill)
                    textSize = 14f
                    setTextColor(0xFF999999.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                binding.llSkillCommunityList.addView(emptyView)
            } else {
                // 显示过滤后的技能卡片
                filteredSkills.forEach { skill ->
                    val cardView = createCommunitySkillCard(skill)
                    binding.llSkillCommunityList.addView(cardView)
                }
            }
            
            Log.d(TAG, "搜索技能社区完成，查询: $query, 结果: ${filteredSkills.size} 个")
        } catch (e: Exception) {
            Log.e(TAG, "搜索技能社区失败: ${e.message}", e)
        }
    }
    
    /**
     * 刷新技能列表
     */
    fun refreshSkillsList() {
        isSkillsLoaded = false
        lastSkillsLoadTime = 0L
        loadedSkillsCount = 0 // 重置分页状态
        loadAndDisplaySkills()
    }
    
    /**
     * 设置滚动监听，用于检测滚动到底部时加载更多
     */
    private fun setupScrollListener() {
        binding.scrollViewMySkills.viewTreeObserver.addOnScrollChangedListener {
            if (isLoadingMore || loadedSkillsCount >= allSkills.size) return@addOnScrollChangedListener
            
            val scrollView = binding.scrollViewMySkills
            val scrollY = scrollView.scrollY
            val height = scrollView.height
            val contentHeight = scrollView.getChildAt(0)?.height ?: 0
            
            // 距离底部100dp时触发加载更多
            val threshold = 100 * resources.displayMetrics.density
            if (contentHeight > 0 && scrollY + height >= contentHeight - threshold) {
                loadMoreSkills()
            }
        }
    }
    
    /**
     * 加载并显示技能列表（分页加载，首次只加载10个）
     */
    private fun loadAndDisplaySkills() {
        try {
            if (_binding == null) return
            
            // 从 SkillManager 加载所有技能数据
            allSkills = SkillManager.loadSkills(requireContext())
            
            // 重置分页状态
            loadedSkillsCount = 0
            isLoadingMore = false
            
            // 清空现有列表
            binding.llMySkillsList.removeAllViews()
            loadMoreView = null
            
            if (allSkills.isEmpty()) {
                // 显示空状态提示
                val emptyView = TextView(requireContext()).apply {
                    text = getString(R.string.no_skills)
                    textSize = 14f
                    setTextColor(0xFF999999.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                binding.llMySkillsList.addView(emptyView)
            } else {
                // 首次只加载第一页（10个）
                val firstPage = allSkills.take(PAGE_SIZE)
                displaySkills(firstPage)
                loadedSkillsCount = firstPage.size
                
                // 更新skills变量（用于兼容其他代码）
                skills = allSkills.take(loadedSkillsCount)
                
                // 如果还有更多，添加加载更多提示
                if (loadedSkillsCount < allSkills.size) {
                    addLoadMoreView()
                }
            }
            
            Log.d(TAG, "技能列表加载完成，共 ${allSkills.size} 个技能，已显示 $loadedSkillsCount 个")
            
            // 更新"我的技能"标题，显示技能数量
            updateMySkillsTitle()
        } catch (e: Exception) {
            Log.e(TAG, "加载技能列表失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新"我的技能"标题，显示技能数量
     */
    private fun updateMySkillsTitle() {
        try {
            if (_binding == null) return
            val count = SkillManager.loadSkills(requireContext()).size
            binding.tvMySkillsTitle.text = getString(R.string.my_skills_count_format, count)
        } catch (e: Exception) {
            Log.e(TAG, "更新我的技能标题失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新"技能社区"标题，显示技能数量
     */
    private fun updateCommunityTitle() {
        try {
            if (_binding == null) return
            val count = SkillManager.loadCommunitySkills(requireContext()).size
            binding.tvCommunityTitle.text = getString(R.string.skill_community_count_format, count)
        } catch (e: Exception) {
            Log.e(TAG, "更新技能社区标题失败: ${e.message}", e)
        }
    }
    
    /**
     * 显示技能卡片列表
     */
    private fun displaySkills(skillsToDisplay: List<Skill>) {
        skillsToDisplay.forEach { skill ->
            val cardView = createSkillCard(skill)
            binding.llMySkillsList.addView(cardView)
        }
    }
    
    /**
     * 加载更多技能
     */
    private fun loadMoreSkills() {
        if (isLoadingMore || loadedSkillsCount >= allSkills.size) return
        
        isLoadingMore = true
        
        // 更新加载提示
        updateLoadMoreView(true)
        
        // 在后台线程创建卡片视图，避免阻塞主线程
        lifecycleScope.launch {
            try {
                val nextPage = allSkills.subList(
                    loadedSkillsCount,
                    minOf(loadedSkillsCount + PAGE_SIZE, allSkills.size)
                )
                
                // 在后台线程创建卡片视图
                val cardViews = withContext(Dispatchers.Default) {
                    nextPage.map { skill -> createSkillCard(skill) }
                }
                
                // 在主线程添加到列表
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    
                    // 移除加载提示
                    loadMoreView?.let { binding.llMySkillsList.removeView(it) }
                    
                    // 添加新卡片
                    cardViews.forEach { cardView ->
                        binding.llMySkillsList.addView(cardView)
                    }
                    
                    loadedSkillsCount += nextPage.size
                    
                    // 更新skills变量（用于兼容其他代码）
                    skills = allSkills.take(loadedSkillsCount)
                    
                    // 如果还有更多，添加加载更多提示
                    if (loadedSkillsCount < allSkills.size) {
                        addLoadMoreView()
                    }
                    
                    Log.d(TAG, "加载更多完成，已显示 $loadedSkillsCount/${allSkills.size} 个技能")
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载更多技能失败: ${e.message}", e)
            } finally {
                isLoadingMore = false
                updateLoadMoreView(false)
            }
        }
    }
    
    /**
     * 添加加载更多提示视图
     */
    private fun addLoadMoreView() {
        if (loadMoreView != null) return
        
        loadMoreView = TextView(requireContext()).apply {
            text = "加载更多..."
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        binding.llMySkillsList.addView(loadMoreView)
    }
    
    /**
     * 更新加载更多提示视图
     */
    private fun updateLoadMoreView(isLoading: Boolean) {
        loadMoreView?.let { view ->
            if (view is TextView) {
                view.text = if (isLoading) "正在加载..." else "加载更多..."
            }
        }
    }
    
    /**
     * 创建技能卡片（我的技能）
     */
    private fun createSkillCard(skill: Skill): View {
        return createSkillCard(skill, isMySkill = true)
    }
    
    /**
     * 执行技能
     * @param skill 技能对象
     */
    private fun executeSkill(skill: Skill) {
        try {
            Log.d(TAG, "executeSkill: 开始执行技能 - ${skill.title}")
            
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                // 切换到TopoClaw对话
                val assistantConv = Conversation(
                    id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                    name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                
                // 切换到TopoClaw对话
                mainActivity.switchToChatFragment(assistantConv)
                
                // 等待Fragment切换完成后再发送技能
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val chatFragment = mainActivity.getChatFragment()
                        if (chatFragment != null && chatFragment.isAdded) {
                            // 直接调用公开的sendSkillAsQuery方法
                            chatFragment.sendSkillAsQuery(skill)
                            Log.d(TAG, "技能已发送到TopoClaw: ${skill.title}")
                        } else {
                            Log.w(TAG, "ChatFragment未就绪，延迟重试")
                            // 如果Fragment还没准备好，再延迟一点
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    val retryFragment = mainActivity.getChatFragment()
                                    if (retryFragment != null && retryFragment.isAdded) {
                                        retryFragment.sendSkillAsQuery(skill)
                                        Log.d(TAG, "技能已发送到TopoClaw（重试）: ${skill.title}")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "重试发送技能失败: ${e.message}", e)
                                }
                            }, 500)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "发送技能到TopoClaw失败: ${e.message}", e)
                    }
                }, 300)
            } else {
                Log.e(TAG, "executeSkill: MainActivity为空")
                Toast.makeText(requireContext(), "执行技能失败: 无法获取MainActivity", Toast.LENGTH_SHORT).show()
            }
                
        } catch (e: Exception) {
            Log.e(TAG, "executeSkill异常: ${e.message}", e)
            Toast.makeText(requireContext(), "执行技能失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 创建技能卡片（技能社区）
     */
    private fun createCommunitySkillCard(skill: Skill): View {
        return createSkillCard(skill, isMySkill = false)
    }
    
    /**
     * 创建技能卡片
     * @param skill 技能对象
     * @param isMySkill 是否为"我的技能"（true）或"技能社区"（false）
     */
    private fun createSkillCard(skill: Skill, isMySkill: Boolean): View {
        // 加载卡片布局
        val inflater = LayoutInflater.from(requireContext())
        val targetContainer = if (isMySkill) binding.llMySkillsList else binding.llSkillCommunityList
        
        // 我的技能使用可滑动布局，技能社区使用普通布局
        val layoutRes = if (isMySkill) R.layout.item_skill_card_swipe else R.layout.item_skill_card
        val container = inflater.inflate(layoutRes, targetContainer, false)
        
        // 获取卡片视图
        val cardView = if (isMySkill) {
            container.findViewById<androidx.cardview.widget.CardView>(R.id.cardView)
        } else {
            container as? androidx.cardview.widget.CardView ?: container
        }
        
        // 获取复选框
        val checkBox = if (isMySkill) {
            cardView.findViewById<android.widget.CheckBox>(R.id.checkBox)
        } else {
            container.findViewById<android.widget.CheckBox>(R.id.checkBox)
        }
        
        // 设置复选框状态和可见性
        checkBox.isChecked = selectedSkills.contains(skill.id)
        checkBox.visibility = if (isMultiSelectMode && 
            ((isMySkill && multiSelectModeType == 0) || (!isMySkill && multiSelectModeType == 1))) {
            View.VISIBLE
        } else {
            View.GONE
        }
        
        // 复选框点击事件
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedSkills.add(skill.id)
            } else {
                selectedSkills.remove(skill.id)
            }
            updateSelectedCount()
        }
        
        // 保存卡片视图映射
        skillCardViews[skill.id] = container
        
        // 设置标题（最多显示两行）
        val titleView = if (isMySkill) {
            cardView.findViewById<TextView>(R.id.tvSkillTitle)
        } else {
            container.findViewById<TextView>(R.id.tvSkillTitle)
        }
        titleView.text = skill.title
        
        // 设置热门标识（仅在技能社区显示）
        val hotTagView = if (isMySkill) {
            cardView.findViewById<TextView>(R.id.tvHotTag)
        } else {
            container.findViewById<TextView>(R.id.tvHotTag)
        }
        hotTagView?.visibility = if (skill.isHot) View.VISIBLE else View.GONE
        
        // 执行按钮（暂时无效果）
        val executeButton = if (isMySkill) {
            cardView.findViewById<android.widget.ImageButton>(R.id.btnExecute)
        } else {
            container.findViewById<android.widget.ImageButton>(R.id.btnExecute)
        }
        executeButton.setOnClickListener {
            // 执行技能：跳转到技能学习小助手并执行
            Log.d(TAG, "点击执行按钮: ${skill.title}")
            executeSkill(skill)
        }
        
        // 编辑按钮和分享按钮容器
        val editButton = if (isMySkill) {
            cardView.findViewById<android.widget.ImageButton>(R.id.btnEdit)
        } else {
            container.findViewById<android.widget.ImageButton>(R.id.btnEdit)
        }
        val shareButton = if (isMySkill) {
            cardView.findViewById<android.widget.ImageButton>(R.id.btnShare)
        } else {
            container.findViewById<android.widget.ImageButton>(R.id.btnShare)
        }
        
        if (isMySkill) {
            // 我的技能：显示编辑和分享按钮
            editButton.visibility = View.VISIBLE
            shareButton.visibility = View.VISIBLE
            
            // 编辑按钮 - 直接编辑标题
            editButton.setOnClickListener {
                enableTitleEditing(titleView, skill)
            }
            
            // 分享按钮
            shareButton.setOnClickListener {
                showShareOptionsDialog(skill)
            }
            
            // 标题点击和滑动处理在 setupSwipeToDelete 中统一处理
            // 长按删除功能
            cardView.setOnLongClickListener {
                showDeleteConfirmDialog(skill)
                true
            }
        } else {
            // 技能社区：隐藏编辑按钮，分享按钮改为加号按钮
            editButton.visibility = View.GONE
            
            // 将分享按钮改为加号按钮
            shareButton.setImageResource(android.R.drawable.ic_input_add)
            shareButton.contentDescription = "添加到我的技能"
            shareButton.setOnClickListener {
                addSkillToMySkills(skill)
            }
            
            // 点击标题区域跳转到详情页（但不能编辑）
            // 多选模式下，点击卡片切换复选框状态
            titleView.setOnClickListener {
                if (isMultiSelectMode && multiSelectModeType == 1) {
                    // 多选模式下，切换复选框状态
                    checkBox.isChecked = !checkBox.isChecked
                } else {
                    navigateToSkillDetail(skill.id, allowEdit = false)
                }
            }
            
            // 技能社区卡片不支持长按删除
        }
        
        // 多选模式下，点击整个卡片切换复选框状态
        if (isMultiSelectMode && 
            ((isMySkill && multiSelectModeType == 0) || (!isMySkill && multiSelectModeType == 1))) {
            container.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }
        }
        
        // 如果是我的技能，设置左滑删除功能
        if (isMySkill) {
            setupSwipeToDelete(container, cardView, skill)
            return container
        }
        
        return container
    }
    
    /**
     * 设置左滑删除功能（支持标题区域滑动，使用距离+速度组合判断）
     */
    private fun setupSwipeToDelete(container: View, cardView: View, skill: Skill) {
        val deleteBackground = container.findViewById<View>(R.id.llDeleteBackground)
        val deleteIcon = container.findViewById<android.widget.ImageView>(R.id.ivDelete)
        val titleView = cardView.findViewById<TextView>(R.id.tvSkillTitle)
        
        var startX = 0f
        var startY = 0f
        var startTime = 0L
        var lastX = 0f
        var lastTime = 0L
        var currentX = 0f
        var isSwipeOpen = false
        var isSwipeDetected = false // 是否检测到滑动
        
        val density = requireContext().resources.displayMetrics.density
        // 滑动距离阈值（dp转px）- 降低阈值，让滑动更容易触发
        val swipeThreshold = 80f * density
        // 点击距离阈值（10dp）- 降低阈值，让滑动更容易检测
        val clickDistanceThreshold = 10f * density
        // 滑动速度阈值（300dp/s）- 降低阈值，让滑动更容易检测
        val swipeVelocityThreshold = 300f * density / 1000f // 转换为px/ms
        
        // 辅助函数：向上查找 ScrollView 并阻止它拦截触摸事件
        fun requestDisallowInterceptTouchEvent(view: View, disallow: Boolean) {
            var parent = view.parent
            while (parent != null && parent is android.view.ViewGroup) {
                parent.requestDisallowInterceptTouchEvent(disallow)
                if (parent is android.widget.ScrollView) {
                    // 找到 ScrollView，确保阻止它拦截触摸事件
                    break
                }
                parent = parent.parent
            }
        }
        
        // 点击删除图标
        deleteIcon.setOnClickListener {
            deleteSkillWithAnimation(container, skill)
        }
        
        // 创建统一的触摸监听器，应用到整个卡片
        val touchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    lastX = event.rawX
                    startTime = System.currentTimeMillis()
                    lastTime = startTime
                    currentX = 0f
                    isSwipeDetected = false
                    
                    // 如果已经打开，点击其他地方关闭
                    if (isSwipeOpen && view != deleteIcon) {
                        cardView.animate()
                            .translationX(0f)
                            .setDuration(200)
                            .start()
                        deleteBackground.visibility = View.GONE
                        isSwipeOpen = false
                        return@OnTouchListener true
                    }
                    
                    // 立即返回true，确保能接收到后续事件
                    // 阻止 ScrollView 拦截触摸事件
                    requestDisallowInterceptTouchEvent(view, true)
                    // 确保能接收到后续的 ACTION_MOVE 和 ACTION_UP 事件
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val currentTime = System.currentTimeMillis()
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    val absDeltaX = kotlin.math.abs(deltaX)
                    val absDeltaY = kotlin.math.abs(deltaY)
                    
                    // 计算速度（px/ms）
                    val timeDelta = (currentTime - lastTime).coerceAtLeast(1L)
                    val velocityX = if (timeDelta > 0) {
                        kotlin.math.abs((event.rawX - lastX) / timeDelta)
                    } else {
                        0f
                    }
                    
                    lastX = event.rawX
                    lastTime = currentTime
                    
                    // 组合判断：距离或速度超过阈值，视为滑动
                    val isHorizontalSwipe = absDeltaX > clickDistanceThreshold || velocityX > swipeVelocityThreshold
                    val isVerticalScroll = absDeltaY > absDeltaX * 1.5f // 垂直滑动距离是水平的1.5倍以上，可能是列表滚动
                    
                    // 如果是水平滑动（向左），处理滑动逻辑
                    // 只要水平滑动距离超过阈值且向左滑动，就处理
                    if (isHorizontalSwipe && !isVerticalScroll && deltaX < 0) {
                        isSwipeDetected = true
                        currentX = deltaX
                        val translationX = currentX.coerceAtLeast(-swipeThreshold)
                        cardView.translationX = translationX
                        
                        // 显示/隐藏删除背景
                        if (translationX < -swipeThreshold * 0.1f) {
                            deleteBackground.visibility = View.VISIBLE
                            isSwipeOpen = true
                        } else {
                            deleteBackground.visibility = View.GONE
                            isSwipeOpen = false
                        }
                        requestDisallowInterceptTouchEvent(view, true)
                        true
                    } else if (isHorizontalSwipe && deltaX > 0 && isSwipeOpen) {
                        // 向右滑动时关闭删除按钮
                        isSwipeDetected = true
                        currentX = deltaX
                        val translationX = (cardView.translationX + deltaX).coerceAtMost(0f)
                        cardView.translationX = translationX
                        
                        if (translationX > -swipeThreshold * 0.1f) {
                            deleteBackground.visibility = View.GONE
                            isSwipeOpen = false
                        }
                        requestDisallowInterceptTouchEvent(view, true)
                        true
                    } else if (isVerticalScroll) {
                        // 垂直滚动，交给父视图处理
                        requestDisallowInterceptTouchEvent(view, false)
                        false
                    } else {
                        // 小范围移动，可能是点击前的微调，继续拦截事件
                        requestDisallowInterceptTouchEvent(view, true)
                        true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    requestDisallowInterceptTouchEvent(view, false)
                    
                    // 如果检测到滑动，处理滑动结束逻辑
                    if (isSwipeDetected) {
                        // 根据滑动距离决定是打开还是关闭
                        if (currentX < -swipeThreshold * 0.1f) {
                            // 打开删除按钮
                            cardView.animate()
                                .translationX(-swipeThreshold)
                                .setDuration(200)
                                .start()
                            deleteBackground.visibility = View.VISIBLE
                            isSwipeOpen = true
                        } else {
                            // 关闭删除按钮
                            cardView.animate()
                                .translationX(0f)
                                .setDuration(200)
                                .start()
                            deleteBackground.visibility = View.GONE
                            isSwipeOpen = false
                        }
                        true
                    } else {
                        // 没有检测到滑动，可能是点击
                        val totalTime = System.currentTimeMillis() - startTime
                        val totalDistance = kotlin.math.sqrt(
                            (event.rawX - startX) * (event.rawX - startX) + 
                            (event.rawY - startY) * (event.rawY - startY)
                        )
                        
                        // 如果移动距离很小且时间很短，视为点击
                        if (totalDistance < clickDistanceThreshold && totalTime < 300) {
                            // 触发点击事件：跳转到详情页
                            if (!isSwipeOpen) {
                                navigateToSkillDetail(skill.id)
                            } else {
                                // 如果删除按钮已打开，点击关闭
                                cardView.animate()
                                    .translationX(0f)
                                    .setDuration(200)
                                    .start()
                                deleteBackground.visibility = View.GONE
                                isSwipeOpen = false
                            }
                        }
                        false // 返回false，允许其他点击事件处理
                    }
                }
                else -> false
            }
        }
        
        // 将触摸监听器应用到 cardView，因为它是实际滑动的视图
        // cardView 包含了所有内容，包括按钮，所以在这里设置可以捕获所有触摸事件
        cardView.setOnTouchListener(touchListener)
        
        // 确保 cardView 可以接收触摸事件
        cardView.isClickable = true
        cardView.isFocusable = true
        
        // 同时也在 container 上设置，确保能捕获所有触摸事件
        container.setOnTouchListener(touchListener)
        
        // 注意：不设置 cardView.setOnClickListener，避免与触摸监听器冲突
        // 点击事件已在触摸监听器的 ACTION_UP 中处理
        // 按钮的点击事件仍然可以正常工作，因为触摸监听器在 ACTION_UP 时如果不是滑动会返回 false
    }
    
    /**
     * 删除技能并添加向左滑出动画
     */
    private fun deleteSkillWithAnimation(container: View, skill: Skill) {
        val cardView = container.findViewById<androidx.cardview.widget.CardView>(R.id.cardView)
        val parent = container.parent as? android.view.ViewGroup
        
        // 向左滑出动画
        cardView.animate()
            .translationX(-container.width.toFloat())
            .alpha(0f)
            .setDuration(300)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 执行删除
                    val success = SkillManager.deleteSkill(requireContext(), skill.id)
                    if (success) {
                        // 从父容器移除
                        parent?.removeView(container)
                        Toast.makeText(requireContext(), "技能已删除", Toast.LENGTH_SHORT).show()
                        // 更新"我的技能"标题，显示新的技能数量
                        updateMySkillsTitle()
                    } else {
                        // 删除失败，恢复位置
                        cardView.translationX = 0f
                        cardView.alpha = 1f
                        Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            .start()
    }
    
    /**
     * 将技能添加到"我的技能"
     */
    private fun addSkillToMySkills(skill: Skill, showToast: Boolean = true): Boolean {
        // 创建新ID的技能，避免ID冲突
        val newSkill = skill.copy(
            id = java.util.UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis()
        )
        val success = SkillManager.saveSkill(requireContext(), newSkill)
        if (success) {
            // 如果该技能在临时存储中，删除它（因为已经添加到"我的技能"了）
            TemporarySkillManager.deleteTemporarySkill(requireContext(), skill.id)
            if (showToast) {
                Toast.makeText(requireContext(), "技能已添加到我的技能", Toast.LENGTH_SHORT).show()
            }
            // 刷新"我的技能"列表
            loadAndDisplaySkills()
            return true
        } else {
            if (showToast) {
                Toast.makeText(requireContext(), "添加失败，该技能可能已存在", Toast.LENGTH_SHORT).show()
            }
            return false
        }
    }
    
    /**
     * 启用标题直接编辑
     */
    private fun enableTitleEditing(titleView: TextView, skill: Skill) {
        // 将TextView转换为可编辑状态
        val editText = android.widget.EditText(requireContext()).apply {
            setText(skill.title)
            setSelection(skill.title.length)
            textSize = titleView.textSize / requireContext().resources.displayMetrics.scaledDensity
            setTextColor(titleView.currentTextColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(
                titleView.paddingLeft,
                titleView.paddingTop,
                titleView.paddingRight,
                titleView.paddingBottom
            )
            background = null // 移除背景，使其看起来像TextView
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        
        // 获取父容器
        val parent = titleView.parent as? android.view.ViewGroup
        if (parent != null) {
            val index = parent.indexOfChild(titleView)
            val layoutParams = titleView.layoutParams
            
            // 移除原TextView，添加EditText
            parent.removeView(titleView)
            parent.addView(editText, index, layoutParams)
            
            // 聚焦并显示键盘
            editText.requestFocus()
            editText.post {
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            
            // 监听失去焦点时保存
            editText.setOnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) {
                    val newTitle = (view as android.widget.EditText).text.toString().trim()
                    val finalTitle = if (newTitle.isNotEmpty()) newTitle else skill.title
                    
                    if (newTitle.isNotEmpty() && newTitle != skill.title) {
                        val success = SkillManager.updateSkillTitle(requireContext(), skill.id, newTitle)
                        if (success) {
                            // 刷新列表
                            loadAndDisplaySkills()
                        } else {
                            Toast.makeText(requireContext(), "更新失败", Toast.LENGTH_SHORT).show()
                            // 即使更新失败，也恢复显示
                            restoreTitleView(parent, editText, finalTitle, skill.id, index, layoutParams)
                        }
                    } else {
                        // 如果没有变化，直接恢复
                        restoreTitleView(parent, editText, finalTitle, skill.id, index, layoutParams)
                    }
                }
            }
            
            // 监听回车键保存
            editText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    editText.clearFocus()
                    true
                } else {
                    false
                }
            }
        }
    }
    
    /**
     * 恢复标题为TextView
     */
    private fun restoreTitleView(
        parent: android.view.ViewGroup,
        editText: android.widget.EditText,
        title: String,
        skillId: String,
        index: Int,
        layoutParams: android.view.ViewGroup.LayoutParams
    ) {
        val titleView = TextView(requireContext()).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF000000.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(
                editText.paddingLeft,
                editText.paddingTop,
                editText.paddingRight,
                editText.paddingBottom
            )
            this.layoutParams = editText.layoutParams
            // 恢复点击事件
            setOnClickListener {
                navigateToSkillDetail(skillId)
            }
        }
        
        parent.removeView(editText)
        parent.addView(titleView, index, layoutParams)
        
        // 隐藏键盘
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }
    
    /**
     * 显示分享选项对话框
     */
    private fun showShareOptionsDialog(skill: Skill) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("选择分享方式")
            .setItems(arrayOf("分享到技能社区", "分享到好友/群组")) { _, which ->
                when (which) {
                    0 -> shareSkillToCommunity(skill)
                    1 -> shareSkillToFriendOrGroup(skill)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 分享技能到好友/群组
     */
    private fun shareSkillToFriendOrGroup(skill: Skill) {
        // 获取好友列表和群组列表
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" }
        val groups = GroupManager.getGroups(requireContext())
        
        // 构建选项列表
        val options = mutableListOf<String>()
        val optionTypes = mutableListOf<String>() // "friend" 或 "group"
        
        // 添加好友选项
        friends.forEach { friend ->
            val friendName = friend.nickname ?: friend.imei.take(8) + "..."
            options.add("好友: $friendName")
            optionTypes.add("friend_${friend.imei}")
        }
        
        // 添加群组选项
        groups.forEach { group ->
            options.add("群组: ${group.name}")
            optionTypes.add("group_${group.groupId}")
        }
        
        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "暂无好友或群组可分享", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示选择对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("选择分享对象")
            .setItems(options.toTypedArray()) { _, which ->
                val selectedType = optionTypes[which]
                val skillMessage = formatSkillAsMessage(skill)
                
                if (selectedType.startsWith("friend_")) {
                    val targetImei = selectedType.removePrefix("friend_")
                    sendSkillToFriend(targetImei, skill, skillMessage)
                } else if (selectedType.startsWith("group_")) {
                    val groupId = selectedType.removePrefix("group_")
                    sendSkillToGroup(groupId, skill, skillMessage)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 将技能格式化为消息内容
     */
    private fun formatSkillAsMessage(skill: Skill): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.skill_share_format, skill.title))
        sb.append(getString(R.string.action_steps_label))
        sb.append("\n")
        skill.steps.forEachIndexed { index, step ->
            sb.append("${index + 1}. $step\n")
        }
        if (skill.originalPurpose != null) {
            sb.append(getString(R.string.original_purpose_format, skill.originalPurpose))
        }
        return sb.toString()
    }
    
    /**
     * 发送技能到好友
     */
    private fun sendSkillToFriend(targetImei: String, skill: Skill, message: String) {
        try {
            val mainActivity = activity as? MainActivity
            val webSocket = mainActivity?.getCustomerServiceWebSocket()
            
            if (webSocket != null && webSocket.isConnected()) {
                // 传递skillId，以便接收端能正确识别和显示技能卡片
                webSocket.sendFriendMessage(targetImei, message, skillId = skill.id)
                
                // 切换到好友对话页面并添加技能消息到聊天记录
                val friendConversationId = "friend_$targetImei"
                val friendConversation = Conversation(
                    id = friendConversationId,
                    name = "好友",
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                val chatFragment = mainActivity?.getOrCreateChatFragment(friendConversation)
                chatFragment?.addSkillMessage(skill, "我", isUserMessage = true)
                mainActivity?.switchToChatFragment(friendConversation)
                
                Toast.makeText(requireContext(), "技能已分享给好友", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "无法连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "分享技能到好友失败: ${e.message}", e)
            Toast.makeText(requireContext(), "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 发送技能到群组
     */
    private fun sendSkillToGroup(groupId: String, skill: Skill, message: String) {
        try {
            val mainActivity = activity as? MainActivity
            val webSocket = mainActivity?.getCustomerServiceWebSocket()
            
            if (webSocket != null && webSocket.isConnected()) {
                webSocket.sendGroupMessage(groupId, message)
                
                // 切换到群组对话页面并添加技能消息到聊天记录
                val groupConversationId = if (groupId == "friends") {
                    ConversationListFragment.CONVERSATION_ID_GROUP
                } else {
                    "group_$groupId"
                }
                val groupConversation = Conversation(
                    id = groupConversationId,
                    name = "群组",
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                val chatFragment = mainActivity?.getOrCreateChatFragment(groupConversation)
                chatFragment?.addSkillMessage(skill, "我", isUserMessage = true)
                mainActivity?.switchToChatFragment(groupConversation)
                
                Toast.makeText(requireContext(), "技能已分享到群组", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "无法连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "分享技能到群组失败: ${e.message}", e)
            Toast.makeText(requireContext(), "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 分享技能到技能社区（本地+云端）
     */
    private fun shareSkillToCommunity(skill: Skill) {
        // 先保存到本地技能社区
        val localSuccess = SkillManager.saveSkillToCommunity(requireContext(), skill)
        
        if (localSuccess) {
            Toast.makeText(requireContext(), "技能已分享到本地技能社区", Toast.LENGTH_SHORT).show()
            // 刷新技能社区列表
            refreshCommunitySkillsList()
        } else {
            // 本地已存在，但继续上传到云端
            Log.d(TAG, "技能在本地社区已存在，继续上传到云端")
        }
        
        // 上传到云端技能服务
        uploadSkillToCloudForShare(skill)
    }
    
    /**
     * 为分享功能上传技能到云端
     */
    private fun uploadSkillToCloudForShare(skill: Skill) {
        lifecycleScope.launch {
            try {
                // 获取技能服务地址
                val skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(requireContext())
                Log.d(TAG, "开始上传技能到云端: ${skill.title}, URL: $skillServiceUrl")
                
                // 检查技能是否已存在
                Log.d(TAG, "检查技能是否已存在: ${skill.title}")
                val exists = SkillManager.checkSkillExistsInService(
                    context = requireContext(),
                    skillName = skill.title,
                    skillServiceUrl = skillServiceUrl
                )
                Log.d(TAG, "技能存在检查结果: $exists")
                
                if (exists) {
                    // 技能已存在，询问用户是否覆盖
                    Log.d(TAG, "技能已存在，显示覆盖对话框")
                    withContext(Dispatchers.Main) {
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("云端技能已存在")
                            .setMessage("技能「${skill.title}」已在云端技能服务中存在，是否要覆盖上传？\n\n注意：覆盖上传会创建新的技能记录，不会删除旧记录。")
                            .setPositiveButton("覆盖上传") { _, _ ->
                                Log.d(TAG, "用户选择覆盖上传")
                                // 用户选择覆盖，继续上传
                                performUploadForShare(skill, skillServiceUrl)
                            }
                            .setNegativeButton("跳过") { _, _ ->
                                Log.d(TAG, "用户选择跳过上传")
                                Toast.makeText(requireContext(), "已跳过云端上传", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    return@launch
                }
                
                // 技能不存在，直接上传
                Log.d(TAG, "技能不存在，直接上传")
                performUploadForShare(skill, skillServiceUrl)
                
            } catch (e: Exception) {
                Log.e(TAG, "检查云端技能异常: ${e.message}", e)
                e.printStackTrace()
                // 检查出错时，仍然尝试上传
                performUploadForShare(skill, ServiceUrlConfig.getSkillCommunityUrl(requireContext()))
            }
        }
    }
    
    /**
     * 执行分享功能的上传操作
     */
    private fun performUploadForShare(skill: Skill, skillServiceUrl: String) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "开始执行分享上传，技能: ${skill.title}, URL: $skillServiceUrl")
                
                // 执行上传
                val result = SkillManager.uploadSkillToService(
                    context = requireContext(),
                    skill = skill,
                    skillServiceUrl = skillServiceUrl,
                    skipIfExists = false  // 已经检查过了，直接上传
                )
                
                Log.d(TAG, "分享上传结果: success=${result.success}, message=${result.message}, skillId=${result.skillId}")
                
                // 显示结果
                val message = when {
                    result.success -> "已分享到云端：${skill.title}"
                    result.skipped -> "云端已存在，已跳过：${skill.title}"
                    else -> "云端上传失败：${result.message}"
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "分享上传异常: ${e.message}", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "云端上传失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * 加载并显示技能社区列表
     */
    private fun loadAndDisplayCommunitySkills() {
        try {
            if (_binding == null) return
            
            // 从 SkillManager 加载技能社区技能
            val communitySkills = SkillManager.loadCommunitySkills(requireContext())
            
            // 清空现有列表
            binding.llSkillCommunityList.removeAllViews()
            
            if (communitySkills.isEmpty()) {
                // 显示空状态提示
                val emptyView = TextView(requireContext()).apply {
                    text = getString(R.string.no_content)
                    textSize = 14f
                    setTextColor(0xFF999999.toInt())
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                binding.llSkillCommunityList.addView(emptyView)
            } else {
                // 显示技能卡片（使用社区卡片样式）
                communitySkills.forEach { skill ->
                    val cardView = createCommunitySkillCard(skill)
                    binding.llSkillCommunityList.addView(cardView)
                }
            }
            
            Log.d(TAG, "技能社区列表加载完成，共 ${communitySkills.size} 个技能")
            
            // 更新"技能社区"标题，显示技能数量
            updateCommunityTitle()
        } catch (e: Exception) {
            Log.e(TAG, "加载技能社区列表失败: ${e.message}", e)
        }
    }
    
    /**
     * 从技能服务下载技能到技能社区（批量下载）
     */
    private fun downloadSkillsFromService() {
        lifecycleScope.launch {
            try {
                // 获取技能服务地址
                val skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(requireContext())
                
                Log.d(TAG, "开始从云端获取技能列表，服务地址: $skillServiceUrl")
                
                // 初始化技能服务网络
                SkillServiceNetwork.initialize(skillServiceUrl)
                val apiService = SkillServiceNetwork.getApiService()
                
                if (apiService == null) {
                    Toast.makeText(requireContext(), "技能服务未初始化", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // 从云端获取技能列表
                val response = apiService.getSkillsForMobile()
                
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "获取技能列表失败: code=${response.code()}, errorBody=$errorBody")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "获取技能列表失败: HTTP ${response.code()}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                val skillServiceResponse = response.body()
                val cloudSkills = skillServiceResponse?.skills ?: emptyList()
                
                if (cloudSkills.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.no_skills_to_download), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // 加载本地已有的技能（用于去重）
                val localSkills = SkillManager.loadCommunitySkills(requireContext())
                val localTitles = localSkills.map { it.title.lowercase() }.toSet()
                
                // 过滤出未下载的技能
                val availableSkills = cloudSkills.filter { 
                    !localTitles.contains(it.title.lowercase()) 
                }
                
                if (availableSkills.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.all_skills_downloaded), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // 显示选择对话框
                withContext(Dispatchers.Main) {
                    showDownloadSkillSelectionDialog(availableSkills, cloudSkills)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "获取技能列表异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "获取技能列表失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * 显示技能下载选择对话框
     */
    private fun showDownloadSkillSelectionDialog(availableSkills: List<Skill>, allSkills: List<Skill>) {
        val skillTitles = availableSkills.map { it.title }.toTypedArray()
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.select_skill_to_download_format, availableSkills.size))
            .setItems(skillTitles) { _, which ->
                val selectedSkill = availableSkills[which]
                downloadSingleSkill(selectedSkill)
            }
            .setNeutralButton(getString(R.string.download_all)) { _, _ ->
                downloadAllSkills(availableSkills)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 下载单个技能
     */
    private fun downloadSingleSkill(skill: Skill) {
        lifecycleScope.launch {
            try {
                val success = SkillManager.saveSkillToCommunity(requireContext(), skill)
                withContext(Dispatchers.Main) {
                    if (success) {
                        // 检查并更新新热门技能状态
                        HotSkillBadgeManager.checkAndUpdateNewHotSkills(requireContext())
                        // 更新徽章显示
                        updateHotSkillBadge()
                        Toast.makeText(requireContext(), getString(R.string.downloaded_format, skill.title), Toast.LENGTH_SHORT).show()
                        refreshCommunitySkillsList()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.download_failed_exists), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "下载技能失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.download_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 批量下载所有技能
     */
    private fun downloadAllSkills(skills: List<Skill>) {
        if (skills.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_skills_to_download), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示确认对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_download))
            .setMessage(getString(R.string.batch_download_confirm_format, skills.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                performBatchDownload(skills)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 执行批量下载
     */
    private fun performBatchDownload(skills: List<Skill>) {
        // 禁用下载按钮
        binding.btnDownloadSkills.isEnabled = false
        
        // 显示进度提示
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setTitle(getString(R.string.batch_downloading))
            setMessage(getString(R.string.downloading_skills))
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = skills.size
            setCancelable(false)
            show()
        }
        
        lifecycleScope.launch {
            var successCount = 0
            var skippedCount = 0
            var failCount = 0
            
            skills.forEachIndexed { index, skill ->
                try {
                    // 更新进度
                    withContext(Dispatchers.Main) {
                        progressDialog.progress = index
                        progressDialog.setMessage(getString(R.string.downloading_skill_format, skill.title, index + 1, skills.size))
                    }
                    
                    // 下载技能到本地社区
                    val success = SkillManager.saveSkillToCommunity(requireContext(), skill)
                    if (success) {
                        successCount++
                        Log.d(TAG, "技能下载成功: ${skill.title}")
                    } else {
                        skippedCount++
                        Log.d(TAG, "技能已存在，跳过: ${skill.title}")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "批量下载技能失败: ${skill.title}, ${e.message}", e)
                    failCount++
                }
            }
            
            // 完成
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                
                // 检查并更新新热门技能状态
                HotSkillBadgeManager.checkAndUpdateNewHotSkills(requireContext())
                // 更新徽章显示
                updateHotSkillBadge()
                
                // 刷新技能社区列表
                refreshCommunitySkillsList()
                
                // 显示结果
                val message = buildString {
                    append(getString(R.string.batch_download_complete)).append("\n")
                    append(getString(R.string.batch_complete_success)).append(" $successCount\n")
                    if (skippedCount > 0) append(getString(R.string.batch_complete_skipped)).append(" $skippedCount\n")
                    if (failCount > 0) append(getString(R.string.batch_complete_failed)).append(" $failCount")
                }
                
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.download_complete))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
            
            binding.btnDownloadSkills.isEnabled = true
        }
    }
    
    /**
     * 从技能服务同步技能到技能社区（下拉刷新时调用，保留原有功能）
     */
    private fun syncSkillsFromService() {
        lifecycleScope.launch {
            try {
                // 获取技能服务地址
                val skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(requireContext())
                
                Log.d(TAG, "开始同步技能，服务地址: $skillServiceUrl")
                
                // 执行同步
                val result = SkillManager.syncSkillsFromService(
                    context = requireContext(),
                    skillServiceUrl = skillServiceUrl
                )
                
                Log.d(TAG, "同步结果: success=${result.success}, message=${result.message}, synced=${result.syncedCount}, skipped=${result.skippedCount}")
                
                // 显示结果
                val message = if (result.success) {
                    if (result.syncedCount > 0) {
                        "同步成功：新增 ${result.syncedCount} 个技能"
                    } else if (result.skippedCount > 0) {
                        "同步完成：跳过 ${result.skippedCount} 个已存在的技能"
                    } else {
                        "没有可同步的技能"
                    }
                } else {
                    "同步失败：${result.message}"
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    
                    // 如果同步成功，刷新技能社区列表
                    if (result.success) {
                        // 检查并更新新热门技能状态
                        HotSkillBadgeManager.checkAndUpdateNewHotSkills(requireContext())
                        // 更新徽章显示
                        updateHotSkillBadge()
                        // 刷新列表
                        if (result.syncedCount > 0) {
                            refreshCommunitySkillsList()
                        }
                    }
                    
                    // 停止刷新动画
                    binding.swipeRefreshLayoutCommunity.isRefreshing = false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "同步技能异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "同步失败：${e.message}", Toast.LENGTH_LONG).show()
                    binding.swipeRefreshLayoutCommunity.isRefreshing = false
                }
            }
        }
    }
    
    /**
     * 上传我的技能到技能服务（端-->云+本地分享）
     * 支持批量操作：先分享到本地社区，再上传到云端
     */
    private fun uploadMySkillsToService() {
        // 获取我的技能列表
        val mySkills = SkillManager.loadSkills(requireContext())
        
        if (mySkills.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_skills_to_upload), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示选择对话框，让用户选择要上传的技能
        val skillTitles = mySkills.map { it.title }.toTypedArray()
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.select_skill_to_upload))
            .setItems(skillTitles) { _, which ->
                val selectedSkill = mySkills[which]
                uploadSingleSkillToService(selectedSkill)
            }
            .setNeutralButton(getString(R.string.upload_all)) { _, _ ->
                uploadAllSkillsToService(mySkills)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 批量上传所有技能
     */
    private fun uploadAllSkillsToService(skills: List<Skill>) {
        if (skills.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_skills_to_upload), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示确认对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_upload))
            .setMessage(getString(R.string.batch_upload_confirm_format, skills.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                performBatchUpload(skills)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 执行批量上传
     */
    private fun performBatchUpload(skills: List<Skill>) {
        // 禁用上传按钮
        binding.btnUploadMySkills.isEnabled = false
        
        // 显示进度提示
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setTitle(getString(R.string.batch_uploading))
            setMessage(getString(R.string.uploading_skills))
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = skills.size
            setCancelable(false)
            show()
        }
        
        lifecycleScope.launch {
            var successCount = 0
            var skippedCount = 0
            var failCount = 0
            
            skills.forEachIndexed { index, skill ->
                try {
                    // 更新进度
                    withContext(Dispatchers.Main) {
                        progressDialog.progress = index
                        progressDialog.setMessage(getString(R.string.uploading_skill_format, skill.title, index + 1, skills.size))
                    }
                    
                    // 1. 先分享到本地社区
                    val localSuccess = SkillManager.saveSkillToCommunity(requireContext(), skill)
                    if (localSuccess) {
                        Log.d(TAG, "技能已分享到本地社区: ${skill.title}")
                    }
                    
                    // 2. 上传到云端（不询问，直接上传，如果存在则跳过）
                    val uploadResult = SkillManager.uploadSkillToService(
                        context = requireContext(),
                        skill = skill,
                        skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(requireContext()),
                        skipIfExists = true  // 批量上传时，如果存在则跳过
                    )
                    
                    when {
                        uploadResult.success -> successCount++
                        uploadResult.skipped -> skippedCount++
                        else -> failCount++
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "批量上传技能失败: ${skill.title}, ${e.message}", e)
                    failCount++
                }
            }
            
            // 完成
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                
                // 刷新技能社区列表
                refreshCommunitySkillsList()
                
                // 显示结果
                val message = buildString {
                    append(getString(R.string.batch_upload_complete)).append("\n")
                    append(getString(R.string.batch_complete_success)).append(" $successCount\n")
                    if (skippedCount > 0) append(getString(R.string.batch_complete_skipped)).append(" $skippedCount\n")
                    if (failCount > 0) append(getString(R.string.batch_complete_failed)).append(" $failCount")
                }
                
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.upload_complete))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
            
            binding.btnUploadMySkills.isEnabled = true
        }
    }
    
    /**
     * 上传单个技能到技能服务（本地分享+云端上传）
     */
    private fun uploadSingleSkillToService(skill: Skill) {
        // 禁用上传按钮，防止重复点击
        binding.btnUploadMySkills.isEnabled = false
        
        lifecycleScope.launch {
            try {
                // 获取技能服务地址
                val skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(requireContext())
                
                Log.d(TAG, "开始上传技能到服务，技能: ${skill.title}, 服务地址: $skillServiceUrl")
                
                // 1. 先分享到本地技能社区
                val localSuccess = SkillManager.saveSkillToCommunity(requireContext(), skill)
                if (localSuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "已分享到本地技能社区", Toast.LENGTH_SHORT).show()
                        refreshCommunitySkillsList()
                    }
                }
                
                // 2. 检查云端是否已存在
                val exists = SkillManager.checkSkillExistsInService(
                    context = requireContext(),
                    skillName = skill.title,
                    skillServiceUrl = skillServiceUrl
                )
                
                if (exists) {
                    // 技能已存在，询问用户是否覆盖
                    withContext(Dispatchers.Main) {
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("云端技能已存在")
                            .setMessage("技能「${skill.title}」已在云端技能服务中存在，是否要覆盖上传？\n\n注意：覆盖上传会创建新的技能记录，不会删除旧记录。")
                            .setPositiveButton("覆盖上传") { _, _ ->
                                // 用户选择覆盖，继续上传
                                performUpload(skill, skillServiceUrl)
                            }
                            .setNegativeButton("跳过") { _, _ ->
                                Toast.makeText(requireContext(), "已跳过云端上传", Toast.LENGTH_SHORT).show()
                                binding.btnUploadMySkills.isEnabled = true
                            }
                            .setOnDismissListener {
                                if (binding.btnUploadMySkills.isEnabled.not()) {
                                    binding.btnUploadMySkills.isEnabled = true
                                }
                            }
                            .show()
                    }
                    return@launch
                }
                
                // 技能不存在，直接上传
                performUpload(skill, skillServiceUrl)
                
            } catch (e: Exception) {
                Log.e(TAG, "检查技能异常: ${e.message}", e)
                // 检查出错时，仍然允许上传
                performUpload(skill, ServiceUrlConfig.getSkillCommunityUrl(requireContext()))
            }
        }
    }
    
    /**
     * 执行实际上传操作
     */
    private fun performUpload(skill: Skill, skillServiceUrl: String) {
        // 显示加载提示
        val loadingToast = Toast.makeText(requireContext(), "正在上传到云端...", Toast.LENGTH_SHORT)
        loadingToast.show()
        
        lifecycleScope.launch {
            try {
                // 执行上传（不跳过，因为已经检查过了）
                val result = SkillManager.uploadSkillToService(
                    context = requireContext(),
                    skill = skill,
                    skillServiceUrl = skillServiceUrl,
                    skipIfExists = false  // 已经检查过了，直接上传
                )
                
                Log.d(TAG, "上传结果: success=${result.success}, message=${result.message}, skillId=${result.skillId}, skipped=${result.skipped}")
                
                // 显示结果
                val message = when {
                    result.success && result.skipped -> "技能已在云端存在：${skill.title}"
                    result.success -> "已上传到云端：${skill.title}"
                    else -> "云端上传失败：${result.message}"
                }
                
                Log.d(TAG, "上传结果详情: success=${result.success}, skipped=${result.skipped}, message=${result.message}, skillId=${result.skillId}")
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "上传技能异常: ${e.message}", e)
                Toast.makeText(requireContext(), "云端上传失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                // 恢复上传按钮
                binding.btnUploadMySkills.isEnabled = true
            }
        }
    }
    
    /**
     * 刷新技能社区列表
     */
    fun refreshCommunitySkillsList() {
        isCommunitySkillsLoaded = false
        lastCommunitySkillsLoadTime = 0L
        loadAndDisplayCommunitySkills()
    }
    
    /**
     * 跳转到技能详情页
     * @param skillId 技能ID
     * @param allowEdit 是否允许编辑（默认为true）
     */
    private fun navigateToSkillDetail(skillId: String, allowEdit: Boolean = true) {
        try {
            val detailFragment = SkillDetailFragment.newInstance(skillId, allowEdit)
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(0, 0, 0, 0)
                .replace(R.id.fragmentContainer, detailFragment)
                .addToBackStack(null)
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            Log.e(TAG, "跳转到技能详情页失败: ${e.message}", e)
        }
    }
    
    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog(skill: Skill) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("删除技能")
            .setMessage("确定要删除技能「${skill.title}」吗？")
            .setPositiveButton("删除") { _, _ ->
                val success = SkillManager.deleteSkill(requireContext(), skill.id)
                if (success) {
                    Toast.makeText(requireContext(), "技能已删除", Toast.LENGTH_SHORT).show()
                    loadAndDisplaySkills() // 刷新列表
                } else {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        if (!isAdded || context == null || isHidden) return
        
        // 确保ActionBar隐藏（技能页面有自己的标题栏，不需要ActionBar）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            // 设置Fragment层级的底部导航栏背景颜色为浅灰色，与顶部导航栏保持一致
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            // 更新Fragment层级的底部导航栏选中状态
            binding.bottomNavigation.selectedItemId = R.id.nav_skill
            // 更新聊天图标徽章
            binding.root.post {
                mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
            }
        }
        
        // 性能优化：避免频繁刷新，只在必要时刷新列表
        val currentTime = System.currentTimeMillis()
        val shouldRefreshSkills = !isSkillsLoaded || (currentTime - lastSkillsLoadTime > REFRESH_INTERVAL_MS)
        val shouldRefreshCommunity = !isCommunitySkillsLoaded || (currentTime - lastCommunitySkillsLoadTime > REFRESH_INTERVAL_MS)
        
        if (shouldRefreshSkills) {
            loadAndDisplaySkills()
            lastSkillsLoadTime = currentTime
            isSkillsLoaded = true
        }
        
        if (shouldRefreshCommunity) {
            loadAndDisplayCommunitySkills()
            lastCommunitySkillsLoadTime = currentTime
            isCommunitySkillsLoaded = true
        }
        
        // 更新热门技能徽章
        updateHotSkillBadge()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && _binding != null) {
            (activity as? MainActivity)?.let { mainActivity ->
                if (mainActivity.supportActionBar?.isShowing == true) {
                    mainActivity.hideActionBarInstantly()
                }
                mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
                binding.bottomNavigation.selectedItemId = R.id.nav_skill
                binding.root.post {
                    if (_binding != null) {
                        mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                    }
                }
            }
            updateHotSkillBadge()
        }
    }

    /**
     * 进入多选模式
     * @param isUploadMode true为上传模式，false为下载模式
     */
    private fun enterMultiSelectMode(isUploadMode: Boolean) {
        isMultiSelectMode = true
        multiSelectModeType = if (isUploadMode) 0 else 1
        selectedSkills.clear()
        
        // 更新UI
        if (isUploadMode) {
            // 上传模式：显示在我的技能页面
            binding.tvMySkillsTitle.visibility = View.GONE
            binding.btnUploadMySkills.visibility = View.GONE
            binding.btnSearchMySkills.visibility = View.GONE
            binding.tvSelectedCountMySkills.visibility = View.VISIBLE
            binding.btnBatchUpload.visibility = View.VISIBLE
            binding.btnCancelSelectionMySkills.visibility = View.VISIBLE
        } else {
            // 下载模式：显示在技能社区页面
            binding.tvCommunityTitle.visibility = View.GONE
            binding.btnDownloadSkills.visibility = View.GONE
            binding.btnSearchCommunity.visibility = View.GONE
            binding.tvSelectedCountCommunity.visibility = View.VISIBLE
            binding.btnBatchDownload.visibility = View.VISIBLE
            binding.btnCancelSelectionCommunity.visibility = View.VISIBLE
        }
        
        // 刷新列表以显示复选框
        if (isUploadMode) {
            loadAndDisplaySkills()
        } else {
            loadAndDisplayCommunitySkills()
        }
        
        updateSelectedCount()
    }
    
    /**
     * 退出多选模式
     */
    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedSkills.clear()
        
        // 恢复UI
        binding.tvMySkillsTitle.visibility = View.VISIBLE
        binding.btnUploadMySkills.visibility = View.VISIBLE
        binding.btnSearchMySkills.visibility = View.VISIBLE
        binding.tvSelectedCountMySkills.visibility = View.GONE
        binding.btnBatchUpload.visibility = View.GONE
        binding.btnCancelSelectionMySkills.visibility = View.GONE
        
        binding.tvCommunityTitle.visibility = View.VISIBLE
        binding.btnDownloadSkills.visibility = View.VISIBLE
        binding.btnSearchCommunity.visibility = View.VISIBLE
        binding.tvSelectedCountCommunity.visibility = View.GONE
        binding.btnBatchDownload.visibility = View.GONE
        binding.btnCancelSelectionCommunity.visibility = View.GONE
        
        // 刷新列表以隐藏复选框
        loadAndDisplaySkills()
        loadAndDisplayCommunitySkills()
    }
    
    /**
     * 更新选中数量显示
     */
    private fun updateSelectedCount() {
        val count = selectedSkills.size
        if (multiSelectModeType == 0) {
            // 上传模式
            binding.tvSelectedCountMySkills.text = "已选择 $count 个"
        } else {
            // 下载模式
            binding.tvSelectedCountCommunity.text = "已选择 $count 个"
        }
    }
    
    /**
     * 从选中的技能执行批量上传
     */
    private fun performBatchUploadFromSelection() {
        if (selectedSkills.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_select_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 获取选中的技能
        val selectedSkillList = skills.filter { selectedSkills.contains(it.id) }
        
        if (selectedSkillList.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_selected_skills), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示确认对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_upload))
            .setMessage(getString(R.string.batch_upload_selected_confirm_format, selectedSkillList.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                performBatchUpload(selectedSkillList)
                exitMultiSelectMode()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 从选中的技能执行批量下载（将技能社区技能添加到我的技能）
     */
    private fun performBatchDownloadFromSelection() {
        if (selectedSkills.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_select_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 获取技能社区列表
        val communitySkills = SkillManager.loadCommunitySkills(requireContext())
        val selectedSkillList = communitySkills.filter { selectedSkills.contains(it.id) }
        
        if (selectedSkillList.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_selected_skills), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示确认对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.batch_download))
            .setMessage(getString(R.string.batch_add_to_my_skills_confirm_format, selectedSkillList.size))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                performBatchAddToMySkills(selectedSkillList)
                exitMultiSelectMode()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 批量将技能添加到我的技能
     */
    private fun performBatchAddToMySkills(skills: List<Skill>) {
        if (skills.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.no_skills_to_add), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 禁用下载按钮
        binding.btnDownloadSkills.isEnabled = false
        
        // 显示进度提示
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setTitle(getString(R.string.batch_adding))
            setMessage(getString(R.string.adding_skills))
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = skills.size
            setCancelable(false)
            show()
        }
        
        lifecycleScope.launch {
            var successCount = 0
            var skippedCount = 0
            var failCount = 0
            
            skills.forEachIndexed { index, skill ->
                try {
                    // 更新进度
                    withContext(Dispatchers.Main) {
                        progressDialog.progress = index
                        progressDialog.setMessage(getString(R.string.adding_skill_format, skill.title, index + 1, skills.size))
                    }
                    
                    // 添加到我的技能（批量操作时不显示Toast）
                    val success = addSkillToMySkills(skill, showToast = false)
                    if (success) {
                        successCount++
                        Log.d(TAG, "技能添加成功: ${skill.title}")
                    } else {
                        skippedCount++
                        Log.d(TAG, "技能已存在，跳过: ${skill.title}")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "批量添加技能失败: ${skill.title}, ${e.message}", e)
                    failCount++
                }
            }
            
            // 完成
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                
                // 刷新我的技能列表
                loadAndDisplaySkills()
                
                // 显示结果
                val message = buildString {
                    append(getString(R.string.batch_add_complete)).append("\n")
                    append(getString(R.string.batch_complete_success)).append(" $successCount\n")
                    if (skippedCount > 0) append(getString(R.string.batch_complete_skipped)).append(" $skippedCount\n")
                    if (failCount > 0) append(getString(R.string.batch_complete_failed)).append(" $failCount")
                }
                
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.add_complete))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
            
            binding.btnDownloadSkills.isEnabled = true
        }
    }
    
    /**
     * 从SOPs.json导入本地技能库
     * 所有耗时操作都在后台线程执行，避免卡顿
     */
    private fun importSkillsFromSOPs() {
        // 显示进度提示
        val progressDialog = android.app.ProgressDialog(requireContext()).apply {
            setTitle("导入本地技能库")
            setMessage("正在读取SOPs.json...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }
        
        val context = requireContext() // 在主线程获取context引用
        lifecycleScope.launch {
            try {
                // 在后台线程读取和解析JSON
                val (tasks, totalCount) = withContext(Dispatchers.IO) {
                    readSOPsFromAssets(context)
                }
                
                if (tasks.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), "SOPs.json中没有找到任务数据", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // 更新进度对话框
                withContext(Dispatchers.Main) {
                    progressDialog.max = totalCount
                    progressDialog.setMessage("正在导入技能...")
                }
                
                // 先加载所有已有技能，用于去重检查
                val existingSkills = withContext(Dispatchers.IO) {
                    SkillManager.loadSkills(context)
                }
                val existingTitles = existingSkills.map { it.title.lowercase().trim() }.toMutableSet()
                
                var successCount = 0
                var skippedCount = 0
                var failCount = 0
                
                // 在后台线程处理每个任务
                tasks.forEachIndexed { index, task ->
                    try {
                        // 更新进度（在主线程更新UI）
                        withContext(Dispatchers.Main) {
                            progressDialog.progress = index
                            progressDialog.setMessage("正在导入: ${task.query} (${index + 1}/$totalCount)")
                        }
                        
                        // 检查query和initial_task_breakdown是否有效
                        val query = task.query
                        val steps = task.task_plan?.initial_task_breakdown
                        
                        if (query.isNullOrBlank() || steps.isNullOrEmpty()) {
                            Log.w(TAG, "任务数据无效，跳过: query=$query, steps=${steps?.size}")
                            skippedCount++
                            return@forEachIndexed
                        }
                        
                        // 以技能名（query）为基准检查是否已存在，不区分大小写
                        val queryNormalized = query.trim().lowercase()
                        if (existingTitles.contains(queryNormalized)) {
                            skippedCount++
                            Log.d(TAG, "技能已存在（按名称），跳过: $query")
                            return@forEachIndexed
                        }
                        
                        // 使用saveSkillFromData方法保存技能（会自动生成ID）
                        val saved = withContext(Dispatchers.IO) {
                            SkillManager.saveSkillFromData(
                                context,
                                title = query,
                                steps = steps,
                                originalPurpose = query
                            )
                        }
                        
                        if (saved) {
                            successCount++
                            // 添加到已存在集合中，避免同一批次重复导入
                            existingTitles.add(queryNormalized)
                            Log.d(TAG, "技能导入成功: $query")
                        } else {
                            skippedCount++
                            Log.d(TAG, "技能保存失败，跳过: $query")
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "导入技能失败: ${task.query}, ${e.message}", e)
                        failCount++
                    }
                }
                
                // 完成，在主线程更新UI
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    
                    // 刷新技能列表
                    refreshSkillsList()
                    
                    // 显示完成提示
                    val message = buildString {
                        append("导入完成\n")
                        append("成功: $successCount 个\n")
                        if (skippedCount > 0) append("跳过: $skippedCount 个\n")
                        if (failCount > 0) append("失败: $failCount 个")
                    }
                    
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("导入完成")
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "导入技能库失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(requireContext(), "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * 从assets读取SOPs.json并解析
     * 在后台线程执行
     */
    private suspend fun readSOPsFromAssets(context: android.content.Context): Pair<List<SOPTask>, Int> = withContext(Dispatchers.IO) {
        try {
            // 读取assets中的SOPs.json文件
            val inputStream: InputStream = context.assets.open("SOPs.json")
            val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            inputStream.close()
            
            // 解析JSON
            val gson = Gson()
            val type = object : TypeToken<List<SOPTask>>() {}.type
            val tasks = gson.fromJson<List<SOPTask>>(jsonString, type) ?: emptyList()
            
            Log.d(TAG, "成功读取SOPs.json，共 ${tasks.size} 个任务")
            return@withContext Pair(tasks, tasks.size)
            
        } catch (e: Exception) {
            Log.e(TAG, "读取SOPs.json失败: ${e.message}", e)
            throw e
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

