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
 * 本地 ColorClaw / Skills 服务根地址（无尾斜杠亦可）。
 * 与 `fetchInstalledSkillsFromService`（GET /skills）同源；PublicHub 安装/更新经主进程代理到此端口。
 * 定时任务 HTTP（GET/POST cron/jobs、DELETE cron/jobs/:id）与此同源，见 scheduleApi / getScheduleJobBaseUrl。
 */
export const COLORCLAW_SERVICE_BASE_URL = 'http://127.0.0.1:18790'
