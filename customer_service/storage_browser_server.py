#!/usr/bin/env python3
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

"""
本地静态页 + 只读 API，浏览本包 outputs/ 下的 JSON / jsonl。
用法（在 customer_service2 目录）:
  python storage_browser_server.py
  python storage_browser_server.py --port 8765
  python storage_browser_server.py --host 127.0.0.1   # 仅本机可访问
默认监听 0.0.0.0，本机用 127.0.0.1，其它设备用本机局域网 IPv4；勿用 file:// 打开 HTML。

与 batch_queries_web 一致：支持 /v6/ 入口；/v6/api/... 与 /api/... 等价（网关常保留 /v6 前缀）。
"""
from __future__ import annotations

import argparse
import datetime
import json
import mimetypes
import os
import re
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from imei_search import search_imei as _search_imei_fragments

ALLOWED_BUCKETS = frozenset(
    {
        "multi_device",
        "friends",
        "profiles",
        "unified_messages",
        "groups",
        "sessions",
        "custom_assistants",
        "plaza",
        "customer_service",
        "version",
    }
)

MAX_JSON_BYTES = 8 * 1024 * 1024
MAX_JSONL_LINES_PER_REQUEST = 300
MAX_JSONL_SCAN_LINES = 200_000
MAX_RECENT_TERMINAL_ENTRIES = 2500
MAX_RECENT_TERMINAL_READ_BYTES = 8 * 1024 * 1024
TERMINAL_LOG_BASENAME = "app_terminal.log"
_LOG_LINE_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) \| ([A-Z]+) \| ([^|]+) \| (.*)$"
)


def _package_root() -> Path:
    return Path(__file__).resolve().parent


def _outputs_root() -> Path:
    return _package_root() / "outputs"


def _safe_bucket_file(bucket: str, name: str) -> Path | None:
    if bucket not in ALLOWED_BUCKETS or not name or name.startswith(".") or "/" in name or "\\" in name:
        return None
    if ".." in name or not re.fullmatch(r"[\w\-.]+\.(json|jsonl)", name, re.I):
        return None
    p = (_outputs_root() / bucket / name).resolve()
    try:
        p.relative_to(_outputs_root().resolve())
    except ValueError:
        return None
    return p


def _all_profile_nicknames() -> dict[str, str]:
    """profiles_storage 中所有 IMEI 键对应的展示名（供好友列表等片段里任意 IMEI 显示昵称）。"""
    out: dict[str, str] = {}
    path = _outputs_root() / "profiles" / "profiles_storage.json"
    if not path.is_file():
        return out
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return out
    if not isinstance(raw, dict):
        return out
    for im, prof in raw.items():
        if not isinstance(im, str) or not isinstance(prof, dict):
            continue
        n = prof.get("name") or prof.get("nickname")
        if n is None:
            continue
        s = str(n).strip()
        if s:
            out[im] = s
    return out


def _terminal_log_base_path() -> Path:
    return _outputs_root() / "server_logs" / TERMINAL_LOG_BASENAME


def _ordered_rotated_terminal_logs(base: Path) -> list[Path]:
    """RotatingFileHandler: app_terminal.log.5 … .1 为更旧片段，无后缀为当前文件。"""
    parent = base.parent
    if not parent.is_dir():
        return []
    name = base.name
    out: list[Path] = []
    for i in range(5, 0, -1):
        p = parent / f"{name}.{i}"
        if p.is_file():
            out.append(p)
    if base.is_file():
        out.append(base)
    return out


def _read_text_tail(path: Path, max_bytes: int) -> str:
    try:
        sz = path.stat().st_size
    except OSError:
        return ""
    try:
        with path.open("rb") as f:
            if sz <= max_bytes:
                data = f.read()
            else:
                f.seek(-max_bytes, os.SEEK_END)
                data = f.read()
    except OSError:
        return ""
    text = data.decode("utf-8", errors="replace")
    if sz > max_bytes and "\n" in text:
        text = text.split("\n", 1)[-1]
    return text


def _parse_terminal_log_line_ts(line: str) -> int | None:
    m = _LOG_LINE_RE.match(line.rstrip("\r"))
    if not m:
        return None
    try:
        dt = datetime.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")
        return int(dt.timestamp() * 1000)
    except (OSError, ValueError, OverflowError):
        return None


def _parse_terminal_log_lines(
    lines: list[str], from_ms: int, to_ms: int, source_name: str
) -> list[dict]:
    entries: list[dict] = []
    last_included: int | None = None
    for line_no, line in enumerate(lines, start=1):
        m = _LOG_LINE_RE.match(line.rstrip("\r"))
        if m:
            ts_ms = _parse_terminal_log_line_ts(line)
            if ts_ms is None:
                last_included = None
                continue
            rec = {
                "timestamp": ts_ms,
                "level": m.group(2).strip(),
                "logger": m.group(3).strip(),
                "message": m.group(4),
                "lineNo": line_no,
                "sourceFile": source_name,
                "raw": line,
            }
            if from_ms <= ts_ms <= to_ms:
                entries.append(rec)
                last_included = len(entries) - 1
            else:
                last_included = None
        elif last_included is not None:
            hit = entries[last_included]
            hit["message"] += "\n" + line
            hit["raw"] += "\n" + line
    return entries


def _collect_recent_server_terminal_logs(from_ms: int, to_ms: int) -> tuple[list[dict], bool, int, str]:
    """
    读取 outputs/server_logs/app_terminal.log*（与 python app.py 写入格式一致），
    返回时间窗内日志行，按时间降序。
    """
    base = _terminal_log_base_path()
    try:
        rel_log = str(base.relative_to(_package_root()))
    except ValueError:
        rel_log = "outputs/server_logs/app_terminal.log"

    paths = _ordered_rotated_terminal_logs(base)
    if not paths:
        return [], False, 0, rel_log

    truncated = False
    merged: list[dict] = []
    per_cap = max(512 * 1024, MAX_RECENT_TERMINAL_READ_BYTES // max(1, len(paths)))

    for p in paths:
        try:
            sz = p.stat().st_size
        except OSError:
            continue
        if sz > per_cap:
            truncated = True
        chunk = _read_text_tail(p, per_cap)
        lines = [ln for ln in chunk.splitlines() if ln]
        merged.extend(_parse_terminal_log_lines(lines, from_ms, to_ms, p.name))

    merged.sort(key=lambda x: x["timestamp"], reverse=True)
    if len(merged) > MAX_RECENT_TERMINAL_ENTRIES:
        merged = merged[:MAX_RECENT_TERMINAL_ENTRIES]
        truncated = True

    return merged, truncated, len(paths), rel_log


def _normalize_request_path(path: str) -> str:
    """将 /v6 前缀下的 API、静态资源映射到与根路径相同的路由（对齐 batch_queries_web 的 /v6 约定）。"""
    if path.startswith("/v6/api/") or path == "/v6/api":
        return path[4:]  # /v6/api/health -> /api/health
    if path.startswith("/v6/storage_browser/"):
        return path[4:]
    if path == "/v6/favicon.ico":
        return "/favicon.ico"
    return path


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - - [%s] %s\n" % (self.address_string(), self.log_date_time_string(), fmt % args))

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code: int, obj: object) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._send(code, data, "application/json; charset=utf-8")

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        raw_path = parsed.path
        path = _normalize_request_path(raw_path)
        qs = urllib.parse.parse_qs(parsed.query)

        if raw_path == "/v6/health":
            root = _outputs_root()
            self._json(
                200,
                {
                    "status": "healthy",
                    "service": "storage_browser",
                    "outputs": str(root),
                    "exists": root.is_dir(),
                },
            )
            return

        if path in ("/favicon.ico",):
            self.send_response(204)
            self.end_headers()
            return

        if path == "/api/health":
            root = _outputs_root()
            self._json(200, {"ok": True, "outputs": str(root), "exists": root.is_dir()})
            return

        if path == "/api/buckets":
            root = _outputs_root()
            if not root.is_dir():
                self._json(200, {"buckets": [], "error": "outputs 目录不存在"})
                return
            out = []
            for d in sorted(root.iterdir()):
                if not d.is_dir():
                    continue
                if d.name not in ALLOWED_BUCKETS:
                    continue
                files = []
                for f in sorted(d.iterdir()):
                    if f.is_file() and not f.name.startswith("."):
                        st = f.stat()
                        files.append(
                            {
                                "name": f.name,
                                "size": st.st_size,
                                "mtime": int(st.st_mtime * 1000),
                            }
                        )
                out.append({"name": d.name, "files": files})
            self._json(200, {"buckets": out})
            return

        if path == "/api/jsonl-imeis":
            root = _outputs_root() / "customer_service"
            if not root.is_dir():
                self._json(200, {"imeis": [], "nicknames": _all_profile_nicknames()})
                return
            imeis = sorted(p.stem for p in root.glob("*.jsonl") if p.is_file())
            nicknames = dict(_all_profile_nicknames())
            for im in imeis:
                nicknames.setdefault(im, "")
            self._json(200, {"imeis": imeis, "nicknames": nicknames})
            return

        if path == "/api/search-imei":
            imei_q = (qs.get("imei") or [""])[0]
            ok, err_msg, hits = _search_imei_fragments(imei_q, _outputs_root())
            if not ok:
                self._json(400, {"error": err_msg, "imei": (imei_q or "").strip(), "hits": []})
                return
            self._json(200, {"imei": (imei_q or "").strip(), "hits": hits})
            return

        if path == "/api/recent-terminal-logs":
            try:
                hours = float((qs.get("hours") or ["1"])[0])
            except ValueError:
                hours = 1.0
            hours = max(0.05, min(hours, 168.0))
            now_ms = int(time.time() * 1000)
            from_ms = now_ms - int(hours * 3600 * 1000)
            entries, truncated, files_scanned, log_rel = _collect_recent_server_terminal_logs(from_ms, now_ms)
            self._json(
                200,
                {
                    "fromMs": from_ms,
                    "toMs": now_ms,
                    "hours": hours,
                    "entries": entries,
                    "truncated": truncated,
                    "logFilesScanned": files_scanned,
                    "entryCount": len(entries),
                    "logRelPath": log_rel,
                },
            )
            return

        if path == "/api/read-json":
            bucket = (qs.get("bucket") or [""])[0]
            name = (qs.get("file") or [""])[0]
            p = _safe_bucket_file(bucket, name)
            if p is None or not p.is_file():
                self._json(404, {"error": "文件不存在或路径非法"})
                return
            if p.stat().st_size > MAX_JSON_BYTES:
                self._json(413, {"error": f"文件超过 {MAX_JSON_BYTES // (1024 * 1024)}MB 上限，请用其他工具查看"})
                return
            try:
                text = p.read_text(encoding="utf-8")
                data = json.loads(text)
            except UnicodeDecodeError:
                self._json(400, {"error": "非 UTF-8 文本"})
                return
            except json.JSONDecodeError as e:
                self._json(400, {"error": f"JSON 解析失败: {e}"})
                return
            self._json(200, {"path": str(p.relative_to(_package_root())), "data": data})
            return

        if path == "/api/read-jsonl":
            bucket = (qs.get("bucket") or [""])[0]
            name = (qs.get("file") or [""])[0]
            try:
                start = max(0, int((qs.get("start") or ["0"])[0]))
            except ValueError:
                start = 0
            try:
                limit = min(MAX_JSONL_LINES_PER_REQUEST, max(1, int((qs.get("limit") or ["100"])[0])))
            except ValueError:
                limit = 100

            p = _safe_bucket_file(bucket, name)
            if p is None or not p.is_file():
                self._json(404, {"error": "文件不存在或路径非法"})
                return
            lines_out = []
            total_read = 0
            try:
                with p.open("r", encoding="utf-8", errors="replace") as f:
                    for i, line in enumerate(f):
                        if total_read >= MAX_JSONL_SCAN_LINES:
                            break
                        total_read = i + 1
                        if i < start:
                            continue
                        if len(lines_out) >= limit:
                            continue
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            obj = json.loads(line)
                        except json.JSONDecodeError:
                            obj = {"_raw": line, "_parseError": True}
                        lines_out.append({"lineNo": i + 1, "data": obj})
            except OSError as e:
                self._json(500, {"error": str(e)})
                return

            scanned = total_read
            truncated = scanned >= MAX_JSONL_SCAN_LINES
            self._json(
                200,
                {
                    "path": str(p.relative_to(_package_root())),
                    "start": start,
                    "limit": limit,
                    "returned": len(lines_out),
                    "scannedLines": scanned,
                    "truncatedScan": truncated,
                    "lines": lines_out,
                },
            )
            return

        if path == "/api/jsonl-filter-time":
            """按毫秒时间戳过滤 jsonl（全文件扫描，大文件可能较慢）。"""
            bucket = (qs.get("bucket") or [""])[0]
            name = (qs.get("file") or [""])[0]
            try:
                from_ms = int((qs.get("from") or ["0"])[0])
            except ValueError:
                from_ms = 0
            try:
                to_ms = int((qs.get("to") or [str(2**63 - 1)])[0])
            except ValueError:
                to_ms = 2**63 - 1
            try:
                max_hits = min(500, max(1, int((qs.get("max") or ["200"])[0])))
            except ValueError:
                max_hits = 200

            p = _safe_bucket_file(bucket, name)
            if p is None or not p.is_file():
                self._json(404, {"error": "文件不存在或路径非法"})
                return

            hits = []
            scanned = 0
            try:
                with p.open("r", encoding="utf-8", errors="replace") as f:
                    for i, line in enumerate(f):
                        if scanned >= MAX_JSONL_SCAN_LINES:
                            break
                        scanned = i + 1
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            obj = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        ts = obj.get("timestamp")
                        if not isinstance(ts, (int, float)):
                            continue
                        tsi = int(ts)
                        if from_ms <= tsi <= to_ms:
                            hits.append({"lineNo": i + 1, "data": obj})
                            if len(hits) >= max_hits:
                                break
            except OSError as e:
                self._json(500, {"error": str(e)})
                return

            self._json(
                200,
                {
                    "path": str(p.relative_to(_package_root())),
                    "fromMs": from_ms,
                    "toMs": to_ms,
                    "hits": hits,
                    "scannedLines": scanned,
                    "truncatedScan": scanned >= MAX_JSONL_SCAN_LINES,
                },
            )
            return

        static_dir = _package_root() / "storage_browser"
        if path in ("/", "/index.html", "/v6", "/v6/", "/v6/index.html"):
            index = static_dir / "index.html"
            if not index.is_file():
                self._send(500, b"index.html missing", "text/plain; charset=utf-8")
                return
            body = index.read_bytes()
            self._send(200, body, "text/html; charset=utf-8")
            return

        if path.startswith("/storage_browser/"):
            rel = path[len("/storage_browser/") :].lstrip("/")
            if ".." in rel or rel.startswith("/"):
                self.send_error(404)
                return
            target = (static_dir / rel).resolve()
            try:
                target.relative_to(static_dir.resolve())
            except ValueError:
                self.send_error(404)
                return
            if not target.is_file():
                self.send_error(404)
                return
            ctype, _ = mimetypes.guess_type(target.name)
            if not ctype:
                ctype = "application/octet-stream"
            self._send(200, target.read_bytes(), ctype)
            return

        self.send_error(404)


def main() -> None:
    parser = argparse.ArgumentParser(description="storage_browser 本地服务")
    parser.add_argument(
        "--host",
        default="0.0.0.0",
        help="监听地址。0.0.0.0 允许本机与局域网 IPv4 访问；127.0.0.1 仅本机",
    )
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    out = _outputs_root()
    print(f"outputs 目录: {out} (exists={out.is_dir()})")
    if args.host in ("0.0.0.0", "::", ""):
        print(f"本机访问: http://127.0.0.1:{args.port}/  或  http://127.0.0.1:{args.port}/v6/")
        print(f"健康检查: http://127.0.0.1:{args.port}/v6/health")
        print(f"局域网其它设备: http://<本机IPv4>:{args.port}/ （若不通请检查系统防火墙是否放行 TCP {args.port}）")
    else:
        print(f"打开浏览器: http://{args.host}:{args.port}/  或  /v6/")
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


if __name__ == "__main__":
    main()
