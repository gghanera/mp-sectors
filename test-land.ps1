# Launch-cost check: two simultaneous landings must charge the live core exactly once.
# Starts a throwaway copy of an existing campaign, pauses it (no production), fires two
# /land requests at the same moment and reads the campaign info written by the autosave.
# The loadout is a one-tile core-shard schematic (copper:1000 lead:800).
# Run: .\test-land.ps1 [-Campaign <campaign folder>]
param(
    [string]$ServerJar = (Join-Path $PSScriptRoot 'libs\server-release.jar'),
    [string]$ModJar = (Join-Path $PSScriptRoot 'build\mp-sectors.jar'),
    [string]$Java = 'java',
    [string]$Campaign = "$env:USERPROFILE\Desktop\MP-Sectors\campaign",
    [string]$Moderator = 'LandHost',
    [int]$HttpPort = 8198,
    [int]$GamePort = 6698,
    [int]$InstanceBasePort = 6780,
    [int]$TimeoutSeconds = 90
)

# one core-shard tile, serialized the way the client's Schematics.writeBase64 does
$loadout = 'bXNjaAF4nGNgZGBkYmDLSUxKzSlmYIqOZeBKzs8rSc0r8U0sYGCqrmUECRSl6hZnJBalMDAwMDJAAAB6CwxJ'

$ErrorActionPreference = 'Stop'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ('mp-sectors-land-test-' + [guid]::NewGuid().ToString('N'))
$coordinator = Join-Path $root 'coordinator'
$copy = Join-Path $root 'campaign'
$proc = $null

function Fail($message){
    Write-Host "FAIL: $message" -ForegroundColor Red
    exit 1
}

function Stop-Coordinator($process){
    if($process -eq $null -or $process.HasExited) { return }
    Set-Content (Join-Path $coordinator 'stop.flag') 'stop' -NoNewline -Encoding ascii
    if(-not $process.WaitForExit(60000)) { $process.Kill() }
    Start-Sleep -Seconds 2
}

function Read-Items($file){
    $text = Get-Content $file -Raw
    $m = [regex]::Match($text, 'items:\{([^}]*)\}')
    if(-not $m.Success){ Fail "no items in $file" }
    $map = @{}
    foreach($pair in $m.Groups[1].Value.Split(',')){
        $kv = $pair.Split(':')
        if($kv.Count -eq 2){ $map[$kv[0].Trim()] = [int]$kv[1] }
    }
    return $map
}

try{
    if(-not (Test-Path (Join-Path $Campaign 'home.json'))){
        Write-Host "SKIP: campaign not found at $Campaign" -ForegroundColor Yellow
        exit 0
    }

    $homeKey = [regex]::Match((Get-Content (Join-Path $Campaign 'home.json') -Raw), '[a-z]+-\d+').Value
    if($homeKey -notmatch '^([a-z]+)-(\d+)$'){ Fail 'could not read the home sector' }
    $planet = $Matches[1]
    $id = [int]$Matches[2]

    New-Item -ItemType Directory -Force (Join-Path $coordinator 'config\mods') | Out-Null
    Copy-Item $Campaign $copy -Recurse -Force
    Set-Content (Join-Path $copy 'paused.flag') 'paused' -NoNewline -Encoding ascii
    Copy-Item $ModJar (Join-Path $coordinator 'config\mods\mp-sectors.jar') -Force

    $cfg = @{
        campaign = $copy.Replace('\', '/')
        httpPort = $HttpPort
        gamePort = $GamePort
        maxSectors = 3
        maxPlayers = 5
        playerLimit = 5
        idleStopSeconds = 600
        autosaveSeconds = 3
        instanceBasePort = $InstanceBasePort
        memoryMb = 512
        updateCheck = $false
        planet = $planet
        moderator = $Moderator
        serverJar = $ServerJar.Replace('\', '/')
        javaPath = $Java
    } | ConvertTo-Json
    Set-Content (Join-Path $coordinator 'config\mp-coordinator.json') $cfg -Encoding utf8

    $log = Join-Path $coordinator 'out.log'
    $proc = Start-Process -FilePath $Java -ArgumentList @('-Xmx512m', '-jar', $ServerJar) `
        -WorkingDirectory $coordinator -RedirectStandardOutput $log `
        -RedirectStandardError (Join-Path $coordinator 'err.log') -PassThru -WindowStyle Hidden

    $status = $null
    for($i = 0; $i -lt $TimeoutSeconds; $i++){
        Start-Sleep -Seconds 1
        if($proc.HasExited){ break }
        try{
            $status = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 3).Content | ConvertFrom-Json
            break
        }catch{
        }
    }
    if($status -eq $null){ Fail 'coordinator did not start' }
    if($status.home -ne $homeKey){ Fail ("home is '$($status.home)', expected '$homeKey'") }

    # let two autosaves write the live core into info (paused: no production drift)
    Start-Sleep -Seconds 8
    $infoFile = Join-Path $copy "info\$homeKey.json"
    $before = Read-Items $infoFile

    # the scenario needs a core that can pay once but not twice, otherwise the result is ambiguous
    $payOnce = $before['copper'] -ge 1000 -and $before['lead'] -ge 800
    $blocksSecond = $before['lead'] -lt 1600 -or $before['copper'] -lt 2000
    if(-not $payOnce -or -not $blocksSecond){
        Fail "core cannot discriminate: copper=$($before['copper']) lead=$($before['lead'])"
    }

    $body = @{
        planet = $planet
        id = $id
        player = 'LandTest'
        fromPlanet = $planet
        fromId = $id
        loadout = $loadout
        resources = ''
    } | ConvertTo-Json -Compress

    $client = [System.Net.Http.HttpClient]::new()
    $url = "http://127.0.0.1:$HttpPort/land"
    $tasks = @()
    foreach($n in 1..2){
        $content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, 'application/json')
        $tasks += $client.PostAsync($url, $content)
    }
    [void][System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
    $responses = @($tasks | ForEach-Object { $_.Result.Content.ReadAsStringAsync().Result })
    $client.Dispose()

    $okCount = @($responses | Where-Object { $_ -match '"ok":true' }).Count
    Write-Host ("responses: " + ($responses -join ' | ')) -ForegroundColor DarkGray
    if($okCount -ne 1){ Fail "expected exactly one charged landing, got $okCount" }

    Start-Sleep -Seconds 8
    $after = Read-Items $infoFile

    $copper = $before['copper'] - $after['copper']
    $lead = $before['lead'] - $after['lead']
    if($copper -ne 1000 -or $lead -ne 800){
        Fail "charged copper=$copper lead=$lead, expected copper=1000 lead=800 (double charge or no charge)"
    }

    Write-Host 'PASS: two simultaneous landings charged the core exactly once' -ForegroundColor Green
}finally{
    Stop-Coordinator $proc
    Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue
}
