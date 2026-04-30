---
name: browser-automation
description: "Use the browser to search the web, log into websites, or extract live page content using spawn_browser_interactive_task."
metadata: {"topoclaw":{"emoji":"🌐"}}
---

# 浏览器自动化 (Browser Automation)

当用户要求你“打开网页操作”、“登录某个网站截图”、“查询商品比价”或“自动在网页上发帖”，且该任务需要真实的浏览器交互和复杂的页面渲染时，请启用此 Skill。

## 工具调用规范

你必须使用内置工具 `spawn_browser_interactive_task`。由于该工具是在后台启动一个异步真实的浏览器（browser-use），因此：

1. **精确描述任务目标** (`description`):
   - 描述必须极其具体，包含所有操作步骤和判断条件。
   - 示例: "Go to google.com, search for 'Topoclaw github', click the first result, and summarize the README content."
   - 示例: "Open https://x.com, log in using credentials from 1password if needed, search for AI news, and like the top 3 posts."

2. **必须携带原始用户意图** (`original_query`):
   - 必须原封不动地传入用户下达的原始需求，以便后台 Agent 执行完成后可以将结果与上下文对照。

3. **设置合理的超时时间** (`timeout_s`):
   - 默认是 600 秒（10 分钟）。如果预判任务非常长（比如多步数据采集），可以适当加大，但不超过 7200 秒。

4. **预留起始链接** (`start_url`):
   - 如果用户指定了某个特定的网站（例如淘宝、京东、某特定系统），务必提取该网站的根域名或全路径填入 `start_url`。不要省略 `https://` 前缀。

5. **不阻塞当前会话**:
   - `spawn_browser_interactive_task` 是异步执行的，调用后你只需告诉用户“已启动后台浏览器任务，请稍候”即可，不需要反复询问状态。

## 何时不需要使用此 Skill？
- 如果用户只是想获取某一篇已知的静态网页的文章内容，使用 `web_fetch` 更快更轻量。
- 如果用户只是想进行通用的知识查询，不需要点击、翻页、登录等交互，使用 `web_search` 或 `serper_search` 更好。