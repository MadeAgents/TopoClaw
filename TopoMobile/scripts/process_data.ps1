# Copyright 2025 OPPO

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# PowerShell 脚本：处理 pinyin-data 和 Rime-ice 数据
# 生成 Kotlin 代码

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$externalDir = Join-Path $projectRoot "external"

Write-Host "开始处理拼音数据..." -ForegroundColor Green

# 处理 pinyin-data
$pinyinDataFile = Join-Path $externalDir "pinyin-data\kTGHZ2013.txt"
if (Test-Path $pinyinDataFile) {
    Write-Host "处理 pinyin-data..." -ForegroundColor Yellow
    $pinyinMap = @{}
    
    Get-Content $pinyinDataFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            # 格式: U+4E2D: zhōng,zhòng  # 中
            if ($line -match "U\+([0-9A-F]+):\s+([^#]+)\s+#\s*(.+)") {
                $pinyins = $matches[2].Trim()
                $char = $matches[3].Trim()
                
                $pinyins -split "," | ForEach-Object {
                    $pinyin = $_.Trim().ToLower()
                    if ($pinyin -and $char) {
                        if (-not $pinyinMap.ContainsKey($pinyin)) {
                            $pinyinMap[$pinyin] = @()
                        }
                        if ($pinyinMap[$pinyin] -notcontains $char) {
                            $pinyinMap[$pinyin] += $char
                        }
                    }
                }
            }
        }
    }
    
    # 生成 Kotlin 代码
    $outputFile = Join-Path $PSScriptRoot "generated_pinyin_map.kt"
    $content = "// 自动生成 - 来自 pinyin-data`n"
    $content += "// 共 ${$pinyinMap.Count} 个拼音映射`n"
    $content += "private val pinyinMapFromData = mapOf(`n"
    
    $sortedPinyins = $pinyinMap.Keys | Sort-Object
    $index = 0
    foreach ($pinyin in $sortedPinyins) {
        $chars = $pinyinMap[$pinyin] | Select-Object -First 10
        $charsStr = ($chars | ForEach-Object { "`"$_`"" }) -join ", "
        $comma = if ($index -lt $sortedPinyins.Count - 1) { "," } else { "" }
        $content += "    `"$pinyin`" to listOf($charsStr)$comma`n"
        $index++
    }
    
    $content += ")`n"
    $content | Out-File -FilePath $outputFile -Encoding UTF8
    Write-Host "生成完成: $outputFile (${$pinyinMap.Count} 个拼音映射)" -ForegroundColor Green
} else {
    Write-Host "未找到 pinyin-data 文件: $pinyinDataFile" -ForegroundColor Red
}

# 处理 Rime-ice
$rimeIceFile = Join-Path $externalDir "rime-ice\cn_dicts\base.dict.yaml"
if (Test-Path $rimeIceFile) {
    Write-Host "处理 Rime-ice..." -ForegroundColor Yellow
    $commonWordsMap = @{}
    $inData = $false
    
    Get-Content $rimeIceFile -Encoding UTF8 | ForEach-Object {
        $line = $_
        if ($line -eq "...") {
            $inData = $true
            return
        }
        if (-not $inData -or -not $line.Trim() -or $line.StartsWith("#")) {
            return
        }
        
        # 格式: 词	拼音（空格分隔）	词频
        $parts = $line -split "`t"
        if ($parts.Length -ge 3) {
            $word = $parts[0].Trim()
            $pinyin = ($parts[1].Trim() -replace " ", "").ToLower()
            $freqStr = $parts[2].Trim()
            $freq = 0
            if ([int]::TryParse($freqStr, [ref]$freq)) {
                $freq = [int]$freqStr
            }
            
            if ($word -and $pinyin -and $freq -gt 50) {  # 只取词频 > 50 的
                if (-not $commonWordsMap.ContainsKey($pinyin)) {
                    $commonWordsMap[$pinyin] = @()
                }
                $commonWordsMap[$pinyin] += [PSCustomObject]@{Word = $word; Freq = $freq}
            }
        }
    }
    
    # 按词频排序，每个拼音只保留前5个最常用的词
    $outputFile = Join-Path $PSScriptRoot "generated_common_words.kt"
    $content = "// 自动生成 - 来自 Rime-ice (词频 > 50)`n"
    $content += "// 共 ${$commonWordsMap.Count} 个拼音映射`n"
    $content += "private val commonWordsMapFromRime = mapOf(`n"
    
    # 按最大词频排序，取前3000个
    $sortedEntries = $commonWordsMap.GetEnumerator() | 
        ForEach-Object {
            $maxFreq = ($_.Value | Measure-Object -Property Freq -Maximum).Maximum
            [PSCustomObject]@{Pinyin = $_.Key; Words = $_.Value; MaxFreq = $maxFreq}
        } | 
        Sort-Object -Property MaxFreq -Descending | 
        Select-Object -First 3000
    
    $index = 0
    foreach ($entry in $sortedEntries) {
        $pinyin = $entry.Pinyin
        $words = $entry.Words | 
            Sort-Object -Property Freq -Descending | 
            Select-Object -First 5 -ExpandProperty Word | 
            Select-Object -Unique
        
        $wordsStr = ($words | ForEach-Object { "`"$_`"" }) -join ", "
        $comma = if ($index -lt $sortedEntries.Count - 1) { "," } else { "" }
        $content += "    `"$pinyin`" to listOf($wordsStr)$comma`n"
        $index++
    }
    
    $content += ")`n"
    $content | Out-File -FilePath $outputFile -Encoding UTF8
    Write-Host "生成完成: $outputFile (${$sortedEntries.Count} 个常用词映射)" -ForegroundColor Green
} else {
    Write-Host "未找到 Rime-ice 文件: $rimeIceFile" -ForegroundColor Red
}

Write-Host "`n处理完成！" -ForegroundColor Green
Write-Host "请查看生成的 .kt 文件，并手动合并到 PinyinDictionary.kt 中" -ForegroundColor Yellow
