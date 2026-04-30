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

/** 定时任务 HTTP 路径（相对本地 Skills / Cron 服务根，如 http://127.0.0.1:18790/） */

export const CRON_JOBS_PATH = 'cron/jobs'

export function cronJobDeletePath(jobId: string): string {
  return `cron/jobs/${encodeURIComponent(jobId)}`
}
