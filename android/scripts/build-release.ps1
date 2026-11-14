# Builds and signs the FlowKey release APKs:
#   FlowKey.apk           with the bundled speech models
#   FlowKey-no-model.apk  without them (models copied to the phone by hand)
#
# Signing uses APK Signature Scheme v3 key rotation: the release key takes over from the debug
# key that signed v1.0-v1.1, so phones update in place. Run create-release-key.ps1 once first.
# Windows PowerShell 5.1.
#
# Usage:  powershell -ExecutionPolicy Bypass -File android\scripts\build-release.ps1 [-SkipNoModel] [-TrustStore <jks>]
#
# -TrustStore: a Java truststore for networks that intercept TLS (Gradle downloads). Defaults to
# %USERPROFILE%\.flowkey-build\truststore.jks when that file exists (password 'changeit').

param([switch]$SkipNoModel, [string]$TrustStore = '')

$ErrorActionPreference = 'Stop'

$android = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $android 'app\src\main\assets'
$jbr = Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'
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
$props = Join-Path $env:USERPROFILE '.gradle\flowkey-signing.properties'
foreach ($f in @($debugKs, $releaseKs, $lineage, $props)) {
    if (-not (Test-Path $f)) { throw "Missing $f - run android\scripts\create-release-key.ps1 first" }
}

# Required build inputs (live text and hotwords silently disappear without them).
$required = @(
    (Join-Path $assets 'vad\silero_vad.onnx'),
    (Join-Path $assets 'hotwords\en-2023-06-26.bpe.vocab'),
    (Join-Path $assets 'model\whisper\base-encoder.int8.onnx'),
    (Join-Path $assets 'model\zipformer\tokens.txt')
)
foreach ($f in $required) {
    if (-not (Test-Path $f)) { throw "Missing $f - run android/fetch-deps.sh --model first" }
}

$gradleFile = Get-Content (Join-Path $android 'app\build.gradle.kts') -Raw
if ($gradleFile -notmatch 'versionName = "([^"]+)"') { throw 'versionName not found' }
$version = $Matches[1]
$outDir = Join-Path $android ("releases\" + (Get-Date -Format 'yyyy-MM-dd') + "-v$version")
New-Item -ItemType Directory -Force $outDir | Out-Null
# A re-run must never mix in APKs from an earlier build of the same day. Nothing is deleted:
# earlier APKs are moved to superseded\<time>\ next to the new ones.
# Virus scanners and the search indexer briefly lock fresh files in this folder: retry.
function Invoke-Retry([scriptblock]$action) {
    for ($i = 1; $i -le 10; $i++) {
        try { & $action; return } catch { if ($i -eq 10) { throw } ; Start-Sleep -Milliseconds 500 }
    }
}
$oldApks = @(Get-ChildItem $outDir -File -Filter '*.apk')
if ($oldApks.Count -gt 0) {
    $superseded = Join-Path $outDir ('superseded\' + (Get-Date -Format 'HHmmss'))
    New-Item -ItemType Directory -Force $superseded | Out-Null
    # (SHA256SUMS.txt is overwritten at the end; the copy here documents the old APKs.)
    $oldSums = Join-Path $outDir 'SHA256SUMS.txt'
    if (Test-Path $oldSums) { Copy-Item $oldSums $superseded }
    foreach ($apk in $oldApks) {
        $path = $apk.FullName
        Invoke-Retry { Move-Item $path $superseded -Force -ErrorAction Stop }
    }
    Write-Host "Moved $($oldApks.Count) earlier APK(s) to $superseded"
}

$pwLine = Get-Content $props | Where-Object { $_ -match '^FLOWKEY_STORE_PASSWORD=' } | Select-Object -First 1
if ($null -eq $pwLine) { throw "FLOWKEY_STORE_PASSWORD missing in $props" }
# Kept in a local variable; it is put into the environment only while apksigner runs, so the
# Gradle and Kotlin daemons never inherit it.
$storePassword = $pwLine.Substring('FLOWKEY_STORE_PASSWORD='.Length).Trim()

# Prefer a Gradle of the wrapper's version that is already unpacked (either -bin or -all):
# the wrapper insists on its exact flavour and would otherwise download it again.
$wrapperProps = Get-Content (Join-Path $android 'gradle\wrapper\gradle-wrapper.properties') -Raw
$gradleCmd = Join-Path $android 'gradlew.bat'
if ($wrapperProps -match 'gradle-([0-9.]+)-(bin|all)\.zip') {
    $gv = $Matches[1]
    $dists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
    $cached = Get-ChildItem $dists -Directory -Filter "gradle-$gv-*" -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName '*\gradle-*\bin\gradle.bat' } |
        ForEach-Object { Get-Item $_ -ErrorAction SilentlyContinue } | Select-Object -First 1
    if ($null -ne $cached) { $gradleCmd = $cached.FullName }
}
Write-Host "Gradle: $gradleCmd"

function Invoke-Gradle([string[]]$extra = @()) {
    Push-Location $android
    try {
        $ts = @()
        if ($TrustStore -eq '') {
            $default = Join-Path $env:USERPROFILE '.flowkey-build\truststore.jks'
            if (Test-Path $default) { $TrustStore = $default }
        }
        if ($TrustStore -ne '') {
            $ts = @("-Djavax.net.ssl.trustStore=$TrustStore", '-Djavax.net.ssl.trustStorePassword=changeit')
        }
        & $gradleCmd clean assembleRelease --no-daemon -q @ts @extra
        if ($LASTEXITCODE -ne 0) { throw 'gradle assembleRelease failed' }
    } finally {
        Pop-Location
    }
}

function Invoke-Sign([string]$name) {
    $unsigned = Join-Path $android 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (-not (Test-Path $unsigned)) { throw "Build output missing: $unsigned" }
    $out = Join-Path $outDir $name
    # Old (debug) key signs v1/v2/v3.0 for the lineage; the release key is the app's identity
    # from Android 9 (API 28) on. apksigner aligns the APK itself. No .idsig (v4) file.
    $env:FLOWKEY_STORE_PASSWORD = $storePassword
    try {
        & $apksigner sign `
            --ks $debugKs --ks-key-alias androiddebugkey --ks-pass pass:android `
            --next-signer --ks $releaseKs --ks-key-alias flowkey --ks-pass env:FLOWKEY_STORE_PASSWORD `
            --lineage $lineage --rotation-min-sdk-version 28 --v4-signing-enabled false `
            --out $out $unsigned
        if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed for $name" }
    } finally {
        Remove-Item Env:\FLOWKEY_STORE_PASSWORD -ErrorAction SilentlyContinue
    }
    & $apksigner verify -v --print-certs $out | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $name" }
    Write-Host "Signed $out"
}

try {
    Write-Host '==> Building FlowKey.apk (with models)'
    Invoke-Gradle
    Invoke-Sign 'FlowKey.apk'

    if (-not $SkipNoModel) {
        # -PnoModel leaves assets/model/ out of the APK (see app/build.gradle.kts); no files
        # are moved, so an interrupted build can never misplace the models.
        Write-Host '==> Building FlowKey-no-model.apk'
        Invoke-Gradle @('-PnoModel')
        Invoke-Sign 'FlowKey-no-model.apk'
    }
} finally {
    Remove-Item Env:\FLOWKEY_STORE_PASSWORD -ErrorAction SilentlyContinue
}

$lines = Get-ChildItem $outDir -Filter '*.apk' | Sort-Object Name | ForEach-Object {
    (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() + ' *' + $_.Name
}
$sums = Join-Path $outDir 'SHA256SUMS.txt'
Invoke-Retry { [IO.File]::WriteAllText($sums, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false)) }
Write-Host "==> Release files in $outDir"
Get-ChildItem $outDir | Format-Table Name, Length -AutoSize | Out-Host
