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

# Android Log 过滤脚本
# 使用方法: .\filter_logs.ps1 [filter_type]

param(
    [Parameter(Position=0)]
    [ValidateSet("all", "errors", "tutorial", "main", "chat", "service")]
    [string]$FilterType = "all"
)

$packageName = "com.cloudcontrol.demo"

Write-Host "=== Android Log 过滤器 ===" -ForegroundColor Cyan
Write-Host "包名: $packageName" -ForegroundColor Yellow
Write-Host ""

# 清除之前的日志
adb logcat -c

switch ($FilterType) {
    "all" {
        Write-Host "显示所有日志..." -ForegroundColor Green
        adb logcat | Select-String -Pattern $packageName
    }
    "errors" {
        Write-Host "仅显示错误日志..." -ForegroundColor Red
        adb logcat *:E | Select-String -Pattern $packageName
    }
    "tutorial" {
        Write-Host "显示教程相关日志..." -ForegroundColor Green
        adb logcat -s TutorialDialog:* MainActivity:* | Select-String -Pattern "Tutorial|教程"
    }
    "main" {
        Write-Host "显示 MainActivity 日志..." -ForegroundColor Green
        adb logcat -s MainActivity:*
    }
    "chat" {
        Write-Host "显示聊天相关日志..." -ForegroundColor Green
        adb logcat -s ChatFragment:* MainActivity:*
    }
    "service" {
        Write-Host "显示服务相关日志..." -ForegroundColor Green
        adb logcat -s ScreenshotService:* MyAccessibilityService:*
    }
    default {
        Write-Host "未知的过滤类型: $FilterType" -ForegroundColor Red
        Write-Host "可用类型: all, errors, tutorial, main, chat, service" -ForegroundColor Yellow
    }
}
