"""Tool for retrieving top deeplinks from synced catalog."""

from __future__ import annotations

import json
from typing import Any

from topoclaw.agent.tools.base import Tool
from topoclaw.service.deeplink_catalog_service import (
    DeeplinkCatalogService,
    get_default_deeplink_catalog_service,
)


class SearchDeeplinkCatalogTool(Tool):
    """Lookup deeplinks by intent query and optional app filter."""

    def __init__(self, catalog_service: DeeplinkCatalogService | None = None) -> None:
        self._catalog_service = catalog_service or get_default_deeplink_catalog_service()

    @property
    def name(self) -> str:
        return "search_deeplink_catalog"

    @property
    def description(self) -> str:
        return (
            "Search deeplink catalog and return best Top-K matches. "
            "Input query is required; app_name is optional and filters candidates first."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Natural language query for target deeplink capability.",
                    "minLength": 1,
                },
                "app_name": {
                    "type": "string",
                    "description": "Optional app name filter, e.g. 支付宝、QQ、小红书.",
                },
                "top_k": {
                    "type": "integer",
                    "description": "How many candidates to return. Default 3, max 5.",
                    "minimum": 1,
                    "maximum": 5,
                },
            },
            "required": ["query"],
        }

    async def execute(
        self,
        query: str,
        app_name: str | None = None,
        top_k: int = 3,
        **kwargs: Any,
    ) -> str:
        result = self._catalog_service.search(query=query, app_name=app_name, top_k=top_k)
        return json.dumps(result, ensure_ascii=False)

