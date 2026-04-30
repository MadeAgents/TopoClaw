"""Desktop-side mobile location skill with reverse geocoding."""

from __future__ import annotations

import json
from typing import Any

import httpx

from topoclaw.agent.tools.base import Tool
from topoclaw.service.gui_mobile_service import dispatch_mobile_tool_request


class MobileLocationSkillTool(Tool):
    """Get mobile coordinates then reverse geocode to readable address/POIs."""

    def __init__(self, app_config: Any | None = None) -> None:
        self._channel = "cli"
        self._chat_id = "direct"
        self._imei = ""
        self._app_config = app_config

    def set_context(self, channel: str, chat_id: str, metadata: dict[str, Any] | None = None) -> None:
        self._channel = channel
        self._chat_id = chat_id
        self._imei = str((metadata or {}).get("imei") or "").strip()

    @property
    def name(self) -> str:
        return "mobile_location_skill"

    @property
    def description(self) -> str:
        return (
            "Get phone coordinates, then reverse geocode to real address and nearby POIs. "
            "Use when user asks where they are or nearby places."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "accuracy": {
                    "type": "string",
                    "enum": ["coarse", "fine"],
                    "description": "Location precision hint for phone-side request.",
                },
                "timeout_s": {
                    "type": "integer",
                    "minimum": 3,
                    "maximum": 120,
                    "description": "Timeout seconds waiting phone-side location result.",
                },
                "poi_limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 20,
                    "description": "Maximum number of nearby POIs in output.",
                },
                "api_key": {
                    "type": "string",
                    "description": "Override reverse geocode API key for this call.",
                },
                "regeo_url": {
                    "type": "string",
                    "description": "Override reverse geocode endpoint for this call.",
                },
            },
            "required": [],
        }

    async def execute(
        self,
        accuracy: str = "coarse",
        timeout_s: int = 20,
        poi_limit: int = 5,
        api_key: str | None = None,
        regeo_url: str | None = None,
        **kwargs: Any,
    ) -> str:
        resolved_accuracy = str(accuracy or "coarse").strip().lower()
        if resolved_accuracy not in {"coarse", "fine"}:
            resolved_accuracy = "coarse"
        try:
            timeout_value = int(timeout_s)
        except (TypeError, ValueError):
            timeout_value = 20
        bounded_timeout = max(3, min(timeout_value, 120))
        try:
            poi_cap = int(poi_limit)
        except (TypeError, ValueError):
            poi_cap = 5
        poi_cap = max(1, min(poi_cap, 20))

        location_result = await dispatch_mobile_tool_request(
            thread_id=self._chat_id,
            tool="device.get_location",
            args={
                "accuracy": resolved_accuracy,
                "with_address": False,
                "timeout_ms": bounded_timeout * 1000,
            },
            timeout_s=bounded_timeout,
            protocol="mobile_tool/v1",
        )
        if not bool(location_result.get("success")):
            return f"Error: {location_result.get('error') or 'mobile location request failed'}"

        parsed_location = self._parse_location_payload(str(location_result.get("content") or ""))
        if isinstance(parsed_location, str):
            return f"Error: {parsed_location}"

        lng = parsed_location["lng"]
        lat = parsed_location["lat"]
        cfg_key, cfg_url, cfg_timeout = self._resolve_reverse_geocode_config()
        final_key = str(api_key or cfg_key).strip()
        final_url = str(regeo_url or cfg_url).strip()
        if not final_url:
            final_url = "https://restapi.amap.com/v3/geocode/regeo"
        if not final_key:
            return (
                "Error: reverse geocode API key is missing. "
                "Configure TopoDesktop/config.txt with API_KEY (or reverse_geocode_api_key)."
            )

        reverse = await self._reverse_geocode(
            lng=lng,
            lat=lat,
            api_key=final_key,
            regeo_url=final_url,
            timeout_seconds=cfg_timeout,
            poi_limit=poi_cap,
        )
        if isinstance(reverse, str):
            return f"Error: {reverse}"

        out = {
            "location": parsed_location,
            "reverse_geocode": reverse,
        }
        return json.dumps(out, ensure_ascii=False)

    @staticmethod
    def _parse_location_payload(content: str) -> dict[str, Any] | str:
        text = str(content or "").strip()
        if not text:
            return "mobile_get_location returned empty content"
        if text.startswith("[") and "]" in text:
            return text
        try:
            data = json.loads(text)
        except Exception:
            return f"invalid mobile location payload: {text[:200]}"
        if not isinstance(data, dict):
            return "mobile location payload is not a JSON object"

        try:
            lat = float(data.get("lat"))
            lng = float(data.get("lng"))
        except (TypeError, ValueError):
            return "mobile location payload missing lat/lng"
        return {
            "lat": lat,
            "lng": lng,
            "accuracy_m": data.get("accuracy_m"),
            "provider": data.get("provider"),
            "captured_at": data.get("captured_at"),
        }

    def _resolve_reverse_geocode_config(self) -> tuple[str, str, int]:
        cfg = self._app_config
        if not cfg:
            return "", "https://restapi.amap.com/v3/geocode/regeo", 10
        try:
            mobile_cfg = cfg.tools.mobile_location
            reverse_cfg = mobile_cfg.reverse_geocode
            timeout_value = int(getattr(reverse_cfg, "timeout_seconds", 10) or 10)
            timeout_value = max(3, min(timeout_value, 60))
            return (
                str(getattr(reverse_cfg, "api_key", "") or "").strip(),
                str(getattr(reverse_cfg, "regeo_url", "") or "").strip(),
                timeout_value,
            )
        except Exception:
            return "", "https://restapi.amap.com/v3/geocode/regeo", 10

    async def _reverse_geocode(
        self,
        *,
        lng: float,
        lat: float,
        api_key: str,
        regeo_url: str,
        timeout_seconds: int,
        poi_limit: int,
    ) -> dict[str, Any] | str:
        params = {
            "key": api_key,
            "location": f"{lng},{lat}",
            "extensions": "all",
            "output": "json",
        }
        try:
            async with httpx.AsyncClient(timeout=float(timeout_seconds)) as client:
                resp = await client.get(regeo_url, params=params)
            if resp.status_code >= 400:
                return f"reverse geocode http error {resp.status_code}: {resp.text[:300]}"
            payload = resp.json()
        except Exception as exc:
            return f"reverse geocode request failed: {exc}"

        if not isinstance(payload, dict):
            return "reverse geocode response is not JSON object"
        if str(payload.get("status")) != "1":
            info = str(payload.get("info") or "unknown error")
            infocode = str(payload.get("infocode") or "")
            return f"reverse geocode failed: {info} ({infocode})".strip()

        regeo = payload.get("regeocode")
        if not isinstance(regeo, dict):
            return "reverse geocode response missing 'regeocode'"
        pois = regeo.get("pois")
        out_pois: list[dict[str, Any]] = []
        if isinstance(pois, list):
            for poi in pois[:poi_limit]:
                if not isinstance(poi, dict):
                    continue
                item: dict[str, Any] = {
                    "name": poi.get("name"),
                    "type": poi.get("type"),
                    "address": poi.get("address"),
                    "distance_m": poi.get("distance"),
                }
                location = str(poi.get("location") or "").strip()
                if "," in location:
                    lon_text, lat_text = location.split(",", 1)
                    try:
                        item["lng"] = float(lon_text)
                        item["lat"] = float(lat_text)
                    except ValueError:
                        pass
                out_pois.append(item)
        return {
            "formatted_address": regeo.get("formatted_address"),
            "nearby_pois": out_pois,
        }

