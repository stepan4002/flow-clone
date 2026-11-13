# Builds and signs the FlowKey release APKs:
#   FlowKey.apk           with the bundled speech models
#   FlowKey-no-model.apk  without them (models copied to the phone by hand)
#
# Signing uses APK Signature Scheme v3 key rotation: the release key takes over from the debug
# key that signed v1.0-v1.1, so phones update in place. Run create-release-key.ps1 once first.
# Windows PowerShell 5.1.
#
# Usage:  powershell -ExecutionPolicy Bypass -File android\scripts\build-release.ps1 [-SkipNoModel]

param([switch]$SkipNoModel)

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

$pwLine = Get-Content $props | Where-Object { $_ -match '^FLOWKEY_STORE_PASSWORD=' } | Select-Object -First 1
$env:FLOWKEY_STORE_PASSWORD = $pwLine.Substring('FLOWKEY_STORE_PASSWORD='.Length).Trim()

function Invoke-Gradle {
    Push-Location $android
    try {
        & (Join-Path $android 'gradlew.bat') clean assembleRelease --offline --no-daemon -q
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
    & $apksigner sign `
        --ks $debugKs --ks-key-alias androiddebugkey --ks-pass pass:android `
        --next-signer --ks $releaseKs --ks-key-alias flowkey --ks-pass env:FLOWKEY_STORE_PASSWORD `
        --lineage $lineage --rotation-min-sdk-version 28 --v4-signing-enabled false `
        --out $out $unsigned
    if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed for $name" }
    & $apksigner verify -v --print-certs $out | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed for $name" }
    Write-Host "Signed $out"
}

try {
    Write-Host '==> Building FlowKey.apk (with models)'
    Invoke-Gradle
    Invoke-Sign 'FlowKey.apk'

    if (-not $SkipNoModel) {
        Write-Host '==> Building FlowKey-no-model.apk'
        $modelDir = Join-Path $assets 'model'
        $aside = Join-Path $android 'app\src\model-aside'
        if (Test-Path $aside) { throw "$aside exists - restore it into assets\model first" }
        Move-Item $modelDir $aside
        try {
            Invoke-Gradle
            Invoke-Sign 'FlowKey-no-model.apk'
        } finally {
            Move-Item $aside $modelDir
            $n = (Get-ChildItem $modelDir -Recurse -File).Count
            Write-Host "Restored $n bundled model files"
        }
    }
} finally {
    Remove-Item Env:\FLOWKEY_STORE_PASSWORD -ErrorAction SilentlyContinue
}

$lines = Get-ChildItem $outDir -Filter '*.apk' | Sort-Object Name | ForEach-Object {
    (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower() + ' *' + $_.Name
}
[IO.File]::WriteAllText((Join-Path $outDir 'SHA256SUMS.txt'), (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))
Write-Host "==> Release files in $outDir"
Get-ChildItem $outDir | Format-Table Name, Length -AutoSize | Out-Host
