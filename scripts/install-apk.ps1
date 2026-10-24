<#
.SYNOPSIS
    Installs dist\Touchpad.apk on a phone connected with USB debugging
    (or paired with Wireless debugging). See docs\BUILDING.md.
#>
# Not "Stop": adb prints progress on stderr, and exit codes are checked instead.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "dist\Touchpad.apk"
if (-not (Test-Path $apk)) { throw "dist\Touchpad.apk not found. Run scripts\build.ps1 first." }

$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path "$_\platform-tools\adb.exe") } | Select-Object -First 1
if (-not $sdk) { throw "adb not found. Install the Android SDK platform-tools." }
$adb = "$sdk\platform-tools\adb.exe"

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Host "No phone found. On the phone: Settings > Developer options > USB debugging, then plug it in" -ForegroundColor Yellow
    Write-Host "and accept the 'Allow USB debugging?' prompt. Unauthorized/offline devices:" -ForegroundColor Yellow
    & $adb devices
    exit 1
}

& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
& $adb shell monkey -p dev.playground.touchpad -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Installed and launched Touchpad." -ForegroundColor Green
