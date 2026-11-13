# Creates the FlowKey release signing key ONCE, plus the key-rotation lineage that lets the
# release key take over from the debug key that signed v1.0-v1.1 (so installed apps update in
# place instead of needing an uninstall). Windows PowerShell 5.1. Contains no secrets.
#
# Outputs (all OUTSIDE the repository; back up all four, see android/README.md):
#   %USERPROFILE%\.android\flowkey-release.jks          release key (RSA 4096, PKCS12)
#   %USERPROFILE%\.android\flowkey-lineage.bin          debug -> release rotation proof
#   %USERPROFILE%\.android\debug.keystore               old key, still part of every signature
#   %USERPROFILE%\.gradle\flowkey-signing.properties    FLOWKEY_STORE_PASSWORD=...
#
# Usage:  powershell -ExecutionPolicy Bypass -File android\scripts\create-release-key.ps1

$ErrorActionPreference = 'Stop'

# SHA-256 of the debug certificate that signed every published v1.0-v1.1 APK.
$ExpectedDebugCert = '1b952da2c807ae62024d1eab5c9ca3a6e086ec61614cba412ecd54a9ae0357ba'

$jbr = Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'
$keytool = Join-Path $jbr 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { throw "keytool not found at $keytool (install Android Studio)" }
$env:JAVA_HOME = $jbr

$buildTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'
$bt = Get-ChildItem $buildTools -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName 'apksigner.bat')) } |
    Sort-Object { [version]$_.Name } | Select-Object -Last 1
if ($null -eq $bt) { throw "No build-tools with apksigner under $buildTools" }
$apksigner = Join-Path $bt.FullName 'apksigner.bat'

$dotAndroid = Join-Path $env:USERPROFILE '.android'
$debugKs = Join-Path $dotAndroid 'debug.keystore'
$releaseKs = Join-Path $dotAndroid 'flowkey-release.jks'
$lineage = Join-Path $dotAndroid 'flowkey-lineage.bin'
$gradleDir = Join-Path $env:USERPROFILE '.gradle'
$props = Join-Path $gradleDir 'flowkey-signing.properties'

# 1. The debug key must be the one that signed the published APKs, or rotation is useless.
if (-not (Test-Path $debugKs)) { throw "Missing $debugKs - the key that signed v1.0-v1.1" }
$listing = & $keytool -list -v -keystore $debugKs -storepass android -alias androiddebugkey
if ($LASTEXITCODE -ne 0) { throw "keytool could not read $debugKs" }
$line = $listing | Where-Object { $_ -match 'SHA256:' } | Select-Object -First 1
$actual = (($line -split 'SHA256:')[1] -replace '[:\s]', '').ToLower()
if ($actual -ne $ExpectedDebugCert) {
    throw "debug.keystore certificate $actual is not the one that signed FlowKey ($ExpectedDebugCert)"
}
Write-Host "Debug key matches the published APKs."

# 2. Release key. The password is saved BEFORE the key exists, so a failure later never
#    leaves a key nobody can open. Alphanumeric only: apksigner.bat re-parses arguments.
if (-not (Test-Path $releaseKs)) {
    if (-not (Test-Path $gradleDir)) { New-Item -ItemType Directory $gradleDir | Out-Null }
    $chars = [char[]]'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $bytes = New-Object byte[] 40
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pw = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
    [IO.File]::WriteAllText($props, "FLOWKEY_STORE_PASSWORD=$pw`n", (New-Object Text.UTF8Encoding $false))
    # Passed through the environment, not the command line (process lists and audit logs
    # record command lines).
    $env:FLOWKEY_STORE_PASSWORD = $pw
    & $keytool -genkeypair -keystore $releaseKs -storetype PKCS12 -alias flowkey -keyalg RSA `
        -keysize 4096 -validity 10000 -dname 'CN=FlowKey' `
        -storepass:env FLOWKEY_STORE_PASSWORD -keypass:env FLOWKEY_STORE_PASSWORD
    Remove-Item Env:\FLOWKEY_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'keytool -genkeypair failed' }
    Write-Host "Created release key $releaseKs"
} else {
    Write-Host "Release key already exists: $releaseKs (not touched)"
}

if (-not (Test-Path $props)) { throw "Release key exists but $props (its password) is missing" }
$pwLine = Get-Content $props | Where-Object { $_ -match '^FLOWKEY_STORE_PASSWORD=' } | Select-Object -First 1
if ($null -eq $pwLine) { throw "FLOWKEY_STORE_PASSWORD missing in $props" }
$env:FLOWKEY_STORE_PASSWORD = $pwLine.Substring('FLOWKEY_STORE_PASSWORD='.Length).Trim()

# 3. Lineage: debug key -> release key.
if (-not (Test-Path $lineage)) {
    & $apksigner rotate --out $lineage `
        --old-signer --ks $debugKs --ks-key-alias androiddebugkey --ks-pass pass:android `
        --new-signer --ks $releaseKs --ks-key-alias flowkey --ks-pass env:FLOWKEY_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'apksigner rotate failed' }
    Write-Host "Created rotation lineage $lineage"
} else {
    Write-Host "Lineage already exists: $lineage (not touched)"
}
Remove-Item Env:\FLOWKEY_STORE_PASSWORD

Write-Host ''
Write-Host 'Done. BACK UP these four files together (password manager or encrypted drive).'
Write-Host 'Losing any of them means future updates need an uninstall:'
Write-Host "  $releaseKs"
Write-Host "  $lineage"
Write-Host "  $debugKs"
Write-Host "  $props"
