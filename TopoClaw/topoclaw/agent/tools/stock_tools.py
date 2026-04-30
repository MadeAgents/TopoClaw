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

"""Thin wrappers around akshare A-share data APIs. Requires optional dependency: akshare."""

from __future__ import annotations

import asyncio
from datetime import date, timedelta
from typing import Any

from loguru import logger

from topoclaw.agent.tools.base import Tool

# --- 总貌 ---
__all__ = [
    "StockAkshareTool",
    "StockTechnicalAnalysisTool",
    "stock_technical_analysis",
    "stock_sse_summary",
    "stock_szse_summary",
    "stock_szse_sector_summary",
    "stock_news_main_cx",
    "stock_board_industry_summary_ths",
    "stock_board_industry_index_ths",
    "stock_individual_info_em",
    "stock_individual_basic_info_xq",
    "stock_individual_spot_xq",
    "stock_zh_a_hist_tx",
    "stock_intraday_em",
    "stock_research_report_em",
    "stock_financial_report_sina",
    "stock_news_em",
    "dataframe_to_text",
]

_AKSHARE_ENDPOINTS: frozenset[str] = frozenset(
    {
        "stock_sse_summary",
        "stock_szse_summary",
        "stock_szse_sector_summary",
        "stock_news_main_cx",
        "stock_board_industry_summary_ths",
        "stock_board_industry_index_ths",
        "stock_individual_info_em",
        "stock_individual_basic_info_xq",
        "stock_individual_spot_xq",
        "stock_zh_a_hist_tx",
        "stock_intraday_em",
        "stock_research_report_em",
        "stock_financial_report_sina",
        "stock_news_em",
    }
)

_AK: Any = None


def _ak() -> Any:
    global _AK
    if _AK is None:
        try:
            import akshare as ak  # type: ignore[import-untyped]
        except ImportError as e:
            raise ImportError(
                "akshare is required for stock_tools. Install: pip install akshare "
                "or pip install topoclaw-ai[stock]"
            ) from e
        _AK = ak
    return _AK


def dataframe_to_text(df: Any, *, max_rows: int = 500) -> str:
    """Render a pandas DataFrame to plain text; truncate long results."""
    if df is None:
        return "(no data)"
    if getattr(df, "empty", False):
        return "(empty)"
    n = len(df)
    if n > max_rows:
        out = df.head(max_rows).to_string(index=False)
        return f"{out}\n\n... ({max_rows} of {n} rows shown)"
    return df.to_string(index=False)


def _df(df: Any) -> str:
    return dataframe_to_text(df)


# ----- 获取总貌 -----


def stock_sse_summary() -> str:
    """上交所市场总貌。"""
    return _df(_ak().stock_sse_summary())


def stock_szse_summary(date: str | None = None) -> str:
    """深交所市场总貌。date: YYYYMMDD，省略则使用 akshare 默认日期。"""
    ak = _ak()
    if date is None:
        return _df(ak.stock_szse_summary())
    return _df(ak.stock_szse_summary(date=date))


def stock_szse_sector_summary(symbol: str = "当年", date: str = "202501") -> str:
    """深交所行业成交汇总。symbol 如 当年/当月 等，date 如 202501（年月）。"""
    return _df(_ak().stock_szse_sector_summary(symbol=symbol, date=date))


def stock_news_main_cx() -> str:
    """市场财经热点（财讯）。"""
    return _df(_ak().stock_news_main_cx())


# ----- 获取板块信息 -----


def stock_board_industry_summary_ths() -> str:
    """同花顺实时行业一览。"""
    return _df(_ak().stock_board_industry_summary_ths())


def stock_board_industry_index_ths(
    symbol: str = "元件",
    start_date: str = "20240101",
    end_date: str = "20240718",
) -> str:
    """同花顺某行业板块历史指数行情。"""
    return _df(
        _ak().stock_board_industry_index_ths(
            symbol=symbol,
            start_date=start_date,
            end_date=end_date,
        )
    )


# ----- 个股：信息 / 行情 -----


def stock_individual_info_em(symbol: str = "000001", *, timeout: float | None = None) -> str:
    """东方财富个股资料摘要。"""
    ak = _ak()
    if timeout is None:
        return _df(ak.stock_individual_info_em(symbol=symbol))
    return _df(ak.stock_individual_info_em(symbol=symbol, timeout=timeout))


def stock_individual_basic_info_xq(
    symbol: str = "SH601127",
    *,
    token: str | None = None,
    timeout: float | None = None,
) -> str:
    """雪球个股基本信息。"""
    ak = _ak()
    kwargs: dict[str, Any] = {"symbol": symbol}
    if token is not None:
        kwargs["token"] = token
    if timeout is not None:
        kwargs["timeout"] = timeout
    return _df(ak.stock_individual_basic_info_xq(**kwargs))


def stock_individual_spot_xq(
    symbol: str = "SH600000",
    *,
    token: str | None = None,
    timeout: float | None = None,
) -> str:
    """雪球个股实时行情（akshare 中雪球支持个股实时）。"""
    ak = _ak()
    kwargs: dict[str, Any] = {"symbol": symbol}
    if token is not None:
        kwargs["token"] = token
    if timeout is not None:
        kwargs["timeout"] = timeout
    return _df(ak.stock_individual_spot_xq(**kwargs))


def stock_zh_a_hist_tx(
    symbol: str = "sz000001",
    start_date: str = "20200101",
    end_date: str = "20231027",
    adjust: str = "",
    *,
    timeout: float | None = None,
) -> str:
    """腾讯财经 A 股日 K 历史行情。symbol 如 sz000001 / sh600000。"""
    ak = _ak()
    kwargs: dict[str, Any] = {
        "symbol": symbol,
        "start_date": start_date,
        "end_date": end_date,
        "adjust": adjust,
    }
    if timeout is not None:
        kwargs["timeout"] = timeout
    return _df(ak.stock_zh_a_hist_tx(**kwargs))


def stock_intraday_em(symbol: str = "000001") -> str:
    """东方财富日内分时（通常为最近一个交易日）。"""
    return _df(_ak().stock_intraday_em(symbol=symbol))


# ----- 个股：研报 / 财报 / 新闻 -----


def stock_research_report_em(symbol: str = "000001") -> str:
    """东方财富个股研报列表。"""
    return _df(_ak().stock_research_report_em(symbol=symbol))


def stock_financial_report_sina(stock: str = "sh600600", symbol: str = "资产负债表") -> str:
    """新浪财经个股财务报表（资产负债表/利润表/现金流量表等）。"""
    return _df(_ak().stock_financial_report_sina(stock=stock, symbol=symbol))


def stock_news_em(symbol: str = "603777") -> str:
    """东方财富个股新闻。"""
    return _df(_ak().stock_news_em(symbol=symbol))


# ----- 技术分析（基于腾讯日 K + pandas） -----


def _pd():
    try:
        import pandas as pd  # type: ignore[import-untyped]
    except ImportError as e:
        raise ImportError(
            "pandas is required for technical analysis (install akshare / topoclaw-ai[stock])"
        ) from e
    return pd


def _coerce_yyyymmdd(value: Any, *, fallback: str) -> str:
    """Normalize to YYYYMMDD; akshare rejects garbage (e.g. 'i') which can cause int() parse errors."""
    if value is None:
        return fallback
    s = str(value).strip()
    if not s:
        return fallback
    digits = "".join(c for c in s if c.isdigit())
    if len(digits) >= 8:
        return digits[:8]
    return fallback


def _safe_tail_rows(value: Any) -> int:
    """5–120 table rows; tolerate str/float/invalid from LLM or cast_params."""
    try:
        if value is None:
            return 20
        tr = int(float(value))
        return max(5, min(tr, 120))
    except (TypeError, ValueError):
        return 20


def _normalize_ohlc_df(df: Any) -> Any:
    """Normalize akshare OHLC column names to: date, open, high, low, close, volume."""
    pd = _pd()
    if df is None or getattr(df, "empty", True):
        return pd.DataFrame()

    def _first_existing(candidates: tuple[str, ...]) -> str | None:
        for c in candidates:
            if c in df.columns:
                return c
        return None

    col_date = _first_existing(("date", "日期", "day"))
    col_open = _first_existing(("open", "开盘"))
    col_high = _first_existing(("high", "最高"))
    col_low = _first_existing(("low", "最低"))
    col_close = _first_existing(("close", "收盘"))
    col_vol = _first_existing(("volume", "成交量", "amount", "成交额", "vol"))

    if not all((col_date, col_open, col_high, col_low, col_close)):
        raise ValueError(
            f"Unexpected OHLC columns: {list(df.columns)}; "
            "expected date/日期, open/开盘, high/最高, low/最低, close/收盘"
        )

    out = pd.DataFrame(
        {
            "date": pd.to_datetime(df[col_date], errors="coerce").dt.strftime("%Y-%m-%d"),
            "open": pd.to_numeric(df[col_open], errors="coerce"),
            "high": pd.to_numeric(df[col_high], errors="coerce"),
            "low": pd.to_numeric(df[col_low], errors="coerce"),
            "close": pd.to_numeric(df[col_close], errors="coerce"),
        }
    )
    if col_vol:
        out["volume"] = pd.to_numeric(df[col_vol], errors="coerce")
    out = out.dropna(subset=["open", "high", "low", "close"]).sort_values("date").reset_index(drop=True)
    return out


def _rsi_wilder(close: Any, period: int = 14) -> Any:
    """Relative Strength Index (Wilder / EWM)."""
    pd = _pd()
    delta = close.diff()
    gain = delta.clip(lower=0.0)
    loss = (-delta).clip(lower=0.0)
    avg_gain = gain.ewm(alpha=1.0 / period, min_periods=period, adjust=False).mean()
    avg_loss = loss.ewm(alpha=1.0 / period, min_periods=period, adjust=False).mean()
    rs = avg_gain / avg_loss  # loss=0 & gain>0 → inf → RSI→100
    return 100 - (100 / (1 + rs))


def _macd(close: Any, fast: int = 12, slow: int = 26, signal: int = 9) -> tuple[Any, Any, Any]:
    pd = _pd()
    ema_fast = close.ewm(span=fast, adjust=False).mean()
    ema_slow = close.ewm(span=slow, adjust=False).mean()
    dif = ema_fast - ema_slow
    dea = dif.ewm(span=signal, adjust=False).mean()
    hist = dif - dea
    return dif, dea, hist


def _kdj(high: Any, low: Any, close: Any, n: int = 9) -> tuple[Any, Any, Any]:
    """KDJ: RSV 后接 K/D 的 3 期滚动均值，J=3K-2D（与常见行情软件近似，非唯一标准）。"""
    pd = _pd()
    llv = low.rolling(n, min_periods=n).min()
    hhv = high.rolling(n, min_periods=n).max()
    denom = (hhv - llv).replace(0, pd.NA)
    rsv = (close - llv) / denom * 100.0
    k = rsv.rolling(3, min_periods=1).mean()
    d = k.rolling(3, min_periods=1).mean()
    j = 3.0 * k - 2.0 * d
    return k, d, j


def _attach_indicators(work: Any) -> Any:
    """Add SMA/EMA/RSI/MACD/BOLL/KDJ columns to ascending OHLC dataframe."""
    pd = _pd()
    c = work["close"]
    h, lo = work["high"], work["low"]

    for p in (5, 10, 20, 60):
        work[f"SMA{p}"] = c.rolling(p, min_periods=p).mean()
    work["EMA12"] = c.ewm(span=12, adjust=False).mean()
    work["EMA26"] = c.ewm(span=26, adjust=False).mean()
    work["RSI14"] = _rsi_wilder(c, 14)
    dif, dea, mhist = _macd(c)
    work["MACD_DIF"] = dif
    work["MACD_DEA"] = dea
    work["MACD_HIST"] = mhist

    mid = c.rolling(20, min_periods=20).mean()
    std = c.rolling(20, min_periods=20).std()
    work["BOLL_MID"] = mid
    work["BOLL_UP"] = mid + 2.0 * std
    work["BOLL_LOW"] = mid - 2.0 * std

    kv, dv, jv = _kdj(h, lo, c, 9)
    work["KDJ_K"] = kv
    work["KDJ_D"] = dv
    work["KDJ_J"] = jv
    return work


def stock_technical_analysis(
    symbol: str = "sz000001",
    start_date: str | None = None,
    end_date: str | None = None,
    adjust: str = "",
    *,
    tail_rows: int = 20,
    timeout: float | None = None,
    include_table: bool = True,
) -> str:
    """
    拉取腾讯日 K 并计算常用技术指标：SMA5/10/20/60、EMA12/26、RSI(14)、MACD、布林带(20,2)、KDJ(9)。

    symbol：小写 sz/sh+6 位；start_date/end_date：YYYYMMDD，省略则默认约近两年区间。
    """
    pd = _pd()
    ak = _ak()
    end_default = date.today().strftime("%Y%m%d")
    start_default = (date.today() - timedelta(days=800)).strftime("%Y%m%d")
    end_date = _coerce_yyyymmdd(end_date, fallback=end_default)
    start_date = _coerce_yyyymmdd(start_date, fallback=start_default)
    if start_date > end_date:
        start_date, end_date = end_date, start_date

    sym = (symbol or "").strip().lower()
    if not sym:
        return "(symbol is required)"

    adj = str(adjust).strip() if adjust is not None else ""
    kwargs: dict[str, Any] = {
        "symbol": sym,
        "start_date": start_date,
        "end_date": end_date,
        "adjust": adj,
    }
    if timeout is not None:
        kwargs["timeout"] = timeout

    raw = ak.stock_zh_a_hist_tx(**kwargs)
    work = _normalize_ohlc_df(raw)
    if work.empty:
        return "(no OHLC data; check symbol / date range / network)"

    work = _attach_indicators(work)
    last = work.iloc[-1]
    last_date = last["date"]
    tail_n = _safe_tail_rows(tail_rows)

    def _num(x: Any, *, fmt: str = ".4g") -> str:
        if x is None or (isinstance(x, float) and (x != x)):  # NaN
            return "n/a"
        try:
            if pd.isna(x):
                return "n/a"
        except TypeError:
            pass
        try:
            return format(float(x), fmt)
        except (TypeError, ValueError):
            return str(x)

    lines: list[str] = [
        f"=== Technical analysis: {sym} ({work['date'].iloc[0]} ~ {work['date'].iloc[-1]}, n={len(work)}) ===",
        f"--- Latest bar {last_date} ---",
        f"OHLC: O={_num(last['open'])} H={_num(last['high'])} L={_num(last['low'])} C={_num(last['close'])}",
    ]
    if "volume" in work.columns and pd.notna(last["volume"]):
        lines.append(f"Volume: {_num(last['volume'])}")

    lines.extend(
        [
            f"SMA: 5={_num(last['SMA5'])} 10={_num(last['SMA10'])} 20={_num(last['SMA20'])} 60={_num(last['SMA60'])}",
            f"EMA12={_num(last['EMA12'])} EMA26={_num(last['EMA26'])}",
            f"RSI(14)={_num(last['RSI14'])}",
            f"MACD: DIF={_num(last['MACD_DIF'])} DEA={_num(last['MACD_DEA'])} Hist={_num(last['MACD_HIST'])}",
            f"BOLL(20,2): upper={_num(last['BOLL_UP'])} mid={_num(last['BOLL_MID'])} lower={_num(last['BOLL_LOW'])}",
            f"KDJ(9): K={_num(last['KDJ_K'])} D={_num(last['KDJ_D'])} J={_num(last['KDJ_J'])}",
        ]
    )

    # Short positioning hint (not investment advice)
    try:
        c = float(last["close"])
    except (TypeError, ValueError):
        c = float("nan")
    if not pd.isna(last["BOLL_UP"]) and not pd.isna(last["BOLL_LOW"]) and c == c:
        if c >= float(last["BOLL_UP"]):
            lines.append("Note: close at/above upper Bollinger band (strong; watch mean reversion).")
        elif c <= float(last["BOLL_LOW"]):
            lines.append("Note: close at/below lower Bollinger band (weak; possible oversold).")
    if not pd.isna(last["RSI14"]):
        r = float(last["RSI14"])
        if r >= 70:
            lines.append("Note: RSI >= 70 (overbought zone).")
        elif r <= 30:
            lines.append("Note: RSI <= 30 (oversold zone).")

    if include_table:
        tail = tail_n
        cols = [
            "date",
            "close",
            "SMA5",
            "SMA20",
            "SMA60",
            "RSI14",
            "MACD_DIF",
            "MACD_DEA",
            "MACD_HIST",
            "BOLL_UP",
            "BOLL_LOW",
            "KDJ_K",
            "KDJ_D",
        ]
        use = [c for c in cols if c in work.columns]
        sub = work[use].tail(tail)
        lines.append("")
        lines.append(f"--- Last {len(sub)} rows ---")
        lines.append(dataframe_to_text(sub, max_rows=tail))

    return "\n".join(lines)


class StockTechnicalAnalysisTool(Tool):
    """基于腾讯日 K 的技术指标（SMA/EMA/RSI/MACD/布林带/KDJ）。"""

    name = "akshare_stock_technical"
    description = """A 股技术分析：拉取腾讯财经日 K（与 akshare_stock 的 stock_zh_a_hist_tx 相同 symbol 规则），
计算 SMA5/10/20/60、EMA12/26、RSI(14)、MACD(12,26,9)、布林带(20,2)、KDJ(9)。
必选：symbol（小写 sz000001 / sh600519）。可选：start_date、end_date（YYYYMMDD，省略则默认约近两年）、
adjust（复权；空字符串为不复权）、tail_rows（尾部表格行数，默认 20）、include_table（是否输出近期指标表，默认 true）、
timeout（秒）。输出含最新一根 K 的指标摘要与可选历史表。非投资建议。"""

    parameters = {
        "type": "object",
        "properties": {
            "symbol": {
                "type": "string",
                "description": "腾讯日 K 代码：小写 sz/sh + 6 位，如 sz000001、sh600519。",
            },
            "start_date": {
                "type": "string",
                "description": "开始日期 YYYYMMDD；省略则自动取约两年前。",
            },
            "end_date": {
                "type": "string",
                "description": "结束日期 YYYYMMDD；省略则使用今天。",
            },
            "adjust": {
                "type": "string",
                "description": "复权参数；空字符串为不复权（与 akshare 文档一致，如 q 前复权）。",
            },
            "tail_rows": {
                "type": "integer",
                "description": "尾部输出表格行数（5~120），默认 20。",
            },
            "include_table": {
                "type": "boolean",
                "description": "是否附带近期指标表，默认 true。",
            },
            "timeout": {
                "type": "number",
                "description": "拉取日 K 的超时秒数，可选。",
            },
        },
        "required": ["symbol"],
    }

    async def execute(self, symbol: str, **kwargs: Any) -> str:
        tr = _safe_tail_rows(kwargs.get("tail_rows"))
        inc = kwargs.get("include_table", True)
        if isinstance(inc, str):
            inc = inc.strip().lower() in ("1", "true", "yes", "y", "on")

        try:
            return await asyncio.to_thread(
                stock_technical_analysis,
                symbol,
                kwargs.get("start_date"),
                kwargs.get("end_date"),
                kwargs.get("adjust") if kwargs.get("adjust") is not None else "",
                tail_rows=tr,
                timeout=_opt_float(kwargs.get("timeout")),
                include_table=bool(inc),
            )
        except ImportError as e:
            return (
                f"Error: {e}. Install: pip install akshare or pip install topoclaw-ai[stock], "
                "and ensure pandas is available."
            )
        except Exception as e:
            logger.exception("akshare_stock_technical symbol={}", symbol)
            return f"Error: {e}"


def _clean_str(v: Any) -> str | None:
    if v is None:
        return None
    s = str(v).strip()
    return s if s else None


def _opt_float(v: Any) -> float | None:
    if v is None or v == "":
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def dispatch_stock_endpoint(endpoint: str, params: dict[str, Any]) -> str:
    """
    Map ``endpoint`` + flat ``params`` to the thin wrappers above.
    Used by :class:`StockAkshareTool` and tests.
    """
    ep = (endpoint or "").strip()
    if ep not in _AKSHARE_ENDPOINTS:
        return f"Error: unknown endpoint '{endpoint}'. Valid: {', '.join(sorted(_AKSHARE_ENDPOINTS))}"

    raw = {k: v for k, v in params.items() if k != "endpoint"}

    match ep:
        case "stock_sse_summary":
            return stock_sse_summary()
        case "stock_szse_summary":
            td = _clean_str(raw.get("trade_date"))
            return stock_szse_summary(None if td is None else td)
        case "stock_szse_sector_summary":
            sym = _clean_str(raw.get("sector_symbol")) or "当年"
            per = _clean_str(raw.get("period")) or "202501"
            return stock_szse_sector_summary(symbol=sym, date=per)
        case "stock_news_main_cx":
            return stock_news_main_cx()
        case "stock_board_industry_summary_ths":
            return stock_board_industry_summary_ths()
        case "stock_board_industry_index_ths":
            sym = _clean_str(raw.get("symbol")) or "元件"
            sd = _clean_str(raw.get("start_date")) or "20240101"
            ed = _clean_str(raw.get("end_date")) or "20240718"
            return stock_board_industry_index_ths(symbol=sym, start_date=sd, end_date=ed)
        case "stock_individual_info_em":
            sym = _clean_str(raw.get("symbol")) or "000001"
            to = _opt_float(raw.get("timeout"))
            if to is None:
                return stock_individual_info_em(symbol=sym)
            return stock_individual_info_em(symbol=sym, timeout=to)
        case "stock_individual_basic_info_xq":
            sym = _clean_str(raw.get("symbol")) or "SH601127"
            tok = _clean_str(raw.get("token"))
            to = _opt_float(raw.get("timeout"))
            return stock_individual_basic_info_xq(
                symbol=sym,
                token=tok,
                timeout=to,
            )
        case "stock_individual_spot_xq":
            sym = _clean_str(raw.get("symbol")) or "SH600000"
            tok = _clean_str(raw.get("token"))
            to = _opt_float(raw.get("timeout"))
            return stock_individual_spot_xq(
                symbol=sym,
                token=tok,
                timeout=to,
            )
        case "stock_zh_a_hist_tx":
            sym = _clean_str(raw.get("symbol")) or "sz000001"
            sd = _clean_str(raw.get("start_date")) or "20200101"
            ed = _clean_str(raw.get("end_date")) or "20231027"
            adj = _clean_str(raw.get("adjust"))
            adj = adj if adj is not None else ""
            to = _opt_float(raw.get("timeout"))
            return stock_zh_a_hist_tx(
                symbol=sym,
                start_date=sd,
                end_date=ed,
                adjust=adj,
                timeout=to,
            )
        case "stock_intraday_em":
            sym = _clean_str(raw.get("symbol")) or "000001"
            return stock_intraday_em(symbol=sym)
        case "stock_research_report_em":
            sym = _clean_str(raw.get("symbol")) or "000001"
            return stock_research_report_em(symbol=sym)
        case "stock_financial_report_sina":
            st = _clean_str(raw.get("stock")) or "sh600600"
            rt = _clean_str(raw.get("report_type")) or "资产负债表"
            return stock_financial_report_sina(stock=st, symbol=rt)
        case "stock_news_em":
            sym = _clean_str(raw.get("symbol")) or "603777"
            return stock_news_em(symbol=sym)
    return f"Error: unhandled endpoint '{ep}'"


class StockAkshareTool(Tool):
    """Agent tool: A股/市场数据，经 akshare 拉取表格化文本。"""

    name = "akshare_stock"
    description = """A股与市场数据（akshare）。必选参数：endpoint。其余字段仅在与下表对应时使用；无关字段会被忽略。

【无额外参数】勿传 symbol/trade_date 等（传了也会忽略）：
• stock_sse_summary — 上交所市场总貌
• stock_news_main_cx — 市场财经热点
• stock_board_industry_summary_ths — 同花顺实时行业一览

【stock_szse_summary】深交所总貌。可选 trade_date：YYYYMMDD（如 20240830）；不传则用数据源默认日期。

【stock_szse_sector_summary】深交所行业成交。sector_symbol：如「当年」「当月」等；period：YYYYMM（如 202501）。缺省：当年、202501。

【stock_board_industry_index_ths】同花顺行业指数历史。symbol：行业板块名称（如「元件」）；start_date、end_date：YYYYMMDD。缺省：元件、20240101、20240718。

【stock_individual_info_em】东方财富个股摘要。symbol：仅 6 位数字代码（如 000001、600519），不要加 SH/SZ。可选 timeout（秒）。

【stock_individual_basic_info_xq】【stock_individual_spot_xq】雪球个股信息/实时行情。symbol：必须带交易所前缀 — 沪市 SH+6 位（如 SH600000），深市 SZ+6 位（如 SZ002594）；禁止只传 6 位数字（会导致无数据或报错）。可选 token、timeout。

【stock_zh_a_hist_tx】腾讯 A 股日 K。symbol：小写市场前缀+6 位，如 sz000001、sh600519；start_date、end_date：YYYYMMDD；adjust：复权，空字符串为不复权（常见 q 前复权、hfq 后复权，以 akshare 文档为准）。可选 timeout。

【stock_intraday_em】东财日内分时（通常最近一交易日）。symbol：6 位数字代码（如 000001）。缺省 000001。

【stock_research_report_em】东财个股研报列表。symbol：6 位数字代码。缺省 000001。

【stock_financial_report_sina】新浪财务报表。stock：小写 sh/sz+6 位（如 sh600600）；report_type：中文报表名，如「资产负债表」「利润表」「现金流量表」。

【stock_news_em】东财个股新闻。symbol：6 位数字代码。缺省 603777。

【通用】token/timeout 仅对上述雪球两个 endpoint 有意义；timeout 还对 stock_individual_info_em、stock_zh_a_hist_tx 有效。"""
    parameters = {
        "type": "object",
        "properties": {
            "endpoint": {
                "type": "string",
                "enum": sorted(_AKSHARE_ENDPOINTS),
                "description": "要调用的 akshare 函数名，须与下述各条入参规则一致。",
            },
            "trade_date": {
                "type": "string",
                "description": "仅 stock_szse_summary：YYYYMMDD。",
            },
            "sector_symbol": {
                "type": "string",
                "description": "仅 stock_szse_sector_summary：如 当年、当月。",
            },
            "period": {
                "type": "string",
                "description": "仅 stock_szse_sector_summary：YYYYMM。",
            },
            "symbol": {
                "type": "string",
                "description": (
                    "含义随 endpoint："
                    "stock_board_industry_index_ths=行业名；"
                    "stock_individual_info_em / stock_intraday_em / stock_research_report_em / stock_news_em=6位数字；"
                    "stock_individual_basic_info_xq / stock_individual_spot_xq=雪球 SH/SZ+6位；"
                    "stock_zh_a_hist_tx=小写 sz/sh+6位。"
                ),
            },
            "start_date": {
                "type": "string",
                "description": "stock_board_industry_index_ths、stock_zh_a_hist_tx：YYYYMMDD。",
            },
            "end_date": {
                "type": "string",
                "description": "stock_board_industry_index_ths、stock_zh_a_hist_tx：YYYYMMDD。",
            },
            "adjust": {
                "type": "string",
                "description": "仅 stock_zh_a_hist_tx：复权参数；空字符串表示不复权。",
            },
            "stock": {
                "type": "string",
                "description": "仅 stock_financial_report_sina：小写 sh600600 / sz000001 形式。",
            },
            "report_type": {
                "type": "string",
                "description": "仅 stock_financial_report_sina：报表中文名，如 资产负债表。",
            },
            "token": {
                "type": "string",
                "description": "仅雪球 endpoint（basic_info_xq、spot_xq）：可选。",
            },
            "timeout": {
                "type": "number",
                "description": "秒；用于 stock_individual_info_em、雪球两接口、stock_zh_a_hist_tx。",
            },
        },
        "required": ["endpoint"],
    }

    async def execute(self, endpoint: str, **kwargs: Any) -> str:
        try:
            return await asyncio.to_thread(dispatch_stock_endpoint, endpoint, {"endpoint": endpoint, **kwargs})
        except ImportError as e:
            return (
                f"Error: {e}. Install: pip install akshare or pip install topoclaw-ai[stock], "
                "and set tools.stock.enabled in config."
            )
        except Exception as e:
            logger.exception("akshare_stock endpoint={}", endpoint)
            return f"Error: {e}"
