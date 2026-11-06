# Builds build/mp-sectors.jar against the official (non-Steam) server build.
param(
    [string]$ServerJar = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$classes = Join-Path $root "build\classes"
$libs = Join-Path $root "build"

if(-not $ServerJar){ $ServerJar = Join-Path $root "libs\server-release.jar" }
if(-not (Test-Path $ServerJar)){ throw "Mindustry server jar not found: $ServerJar (run .\fetch.ps1)" }

Remove-Item -Recurse -Force (Join-Path $root "build") -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root "src") | ForEach-Object FullName
javac --release 17 -nowarn -cp $ServerJar -d $classes $sources
if($LASTEXITCODE -ne 0){ throw "javac failed" }

Copy-Item (Join-Path $root "mod.hjson") $classes
Push-Location $classes
try {
    jar --create --file (Join-Path $libs "mp-sectors.jar") .
    if($LASTEXITCODE -ne 0){ throw "jar failed" }
} finally {
    Pop-Location
}

Write-Host "built: $libs\mp-sectors.jar"
