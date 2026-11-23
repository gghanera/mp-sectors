# Fresh-install check: an empty campaign folder must start and generate its first sector.
# Starts a throwaway coordinator twice (first run generates, second run loads the save).
# Run: .\test-fresh.ps1
param(
    [string]$ServerJar = (Join-Path $PSScriptRoot 'libs\server-release.jar'),
    [string]$ModJar = (Join-Path $PSScriptRoot 'build\mp-sectors.jar'),
    [string]$Java = 'java',
    [string]$Planet = 'serpulo',
    [string]$Moderator = 'FreshHost',
    [int]$HttpPort = 8196,
    [int]$GamePort = 6696,
    [int]$InstanceBasePort = 6720,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('mp-sectors-fresh-test-' + [guid]::NewGuid().ToString('N'))
$coordinator = Join-Path $root 'coordinator'
$campaign = Join-Path $root 'campaign'
$proc = $null

function Fail($message){
    Write-Host "FAIL: $message" -ForegroundColor Red
    exit 1
}

function Start-Coordinator($logName){
    $log = Join-Path $coordinator $logName
    return Start-Process -FilePath $Java -ArgumentList @('-Xmx512m', '-jar', $ServerJar) `
        -WorkingDirectory $coordinator -RedirectStandardOutput $log `
        -RedirectStandardError (Join-Path $coordinator ($logName + '.err')) -PassThru -WindowStyle Hidden
}

function Wait-Status($process){
    for($i = 0; $i -lt $TimeoutSeconds; $i++){
        Start-Sleep -Seconds 1
        if($process.HasExited){ return $null }
        try{
            return (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 3).Content | ConvertFrom-Json
        }catch{
        }
    }
    return $null
}

function Stop-Coordinator($process){
    if($process -eq $null -or $process.HasExited) { return }
    Set-Content (Join-Path $coordinator 'stop.flag') 'stop' -NoNewline -Encoding ascii
    if(-not $process.WaitForExit(30000)) { $process.Kill() }
    Start-Sleep -Seconds 2
}

try{
    New-Item -ItemType Directory -Force (Join-Path $coordinator 'config\mods') | Out-Null
    New-Item -ItemType Directory -Force $campaign | Out-Null
    Copy-Item $ModJar (Join-Path $coordinator 'config\mods\mp-sectors.jar') -Force

    $cfg = @{
        campaign = $campaign.Replace('\', '/')
        httpPort = $HttpPort
        gamePort = $GamePort
        maxSectors = 3
        maxPlayers = 5
        idleStopSeconds = 600
        autosaveSeconds = 60
        playerLimit = 5
        instanceBasePort = $InstanceBasePort
        memoryMb = 512
        updateCheck = $false
        planet = $Planet
        moderator = $Moderator
        serverJar = $ServerJar.Replace('\', '/')
        javaPath = $Java
    } | ConvertTo-Json
    Set-Content (Join-Path $coordinator 'config\mp-coordinator.json') $cfg -Encoding utf8

    # first run: no home.json, no saves
    $proc = Start-Coordinator 'first.log'
    $status = Wait-Status $proc
    if($status -eq $null){ Stop-Coordinator $proc; Fail 'coordinator did not start on an empty campaign' }

    $expected = if($Planet -eq 'erekir'){ 'erekir-10' } else { 'serpulo-170' }
    if($status.home -ne $expected){ Fail ("home is '$($status.home)', expected '$expected'") }
    $save = Join-Path $campaign ('sectors\' + $expected + '.msav')
    if(-not (Test-Path $save)){ Fail "first sector was not generated: $save" }
    if(-not (Test-Path (Join-Path $campaign ('info\' + $expected + '.json')))){ Fail 'sector info was not written' }
    $infos = @(Get-ChildItem (Join-Path $campaign 'info') -Filter '*.json')
    if($infos.Count -lt 20){ Fail ("campaign check created only $($infos.Count) sector info files") }
    $sectors = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/sectors" -TimeoutSec 10).Content | ConvertFrom-Json
    $homeEntry = $sectors.sectors | Where-Object { "$($_.planet)-$($_.id)" -eq $expected }
    if($homeEntry -eq $null){ Fail 'home sector is missing from /sectors' }
    if(-not $homeEntry.attacked){ Fail 'the endless home is not reported as attackable' }
    $mods = Get-Content (Join-Path $campaign 'moderators.json') -Raw | ConvertFrom-Json
    if($mods -notcontains $Moderator){ Fail "moderators.json does not contain '$Moderator'" }

    Stop-Coordinator $proc
    $proc = $null

    # second run: must load the generated save instead of regenerating the sector
    $proc = Start-Coordinator 'second.log'
    $status = Wait-Status $proc
    if($status -eq $null){ Stop-Coordinator $proc; Fail 'coordinator did not restart on the generated campaign' }
    if($status.home -ne $expected){ Fail ("home changed to '$($status.home)' on restart") }
    $saves = @(Get-ChildItem (Join-Path $campaign 'sectors') -Filter '*.msav')
    if($saves.Count -ne 1){ Fail ("expected one sector save after restart, found $($saves.Count)") }
    $secondLog = Get-Content (Join-Path $coordinator 'second.log') -Raw
    if($secondLog -match 'generated a new sector'){ Fail 'restart regenerated the existing sector' }

    Write-Host 'PASS: empty campaign bootstrap' -ForegroundColor Green
}finally{
    Stop-Coordinator $proc
    Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}
