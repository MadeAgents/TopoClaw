package com.cloudcontrol.demo

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentFloatingBallGuideBinding

/**
 * 悬浮球Fragment
 */
class FloatingBallGuideFragment : Fragment() {
    
    companion object {
        private const val TAG = "FloatingBallGuideFragment"
    }
    
    private var _binding: FragmentFloatingBallGuideBinding? = null
    private val binding get() = _binding!!
    
    // 悬浮球的图片资源
    private val floatingBallImages = listOf(
        R.drawable.tutorial_page4_1,
        R.drawable.tutorial_page4_2,
        R.drawable.tutorial_page4_3
    )
    
    // 图片滑动指示器
    private val floatingBallImageIndicators = mutableListOf<View>()
    private var floatingBallScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFloatingBallGuideBinding.inflate(inflater, container, false)
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
        
        // 初始化图片内容
        setupFloatingBallImages()
    }
    
    /**
     * 设置悬浮球的图片
     */
    private fun setupFloatingBallImages() {
        val layoutImages = binding.layoutFloatingBallImages
        val scrollViewImages = binding.scrollViewFloatingBallImages
        val layoutIndicators = binding.layoutFloatingBallImageIndicators
        
        layoutImages.removeAllViews()
        layoutIndicators.removeAllViews()
        floatingBallImageIndicators.clear()
        
        // 如果图片数量>2，显示图片滑动指示器
        if (floatingBallImages.size > 2) {
            layoutIndicators.visibility = View.VISIBLE
            setupImageIndicators(floatingBallImages.size, layoutIndicators, floatingBallImageIndicators)
        } else {
            layoutIndicators.visibility = View.GONE
        }
        
        // 添加图片
        val imageMargin = (12 * resources.displayMetrics.density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val padding = (24 * resources.displayMetrics.density).toInt() * 2 // 左右padding
        val imageWidth = ((screenWidth - padding) * 0.425).toInt() // 图片宽度为可用宽度的42.5%（参照小助手介绍）
        
        floatingBallImages.forEachIndexed { index, imageResId ->
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
        if (floatingBallImages.size > 2) {
            floatingBallScrollListener?.let { listener ->
                scrollViewImages.viewTreeObserver.removeOnScrollChangedListener(listener)
            }
            floatingBallScrollListener = ViewTreeObserver.OnScrollChangedListener {
                updateImageIndicators(scrollViewImages, floatingBallImageIndicators, imageWidth, imageMargin)
            }
            scrollViewImages.viewTreeObserver.addOnScrollChangedListener(floatingBallScrollListener!!)
            scrollViewImages.post {
                updateImageIndicators(scrollViewImages, floatingBallImageIndicators, imageWidth, imageMargin)
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
        scrollView: android.widget.HorizontalScrollView?,
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
    
    /**
     * 显示全屏图片
     */
    private fun showFullScreenImage(imageResId: Int) {
        try {
            val fullScreenDialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_image_fullscreen, null)
            fullScreenDialog.setContentView(dialogView)
            
            val ivFullScreen = dialogView.findViewById<ImageView>(R.id.ivFullScreenImage)
            
            ivFullScreen.setImageResource(imageResId)
            ivFullScreen.scaleType = ImageView.ScaleType.FIT_CENTER
            
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
    
    override fun onDestroyView() {
        // 移除滚动监听器
        floatingBallScrollListener?.let { listener ->
            binding.scrollViewFloatingBallImages.viewTreeObserver.removeOnScrollChangedListener(listener)
        }
        
        super.onDestroyView()
        _binding = null
    }
}

