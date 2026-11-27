# Downloads the official Mindustry jars used for compiling and for the sector servers.
#   .\fetch.ps1           server-release.jar (server + coordinator)
#   .\fetch.ps1 -Client   Mindustry.jar (client mod builds)
#   .\fetch.ps1 -All      both
param(
    [string]$Version = "v160.8",
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

    #download to .part so an interrupted transfer never looks like a finished jar
    $part = "$target.part"
    Remove-Item -Force $part -ErrorAction SilentlyContinue

    Write-Host "downloading $url ..."
    try{
        curl.exe -fL -o $part $url
        if($LASTEXITCODE -ne 0){ throw "download failed: $url" }
        Move-Item -Force $part $target
    }catch{
        Remove-Item -Force $part -ErrorAction SilentlyContinue
        throw
    }

    Get-Item $target | Select-Object FullName,Length
}

$serverJar = Join-Path $root "libs\server-release.jar"
$clientJar = Join-Path $root "client\libs\Mindustry.jar"

if($All -or -not $Client){
    Get-Jar "https://github.com/Anuken/Mindustry/releases/download/$Version/server-release.jar" $serverJar
}
if($All -or $Client){
    Get-Jar "https://github.com/Anuken/Mindustry/releases/download/$Version/Mindustry.jar" $clientJar
}
