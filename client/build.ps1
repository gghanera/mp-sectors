# Builds mp-campaign.jar. Needs a JDK 17+ and a Mindustry client jar (libs\Mindustry.jar).
param(
    [string]$GameJar = ""
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$classes = Join-Path $root "build\classes"
$libs = Join-Path $root "build"

if(-not $GameJar){
    $local = Join-Path $root "libs\Mindustry.jar"
    $steam = "F:\SteamLibrary\steamapps\common\Mindustry\jre\desktop.jar"
    $GameJar = if(Test-Path $local){ $local } elseif(Test-Path $steam){ $steam } else { "" }
}
if(-not $GameJar -or -not (Test-Path $GameJar)){ throw "Mindustry client jar not found (put Mindustry.jar into libs\ or pass -GameJar)" }

Remove-Item -Recurse -Force (Join-Path $root "build") -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root "src") | ForEach-Object FullName
javac --release 17 -nowarn -cp $GameJar -d $classes $sources
if($LASTEXITCODE -ne 0){ throw "javac failed" }

Copy-Item (Join-Path $root "mod.hjson") $classes
Push-Location $classes
try {
    jar --create --file (Join-Path $libs "mp-campaign.jar") .
    if($LASTEXITCODE -ne 0){ throw "jar failed" }
} finally {
    Pop-Location
}

Write-Host "built: $libs\mp-campaign.jar"
