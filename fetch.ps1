# Downloads the official (non-Steam) Mindustry server build used for compiling and for instances.
param(
    [string]$Version = "v160.6",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$libs = Join-Path $PSScriptRoot "libs"
$target = Join-Path $libs "server-release.jar"

New-Item -ItemType Directory -Force $libs | Out-Null

if((Test-Path $target) -and -not $Force){
    Write-Host "already present: $target"
    exit 0
}

Write-Host "downloading $Version/server-release.jar ..."
curl.exe -fL -o $target "https://github.com/Anuken/Mindustry/releases/download/$Version/server-release.jar"
if($LASTEXITCODE -ne 0){ throw "download failed" }

Get-Item $target | Select-Object FullName,Length
