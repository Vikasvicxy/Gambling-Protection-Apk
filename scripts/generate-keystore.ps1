<#
.SYNOPSIS
    Generates the production release keystore for Shield and writes keystore.properties.

.DESCRIPTION
    Creates a 4096-bit RSA release key using keytool and records the connection
    details in keystore.properties (git-ignored). The script is idempotent: if a
    keystore already exists it is NEVER regenerated, because rotating the upload
    key makes an already-published app impossible to update.

    Passwords are taken from the environment when present, otherwise generated
    from a cryptographic RNG:
        SHIELD_KEYSTORE_PASSWORD   - keystore/store password
        SHIELD_KEY_PASSWORD        - key password (defaults to the store password)

.PARAMETER Alias
    Key alias. Defaults to 'shield-release'.

.PARAMETER ValidityDays
    Certificate validity in days. Defaults to 10000 (~27 years). Google Play
    requires the upload key to stay valid past 22 October 2033.

.PARAMETER Dname
    Certificate distinguished name.

.PARAMETER KeystorePath
    Destination keystore. Defaults to <repo>/release-keys/shield-release.p12.

.EXAMPLE
    ./scripts/generate-keystore.ps1
    ./scripts/generate-keystore.ps1 -ValidityDays 10950
#>
[CmdletBinding()]
param(
    [string] $Alias = 'shield-release',
    [int]    $ValidityDays = 10000,
    [string] $Dname = 'CN=Shield, OU=Mobile, O=Gamblock, L=Bengaluru, ST=Karnataka, C=IN',
    [string] $KeystorePath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($KeystorePath)) {
    $KeystorePath = Join-Path $repoRoot 'release-keys/shield-release.p12'
}
$propertiesPath = Join-Path $repoRoot 'keystore.properties'

function Write-Step { param([string] $Message) Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok   { param([string] $Message) Write-Host "    $Message" -ForegroundColor Green }
function Write-Note { param([string] $Message) Write-Host "    $Message" -ForegroundColor Yellow }
function Write-Fail { param([string] $Message) Write-Host "    $Message" -ForegroundColor Red }

function Resolve-Keytool {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
        if (-not $candidate.EndsWith('keytool.exe')) {
            $candidate = Join-Path $env:JAVA_HOME 'bin/keytool'
        }
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $onPath = Get-Command 'keytool' -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw 'keytool not found. Install a JDK 17+ and set JAVA_HOME, or put keytool on PATH.'
}

function New-RandomPassword {
    param([int] $Length = 28)
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $bytes = [byte[]]::new($Length)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $builder = [System.Text.StringBuilder]::new($Length)
    foreach ($b in $bytes) { [void] $builder.Append($alphabet[$b % $alphabet.Length]) }
    return $builder.ToString()
}

Write-Step 'Shield release keystore setup'

if (Test-Path -LiteralPath $KeystorePath) {
    Write-Note "Keystore already exists: $KeystorePath"
    Write-Fail  'Refusing to overwrite. Replacing the upload key would break updates'
    Write-Fail  'for any version already published to Google Play.'
    Write-Ok    'Delete the file yourself only if you are certain it is unused.'
    if (-not (Test-Path -LiteralPath $propertiesPath)) {
        Write-Fail  'keystore.properties is missing, so the build cannot sign with this key.'
        Write-Fail  'Re-run with the passwords you originally used, or recreate the key.'
    }
    exit 1
}

$keytool = Resolve-Keytool
Write-Ok "keytool: $keytool"

$storePassword = if ($env:SHIELD_KEYSTORE_PASSWORD) { $env:SHIELD_KEYSTORE_PASSWORD } else { New-RandomPassword }
$keyPassword   = if ($env:SHIELD_KEY_PASSWORD) { $env:SHIELD_KEY_PASSWORD } else { $storePassword }
if ($keyPassword.Length -lt 6 -or $storePassword.Length -lt 6) {
    throw 'Keystore and key passwords must be at least 6 characters.'
}
if ($ValidityDays -lt 365) {
    throw "ValidityDays must be at least 365 (got $ValidityDays)."
}

$keyDir = Split-Path -Parent $KeystorePath
if (-not (Test-Path -LiteralPath $keyDir)) {
    [void] (New-Item -ItemType Directory -Path $keyDir -Force)
    Write-Ok "created $keyDir"
}

Write-Step 'Generating 4096-bit RSA key pair'
Write-Ok "alias=$Alias validity=${ValidityDays}d storetype=PKCS12"

# PKCS12 derives the key password from the store password, so they must match.
# Passing both keeps the intent explicit and fails loudly if that ever changes.
$keytoolArgs = @(
    '-genkeypair',
    '-keystore', $KeystorePath,
    '-storetype', 'PKCS12',
    '-alias', $Alias,
    '-keyalg', 'RSA',
    '-keysize', '4096',
    '-sigalg', 'SHA256withRSA',
    '-validity', "$ValidityDays",
    '-dname', $Dname,
    '-storepass', $storePassword,
    '-keypass', $keyPassword
)

& $keytool @keytoolArgs
if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE. The keystore may be partially written; delete '$KeystorePath' and retry."
}
Write-Ok "wrote $KeystorePath"

Write-Step 'Verifying the key'
$verifyArgs = @(
    '-list', '-v',
    '-keystore', $KeystorePath,
    '-alias', $Alias,
    '-storepass', $storePassword
)
& $keytool @verifyArgs | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Verification failed; '$KeystorePath' is not usable." }
Write-Ok 'key verifies successfully'

Write-Step 'Writing keystore.properties'
$relativeStore = [System.IO.Path]::GetRelativePath($repoRoot, $KeystorePath).Replace('\', '/')
$properties = @(
    '# Shield release signing. Generated by scripts/generate-keystore.ps1.'
    '# Never commit this file - it is covered by .gitignore.'
    "storeFile=$relativeStore"
    "storePassword=$storePassword"
    "keyAlias=$Alias"
    "keyPassword=$keyPassword"
)
# Write with restrictive permissions where the platform supports it.
Set-Content -LiteralPath $propertiesPath -Value $properties -Encoding UTF8
if ($IsLinux -or $IsMacOS) {
    & chmod 600 $propertiesPath
}
Write-Ok "wrote $propertiesPath"

Write-Host ''
Write-Host 'Next steps:' -ForegroundColor Cyan
Write-Host "  1. Back up '$KeystorePath' and keystore.properties somewhere you will"
Write-Host '     NOT lose them. Losing the upload key means the published app can'
Write-Host '     never be updated again.'
Write-Host '  2. Play Console > App integrity > App signing: upload this key and'
Write-Host '     enrol it in Play App Signing.'
Write-Host '  3. ./gradlew bundleRelease   (keystore.properties is picked up automatically)'
Write-Host ''
Write-Note 'Passwords are shown once. Store them in a password manager now.'
Write-Host ''
foreach ($line in $properties) { if ($line -match 'Password=') { Write-Host "    $line" -ForegroundColor DarkGray } }

