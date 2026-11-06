# Builds a distributable zip: both mods, any-research, scripts and docs.
# Run the three build scripts first (or let the launcher build the server mod).
param(
    [string]$Version = "2.1.0"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "dist"
$stage = Join-Path $out "MP-Sectors-$Version"

$serverJar = Join-Path $root "build\mp-sectors.jar"
$clientJar = Join-Path $root "client\build\mp-campaign.jar"
$researchJar = Join-Path $root "any-research\build\any-research.jar"

foreach($jar in @($serverJar, $clientJar, $researchJar)){
    if(-not (Test-Path $jar)){ throw "missing $jar - build it first" }
}

Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $stage "mods") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "client-mods") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "docs") | Out-Null

Copy-Item $serverJar (Join-Path $stage "mods\mp-sectors.jar")
Copy-Item $clientJar (Join-Path $stage "mods\mp-campaign.jar")
Copy-Item $researchJar (Join-Path $stage "client-mods\any-research.jar")
Copy-Item (Join-Path $root "mp-sectors.ps1") $stage
Copy-Item (Join-Path $root "fetch.ps1") $stage
Copy-Item (Join-Path $root "build.ps1") $stage
Copy-Item (Join-Path $root "mod.hjson") $stage
Copy-Item (Join-Path $root "README.md") $stage
Copy-Item (Join-Path $root "README.ru.md") $stage
Copy-Item (Join-Path $root "docs\MP-Sectors-полное-описание.txt") (Join-Path $stage "docs")

$zip = Join-Path $out "MP-Sectors-$Version.zip"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip
Remove-Item -Recurse -Force $stage

Write-Host "built: $zip"
