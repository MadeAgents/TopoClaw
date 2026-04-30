package com.cloudcontrol.demo

import android.content.Context
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * 统一管理 Markdown 渲染配置，避免各处初始化不一致。
 */
object MarkdownRenderer {
    fun createMarkwon(context: Context): Markwon {
        val density = context.resources.displayMetrics.density
        return Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    val blockMarginPx = (4f * density).toInt().coerceAtLeast(1)
                    // 统一 Markdown 分割线样式：黑色、1dp 细线
                    builder
                        // 对齐桌面端：弱化标题层级差异，避免移动端标题过大过粗的观感
                        .headingTextSizeMultipliers(floatArrayOf(1.12f, 1.05f, 1.0f, 0.98f, 0.95f, 0.92f))
                        // 对齐桌面端：压缩段落/列表/引用等块级间距
                        .blockMargin(blockMarginPx)
                        .blockQuoteWidth((1f * density).toInt().coerceAtLeast(1))
                        .blockQuoteColor(0x80333333.toInt())
                        .thematicBreakColor(0xFF000000.toInt())
                        .thematicBreakHeight((1f * density).toInt().coerceAtLeast(1))
                }
            })
            .build()
    }
}
