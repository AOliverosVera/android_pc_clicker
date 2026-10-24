<#
.SYNOPSIS
    Starts touchpad-server automatically when you log in to Windows, so the
    phone can connect at any time. Run with -Remove to undo.
#>
param([switch]$Remove)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root "dist\touchpad-server.exe"
$shortcut = Join-Path ([Environment]::GetFolderPath("Startup")) "Touchpad server.lnk"

if ($Remove) {
    Remove-Item $shortcut -ErrorAction SilentlyContinue
    Write-Host "Removed from startup." -ForegroundColor Green
    exit
}

if (-not (Test-Path $exe)) { throw "dist\touchpad-server.exe not found. Run scripts\build.ps1 first." }
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.WorkingDirectory = Split-Path $exe
$link.WindowStyle = 7 # minimized
$link.Description = "Touchpad server for the Android Touchpad app"
$link.Save()
Write-Host "touchpad-server will start minimized at every login." -ForegroundColor Green
