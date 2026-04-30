package com.cloudcontrol.demo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 应用内消息通知管理器
 * 类似微信的应用内弹窗提示，当收到消息但不在对应聊天页面时显示
 */
object InAppNotificationManager {
    private const val TAG = "InAppNotificationManager"
    private const val DISPLAY_DURATION = 3000L // 显示3秒
    private const val ANIMATION_DURATION = 300L // 动画时长
    
    private var currentNotificationView: View? = null
    private var activityRef: WeakReference<Activity>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        hideCurrentNotification(animated = true)
    }
    
    // 点击回调
    private var onNotificationClickCallback: (() -> Unit)? = null
    
    /** 取消属性动画并从父布局移除，避免连续弹窗时退场动画未结束导致视图残留 */
    private fun cancelAnimationsAndDetachFromParent(view: View) {
        try {
            view.animate().cancel()
            val parent = view.parent as? ViewGroup ?: return
            if (parent.indexOfChild(view) >= 0) {
                parent.removeView(view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelAnimationsAndDetachFromParent: ${e.message}")
        }
    }
    
    /**
     * 显示应用内消息通知弹窗
     * @param activity 当前Activity
     * @param title 通知标题（如"人工客服"）
     * @param content 消息内容
     * @param avatarResId 头像资源ID（可选）
     * @param avatarBitmap 头像Bitmap（可选，优先级高于avatarResId）
     * @param onClick 点击通知的回调（可选）
     * @param duration 显示时长（毫秒），默认3秒
     */
    fun showNotification(
        activity: Activity,
        title: String,
        content: String,
        avatarResId: Int = R.drawable.ic_system_avatar,
        avatarBitmap: Bitmap? = null,
        onClick: (() -> Unit)? = null,
        duration: Long = DISPLAY_DURATION
    ) {
        // 确保在主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post {
                showNotification(activity, title, content, avatarResId, avatarBitmap, onClick, duration)
            }
            return
        }
        
        try {
            // 如果Activity已销毁，直接返回
            if (activity.isFinishing || activity.isDestroyed) {
                Log.w(TAG, "Activity已销毁，无法显示通知")
                return
            }
            
            // 隐藏之前的通知（如果有）；连续弹新条时必须取消动画并移除视图，否则会卡住或叠两层
            hideCurrentNotification(animated = false)
            
            activityRef = WeakReference(activity)
            onNotificationClickCallback = onClick
            
            // 创建通知视图
            val inflater = LayoutInflater.from(activity)
            val notificationView = inflater.inflate(R.layout.view_toast_notification, null)
            
            // 设置内容
            val tvTitle = notificationView.findViewById<TextView>(R.id.tvNotificationTitle)
            val tvContent = notificationView.findViewById<TextView>(R.id.tvNotificationContent)
            val ivAvatar = notificationView.findViewById<ImageView>(R.id.ivNotificationAvatar)
            
            tvTitle.text = title
            tvContent.text = content
            // 优先使用Bitmap头像，如果没有则使用资源ID
            if (avatarBitmap != null && !avatarBitmap.isRecycled) {
                ivAvatar.setImageBitmap(avatarBitmap)
            } else {
                ivAvatar.setImageResource(avatarResId)
            }
            
            // 设置点击事件
            notificationView.setOnClickListener {
                hideCurrentNotification(animated = true)
                onNotificationClickCallback?.invoke()
            }
            
            // 获取DecorView（最顶层视图，可以显示在ActionBar之上）
            val decorView = activity.window.decorView as ViewGroup
            
            // 计算状态栏高度，让弹窗紧贴状态栏下方
            val statusBarHeight = getStatusBarHeight(activity)
            
            // 创建LayoutParams
            val layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = statusBarHeight  // 紧贴状态栏下方
            }
            
            // 直接添加到DecorView，这样弹窗会显示在ActionBar之上
            decorView.addView(notificationView, layoutParams)
            currentNotificationView = notificationView
            
            // 初始状态：从上方滑入
            notificationView.translationY = -200f
            notificationView.alpha = 0f
            
            // 入场动画
            notificationView.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ANIMATION_DURATION)
                .start()
            
            // 自动隐藏：固定 Runnable，连续弹窗时 removeCallbacks 能稳定取消上一次定时
            handler.removeCallbacks(autoHideRunnable)
            handler.postDelayed(autoHideRunnable, duration)
            
            Log.d(TAG, "显示应用内通知: $title - $content")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示应用内通知失败: ${e.message}", e)
        }
    }
    
    /**
     * 隐藏当前通知
     */
    fun hideCurrentNotification(animated: Boolean = true) {
        // 取消自动隐藏任务（含连续消息时上一次 postDelayed）
        handler.removeCallbacks(autoHideRunnable)
        
        val view = currentNotificationView ?: return
        val activity = activityRef?.get()
        // Activity 引用丢失或已销毁时仍必须拆掉 View，否则弹窗会一直留在 DecorView 上
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            cancelAnimationsAndDetachFromParent(view)
            currentNotificationView = null
            onNotificationClickCallback = null
            return
        }
        
        try {
            // 先取消入场/退场中动画，避免与下一次 show 叠层或 onAnimationEnd 重复 remove
            view.animate().cancel()
            if (animated) {
                // 退场动画：向上滑出
                view.animate()
                    .translationY(-200f)
                    .alpha(0f)
                    .setDuration(ANIMATION_DURATION)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            view.animate().setListener(null)
                            removeNotificationView(activity, view)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            view.animate().setListener(null)
                        }
                    })
                    .start()
            } else {
                removeNotificationView(activity, view)
            }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏通知失败: ${e.message}", e)
            cancelAnimationsAndDetachFromParent(view)
            currentNotificationView = null
        }
    }
    
    private fun removeNotificationView(activity: Activity, view: View) {
        try {
            // 从DecorView移除视图
            val decorView = activity.window.decorView as ViewGroup
            decorView.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "移除通知视图失败: ${e.message}", e)
        }
        // 仅当移除的仍是当前条时清空，避免旧条退场回调晚于新条 show 时把新条引用清掉导致无法自动消失
        if (currentNotificationView === view) {
            currentNotificationView = null
        }
    }
    
    private fun getStatusBarHeight(context: Context): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return result
    }
    
    /**
     * 检查是否有通知正在显示
     */
    fun isShowingNotification(): Boolean {
        return currentNotificationView != null
    }
}

