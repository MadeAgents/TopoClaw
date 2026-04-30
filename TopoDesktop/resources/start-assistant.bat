@echo off
"%SystemRoot%\System32\chcp.com" 65001 >nul 2>&1
setlocal
set "BAT_DIR=%~dp0"
set "BAT_DIR=%BAT_DIR:~0,-1%"

REM 若在 resources 下（含 python-embed、TopoClaw），则在此运行
if exist "%BAT_DIR%\python-embed\python.exe" if exist "%BAT_DIR%\TopoClaw\pyproject.toml" (
  cd /d "%BAT_DIR%"
  goto :run
)

REM 若在 exe 同级（含 resources 子目录），则进入 resources 运行
if exist "%BAT_DIR%\resources\python-embed\python.exe" if exist "%BAT_DIR%\resources\TopoClaw\pyproject.toml" (
  cd /d "%BAT_DIR%\resources"
  goto :run
)

echo [错误] 未找到内置 Python 或 TopoClaw，请确保在应用安装目录下运行此脚本。
echo 当前路径: %BAT_DIR%
pause
exit /b 1

:run
REM 在 TopoClaw 目录下运行 nanobot service（/ws + /upload），便于加载 .env 和 config.json
cd TopoClaw
..\python-embed\python.exe -m nanobot.cli.commands service --config config.json --port 18790 --workspace workspace %*
