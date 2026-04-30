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

"""Voice transcription provider using Groq."""

import os
from pathlib import Path
from typing import Any

import httpx
from loguru import logger

from topoclaw.config.paths import get_runtime_subdir
from topoclaw.service.token_usage_service import TokenUsageService
from topoclaw.utils.helpers import estimate_message_tokens
from topoclaw.utils.path_guard import resolve_path


class GroqTranscriptionProvider:
    """
    Voice transcription provider using Groq's Whisper API.

    Groq offers extremely fast transcription with a generous free tier.
    """

    _default_usage_service: TokenUsageService | None = None

    def __init__(
        self,
        api_key: str | None = None,
        token_usage_service: TokenUsageService | None = None,
    ):
        self.api_key = api_key or os.environ.get("GROQ_API_KEY")
        self.api_url = "https://api.groq.com/openai/v1/audio/transcriptions"
        self._token_usage_service = token_usage_service or self._get_default_usage_service()

    async def transcribe(self, file_path: str | Path) -> str:
        """
        Transcribe an audio file using Groq.

        Args:
            file_path: Path to the audio file.

        Returns:
            Transcribed text.
        """
        if not self.api_key:
            logger.warning("Groq API key not configured for transcription")
            return ""

        try:
            path = resolve_path(file_path)
        except ValueError as e:
            logger.error("Invalid audio path {}: {}", file_path, e)
            return ""
        if not path.exists() or not path.is_file():
            logger.error("Audio file not found: {}", file_path)
            return ""

        try:
            async with httpx.AsyncClient() as client:
                with open(path, "rb") as f:
                    files = {
                        "file": (path.name, f),
                        "model": (None, "whisper-large-v3"),
                    }
                    headers = {
                        "Authorization": f"Bearer {self.api_key}",
                    }

                    response = await client.post(
                        self.api_url,
                        headers=headers,
                        files=files,
                        timeout=60.0
                    )

                    response.raise_for_status()
                    data = response.json()
                    text = str(data.get("text", "") or "")
                    self._record_usage(model="whisper-large-v3", transcript=text, payload=data)
                    return text

        except Exception as e:
            logger.error("Groq transcription error: {}", e)
            return ""

    @classmethod
    def _get_default_usage_service(cls) -> TokenUsageService | None:
        if cls._default_usage_service is not None:
            return cls._default_usage_service
        try:
            cls._default_usage_service = TokenUsageService(get_runtime_subdir("metrics") / "token_usage.db")
            return cls._default_usage_service
        except Exception:
            return None

    def _record_usage(self, *, model: str, transcript: str, payload: Any) -> None:
        svc = self._token_usage_service
        if svc is None:
            return

        usage_obj = self._extract_usage(payload)
        prompt_tokens = self._safe_int(usage_obj.get("prompt_tokens"))
        completion_tokens = self._safe_int(usage_obj.get("completion_tokens"))
        total_tokens = self._safe_int(usage_obj.get("total_tokens"))
        is_estimated = False

        if prompt_tokens <= 0 and completion_tokens <= 0 and total_tokens <= 0:
            completion_tokens = estimate_message_tokens({"role": "assistant", "content": transcript})
            prompt_tokens = 0
            total_tokens = completion_tokens
            is_estimated = True

        if total_tokens <= 0:
            total_tokens = prompt_tokens + completion_tokens

        try:
            svc.record_usage(
                model=(model or "unknown").strip() or "unknown",
                input_tokens=prompt_tokens,
                output_tokens=completion_tokens,
                total_tokens=total_tokens,
                source="tool_audio_transcription",
                is_estimated=is_estimated,
            )
        except Exception:
            pass

    @staticmethod
    def _extract_usage(payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            return {}

        direct = payload.get("usage")
        if isinstance(direct, dict):
            return direct

        nested = payload.get("x_groq")
        if isinstance(nested, dict):
            nested_usage = nested.get("usage")
            if isinstance(nested_usage, dict):
                return nested_usage

        return payload

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return max(0, int(value))
        except Exception:
            return 0
