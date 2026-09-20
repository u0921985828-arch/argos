@echo off
setlocal EnableDelayedExpansion
rem ===========================================================================
rem  ARGOS - lanzador de escritorio
rem ---------------------------------------------------------------------------
rem  Abre ARGOS como aplicacion propia: ventana sin barra de navegador, icono
rem  propio, entrada propia en la barra de tareas. Sin Electron y sin instalar
rem  nada: usa el modo --app del navegador que ya tienes.
rem
rem  Levanta antes un servidor estatico en 127.0.0.1. No es el modulo de
rem  analisis --- son treinta lineas que sirven un fichero --- y esta ahi por un
rem  motivo concreto: la captura de pantalla exige contexto seguro, y
rem  http://127.0.0.1 lo es mientras que file:// no lo es de forma fiable.
rem ===========================================================================

cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
  echo.
  echo   ARGOS necesita Node.js para el lanzador.
  echo   Descargalo en https://nodejs.org  ^(o abre argos.html directamente,
  echo   aunque entonces la captura de pantalla puede no estar disponible^).
  echo.
  pause
  exit /b 1
)

rem --- navegador basado en Chromium, en orden de preferencia -----------------
set "BROWSER="
for %%P in (
  "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"
  "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"
  "%ProgramFiles%\Google\Chrome\Application\chrome.exe"
  "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
  "%LocalAppData%\Google\Chrome\Application\chrome.exe"
  "%ProgramFiles%\BraveSoftware\Brave-Browser\Application\brave.exe"
) do if exist %%P if not defined BROWSER set "BROWSER=%%~P"

if not defined BROWSER (
  echo   No encuentro Edge, Chrome ni Brave. El modo aplicacion los necesita.
  pause
  exit /b 1
)

rem --- arrancar el servidor y capturar el puerto que ha tocado ---------------
for /f "tokens=2" %%A in ('powershell -NoProfile -Command ^
  "$p=Start-Process node -ArgumentList 'server.js' -PassThru -NoNewWindow -RedirectStandardOutput '%TEMP%\argos_port.txt'; Start-Sleep -Milliseconds 900; Get-Content '%TEMP%\argos_port.txt' -TotalCount 1; Set-Content '%TEMP%\argos_pid.txt' $p.Id"') do set PORT=%%A

if not defined PORT (
  echo   El servidor local no arranco. Revisa que node funciona.
  pause
  exit /b 1
)

rem  Perfil propio para que ARGOS no herede extensiones ni sesiones del
rem  navegador del usuario, y para que la ventana sea realmente independiente.
set "PROFILE=%LocalAppData%\ARGOS\profile"

start "" /wait "%BROWSER%" ^
  --app=http://127.0.0.1:%PORT%/ ^
  --user-data-dir="%PROFILE%" ^
  --window-size=1360,860 ^
  --no-first-run ^
  --no-default-browser-check ^
  --disable-features=Translate,MediaRouter ^
  --autoplay-policy=no-user-gesture-required

rem --- el navegador se ha cerrado: no dejar el servidor huerfano -------------
for /f %%I in ('type "%TEMP%\argos_pid.txt" 2^>nul') do taskkill /PID %%I /F >nul 2>&1
del "%TEMP%\argos_port.txt" "%TEMP%\argos_pid.txt" >nul 2>&1
endlocal
