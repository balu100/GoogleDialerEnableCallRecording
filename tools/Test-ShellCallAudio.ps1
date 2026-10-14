[CmdletBinding()]
param(
    [switch]$Record,
    [ValidateRange(1,120)][int]$Seconds = 20,
    [string]$Serial,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskOutput = Join-Path $taskWorkspace 'verification/clone/recording-failure/probe'
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
if (-not $taskPlatform -or -not $taskBuildTools -or -not $Jdk) { throw 'Install an Android SDK and JDK 17 or newer.' }
if (-not $Serial) {
    $taskDevices = @(& $taskAdb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($taskDevices.Count -ne 1) { throw 'Connect one authorized device or pass -Serial.' }
    $Serial = $taskDevices[0]
}
New-Item -ItemType Directory -Force -Path (Join-Path $taskOutput 'classes'),(Join-Path $taskOutput 'dex') | Out-Null
& (Join-Path $Jdk 'bin/javac.exe') --release 17 -classpath (Join-Path $taskPlatform.FullName 'android.jar') -d (Join-Path $taskOutput 'classes') (Join-Path $PSScriptRoot 'art-probe/ShellCallAudioProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed.' }
$taskClasses = Get-ChildItem -LiteralPath (Join-Path $taskOutput 'classes') -Filter 'ShellCallAudioProbe*.class' | ForEach-Object { $_.FullName }
& (Join-Path $Jdk 'bin/java.exe') -cp (Join-Path $taskBuildTools.FullName 'lib/d8.jar') com.android.tools.r8.D8 --min-api 31 --lib (Join-Path $taskPlatform.FullName 'android.jar') --output (Join-Path $taskOutput 'dex') @taskClasses
if ($LASTEXITCODE -ne 0) { throw 'Probe DEX compilation failed.' }
& $taskAdb -s $Serial push (Join-Path $taskOutput 'dex/classes.dex') '/data/local/tmp/google-phone-shell-audio-probe.dex'
if ($LASTEXITCODE -ne 0) { throw 'Probe transfer failed.' }
try {
    $taskArguments = 'probe'
    $taskFilename = 'google-phone-audio-test-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.wav'
    if ($Record) {
        Write-Output "Explicit test recording: $Seconds seconds. Start a test call before using -Record."
        $taskArguments = "record $Seconds /sdcard/Download/$taskFilename"
    }
    & $taskAdb -s $Serial shell "CLASSPATH=/data/local/tmp/google-phone-shell-audio-probe.dex app_process /system/bin ShellCallAudioProbe $taskArguments" |
        Tee-Object -FilePath (Join-Path $taskOutput 'latest-result.txt')
    if ($LASTEXITCODE -ne 0) { throw 'The shell audio probe failed; inspect latest-result.txt and audio-policy logs.' }
    if ($Record) {
        & $taskAdb -s $Serial pull "/sdcard/Download/$taskFilename" (Join-Path $taskOutput $taskFilename)
        if ($LASTEXITCODE -ne 0) { throw 'The test audio could not be retrieved.' }
        Write-Output "Playback file: $(Join-Path $taskOutput $taskFilename)"
    }
} finally {
    & $taskAdb -s $Serial shell rm -f '/data/local/tmp/google-phone-shell-audio-probe.dex'
}
