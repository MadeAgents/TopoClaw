#!/usr/bin/env python3
# -*- coding: utf-8 -*-
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

"""
解析 pinyin-data 和 Rime-ice 词库，生成 Kotlin 代码
使用方法：
1. 下载 pinyin-data: git clone https://github.com/mozillazg/pinyin-data.git
2. 下载 rime-ice: git clone https://github.com/iDvel/rime-ice.git
3. 运行此脚本: python parse_pinyin_data.py
"""

import os
import re
from collections import defaultdict
from pathlib import Path

def parse_pinyin_data(pinyin_data_dir):
    """解析 pinyin-data 的单字映射"""
    print("正在解析 pinyin-data...")
    pinyin_map = defaultdict(list)
    
    # 查找 kTGHZ2013.txt 文件
    ktghz_file = Path(pinyin_data_dir) / "kTGHZ2013.txt"
    if not ktghz_file.exists():
        print(f"警告: 未找到 {ktghz_file}")
        return pinyin_map
    
    with open(ktghz_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            
            # 格式: U+4E2D: zhōng,zhòng  # 中
            match = re.match(r'U\+([0-9A-F]+):\s+([^#]+)\s+#\s*(.+)', line)
            if match:
                unicode_code = match.group(1)
                pinyins = match.group(2).strip()
                char = match.group(3).strip()
                
                # 处理多音字
                for pinyin_item in pinyins.split(','):
                    pinyin_item = pinyin_item.strip().lower()
                    if pinyin_item and char:
                        pinyin_map[pinyin_item].append(char)
    
    print(f"解析完成: {len(pinyin_map)} 个拼音映射")
    return pinyin_map

def parse_rime_ice(rime_ice_dir):
    """解析 Rime-ice 的常用词库"""
    print("正在解析 Rime-ice...")
    common_words_map = defaultdict(list)
    
    # 查找词库文件（通常在 cn_dicts 目录下）
    dict_dir = Path(rime_ice_dir) / "cn_dicts"
    if not dict_dir.exists():
        print(f"警告: 未找到 {dict_dir}")
        return common_words_map
    
    # 查找所有 .dict.yaml 文件
    dict_files = list(dict_dir.glob("*.dict.yaml"))
    if not dict_files:
        print(f"警告: 未找到 .dict.yaml 文件")
        return common_words_map
    
    for dict_file in dict_files:
        print(f"  处理文件: {dict_file.name}")
        with open(dict_file, 'r', encoding='utf-8') as f:
            in_data = False
            for line in f:
                line = line.strip()
                
                # 跳过注释和空行
                if not line or line.startswith('#'):
                    continue
                
                # 检测数据开始
                if line == '...':
                    in_data = True
                    continue
                
                if not in_data:
                    continue
                
                # 格式: 词	拼音	词频
                # 例如: 你好	ni hao	100
                parts = line.split('\t')
                if len(parts) >= 2:
                    word = parts[0].strip()
                    pinyin = parts[1].strip().replace(' ', '').lower()
                    
                    if word and pinyin:
                        common_words_map[pinyin].append(word)
    
    print(f"解析完成: {len(common_words_map)} 个常用词映射")
    return common_words_map

def generate_kotlin_code(pinyin_map, common_words_map, output_file):
    """生成 Kotlin 代码"""
    print("正在生成 Kotlin 代码...")
    
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write("// 自动生成的代码 - 来自 pinyin-data 和 Rime-ice\n")
        f.write("// 请手动合并到 PinyinDictionary.kt 中\n\n")
        
        # 生成 pinyinMap 代码
        f.write("// ========== pinyinMap (来自 pinyin-data) ==========\n")
        f.write("private val pinyinMapFromData = mapOf(\n")
        
        # 按拼音排序
        sorted_pinyins = sorted(pinyin_map.keys())
        for i, pinyin in enumerate(sorted_pinyins):
            chars = pinyin_map[pinyin]
            # 去重并保持顺序
            unique_chars = []
            seen = set()
            for char in chars:
                if char not in seen:
                    unique_chars.append(char)
                    seen.add(char)
            
            chars_str = ', '.join(f'"{c}"' for c in unique_chars[:10])  # 最多10个
            comma = "," if i < len(sorted_pinyins) - 1 else ""
            f.write(f'        "{pinyin}" to listOf({chars_str}){comma}\n')
        
        f.write("    )\n\n")
        
        # 生成 commonWordsMap 代码
        f.write("// ========== commonWordsMap (来自 Rime-ice) ==========\n")
        f.write("private val commonWordsMapFromRime = mapOf(\n")
        
        # 按拼音排序，只取前5000个最常用的
        sorted_common = sorted(common_words_map.items(), key=lambda x: len(x[1]), reverse=True)[:5000]
        for i, (pinyin, words) in enumerate(sorted_common):
            # 去重
            unique_words = list(dict.fromkeys(words))[:5]  # 每个拼音最多5个词
            words_str = ', '.join(f'"{w}"' for w in unique_words)
            comma = "," if i < len(sorted_common) - 1 else ""
            f.write(f'        "{pinyin}" to listOf({words_str}){comma}\n')
        
        f.write("    )\n")
    
    print(f"代码已生成到: {output_file}")

def main():
    # 配置路径（请根据实际情况修改）
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    
    pinyin_data_dir = project_root / "external" / "pinyin-data"
    rime_ice_dir = project_root / "external" / "rime-ice"
    output_file = script_dir / "generated_pinyin_data.kt"
    
    print("=" * 60)
    print("拼音数据解析脚本")
    print("=" * 60)
    
    # 检查目录
    if not pinyin_data_dir.exists():
        print(f"\n错误: 未找到 pinyin-data 目录: {pinyin_data_dir}")
        print("请先下载: git clone https://github.com/mozillazg/pinyin-data.git external/pinyin-data")
        return
    
    if not rime_ice_dir.exists():
        print(f"\n错误: 未找到 rime-ice 目录: {rime_ice_dir}")
        print("请先下载: git clone https://github.com/iDvel/rime-ice.git external/rime-ice")
        return
    
    # 解析数据
    pinyin_map = parse_pinyin_data(pinyin_data_dir)
    common_words_map = parse_rime_ice(rime_ice_dir)
    
    # 生成代码
    if pinyin_map or common_words_map:
        generate_kotlin_code(pinyin_map, common_words_map, output_file)
        print(f"\n完成! 生成的代码文件: {output_file}")
        print("请手动将生成的代码合并到 PinyinDictionary.kt 中")
    else:
        print("\n错误: 未解析到任何数据")

if __name__ == "__main__":
    main()
