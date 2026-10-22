[CmdletBinding()]
param(
    [string[]]$Tasks = @(':app:assembleGithubReleaseDebug'),
    [switch]$InstallWebDependencies
)

$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskArtifacts = [IO.Path]::GetFullPath((Join-Path $taskRepo '..\artifacts'))
if ([IO.Path]::GetPathRoot($taskArtifacts) -ne 'D:\') { throw 'This build configuration requires the D: workspace.' }
$taskTemporary = 'D:\rikka-validation-temp'
foreach ($taskDirectory in @($taskTemporary, $taskArtifacts, "$taskArtifacts\gradle-cache", "$taskArtifacts\pnpm-store", "$taskArtifacts\pnpm-cache", "$taskArtifacts\npm-cache", "$taskArtifacts\xdg-cache")) {
    New-Item -ItemType Directory -Path $taskDirectory -Force | Out-Null
}
$env:JAVA_HOME = 'D:\androids\jbr'
$env:ANDROID_HOME = 'D:\adroidsdk'
$env:GRADLE_USER_HOME = "$taskArtifacts\gradle-cache"
$env:TEMP = $taskTemporary
$env:TMP = $taskTemporary
$env:npm_config_cache = "$taskArtifacts\npm-cache"
$env:XDG_CACHE_HOME = "$taskArtifacts\xdg-cache"
$env:XDG_STATE_HOME = "$taskArtifacts\xdg-state"
$env:XDG_DATA_HOME = "$taskArtifacts\xdg-data"

Push-Location $taskRepo
try {
    if ($InstallWebDependencies) {
        Push-Location (Join-Path $taskRepo 'web-ui')
        try {
            & pnpm install --frozen-lockfile --store-dir "$taskArtifacts\pnpm-store" --cache-dir "$taskArtifacts\pnpm-cache"
            if ($LASTEXITCODE -ne 0) { throw 'Web dependency installation failed.' }
        } finally { Pop-Location }
    }
    & .\gradlew.bat @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with code $LASTEXITCODE." }
} finally { Pop-Location }
