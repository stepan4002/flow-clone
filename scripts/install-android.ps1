# FlowKey (Android) - install over USB from a Windows PC
#
#   irm https://raw.githubusercontent.com/stepan4002/flow-clone/main/scripts/install-android.ps1 | iex
#
# Needs: one Android phone connected by USB with USB debugging enabled, and adb
# (Android platform-tools). Downloads the latest FlowKey.apk from GitHub Releases,
# installs it, and opens the setup screen. It never uninstalls anything.
#
# No USB debugging? Just open this link on the phone instead and install the APK:
#   https://github.com/stepan4002/flow-clone/releases/latest/download/FlowKey.apk
#
# Optional: set FLOWKEY_APK to a local .apk path to skip the download.
# This file is ASCII-only on purpose so Windows PowerShell 5.1 parses it correctly.

function Install-FlowKey {
    $ErrorActionPreference = 'Stop'
    $ApkUrl = 'https://github.com/stepan4002/flow-clone/releases/latest/download/FlowKey.apk'

    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adb) {
        foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'))) {
            if ($sdk -and (Test-Path (Join-Path $sdk 'platform-tools\adb.exe'))) { $adb = Join-Path $sdk 'platform-tools\adb.exe'; break }
        }
    }
    if (-not $adb) {
        throw "adb was not found. Install Android platform-tools (https://developer.android.com/tools/releases/platform-tools) or simply open $ApkUrl on your phone."
    }

    $ErrorActionPreference = 'Continue'
    $state = & $adb -d get-state 2>&1
    $ok = ($LASTEXITCODE -eq 0 -and "$state" -match 'device')
    $ErrorActionPreference = 'Stop'
    if (-not $ok) {
        throw 'Connect ONE Android phone by USB, enable USB debugging (Settings > Developer options), unlock it and accept the authorization prompt.'
    }

    $apk = $env:FLOWKEY_APK
    $downloaded = $false
    if (-not $apk) {
        $apk = Join-Path ([IO.Path]::GetTempPath()) 'FlowKey.apk'
        Write-Host '==> Downloading FlowKey.apk (~270 MB, includes the speech models)' -ForegroundColor Cyan
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri $ApkUrl -OutFile $apk
        $downloaded = $true
    }

    try {
        Write-Host '==> Installing on the phone (existing FlowKey data is kept)' -ForegroundColor Cyan
        & $adb -d install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'Android rejected the install - read the message above. Nothing was removed.' }
        & $adb -d shell am start -n com.flowkey.ime/.SetupActivity | Out-Null
        Write-Host 'FlowKey is installed. Finish the 4 setup steps on the phone.' -ForegroundColor Green
    } finally {
        if ($downloaded) { Remove-Item $apk -Force -ErrorAction SilentlyContinue }
    }
}

try {
    Install-FlowKey
} catch {
    Write-Host "FlowKey install failed: $($_.Exception.Message)" -ForegroundColor Red
}
