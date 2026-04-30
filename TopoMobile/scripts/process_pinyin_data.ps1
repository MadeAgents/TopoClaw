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

# PowerShell script to process pinyin-data and rime-ice
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk2Dir = Split-Path -Parent $scriptDir
$projectRoot = Split-Path -Parent $apk2Dir
$externalDir = Join-Path $projectRoot "external"

Write-Host "Processing pinyin data..." -ForegroundColor Green

# Process pinyin-data
$pinyinDataFile = Join-Path $externalDir "pinyin-data\kTGHZ2013.txt"
if (Test-Path $pinyinDataFile) {
    Write-Host "Processing pinyin-data..." -ForegroundColor Yellow
    $pinyinMap = @{}
    
    Get-Content $pinyinDataFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#")) {
            if ($line -match "U\+[0-9A-F]+:\s+([^#]+)\s+#\s*(.+)") {
                $pinyins = $matches[1].Trim()
                $char = $matches[2].Trim()
                
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
    
    # Generate Kotlin code
    $outputFile = Join-Path $scriptDir "generated_pinyin_map.kt"
    $sb = New-Object System.Text.StringBuilder
    $sb.AppendLine("// Auto-generated from pinyin-data") | Out-Null
    $sb.AppendLine("// Total: $($pinyinMap.Count) pinyin mappings") | Out-Null
    $sb.AppendLine("private val pinyinMapFromData = mapOf(") | Out-Null
    
    $sortedKeys = $pinyinMap.Keys | Sort-Object
    $index = 0
    foreach ($pinyin in $sortedKeys) {
        $chars = $pinyinMap[$pinyin] | Select-Object -First 10
        $charsArray = $chars | ForEach-Object { '"' + $_ + '"' }
        $charsStr = $charsArray -join ", "
        $comma = if ($index -lt $sortedKeys.Count - 1) { "," } else { "" }
        $sb.AppendLine("    `"$pinyin`" to listOf($charsStr)$comma") | Out-Null
        $index++
    }
    
    $sb.AppendLine(")") | Out-Null
    [System.IO.File]::WriteAllText($outputFile, $sb.ToString(), [System.Text.Encoding]::UTF8)
    Write-Host "Generated: $outputFile" -ForegroundColor Green
    Write-Host "Total: $($pinyinMap.Count) pinyin mappings" -ForegroundColor Green
} else {
    Write-Host "File not found: $pinyinDataFile" -ForegroundColor Red
}

# Process rime-ice
$rimeIceDir = Join-Path $externalDir "rime-ice\cn_dicts"
if (Test-Path $rimeIceDir) {
    Write-Host "Processing rime-ice..." -ForegroundColor Yellow
    $commonWordsMap = @{}
    
    Get-ChildItem -Path $rimeIceDir -Filter "*.dict.yaml" | ForEach-Object {
        Write-Host "  Processing: $($_.Name)" -ForegroundColor Cyan
        $inData = $false
        
        Get-Content $_.FullName -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            
            if ($line -eq "...") {
                $inData = $true
                return
            }
            
            if (-not $inData -or -not $line -or $line.StartsWith("#")) {
                return
            }
            
            $parts = $line -split "`t"
            if ($parts.Length -ge 3) {
                $word = $parts[0].Trim()
                $pinyin = ($parts[1].Trim() -replace " ", "").ToLower()
                $freq = 0
                [int]::TryParse($parts[2].Trim(), [ref]$freq) | Out-Null
                
                if ($word -and $pinyin) {
                    if (-not $commonWordsMap.ContainsKey($pinyin)) {
                        $commonWordsMap[$pinyin] = @()
                    }
                    $commonWordsMap[$pinyin] += [PSCustomObject]@{Word = $word; Freq = $freq}
                }
            }
        }
    }
    
    # Sort by frequency, take top 5 for each pinyin
    $sortedMap = @{}
    foreach ($pinyin in $commonWordsMap.Keys) {
        $words = $commonWordsMap[$pinyin] | 
            Sort-Object -Property Freq -Descending | 
            Select-Object -First 5 -ExpandProperty Word
        $sortedMap[$pinyin] = $words
    }
    
    # Take top 5000 most common
    $top5000 = $sortedMap.GetEnumerator() | 
        Sort-Object { $_.Value.Count } -Descending | 
        Select-Object -First 5000
    
    # Generate Kotlin code
    $outputFile = Join-Path $scriptDir "generated_common_words.kt"
    $sb = New-Object System.Text.StringBuilder
    $sb.AppendLine("// Auto-generated from rime-ice") | Out-Null
    $sb.AppendLine("// Total: $($top5000.Count) common word mappings") | Out-Null
    $sb.AppendLine("private val commonWordsMapFromRime = mapOf(") | Out-Null
    
    $index = 0
    foreach ($item in $top5000) {
        $pinyin = $item.Key
        $words = $item.Value
        $wordsArray = $words | ForEach-Object { '"' + $_ + '"' }
        $wordsStr = $wordsArray -join ", "
        $comma = if ($index -lt $top5000.Count - 1) { "," } else { "" }
        $sb.AppendLine("    `"$pinyin`" to listOf($wordsStr)$comma") | Out-Null
        $index++
    }
    
    $sb.AppendLine(")") | Out-Null
    [System.IO.File]::WriteAllText($outputFile, $sb.ToString(), [System.Text.Encoding]::UTF8)
    Write-Host "Generated: $outputFile" -ForegroundColor Green
    Write-Host "Total: $($top5000.Count) common word mappings" -ForegroundColor Green
} else {
    Write-Host "Directory not found: $rimeIceDir" -ForegroundColor Red
}

Write-Host "`nDone! Please merge the generated .kt files into PinyinDictionary.kt" -ForegroundColor Green
