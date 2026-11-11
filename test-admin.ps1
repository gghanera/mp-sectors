# Minimal end-to-end check for the moderator admin endpoint.
# Starts a throwaway coordinator on a temp copy of the campaign, calls /admin, then stops it.
# Run: .\test-admin.ps1
param(
    [string]$ServerJar = (Join-Path $PSScriptRoot 'libs\server-release.jar'),
    [string]$ModJar = (Join-Path $PSScriptRoot 'build\mp-sectors.jar'),
    [string]$Campaign = (Join-Path $PSScriptRoot 'campaign'),
    [string]$Java = 'java',
    [int]$HttpPort = 8199,
    [int]$GamePort = 6699,
    [int]$InstanceBasePort = 6700
)

$ErrorActionPreference = 'Stop'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('mp-sectors-admin-test-' + [guid]::NewGuid().ToString('N'))
$coordinator = Join-Path $root 'coordinator'
$proc = $null

function Fail($message){
    Write-Host "FAIL: $message" -ForegroundColor Red
    exit 1
}

function Admin($body){
    return (Invoke-WebRequest -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$HttpPort/admin" `
        -ContentType 'application/json' -Body $body -TimeoutSec 180).Content | ConvertFrom-Json
}

try{
    New-Item -ItemType Directory -Force (Join-Path $coordinator 'config\mods') | Out-Null
    Copy-Item $Campaign (Join-Path $root 'campaign') -Recurse -Force
    Copy-Item $ModJar (Join-Path $coordinator 'config\mods\mp-sectors.jar') -Force

    #the dev campaign may have extra moderators; make the last-moderator check deterministic
    Set-Content (Join-Path $root 'campaign\moderators.json') '["GGhanera"]' -NoNewline -Encoding utf8

    $cfg = @{
        campaign = (Join-Path $root 'campaign').Replace('\', '/')
        httpPort = $HttpPort
        gamePort = $GamePort
        maxSectors = 5
        maxPlayers = 10
        idleStopSeconds = 600
        autosaveSeconds = 60
        playerLimit = 5
        instanceBasePort = $InstanceBasePort
        memoryMb = 512
        serverJar = $ServerJar.Replace('\', '/')
        javaPath = $Java
    } | ConvertTo-Json
    Set-Content (Join-Path $coordinator 'config\mp-coordinator.json') $cfg -Encoding utf8

    $proc = Start-Process -FilePath $Java -ArgumentList @('-Xmx512m', '-jar', $ServerJar) `
        -WorkingDirectory $coordinator -PassThru -WindowStyle Hidden

    $status = $null
    for($i = 0; $i -lt 60; $i++){
        Start-Sleep -Seconds 1
        try{
            $status = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 3).Content | ConvertFrom-Json
            if($status.home){ break }
        }catch{
            $status = $null
        }
    }
    if($status -eq $null -or -not $status.home){ Fail 'coordinator did not become ready' }
    if($null -eq $status.homePlayers){ Fail '/status has no homePlayers field' }

    $denied = Admin '{"player":"not-a-moderator","action":"pause","value":"true"}'
    if($denied.ok){ Fail 'a non-moderator was allowed to pause the campaign' }

    $paused = Admin '{"player":"GGhanera","action":"pause","value":"true"}'
    if(-not $paused.ok){ Fail 'moderator could not pause the campaign' }

    $speed = Admin '{"player":"GGhanera","action":"speed","value":"2"}'
    if(-not $speed.ok){ Fail 'moderator could not change the speed' }

    $badSpeed = Admin '{"player":"GGhanera","action":"speed","value":"99"}'
    if($badSpeed.ok){ Fail 'speed out of range was accepted' }

    $log = Admin '{"player":"GGhanera","action":"log","sector":""}'
    if(-not $log.ok -or $log.log -notmatch 'coordinator ready'){ Fail 'admin log did not return the coordinator log' }

    $homeStop = Admin ('{"player":"GGhanera","action":"stop","sector":"' + $status.home + '"}')
    if($homeStop.ok){ Fail 'the home sector was stopped from the admin panel' }

    $added = Admin '{"player":"GGhanera","action":"modadd","name":"TestMod"}'
    if(-not $added.ok){ Fail 'moderator add failed' }

    $removed = Admin '{"player":"GGhanera","action":"modremove","name":"GGhanera"}'
    if(-not $removed.ok){ Fail 'moderator remove failed' }

    $last = Admin '{"player":"TestMod","action":"modremove","name":"TestMod"}'
    if($last.ok){ Fail 'the last moderator was removed' }

    #bans carry a reason and an expiry
    $ban = Admin '{"player":"TestMod","action":"ban","name":"BannedGuy","reason":"test","hours":1}'
    if(-not $ban.ok){ Fail 'ban with reason failed' }
    $status = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 10).Content | ConvertFrom-Json
    $entry = $status.bans | Where-Object { $_.name -eq 'BannedGuy' }
    if($entry -eq $null -or $entry.reason -ne 'test' -or $entry.expires -le [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()){
        Fail 'ban reason/expiry not stored'
    }
    if(-not (Admin '{"player":"TestMod","action":"unban","name":"BannedGuy"}').ok){ Fail 'unban failed' }

    #named companies: archive the current one, start a fresh campaign with the same research
    $create = Admin '{"player":"TestMod","action":"companycreate","value":"TestCompany"}'
    if(-not $create.ok){ Fail ('company create failed: ' + $create.error) }
    $status = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 10).Content | ConvertFrom-Json
    if($status.company -ne 'TestCompany'){ Fail 'company name not switched after create' }
    if(-not (Test-Path (Join-Path $root 'campaign\unlocks.json'))){ Fail 'new company lost its research file' }
    if(-not (Test-Path (Join-Path $root 'companies\Основная'))){ Fail 'the old company was not archived' }

    $back = Admin '{"player":"TestMod","action":"companyswitch","value":"Основная"}'
    if(-not $back.ok){ Fail ('company switch failed: ' + $back.error) }
    $status = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 10).Content | ConvertFrom-Json
    if($status.company -ne 'Основная'){ Fail 'company did not switch back' }

    Write-Host 'PASS: admin endpoint checks' -ForegroundColor Green
}finally{
    if($proc -ne $null -and -not $proc.HasExited){ $proc.Kill() }
    Start-Sleep -Seconds 2
    Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}
