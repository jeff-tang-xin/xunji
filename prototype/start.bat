@echo off
rem ============================================================
rem  XuanJi prototype local preview
rem  Double-click to run: starts local HTTP server on port 8321
rem  and opens the browser at http://localhost:8321/
rem  (file:// protocol is blocked by browser security - must use HTTP)
rem ============================================================
cd /d %~dp0

netstat -ano | findstr :8321 | findstr LISTENING >nul 2>&1
if %errorlevel%==0 (
  echo [OK] Server already running on port 8321, opening browser...
) else (
  echo [..] Starting local server at http://localhost:8321/ ...
  start "xuanji-proto" /min python -m http.server 8321
  timeout /t 1 /nobreak >nul
  echo [OK] Server started. To stop: close the minimized "xuanji-proto" window.
)

start "" http://localhost:8321/
