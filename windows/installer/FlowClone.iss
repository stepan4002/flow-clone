; FlowClone installer (Inno Setup 6.3+). Build: ..\build_installer.bat (after build_exe.bat).
; Per-user install, no admin rights: %LOCALAPPDATA%\Programs\FlowClone.
; NEVER change AppId: it is how Windows recognises upgrades of the same app.

#define ExePath AddBackslash(SourcePath) + "..\dist\FlowClone\FlowClone.exe"
#if !FileExists(ExePath)
  #error dist\FlowClone\FlowClone.exe not found - run build_exe.bat first
#endif
#define FullVer GetVersionNumbersString(ExePath)
#define AppVersion Copy(FullVer, 1, RPos(".", FullVer) - 1)

[Setup]
AppId={{8456CF1F-F3F1-4BE1-AE81-0513E6010D46}
AppName=FlowClone
AppVersion={#AppVersion}
AppVerName=FlowClone {#AppVersion}
AppPublisher=FlowClone contributors
AppPublisherURL=https://github.com/stepan4002/flow-clone
AppSupportURL=https://github.com/stepan4002/flow-clone/issues
AppUpdatesURL=https://github.com/stepan4002/flow-clone/releases
VersionInfoVersion={#FullVer}
DefaultDirName={autopf}\FlowClone
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
UsePreviousTasks=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=Output
OutputBaseFilename=FlowClone-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\assets\flowclone.ico
UninstallDisplayIcon={app}\FlowClone.exe
UninstallDisplayName=FlowClone
LicenseFile=..\..\LICENSE
CloseApplications=yes
RestartApplications=no
; Code signing (not enabled yet): define a sign tool in the Inno IDE / ISCC /S and uncomment.
; SignTool=flowclone $f
; SignedUninstaller=yes

[Tasks]
Name: "autostart"; Description: "Start FlowClone when I sign in to Windows"; Flags: unchecked
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked

[InstallDelete]
; No stale PyInstaller files across upgrades - only in a folder that holds FlowClone.exe,
; never another app's _internal in a hand-picked folder.
Type: filesandordirs; Name: "{app}\_internal"; Check: IsFlowCloneFolder

[Files]
Source: "..\dist\FlowClone\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\FlowClone"; Filename: "{app}\FlowClone.exe"
Name: "{autodesktop}\FlowClone"; Filename: "{app}\FlowClone.exe"; Tasks: desktopicon

[Registry]
; No uninsdeletevalue: the uninstaller removes the value only if it still points here
; (the Hub can rewrite it, and a script install may own it).
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "FlowClone"; ValueData: """{app}\FlowClone.exe"""; Tasks: autostart

[Run]
Filename: "{app}\FlowClone.exe"; Description: "Start FlowClone now"; Flags: nowait postinstall skipifsilent

[Messages]
FinishedLabel=FlowClone is installed. On first start it downloads its speech model once (about 670 MB) and shows the progress near the bottom of the screen.

[Code]
const
  ExeMutex = 'Local\FlowCloneExe';
  RunKey = 'Software\Microsoft\Windows\CurrentVersion\Run';

var
  OldScriptInstall: Boolean;

function IsFlowCloneFolder(): Boolean;
begin
  Result := FileExists(ExpandConstant('{app}\FlowClone.exe'));
end;

{ The script install (install.ps1) uses the same Start-menu file. When the exe is
  uninstalled while the script install remains, give the shortcut back to it. }
procedure RestoreScriptShortcut();
var
  Base, Pythonw, RunPy, Lnk: String;
begin
  Base := ExpandConstant('{localappdata}\FlowClone');
  Pythonw := Base + '\venv\Scripts\pythonw.exe';
  RunPy := Base + '\app\run.py';
  Lnk := ExpandConstant('{userprograms}\FlowClone.lnk');
  if FileExists(Pythonw) and FileExists(RunPy) and not FileExists(Lnk) then
    try
      CreateShellLink(Lnk, 'FlowClone - local voice dictation', Pythonw,
                      '"' + RunPy + '"', Base + '\app', '', 0, SW_SHOWNORMAL);
    except
      Log('Could not restore the script-install shortcut: ' + GetExceptionMessage);
    end;
end;

{ Close a running FlowClone.exe and wait until it has released its files. }
procedure CloseRunningApp(Silent: Boolean);
var
  RC, I: Integer;
begin
  if not CheckForMutexes(ExeMutex) then
    exit;
  if not Silent then
    MsgBox('FlowClone is running and will be closed.', mbInformation, MB_OK);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM FlowClone.exe', '', SW_HIDE,
       ewWaitUntilTerminated, RC);
  for I := 1 to 40 do
  begin
    if not CheckForMutexes(ExeMutex) then
      exit;
    Sleep(250);
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  CloseRunningApp(WizardSilent);
  OldScriptInstall := DirExists(ExpandConstant('{localappdata}\FlowClone\venv'));
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and OldScriptInstall and (not WizardSilent) then
    MsgBox('An older script-based FlowClone install was found in' + #13#10 +
           ExpandConstant('{localappdata}\FlowClone') + ' (app and venv folders).' + #13#10#13#10 +
           'Nothing was deleted. Both versions share your settings; the Start-menu entry now' + #13#10 +
           'opens FlowClone.exe. To remove the old one, use the uninstall command in the README.',
           mbInformation, MB_OK);
end;

function InitializeUninstall(): Boolean;
begin
  CloseRunningApp(UninstallSilent);
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Data: String;
begin
  if CurUninstallStep <> usPostUninstall then
    exit;
  { Autostart: remove it only if it still starts this install. }
  if RegQueryStringValue(HKEY_CURRENT_USER, RunKey, 'FlowClone', Data) then
    if Pos(Lowercase(ExpandConstant('{app}\FlowClone.exe')), Lowercase(Data)) > 0 then
      RegDeleteValue(HKEY_CURRENT_USER, RunKey, 'FlowClone');
  RestoreScriptShortcut();
  if UninstallSilent then
    exit;
  if MsgBox('Also remove your FlowClone settings, history, dictionary and the downloaded' + #13#10 +
            'speech model (about 670 MB)?' + #13#10#13#10 +
            'They are shared with a script-based FlowClone install, if you have one.' + #13#10 +
            'Choose No to keep them for a later reinstall.',
            mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
  begin
    DelTree(ExpandConstant('{userappdata}\FlowClone'), True, True, True);
    DelTree(ExpandConstant('{localappdata}\FlowClone\models'), True, True, True);
    RemoveDir(ExpandConstant('{localappdata}\FlowClone'));  { only if now empty }
  end;
end;
