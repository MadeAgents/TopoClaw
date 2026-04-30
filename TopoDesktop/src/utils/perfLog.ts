// Copyright 2025 OPPO

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/**
 * 性能日志工具，便于在控制台用 [Perf] 筛选分析耗时
 */
export function perfLog(op: string, detail?: Record<string, unknown>): void {
  const msg = detail ? `[Perf] ${op}` : `[Perf] ${op}`
  console.log(msg, detail ?? '')
}

export function perfLogEnd(op: string, startMs: number, extra?: Record<string, unknown>): void {
  const duration = Date.now() - startMs
  perfLog(`${op} 完成`, { durationMs: duration, ...extra })
}

export function perfTime<T>(op: string, fn: () => T, extra?: Record<string, unknown>): T {
  const start = Date.now()
  try {
    const result = fn()
    perfLogEnd(op, start, extra)
    return result
  } catch (e) {
    perfLogEnd(`${op} 失败`, start, { error: String(e), ...extra })
    throw e
  }
}

export async function perfTimeAsync<T>(
  op: string,
  fn: () => Promise<T>,
  extra?: Record<string, unknown>
): Promise<T> {
  const start = Date.now()
  try {
    const result = await fn()
    perfLogEnd(op, start, extra)
    return result
  } catch (e) {
    perfLogEnd(`${op} 失败`, start, { error: String(e), ...extra })
    throw e
  }
}
