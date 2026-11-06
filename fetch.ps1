# Downloads the official Mindustry jars used for compiling and for the sector servers.
#   .\fetch.ps1           server-release.jar (server + coordinator)
#   .\fetch.ps1 -Client   Mindustry.jar (client mod builds)
#   .\fetch.ps1 -All      both
param(
    [string]$Version = "v160.7",
    [switch]$Force,
    [switch]$Client,
    [switch]$All
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Get-Jar($url, $target){
    New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null

    if((Test-Path $target) -and -not $Force){
        Write-Host "already present: $target"
        return
    }

    Write-Host "downloading $url ..."
    curl.exe -fL -o $target $url
    if($LASTEXITCODE -ne 0){ throw "download failed: $url" }

    Get-Item $target | Select-Object FullName,Length
}

$server = Join-Path $root "libs\server-release.jar"
$client = Join-Path $root "client\libs\Mindustry.jar"

if($All -or -not $Client){
    Get-Jar "https://github.com/Anuken/Mindustry/releases/download/$Version/server-release.jar" $server
}
if($All -or $Client){
    Get-Jar "https://github.com/Anuken/Mindustry/releases/download/$Version/Mindustry.jar" $client
}
