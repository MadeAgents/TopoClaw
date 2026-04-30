package com.cloudcontrol.demo

object MarkdownTableParser {
    sealed interface Block {
        data class Text(val markdown: String) : Block
        data class Table(
            val headers: List<String>,
            val rows: List<List<String>>,
            val rawMarkdown: String
        ) : Block
    }

    private val separatorRegex = Regex("^\\s*\\|?\\s*:?-{3,}:?(\\s*\\|\\s*:?-{3,}:?)+\\s*\\|?\\s*$")

    fun hasTable(markdown: String): Boolean {
        val lines = markdown.lines()
        for (i in 0 until lines.lastIndex) {
            if (isTableLikeRow(lines[i]) && separatorRegex.matches(lines[i + 1].trim())) {
                return true
            }
        }
        return false
    }

    fun splitBlocks(markdown: String): List<Block> {
        val lines = markdown.lines()
        val blocks = mutableListOf<Block>()
        val textBuffer = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (i + 1 < lines.size && isTableLikeRow(lines[i]) && separatorRegex.matches(lines[i + 1].trim())) {
                flushText(textBuffer, blocks)
                val tableLines = mutableListOf<String>()
                tableLines.add(lines[i])
                tableLines.add(lines[i + 1])
                i += 2
                while (i < lines.size && isTableLikeRow(lines[i])) {
                    tableLines.add(lines[i])
                    i += 1
                }
                parseTable(tableLines)?.let { blocks.add(it) }
            } else {
                textBuffer.add(lines[i])
                i += 1
            }
        }
        flushText(textBuffer, blocks)
        return blocks
    }

    fun toTsv(table: Block.Table): String {
        val lines = mutableListOf<String>()
        lines.add(table.headers.joinToString("\t"))
        lines.addAll(table.rows.map { row -> row.joinToString("\t") })
        return lines.joinToString("\n")
    }

    private fun flushText(textBuffer: MutableList<String>, blocks: MutableList<Block>) {
        if (textBuffer.isEmpty()) return
        val text = textBuffer.joinToString("\n").trim('\n')
        if (text.isNotBlank()) {
            blocks.add(Block.Text(text))
        }
        textBuffer.clear()
    }

    private fun parseTable(lines: List<String>): Block.Table? {
        if (lines.size < 2) return null
        val headers = splitCells(lines.first())
        if (headers.isEmpty()) return null
        val rows = lines.drop(2).map { splitCells(it) }.filter { it.isNotEmpty() }
        return Block.Table(
            headers = headers,
            rows = rows,
            rawMarkdown = lines.joinToString("\n")
        )
    }

    private fun splitCells(line: String): List<String> {
        val cleaned = line.trim().removePrefix("|").removeSuffix("|")
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split("|").map { it.trim() }
    }

    private fun isTableLikeRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.contains("|") && !trimmed.startsWith("```")
    }
}
