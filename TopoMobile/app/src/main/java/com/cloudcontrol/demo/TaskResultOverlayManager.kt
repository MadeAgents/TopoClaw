package com.cloudcontrol.demo

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageButton
import io.noties.markwon.Markwon

/**
 * 任务结果弹窗管理器
 * 用于在外部应用上显示任务完成后的助手回复
 */
object TaskResultOverlayManager {
    private const val TAG = "TaskResultOverlay"
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    
    /**
     * 显示任务结果弹窗
     * @param context 上下文
     * @param resultText 助手回复内容
     * @param onDismiss 弹窗关闭后的回调（可选）
     */
    fun show(
        context: Context,
        resultText: String,
        onDismiss: (() -> Unit)? = null
    ) {
        if (isShowing) {
            Log.d(TAG, "结果弹窗已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_task_result, null
            )
            
            // 设置标题
            val titleTextView = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
            titleTextView?.text = "任务完成"
            
            // 设置结果内容（支持滚动和Markdown链接）
            val resultTextView = dialogView.findViewById<TextView>(R.id.tvResultText)
            if (resultTextView != null) {
                // 使用Markwon渲染Markdown并处理app://链接
                val markwon = MarkdownRenderer.createMarkwon(context)
                setupMarkwonWithAppLinksForDialog(resultTextView, resultText, markwon, context)
            }
            
            // 获取按钮
            val btnBackToApp = dialogView.findViewById<android.widget.Button>(R.id.btnBackToApp)
            val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btnContinue)
            val btnOk = dialogView.findViewById<android.widget.Button>(R.id.btnOk)
            
            // 设置返回应用按钮的图标（应用图标，更大尺寸，只显示图标不显示文字）
            try {
                val packageManager = context.packageManager
                val appIcon: Drawable? = try {
                    packageManager.getApplicationIcon(context.packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "获取应用图标失败: ${e.message}")
                    null
                }
                
                // 设置图标大小（更大）并居中显示
                if (appIcon != null && btnBackToApp != null) {
                    val iconSize = (48 * context.resources.displayMetrics.density).toInt() // 增大到48dp
                    appIcon.setBounds(0, 0, iconSize, iconSize)
                    // 只显示图标，不显示文字（文字已在布局中设为空）
                    btnBackToApp.setCompoundDrawables(appIcon, null, null, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "设置应用图标失败: ${e.message}", e)
            }
            
            // 创建对话框
            dialog = android.app.AlertDialog.Builder(context)
                .setView(dialogView)
                .create()
            
            // 设置对话框窗口类型为悬浮窗
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog?.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog?.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            // 美化对话框窗口样式
            dialog?.window?.let { window ->
                // 设置背景透明，让圆角背景显示出来
                window.setBackgroundDrawableResource(android.R.color.transparent)
                // 移除默认的窗口装饰
                window.decorView.setBackgroundResource(android.R.color.transparent)
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 返回应用按钮点击事件
            btnBackToApp?.setOnClickListener {
                Log.d(TAG, "用户点击返回应用按钮")
                val mainActivity = context as? MainActivity
                if (mainActivity != null) {
                    // 关闭弹窗
                    dialog?.dismiss()
                    // 切回TopoClaw应用
                    val success = mainActivity.bringAppToForeground()
                    if (success) {
                        Log.d(TAG, "已切回TopoClaw应用")
                    } else {
                        Log.w(TAG, "切回TopoClaw应用失败")
                    }
                }
                onDismiss?.invoke()
            }
            
            // 继续追问按钮点击事件
            btnContinue?.setOnClickListener {
                Log.d(TAG, "用户点击继续追问按钮")
                // 关闭结果弹窗
                dialog?.dismiss()
                // 显示输入框弹窗
                val mainActivity = context as? MainActivity
                if (mainActivity != null) {
                    // 延迟一下确保结果弹窗已关闭
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        TaskIndicatorOverlayManager.showInputDialogForNotification(mainActivity)
                    }, 200)
                }
                onDismiss?.invoke()
            }
            
            // 确定按钮点击事件
            btnOk?.setOnClickListener {
                Log.d(TAG, "用户点击确定，关闭结果弹窗")
                dialog?.dismiss()
                onDismiss?.invoke()
            }
            
            // 设置对话框关闭监听
            dialog?.setOnDismissListener {
                Log.d(TAG, "结果弹窗已关闭")
                isShowing = false
                dialog = null
                onDismiss?.invoke()
            }
            
            // 显示对话框后，确保 ScrollView 高度被限制，按钮始终可见
            dialog?.setOnShowListener {
                val scrollView = dialogView.findViewById<android.widget.ScrollView>(R.id.scrollView)
                val titleView = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
                val buttonContainer = dialogView.findViewById<android.view.View>(R.id.buttonContainer)
                
                // 等待布局完成后再设置
                scrollView?.post {
                    val displayMetrics = context.resources.displayMetrics
                    val density = displayMetrics.density
                    
                    // 对话框最大高度（600dp）
                    val dialogMaxHeight = (600 * density).toInt()
                    
                    // 计算已占用的高度
                    val padding = (28 * density * 2).toInt() // 上下 padding
                    val titleHeight = titleView?.height ?: 0
                    val titleMargin = (20 * density).toInt() // title 的 marginBottom
                    val scrollMargin = (24 * density).toInt() // scrollView 的 marginBottom
                    
                    // 测量按钮容器高度
                    buttonContainer?.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            (displayMetrics.widthPixels * 0.9).toInt(),
                            android.view.View.MeasureSpec.AT_MOST
                        ),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                    )
                    val buttonHeight = buttonContainer?.measuredHeight ?: (56 * density).toInt()
                    
                    // 计算 ScrollView 的最大高度
                    val usedHeight = padding + titleHeight + titleMargin + scrollMargin + buttonHeight
                    val maxScrollHeight = (dialogMaxHeight - usedHeight).coerceAtMost((400 * density).toInt())
                    
                    // 设置 ScrollView 的最大高度
                    val layoutParams = scrollView?.layoutParams
                    if (layoutParams != null) {
                        // 先测量当前内容高度
                        scrollView.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(scrollView.width, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                        )
                        val contentHeight = scrollView.measuredHeight
                        
                        // 如果内容高度超过最大高度，则限制为最大高度
                        if (contentHeight > maxScrollHeight) {
                            layoutParams.height = maxScrollHeight
                            scrollView.layoutParams = layoutParams
                            Log.d(TAG, "限制 ScrollView 高度: ${maxScrollHeight}px (内容高度: ${contentHeight}px)")
                        } else {
                            // 内容高度小于最大高度，保持 wrap_content
                            Log.d(TAG, "ScrollView 内容高度正常: ${contentHeight}px (最大: ${maxScrollHeight}px)")
                        }
                    }
                }
            }
            
            // 显示对话框
            dialog?.show()
            
            isShowing = true
            Log.d(TAG, "任务结果弹窗已显示，内容长度: ${resultText.length}")
        } catch (e: Exception) {
            Log.e(TAG, "显示任务结果弹窗失败: ${e.message}", e)
            dialog = null
        }
    }
    
    /**
     * 隐藏任务结果弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            dialog?.dismiss()
            dialog = null
            isShowing = false
            Log.d(TAG, "任务结果弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏任务结果弹窗失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
        context = null
    }
    
    /**
     * 为对话框TextView设置Markwon并处理app://链接
     * 这是从ChatFragment.setupMarkwonWithAppLinks复制并修改的版本，用于对话框
     */
    private fun setupMarkwonWithAppLinksForDialog(
        textView: TextView,
        markdownText: String,
        markwon: Markwon,
        context: Context
    ) {
        Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 开始处理，markdownText包含app://: ${markdownText.contains("app://")}")
        
        markwon.setMarkdown(textView, markdownText)
        
        // 获取渲染后的文本
        val text = textView.text
        Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 文本类型: ${text.javaClass.simpleName}, 长度: ${text.length}")
        
        if (text is android.text.Spannable) {
            var replacedCount = 0
            
            // 查找所有LinkSpan并替换app://链接（使用反射，如果失败则跳过）
            try {
                val linkSpanClass = Class.forName("io.noties.markwon.LinkSpan")
                val linkSpans = text.getSpans(0, text.length, linkSpanClass)
                Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 找到 ${linkSpans.size} 个LinkSpan")
                
                for (linkSpan in linkSpans) {
                    val getLinkMethod = linkSpanClass.getMethod("getLink")
                    val url = getLinkMethod.invoke(linkSpan) as? String
                    Log.d(TAG, "setupMarkwonWithAppLinksForDialog: LinkSpan URL: $url")
                    
                    if (url != null && url.startsWith("app://")) {
                        val appName = url.removePrefix("app://")
                        val spanStart = text.getSpanStart(linkSpan)
                        val spanEnd = text.getSpanEnd(linkSpan)
                        val spanFlags = text.getSpanFlags(linkSpan)
                        
                        Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 找到app://链接: $appName (位置: $spanStart-$spanEnd)")
                        
                        // 移除原来的LinkSpan
                        text.removeSpan(linkSpan)
                        
                        // 创建自定义的ClickableSpan来处理app://链接
                        val appLinkSpan = object : android.text.style.ClickableSpan() {
                            override fun onClick(widget: android.view.View) {
                                Log.d(TAG, "【对话框点击事件】ClickableSpan.onClick被调用: $appName")
                                handleAppLinkForDialog(appName, context)
                            }
                            
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                super.updateDrawState(ds)
                                // 保持链接样式（蓝色、下划线）
                                ds.color = 0xFF2196F3.toInt()
                                ds.isUnderlineText = true
                            }
                        }
                        
                        // 添加自定义的ClickableSpan
                        text.setSpan(appLinkSpan, spanStart, spanEnd, spanFlags)
                        replacedCount++
                        Log.d(TAG, "setupMarkwonWithAppLinksForDialog: ✓ 已替换app://链接: $appName (位置: $spanStart-$spanEnd)")
                    }
                }
            } catch (e: ClassNotFoundException) {
                // LinkSpan类不存在，这是正常的，Markwon可能使用URLSpan
                Log.d(TAG, "setupMarkwonWithAppLinksForDialog: LinkSpan类不存在，跳过（Markwon可能使用URLSpan）")
            } catch (e: Exception) {
                Log.e(TAG, "setupMarkwonWithAppLinksForDialog: 处理Markwon LinkSpan失败: ${e.message}", e)
            }
            
            // 检查URLSpan（Markwon通常使用URLSpan）
            val urlSpans = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
            Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 找到 ${urlSpans.size} 个URLSpan")
            for (urlSpan in urlSpans) {
                val url = urlSpan.url
                Log.d(TAG, "setupMarkwonWithAppLinksForDialog: URLSpan URL: $url")
                if (url.startsWith("app://")) {
                    val appName = url.removePrefix("app://")
                    val spanStart = text.getSpanStart(urlSpan)
                    val spanEnd = text.getSpanEnd(urlSpan)
                    val spanFlags = text.getSpanFlags(urlSpan)
                    
                    Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 找到app://链接(URLSpan): $appName (位置: $spanStart-$spanEnd)")
                    
                    // 移除原来的URLSpan
                    text.removeSpan(urlSpan)
                    
                    // 创建自定义的ClickableSpan
                    val appLinkSpan = object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: android.view.View) {
                            Log.d(TAG, "【对话框点击事件】ClickableSpan.onClick被调用(URLSpan): $appName")
                            handleAppLinkForDialog(appName, context)
                        }
                        
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.color = 0xFF2196F3.toInt()
                            ds.isUnderlineText = true
                        }
                    }
                    
                    text.setSpan(appLinkSpan, spanStart, spanEnd, spanFlags)
                    replacedCount++
                    Log.d(TAG, "setupMarkwonWithAppLinksForDialog: ✓ 已替换app://链接(URLSpan): $appName")
                }
            }
            
            // 最后检查所有ClickableSpan
            val allClickableSpans = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
            Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 最终ClickableSpan数量: ${allClickableSpans.size}, 已替换app://链接数量: $replacedCount")
        } else {
            Log.w(TAG, "setupMarkwonWithAppLinksForDialog: 文本不是Spannable类型，无法处理链接")
        }
        
        // 使用OnTouchListener直接处理链接点击
        var touchedSpan: android.text.style.ClickableSpan? = null
        
        textView.setOnTouchListener { view, event ->
            Log.d(TAG, "【对话框触摸事件】TextView.setOnTouchListener: action=${event.action}, x=${event.x}, y=${event.y}")
            
            val textView = view as? TextView ?: return@setOnTouchListener false
            val text = textView.text
            if (text !is android.text.Spannable) {
                Log.d(TAG, "【对话框触摸事件】文本不是Spannable类型")
                return@setOnTouchListener false
            }
            
            val layout = textView.layout
            if (layout == null) {
                Log.d(TAG, "【对话框触摸事件】layout为null")
                return@setOnTouchListener false
            }
            
            // 计算点击位置在文本中的偏移量
            val x = event.x.toInt()
            val y = event.y.toInt()
            val line = layout.getLineForVertical(y - textView.totalPaddingTop + textView.scrollY)
            val offset = layout.getOffsetForHorizontal(line, (x - textView.totalPaddingLeft + textView.scrollX).toFloat())
            
            Log.d(TAG, "【对话框触摸事件】点击位置: line=$line, offset=$offset, text.length=${text.length}, action=${event.action}")
            
            // 查找包含该位置的ClickableSpan
            val clickableSpans = text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java)
            Log.d(TAG, "【对话框触摸事件】找到 ${clickableSpans.size} 个ClickableSpan")
            
            val matchingSpan = clickableSpans.find { span ->
                val spanStart = text.getSpanStart(span)
                val spanEnd = text.getSpanEnd(span)
                val contains = spanStart <= offset && offset < spanEnd
                if (contains) {
                    Log.d(TAG, "【对话框触摸事件】匹配span: start=$spanStart, end=$spanEnd, offset=$offset")
                }
                contains
            }
            
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchedSpan = matchingSpan
                    if (matchingSpan != null) {
                        Log.d(TAG, "【对话框触摸事件】ACTION_DOWN: 点击到链接，消费事件")
                        return@setOnTouchListener true // 消费事件
                    } else {
                        Log.d(TAG, "【对话框触摸事件】ACTION_DOWN: 未点击到链接")
                        return@setOnTouchListener false
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (touchedSpan != null && touchedSpan == matchingSpan) {
                        Log.d(TAG, "【对话框触摸事件】ACTION_UP: 触发链接点击")
                        matchingSpan?.onClick(textView)
                        touchedSpan = null
                        return@setOnTouchListener true // 消费事件
                    } else {
                        Log.d(TAG, "【对话框触摸事件】ACTION_UP: 未点击到链接或span已改变")
                        touchedSpan = null
                        return@setOnTouchListener false
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    touchedSpan = null
                    return@setOnTouchListener false
                }
                else -> {
                    // 其他事件（如ACTION_MOVE）
                    if (touchedSpan != null) {
                        // 如果之前点击到了链接，继续消费事件
                        return@setOnTouchListener true
                    }
                    return@setOnTouchListener false
                }
            }
        }
        
        // 设置LinkMovementMethod以支持链接样式（下划线、颜色等）
        textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        
        Log.d(TAG, "setupMarkwonWithAppLinksForDialog: 完成处理，已设置OnTouchListener和LinkMovementMethod")
    }
    
    /**
     * 处理对话框中的应用链接点击
     */
    private fun handleAppLinkForDialog(appName: String, context: Context) {
        Log.d(TAG, "【handleAppLinkForDialog】开始处理应用链接: $appName")
        val packageName = AppMappingManager.getPackageName(appName)
        Log.d(TAG, "【handleAppLinkForDialog】应用名: $appName, 包名: $packageName")
        
        if (packageName != null) {
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                Log.d(TAG, "【handleAppLinkForDialog】调用launchAppFromLink: $packageName")
                val success = mainActivity.launchAppFromLink(packageName)
                Log.d(TAG, "【handleAppLinkForDialog】launchAppFromLink返回: $success")
                if (!success) {
                    Log.w(TAG, "【handleAppLinkForDialog】✗ 无法打开应用: $appName")
                    android.widget.Toast.makeText(context, "无法打开应用: $appName", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    Log.d(TAG, "【handleAppLinkForDialog】✓ 成功打开应用: $appName")
                }
            } else {
                Log.e(TAG, "【handleAppLinkForDialog】✗ Context不是MainActivity")
            }
        } else {
            Log.w(TAG, "【handleAppLinkForDialog】✗ 未找到应用: $appName")
            android.widget.Toast.makeText(context, "未找到应用: $appName", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

