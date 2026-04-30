import java.io.File

/**
 * 处理 pinyin-data 和 Rime-ice 数据，生成 Kotlin 代码
 * 这是一个 Kotlin 脚本，可以直接运行
 */

fun main() {
    val projectRoot = File(".").absoluteFile.parentFile
    val externalDir = File(projectRoot, "external")
    
    // 处理 pinyin-data
    val pinyinDataFile = File(externalDir, "pinyin-data/kTGHZ2013.txt")
    if (pinyinDataFile.exists()) {
        processPinyinData(pinyinDataFile)
    } else {
        println("未找到 pinyin-data 文件: $pinyinDataFile")
    }
    
    // 处理 Rime-ice
    val rimeIceFile = File(externalDir, "rime-ice/cn_dicts/base.dict.yaml")
    if (rimeIceFile.exists()) {
        processRimeIce(rimeIceFile)
    } else {
        println("未找到 Rime-ice 文件: $rimeIceFile")
    }
}

fun processPinyinData(file: File) {
    println("处理 pinyin-data...")
    val pinyinMap = mutableMapOf<String, MutableList<String>>()
    
    file.useLines { lines ->
        lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            
            // 格式: U+4E2D: zhōng,zhòng  # 中
            val regex = Regex("""U\+([0-9A-F]+):\s+([^#]+)\s+#\s*(.+)""")
            val match = regex.find(line)
            if (match != null) {
                val pinyins = match.groupValues[2].trim()
                val char = match.groupValues[3].trim()
                
                pinyins.split(",").forEach { pinyin ->
                    val normalizedPinyin = pinyin.trim().lowercase()
                    if (normalizedPinyin.isNotEmpty() && char.isNotEmpty()) {
                        pinyinMap.getOrPut(normalizedPinyin) { mutableListOf() }.add(char)
                    }
                }
            }
        }
    }
    
    // 生成 Kotlin 代码
    val outputFile = File("apk2/scripts/generated_pinyin_map.kt")
    outputFile.writeText("// 自动生成 - 来自 pinyin-data\n")
    outputFile.appendText("private val pinyinMapFromData = mapOf(\n")
    
    val sortedPinyins = pinyinMap.keys.sorted()
    sortedPinyins.forEachIndexed { index, pinyin ->
        val chars = pinyinMap[pinyin]!!.distinct().take(10) // 最多10个
        val charsStr = chars.joinToString(", ") { "\"$it\"" }
        val comma = if (index < sortedPinyins.size - 1) "," else ""
        outputFile.appendText("    \"$pinyin\" to listOf($charsStr)$comma\n")
    }
    
    outputFile.appendText(")\n")
    println("生成完成: ${outputFile.absolutePath}, ${pinyinMap.size} 个拼音映射")
}

fun processRimeIce(file: File) {
    println("处理 Rime-ice...")
    val commonWordsMap = mutableMapOf<String, MutableList<Pair<String, Int>>>()
    var inData = false
    
    file.useLines { lines ->
        lines.forEach { line ->
            if (line == "...") {
                inData = true
                return@forEach
            }
            if (!inData || line.isBlank() || line.startsWith("#")) return@forEach
            
            // 格式: 词	拼音（空格分隔）	词频
            val parts = line.split("\t")
            if (parts.size >= 3) {
                val word = parts[0].trim()
                val pinyin = parts[1].trim().replace(" ", "").lowercase()
                val freq = parts[2].trim().toIntOrNull() ?: 0
                
                if (word.isNotEmpty() && pinyin.isNotEmpty() && freq > 50) { // 只取词频 > 50 的
                    commonWordsMap.getOrPut(pinyin) { mutableListOf() }.add(word to freq)
                }
            }
        }
    }
    
    // 按词频排序，每个拼音只保留前5个最常用的词
    val outputFile = File("apk2/scripts/generated_common_words.kt")
    outputFile.writeText("// 自动生成 - 来自 Rime-ice (词频 > 50)\n")
    outputFile.appendText("private val commonWordsMapFromRime = mapOf(\n")
    
    val sortedEntries = commonWordsMap.entries.sortedByDescending { 
        it.value.maxOfOrNull { it.second } ?: 0 
    }.take(3000) // 只取前3000个最常用的拼音
    
    sortedEntries.forEachIndexed { index, entry ->
        val pinyin = entry.key
        val words = entry.value.sortedByDescending { it.second }
            .take(5)
            .map { it.first }
            .distinct()
        
        val wordsStr = words.joinToString(", ") { "\"$it\"" }
        val comma = if (index < sortedEntries.size - 1) "," else ""
        outputFile.appendText("    \"$pinyin\" to listOf($wordsStr)$comma\n")
    }
    
    outputFile.appendText(")\n")
    println("生成完成: ${outputFile.absolutePath}, ${sortedEntries.size} 个常用词映射")
}

