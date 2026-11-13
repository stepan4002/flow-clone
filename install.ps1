# FlowClone for Windows - one-line installer
#
#   irm https://raw.githubusercontent.com/stepan4002/flow-clone/main/install.ps1 | iex
#
# What it does (per-user, no admin rights needed):
#   1. Finds Python 3.10-3.12 (installs Python 3.12 with winget if none is found)
#   2. Downloads FlowClone into %LOCALAPPDATA%\FlowClone\app
#   3. Creates a private virtual environment and installs the requirements
#   4. Adds a "FlowClone" Start-menu shortcut and starts the app
#
# Optional environment variables:
#   FLOWCLONE_DIR        install location            (default %LOCALAPPDATA%\FlowClone)
#   FLOWCLONE_REF        git branch or tag to install (default main)
#   FLOWCLONE_SOURCE     install from a local checkout instead of downloading
#   FLOWCLONE_NO_START=1 do not launch the app after installing
#   FLOWCLONE_UNINSTALL=1 remove the app, shortcut and autostart entry
#                         (your settings in %APPDATA%\FlowClone are kept)
#
# This file is ASCII-only on purpose so Windows PowerShell 5.1 parses it correctly.

function Install-FlowClone {
    $ErrorActionPreference = 'Stop'
    $Repo = 'stepan4002/flow-clone'
    $Ref = if ($env:FLOWCLONE_REF) { $env:FLOWCLONE_REF } else { 'main' }
    $Dir = if ($env:FLOWCLONE_DIR) { $env:FLOWCLONE_DIR } else { Join-Path $env:LOCALAPPDATA 'FlowClone' }
    $AppDir = Join-Path $Dir 'app'
    $VenvDir = Join-Path $Dir 'venv'
    $Shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'FlowClone.lnk'

    function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

    function Stop-RunningFlowClone {
        Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine -like "*$AppDir*" } |
            ForEach-Object {
                Write-Step "Stopping the running FlowClone (PID $($_.ProcessId))"
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            }
    }

    if ($env:FLOWCLONE_UNINSTALL -eq '1') {
        Stop-RunningFlowClone
        Write-Step "Removing $Dir"
        if (Test-Path $Dir) { Remove-Item $Dir -Recurse -Force }
        if (Test-Path $Shortcut) { Remove-Item $Shortcut -Force }
        Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'FlowClone' -ErrorAction SilentlyContinue
        Write-Host "FlowClone was uninstalled. Settings and history remain in $env:APPDATA\FlowClone." -ForegroundColor Green
        return
    }

    # ---------- 1. Python ----------
    function Find-Python {
        $ErrorActionPreference = 'Continue'
        foreach ($v in '3.12', '3.11', '3.10') {
            try {
                $p = & py "-$v" -c "import sys; print(sys.executable)" 2>$null
                if ($LASTEXITCODE -eq 0 -and $p) { return "$p".Trim() }
            } catch { }
        }
        $candidates = @(
            (Get-Command python -ErrorAction SilentlyContinue).Source,
            "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
            "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
            "$env:LOCALAPPDATA\Programs\Python\Python310\python.exe"
        )
        foreach ($c in $candidates) {
            # Skip the Microsoft Store "python" alias, which only opens the Store.
            if (-not $c -or -not (Test-Path $c) -or $c -like '*WindowsApps*') { continue }
            try {
                $ver = & $c -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null
                if ($LASTEXITCODE -eq 0 -and @('3.10', '3.11', '3.12') -contains "$ver".Trim()) { return $c }
            } catch { }
        }
        return $null
    }

    Write-Step 'Looking for Python 3.10-3.12'
    $Python = Find-Python
    if (-not $Python) {
        if (Get-Command winget -ErrorAction SilentlyContinue) {
            Write-Step 'Python not found - installing Python 3.12 for this user with winget'
            winget install -e --id Python.Python.3.12 --scope user --silent --accept-package-agreements --accept-source-agreements
            $Python = Find-Python
        }
        if (-not $Python) {
            throw 'Python 3.10-3.12 is required. Install it from https://www.python.org/downloads/ (tick "Add python.exe to PATH"), then run this installer again.'
        }
    }
    Write-Host "    using $Python"

    # ---------- 2. Get the app files ----------
    Stop-RunningFlowClone
    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    $staging = Join-Path ([IO.Path]::GetTempPath()) ("flowclone-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    try {
        if ($env:FLOWCLONE_SOURCE) {
            Write-Step "Copying from local source $env:FLOWCLONE_SOURCE"
            $winSrc = Join-Path $env:FLOWCLONE_SOURCE 'windows'
        } else {
            Write-Step "Downloading FlowClone ($Ref) from github.com/$Repo"
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            $zip = Join-Path $staging 'src.zip'
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/$Repo/archive/$Ref.zip" -OutFile $zip
            Expand-Archive -Path $zip -DestinationPath $staging -Force
            $root = Get-ChildItem $staging -Directory | Select-Object -First 1
            $winSrc = Join-Path $root.FullName 'windows'
        }
        if (-not (Test-Path (Join-Path $winSrc 'run.py'))) { throw "Could not find windows\run.py in the source ($winSrc)." }
        if (Test-Path $AppDir) { Remove-Item $AppDir -Recurse -Force }
        Copy-Item $winSrc $AppDir -Recurse
        Get-ChildItem $AppDir -Recurse -Directory -Filter '__pycache__' | Remove-Item -Recurse -Force
        $testsDir = Join-Path $AppDir 'tests'
        if (Test-Path $testsDir) { Remove-Item $testsDir -Recurse -Force }
    } finally {
        Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    }

    # ---------- 3. Virtual environment + dependencies ----------
    $VenvPy = Join-Path $VenvDir 'Scripts\python.exe'
    $ErrorActionPreference = 'Continue'
    if (-not (Test-Path $VenvPy)) {
        Write-Step 'Creating a private Python environment'
        & $Python -m venv $VenvDir
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the virtual environment.' }
    }
    Write-Step 'Installing dependencies (first install takes a few minutes)'
    & $VenvPy -m pip install --disable-pip-version-check --quiet --upgrade pip
    & $VenvPy -m pip install --disable-pip-version-check --quiet -r (Join-Path $AppDir 'requirements.txt')
    if ($LASTEXITCODE -ne 0) {
        # Corporate proxies / antivirus HTTPS scanning: retry using the Windows certificate store.
        Write-Step 'Retrying with the Windows certificate store'
        & $VenvPy -m pip install --disable-pip-version-check --quiet --use-feature=truststore -r (Join-Path $AppDir 'requirements.txt')
        if ($LASTEXITCODE -ne 0) { throw 'Installing the Python dependencies failed. See the pip output above.' }
    }
    $ErrorActionPreference = 'Stop'

    # ---------- 4. Shortcut + launch ----------
    $Pythonw = Join-Path $VenvDir 'Scripts\pythonw.exe'
    $RunPy = Join-Path $AppDir 'run.py'
    Write-Step 'Adding a Start-menu shortcut'
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut($Shortcut)
    $lnk.TargetPath = $Pythonw
    $lnk.Arguments = "`"$RunPy`""
    $lnk.WorkingDirectory = $AppDir
    $lnk.Description = 'FlowClone - local voice dictation'
    $lnk.Save()

    Write-Host ''
    Write-Host 'FlowClone is installed.' -ForegroundColor Green
    Write-Host '  - Hold Ctrl+Win, speak, release: your words are typed into the focused app.'
    Write-Host '  - The first start downloads the speech model (~330 MB, one time).'
    Write-Host '  - Double-click the tray icon for settings; enable "Start with Windows" there.'
    Write-Host '  - Start it later from the Start menu: FlowClone.'
    if ($env:FLOWCLONE_NO_START -ne '1') {
        Start-Process -FilePath $Pythonw -ArgumentList "`"$RunPy`"" -WorkingDirectory $AppDir
        Write-Host '  - Starting FlowClone now - look for the tray icon near the clock.'
    }
}

try {
    Install-FlowClone
} catch {
    Write-Host ''
    Write-Host "FlowClone install failed: $($_.Exception.Message)" -ForegroundColor Red
}
