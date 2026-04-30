package com.cloudcontrol.demo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 伴随模式教程悬浮窗管理器
 * 在用户首次开启伴随模式时显示使用指南动画
 */
object CompanionModeTutorialOverlayManager {
    private const val TAG = "CompanionTutorial"
    private const val PREF_NAME = "companion_tutorial_prefs"
    private const val PREF_KEY_SHOWN = "tutorial_shown"
    
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var isShowing = false
    private var context: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * 检查是否应该显示教程
     */
    fun shouldShowTutorial(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(PREF_KEY_SHOWN, false)
    }
    
    /**
     * 标记教程已显示
     */
    private fun markTutorialShown(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_SHOWN, true).apply()
    }
    
    /**
     * 显示教程动画
     * 每次开启伴随模式时都会显示教程
     */
    fun show(context: Context) {
        Log.d(TAG, "show: 开始显示教程，isShowing=$isShowing")
        
        if (isShowing) {
            Log.d(TAG, "教程已显示，跳过")
            return
        }
        
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(context)) {
                Log.w(TAG, "没有悬浮窗权限，无法显示教程")
                return
            }
        }
        
        this.context = context
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            Log.e(TAG, "无法获取WindowManager")
            return
        }
        windowManager = wm
        
        try {
            // 加载布局
            val inflater = LayoutInflater.from(context)
            val rootView = inflater.inflate(R.layout.overlay_companion_tutorial, null)
            
            // 获取视图元素
            val cardTutorial = rootView.findViewById<CardView>(R.id.cardTutorial)
            val layoutTopTip = rootView.findViewById<LinearLayout>(R.id.layoutTopTip)
            val layoutBottomTip = rootView.findViewById<LinearLayout>(R.id.layoutBottomTip)
            val layoutDoubleClickTip = rootView.findViewById<LinearLayout>(R.id.layoutDoubleClickTip)
            val layoutClickTip = rootView.findViewById<LinearLayout>(R.id.layoutClickTip)
            val layoutLongPressTip = rootView.findViewById<LinearLayout>(R.id.layoutLongPressTip)
            val btnGotIt = rootView.findViewById<Button>(R.id.btnGotIt)
            val ivTopArrow = rootView.findViewById<ImageView>(R.id.ivTopArrow)
            val ivBottomArrow = rootView.findViewById<ImageView>(R.id.ivBottomArrow)
            val ivFloatingBallIcon = rootView.findViewById<android.widget.ImageView>(R.id.ivFloatingBallIcon)
            
            // 加载应用图标并设置到悬浮球预览中
            try {
                val packageManager = context.packageManager
                val packageName = context.packageName
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val appIcon = packageManager.getApplicationIcon(appInfo)
                ivFloatingBallIcon?.setImageDrawable(appIcon)
            } catch (e: Exception) {
                Log.w(TAG, "加载应用图标失败: ${e.message}", e)
            }
            
            // 设置背景点击事件（点击外部关闭）
            rootView.setOnClickListener {
                hide()
            }
            
            // 阻止卡片点击事件冒泡
            cardTutorial?.setOnClickListener {
                // 阻止事件冒泡
            }
            
            // 我知道了按钮点击事件
            btnGotIt?.setOnClickListener {
                markTutorialShown(context)
                hide()
            }
            
            // 设置WindowManager参数
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                gravity = Gravity.START or Gravity.TOP
                x = 0
                y = 0
            }
            
            // 添加视图
            try {
                wm.addView(rootView, params)
                overlayView = rootView
                isShowing = true
                
                Log.d(TAG, "教程悬浮窗已显示，开始播放动画")
            } catch (e: Exception) {
                Log.e(TAG, "添加教程悬浮窗失败: ${e.message}", e)
                e.printStackTrace()
                isShowing = false
                overlayView = null
                windowManager = null
                return
            }
            
            // 播放动画
            playAnimationSequence(
                cardTutorial, 
                layoutTopTip, 
                layoutBottomTip, 
                layoutDoubleClickTip,
                layoutClickTip,
                layoutLongPressTip,
                btnGotIt, 
                ivTopArrow, 
                ivBottomArrow
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "显示教程失败: ${e.message}", e)
            isShowing = false
        }
    }
    
    /**
     * 播放动画序列
     */
    private fun playAnimationSequence(
        card: CardView?,
        topTip: LinearLayout?,
        bottomTip: LinearLayout?,
        doubleClickTip: LinearLayout?,
        clickTip: LinearLayout?,
        longPressTip: LinearLayout?,
        button: Button?,
        topArrow: ImageView?,
        bottomArrow: ImageView?
    ) {
        if (card == null) return
        
        // 1. 卡片入场动画（淡入 + 缩放 + 弹性）
        val cardScaleX = ObjectAnimator.ofFloat(card, "scaleX", 0.8f, 1.0f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.5f)
        }
        val cardScaleY = ObjectAnimator.ofFloat(card, "scaleY", 0.8f, 1.0f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.5f)
        }
        val cardAlpha = ObjectAnimator.ofFloat(card, "alpha", 0f, 1.0f).apply {
            duration = 300
        }
        
        // 2. 顶部提示项淡入动画（延迟500ms）
        val topTipAlpha = ObjectAnimator.ofFloat(topTip, "alpha", 0f, 1.0f).apply {
            duration = 400
            startDelay = 500
        }
        val topArrowAnimation = ObjectAnimator.ofFloat(topArrow, "translationY", -20f, 0f).apply {
            duration = 600
            startDelay = 500
            repeatCount = 2
            repeatMode = ValueAnimator.REVERSE
        }
        
        // 3. 底部提示项淡入动画（延迟900ms）
        val bottomTipAlpha = ObjectAnimator.ofFloat(bottomTip, "alpha", 0f, 1.0f).apply {
            duration = 400
            startDelay = 900
        }
        val bottomArrowAnimation = ObjectAnimator.ofFloat(bottomArrow, "translationY", 20f, 0f).apply {
            duration = 600
            startDelay = 900
            repeatCount = 2
            repeatMode = ValueAnimator.REVERSE
        }
        
        // 4. 双击提示项淡入动画（延迟1300ms）
        val doubleClickTipAlpha = ObjectAnimator.ofFloat(doubleClickTip, "alpha", 0f, 1.0f).apply {
            duration = 400
            startDelay = 1300
        }
        val doubleClickScale = ObjectAnimator.ofFloat(doubleClickTip, "scaleX", 0.95f, 1.0f).apply {
            duration = 400
            startDelay = 1300
            interpolator = OvershootInterpolator(1.2f)
        }
        val doubleClickScaleY = ObjectAnimator.ofFloat(doubleClickTip, "scaleY", 0.95f, 1.0f).apply {
            duration = 400
            startDelay = 1300
            interpolator = OvershootInterpolator(1.2f)
        }
        
        // 5. 点击提示项淡入动画（延迟1700ms）
        val clickTipAlpha = ObjectAnimator.ofFloat(clickTip, "alpha", 0f, 1.0f).apply {
            duration = 400
            startDelay = 1700
        }
        val clickScale = ObjectAnimator.ofFloat(clickTip, "scaleX", 0.95f, 1.0f).apply {
            duration = 400
            startDelay = 1700
            interpolator = OvershootInterpolator(1.2f)
        }
        val clickScaleY = ObjectAnimator.ofFloat(clickTip, "scaleY", 0.95f, 1.0f).apply {
            duration = 400
            startDelay = 1700
            interpolator = OvershootInterpolator(1.2f)
        }
        
        // 6. 长按提示项淡入动画（延迟2100ms）
        val longPressTipAlpha = ObjectAnimator.ofFloat(longPressTip, "alpha", 0f, 1.0f).apply {
            duration = 400
            startDelay = 2100
        }
        val longPressScale = ObjectAnimator.ofFloat(longPressTip, "scaleX", 0.95f, 1.0f).apply {
            duration = 400
            startDelay = 2100
            interpolator = OvershootInterpolator(1.2f)
        }
        val longPressScaleY = ObjectAnimator.ofFloat(longPressTip, "scaleY", 0.95f, 1.0f).apply {
            duration = 400
            startDelay = 2100
            interpolator = OvershootInterpolator(1.2f)
        }
        
        // 7. 按钮淡入动画（延迟2500ms）
        val buttonAlpha = ObjectAnimator.ofFloat(button, "alpha", 0f, 1.0f).apply {
            duration = 400
            startDelay = 2500
        }
        
        // 启动所有动画
        cardScaleX.start()
        cardScaleY.start()
        cardAlpha.start()
        topTipAlpha.start()
        topArrowAnimation.start()
        bottomTipAlpha.start()
        bottomArrowAnimation.start()
        doubleClickTipAlpha.start()
        doubleClickScale.start()
        doubleClickScaleY.start()
        clickTipAlpha.start()
        clickScale.start()
        clickScaleY.start()
        longPressTipAlpha.start()
        longPressScale.start()
        longPressScaleY.start()
        buttonAlpha.start()
        
        // 不再自动关闭，只有用户点击"我知道了"才会关闭
    }
    
    /**
     * 隐藏教程
     */
    fun hide() {
        if (!isShowing || overlayView == null) {
            return
        }
        
        val view = overlayView
        val wm = windowManager
        
        if (view != null && wm != null) {
            try {
                // 播放退场动画
                val fadeOut = ObjectAnimator.ofFloat(view, "alpha", 1.0f, 0f).apply {
                    duration = 300
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            try {
                                wm.removeView(view)
                                Log.d(TAG, "教程悬浮窗已移除")
                            } catch (e: Exception) {
                                Log.e(TAG, "移除教程悬浮窗失败: ${e.message}", e)
                            }
                        }
                    })
                }
                
                // 同时上移
                val translateY = ObjectAnimator.ofFloat(view, "translationY", 0f, -50f).apply {
                    duration = 300
                }
                
                fadeOut.start()
                translateY.start()
                
            } catch (e: Exception) {
                Log.e(TAG, "隐藏教程失败: ${e.message}", e)
                try {
                    wm.removeView(view)
                } catch (e2: Exception) {
                    Log.e(TAG, "强制移除教程悬浮窗失败: ${e2.message}", e2)
                }
            }
        }
        
        overlayView = null
        windowManager = null
        isShowing = false
        context = null
    }
    
    /**
     * 强制隐藏（不播放动画）
     */
    fun forceHide() {
        if (!isShowing || overlayView == null) {
            return
        }
        
        val view = overlayView
        val wm = windowManager
        
        if (view != null && wm != null) {
            try {
                wm.removeView(view)
                Log.d(TAG, "教程悬浮窗已强制移除")
            } catch (e: Exception) {
                Log.e(TAG, "强制移除教程悬浮窗失败: ${e.message}", e)
            }
        }
        
        overlayView = null
        windowManager = null
        isShowing = false
        context = null
    }
    
    /**
     * 重置教程状态（用于测试）
     */
    fun resetTutorialState(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_SHOWN, false).apply()
        Log.d(TAG, "教程状态已重置")
    }
}

