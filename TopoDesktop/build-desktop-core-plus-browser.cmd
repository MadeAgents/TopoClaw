@echo off
setlocal

REM One-click desktop build:
REM 1) Sync TopoClaw resources
REM 2) Sync customer_service resources
REM 3) Install TopoClaw core deps
REM 4) Install browser-use without dependency solving (--no-deps)
REM 5) Build Electron package

cd /d "%~dp0"

echo [1/9] Install Node dependencies...
call npm install
if errorlevel 1 goto :fail

echo [2/9] Sync TopoClaw assistant resources...
call npm run setup:assistant
if errorlevel 1 goto :fail

echo [3/9] Sync customer_service resources...
call npm run setup:customer-service
if errorlevel 1 goto :fail

echo [4/9] Install TopoClaw core dependencies...
call npm run setup:python
if errorlevel 1 goto :fail

echo [5/9] Install browser-use package only (--no-deps)...
pushd resources\TopoClaw
..\python-embed\python.exe -m pip install browser-use==0.12.0 --no-deps --prefer-binary
if errorlevel 1 (
  popd
  goto :fail
)
popd

echo [6/9] Round icon...
call npm run round-icon
if errorlevel 1 goto :fail

echo [7/9] Generate licenses...
call npm run licenses:generate
if errorlevel 1 goto :fail

echo [8/9] Build TypeScript and Vite bundles...
call npx tsc -p tsconfig.electron.json
if errorlevel 1 goto :fail
call npx vite build
if errorlevel 1 goto :fail

echo [9/9] Build Electron package...
call npx electron-builder --config electron-builder.config.cjs
if errorlevel 1 goto :fail

echo.
echo Build completed successfully.
exit /b 0

:fail
echo.
echo Build failed. Check logs above.
exit /b 1
