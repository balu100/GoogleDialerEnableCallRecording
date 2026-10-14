[CmdletBinding()]
param([switch]$BuildOnly)

$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskCache = Join-Path $workspace '.tooling'
$savedEnvironment = @{}
foreach ($name in @('JAVA_HOME','GRADLE_USER_HOME','GITHUB_ACTOR','GITHUB_TOKEN')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location -LiteralPath $workspace
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $jdk = Get-ChildItem -LiteralPath 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'jdk-*' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe')) } |
            Sort-Object Name -Descending | Select-Object -First 1
        if (-not $jdk) { throw 'Set JAVA_HOME to a JDK supported by Gradle 9.8.1 (tested with JDK 25).' }
        $env:JAVA_HOME = $jdk.FullName
    }
    New-Item -ItemType Directory -Force -Path $taskCache | Out-Null
    $upstream = @(
        @{ Repo='morphe-patcher'; Tag='v1.15.1'; Directory='morphe-patcher-1.15.1'; Hash='D9FB5ADF13D2ACE0CC6C9793CBAC63F2BC2AE4207A8B4AA04BABFCBD135AF5A8' },
        @{ Repo='morphe-patches-gradle-plugin'; Tag='v1.3.4'; Directory='morphe-patches-gradle-plugin-1.3.4'; Hash='4A1B08928F22E95A48A3C72DA5B30770ECE326DA9B1DE72E320A4036B33CE2D6' }
    )
    foreach ($source in $upstream) {
        $archive = Join-Path $taskCache ($source.Repo + '-' + $source.Tag + '.zip')
        if (-not (Test-Path -LiteralPath $archive)) {
            Invoke-WebRequest -Uri ('https://codeload.github.com/MorpheApp/' + $source.Repo + '/zip/refs/tags/' + $source.Tag) -OutFile $archive
        }
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $source.Hash) {
            throw ('Unexpected upstream archive checksum: ' + $source.Repo)
        }
        if (-not (Test-Path -LiteralPath (Join-Path $taskCache $source.Directory))) {
            Expand-Archive -LiteralPath $archive -DestinationPath $taskCache
        }
    }
    $env:GRADLE_USER_HOME = Join-Path $taskCache 'gradle-user-home'
    # Upstream 1.3.4 requires non-null environment fallbacks when applying its
    # settings plugin. These are deliberately unusable public placeholders.
    # The root settings remove GitHub Packages for this source-build mode.
    $env:GITHUB_ACTOR = 'local-source-unused'
    $env:GITHUB_TOKEN = 'local-source-unused'
    [string[]]$tasks = if ($BuildOnly) { @('buildAndroid') } else { @(':patches:test','buildAndroid',':patches:verifyDex') }
    & (Join-Path $workspace 'gradlew.bat') @tasks '-PlocalMorpheSources=true' '--console=plain' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw ('Gradle failed with exit code ' + $LASTEXITCODE) }
    $versionLine = Get-Content -LiteralPath (Join-Path $workspace 'gradle.properties') |
        Where-Object { $_ -match '^version\s*=' } | Select-Object -First 1
    $bundleVersion = ($versionLine -split '=', 2)[1].Trim()
    $distribution = Join-Path $workspace 'dist'
    New-Item -ItemType Directory -Force -Path $distribution | Out-Null
    Copy-Item -LiteralPath (Join-Path $workspace ('patches/build/libs/patches-' + $bundleVersion + '.mpp')) -Destination (Join-Path $distribution ('local-google-phone-patches-' + $bundleVersion + '.mpp')) -Force
    Write-Output ('Bundle ready: ' + $distribution)
} finally {
    Pop-Location
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
