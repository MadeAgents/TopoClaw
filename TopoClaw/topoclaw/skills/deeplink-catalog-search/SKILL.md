---
name: deeplink-catalog-search
description: Retrieve top deeplink candidates from desktop-synced catalog.
metadata: {"topoclaw":{"always":false,"emoji":"🔗"}}
---

# Deeplink Catalog Search

Use this skill when user asks to find deeplinks by intent, feature, or app scenario.

## When to use

- User asks "哪个 deeplink 可以实现 X"
- User asks for best 2-3 deeplink options
- User gives app name + intent (e.g. 支付宝 + 收款码)

## Tool to call

Call `search_deeplink_catalog` with:

- `query` (required): user intent in natural language
- `app_name` (optional): app filter to reduce candidates
- `top_k` (optional): default 3, max 5

## Output handling

Tool returns ranked candidates with:

- `deeplink`
- `call_method`
- `description`
- `parameters`
- `match_reason`

Then:

1. Explain Top 1-3 options briefly.
2. Ask user to choose one if execution is not explicit.
3. If user already requested execution, continue with selected deeplink directly.
