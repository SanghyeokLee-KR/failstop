@echo off
rem Serve this deck over HTTP (double-clicking index.html via file:// breaks in-slide steps, notes and links).
rem Uses serve.mjs (Node.js, supports Range so the demo video can seek) and falls back to Python.
cd /d "%~dp0"
where node >nul 2>nul && (
  start "deck server" /min node serve.mjs 8090
  goto open
)
where python >nul 2>nul || (echo Node.js or Python 3 is required. See the text file in this folder. & pause & exit /b 1)
echo Node.js not found. Using Python; seeking in the demo video may not work.
start "deck server" /min python -m http.server 8090 --bind 127.0.0.1
:open
timeout /t 2 /nobreak >nul
start "" "http://127.0.0.1:8090/index.html"
