<#
.SYNOPSIS
    End-to-end test: the app's protocol code (Kotlin, on the JVM) pairs with and
    drives the real touchpad-server over UDP on this PC.
.DESCRIPTION
    Starts a throwaway server (its own temporary pairing store, so your real
    pairings are untouched), runs InteropTest, and answers the server's pairing
    prompt with "y" only if the code the server shows equals the code the test
    computed: the same comparison a person makes when pairing a phone.
    The test moves the pointer a few pixels and back. It never clicks.
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $root "pc-server\target\release\touchpad-server.exe"
if (-not (Test-Path $exe)) { throw "Build the server first: scripts\build.ps1 -Only server" }

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("touchpad-interop-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory $tmp | Out-Null
$codeFile = Join-Path $tmp "phone-code.txt"
$gradleLog = Join-Path $tmp "gradle.log"

$psi = New-Object Diagnostics.ProcessStartInfo $exe, "--pair --data-dir `"$tmp`""
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$server = [Diagnostics.Process]::Start($psi)

try {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "$env:ProgramFiles\Java\jdk-21" }
    $androidDir = Join-Path $root "android"
    $gradle = Start-Process -FilePath (Join-Path $androidDir "gradlew.bat") -WorkingDirectory $androidDir -PassThru -NoNewWindow `
        -RedirectStandardOutput $gradleLog `
        -ArgumentList "testDebugUnitTest", "--tests", "dev.playground.touchpad.net.InteropTest", "-PtouchpadInteropHost=127.0.0.1", "-PtouchpadInteropCodeFile=$codeFile"
    $null = $gradle.Handle # Windows PowerShell only records ExitCode if the handle was opened

    $serverCode = $null
    $answered = $false
    $line = $server.StandardOutput.ReadLineAsync()
    $deadline = (Get-Date).AddMinutes(10)
    while (-not $gradle.HasExited -and (Get-Date) -lt $deadline) {
        if ($line.Wait(200)) {
            if ($null -eq $line.Result) { throw "the server exited unexpectedly" }
            Write-Host "  server | $($line.Result)"
            if ($line.Result -match 'Code: (\d{3}) (\d{3})') { $serverCode = $Matches[1] + $Matches[2] }
            $line = $server.StandardOutput.ReadLineAsync()
        }
        if ($serverCode -and -not $answered -and (Test-Path $codeFile)) {
            $phoneCode = (Get-Content $codeFile -Raw).Trim()
            $match = $phoneCode -eq $serverCode
            Write-Host "  harness | server code $serverCode, phone code $phoneCode -> $(if ($match) { 'match: y' } else { 'MISMATCH: n' })"
            # Raw ASCII bytes: Windows PowerShell's stdin writer would prepend a UTF-8 BOM.
            $answer = [Text.Encoding]::ASCII.GetBytes($(if ($match) { "y`n" } else { "n`n" }))
            $server.StandardInput.BaseStream.Write($answer, 0, $answer.Length)
            $server.StandardInput.BaseStream.Flush()
            $answered = $true
        }
    }
    if (-not $gradle.HasExited) { $gradle.Kill(); throw "timed out" }
    $gradle.WaitForExit()

    $report = Get-ChildItem (Join-Path $androidDir "app\build\test-results\testDebugUnitTest") -Filter "*InteropTest.xml" | Select-Object -First 1
    [xml]$xml = Get-Content $report.FullName
    $suite = $xml.testsuite
    if ($gradle.ExitCode -eq 0 -and $suite.tests -eq "1" -and $suite.skipped -eq "0" -and $suite.failures -eq "0" -and $suite.errors -eq "0") {
        Write-Host "INTEROP TEST PASSED" -ForegroundColor Green
    } else {
        Get-Content $gradleLog | Select-Object -Last 30 | Write-Host
        throw "INTEROP TEST FAILED (tests=$($suite.tests) skipped=$($suite.skipped) failures=$($suite.failures) errors=$($suite.errors))"
    }
} finally {
    if (-not $server.HasExited) { $server.Kill() }
    $null = $server.WaitForExit(5000) # release the store file before deleting it
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
