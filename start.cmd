@echo off
rem JudgeX launcher: single port 18900, SPA + API in one jar.
setlocal
cd /d "%~dp0backend"

if not exist "target\judgex-backend-1.0.0.jar" (
  echo [!] jar not found. Build it first, see README.md section 7.
  exit /b 1
)

if not defined JUDGEX_PORT set JUDGEX_PORT=18900
echo JudgeX starting on http://127.0.0.1:%JUDGEX_PORT%
java -jar target\judgex-backend-1.0.0.jar
