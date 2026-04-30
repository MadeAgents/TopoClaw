---
name: image-collect-search
description: Search and return high-quality collected images using a Serper-compatible image endpoint. This skill only handles image collection and should be used in parallel with normal web search tools for factual text answers. Use when user asks for image collection, picture retrieval, visual references, or "find related images".
---

# Image Collect Search

This skill only handles image collection. It does not replace normal web text search.

## Workflow

1. Run normal web search tools for factual text in parallel when needed.
2. Run this skill's script to fetch and score candidate images.
3. Return exactly 2 selected images (or fewer if unavailable), with local paths and detailed metadata.

## Script

Script path is relative to this skill directory:

`scripts/collect_images.py`

Use Python to execute:

```bash
python "<ABS_SKILL_DIR>/scripts/collect_images.py" --query "<user_query>" --top 2
```

Notes:
- `SERPER_API_KEY` is required (or `--api-key`).
- `SERPER_API_BASE` is optional (or `--api-base`), recommended for private gateways.
- Do not hardcode key/base in code.

## Required Response Fields

When replying to user, include:

- Selected image local paths (`local_path`)
- For each image:
  - `title`
  - `source`
  - `page_url`
  - `image_url`
  - `used_url`
  - `local_path`
  - `mime`
  - `width`
  - `height`
  - `byte_size`
  - `score`
  - `reasons`

If image search fails, report failure reason and continue with text-search results when available.
