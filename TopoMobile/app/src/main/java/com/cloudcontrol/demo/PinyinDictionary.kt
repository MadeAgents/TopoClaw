package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.InputStream

/**
 * 拼音词典
 * 提供拼音到汉字的转换功能
 * 所有拼音映射数据都从 pinyin_mapping.json 文件加载
 */
class PinyinDictionary(private val context: Context? = null) {
    companion object {
        private const val TAG = "PinyinDictionary"
        private const val PREFS_NAME = "pinyin_user_input_history"
        private const val KEY_HISTORY = "user_input_history"  // 格式: "pinyin:word:count|pinyin:word:count|..."
        private const val KEY_WORD_PAIRS = "word_pairs_history"  // 格式: "前词:后词:count|前词:后词:count|..."
        private const val PINYIN_MAPPING_FILE = "pinyin_mapping.json"
    }
    
    // SharedPreferences用于存储用户输入历史
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // 用户输入历史：Map<拼音, Map<词, 使用次数>>
    private val userInputHistory: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
    
    // 词对关系：Map<前一个词, Map<后一个词, 使用次数>>
    // 用于跨词预测，例如："你在" -> "干什么" (次数: 5)
    private val wordPairsHistory: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
    
    init {
        // 从SharedPreferences加载用户输入历史
        loadUserInputHistory()
        // 加载词对关系历史
        loadWordPairsHistory()
    }
    
    /**
     * 从SharedPreferences加载用户输入历史
     */
    private fun loadUserInputHistory() {
        if (prefs == null) return
        
        try {
            val historyString = prefs.getString(KEY_HISTORY, "") ?: ""
            if (historyString.isEmpty()) return
            
            val entries = historyString.split("|")
            for (entry in entries) {
                val parts = entry.split(":")
                if (parts.size == 3) {
                    val pinyin = parts[0]
                    val word = parts[1]
                    val count = parts[2].toIntOrNull() ?: 0
                    
                    if (pinyin.isNotEmpty() && word.isNotEmpty() && count > 0) {
                        if (!userInputHistory.containsKey(pinyin)) {
                            userInputHistory[pinyin] = mutableMapOf()
                        }
                        userInputHistory[pinyin]!![word] = count
                    }
                }
            }
            Log.d(TAG, "加载用户输入历史: ${userInputHistory.size} 个拼音记录")
        } catch (e: Exception) {
            Log.e(TAG, "加载用户输入历史失败: ${e.message}", e)
        }
    }
    
    /**
     * 保存用户输入历史到SharedPreferences
     */
    private fun saveUserInputHistory() {
        if (prefs == null) return
        
        try {
            val historyList = mutableListOf<String>()
            for ((pinyin, words) in userInputHistory) {
                for ((word, count) in words) {
                    historyList.add("$pinyin:$word:$count")
                }
            }
            val historyString = historyList.joinToString("|")
            prefs.edit().putString(KEY_HISTORY, historyString).apply()
            Log.d(TAG, "保存用户输入历史: ${historyList.size} 条记录")
        } catch (e: Exception) {
            Log.e(TAG, "保存用户输入历史失败: ${e.message}", e)
        }
    }
    
    /**
     * 记录用户输入的词
     * @param pinyin 拼音
     * @param word 用户选择的词
     * @param previousWord 前一个词（用于跨词预测），可选
     */
    fun recordUserInput(pinyin: String, word: String, previousWord: String? = null) {
        if (prefs == null) return
        
        val normalizedPinyin = pinyin.lowercase().trim()
        if (normalizedPinyin.isEmpty() || word.isEmpty()) return
        
        try {
            // 1. 记录拼音到词的映射（用于同拼音下的词频排序）
            if (!userInputHistory.containsKey(normalizedPinyin)) {
                userInputHistory[normalizedPinyin] = mutableMapOf()
            }
            
            val currentCount = userInputHistory[normalizedPinyin]!!.getOrDefault(word, 0)
            userInputHistory[normalizedPinyin]!![word] = currentCount + 1
            
            // 2. 记录词对关系（用于跨词预测）
            if (previousWord != null && previousWord.isNotEmpty()) {
                if (!wordPairsHistory.containsKey(previousWord)) {
                    wordPairsHistory[previousWord] = mutableMapOf()
                }
                
                val pairCount = wordPairsHistory[previousWord]!!.getOrDefault(word, 0)
                wordPairsHistory[previousWord]!![word] = pairCount + 1
                
                // 保存词对关系
                saveWordPairsHistory()
                
                Log.d(TAG, "记录词对关系: $previousWord -> $word (次数: ${pairCount + 1})")
            }
            
            // 保存拼音到词的映射
            saveUserInputHistory()
            
            Log.d(TAG, "记录用户输入: $normalizedPinyin -> $word (次数: ${currentCount + 1})")
        } catch (e: Exception) {
            Log.e(TAG, "记录用户输入失败: ${e.message}", e)
        }
    }
    
    /**
     * 从SharedPreferences加载词对关系历史
     */
    private fun loadWordPairsHistory() {
        if (prefs == null) return
        
        try {
            val pairsString = prefs.getString(KEY_WORD_PAIRS, "") ?: ""
            if (pairsString.isEmpty()) return
            
            val entries = pairsString.split("|")
            for (entry in entries) {
                val parts = entry.split(":")
                if (parts.size == 3) {
                    val previousWord = parts[0]
                    val nextWord = parts[1]
                    val count = parts[2].toIntOrNull() ?: 0
                    
                    if (previousWord.isNotEmpty() && nextWord.isNotEmpty() && count > 0) {
                        if (!wordPairsHistory.containsKey(previousWord)) {
                            wordPairsHistory[previousWord] = mutableMapOf()
                        }
                        wordPairsHistory[previousWord]!![nextWord] = count
                    }
                }
            }
            Log.d(TAG, "加载词对关系历史: ${wordPairsHistory.size} 个前词记录")
        } catch (e: Exception) {
            Log.e(TAG, "加载词对关系历史失败: ${e.message}", e)
        }
    }
    
    /**
     * 保存词对关系历史到SharedPreferences
     */
    private fun saveWordPairsHistory() {
        if (prefs == null) return
        
        try {
            val pairsList = mutableListOf<String>()
            for ((previousWord, nextWords) in wordPairsHistory) {
                for ((nextWord, count) in nextWords) {
                    pairsList.add("$previousWord:$nextWord:$count")
                }
            }
            val pairsString = pairsList.joinToString("|")
            prefs.edit().putString(KEY_WORD_PAIRS, pairsString).apply()
            Log.d(TAG, "保存词对关系历史: ${pairsList.size} 条记录")
        } catch (e: Exception) {
            Log.e(TAG, "保存词对关系历史失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取用户输入过的词的次数（热度）
     * @param pinyin 拼音
     * @param word 词
     * @return 使用次数，如果未使用过返回0
     */
    private fun getUserInputCount(pinyin: String, word: String): Int {
        val normalizedPinyin = pinyin.lowercase().trim()
        return userInputHistory[normalizedPinyin]?.getOrDefault(word, 0) ?: 0
    }
    
    /**
     * 获取词对关系的使用次数（用于跨词预测）
     * @param previousWord 前一个词
     * @param nextWord 后一个词
     * @return 使用次数，如果未使用过返回0
     */
    private fun getWordPairCount(previousWord: String, nextWord: String): Int {
        return wordPairsHistory[previousWord]?.getOrDefault(nextWord, 0) ?: 0
    }
    
    /**
     * 根据前一个词获取可能的后续词（跨词预测）
     * 支持语义相关词匹配，例如："北京旅游" -> "攻略"，"北京旅行"也能预测"攻略"
     * @param previousWord 前一个词
     * @return 可能的后续词列表，按使用次数排序
     */
    private fun getNextWordCandidates(previousWord: String): List<String> {
        if (previousWord.isEmpty()) return emptyList()
        
        val allNextWords = mutableMapOf<String, Int>()
        
        // 1. 完全匹配：直接查找前一个词的后续词（最高优先级）
        val exactMatch = wordPairsHistory[previousWord]
        exactMatch?.forEach { (nextWord, count) ->
            // 完全匹配的权重更高（乘以2）
            allNextWords[nextWord] = (allNextWords[nextWord] ?: 0) + count * 2
        }
        
        // 2. 语义相关匹配：查找相似词的后续词
        // 例如："北京旅游" 和 "北京旅行" 相似，可以共享后续词
        if (previousWord.length >= 2) {
            for ((key, nextWords) in wordPairsHistory) {
                if (key == previousWord) continue // 跳过完全匹配（已在步骤1处理）
                
                // 计算词的相似度
                val similarity = calculateWordSimilarity(previousWord, key)
                
                // 如果相似度 >= 0.6，认为相关，合并后续词（按相似度加权）
                if (similarity >= 0.6) {
                    nextWords.forEach { (nextWord, count) ->
                        // 相似词的权重 = 原始次数 * 相似度
                        val weightedCount = (count * similarity).toInt()
                        allNextWords[nextWord] = (allNextWords[nextWord] ?: 0) + weightedCount
                    }
                    Log.d(TAG, "语义相关词匹配: '$previousWord' <-> '$key' (相似度: ${String.format("%.2f", similarity)})")
                }
            }
        }
        
        // 按加权使用次数降序排序，返回前15个
        return allNextWords.entries
            .sortedByDescending { it.value }
            .take(15)
            .map { it.key }
    }
    
    /**
     * 计算两个词的相似度（基于字符重叠和子串匹配）
     * 例如："北京旅游" 和 "北京旅行" 相似度较高
     * @param word1 词1
     * @param word2 词2
     * @return 相似度（0.0-1.0）
     */
    private fun calculateWordSimilarity(word1: String, word2: String): Double {
        if (word1 == word2) return 1.0
        if (word1.isEmpty() || word2.isEmpty()) return 0.0
        
        // 1. 如果一个是另一个的子串，相似度较高
        if (word1.contains(word2) || word2.contains(word1)) {
            val shorter = minOf(word1.length, word2.length)
            val longer = maxOf(word1.length, word2.length)
            return shorter.toDouble() / longer.toDouble() * 0.8
        }
        
        // 2. 计算字符集合的Jaccard相似度
        val chars1 = word1.toSet()
        val chars2 = word2.toSet()
        val intersection = chars1.intersect(chars2).size
        val union = chars1.union(chars2).size
        
        if (union == 0) return 0.0
        val jaccard = intersection.toDouble() / union.toDouble()
        
        // 3. 计算最长公共子串的相似度
        val lcsLength = longestCommonSubstring(word1, word2)
        val lcsSimilarity = lcsLength.toDouble() / maxOf(word1.length, word2.length)
        
        // 4. 计算2-gram（2字子串）重叠度（对中文更有效）
        val ngrams1 = getNgrams(word1, 2)
        val ngrams2 = getNgrams(word2, 2)
        val ngramIntersection = ngrams1.intersect(ngrams2).size
        val ngramUnion = ngrams1.union(ngrams2).size
        val ngramSimilarity = if (ngramUnion > 0) ngramIntersection.toDouble() / ngramUnion.toDouble() else 0.0
        
        // 综合相似度：Jaccard(0.3) + LCS(0.4) + N-gram(0.3)
        val combinedSimilarity = jaccard * 0.3 + lcsSimilarity * 0.4 + ngramSimilarity * 0.3
        
        return minOf(1.0, combinedSimilarity)
    }
    
    /**
     * 获取词的N-gram（N字子串）
     * @param word 词
     * @param n N值（通常为2）
     * @return N-gram集合
     */
    private fun getNgrams(word: String, n: Int): Set<String> {
        val ngrams = mutableSetOf<String>()
        for (i in 0..word.length - n) {
            ngrams.add(word.substring(i, i + n))
        }
        return ngrams
    }
    
    /**
     * 计算两个字符串的最长公共子串长度
     * @param s1 字符串1
     * @param s2 字符串2
     * @return 最长公共子串长度
     */
    private fun longestCommonSubstring(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        var maxLength = 0
        
        for (i in 1..m) {
            for (j in 1..n) {
                if (s1[i - 1] == s2[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                    maxLength = maxOf(maxLength, dp[i][j])
                } else {
                    dp[i][j] = 0
                }
            }
        }
        
        return maxLength
    }
    
    /**
     * 从JSON文件统一加载所有拼音映射（包括单字和多字词）
     * 这是唯一的数据源，所有拼音映射都从 pinyin_mapping.json 文件加载
     */
    private val allPinyinMapping: Map<String, List<String>> by lazy {
        val result = mutableMapOf<String, List<String>>()
        
        if (context == null) {
            Log.w(TAG, "Context为null，无法加载JSON映射文件")
            return@lazy result
        }
        
        try {
            val inputStream: InputStream = context.assets.open(PINYIN_MAPPING_FILE)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val pinyin = keys.next()
                val jsonArray = jsonObject.getJSONArray(pinyin)
                if (jsonArray.length() > 0) {
                    val words = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        words.add(jsonArray.getString(i))
                    }
                    result[pinyin] = words
                }
            }
            
            Log.d(TAG, "成功从JSON文件加载 ${result.size} 个拼音映射（包括单字和多字词）")
            // 调试：检查 "ai" 是否加载成功
            if (result.containsKey("ai")) {
                Log.d(TAG, "JSON中 'ai' 的候选字数量: ${result["ai"]?.size ?: 0}, 内容: ${result["ai"]?.take(10)?.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载拼音映射JSON文件失败: ${e.message}", e)
        }
        
        result
    }
    
    /**
     * 单字映射（从 allPinyinMapping 中筛选出单字）
     */
    private val singleCharMapping: Map<String, List<String>> by lazy {
        allPinyinMapping.filter { (_, words) ->
            words.isNotEmpty() && words[0].length == 1
        }
    }
    
    /**
     * 多字词映射（从 allPinyinMapping 中筛选出多字词）
     */
    private val multiCharMapping: Map<String, List<String>> by lazy {
        allPinyinMapping.filter { (_, words) ->
            words.isNotEmpty() && words[0].length >= 2
        }
    }
    
    /**
     * 根据拼音获取候选汉字列表（支持跨词预测）
     * @param pinyin 拼音（不区分大小写）
     * @param previousWord 前一个词（用于跨词预测），可选
     * @return 候选汉字列表，如果找不到返回空列表
     */
    fun getCandidates(pinyin: String, previousWord: String? = null): List<String> {
        val normalizedPinyin = pinyin.lowercase().trim()
        Log.d(TAG, "========== getCandidates 开始: '$normalizedPinyin' ==========")
        
        // 0. 检查常用词映射（完全匹配）- 从JSON加载的多字词映射
        val commonWords = multiCharMapping[normalizedPinyin]
        if (commonWords != null && commonWords.isNotEmpty()) {
            Log.d(TAG, "常用词完全匹配: $normalizedPinyin -> $commonWords")
        } else {
            Log.d(TAG, "常用词完全匹配: $normalizedPinyin -> 无匹配")
        }
        
        // 0.5. 检查常用词映射（前缀匹配，如 "dak" -> "dakai" -> "打开"）- 从JSON加载的多字词映射
        val prefixMatchedCommonWords = mutableListOf<String>()
        for ((key, words) in multiCharMapping) {
            if (key.startsWith(normalizedPinyin) && key.length > normalizedPinyin.length) {
                prefixMatchedCommonWords.addAll(words)
            }
        }
        if (prefixMatchedCommonWords.isNotEmpty()) {
            Log.d(TAG, "常用词前缀匹配: $normalizedPinyin -> $prefixMatchedCommonWords")
            // 注意：这里不直接返回，而是添加到候选词列表中，让后续的排序逻辑处理
        }
        
        // 1. 先尝试完全匹配（单字）
        // 调试：检查 singleCharMapping 是否已初始化
        var exactMatch: List<String>? = null
        try {
            Log.d(TAG, "检查单字映射 '$normalizedPinyin', singleCharMapping大小: ${singleCharMapping.size}")
            exactMatch = singleCharMapping[normalizedPinyin]
            Log.d(TAG, "单字映射 '$normalizedPinyin' 结果: ${if (exactMatch == null) "null" else if (exactMatch.isEmpty()) "空列表" else "${exactMatch.size}个候选字"}")
        } catch (e: Exception) {
            Log.e(TAG, "访问 singleCharMapping 时出错: ${e.message}", e)
            exactMatch = null
        }
        
        if (exactMatch != null && exactMatch.isNotEmpty()) {
            Log.d(TAG, "完全匹配 '$normalizedPinyin': 找到 ${exactMatch.size} 个候选字: ${exactMatch.take(10).joinToString(", ")}")
            
            // 如果有常用词匹配，合并常用词和单字映射，常用词优先
            if (commonWords != null && commonWords.isNotEmpty()) {
                // 合并常用词和单字，去重，常用词在前
                val merged = (commonWords + exactMatch).distinct()
                Log.d(TAG, "合并常用词和单字映射: $normalizedPinyin -> ${merged.size} 个候选字: ${merged.take(10).joinToString(", ")}")
                return merged
            }
            
            // 如果没有常用词匹配，直接返回单字映射
            return exactMatch
        }
        
        // 如果单字映射为空，但有常用词匹配，返回常用词
        if (commonWords != null && commonWords.isNotEmpty()) {
            Log.d(TAG, "单字映射为空，返回常用词: $normalizedPinyin -> $commonWords")
            Log.d(TAG, "========== getCandidates 结束（返回常用词）: '$normalizedPinyin' ==========")
            return commonWords
        }
        
        Log.d(TAG, "单字映射和常用词都为空，继续其他匹配逻辑")
        
        // 1.5. 跨词预测：如果提供了前一个词，尝试预测后续词
        val crossWordCandidates = mutableListOf<String>()
        if (previousWord != null && previousWord.isNotEmpty()) {
            val predictedWords = getNextWordCandidates(previousWord)
            // 检查预测的词是否匹配当前拼音
            for (predictedWord in predictedWords) {
                // 如果当前拼音为空，直接添加预测词（用户刚输入完前一个词，开始输入新词）
                if (normalizedPinyin.isEmpty()) {
                    crossWordCandidates.add(predictedWord)
                } else {
                    // 检查预测的词是否匹配当前拼音
                    // 方法1：检查预测词的拼音是否以当前拼音开头
                    val predictedPinyin = getPinyinForWord(predictedWord)
                    if (predictedPinyin != null && predictedPinyin.startsWith(normalizedPinyin)) {
                        crossWordCandidates.add(predictedWord)
                    } else {
                        // 方法2：如果预测词是多字词，检查第一个字的拼音是否匹配
                        if (predictedWord.length >= 2) {
                            val firstChar = predictedWord.substring(0, 1)
                            val firstCharPinyin = getPinyinForWord(firstChar)
                            if (firstCharPinyin != null && firstCharPinyin.startsWith(normalizedPinyin)) {
                                crossWordCandidates.add(predictedWord)
                            }
                        }
                    }
                }
            }
            if (crossWordCandidates.isNotEmpty()) {
                Log.d(TAG, "跨词预测: '$previousWord' -> '$normalizedPinyin' 找到 ${crossWordCandidates.size} 个候选词: ${crossWordCandidates.take(5).joinToString(", ")}")
            }
        }
        
        // 2. 尝试简拼匹配（首字母匹配，如 "srf" -> "输入法"）
        val abbreviationCandidates = getAbbreviationCandidates(normalizedPinyin)
        if (abbreviationCandidates.isNotEmpty()) {
            Log.d(TAG, "简拼匹配: $normalizedPinyin -> ${abbreviationCandidates.size}个候选词")
        }
        
        // 3. 尝试多字词匹配（完整拼音连打，如 "shurufa" -> "输入法"）
        val multiWordCandidates = getMultiWordCandidates(normalizedPinyin)
        if (multiWordCandidates.isNotEmpty()) {
            Log.d(TAG, "多字词匹配: $normalizedPinyin -> ${multiWordCandidates.size}个候选词")
        }
        
        // 4. 尝试不完整拼音匹配（如 "shuruf" -> "输入法"）
        val incompleteCandidates = getIncompletePinyinCandidates(normalizedPinyin)
        if (incompleteCandidates.isNotEmpty()) {
            Log.d(TAG, "不完整拼音匹配: $normalizedPinyin -> ${incompleteCandidates.size}个候选词")
        }
        
        // 合并所有候选词，使用综合评分系统进行智能排序
        val allCandidates = mutableListOf<String>()
        
        // 0. 跨词预测候选词（优先级最高）
        allCandidates.addAll(crossWordCandidates)
        
        // 0.5. 常用词前缀匹配（如 "dak" -> "打开"）
        allCandidates.addAll(prefixMatchedCommonWords)
        
        // 1. 简拼候选词
        allCandidates.addAll(abbreviationCandidates)
        
        // 2. 多字词候选词
        allCandidates.addAll(multiWordCandidates)
        
        // 3. 不完整拼音候选词
        allCandidates.addAll(incompleteCandidates)
        
        // 4. 单字候选词（前缀匹配）
        val singleWordCandidates = getSingleWordCandidates(normalizedPinyin)
        
        // 去重
        val uniqueCandidates = (allCandidates + singleWordCandidates).distinct()
        
        // 使用综合评分系统对候选词进行智能排序
        val scoredCandidates = uniqueCandidates.map { candidate ->
            val score = calculateCandidateScore(
                pinyin = normalizedPinyin,
                candidate = candidate,
                previousWord = previousWord,
                isCrossWordPrediction = crossWordCandidates.contains(candidate),
                isPrefixMatchedCommonWord = prefixMatchedCommonWords.contains(candidate),
                isAbbreviation = abbreviationCandidates.contains(candidate),
                isMultiWord = multiWordCandidates.contains(candidate),
                isIncomplete = incompleteCandidates.contains(candidate),
                isSingleWord = singleWordCandidates.contains(candidate)
            )
            Pair(candidate, score)
        }
        
        // 按综合分数降序排序，取前30个
        val finalCandidates = scoredCandidates
            .sortedByDescending { it.second }
            .take(30)
            .map { it.first }
        
        if (finalCandidates.isNotEmpty()) {
            return finalCandidates
        }
        
        // 如果没有多字词匹配，尝试查找以该拼音开头的所有拼音（单字前缀匹配）
        val prefixMatches = getSingleWordCandidates(normalizedPinyin)
        
        // 如果找到前缀匹配，返回前20个（避免太多）
        return if (prefixMatches.isNotEmpty()) {
            prefixMatches.take(20)
        } else {
            emptyList()
        }
    }
    
    /**
     * 获取单字候选词（前缀匹配）
     */
    private fun getSingleWordCandidates(pinyin: String): List<String> {
        val prefixMatches = mutableListOf<String>()
        for ((key, value) in singleCharMapping) {
            if (key.startsWith(pinyin)) {
                prefixMatches.addAll(value)
            }
        }
        return prefixMatches
    }
    
    /**
     * 获取多字词候选词（连打）
     * 例如：输入"hunan" -> 返回["湖南", "胡南", "湖男", ...]
     * 支持混合输入，如"zhej" -> "这就"（"zhe" + "jiu"的简拼）
     */
    private fun getMultiWordCandidates(pinyin: String): List<String> {
        if (pinyin.length < 2) {
            return emptyList()
        }
        
        // 使用动态规划分割拼音
        val segments = segmentPinyin(pinyin)
        if (segments.isEmpty()) {
            // 如果完全分割失败，尝试允许最后一个拼音不完整
            return getMultiWordCandidatesWithIncompleteLast(pinyin)
        }
        
        // 为每个拼音段获取候选字
        val segmentCandidates = segments.map { segment ->
            val candidates = singleCharMapping[segment]
            if (candidates != null && candidates.isNotEmpty()) {
                candidates.take(5) // 每个拼音段最多取5个候选字，避免组合爆炸
            } else {
                emptyList()
            }
        }
        
        // 如果任何一个段没有候选字，返回空
        if (segmentCandidates.any { it.isEmpty() }) {
            return emptyList()
        }
        
        // 组合所有段的候选字，生成多字词
        val multiWordCandidates = mutableListOf<String>()
        combineCandidates(segmentCandidates, 0, "", multiWordCandidates, 20)
        
        return multiWordCandidates
    }
    
    /**
     * 获取多字词候选词（允许最后一个拼音不完整）
     * 例如："zhej" -> "这就"（"zhe" + "jiu"的简拼）
     */
    private fun getMultiWordCandidatesWithIncompleteLast(pinyin: String): List<String> {
        if (pinyin.length < 3) {
            return emptyList()
        }
        
        val candidates = mutableListOf<String>()
        
        // 尝试从2个字符到pinyin.length-2个字符作为最后一个拼音的前缀
        for (lastPrefixLen in 2 until pinyin.length) {
            val prefix = pinyin.substring(0, pinyin.length - lastPrefixLen)
            val lastPrefix = pinyin.substring(pinyin.length - lastPrefixLen)
            
            // 分割前缀部分
            val prefixSegments = segmentPinyin(prefix)
            if (prefixSegments.isEmpty()) {
                continue
            }
            
            // 查找以lastPrefix开头的拼音（支持简拼匹配）
            val matchingPinyins = singleCharMapping.keys.filter { it.startsWith(lastPrefix) }
            if (matchingPinyins.isEmpty()) {
                continue
            }
            
            // 为每个匹配的拼音生成候选词
            for (matchingPinyin in matchingPinyins.take(3)) {
                val lastChars = singleCharMapping[matchingPinyin] ?: continue
                if (lastChars.isEmpty()) continue
                
                // 为前缀部分获取候选字
                val prefixCandidates = prefixSegments.map { seg ->
                    singleCharMapping[seg]?.firstOrNull() ?: ""
                }
                
                if (prefixCandidates.any { it.isEmpty() }) {
                    continue
                }
                
                // 组合生成多字词
                val word = prefixCandidates.joinToString("") + lastChars[0]
                candidates.add(word)
                
                if (candidates.size >= 10) {
                    return candidates
                }
            }
        }
        
        return candidates
    }
    
    /**
     * 使用动态规划分割拼音字符串
     * 例如："hunan" -> [["hu", "nan"], ["hun", "an"], ...]
     * 返回所有可能的分割方案（只返回第一个有效方案，避免组合爆炸）
     */
    private fun segmentPinyin(pinyin: String): List<String> {
        val n = pinyin.length
        if (n == 0) return emptyList()
        
        // 使用动态规划，dp[i] 表示 pinyin[0..i-1] 是否可以分割成有效拼音
        val dp = BooleanArray(n + 1)
        val path = Array(n + 1) { mutableListOf<String>() }
        
        dp[0] = true // 空字符串可以分割
        
        for (i in 1..n) {
            for (j in 0 until i) {
                val segment = pinyin.substring(j, i)
                if (dp[j] && singleCharMapping.containsKey(segment)) {
                    dp[i] = true
                    // 记录分割路径
                    if (path[i].isEmpty()) {
                        path[i].addAll(path[j])
                        path[i].add(segment)
                    }
                    break // 找到第一个有效分割就停止，避免重复
                }
            }
        }
        
        // 如果能够完全分割，返回分割结果
        return if (dp[n]) {
            path[n]
        } else {
            emptyList()
        }
    }
    
    /**
     * 获取简拼候选词（首字母匹配）
     * 例如："srf" -> ["输入法", "输入发", ...]
     */
    private fun getAbbreviationCandidates(abbreviation: String): List<String> {
        if (abbreviation.length < 2 || abbreviation.length > 6) {
            return emptyList()
        }
        
        // 首先检查 allPinyinMapping 中是否有完全匹配的简拼（从JSON文件加载）
        val exactMatch = allPinyinMapping[abbreviation]
        if (exactMatch != null && exactMatch.isNotEmpty()) {
            return exactMatch
        }
        
        // 如果不是完全匹配，尝试动态匹配
        // 将简拼转换为可能的拼音组合
        // 注意：简拼匹配只使用单字拼音，避免匹配到过长的多字词拼音键（如"ikeayjjj"）
        val possiblePinyinSegments = abbreviation.map { char ->
            // 每个字符可能是某个拼音的首字母
            // 例如 's' 可能是 "shu", "shi", "shou", "shuo" 等的首字母
            // 只匹配单字拼音，且长度限制在1-6个字符，避免匹配到过长的键
            val allMatches = singleCharMapping.keys.filter { 
                it.startsWith(char.toString(), ignoreCase = true) && it.length <= 6
            }
            // 按长度排序，优先选择短且常用的拼音
            allMatches.sortedBy { it.length }.take(5) // 每个位置最多尝试5个拼音
        }
        
        // 如果任何一个字符找不到对应的拼音，返回空
        if (possiblePinyinSegments.any { it.isEmpty() }) {
            return emptyList()
        }
        
        // 组合所有可能的拼音，生成候选词
        val candidates = mutableListOf<String>()
        combineAbbreviationCandidates(possiblePinyinSegments, 0, "", candidates, 20)
        
        return candidates
    }
    
    /**
     * 递归组合简拼候选词
     */
    private fun combineAbbreviationCandidates(
        segments: List<List<String>>,
        index: Int,
        current: String,
        result: MutableList<String>,
        maxResults: Int
    ) {
        if (result.size >= maxResults) {
            return
        }
        
        if (index >= segments.size) {
            if (current.isNotEmpty()) {
                result.add(current)
            }
            return
        }
        
        // 对于每个可能的拼音，取第一个候选字
        val possiblePinyins = segments[index]
        for (pinyin in possiblePinyins.take(3)) { // 每个位置最多尝试3个拼音，避免组合爆炸
            val chars = allPinyinMapping[pinyin] ?: continue
            if (chars.isNotEmpty()) {
                // 取第一个候选字（最常用的）
                combineAbbreviationCandidates(segments, index + 1, current + chars[0], result, maxResults)
                if (result.size >= maxResults) {
                    return
                }
            }
        }
    }
    
    /**
     * 获取不完整拼音候选词
     * 例如："shuruf" -> "输入法"（"shu"+"ru"+"fa"，最后一个"fa"不完整）
     */
    private fun getIncompletePinyinCandidates(pinyin: String): List<String> {
        if (pinyin.length < 3) {
            return emptyList()
        }
        
        // 尝试多种分割方式，允许最后一个拼音不完整
        val candidates = mutableListOf<String>()
        
        // 尝试从2个字符到pinyin.length-1个字符作为最后一个拼音的前缀
        for (lastPrefixLen in 2 until pinyin.length) {
            val prefix = pinyin.substring(0, pinyin.length - lastPrefixLen)
            val lastPrefix = pinyin.substring(pinyin.length - lastPrefixLen)
            
            // 分割前缀部分
            val prefixSegments = segmentPinyin(prefix)
            if (prefixSegments.isEmpty()) {
                continue
            }
            
            // 查找以lastPrefix开头的拼音
            // 限制匹配单字拼音，且长度不超过6个字符，避免匹配到过长的多字词拼音键
            val matchingPinyins = singleCharMapping.keys.filter { 
                it.startsWith(lastPrefix) && it.length <= 6
            }
            if (matchingPinyins.isEmpty()) {
                continue
            }
            
            // 为每个匹配的拼音生成候选词
            for (matchingPinyin in matchingPinyins.take(3)) {
                val lastChars = singleCharMapping[matchingPinyin] ?: continue
                if (lastChars.isEmpty()) continue
                
                // 为前缀部分获取候选字
                val prefixCandidates = prefixSegments.map { seg ->
                    singleCharMapping[seg]?.firstOrNull() ?: ""
                }
                
                if (prefixCandidates.any { it.isEmpty() }) {
                    continue
                }
                
                // 组合生成多字词
                val word = prefixCandidates.joinToString("") + lastChars[0]
                candidates.add(word)
                
                if (candidates.size >= 10) {
                    return candidates
                }
            }
        }
        
        return candidates
    }
    
    /**
     * 递归组合候选字，生成多字词
     */
    private fun combineCandidates(
        segmentCandidates: List<List<String>>,
        index: Int,
        current: String,
        result: MutableList<String>,
        maxResults: Int
    ) {
        if (result.size >= maxResults) {
            return
        }
        
        if (index >= segmentCandidates.size) {
            if (current.isNotEmpty()) {
                result.add(current)
            }
            return
        }
        
        val candidates = segmentCandidates[index]
        for (candidate in candidates) {
            combineCandidates(segmentCandidates, index + 1, current + candidate, result, maxResults)
            if (result.size >= maxResults) {
                return
            }
        }
    }
    
    /**
     * 检查拼音是否有效
     */
    fun isValidPinyin(pinyin: String): Boolean {
        val normalizedPinyin = pinyin.lowercase().trim()
        return allPinyinMapping.containsKey(normalizedPinyin)
    }
    
    /**
     * 获取词的拼音（用于跨词预测时检查拼音匹配）
     * @param word 词
     * @return 对应的拼音，如果找不到返回null
     */
    private fun getPinyinForWord(word: String): String? {
        // 遍历所有映射，查找包含该词的拼音
        for ((pinyin, words) in allPinyinMapping) {
            if (words.contains(word)) {
                return pinyin
            }

        }
        return null
    }
    
    /**
     * 计算候选词的综合评分
     * 使用多维度评分系统，综合考虑匹配度、用户习惯、词频、跨词预测等因素
     * @param pinyin 输入的拼音
     * @param candidate 候选词
     * @param previousWord 前一个词（用于跨词预测）
     * @param isCrossWordPrediction 是否是跨词预测
     * @param isPrefixMatchedCommonWord 是否是常用词前缀匹配
     * @param isAbbreviation 是否是简拼匹配
     * @param isMultiWord 是否是多字词匹配
     * @param isIncomplete 是否是不完整拼音匹配
     * @param isSingleWord 是否是单字匹配
     * @return 综合评分（分数越高，优先级越高）
     */
    private fun calculateCandidateScore(
        pinyin: String,
        candidate: String,
        previousWord: String? = null,
        isCrossWordPrediction: Boolean = false,
        isPrefixMatchedCommonWord: Boolean,
        isAbbreviation: Boolean,
        isMultiWord: Boolean,
        isIncomplete: Boolean,
        isSingleWord: Boolean
    ): Double {
        var score = 0.0
        
        // 0. 跨词预测分数（0-100分）- 最高优先级
        if (isCrossWordPrediction && previousWord != null) {
            val pairCount = getWordPairCount(previousWord, candidate)
            // 跨词预测分数：基础分60 + 使用次数*8，最多100分
            score += 60.0 + minOf(pairCount * 8.0, 40.0)
            Log.d(TAG, "跨词预测评分: '$previousWord' -> '$candidate' (次数: $pairCount, 分数: ${60.0 + minOf(pairCount * 8.0, 40.0)})")
        }
        
        // 1. 拼音匹配度分数（0-100分）- 最重要的因素
        score += calculatePinyinMatchScore(pinyin, candidate, 
            isPrefixMatchedCommonWord, isAbbreviation, isMultiWord, isIncomplete, isSingleWord)
        
        // 2. 用户习惯分数（0-80分）- 用户使用过的词优先（增强权重）
        val userInputCount = getUserInputCount(pinyin, candidate)
        score += minOf(userInputCount * 15.0, 80.0) // 每次使用加15分，最多80分（增强）
        
        // 3. 词频分数（0-30分）- 常用词有基础加分
        val isCommonWord = multiCharMapping.values.any { it.contains(candidate) }
        if (isCommonWord) {
            score += 30.0
        }
        
        // 4. 长度优化分数（0-20分）- 短词优先，但多字词也有优势
        when {
            candidate.length == 1 -> score += 5.0   // 单字：5分
            candidate.length == 2 -> score += 20.0  // 2字词：20分（最优先）
            candidate.length == 3 -> score += 15.0  // 3字词：15分
            candidate.length == 4 -> score += 10.0  // 4字词：10分
            else -> score += 5.0                    // 更长：5分
        }
        
        // 5. 字符热门度分数（0-10分）- 热门字符优先
        val charPopularity = getCharPopularity(candidate.firstOrNull()?.toString() ?: "")
        if (charPopularity != Int.MAX_VALUE) {
            // 转换为0-10分（位置越小，分数越高）
            val popularityScore = maxOf(0.0, 10.0 - (charPopularity / 100.0))
            score += popularityScore
        }
        
        return score
    }
    
    /**
     * 计算拼音匹配度分数
     * 根据匹配类型和匹配程度给分
     */
    private fun calculatePinyinMatchScore(
        pinyin: String,
        candidate: String,
        isPrefixMatchedCommonWord: Boolean,
        isAbbreviation: Boolean,
        isMultiWord: Boolean,
        isIncomplete: Boolean,
        isSingleWord: Boolean
    ): Double {
        // 1. 常用词前缀匹配（如 "dak" -> "dakai" -> "打开"）
        if (isPrefixMatchedCommonWord) {
            // 查找匹配的常用词拼音
            var maxMatchRatio = 0.0
            for ((key, words) in multiCharMapping) {
                if (words.contains(candidate) && key.startsWith(pinyin)) {
                    val matchRatio = pinyin.length.toDouble() / key.length.toDouble()
                    maxMatchRatio = maxOf(maxMatchRatio, matchRatio)
                }
            }
            // 匹配度越高，分数越高（60-95分）
            return 60.0 + (maxMatchRatio * 35.0)
        }
        
        // 2. 简拼匹配（如 "dk" -> "打开"）
        if (isAbbreviation) {
            // 简拼匹配度：根据输入长度和词长度计算
            val abbreviationRatio = pinyin.length.toDouble() / candidate.length.toDouble()
            // 简拼匹配分数（70-90分）
            return 70.0 + (abbreviationRatio * 20.0).coerceAtMost(20.0)
        }
        
        // 3. 多字词匹配（完整拼音连打，如 "shurufa" -> "输入法"）
        if (isMultiWord) {
            // 多字词匹配分数（80-95分）
            return 80.0 + (candidate.length * 3.0).coerceAtMost(15.0)
        }
        
        // 4. 不完整拼音匹配（如 "shuruf" -> "输入法"）
        if (isIncomplete) {
            // 不完整拼音匹配分数（50-75分）
            return 50.0 + (candidate.length * 5.0).coerceAtMost(25.0)
        }
        
        // 5. 单字匹配（前缀匹配）
        if (isSingleWord) {
            // 单字匹配分数（30-60分）
            val charPopularity = getCharPopularity(candidate)
            if (charPopularity != Int.MAX_VALUE) {
                val popularityScore = maxOf(0.0, 30.0 - (charPopularity / 100.0))
                return popularityScore
            }
            return 30.0
        }
        
        // 默认分数
        return 0.0
    }
    
    /**
     * 获取字符的热门程度
     * 返回该字符在allPinyinMapping中首次出现的位置（越小越热门）
     * 如果找不到，返回一个很大的数字
     */
    private fun getCharPopularity(char: String): Int {
        if (char.isEmpty()) {
            return Int.MAX_VALUE
        }
        
        // 遍历allPinyinMapping，查找该字符首次出现的位置
        var position = 0
        for ((pinyin, chars) in allPinyinMapping) {
            val index = chars.indexOf(char)
            if (index >= 0) {
                // 找到该字符，返回位置 + 在该拼音候选列表中的位置
                // 位置越小，表示越热门
                return position * 100 + index
            }
            position++
        }
        
        // 如果找不到，返回一个很大的数字
        return Int.MAX_VALUE
    }
}

