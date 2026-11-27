# mp-sectors - run a Mindustry campaign with one server instance per sector.
#
#   .\mp-sectors.ps1 init
#   .\mp-sectors.ps1 import -Sector serpulo-170 -Save "C:\...\sector-serpulo-170.msav"
#   .\mp-sectors.ps1 start  -Sector serpulo-170
#   .\mp-sectors.ps1 list
#   .\mp-sectors.ps1 stop   -Sector serpulo-170
#
# Requires PowerShell 7 and the official (non-Steam) server build: .\fetch.ps1
param(
    [Parameter(Position = 0)]
    [ValidateSet('init', 'import', 'start', 'stop', 'list', 'coordinator', 'coordinator-stop')]
    [string]$Action = 'list',

    [string]$Sector,
    [string]$Save,
    [int]$Port = 0,
    [int]$PlayerLimit = 5,
    [int]$Autosave = 60,
    [int]$MemoryMb = 1024,
    [string]$ServerJar = "",
    [string]$SteamDir = "",
    [switch]$Steam,
    [switch]$Foreground,

    [int]$HttpPort = 8090,
    [int]$GamePort = 6567,
    [int]$InstancePort = 6570,
    [int]$MaxSectors = 5,
    [int]$MaxPlayers = 10,
    [int]$IdleStop = 60,
    [ValidateSet('serpulo', 'erekir')]
    [string]$Planet = 'serpulo',
    [string]$Moderator = "",
    [string]$SnapshotDir = ""
)

$ErrorActionPreference = "Stop"

$Root       = $PSScriptRoot
$Campaign   = Join-Path $Root "campaign"
$SectorsDir = Join-Path $Campaign "sectors"
$InfoDir    = Join-Path $Campaign "info"
$Instances  = Join-Path $Root "instances"
$ModJar     = Join-Path $Root "build\mp-sectors.jar"
if(-not (Test-Path $ModJar)){ $ModJar = Join-Path $Root "mods\mp-sectors.jar" }
$StateFile  = Join-Path $Root "instances.json"

if(-not $ServerJar){ $ServerJar = Join-Path $Root "libs\server-release.jar" }
if(-not $SnapshotDir){
    $SnapshotDir = if(Test-Path "F:\"){ "F:\MP-Sectors\backups" } else { Join-Path $Root "backups" }
}
if(-not $Moderator){ $Moderator = $env:USERNAME }

# Create the campaign folder and its first home sector. The coordinator generates the sector
# itself; without home.json a fresh install refuses to start.
function Initialize-Campaign {
    New-Item -ItemType Directory -Force $Campaign, $SectorsDir, $InfoDir | Out-Null
    $homeFile = Join-Path $Campaign "home.json"
    $saves = @(Get-ChildItem $SectorsDir -Filter *.msav -ErrorAction SilentlyContinue)
    if(-not (Test-Path $homeFile) -and $saves.Count -eq 0){
        $start = if($Planet -eq 'erekir'){ 10 } else { 170 }
        Set-Content -Path $homeFile -Value ('{sector:' + $Planet + '-' + $start + '}') -NoNewline -Encoding ascii
        Write-Host "campaign: new $Planet campaign at $Planet-$start"
    }
}

function Get-Java {
    if($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))){
        return (Join-Path $env:JAVA_HOME "bin\java.exe")
    }
    $candidates = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "jdk*" } | Sort-Object Name -Descending
    foreach($c in $candidates){
        $p = Join-Path $c.FullName "bin\java.exe"
        if(Test-Path $p){ return $p }
    }
    return (Get-Command java).Source
}

function Test-Port($port){
    return [bool](Get-NetUDPEndpoint -LocalPort $port -ErrorAction SilentlyContinue)
}

function Read-State {
    $list = [System.Collections.ArrayList]::new()
    if(Test-Path $StateFile){
        $raw = Get-Content $StateFile -Raw
        if($raw -and $raw.Trim().Length -gt 0){
            foreach($item in ($raw | ConvertFrom-Json)){
                if($item){ [void]$list.Add($item) }
            }
        }
    }
    return ,$list
}

function Write-State($items){
    $array = @($items | Where-Object { $_ })
    if($array.Count -eq 0){
        Set-Content -Path $StateFile -Value "[]" -Encoding utf8
    }else{
        Set-Content -Path $StateFile -Value ($array | ConvertTo-Json -Depth 4 -AsArray) -Encoding utf8
    }
}

function Get-FreePort($state){
    $used = @($state | ForEach-Object { [int]$_.port })
    $port = 6567
    while($used -contains $port -or (Get-NetUDPEndpoint -LocalPort $port -ErrorAction SilentlyContinue)){
        $port++
    }
    return $port
}

function Get-Instance($state, $name){
    return @($state | Where-Object { $_.sector -eq $name })[0]
}

#a stored PID can be reused by an unrelated process; only trust it while its start time matches
function Test-StartedProcess($proc, $startedAt){
    if(-not $proc -or -not $startedAt){ return $false }
    try{
        if($startedAt -isnot [datetime]){
            $startedAt = [datetime]::ParseExact([string]$startedAt, "s", [Globalization.CultureInfo]::InvariantCulture)
        }
        return [math]::Abs(($proc.StartTime - $startedAt).TotalSeconds) -lt 120
    }catch{ return $false }
}

function Get-OwnedProcess($processId, $startedAt){
    if([int]$processId -le 0){ return $null }
    $proc = Get-Process -Id ([int]$processId) -ErrorAction SilentlyContinue
    if(-not $proc){ return $null }
    if(Test-StartedProcess $proc $startedAt){ return $proc }
    return $null
}

switch($Action){
    'init' {
        Initialize-Campaign
        New-Item -ItemType Directory -Force $Instances | Out-Null
        if(-not (Test-Path $StateFile)){ Write-State @() }
        if(-not (Test-Path $ServerJar)){ Write-Host "server jar missing: run .\fetch.ps1" -ForegroundColor Yellow }
        if(-not (Test-Path $ModJar)){ Write-Host "mod jar missing: run .\build.ps1" -ForegroundColor Yellow }
        Write-Host "campaign: $Campaign"
        Write-Host "instances: $Instances"
    }

    'import' {
        if(-not $Sector -or -not $Save){ throw "usage: .\mp-sectors.ps1 import -Sector serpulo-170 -Save <path>" }
        if(-not (Test-Path $Save)){ throw "save not found: $Save" }
        New-Item -ItemType Directory -Force $SectorsDir | Out-Null
        $dest = Join-Path $SectorsDir "$Sector.msav"
        Copy-Item $Save $dest -Force
        Write-Host "imported: $dest"
    }

    'start' {
        if(-not $Sector){ throw "usage: .\mp-sectors.ps1 start -Sector serpulo-170" }
        if(-not (Test-Path $ServerJar)){ throw "server jar not found: $ServerJar (run .\fetch.ps1)" }
        if(-not (Test-Path $ModJar)){ throw "mod jar not found: $ModJar (run .\build.ps1)" }

        $state = Read-State

        if($null -eq $state){ $state = [System.Collections.ArrayList]::new() }
        if(Get-Instance $state $Sector){
            throw "sector '$Sector' is already running (use list)"
        }

        $savePath = Join-Path $SectorsDir "$Sector.msav"
        if(-not (Test-Path $savePath)){ throw "no campaign save for '$Sector' (use import)" }

        $dir = Join-Path $Instances $Sector
        New-Item -ItemType Directory -Force (Join-Path $dir "config\mods"), (Join-Path $dir "config\saves") | Out-Null
        Copy-Item $ModJar (Join-Path $dir "config\mods\mp-sectors.jar") -Force

        if($Port -le 0){ $Port = Get-FreePort $state }

        $config = @{
            campaign       = $Campaign.Replace('\', '/')
            sector         = $Sector
            port           = $Port
            playerLimit    = $PlayerLimit
            autosaveSeconds = $Autosave
        } | ConvertTo-Json -Depth 3
        Set-Content -Path (Join-Path $dir "config\mp-sectors.json") -Value $config -Encoding utf8

        $log = Join-Path $dir "server.log"
        $err = Join-Path $dir "server.err.log"

        Write-Host "starting $Sector on port $Port ..."
        # a stop interrupted during its wait leaves these behind; the new process would exit at once
        Remove-Item (Join-Path $dir "stop.flag"), (Join-Path $dir "status.json") -ErrorAction SilentlyContinue
        $java = Get-Java
        $proc = Start-Process -FilePath $java `
            -ArgumentList "-Xmx${MemoryMb}m", "-jar", "`"$ServerJar`"" `
            -WorkingDirectory $dir `
            -RedirectStandardOutput $log `
            -RedirectStandardError $err `
            -WindowStyle Hidden `
            -PassThru

        [void]$state.Add([pscustomobject]@{
            sector  = $Sector
            port    = [int]$Port
            pid     = [int]$proc.Id
            dir     = $dir
            started = (Get-Date).ToString("s")
        })
        Write-State $state

        #wait for the mod to report the sector; a failed start must not leave a dead entry behind
        $ok = $false
        try{
            $deadline = (Get-Date).AddSeconds(45)
            while((Get-Date) -lt $deadline){
                Start-Sleep -Milliseconds 500
                if($proc.HasExited){ break }
                if((Test-Path $log) -and (Select-String -Path $log -Pattern "sector .* loaded on port" -Quiet -ErrorAction SilentlyContinue)){
                    $ok = $true
                    break
                }
            }

            if(-not $ok){ throw "instance did not report a loaded sector; see $log" }
            Write-Host "ready: $Sector -> 127.0.0.1:$Port" -ForegroundColor Green
        }finally{
            if(-not $ok){
                if(-not $proc.HasExited){ taskkill /PID $proc.Id /T /F 2>$null | Out-Null }
                Write-State ($state | Where-Object { $_.sector -ne $Sector })
                Write-Host "start failed: $Sector (see $log)" -ForegroundColor Yellow
            }
        }
    }

    'stop' {
        if(-not $Sector){ throw "usage: .\mp-sectors.ps1 stop -Sector serpulo-170" }
        $state = Read-State
        if($null -eq $state){ $state = [System.Collections.ArrayList]::new() }
        $entry = Get-Instance $state $Sector
        if(-not $entry){ throw "sector '$Sector' is not running" }

        $dir = $entry.dir
        if(-not $dir){ $dir = Join-Path $Instances $Sector }

        #graceful: the mod saves and exits when it sees this file
        Set-Content -Path (Join-Path $dir "stop.flag") -Value "stop" -Encoding utf8
        Write-Host "asking $Sector to save and stop ..."

        $deadline = (Get-Date).AddSeconds(30)
        while((Get-Date) -lt $deadline){
            Start-Sleep -Milliseconds 500
            if(-not (Test-Port $entry.port)){ break }
        }

        $stillRunning = $false
        if(Test-Port $entry.port){
            $proc = Get-OwnedProcess $entry.pid $entry.started
            if($proc){
                Write-Host "process did not stop in time, killing" -ForegroundColor Yellow
                taskkill /PID $proc.Id /T /F 2>$null | Out-Null
                Start-Sleep -Seconds 2
            }else{
                $stillRunning = $true
                Write-Host "port $($entry.port) is still open, but pid $($entry.pid) is not the saved $Sector process; not killing" -ForegroundColor Yellow
            }
        }

        Remove-Item (Join-Path $dir "stop.flag") -ErrorAction SilentlyContinue
        if($stillRunning){
            Write-Host "instance $Sector is still running; entry kept" -ForegroundColor Yellow
        }else{
            Write-State ($state | Where-Object { $_.sector -ne $Sector })
            Write-Host "stopped: $Sector"
        }
    }

    'coordinator' {
        if(-not (Test-Path $ServerJar)){ throw "server jar not found: $ServerJar (run .\fetch.ps1)" }
        if(-not (Test-Path $ModJar)){ throw "mod jar not found: $ModJar (run .\build.ps1)" }

        $dir = Join-Path $Root "coordinator"
        New-Item -ItemType Directory -Force (Join-Path $dir "config\mods"), (Join-Path $dir "config\saves") | Out-Null
        Copy-Item $ModJar (Join-Path $dir "config\mods\mp-sectors.jar") -Force
        Initialize-Campaign

        #the client mod must be on every server too: the mod list is compared on connect
        $clientMod = Join-Path $Root "mods\mp-campaign.jar"
        if(-not (Test-Path $clientMod)){ $clientMod = Join-Path $Root "client\build\mp-campaign.jar" }
        if(Test-Path $clientMod){ Copy-Item $clientMod (Join-Path $dir "config\mods\mp-campaign.jar") -Force }

        if(-not $SteamDir){
            foreach($candidate in @("F:\SteamLibrary\steamapps\common\Mindustry", "C:\Program Files (x86)\Steam\steamapps\common\Mindustry", "D:\SteamLibrary\steamapps\common\Mindustry")){
                if(Test-Path (Join-Path $candidate "jre\desktop.jar")){ $SteamDir = $candidate; break }
            }
        }
        $steamJar = if($SteamDir){ Join-Path $SteamDir "jre\desktop.jar" } else { "" }
        $useSteam = [bool]($Steam -and ($steamJar -ne "") -and (Test-Path $steamJar))

        # keep hand-set options the launcher does not own
        $cfgPath = Join-Path $dir "config\mp-coordinator.json"
        $oldCfg = $null
        if(Test-Path $cfgPath){
            try { $oldCfg = Get-Content $cfgPath -Raw | ConvertFrom-Json } catch { $oldCfg = $null }
        }
        $updateCheck = if($oldCfg -and $null -ne $oldCfg.updateCheck){ [bool]$oldCfg.updateCheck } else { $true }
        $httpToken = if($oldCfg -and $null -ne $oldCfg.httpToken){ [string]$oldCfg.httpToken } else { "" }
        $publicHost = if($oldCfg -and $null -ne $oldCfg.publicHost){ [string]$oldCfg.publicHost } else { "" }

        $cfg = @{
            campaign         = $Campaign.Replace('\', '/')
            snapshotDir      = $SnapshotDir.Replace('\', '/')
            httpPort         = $HttpPort
            gamePort         = $GamePort
            maxSectors       = $MaxSectors
            maxPlayers       = $MaxPlayers
            idleStopSeconds  = $IdleStop
            autosaveSeconds  = $Autosave
            playerLimit      = $PlayerLimit
            instanceBasePort = $InstancePort
            memoryMb         = $MemoryMb
            planet           = $Planet
            moderator        = $Moderator
            updateCheck      = $updateCheck
            httpToken        = $httpToken
            publicHost       = $publicHost
            serverJar        = (Resolve-Path $ServerJar).Path.Replace('\', '/')
            javaPath         = ""
            steam            = $useSteam
            steamJar         = $steamJar.Replace('\', '/')
            steamNative      = $SteamDir.Replace('\', '/')
        } | ConvertTo-Json -Depth 3
        Set-Content -Path $cfgPath -Value $cfg -Encoding utf8

        $log = Join-Path $dir "server.log"
        $err = Join-Path $dir "server.err.log"
        $java = Get-Java

        Write-Host "starting coordinator (game $GamePort, http $HttpPort) ..."
        if($useSteam){
            Set-Content -Path (Join-Path $dir "steam_appid.txt") -Value "1127400" -Encoding ascii
            $arguments = @("-Xmx${MemoryMb}m", "-Djava.library.path=`"$SteamDir`"", "-cp", "`"$ServerJar;$steamJar`"", "mindustry.server.ServerLauncher")
        }else{
            $arguments = @("-Xmx${MemoryMb}m", "-jar", "`"$ServerJar`"")
        }
        $proc = Start-Process -FilePath $java `
            -ArgumentList $arguments `
            -WorkingDirectory $dir `
            -RedirectStandardOutput $log `
            -RedirectStandardError $err `
            -WindowStyle Hidden `
            -PassThru

        Set-Content -Path (Join-Path $dir "coordinator.pid") -Value $proc.Id -Encoding utf8

        $deadline = (Get-Date).AddSeconds(60)
        $modLog = Join-Path $dir "config\logs\log-0.txt"
        $ok = $false
        while((Get-Date) -lt $deadline){
            Start-Sleep -Milliseconds 500
            if($proc.HasExited){ break }
            if((Test-Path $modLog) -and (Select-String -Path $modLog -Pattern "coordinator ready" -Quiet -ErrorAction SilentlyContinue)){
                $ok = $true
                break
            }
            if((Test-Path $log) -and (Select-String -Path $log -Pattern "coordinator ready" -Quiet -ErrorAction SilentlyContinue)){
                $ok = $true
                break
            }
        }

        if($ok){
            Write-Host "coordinator ready: game 127.0.0.1:$GamePort, http 127.0.0.1:$HttpPort" -ForegroundColor Green
        }else{
            Write-Host "coordinator did not report ready; see $modLog" -ForegroundColor Yellow
        }
    }

    'coordinator-stop' {
        $dir = Join-Path $Root "coordinator"
        if(-not (Test-Path $dir)){ throw "coordinator folder not found" }

        $pidFile = Join-Path $dir "coordinator.pid"
        $coordPid = 0
        $coordStarted = $null
        if(Test-Path $pidFile){
            $coordPid = [int](Get-Content $pidFile -Raw)
            #the pid file is written right after the process starts; its mtime identifies the owner
            $coordStarted = (Get-Item $pidFile).LastWriteTime
        }

        Set-Content -Path (Join-Path $dir "stop.flag") -Value "stop" -Encoding utf8
        Write-Host "asking coordinator to save and stop ..."

        $deadline = (Get-Date).AddSeconds(90)
        while((Get-Date) -lt $deadline){
            Start-Sleep -Milliseconds 500
            if($coordPid -gt 0){
                if(-not (Get-OwnedProcess $coordPid $coordStarted)){ break }
            }else{
                #no pid file: the coordinator's HTTP port disappearing means it is gone
                try{
                    Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$HttpPort/status" -TimeoutSec 2 | Out-Null
                }catch{
                    break
                }
            }
        }

        $coordProc = Get-OwnedProcess $coordPid $coordStarted
        if($coordProc){
            Write-Host "coordinator did not stop in time, killing" -ForegroundColor Yellow
            taskkill /PID $coordProc.Id /T /F 2>$null | Out-Null
        }elseif($coordPid -gt 0 -and (Get-Process -Id $coordPid -ErrorAction SilentlyContinue)){
            Write-Host "pid $coordPid is no longer the coordinator started from this folder; not killing" -ForegroundColor Yellow
        }

        Remove-Item (Join-Path $dir "stop.flag") -ErrorAction SilentlyContinue
        Write-Host "coordinator stopped"
    }

    'list' {
        $state = Read-State
        if($null -eq $state){ $state = [System.Collections.ArrayList]::new() }
        if($state.Count -eq 0){
            Write-Host "no running sectors"
            exit 0
        }
        $state | Select-Object sector, port, pid,
            @{ n = 'alive'; e = { Test-Port $_.port } },
            started | Format-Table -AutoSize
    }
}


