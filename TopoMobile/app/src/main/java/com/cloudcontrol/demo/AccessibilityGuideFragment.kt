package com.cloudcontrol.demo

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentAccessibilityGuideBinding

/**
 * 如何开启无障碍服务Fragment
 */
class AccessibilityGuideFragment : Fragment() {
    
    companion object {
        private const val TAG = "AccessibilityGuideFragment"
    }
    
    private var _binding: FragmentAccessibilityGuideBinding? = null
    private val binding get() = _binding!!
    
    // 当前选中的tab索引：0-初次使用，1-平时使用
    private var currentTab = 0
    
    // 平时使用tab的图片列表
    private val normalUseImages = listOf(
        R.drawable.tutorial_page5_1,
        R.drawable.tutorial_page5_2,
        R.drawable.tutorial_page5_3
    )
    
    // 图片指示器列表
    private val normalUseImageIndicators = mutableListOf<View>()
    
    // 滑动监听器
    private var normalUseScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccessibilityGuideBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 设置返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 设置导航栏
        setupNavigationBar()
        
        // 设置滑动手势
        setupSwipeGesture()
        
        // 加载图片内容
        loadFirstTimeUseContent()
        loadNormalUseContent()
    }
    
    /**
     * 加载初次使用tab的内容
     */
    private fun loadFirstTimeUseContent() {
        // 加载无障碍权限设置图片
        loadAccessibilityPermissionImage()
    }
    
    /**
     * 加载平时使用tab的内容
     */
    private fun loadNormalUseContent() {
        // 加载图片
        setupNormalUseImages()
    }
    
    /**
     * 加载无障碍权限设置图片
     */
    private fun loadAccessibilityPermissionImage() {
        try {
            // 设置图片大小，与小助手小贴士中的图片大小一致
            val screenWidth = resources.displayMetrics.widthPixels
            val padding = (24 * resources.displayMetrics.density).toInt() * 2 // 左右padding
            val imageWidth = ((screenWidth - padding) * 0.425).toInt() // 图片宽度为可用宽度的42.5%
            
            val layoutParams = binding.ivAccessibilityPermission.layoutParams
            layoutParams.width = imageWidth
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.ivAccessibilityPermission.layoutParams = layoutParams
            
            // 从drawable资源加载图片
            val resourceId = resources.getIdentifier("wuzhangai0211", "drawable", requireContext().packageName)
            if (resourceId != 0) {
                Log.d(TAG, "成功找到图片资源，ID: $resourceId")
                binding.ivAccessibilityPermission.setImageResource(resourceId)
                // 添加点击放大功能
                binding.ivAccessibilityPermission.setOnClickListener {
                    showFullScreenImage(resourceId)
                }
                binding.ivAccessibilityPermission.visibility = View.VISIBLE
                return
            } else {
                Log.w(TAG, "未找到图片资源 wuzhangai0211，请确保文件已复制到 drawable 目录")
            }
            
            Log.d(TAG, "drawable资源未找到，尝试从assets加载")
            
            // 如果资源不存在，尝试从assets目录加载
            try {
                val inputStream = requireContext().assets.open("wuzhangai0211.png")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                if (bitmap != null) {
                    Log.d(TAG, "成功从assets加载图片")
                    binding.ivAccessibilityPermission.setImageBitmap(bitmap)
                    binding.ivAccessibilityPermission.visibility = View.VISIBLE
                    // 添加点击放大功能（从assets加载的图片暂时不支持全屏查看）
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "从assets加载图片失败: ${e.message}")
            }
            
            // 如果都失败了，显示错误信息但不隐藏图片（让用户知道有问题）
            Log.w(TAG, "无法加载无障碍权限设置图片，请确保图片已添加到drawable目录，文件名应为: wuzhangai0211.png")
            Log.w(TAG, "当前尝试的资源名称: wuzhangai0211, ic_wuzhangai0211")
            // 不隐藏图片，而是显示一个占位符或错误提示
            binding.ivAccessibilityPermission.visibility = View.VISIBLE
            binding.ivAccessibilityPermission.setImageResource(android.R.drawable.ic_menu_report_image)
        } catch (e: Exception) {
            Log.e(TAG, "加载无障碍权限设置图片失败: ${e.message}", e)
            e.printStackTrace()
            binding.ivAccessibilityPermission.visibility = View.VISIBLE
            binding.ivAccessibilityPermission.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }
    
    /**
     * 显示全屏图片
     */
    private fun showFullScreenImage(imageResId: Int) {
        try {
            val fullScreenDialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_image_fullscreen, null)
            fullScreenDialog.setContentView(dialogView)
            
            val ivFullScreen = dialogView.findViewById<android.widget.ImageView>(R.id.ivFullScreenImage)
            
            ivFullScreen.setImageResource(imageResId)
            ivFullScreen.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            
            ivFullScreen.setOnClickListener {
                fullScreenDialog.dismiss()
            }
            
            fullScreenDialog.window?.let { window ->
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                window.setBackgroundDrawableResource(android.R.color.transparent)
                FullscreenImageDialogHelper.applyBlackSystemBars(window, dialogView)
            }
            
            fullScreenDialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示全屏图片失败: ${e.message}", e)
        }
    }
    
    /**
     * 设置导航栏
     */
    private fun setupNavigationBar() {
        // 默认显示"初次使用"页面
        binding.scrollViewFirstTimeUse.visibility = View.VISIBLE
        binding.scrollViewNormalUse.visibility = View.GONE
        
        // 初次使用按钮
        binding.btnFirstTimeUse.setOnClickListener {
            switchToTab(0)
        }
        
        // 平时使用按钮
        binding.btnNormalUse.setOnClickListener {
            switchToTab(1)
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
        
        val touchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwipeDetected = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    false
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
                        
                        // 向右滑动：切换到前一个TAB
                        if (deltaX > swipeThreshold && currentTab > 0) {
                            switchToTab(currentTab - 1)
                            true
                        }
                        // 向左滑动：切换到后一个TAB
                        else if (deltaX < -swipeThreshold && currentTab < 1) {
                            switchToTab(currentTab + 1)
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
        
        // 在两个ScrollView上设置触摸监听
        binding.scrollViewFirstTimeUse.setOnTouchListener(touchListener)
        binding.scrollViewNormalUse.setOnTouchListener(touchListener)
    }
    
    /**
     * 切换到指定TAB
     * @param tabIndex 0: 初次使用, 1: 平时使用
     */
    private fun switchToTab(tabIndex: Int) {
        currentTab = tabIndex
        
        // 更新按钮文字颜色
        binding.btnFirstTimeUse.setTextColor(if (tabIndex == 0) 0xFF000000.toInt() else 0xFF999999.toInt())
        binding.btnNormalUse.setTextColor(if (tabIndex == 1) 0xFF000000.toInt() else 0xFF999999.toInt())
        
        // 更新页面显示
        binding.scrollViewFirstTimeUse.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.scrollViewNormalUse.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        
        // 移动指示器
        moveIndicator(tabIndex)
    }
    
    /**
     * 移动指示器到指定TAB
     */
    private fun moveIndicator(tabIndex: Int) {
        val indicator = binding.viewIndicator
        
        // 获取屏幕宽度和按钮宽度
        indicator.post {
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val buttonWidth = screenWidth / 2f // 每个按钮占1/2宽度
            
            // 计算指示器的布局参数
            val layoutParams = indicator.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (layoutParams != null) {
                val targetMarginStart = (buttonWidth * tabIndex).toInt()
                
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
            }
        }
    }
    
    /**
     * 设置平时使用tab的图片
     */
    private fun setupNormalUseImages() {
        val layoutImages = binding.layoutNormalUseImages
        val scrollViewImages = binding.scrollViewNormalUseImages
        val layoutIndicators = binding.layoutNormalUseImageIndicators
        
        layoutImages.removeAllViews()
        layoutIndicators.removeAllViews()
        normalUseImageIndicators.clear()
        
        // 如果图片数量>2，显示图片滑动指示器
        if (normalUseImages.size > 2) {
            layoutIndicators.visibility = View.VISIBLE
            setupImageIndicators(normalUseImages.size, layoutIndicators, normalUseImageIndicators)
        } else {
            layoutIndicators.visibility = View.GONE
        }
        
        // 添加图片
        val imageMargin = (12 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val padding = (24 * resources.displayMetrics.density).toInt() * 2 // 左右padding
        val imageWidth = ((screenWidth - padding) * 0.425).toInt() // 图片宽度为可用宽度的42.5%
        
        normalUseImages.forEachIndexed { index, imageResId ->
            try {
                val imageView = ImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        imageWidth,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(imageMargin / 2, imageMargin / 2, imageMargin / 2, imageMargin / 2)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    
                    try {
                        setImageResource(imageResId)
                    } catch (e: android.content.res.Resources.NotFoundException) {
                        Log.e(TAG, "图片资源不存在: $imageResId", e)
                    }
                    
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showFullScreenImage(imageResId)
                    }
                }
                
                layoutImages.addView(imageView)
            } catch (e: Exception) {
                Log.e(TAG, "添加图片失败: ${e.message}", e)
            }
        }
        
        // 监听滑动，更新图片指示器
        if (normalUseImages.size > 2) {
            normalUseScrollListener?.let { listener ->
                scrollViewImages.viewTreeObserver.removeOnScrollChangedListener(listener)
            }
            normalUseScrollListener = ViewTreeObserver.OnScrollChangedListener {
                updateImageIndicators(scrollViewImages, normalUseImageIndicators, imageWidth, imageMargin)
            }
            scrollViewImages.viewTreeObserver.addOnScrollChangedListener(normalUseScrollListener!!)
            scrollViewImages.post {
                updateImageIndicators(scrollViewImages, normalUseImageIndicators, imageWidth, imageMargin)
            }
        }
    }
    
    /**
     * 设置图片滑动指示器
     */
    private fun setupImageIndicators(imageCount: Int, layoutIndicators: LinearLayout, indicators: MutableList<View>) {
        val indicatorSize = (6 * resources.displayMetrics.density).toInt()
        val indicatorMargin = (4 * resources.displayMetrics.density).toInt()
        
        for (i in 0 until imageCount) {
            val indicator = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(indicatorSize, indicatorSize).apply {
                    setMargins(indicatorMargin, 0, indicatorMargin, 0)
                }
                setBackgroundColor(Color.parseColor("#CCCCCC"))
                alpha = 0.5f
            }
            
            indicators.add(indicator)
            layoutIndicators.addView(indicator)
        }
        
        // 初始化第一个为选中状态
        if (indicators.isNotEmpty()) {
            updateImageIndicators(null, indicators, 0, 0)
        }
    }
    
    /**
     * 更新图片滑动指示器状态
     */
    private fun updateImageIndicators(
        scrollView: HorizontalScrollView?,
        indicators: MutableList<View>,
        imageWidth: Int,
        imageMargin: Int
    ) {
        if (indicators.isEmpty()) return
        
        val activeIndex = if (scrollView != null) {
            val scrollX = scrollView.scrollX
            val screenWidth = resources.displayMetrics.widthPixels
            val totalImageWidth = imageWidth + imageMargin
            val currentIndex = if (totalImageWidth > 0) {
                (scrollX + screenWidth / 2) / totalImageWidth
            } else {
                0
            }
            currentIndex.coerceIn(0, indicators.size - 1)
        } else {
            0
        }
        
        indicators.forEachIndexed { index, indicator ->
            if (index == activeIndex) {
                indicator.alpha = 1.0f
                indicator.scaleX = 1.2f
                indicator.scaleY = 1.2f
                indicator.setBackgroundColor(Color.parseColor("#2196F3"))
            } else {
                indicator.alpha = 0.5f
                indicator.scaleX = 1.0f
                indicator.scaleY = 1.0f
                indicator.setBackgroundColor(Color.parseColor("#CCCCCC"))
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 清理滑动监听器
        normalUseScrollListener?.let { listener ->
            binding.scrollViewNormalUseImages.viewTreeObserver.removeOnScrollChangedListener(listener)
        }
        normalUseScrollListener = null
        _binding = null
    }
}

