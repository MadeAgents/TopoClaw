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

"""Base parser for GUI agents."""

import re
from dataclasses import dataclass
from typing import Any

from loguru import logger


@dataclass
class ParsedResponse:
    """Parsed LLM response structure."""
    
    summary: str = ""
    thought: str = ""
    action_intent: str = ""  
    raw_action: str = ""
    action: str = ""
    
    # Advanced planning and memory
    plan_update: list[str] | None = None
    note_append: str | None = None
    
    def to_dict(self) -> dict[str, Any]:
        """Convert to dictionary."""
        return {
            "summary": self.summary,
            "thought": self.thought,
            "action_intent": self.action_intent,
            "raw_action": self.raw_action,
            "plan_update": self.plan_update,
            "note_append": self.note_append,
        }


class BaseParser:
    """Base parser for extracting actions from LLM output."""

    @classmethod
    def parse(cls, response_text: str, agent_type: str = "mobile") -> ParsedResponse:
        """Parse LLM response to extract summary, thought, action_intent, and action."""
        if not response_text:
            logger.warning("Empty response text")
            return ParsedResponse()
            
        parsed_json = cls._parse_json_response(response_text)
        if parsed_json:
            return parsed_json
            
        return cls._parse_regex_fallback(response_text, agent_type)

    @classmethod
    def _parse_json_response(cls, response_text: str) -> ParsedResponse | None:
        """Parse JSON format response."""
        import json
        
        json_text = response_text.strip()
        
        # Remove markdown code blocks if present
        if json_text.startswith("```"):
            json_match = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", json_text, re.DOTALL)
            if json_match:
                json_text = json_match.group(1)
            else:
                json_match = re.search(r"\{.*\}", json_text, re.DOTALL)
                if json_match:
                    json_text = json_match.group(0)
                    
        try:
            data = json.loads(json_text)
            return ParsedResponse(
                summary=data.get("summary", ""),
                thought=data.get("thought", ""),
                action_intent=data.get("action_intent", "") or data.get("reasoning", ""),
                raw_action=data.get("action", ""),
                plan_update=data.get("plan_update"),
                note_append=data.get("note_append"),
            )
        except (json.JSONDecodeError, AttributeError):
            try:
                import json_repair
                repaired_json = json_repair.loads(json_text)
                return ParsedResponse(
                    summary=repaired_json.get("summary", ""),
                    thought=repaired_json.get("thought", ""),
                    action_intent=repaired_json.get("action_intent", "") or repaired_json.get("reasoning", ""),
                    raw_action=repaired_json.get("action", ""),
                    plan_update=repaired_json.get("plan_update"),
                    note_append=repaired_json.get("note_append"),
                )
            except Exception as e:
                logger.debug("JSON parsing failed: {}", e)
                return None

    @classmethod
    def _parse_regex_fallback(cls, response_text: str, agent_type: str) -> ParsedResponse:
        """Fallback to regex parsing if JSON fails."""
        # Try finding the raw action using heuristic patterns
        if agent_type == "mobile":
            action_patterns = [
                r"###action:\s*([a-z_]+(?:\[[^\]]+\])?)",
                r"action:\s*([a-z_]+(?:\[[^\]]+\])?)",
                r"([a-z_]+\[[^\]]+\]|complete|wait|back|home|screenshot|open\[|call_user\[|answer\[)",
            ]
        else:
            action_patterns = [
                r"###action:\s*([a-z_A-Z]+(?:\[[^\]]+\])?)",
                r"action:\s*([a-z_A-Z]+(?:\[[^\]]+\])?)",
                r"(click|double_click|right_click|move|type|hotkey|keydown|keyup|scroll|drag|wait|call_user|answer|complete)(?:\[[^\]]+\])?",
                r"(CLICK|DOUBLE_CLICK|RIGHT_CLICK|MOVE|TYPE|HOTKEY|KEYDOWN|KEYUP|SCROLL|DRAG|WAIT|CALL_USER|ANSWER|COMPLETE)(?:\[[^\]]+\])?",
            ]
            
        raw_action = ""
        for pattern in action_patterns:
            match = re.search(pattern, response_text, re.IGNORECASE)
            if match:
                raw_action = match.group(1).strip()
                break
                
        # Attempt to extract other fields if possible
        summary = ""
        thought = ""
        action_intent = ""
        
        summary_match = re.search(r"summary:\s*([^#]+)", response_text, re.IGNORECASE)
        thought_match = re.search(r"thought:\s*([^#]+)", response_text, re.IGNORECASE)
        reasoning_match = re.search(r"reasoning:\s*([^#]+)", response_text, re.IGNORECASE)
        
        if summary_match: summary = summary_match.group(1).strip()
        if thought_match: thought = thought_match.group(1).strip()
        if reasoning_match: action_intent = reasoning_match.group(1).strip()
        
        return ParsedResponse(
            summary=summary,
            thought=thought,
            action_intent=action_intent,
            raw_action=raw_action
        )
