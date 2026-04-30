package com.cloudcontrol.demo

/**
 * 聊天文本 Markdown 预处理（保守模式）。
 *
 * 目标：
 * 1) 保留历史「标题/正文/图片描述」语义增强；
 * 2) 避免对已是 Markdown 的内容进行破坏性二次改写（尤其表格/列表/代码块）；
 * 3) 仅在普通文本场景补充硬换行，提升阅读体验。
 */
object MarkdownTextFormatter {
    private val codeFenceRegex = Regex("(?m)^\\s*```")
    private val headingRegex = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val unorderedListRegex = Regex("(?m)^\\s{0,3}[-*+]\\s+")
    private val orderedListRegex = Regex("(?m)^\\s{0,3}\\d+\\.\\s+")
    private val blockquoteRegex = Regex("(?m)^\\s{0,3}>\\s+")
    private val tableRowRegex = Regex("(?m)^\\s*\\|.+\\|\\s*$")
    private val tableSeparatorRegex = Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)+\\s*\\|?\\s*$")
    private val thematicBreakRegex = Regex("(?m)^\\s{0,3}(?:-{3,}|\\*{3,}|_{3,})\\s*$")

    fun toDisplayMarkdown(message: String): String {
        var markdown = normalizeNewlines(message)
        val hasSpecialFormat = markdown.contains("标题：") ||
            markdown.contains("正文：") ||
            markdown.contains("图片/视频描述：")

        if (hasSpecialFormat) {
            markdown = transformSpecialSections(markdown)
            markdown = compactHorizontalRules(markdown)
            return cleanupSpacing(markdown)
        }

        // 已是 Markdown 结构时保持原样，避免二次改写破坏排版。
        if (looksLikeMarkdown(markdown)) {
            return cleanupSpacing(compactHorizontalRules(markdown))
        }

        // 普通文本：保留历史行为，把单换行转为硬换行。
        markdown = markdown.replace(Regex("([^\\n])\\n([^\\n])"), "$1  \n$2")
        markdown = compactHorizontalRules(markdown)
        return cleanupSpacing(markdown)
    }

    private fun normalizeNewlines(input: String): String {
        return input
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\\n", "\n")
    }

    private fun looksLikeMarkdown(text: String): Boolean {
        val hasTable = tableRowRegex.containsMatchIn(text) && tableSeparatorRegex.containsMatchIn(text)
        return codeFenceRegex.containsMatchIn(text) ||
            headingRegex.containsMatchIn(text) ||
            unorderedListRegex.containsMatchIn(text) ||
            orderedListRegex.containsMatchIn(text) ||
            blockquoteRegex.containsMatchIn(text) ||
            hasTable
    }

    private fun transformSpecialSections(input: String): String {
        var markdown = input
        markdown = markdown.replace(Regex("标题：(.+?)(\\n|$)"), "## $1\n\n")
        markdown = markdown.replace(Regex("正文：\\s*\\n"), "")
        markdown = markdown.replace(Regex("图片/视频描述：(.+?)(\\n|$)"), "\n\n---\n\n*图片/视频描述：$1*")
        markdown = markdown.replace(Regex("(^|\\n)([❌‼️📍🚗🚄✈️])\\s*(.+?)(\\n|$)"), "$1- $3\n")
        markdown = markdown.replace(Regex("(Day \\d+：)"), "**$1**")
        return markdown
    }

    private fun cleanupSpacing(input: String): String {
        // 第一层：压缩段落空行，减少“空旷感”
        return input.replace(Regex("\\n[\\t \\u3000]*\\n+"), "\n")
    }

    private fun compactHorizontalRules(input: String): String {
        // 第三层：把 Markdown 的 thematic break 改为轻量分隔符，并压缩其上下留白。
        val replaced = thematicBreakRegex.replace(input, "────────")
        return replaced
            .replace(Regex("\\n{2,}────────\\n{2,}"), "\n────────\n")
            .replace(Regex("\\n{2,}────────"), "\n────────")
            .replace(Regex("────────\\n{2,}"), "────────\n")
    }
}
