#!/bin/bash
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
# 使用方法: ./filter_logs.sh [filter_type]

PACKAGE_NAME="com.cloudcontrol.demo"
FILTER_TYPE=${1:-"all"}

echo "=== Android Log 过滤器 ==="
echo "包名: $PACKAGE_NAME"
echo ""

# 清除之前的日志
adb logcat -c

case $FILTER_TYPE in
    "all")
        echo "显示所有日志..."
        adb logcat | grep "$PACKAGE_NAME"
        ;;
    "errors")
        echo "仅显示错误日志..."
        adb logcat *:E | grep "$PACKAGE_NAME"
        ;;
    "tutorial")
        echo "显示教程相关日志..."
        adb logcat -s TutorialDialog:* MainActivity:* | grep -i "tutorial\|教程"
        ;;
    "main")
        echo "显示 MainActivity 日志..."
        adb logcat -s MainActivity:*
        ;;
    "chat")
        echo "显示聊天相关日志..."
        adb logcat -s ChatFragment:* MainActivity:*
        ;;
    "service")
        echo "显示服务相关日志..."
        adb logcat -s ScreenshotService:* MyAccessibilityService:*
        ;;
    *)
        echo "未知的过滤类型: $FILTER_TYPE"
        echo "可用类型: all, errors, tutorial, main, chat, service"
        ;;
esac
