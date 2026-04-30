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

# Sync repo-root GroupManager -> resources/group-manager (bundled SimpleChat / 群组管理助手)
# Run: npm run setup:group-manager
# PSScriptRoot = TopoDesktop/scripts

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($PSScriptRoot, ".."))
$workspaceRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($projectRoot, ".."))
$sourceDir = Join-Path $workspaceRoot "GroupManager"
$targetDir = Join-Path $projectRoot "resources\group-manager"

if (-not (Test-Path $sourceDir)) {
  Write-Warning "GroupManager not found at: $sourceDir"
  exit 1
}

if (-not (Test-Path (Join-Path $targetDir ".."))) {
  New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
}

Write-Host "Syncing GroupManager from $sourceDir to $targetDir ..."
robocopy $sourceDir $targetDir /S /E /XD __pycache__ .venv .git /XF .env *.pyc /NFL /NDL /NJH /NJS
$exitCode = $LASTEXITCODE
if ($exitCode -ge 8) {
  Write-Warning "Robocopy failed with exit code $exitCode"
  exit $exitCode
}
Write-Host "GroupManager sync done."
