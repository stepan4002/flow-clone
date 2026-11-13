@echo off
rem Builds a standalone FlowClone.exe (no Python needed on the target machine).
rem One-time: pip install pyinstaller
cd /d "%~dp0"
pyinstaller --noconsole --name FlowClone ^
  --collect-all onnx_asr ^
  --collect-all onnxruntime ^
  --hidden-import pystray._win32 ^
  run.py
echo.
echo Done. The app is in dist\FlowClone\FlowClone.exe
pause
