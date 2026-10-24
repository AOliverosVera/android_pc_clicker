<#
.SYNOPSIS
    Builds the PC server and the Android app into ..\dist.
.DESCRIPTION
    Produces dist\touchpad-server.exe, dist\touchpad-tray.exe (starts the
    server as an icon by the clock) and dist\Touchpad.apk (release build).
    Quit Touchpad from its icon first, or the old .exe files can't be replaced.
.PARAMETER Only
    Build only "server" or "android". Default: both.
#>
param(
    [ValidateSet("all", "server", "android")]
    [string]$Only = "all"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force $dist | Out-Null

# cargo and Gradle print progress on stderr. Windows PowerShell 5.1 turns that
# into errors under "Stop", so native tools are judged by exit code only.
function Invoke-Native {
    param([string]$What, [scriptblock]$Command)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Command } finally { $ErrorActionPreference = $saved }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}

function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { return $env:JAVA_HOME }
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr",
        "$env:ProgramFiles\Java\jdk-21",
        "$env:ProgramFiles\Eclipse Adoptium\jdk-21*",
        "$env:ProgramFiles\Java\jdk-17"
    )
    foreach ($pattern in $candidates) {
        $match = Get-Item $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($match -and (Test-Path "$($match.FullName)\bin\java.exe")) { return $match.FullName }
    }
    throw "No JDK 17-21 found. Install one (e.g. 'winget install EclipseAdoptium.Temurin.21.JDK') or set JAVA_HOME."
}

function Find-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if ($candidate -and (Test-Path "$candidate\platform-tools")) { return $candidate }
    }
    throw "Android SDK not found. See docs\BUILDING.md, or set ANDROID_HOME."
}

# Creates the release signing key on first use, outside the repository, in
# %USERPROFILE%\.touchpad, readable only by the current user. Back it up: an
# APK signed with a different key can't update the installed app.
function Initialize-SigningKey {
    $dir = Join-Path $env:USERPROFILE ".touchpad"
    $props = Join-Path $dir "signing.properties"
    if (Test-Path $props) { return }
    Write-Host "== Creating a private release signing key in $dir" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force $dir | Out-Null
    # Owner-only access: remove inherited permissions, grant just this user.
    $null = icacls $dir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F"
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes) -replace '[+/=]', ''
    $store = Join-Path $dir "release.p12"
    Invoke-Native "keytool" {
        & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $store -storetype PKCS12 `
            -alias touchpad -keyalg RSA -keysize 3072 -validity 10000 `
            -dname "CN=Touchpad personal build" -storepass $password -keypass $password
    }
    $storeForProps = $store -replace '\\', '/'
    @(
        "storeFile=$storeForProps"
        "storePassword=$password"
        "keyAlias=touchpad"
        "keyPassword=$password"
    ) | Set-Content -Path $props -Encoding ASCII
    Write-Host "   Back up $dir somewhere safe." -ForegroundColor Yellow
}

if ($Only -in "all", "server") {
    Write-Host "== Building PC server" -ForegroundColor Cyan
    $cargo = Get-Command cargo -ErrorAction SilentlyContinue
    if (-not $cargo) {
        $cargoPath = "$env:USERPROFILE\.cargo\bin\cargo.exe"
        if (-not (Test-Path $cargoPath)) { throw "Rust not found. Install it with 'winget install Rustlang.Rustup'." }
        $cargo = $cargoPath
    }
    Push-Location (Join-Path $root "pc-server")
    try {
        Invoke-Native "server tests" { & $cargo test --quiet }
        Invoke-Native "server build" { & $cargo build --release }
    } finally { Pop-Location }
    foreach ($exe in "touchpad-server.exe", "touchpad-tray.exe") {
        Copy-Item (Join-Path $root "pc-server\target\release\$exe") $dist -Force
        Write-Host "   -> dist\$exe" -ForegroundColor Green
    }
}

if ($Only -in "all", "android") {
    Write-Host "== Building Android app" -ForegroundColor Cyan
    $env:JAVA_HOME = Find-Jdk
    $env:ANDROID_HOME = Find-AndroidSdk
    Initialize-SigningKey
    $androidDir = Join-Path $root "android"
    # local.properties is machine-specific (git-ignored); colons must be escaped.
    $sdkForProps = ($env:ANDROID_HOME -replace '\\', '/') -replace ':', '\:'
    Set-Content -Path (Join-Path $androidDir "local.properties") -Value "sdk.dir=$sdkForProps" -Encoding ASCII
    # Never ship a stale APK left over from a previous build.
    Remove-Item (Join-Path $androidDir "app\build\outputs\apk\release") -Recurse -Force -ErrorAction SilentlyContinue
    Push-Location $androidDir
    try {
        Invoke-Native "android build" { & .\gradlew.bat test lintRelease assembleRelease }
    } finally { Pop-Location }
    $apk = Join-Path $androidDir "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) { throw "the release APK was not signed; check $env:USERPROFILE\.touchpad\signing.properties" }
    Copy-Item $apk (Join-Path $dist "Touchpad.apk") -Force
    Write-Host "   -> dist\Touchpad.apk" -ForegroundColor Green
}

Write-Host "Done." -ForegroundColor Green
