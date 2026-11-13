@echo off
rem Builds FlowClone.exe (one folder, no Python needed on the target PC) and self-tests it.
rem Needs Python 3.12 with: pip install -r requirements-build.txt
rem Output: dist\FlowClone\FlowClone.exe
setlocal
cd /d "%~dp0"

python tools\gen_notices.py
if errorlevel 1 goto failed

python -m PyInstaller --noconfirm --clean FlowClone.spec
if errorlevel 1 goto failed

echo.
echo Self-test of the built app...
start "" /wait dist\FlowClone\FlowClone.exe --selftest
set RESULT=%errorlevel%
type "%TEMP%\FlowClone-selftest.log"
if not "%RESULT%"=="0" goto failed

echo.
echo Done. The app is in dist\FlowClone\FlowClone.exe
echo Build the installer next with build_installer.bat
goto end

:failed
echo.
echo BUILD FAILED - see the messages above.
if "%CI%"=="" pause
exit /b 1

:end
if "%CI%"=="" pause
exit /b 0
