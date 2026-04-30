import java.io.File

/**
 * 处理 pinyin-data 和 rime-ice 数据，生成 Kotlin 代码
 * 在 Kotlin 环境中运行此脚本
 */

fun main() {
    val projectRoot = File(".").absolutePath.replace("\\apk2\\scripts", "")
    val externalDir = File(projectRoot, "external")
    
    // 处理 pinyin-data
    val pinyinDataFile = File(externalDir, "pinyin-data/kTGHZ2013.txt")
    if (pinyinDataFile.exists()) {
        processPinyinData(pinyinDataFile)
    }
    
    // 处理 rime-ice
    val rimeIceDir = File(externalDir, "rime-ice/cn_dicts")
    if (rimeIceDir.exists()) {
        processRimeIce(rimeIceDir)
    }
}

fun processPinyinData(file: File) {
    println("处理 pinyin-data...")
    val pinyinMap = mutableMapOf<String, MutableList<String>>()
    
    file.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        
        // 格式: U+4E2D: zhōng,zhòng  # 中
        val regex = Regex("U\\+([0-9A-F]+):\\s+([^#]+)\\s+#\\s*(.+)")
        val match = regex.find(trimmed)
        if (match != null) {
            val pinyins = match.groupValues[2].trim()
            val char = match.groupValues[3].trim()
            
            pinyins.split(",").forEach { pinyin ->
                val normalized = pinyin.trim().lowercase()
                if (normalized.isNotEmpty() && char.isNotEmpty()) {
                    pinyinMap.getOrPut(normalized) { mutableListOf() }.add(char)
                }
            }
        }
    }
    
    // 生成 Kotlin 代码
    val outputFile = File("apk2/scripts/generated_pinyin_map.kt")
    outputFile.writeText("// 自动生成 - 来自 pinyin-data\n")
    outputFile.appendText("private val pinyinMapFromData = mapOf(\n")
    
    pinyinMap.toSortedMap().forEachIndexed { index, (pinyin, chars) ->
        val uniqueChars = chars.distinct().take(10)
        val charsStr = uniqueChars.joinToString(", ") { "\"$it\"" }
        val comma = if (index < pinyinMap.size - 1) "," else ""
        outputFile.appendText("    \"$pinyin\" to listOf($charsStr)$comma\n")
    }
    
    outputFile.appendText(")\n")
    println("生成完成: ${outputFile.absolutePath}")
    println("共 ${pinyinMap.size} 个拼音映射")
}

fun processRimeIce(dir: File) {
    println("处理 rime-ice...")
    val commonWordsMap = mutableMapOf<String, MutableList<Pair<String, Int>>>()
    
    dir.listFiles { _, name -> name.endsWith(".dict.yaml") }?.forEach { file ->
        println("  处理: ${file.name}")
        var inData = false
        
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            
            if (trimmed == "...") {
                inData = true
                return@forEach
            }
            
            if (!inData || trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            
            // 格式: 词	拼音（空格分隔）	词频
            val parts = trimmed.split("\t")
            if (parts.size >= 3) {
                val word = parts[0].trim()
                val pinyin = parts[1].trim().replace(" ", "").lowercase()
                val freq = parts[2].trim().toIntOrNull() ?: 0
                
                if (word.isNotEmpty() && pinyin.isNotEmpty()) {
                    commonWordsMap.getOrPut(pinyin) { mutableListOf() }.add(word to freq)
                }
            }
        }
    }
    
    // 按词频排序，每个拼音取前5个
    val sortedMap = commonWordsMap.mapValues { (_, words) ->
        words.sortedByDescending { it.second }.take(5).map { it.first }
    }.toSortedMap()
    
    // 生成 Kotlin 代码（只取前5000个最常用的）
    val top5000 = sortedMap.toList()
        .sortedByDescending { it.second.size }
        .take(5000)
        .toMap()
    
    val outputFile = File("apk2/scripts/generated_common_words.kt")
    outputFile.writeText("// 自动生成 - 来自 rime-ice\n")
    outputFile.appendText("private val commonWordsMapFromRime = mapOf(\n")
    
    top5000.forEachIndexed { index, (pinyin, words) ->
        val wordsStr = words.joinToString(", ") { "\"$it\"" }
        val comma = if (index < top5000.size - 1) "," else ""
        outputFile.appendText("    \"$pinyin\" to listOf($wordsStr)$comma\n")
    }
    
    outputFile.appendText(")\n")
    println("生成完成: ${outputFile.absolutePath}")
    println("共 ${top5000.size} 个常用词映射")
}

