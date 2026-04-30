package com.cloudcontrol.demo

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 将 AccessibilityNodeInfo 树序列化为丰富的 XML 字符串
 * 用于轨迹采集时的页面结构记录，支持页面合并等后处理
 *
 * 包含属性：className, packageName, bounds, text, contentDescription, viewId,
 * clickable, scrollable, enabled, focusable, visibleToUser, hintText, inputType 等
 *
 * 支持"当前可见页"子树提取：当存在 ViewPager 等多页结构时，仅 dump 包含 focused 节点的
 * 那一页，避免混入其他预加载/缓存页面的元素。
 */
object AccessibilityXmlDumper {
    private const val TAG = "AccessibilityXmlDumper"
    
    /** 判定为"实质性"页面时的最小屏幕重叠比例（面积） */
    private const val MIN_OVERLAP_RATIO = 0.05f
    /** 若子树高度占比小于此阈值，则向上多走一层（避免只保留 header 而丢失主体内容） */
    private const val MIN_PAGE_HEIGHT_RATIO = 0.3f
    
    /**
     * 将根节点序列化为 XML 字符串。
     * 若存在 focused 节点且树中存在多页结构，则仅 dump 当前可见页的子树。
     *
     * @param root 根节点（来自 rootInActiveWindow），调用方负责回收
     * @param screenWidth 屏幕宽度（用于归一化等，可选）
     * @param screenHeight 屏幕高度（可选）
     * @return XML 字符串，失败返回 null
     */
    fun dump(root: AccessibilityNodeInfo?, screenWidth: Int = 0, screenHeight: Int = 0): String? {
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow 为 null")
            return null
        }
        return try {
            val effectiveRoot = findCurrentPageSubtreeRoot(root, screenWidth, screenHeight)
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            sb.append("<hierarchy")
            if (screenWidth > 0 && screenHeight > 0) {
                sb.append(" screenWidth=\"$screenWidth\" screenHeight=\"$screenHeight\"")
            }
            sb.append(">\n")
            dumpNode(sb, effectiveRoot, 0)
            sb.append("</hierarchy>")
            if (effectiveRoot != root) {
                effectiveRoot.recycle()
                Log.d(TAG, "已按当前可见页子树采集 XML")
            }
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "dump 失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 找到"当前可见页"子树的根节点。
     * 策略：若存在 focused 节点，则从 focused 向上遍历，找到首个「父节点有 2+ 个实质性兄弟」的
     * 祖先，该祖先即为当前页根（含 focused 的那一页）。若无 focused 或未找到分页结构，则返回 root。
     * 向上多走一层：若当前页根在屏幕内的可见高度 < 屏幕高度的 30%，则再往上一层，避免只保留
     * header 而丢失主体内容（如搜索栏+列表）。
     */
    private fun findCurrentPageSubtreeRoot(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo {
        if (screenWidth <= 0 || screenHeight <= 0) return root
        val focused = findFocusedNode(root) ?: return root
        var current: AccessibilityNodeInfo? = focused
        var pageRoot: AccessibilityNodeInfo? = null
        var recycledFocused = false
        try {
            while (current != null) {
                val parent = current.parent ?: break
                if (countSubstantialChildren(parent, screenWidth, screenHeight) >= 2) {
                    pageRoot = current
                    parent.recycle()
                    break
                }
                if (current == focused) recycledFocused = true
                current.recycle()
                current = parent
            }
        } finally {
            if (!recycledFocused && pageRoot == null) focused.recycle()
        }
        var result = pageRoot ?: root
        if (pageRoot != null) {
            val rect = Rect()
            pageRoot.getBoundsInScreen(rect)
            val visibleTop = rect.top.coerceIn(0, screenHeight)
            val visibleBottom = rect.bottom.coerceIn(0, screenHeight)
            val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
            if (visibleHeight < screenHeight * MIN_PAGE_HEIGHT_RATIO) {
                val parent = pageRoot.parent
                if (parent != null) {
                    pageRoot.recycle()
                    result = if (parent.parent != null) {
                        Log.d(TAG, "子树高度过小(${visibleHeight}px < ${(screenHeight * MIN_PAGE_HEIGHT_RATIO).toInt()}px)，向上多走一层")
                        parent
                    } else {
                        parent.recycle()
                        root
                    }
                }
            }
        }
        return result
    }
    
    private fun findFocusedNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedNode(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }
    
    /** 统计某节点中「与屏幕有实质性重叠」的子节点数量 */
    private fun countSubstantialChildren(
        node: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): Int {
        var count = 0
        val rect = Rect()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                child.getBoundsInScreen(rect)
                if (isSubstantialOverlap(rect, screenWidth, screenHeight)) count++
            } finally {
                child.recycle()
            }
        }
        return count
    }
    
    /** 判断 bounds 与屏幕的 overlapping 面积是否超过阈值 */
    private fun isSubstantialOverlap(rect: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        val left = rect.left.coerceIn(0, screenWidth)
        val right = rect.right.coerceIn(0, screenWidth)
        val top = rect.top.coerceIn(0, screenHeight)
        val bottom = rect.bottom.coerceIn(0, screenHeight)
        if (left >= right || top >= bottom) return false
        val area = (right - left) * (bottom - top)
        return area >= screenWidth * screenHeight * MIN_OVERLAP_RATIO
    }
    
    private fun dumpNode(sb: StringBuilder, node: AccessibilityNodeInfo, indent: Int) {
        val prefix = "  ".repeat(indent)
        
        val attrs = mutableListOf<String>()
        
        // 基础属性
        node.className?.toString()?.let { attrs.add("class=\"${escapeXml(it)}\"") }
        node.packageName?.toString()?.let { attrs.add("package=\"${escapeXml(it)}\"") }
        
        // bounds
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        attrs.add("bounds=\"[${rect.left},${rect.top},${rect.right},${rect.bottom}]\"")
        
        // 文本与描述
        node.text?.toString()?.let { attrs.add("text=\"${escapeXml(it)}\"") }
        node.contentDescription?.toString()?.let { attrs.add("content-desc=\"${escapeXml(it)}\"") }
        node.viewIdResourceName?.let { attrs.add("resource-id=\"${escapeXml(it)}\"") }
        
        //  hint（输入框）
        try {
            node.hintText?.toString()?.let { attrs.add("hint=\"${escapeXml(it)}\"") }
        } catch (_: Exception) { /* API 可能不可用 */ }
        
        // inputType（输入类型）
        try {
            val inputType = node.inputType
            if (inputType != 0) {
                attrs.add("input-type=\"$inputType\"")
            }
        } catch (_: Exception) { /* API 可能不可用 */ }
        
        // 布尔属性
        if (node.isClickable) attrs.add("clickable=\"true\"")
        if (node.isLongClickable) attrs.add("long-clickable=\"true\"")
        if (node.isScrollable) attrs.add("scrollable=\"true\"")
        if (node.isEnabled) attrs.add("enabled=\"true\"")
        if (node.isFocusable) attrs.add("focusable=\"true\"")
        if (node.isFocused) attrs.add("focused=\"true\"")
        if (node.isCheckable) attrs.add("checkable=\"true\"")
        if (node.isChecked) attrs.add("checked=\"true\"")
        if (node.isSelected) attrs.add("selected=\"true\"")
        if (node.isVisibleToUser) attrs.add("visible-to-user=\"true\"")
        
        val attrStr = attrs.joinToString(" ")
        sb.append(prefix)
        sb.append("<node $attrStr")
        
        val childCount = node.childCount
        if (childCount == 0) {
            sb.append(" />\n")
        } else {
            sb.append(">\n")
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    dumpNode(sb, child, indent + 1)
                } finally {
                    child.recycle()
                }
            }
            sb.append(prefix)
            sb.append("</node>\n")
        }
    }
    
    private fun escapeXml(s: String): String {
        return s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
