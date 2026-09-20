@echo off
setlocal
rem ===========================================================================
rem  Instala ARGOS en %LOCALAPPDATA%\ARGOS y crea el acceso directo.
rem  No toca el registro, no pide administrador y se desinstala borrando la
rem  carpeta: no hay estado escondido en ningun otro sitio.
rem ===========================================================================
set "DEST=%LocalAppData%\ARGOS"

echo   Instalando ARGOS en %DEST% ...
if not exist "%DEST%" mkdir "%DEST%" >nul 2>&1
copy /Y "%~dp0argos.html"  "%DEST%\" >nul
copy /Y "%~dp0server.js"   "%DEST%\" >nul
copy /Y "%~dp0ARGOS.cmd"   "%DEST%\" >nul
if exist "%~dp0argos.ico" copy /Y "%~dp0argos.ico" "%DEST%\" >nul
rem El modelo viaja con la app: sin el, el detector neuronal no se puede cargar
rem y el usuario tendria que ir a buscarlo a una release de GitHub.
if exist "%~dp0yolox_nano.onnx" copy /Y "%~dp0yolox_nano.onnx" "%DEST%\" >nul
if exist "%~dp0MODELO.md" copy /Y "%~dp0MODELO.md" "%DEST%\" >nul
if exist "%~dp0EMPIEZA-AQUI.md" copy /Y "%~dp0EMPIEZA-AQUI.md" "%DEST%\" >nul

powershell -NoProfile -Command ^
  "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\ARGOS.lnk');" ^
  "$s.TargetPath='%DEST%\ARGOS.cmd'; $s.WorkingDirectory='%DEST%';" ^
  "if(Test-Path '%DEST%\argos.ico'){$s.IconLocation='%DEST%\argos.ico'};" ^
  "$s.WindowStyle=7; $s.Description='ARGOS - analisis de video'; $s.Save()"

echo.
echo   Listo. Tienes ARGOS en el Escritorio.
echo   Se desinstala borrando la carpeta %DEST%
echo.
pause
endlocal
