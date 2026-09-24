[CmdletBinding()]
param(
    [string]$Apk = "",
    [switch]$Install,
    [string]$Matrix = "docs/phase4/01-real-device-test-matrix.md",
    [string]$ResultsDir = "out/device-test",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
if ([System.IO.Path]::IsPathRooted($ResultsDir)) {
    $out = [System.IO.Path]::GetFullPath($ResultsDir)
} else {
    $out = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $ResultsDir))
}
[System.IO.Directory]::CreateDirectory($out) | Out-Null

function Get-AdbPath {
    if ($env:ANDROID_HOME) {
        $c = Join-Path $env:ANDROID_HOME "platform-tools/adb.exe"
        if (Test-Path -LiteralPath $c) { return $c }
    }
    if ($env:ANDROID_SDK_ROOT) {
        $c = Join-Path $env:ANDROID_SDK_ROOT "platform-tools/adb.exe"
        if (Test-Path -LiteralPath $c) { return $c }
    }
    $c = "C:\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path -LiteralPath $c) { return $c }
    $g = Get-Command adb -ErrorAction SilentlyContinue
    if ($g) { return $g.Source }
    return $null
}

$adb = Get-AdbPath
if (-not $adb) {
    Write-Host ""
    Write-Host "adb was not found."
    Write-Host "Install the Android SDK platform-tools and set ANDROID_HOME, or place adb.exe in C:\Android\Sdk\platform-tools."
    Write-Host ""
    Write-Host "WAITING FOR PHYSICAL DEVICE"
    exit 0
}

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb $Args 2>&1
}

$devices = Adb devices -l
$online = $devices | Where-Object { $_ -match "^\S+\s+device(\s|$)" }
if (-not $online) {
    Write-Host ""
    Write-Host "adb found at: $adb"
    Write-Host "No physical device is attached (adb devices shows none in the 'device' state)."
    Write-Host ""
    Write-Host "REQUIRES-VIKAS: To start the on-device run:"
    Write-Host "  1. Connect an Android phone/tablet and enable USB debugging (Settings > Developer options > USB debugging)."
    Write-Host "  2. Accept the RSA fingerprint prompt on the device if asked."
    Write-Host "  3. Re-run this script: .\scripts\phase4-real-device-test.ps1"
    Write-Host ""
    Write-Host "WAITING FOR PHYSICAL DEVICE"
    exit 0
}

Write-Host ""
Write-Host "Physical device detected:"
Write-Host (($online | Select-Object -First 1) | ForEach-Object { $_.ToString() })

function GetProp {
    param([string]$Key)
    (Adb shell getprop $Key).Trim()
}

$serial = (($online | Select-Object -First 1) -split "\s+")[0]
$model = GetProp ro.product.model
$manufacturer = GetProp ro.product.manufacturer
$brand = GetProp ro.product.brand
$androidVer = GetProp ro.build.version.release
$sdk = GetProp ro.build.version.sdk
$build = GetProp ro.build.display.id
$gms = GetProp gms-version-code
if (-not $gms) { $gms = (Adb shell pm list packages 2>$null | Select-String "com.google.android.gms") -ne $null }

$session = [ordered]@{
    serial = $serial
    manufacturer = $manufacturer
    brand = $brand
    model = $model
    androidVersion = $androidVer
    sdkLevel = $sdk
    build = $build
    playServicesDetected = ($gms -ne $null -and $gms -ne "")
    timestampUtc = (Get-Date).ToUniversalTime().ToString("o")
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sessionPath = Join-Path $out "session-$Stamp.json"
$session | ConvertTo-Json | Set-Content -LiteralPath $sessionPath -Encoding UTF8
Write-Host "Session facts -> $sessionPath"

if ($Apk -and $Install) {
    if (-not (Test-Path -LiteralPath $Apk)) {
        Write-Error "APK not found: $Apk"
    }
    Write-Host "Installing $Apk ..."
    Adb install -r $Apk
    $pkg = "dev.gamblock.shield"
    Write-Host "Granting notifications (and prompting VPN consent is a user action)."
    Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>$null | Out-Host
}

if (-not (Test-Path -LiteralPath $Matrix)) {
    Write-Warning "Matrix file not found: $Matrix"
    Write-Host ""
    Write-Host "REQUIRES-VIKAS: open docs/phase4/01-real-device-test-matrix.md when present and record the rows there."
    Write-Host "Script stop (no parsed matrix)."
    exit 0
}

$rows = @()
$section = "TOP"
$lines = Get-Content -LiteralPath $Matrix
foreach ($ln in $lines) {
    if ($ln -match "^##\s+(.*)$") { $section = $Matches[1].Trim(); continue }
    if ($ln -notmatch "^\|") { continue }
    if ($ln -match "^\|\s*-+\s*\|") { continue }
    $cells = $ln.Trim("|").Split("|") | ForEach-Object { $_.Trim() }
    if ($cells.Count -lt 2) { continue }
    $cols = @($cells)
    if ($cols[0] -match "^(#|ID)$") { continue }
    if ($cols.Count -ge 2 -and $cols[1] -eq "Pass criteria") { continue }
    $rows += [pscustomobject]@{
        section = $section
        id = $cols[0]
        text = $cols[1]
        pass = if ($cols.Count -ge 3) { $cols[2] } else { "" }
        result = "PENDING"
        notes = ""
    }
}

if ($rows.Count -eq 0) {
    Write-Warning "No table rows parsed from $Matrix"
    exit 0
}

Write-Host ""
Write-Host "Parsed $($rows.Count) matrix rows across sections:"
$rows | Group-Object section | ForEach-Object { Write-Host ("  {0}: {1} rows" -f $_.Name, $_.Count) }
Write-Host ""
Write-Host "REQUIRES-VIKAS: VpnService consent, notification permission, and any OEM battery settings must be done on the device first (see docs/phase4/02-oem-matrix.md)."
Write-Host "Driving the matrix interactively now:"
Write-Host "  P = Pass    S = Skip / not-applicable    F = Fail (records evidence below)   Q = Save and quit"
Write-Host ""

$failed = @()
foreach ($r in $rows) {
    $prompt = "[$($r.id)] $($r.text)"
    if ($r.pass) {
        $prompt += " | PASS: $($r.pass)"
    }
    do {
        $answer = Read-Host -Prompt "P/S/F/Q - $prompt"
        $answer = $answer.Trim().ToUpperInvariant()
    } while ($answer -notin @("P", "S", "F", "Q"))
    if ($answer -eq "Q") {
        Write-Host "Quit requested; saving partial results."
        break
    }
    $r.result = switch ($answer) {
        "P" { "PASS" }
        "S" { "SKIP" }
        "F" { "FAIL" }
    }
    if ($answer -eq "F") {
        $r.notes = Read-Host -Prompt "Evidence for $($r.id)"
        $failed += $r
    }
}

$partial = @($rows | Where-Object { $_.result -ne "PENDING" })
$csvPath = Join-Path $out "results-$Stamp.csv"
$partial | Select-Object section, id, text, pass, result, notes |
    ConvertTo-Csv -NoTypeInformation | Set-Content -LiteralPath $csvPath -Encoding UTF8
$jsonPath = Join-Path $out "results-$Stamp.json"
$partial | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

Write-Host ""
Write-Host "Summary at $Stamp"
Write-Host ("  Passed: {0}" -f ($partial | Where-Object result -eq "PASS" | Measure-Object).Count)
Write-Host ("  Skipped: {0}" -f ($partial | Where-Object result -eq "SKIP" | Measure-Object).Count)
Write-Host ("  Failed: {0}" -f ($partial | Where-Object result -eq "FAIL" | Measure-Object).Count)
Write-Host ("  Remaining: {0}" -f ($rows | Where-Object result -eq "PENDING" | Measure-Object).Count)
Write-Host ""
Write-Host "CSV: $csvPath"
Write-Host "JSON: $jsonPath"
Write-Host "Session: $sessionPath"
if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "Failed rows (attach to the Phase 4 report evidence):"
    $failed | ForEach-Object { Write-Host ("  [{0}] {1} -> {2}" -f $_.id, $_.text, $_.notes) }
    exit 1
}
if (($rows | Where-Object result -eq "PENDING" | Measure-Object).Count -gt 0) {
    Write-Host "Matrix incomplete. Re-run to finish the remaining rows."
    exit 2
}
Write-Host "All parsed matrix rows recorded."
exit 0