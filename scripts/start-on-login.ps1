<#
.SYNOPSIS
    Starts Touchpad automatically when you log in to Windows, as an icon by
    the clock (no window), so the phone can connect at any time. Run with
    -Remove to undo. The same switch is in the icon's menu: "Start with Windows".
#>
param([switch]$Remove)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root "dist\touchpad-tray.exe"
$runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
# Older versions of this script used a minimized shortcut in the Startup folder.
$oldShortcut = Join-Path ([Environment]::GetFolderPath("Startup")) "Touchpad server.lnk"
Remove-Item $oldShortcut -ErrorAction SilentlyContinue

if ($Remove) {
    Remove-ItemProperty -Path $runKey -Name "Touchpad" -ErrorAction SilentlyContinue
    Write-Host "Removed from startup." -ForegroundColor Green
    exit
}

if (-not (Test-Path $exe)) { throw "dist\touchpad-tray.exe not found. Run scripts\build.ps1 first." }
Set-ItemProperty -Path $runKey -Name "Touchpad" -Value "`"$exe`""
Write-Host "Touchpad will start as an icon by the clock at every login." -ForegroundColor Green
