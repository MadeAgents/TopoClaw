---
name: ashare-investment
description: A-share (沪深) investment assistance using akshare_stock and akshare_stock_technical — fundamentals, technicals, sector context, and decision framing (not financial advice).
metadata: {"topoclaw":{"emoji":"📈"}}
---

# A 股投资辅助（ashare）

在已启用股票工具（`akshare`、`topoclaw-ai[stock]`）的前提下，用内置数据源辅助**信息整理与结构化分析**，帮助用户形成投资判断的**材料与检查清单**。你不是持牌投顾，输出必须**不构成买卖建议**，并明确提示风险。

## 工具接口（必读）

A 股数据与技术分析一律通过下列**已注册工具**完成；不要用 `exec` 或终端里自行 `import akshare` 绕开工具，除非用户明确要求调试脚本。

| 工具名 | 作用 | 要点 |
|--------|------|------|
| `akshare_stock` | 行情、基本面、新闻、财报等统一入口 | 必选 `endpoint`（仅下表所列）；其余参数见该工具的 `description` / JSON schema，且须与所选 `endpoint` 对应。 |
| `akshare_stock_technical` | 技术面（SMA/EMA/RSI/MACD/布林带/KDJ 等） | 必选 `symbol`（腾讯日 K：`sz`/`sh` 小写 + 6 位）；可选 `start_date`、`end_date`、`adjust`、`tail_rows`、`include_table`、`timeout`。 |

**`akshare_stock` 的 `endpoint` 仅允许下列取值（勿编造）：**

`stock_sse_summary`、`stock_szse_summary`、`stock_szse_sector_summary`、`stock_news_main_cx`、`stock_board_industry_summary_ths`、`stock_board_industry_index_ths`、`stock_individual_info_em`、`stock_individual_basic_info_xq`、`stock_individual_spot_xq`、`stock_zh_a_hist_tx`、`stock_intraday_em`、`stock_research_report_em`、`stock_financial_report_sina`、`stock_news_em`。

- 参数名与含义以工具 schema 为准（例如 `stock_financial_report_sina` 使用 `stock` + `report_type`）。
- 需要**原始日 K 表**时：使用 `akshare_stock`，`endpoint=stock_zh_a_hist_tx`；不要用 `akshare_stock_technical` 代替拉取原始 OHLC。

## 何时启用

用户提到 A 股/沪深、个股、板块、财报、估值、技术面、买卖点、持仓参考、行业对比等，且需要**可验证的数据**而非纯臆测时，按本 skill 组织调用顺序与说明方式。

## 代码与参数约定

- **查代码**：不确定 6 位代码或交易所时，用 `read_file` 读取工作区内的数据文件 `<data_file>stock_code.csv`（若存在），或请用户给出明确代码。
- **`akshare_stock`**：必选参数 `endpoint`，其余字段仅在与该 endpoint 说明一致时填写（见工具描述中的分节）。
  - 东财个股摘要 / 新闻 / 研报：`symbol` 多为 **6 位数字**（如 `600519`）。
  - 腾讯日 K `stock_zh_a_hist_tx`：`symbol` 为 **小写** `sz`/`sh` + 6 位（如 `sz000001`、`sh600519`）。
  - 雪球接口：`symbol` 为 **SH/SZ 大写前缀** + 6 位（如 `SH600000`、`SZ002594`）。
- **`akshare_stock_technical`**：必选 `symbol`（与腾讯日 K 相同规则）；可选 `start_date` / `end_date`（`YYYYMMDD`）、`adjust`、`tail_rows`、`include_table`、`timeout`。

## 辅助决策流程（建议顺序）

按用户需求裁剪步骤，不必每次全套；但**先定性、后定量、再综合**更清晰。

### 1. 环境与总貌（可选）

- 交易所/市场整体：`stock_sse_summary`、`stock_szse_summary`。
- 热点与情绪参考：`stock_news_main_cx`；个股新闻：`stock_news_em`。
- 行业与板块：先看一览 `stock_board_industry_summary_ths`，需要历史走势再用 `stock_board_industry_index_ths`（行业名、起止日期）；深交所行业成交可用 `stock_szse_sector_summary`。

### 2. 基本面（公司质地与财务）

- **公司资料**：`stock_individual_info_em`（东财摘要）；需要雪球口径时用 `stock_individual_basic_info_xq`（注意 SH/SZ 前缀格式）。
- **实时/快照**：`stock_individual_spot_xq`（雪球，格式同上）。
- **财务报表**：`stock_financial_report_sina`，`stock` 为 **小写** `sh600600` / `sz000001` 形式，`report_type` 为中文报表名（如「资产负债表」「利润表」「现金流量表」）。
- **研报列表**：`stock_research_report_em`（东财，6 位代码）。

归纳时区分：**事实**（报表科目、同比环比）与**推断**（行业地位、盈利质量），对缺失数据如实说明。

### 3. 技术面（价格与指标）

- 优先使用 **`akshare_stock_technical`**：基于腾讯日 K 输出 SMA、EMA、RSI、MACD、布林带、KDJ 等摘要表。
- 需要**原始日 K 表**或自定义窗口时，可用 `akshare_stock` 的 `stock_zh_a_hist_tx`。
- **日内分时**（通常最近一交易日）：`stock_intraday_em`（6 位代码）。

说明指标时写清：**周期、复权方式、数据区间**；避免把单次指标当作信号定论。

### 4. 综合与输出规范

- 用表格或分点对比：**结论只写「基于当前工具数据的观察」**，另起一节写**主要不确定性与需用户自行核实的事项**（公告、政策、持仓成本、风险承受等）。
- 明确写出：**数据来源为 akshare 各接口，存在延迟、复权口径差异、停牌等情形**。
- 若用户要求「买/卖」，回复中保持**中性**：可转述工具结果与常见分析维度，**不给出具体价位、仓位与保证收益类表述**。

## 工具速查

| 目的 | 工具 | 备注 |
|------|------|------|
| 市场总貌 | `akshare_stock` → `stock_sse_summary` / `stock_szse_summary` | 无额外参数或按说明传日期 |
| 行业板块 | `stock_board_industry_summary_ths`、`stock_board_industry_index_ths`、`stock_szse_sector_summary` | 行业名、日期格式见工具描述 |
| 个股资料/新闻/研报/财报 | `stock_individual_info_em`、`stock_news_em`、`stock_research_report_em`、`stock_financial_report_sina` 等 | 严格区分 6 位 / sh+sz 小写 / 雪球前缀 |
| 技术指标 | `akshare_stock_technical` | 与 `stock_zh_a_hist_tx` 同 symbol 规则 |
| 日 K 原始数据 | `stock_zh_a_hist_tx` | 在 `akshare_stock` 内调用 |
| 分时 | `stock_intraday_em` | 6 位代码 |

## 失败与降级

- 若返回空表、超时或 `ImportError`：说明可能原因（网络、代码格式、停牌、日期无成交），可换 endpoint（例如东财 ↔ 腾讯）或缩小日期范围重试；仍失败则如实告知，不编造数据。
