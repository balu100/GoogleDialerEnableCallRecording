[CmdletBinding()]
param(
    [ValidateSet('Begin','Collect')][string]$Phase = 'Collect',
    [string]$Serial,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
$taskOutput = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))) 'verification/clone/call-test'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
if (-not $Serial) {
    $taskDevices = @(& $Adb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($taskDevices.Count -ne 1) { throw 'Connect one authorized device or pass -Serial.' }
    $Serial = $taskDevices[0]
}
function Invoke-TaskAdb {
    param([string[]]$Arguments)
    $taskResult = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments[0])" }
    return $taskResult
}
$taskRole = Invoke-TaskAdb -Arguments @('shell','cmd','role','get-role-holders','--user','0','android.app.role.DIALER')
$taskRole | Set-Content -LiteralPath (Join-Path $taskOutput "$Phase-role.txt") -Encoding utf8
if ($Phase -eq 'Begin') {
    $taskSince = (Invoke-TaskAdb -Arguments @('shell','date','+%m-%d_%H:%M:%S.000')).Trim().Replace('_',' ')
    @{ serial=$Serial; since=$taskSince } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $taskOutput 'window.json') -Encoding utf8
    Invoke-TaskAdb -Arguments @('shell','dumpsys','package','com.google.android.dialer.morphe') |
        Set-Content -LiteralPath (Join-Path $taskOutput 'before-package.txt') -Encoding utf8
    Write-Output "Log window started at $taskSince. Current dialer: $taskRole"
    Write-Output 'Manually test an outgoing call, an incoming call, and recording/playback. Then run -Phase Collect.'
    return
}
$taskWindow = Get-Content -LiteralPath (Join-Path $taskOutput 'window.json') -Raw | ConvertFrom-Json
if ($taskWindow.serial -ne $Serial) { throw 'Collect must use the same device as Begin.' }
$taskLogLines = @(Invoke-TaskAdb -Arguments @('logcat','-d','-v','threadtime','-T',$taskWindow.since))
Set-Content -LiteralPath (Join-Path $taskOutput 'call-window.log') -Value ($taskLogLines -join [Environment]::NewLine) -Encoding utf8
$taskCrashLines = @(Invoke-TaskAdb -Arguments @('logcat','-b','crash','-d','-v','threadtime','-T',$taskWindow.since))
Set-Content -LiteralPath (Join-Path $taskOutput 'crash-window.log') -Value ($taskCrashLines -join [Environment]::NewLine) -Encoding utf8
Invoke-TaskAdb -Arguments @('shell','dumpsys','telecom') |
    Set-Content -LiteralPath (Join-Path $taskOutput 'after-telecom.txt') -Encoding utf8
Invoke-TaskAdb -Arguments @('shell','dumpsys','package','com.google.android.dialer.morphe') |
    Set-Content -LiteralPath (Join-Path $taskOutput 'after-package.txt') -Encoding utf8
$taskCrash = Get-Content -LiteralPath (Join-Path $taskOutput 'crash-window.log') -Raw
$taskCloneCrash = $taskCrash -match 'Process: com\.google\.android\.dialer\.morphe,'
$taskDiagnostics = @(Get-Content -LiteralPath (Join-Path $taskOutput 'call-window.log') |
    Select-String -Pattern 'GooglePhone.CloneRepair|FATAL EXCEPTION|PackageVerificationRslt|com.google.android.dialer.morphe/com.android.incallui.InCallServiceImpl|CallRecorder|CanRecord|CAPTURE_AUDIO|Permission Denial' |
    ForEach-Object { $_.Line })
Set-Content -LiteralPath (Join-Path $taskOutput 'diagnostics.txt') -Value ($taskDiagnostics -join [Environment]::NewLine) -Encoding utf8
Write-Output "Clone crash recorded in this window: $taskCloneCrash"
Write-Output "Current dialer: $taskRole; evidence: $taskOutput"
Write-Output 'Confirm both call directions stayed on the clone and both voices are audible in playback. Log silence alone is not a passing call/recording test.'
