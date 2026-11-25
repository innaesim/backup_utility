<#
.SYNOPSIS
    Installs backup-utility for the current user on Windows.

.DESCRIPTION
    Safe to re-run: it upgrades the jar and launcher, and never touches an existing config, env
    file or backups.

    It doesn't install database tools (run "backup-utility doctor --install" for the ones you need)
    and doesn't start a service (run "backup-utility service enable" when you're ready).

.PARAMETER Jar
    Install this jar instead of building one with Maven.

.PARAMETER Yes
    Answer yes to every prompt, including installing Java.

.PARAMETER Uninstall
    Remove the jar and launcher. Keeps your config and backups.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [switch]$Yes,
    [switch]$Uninstall
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$MinJava = 17
$DataDir = Join-Path $env:LOCALAPPDATA 'backup-utility'
$BinDir = Join-Path $DataDir 'bin'
$ConfigDir = if ($env:BACKUP_UTILITY_HOME) { $env:BACKUP_UTILITY_HOME } else { Join-Path $HOME '.backup-utility' }
$TaskName = 'BackupUtility'

function Stop-WithError([string]$Message) {
    [Console]::Error.WriteLine("Error: $Message")
    exit 1
}

# An empty answer counts as no, so nothing gets installed unattended without -Yes.
function Confirm-Step([string]$Question) {
    if ($Yes) { return $true }
    if (-not [Environment]::UserInteractive) { return $false }
    $answer = Read-Host "$Question [y/N]"
    return $answer -match '^(y|yes)$'
}

function Test-ServiceEnabled {
    & schtasks.exe /Query /TN $TaskName *> $null
    return $LASTEXITCODE -eq 0
}

# Windows PowerShell treats native stderr as errors, and java -version writes there, so cmd merges it.
function Get-JavaMajor([string]$JavaPath) {
    $output = cmd.exe /c "`"$JavaPath`" -version 2>&1"
    if ($LASTEXITCODE -ne 0) { return 0 }
    $match = [regex]::Match(($output -join "`n"), 'version "(\d+)')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return 0
}

function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($candidate in $candidates) {
        if ((Test-Path $candidate) -and ((Get-JavaMajor $candidate) -ge $MinJava)) { return $candidate }
    }
    return $null
}

# Installers update the registry, not this session's PATH.
function Update-SessionPath {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
}

# Without inheritance and with only this user, the file matches chmod 600.
function Set-OwnerOnly([string]$Path) {
    & icacls.exe $Path /inheritance:r /grant:r "$($env:USERNAME):(F)" *> $null
    if ($LASTEXITCODE -ne 0) { Write-Warning "Couldn't restrict access to $Path" }
}

# Without a byte order mark, which PowerShell 5's Set-Content would add and the YAML parser would reject.
function Write-NewPrivateFile([string]$Path, [string]$Content) {
    if (Test-Path $Path) {
        Write-Host "Kept existing: $Path"
        return
    }
    [IO.File]::WriteAllText($Path, $Content, (New-Object Text.UTF8Encoding($false)))
    Set-OwnerOnly $Path
    Write-Host "Created: $Path"
}

$JarPath = Join-Path $DataDir 'backup-utility.jar'
$Launcher = Join-Path $BinDir 'backup-utility.cmd'

if ($Uninstall) {
    if (Test-ServiceEnabled) {
        Stop-WithError "the service is still enabled. Run 'backup-utility service disable' first."
    }
    Remove-Item -Force -ErrorAction SilentlyContinue $Launcher, $JarPath
    Remove-Item -ErrorAction SilentlyContinue $BinDir
    Remove-Item -ErrorAction SilentlyContinue $DataDir
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath) {
        $kept = ($userPath -split ';') | Where-Object { $_ -and $_ -ne $BinDir }
        [Environment]::SetEnvironmentVariable('Path', ($kept -join ';'), 'User')
    }
    Write-Host 'Removed the launcher and jar.'
    Write-Host "Your config and logs are still in $ConfigDir, and your backups are wherever your jobs put them."
    exit 0
}

Write-Host 'Installing backup-utility for Windows'

$Java = Find-Java
if (-not $Java) {
    $install = 'winget install --exact --id Microsoft.OpenJDK.21'
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        Stop-WithError "Java $MinJava or newer is required. Install it from https://adoptium.net/, then re-run this script."
    }
    Write-Host "Java $MinJava or newer is required. It can be installed with:"
    Write-Host "  $install"
    if (-not (Confirm-Step 'Run this command now?')) {
        Stop-WithError "Java is required. To install it, run: $install"
    }
    & winget.exe install --exact --id Microsoft.OpenJDK.21 --accept-source-agreements --accept-package-agreements
    if ($LASTEXITCODE -ne 0) { Stop-WithError "'$install' failed with exit code $LASTEXITCODE" }
    Update-SessionPath
    $Java = Find-Java
    if (-not $Java) { Stop-WithError 'Java was installed but this session cannot find it. Open a new terminal, then re-run this script.' }
}
Write-Host "Java: $Java ($(Get-JavaMajor $Java))"

if ($Jar) {
    if (-not (Test-Path $Jar -PathType Leaf)) { Stop-WithError "jar not found: $Jar" }
    $SourceJar = $Jar
} else {
    if (-not (Test-Path (Join-Path $PSScriptRoot 'pom.xml'))) {
        Stop-WithError 'no pom.xml beside this script. Run it from a clone of the repository, or pass -Jar FILE.'
    }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        # winget has no official Maven package.
        Stop-WithError 'Maven is required to build. Install it from https://maven.apache.org/download.cgi, or pass -Jar FILE.'
    }
    Write-Host 'Building the jar (this can take a minute)...'
    Push-Location $PSScriptRoot
    try {
        & cmd.exe /c 'mvn -q -DskipTests package'
        if ($LASTEXITCODE -ne 0) { Stop-WithError 'the build failed. See the output above.' }
    } finally {
        Pop-Location
    }
    $SourceJar = Join-Path $PSScriptRoot 'target\backup-utility.jar'
    if (-not (Test-Path $SourceJar)) { Stop-WithError "the build finished but $SourceJar is missing." }
}

New-Item -ItemType Directory -Force -Path $DataDir, $BinDir, $ConfigDir | Out-Null

# Unlike Linux and macOS, Windows can't replace a jar that a running daemon has open.
try {
    Copy-Item -Force $SourceJar $JarPath
} catch [IO.IOException] {
    Stop-WithError "can't replace $JarPath because it's in use. Run 'backup-utility service disable', re-run this script, then run 'backup-utility service enable'."
}

# backup.utility.java is what 'service enable' records. %* passes arguments through. In batch files
# a literal percent sign is written twice.
$JavaForBatch = $Java.Replace('%', '%%')
$JarForBatch = $JarPath.Replace('%', '%%')
$LauncherContent = "@echo off`r`n`"$JavaForBatch`" `"-Dbackup.utility.java=$JavaForBatch`" -jar `"$JarForBatch`" %*`r`n"
[IO.File]::WriteAllText($Launcher, $LauncherContent, (New-Object Text.ASCIIEncoding))
Write-Host "Installed: $Launcher"

# Created empty rather than from the example: its placeholder jobs and unset variables would make
# every command fail.
Write-NewPrivateFile (Join-Path $ConfigDir 'config.yaml') "# backup-utility configuration. See config.example.yaml beside this file for every setting.`n"
Write-NewPrivateFile (Join-Path $ConfigDir 'env') "# Secrets for `${VAR} in config.yaml, one KEY=value per line. Keep this file private.`n"
$Example = Join-Path $PSScriptRoot 'config.example.yaml'
if (Test-Path $Example) { Copy-Item -Force $Example (Join-Path $ConfigDir 'config.example.yaml') }

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not $userPath) { $userPath = '' }
if (($userPath -split ';') -notcontains $BinDir) {
    Write-Host ''
    Write-Host "$BinDir isn't on your PATH."
    if (Confirm-Step 'Add it to your user PATH?') {
        [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ";$BinDir").TrimStart(';'), 'User')
        Write-Host 'Added. Open a new terminal for it to take effect.'
    } else {
        Write-Host "To run it without adding it, use: $Launcher"
    }
}

if (Test-ServiceEnabled) {
    Write-Host ''
    Write-Host 'A service is enabled. To be sure it uses this version:'
    Write-Host '  backup-utility service disable'
    Write-Host '  backup-utility service enable'
}

Write-Host ''
& cmd.exe /c "`"$Launcher`" doctor 2>nul"
if ($LASTEXITCODE -ne 0) { Stop-WithError "the installed program didn't run." }

Write-Host ''
Write-Host 'Done. Next steps:'
Write-Host '  1. Install the tools for your database:  backup-utility doctor --install'
Write-Host "  2. Add your jobs to:                     $(Join-Path $ConfigDir 'config.yaml')"
Write-Host '  3. Run a backup:                         backup-utility backup --job NAME'
Write-Host '  4. Start scheduled backups at startup:   backup-utility service enable'
