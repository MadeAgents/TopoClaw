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

# Sync TopoClaw 子目录 topoclaw（原 nanobot）到 resources/TopoClaw
# Run from project root: npm run setup:assistant
# Excludes tests, .venv, __pycache__, .git to keep bundle clean
# Source: TopoClaw/topoclaw 优先；兼容 TopoClaw/nanobot、仓库根 nanobot；legacy openclaw_main3/ColorClaw
# Config / .env.oppo: repo root, then openclaw_main3, then openclaw_main2

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# PSScriptRoot = TopoDesktop/scripts, parent = TopoDesktop, grandparent = workspace (TopoClaw) root
$projectRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, ".."))
$workspaceRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($projectRoot, ".."))
$targetDir = Join-Path $projectRoot "resources\TopoClaw"
$innerTopoClaw = Join-Path $workspaceRoot "TopoClaw"
$legacySource = Join-Path $workspaceRoot "openclaw_main3\ColorClaw"
$topoclawInner = Join-Path $innerTopoClaw "topoclaw"
$nanobotInner = Join-Path $innerTopoClaw "nanobot"
$topoclawAtRoot = Join-Path $workspaceRoot "topoclaw"
$nanobotAtRoot = Join-Path $workspaceRoot "nanobot"
$main3Root = Join-Path $workspaceRoot "openclaw_main3"
$main2Root = Join-Path $workspaceRoot "openclaw_main2"

function Get-EnvValueFromFile {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [Parameter(Mandatory = $true)][string]$Key
  )
  if (-not (Test-Path $FilePath)) { return "" }
  foreach ($line in Get-Content $FilePath -Encoding UTF8) {
    $trimmed = ($line | ForEach-Object { $_.Trim() })
    if (-not $trimmed -or $trimmed.StartsWith("#")) { continue }
    if ($trimmed -match "^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$") {
      if ($Matches[1] -ne $Key) { continue }
      $value = ($Matches[2] | ForEach-Object { $_.Trim() })
      if (
        ($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))
      ) {
        $value = $value.Substring(1, $value.Length - 2)
      }
      return $value.Trim()
    }
  }
  return ""
}

function Convert-CustomerServiceUrlToTopomobileWsUrl {
  param([string]$CustomerServiceUrl)
  if (-not $CustomerServiceUrl) { return "" }
  try {
    $uri = [System.Uri]::new($CustomerServiceUrl.Trim())
    $scheme = if ($uri.Scheme -eq "https") { "wss" } elseif ($uri.Scheme -eq "http") { "ws" } else { $uri.Scheme }
    $builder = [System.UriBuilder]::new($uri)
    $builder.Scheme = $scheme
    if ($builder.Port -eq 443 -and $scheme -eq "wss") { $builder.Port = -1 }
    if ($builder.Port -eq 80 -and $scheme -eq "ws") { $builder.Port = -1 }
    $path = $builder.Path
    if (-not $path) { $path = "/" }
    if (-not $path.EndsWith("/")) { $path = "$path/" }
    if (-not $path.EndsWith("/ws/")) { $path = "$path/ws/" }
    $builder.Path = $path -replace "/{2,}", "/"
    $builder.Query = ""
    $builder.Fragment = ""
    $out = $builder.Uri.AbsoluteUri
    if ($out.EndsWith("/")) { return $out.Substring(0, $out.Length - 1) }
    return $out
  } catch {
    return ""
  }
}

$desktopEnvLocalPath = Join-Path $projectRoot ".env.local"
$customerServiceUrl =
  ($env:VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL | ForEach-Object { "$_".Trim() })
if (-not $customerServiceUrl) {
  $customerServiceUrl = Get-EnvValueFromFile -FilePath $desktopEnvLocalPath -Key "VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL"
}
$topomobileWsUrl = Convert-CustomerServiceUrlToTopomobileWsUrl -CustomerServiceUrl $customerServiceUrl
$preferredNodeId = ($env:TOPO_DESKTOP_IMEI | ForEach-Object { "$_".Trim() })
if (-not $preferredNodeId) {
  $preferredNodeId = ($env:IMEI | ForEach-Object { "$_".Trim() })
}
if ($preferredNodeId -eq "000") { $preferredNodeId = "" }

$sourceDir = $null
if (Test-Path $topoclawInner) {
  $sourceDir = $innerTopoClaw
} elseif (Test-Path $nanobotInner) {
  $sourceDir = $innerTopoClaw
} elseif (Test-Path $topoclawAtRoot) {
  $sourceDir = $workspaceRoot
} elseif (Test-Path $nanobotAtRoot) {
  $sourceDir = $workspaceRoot
} elseif (Test-Path $legacySource) {
  $sourceDir = $legacySource
}

if (-not $sourceDir) {
  Write-Warning "Source not found: expected topoclaw (or legacy nanobot) under `"$innerTopoClaw`", at repo root `"$workspaceRoot`", or legacy `"$legacySource`""
  exit 1
}

if (-not (Test-Path (Join-Path $targetDir ".."))) {
  New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
}

# 关键：每次同步前先清空目标目录，避免历史残留文件被继续打包进桌面端发布物
if (Test-Path $targetDir) {
  Write-Host "Cleaning existing target directory: $targetDir"
  Remove-Item $targetDir -Recurse -Force -ErrorAction Stop
}
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null

Write-Host "Syncing TopoClaw bundle from $sourceDir to $targetDir ..."
robocopy $sourceDir $targetDir /S /E /XD tests examples outputs __pycache__ .venv .git /XF .env *.pyc /NFL /NDL /NJH /NJS
$exitCode = $LASTEXITCODE
if ($exitCode -ge 8) {
  Write-Warning "Robocopy failed with exit code $exitCode"
  exit $exitCode
}

# Ensure key packaging metadata is always refreshed even if destination files are newer.
$sourcePyproject = Join-Path $sourceDir "pyproject.toml"
$targetPyproject = Join-Path $targetDir "pyproject.toml"
if (Test-Path $sourcePyproject) {
  Copy-Item $sourcePyproject $targetPyproject -Force
}

# 创建 workspace 子目录（topoclaw / nanobot 运行时需要）
$workspaceDir = Join-Path $targetDir "workspace"
if (-not (Test-Path $workspaceDir)) {
  New-Item -ItemType Directory -Path $workspaceDir -Force | Out-Null
  Write-Host "Created workspace directory"
}

# 配置：优先仓库根目录，否则 openclaw_main3 / openclaw_main2（旧布局）
$configSource = $null
$configLabel = ""
foreach ($root in @($innerTopoClaw, $workspaceRoot, $main3Root, $main2Root)) {
  $oppo = Join-Path $root "config.json.oppo"
  $plain = Join-Path $root "config.json"
  if (Test-Path $oppo) { $configSource = $oppo; $configLabel = "config.json.oppo in $(Split-Path $root -Leaf)"; break }
  if (Test-Path $plain) { $configSource = $plain; $configLabel = "config.json in $(Split-Path $root -Leaf)"; break }
}

$configTarget = Join-Path $targetDir "config.json"
if ($configSource) {
  $config = Get-Content $configSource -Raw -Encoding UTF8 | ConvertFrom-Json
  $config.gateway.port = 18790  # 内置 exe 固定 18790，兼容 PC/手机默认
  $config.agents.defaults.workspace = "workspace"  # 相对路径，运行时在 TopoClaw 目录下
  # 默认开启 TopoMobile adapter，避免数字分身等云侧回调因未启用而离线
  if (-not $config.channels) {
    $config | Add-Member -NotePropertyName channels -NotePropertyValue (@{}) -Force
  }
  if (-not $config.channels.topomobile) {
    $config.channels | Add-Member -NotePropertyName topomobile -NotePropertyValue (@{}) -Force
  }
  $config.channels.topomobile.enabled = $true
  if (-not $config.channels.topomobile.allowFrom) { $config.channels.topomobile.allowFrom = @("*") }
  $currentWsUrl = "$($config.channels.topomobile.wsUrl)".Trim()
  if (
    $topomobileWsUrl -and (
      -not $currentWsUrl -or
      $currentWsUrl -match '^wss?://localhost(?::\d+)?/' -or
      $currentWsUrl -match '^wss?://127\.0\.0\.1(?::\d+)?/'
    )
  ) {
    $config.channels.topomobile.wsUrl = $topomobileWsUrl
  } elseif (-not $currentWsUrl) {
    $config.channels.topomobile.wsUrl = "ws://localhost:8000/ws"
  }
  if ($preferredNodeId) {
    $config.channels.topomobile.nodeId = $preferredNodeId
  } elseif (-not $config.channels.topomobile.nodeId) {
    $config.channels.topomobile.nodeId = "000"
  }
  $json = $config | ConvertTo-Json -Depth 20
  [System.IO.File]::WriteAllText($configTarget, $json + "`n", [System.Text.UTF8Encoding]::new($false))
  Write-Host "Copied config from $configLabel (port 18790, topomobile enabled, ws=$($config.channels.topomobile.wsUrl), nodeId=$($config.channels.topomobile.nodeId))"
} else {
  $minConfig = @{
    agents = @{ defaults = @{ workspace = "workspace"; model = "openai/gpt-4o-mini" } }
    gateway = @{ port = 18790; host = "0.0.0.0" }
    channels = @{
      topomobile = @{
        enabled = $true
        allowFrom = @("*")
        wsUrl = $(if ($topomobileWsUrl) { $topomobileWsUrl } else { "ws://localhost:8000/ws" })
        nodeId = $(if ($preferredNodeId) { $preferredNodeId } else { "000" })
      }
    }
  }
  $json = $minConfig | ConvertTo-Json -Depth 8
  [System.IO.File]::WriteAllText($configTarget, $json + "`n", [System.Text.UTF8Encoding]::new($false))
  Write-Host "Created default config.json with topomobile enabled (no config in TopoClaw/, repo root, openclaw_main3, or openclaw_main2)"
}

# .env.oppo：与配置同源目录优先，否则在 main3 / main2 中任选一个存在的
$envOppo = $null
if ($configSource) {
  $cfgDir = Split-Path $configSource -Parent
  $trySame = Join-Path $cfgDir ".env.oppo"
  if (Test-Path $trySame) { $envOppo = $trySame }
}
if (-not $envOppo) {
  foreach ($root in @($innerTopoClaw, $workspaceRoot, $main3Root, $main2Root)) {
    $p = Join-Path $root ".env.oppo"
    if (Test-Path $p) { $envOppo = $p; break }
  }
}
$envTarget = Join-Path $targetDir ".env"
if ($envOppo) {
  Copy-Item $envOppo $envTarget -Force
  Write-Host "Generated .env from .env.oppo (bundled in exe)"
} else {
  Write-Warning ".env.oppo not found in TopoClaw/, repo root, openclaw_main3, or openclaw_main2, create one for OPENAI_API_KEY if needed"
}

Write-Host "Done. Assistant bundle synced from $sourceDir to resources/TopoClaw"
