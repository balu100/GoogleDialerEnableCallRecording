[CmdletBinding()]
param(
    [string]$Package = 'com.google.android.dialer.morphe',
    [ValidateRange(1,120)][int]$Minutes = 30,
    [string]$Serial,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if ($Package -notmatch '^[A-Za-z0-9_.]+$') { throw 'Invalid package name.' }
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOutput = Join-Path $taskWorkspace 'verification/adb-audio/helper'
$taskAdb = Join-Path $Sdk 'platform-tools/adb.exe'
if (-not $Jdk) {
    $Jdk = (Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$taskPlatform = Get-ChildItem -LiteralPath (Join-Path $Sdk 'platforms') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar') } |
    Sort-Object Name -Descending | Select-Object -First 1
$taskBuildTools = Get-ChildItem -LiteralPath (Join-Path $Sdk 'build-tools') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'lib/d8.jar') } |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $taskPlatform -or -not $taskBuildTools -or -not $Jdk) { throw 'Install an Android SDK (API 31+) and JDK 17+.' }
if (-not $Serial) {
    $taskDevices = @(& $taskAdb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($taskDevices.Count -ne 1) { throw 'Connect one authorized device or pass -Serial.' }
    $Serial = $taskDevices[0]
}
$taskExisting = & $taskAdb -s $Serial shell pidof GooglePhone.AdbAudio
if ($taskExisting) { throw 'An ADB audio helper is already running. Stop its existing terminal first.' }
New-Item -ItemType Directory -Force -Path (Join-Path $taskOutput 'classes'),(Join-Path $taskOutput 'dex') | Out-Null
& (Join-Path $Jdk 'bin/javac.exe') --release 17 -classpath (Join-Path $taskPlatform.FullName 'android.jar') -d (Join-Path $taskOutput 'classes') (Join-Path $PSScriptRoot 'art-probe/ShellCallAudioServer.java')
if ($LASTEXITCODE -ne 0) { throw 'Helper compilation failed.' }
$taskClasses = @(Get-ChildItem -LiteralPath (Join-Path $taskOutput 'classes') -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $Jdk 'bin/java.exe') -cp (Join-Path $taskBuildTools.FullName 'lib/d8.jar') com.android.tools.r8.D8 --min-api 31 --lib (Join-Path $taskPlatform.FullName 'android.jar') --output (Join-Path $taskOutput 'dex') @taskClasses
if ($LASTEXITCODE -ne 0) { throw 'Helper DEX compilation failed.' }
& $taskAdb -s $Serial push (Join-Path $taskOutput 'dex/classes.dex') '/data/local/tmp/google-phone-adb-audio.dex'
if ($LASTEXITCODE -ne 0) { throw 'Helper upload failed.' }
$taskHelperPid = $null
try {
    Write-Output 'Leave this terminal open. Only the patched Phone Record button starts capture. Ctrl+C stops the helper.'
    & $taskAdb -s $Serial shell "CLASSPATH=/data/local/tmp/google-phone-adb-audio.dex app_process /system/bin --nice-name=GooglePhone.AdbAudio ShellCallAudioServer $Package $Minutes" |
        ForEach-Object {
            if ($_ -match '^HELPER_PID=(\d+)$') { $taskHelperPid = [int]$Matches[1] }
            $_
        }
    if ($LASTEXITCODE -ne 0) { throw 'ADB helper failed.' }
} finally {
    if ($taskHelperPid) {
        $taskCommand = & $taskAdb -s $Serial shell "cat /proc/$taskHelperPid/cmdline"
        if (($taskCommand -replace "`0", '') -eq 'GooglePhone.AdbAudio') {
            & $taskAdb -s $Serial shell kill $taskHelperPid
        }
    }
    & $taskAdb -s $Serial shell rm -f '/data/local/tmp/google-phone-adb-audio.dex'
}
