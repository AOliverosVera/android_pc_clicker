<#
.SYNOPSIS
    Lets the phone reach touchpad-server through Windows Firewall.
.DESCRIPTION
    Adds the narrowest rule that works: inbound UDP 47820, only for
    dist\touchpad-server.exe, only from the local subnet, only on networks
    Windows marks as Private. Re-launches itself as administrator if needed.
    Run it once, and again if you move the project folder.
#>
$ErrorActionPreference = "Stop"
$ruleName = "Touchpad server (UDP 47820)"
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root "dist\touchpad-server.exe"
if (-not (Test-Path $exe)) { throw "dist\touchpad-server.exe not found. Run scripts\build.ps1 first." }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
    IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule

# Cancelling Windows' "allow access?" prompt the first time the server runs
# creates a Block rule for the exe, and Block always wins over Allow.
$blocked = Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue |
    Where-Object { $_.Program -like "*touchpad-server.exe" } |
    Get-NetFirewallRule | Where-Object { $_.Action -eq "Block" }
if ($blocked) {
    $blocked | Remove-NetFirewallRule
    Write-Host "Removed $(@($blocked).Count) rule(s) that were blocking touchpad-server.exe." -ForegroundColor Yellow
}

New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
    -Program $exe -Protocol UDP -LocalPort 47820 -RemoteAddress LocalSubnet -Profile Private | Out-Null

$profiles = Get-NetConnectionProfile | Select-Object Name, InterfaceAlias, NetworkCategory
Write-Host "Firewall rule added: $exe, UDP 47820, local subnet, Private networks only." -ForegroundColor Green
Write-Host "Your current networks:"
$profiles | Format-Table -AutoSize | Out-String | Write-Host
if ($profiles | Where-Object { $_.NetworkCategory -eq "Public" }) {
    Write-Host "A network is marked Public, so the rule does not apply there. If it is your home" -ForegroundColor Yellow
    Write-Host "Wi-Fi, set it to Private: Settings > Network & internet > Wi-Fi > (network) > Private." -ForegroundColor Yellow
}
Read-Host "Press Enter to close"
