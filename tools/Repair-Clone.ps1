[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$taskWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskSavedEnvironment = @{}
foreach ($taskName in @('JAVA_HOME','GRADLE_USER_HOME','GITHUB_ACTOR','GITHUB_TOKEN')) {
    $taskSavedEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
}
Push-Location -LiteralPath $taskWorkspace
try {
    if (-not (Test-Path -LiteralPath '.ignore/morphe.apk')) { throw 'Place the original Morphe clone at .ignore/morphe.apk.' }
    & (Join-Path $PSScriptRoot 'Build.ps1') -BuildOnly
    if ($LASTEXITCODE -ne 0) { throw 'Bundle build failed.' }
    if (-not $env:JAVA_HOME) {
        $taskJdk = Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } |
            Sort-Object Name -Descending | Select-Object -First 1
        $env:JAVA_HOME = $taskJdk.FullName
    }
    $env:GRADLE_USER_HOME = Join-Path $taskWorkspace '.tooling/gradle-user-home'
    $env:GITHUB_ACTOR = 'local-source-unused'
    $env:GITHUB_TOKEN = 'local-source-unused'
    & '.\gradlew.bat' ':patches:test' ':patches:verifyCloneDex' '-PlocalMorpheSources=true' '--console=plain' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw 'Clone verification failed.' }
    $taskVersion = ((Get-Content -LiteralPath 'gradle.properties' | Where-Object { $_ -match '^version\s*=' }) -split '=', 2)[1].Trim()
    Copy-Item -LiteralPath "patches/build/libs/patches-$taskVersion.mpp" -Destination 'dist/google-phone-droidguard-fix-candidate.mpp' -Force
    Write-Output 'Ready: dist/google-phone-droidguard-fix-candidate.mpp and dist/morphe-droidguard-fixed-unsigned.apk'
} finally {
    Pop-Location
    foreach ($taskName in $taskSavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($taskName, $taskSavedEnvironment[$taskName], 'Process')
    }
}

