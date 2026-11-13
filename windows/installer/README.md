# FlowClone installer

`FlowClone.iss` turns `windows/dist/FlowClone/` (built by `build_exe.bat`) into
`installer/Output/FlowClone-Setup.exe` with [Inno Setup 6](https://jrsoftware.org/isinfo.php).

```bat
winget install JRSoftware.InnoSetup --scope user   & rem one time
build_exe.bat
build_installer.bat
```

What the installer does:
- per-user install into `%LOCALAPPDATA%\Programs\FlowClone`, no admin rights;
- Start-menu entry, optional desktop icon and "start when I sign in" (HKCU Run `FlowClone`);
- closes a running FlowClone.exe first (named mutex `Local\FlowCloneExe` + taskkill);
- upgrades in place (same AppId) and replaces `_internal` completely;
- the uninstaller removes the Run value only if it still points at this install, and asks
  before deleting settings (`%APPDATA%\FlowClone`) and the speech model
  (`%LOCALAPPDATA%\FlowClone\models`). It never touches a script install's `app`/`venv`.

**Never change `AppId`** in `FlowClone.iss`: Windows would treat the next version as a
different app and users would end up with two installs.

Code signing is not enabled yet; see the commented `SignTool=` line.
