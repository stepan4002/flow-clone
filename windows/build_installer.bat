@echo off
rem Builds installer\Output\FlowClone-Setup.exe from dist\FlowClone (run build_exe.bat first).
rem Needs Inno Setup 6: winget install JRSoftware.InnoSetup --scope user
setlocal
cd /d "%~dp0"

set ISCC=
if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" set ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe
if exist "%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe" set ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe
if "%ISCC%"=="" for %%I in (ISCC.exe) do set ISCC=%%~$PATH:I
if "%ISCC%"=="" goto noinno

if not exist dist\FlowClone\FlowClone.exe goto nobuild

"%ISCC%" installer\FlowClone.iss
if errorlevel 1 goto failed
echo.
echo Done: installer\Output\FlowClone-Setup.exe
goto end

:noinno
echo Inno Setup 6 not found. Install it with:  winget install JRSoftware.InnoSetup --scope user
goto failed

:nobuild
echo dist\FlowClone\FlowClone.exe not found - run build_exe.bat first.
goto failed

:failed
if "%CI%"=="" pause
exit /b 1

:end
if "%CI%"=="" pause
exit /b 0
