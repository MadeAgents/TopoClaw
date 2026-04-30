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

"""One-off bulk replace for TopoClaw assistant naming in Kotlin sources."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "java" / "com" / "cloudcontrol" / "demo"

def process(text: str) -> str:
    # Order matters: longer / composite patterns first

    # (isAssistantReply && sender == "自动执行小助手")
    text = re.sub(
        r"\(isAssistantReply\s*&&\s*sender\s*==\s*\"自动执行小助手\"\)",
        "(isAssistantReply && ChatConstants.isMainAssistantSender(sender))",
        text,
    )

    # (isInGroupContext || (isAssistantReply && sender == "自动执行小助手"))
    text = re.sub(
        r"\(isInGroupContext\s*\|\|\s*\(isAssistantReply\s*&&\s*sender\s*==\s*\"自动执行小助手\"\)\)",
        "(isInGroupContext || (isAssistantReply && ChatConstants.isMainAssistantSender(sender)))",
        text,
    )

    # if (sender == "自动执行小助手" || sender == "技能学习小助手"
    text = re.sub(
        r"if\s*\(\s*sender\s*==\s*\"自动执行小助手\"\s*\|\|\s*sender\s*==\s*\"技能学习小助手\"",
        "if (ChatConstants.isMainAssistantSender(sender) || sender == \"技能学习小助手\"",
        text,
    )

    # item.sender == "自动执行小助手" || item.sender == "小助手"
    text = re.sub(
        r"item\.sender\s*==\s*\"自动执行小助手\"\s*\|\|\s*item\.sender\s*==\s*\"小助手\"",
        "ChatConstants.isMainAssistantSender(item.sender)",
        text,
    )

    # name = "自动执行小助手"
    text = re.sub(
        r'name\s*=\s*"自动执行小助手"',
        "name = ChatConstants.ASSISTANT_DISPLAY_NAME",
        text,
    )

    # -> "自动执行小助手"  (when branch return)
    text = re.sub(
        r'->\s*"自动执行小助手"\b',
        "-> ChatConstants.ASSISTANT_DISPLAY_NAME",
        text,
    )

    # Generic: expr == "自动执行小助手"  -> ChatConstants.isMainAssistantSender(expr)
    # Avoid double-replacing already converted lines
    def repl_eq(m):
        expr = m.group(1).strip()
        if expr.startswith("ChatConstants.isMainAssistantSender"):
            return m.group(0)
        return f"ChatConstants.isMainAssistantSender({expr})"

    text = re.sub(
        r"([\w.]+)\s*==\s*\"自动执行小助手\"",
        repl_eq,
        text,
    )

    # !command.contains("@自动执行小助手", ignoreCase = true) &&
    #                !command.contains("@自动执行助手", ignoreCase = true)
    text = re.sub(
        r"!command\.contains\(\"@自动执行小助手\",\s*ignoreCase\s*=\s*true\)\s*&&\s*\n\s*!command\.contains\(\"@自动执行助手\",\s*ignoreCase\s*=\s*true\)",
        "!ChatConstants.containsMainAssistantMention(command)",
        text,
    )
    text = re.sub(
        r"!command\.contains\(\"@自动执行小助手\",\s*ignoreCase\s*=\s*true\)\s*&&\s*!command\.contains\(\"@自动执行助手\",\s*ignoreCase\s*=\s*true\)",
        "!ChatConstants.containsMainAssistantMention(command)",
        text,
    )

    # Single-line variant
    text = re.sub(
        r"!command\.contains\(\"@自动执行小助手\",\s*ignoreCase\s*=\s*true\)\s*&&\s*!command\.contains\(\"@自动执行助手\",\s*ignoreCase\s*=\s*true\)",
        "!ChatConstants.containsMainAssistantMention(command)",
        text,
    )

    # "@自动执行小助手 $command"
    text = re.sub(
        r'"@自动执行小助手\s*\$command"',
        '"@${ChatConstants.ASSISTANT_DISPLAY_NAME} $command"',
        text,
    )

    # query.contains("@自动执行小助手", ignoreCase = true) ||
    text = re.sub(
        r"query\.contains\(\"@自动执行小助手\",\s*ignoreCase\s*=\s*true\)\s*\|\|",
        "ChatConstants.containsMainAssistantMention(query) ||",
        text,
    )

    # .replace("@自动执行小助手", "", ignoreCase = true)  (possibly chained)
    text = re.sub(
        r'\.replace\(\"@自动执行小助手\",\s*\"\",\s*ignoreCase\s*=\s*true\)',
        "",
        text,
    )

    # candidate.nickname ?: "自动执行小助手"
    text = re.sub(
        r'\?:\s*"自动执行小助手"',
        "?: ChatConstants.ASSISTANT_DISPLAY_NAME",
        text,
    )

    # addMemberItem("自动执行小助手",
    text = re.sub(
        r'addMemberItem\("自动执行小助手",',
        "addMemberItem(ChatConstants.ASSISTANT_DISPLAY_NAME,",
        text,
    )

    # Pair(... to "自动执行小助手")
    text = re.sub(
        r'to\s+"自动执行小助手"',
        "to ChatConstants.ASSISTANT_DISPLAY_NAME",
        text,
    )

    # val sender: String = "自动执行小助手"
    text = re.sub(
        r'val\s+sender:\s*String\s*=\s*"自动执行小助手"',
        "val sender: String = ChatConstants.ASSISTANT_DISPLAY_NAME",
        text,
    )

    # listOf("自动执行小助手", "小助手",
    text = re.sub(
        r'listOf\("自动执行小助手",\s*"小助手"',
        'listOf(ChatConstants.ASSISTANT_DISPLAY_NAME, ChatConstants.ASSISTANT_LEGACY_INTERNAL_NAME, "小助手"',
        text,
    )

    return text


def main():
    for path in sorted(ROOT.rglob("*.kt")):
        raw = path.read_text(encoding="utf-8")
        new = process(raw)
        if new != raw:
            path.write_text(new, encoding="utf-8")
            print("updated:", path.relative_to(ROOT.parents[4]))


if __name__ == "__main__":
    main()
