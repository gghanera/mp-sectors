# Builds a distributable zip: server, mods, scripts and docs.
# Run the three build scripts first (or let the launcher build the server mod).
param(
    [string]$Version = "2.5.7"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

#the version is interpolated into paths under dist\, so keep it a plain 1.2.3
if($Version -notmatch '^\d+\.\d+\.\d+$'){ throw "invalid -Version '$Version' (expected 1.2.3)" }

$out = Join-Path $root "dist"
$stage = Join-Path $out "MP-Sectors-$Version"

$serverJar = Join-Path $root "build\mp-sectors.jar"
$clientJar = Join-Path $root "client\build\mp-campaign.jar"
$researchJar = Join-Path $root "any-research\build\any-research.jar"
$mindustryJar = Join-Path $root "libs\server-release.jar"

foreach($jar in @($serverJar, $clientJar, $researchJar, $mindustryJar)){
    if(-not (Test-Path $jar)){ throw "missing $jar - build it first (or run .\fetch.ps1)" }
}

Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $stage "mods") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "client-mods") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "build") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "libs") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "docs") | Out-Null

# the script looks in build\ first, mods\ is the fallback
Copy-Item $serverJar (Join-Path $stage "build\mp-sectors.jar")
Copy-Item $serverJar (Join-Path $stage "mods\mp-sectors.jar")
Copy-Item $clientJar (Join-Path $stage "mods\mp-campaign.jar")
Copy-Item $researchJar (Join-Path $stage "client-mods\any-research.jar")
Copy-Item $mindustryJar (Join-Path $stage "libs\server-release.jar")

Copy-Item (Join-Path $root "mp-sectors.ps1") $stage
Copy-Item (Join-Path $root "fetch.ps1") $stage
Copy-Item (Join-Path $root "build.ps1") $stage
Copy-Item (Join-Path $root "test-admin.ps1") $stage
Copy-Item (Join-Path $root "test-fresh.ps1") $stage
Copy-Item (Join-Path $root "mod.hjson") $stage
Copy-Item (Join-Path $root "README.md") $stage
Copy-Item (Join-Path $root "README.ru.md") $stage
Copy-Item (Join-Path $root "docs\*.txt") (Join-Path $stage "docs")

#the READMEs link to source folders that are not shipped in the zip; give them docs\ copies
Copy-Item (Join-Path $root "client\README.md") (Join-Path $stage "docs\client-README.md")
Copy-Item (Join-Path $root "any-research\README.md") (Join-Path $stage "docs\any-research-README.md")
Copy-Item (Join-Path $root "any-research\README.en.md") (Join-Path $stage "docs\any-research-README.en.md")

$linkFix = [ordered]@{
    'any-research/README.en.md' = 'docs/any-research-README.en.md'
    'any-research/README.md'    = 'docs/any-research-README.md'
    'client/README.md'          = 'docs/client-README.md'
}
foreach($name in @("README.md", "README.ru.md")){
    $path = Join-Path $stage $name
    $text = Get-Content $path -Raw
    foreach($key in $linkFix.Keys){ $text = $text.Replace($key, $linkFix[$key]) }
    Set-Content -Path $path -Value $text -NoNewline -Encoding utf8
}

$zip = Join-Path $out "MP-Sectors-$Version.zip"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip
Remove-Item -Recurse -Force $stage

Write-Host "built: $zip"
