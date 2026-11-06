<# :
@echo off
chcp 65001 >nul
setlocal
set "MP_INSTALLER_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ((Get-Content '%~f0' -Raw -Encoding UTF8))"
echo.
pause
exit /b
#>

# MP Sectors - single-file server installer.
# Installs the server into .\MP-Sectors and puts the client mods next to this file.
# No campaign is bundled: copy your campaign folder afterwards.

try{ [Console]::OutputEncoding = [Text.Encoding]::UTF8 }catch{}
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$ReleaseApi = "https://api.github.com/repos/gghanera/mp-sectors/releases/latest"
$FallbackZip = "https://github.com/gghanera/mp-sectors/releases/download/v2.1.0/MP-Sectors-2.1.0.zip"

function Write-Step($text){
    Write-Host ""
    Write-Host "== $text ==" -ForegroundColor Cyan
}

function Download-File($url, $target){
    Write-Host "  $url" -ForegroundColor DarkGray
    New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null

    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if($curl){
        & $curl.Source -fL --retry 3 --retry-delay 2 -o $target $url
        if($LASTEXITCODE -ne 0){ throw "download failed: $url" }
    }else{
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $target -TimeoutSec 600
    }

    if(-not (Test-Path $target)){ throw "download failed: $url" }
}

function Expand-Zip($zip, $dest){
    New-Item -ItemType Directory -Force $dest | Out-Null

    $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
    if($tar){
        & $tar.Source -xf $zip -C $dest
        if($LASTEXITCODE -ne 0){ throw "extract failed: $zip" }
    }else{
        Expand-Archive -Path $zip -DestinationPath $dest -Force
    }
}

function Get-JavaMajor($java){
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try{
        $out = & $java -version 2>&1 | Out-String
        if($out -match 'version "(\d+)'){ return [int]$matches[1] }
    }catch{
    }finally{
        $ErrorActionPreference = $old
    }
    return 0
}

function Find-Java(){
    $candidates = New-Object System.Collections.Generic.List[string]

    if($env:JAVA_HOME){ $candidates.Add((Join-Path $env:JAVA_HOME "bin\java.exe")) }

    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if($cmd -and $cmd.Source){ $candidates.Add($cmd.Source) }

    foreach($root in @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\BellSoft",
        "C:\Program Files\Microsoft"
    )){
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $candidates.Add((Join-Path $_.FullName "bin\java.exe"))
        }
    }

    foreach($java in $candidates){
        if($java -and (Test-Path $java)){
            if((Get-JavaMajor $java) -ge 17){ return $java }
        }
    }
    return $null
}

function Install-Java($dir){
    $zip = Join-Path $dir "tools\jdk.zip"
    $target = Join-Path $dir "tools\jdk"

    Write-Host "  JDK 17+ не найден, скачиваю Temurin 21 (около 190 МБ)..." -ForegroundColor Yellow
    Download-File "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" $zip

    Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
    Expand-Zip $zip $target
    Remove-Item -Force $zip -ErrorAction SilentlyContinue

    $java = Get-ChildItem $target -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if(-not $java){ throw "Java was downloaded but java.exe was not found" }
    return $java.FullName
}

function Find-Pwsh(){
    $cmd = Get-Command pwsh -ErrorAction SilentlyContinue
    if($cmd -and $cmd.Source){ return $cmd.Source }
    return $null
}

function Install-Pwsh($dir){
    $target = Join-Path $dir "tools\pwsh"
    $zip = Join-Path $dir "tools\pwsh.zip"

    Write-Host "  PowerShell 7 не найден, скачиваю переносимую версию..." -ForegroundColor Yellow

    $url = $null
    try{
        $rel = Invoke-RestMethod -Headers @{ "User-Agent" = "MP-Sectors-Installer" } -Uri "https://api.github.com/repos/PowerShell/PowerShell/releases/latest" -TimeoutSec 30
        $asset = $rel.assets | Where-Object { $_.name -match '^PowerShell-.*-win-x64\.zip$' } | Select-Object -First 1
        if($asset){ $url = $asset.browser_download_url }
    }catch{}
    if(-not $url){
        $url = "https://github.com/PowerShell/PowerShell/releases/download/v7.4.6/PowerShell-7.4.6-win-x64.zip"
    }

    Download-File $url $zip
    Remove-Item -Recurse -Force $target -ErrorAction SilentlyContinue
    Expand-Zip $zip $target
    Remove-Item -Force $zip -ErrorAction SilentlyContinue

    $exe = Join-Path $target "pwsh.exe"
    if(-not (Test-Path $exe)){ throw "PowerShell was downloaded but pwsh.exe was not found" }
    return $exe
}

function Test-Admin(){
    try{
        $id = [Security.Principal.WindowsIdentity]::GetCurrent()
        $p = New-Object Security.Principal.WindowsPrincipal($id)
        return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    }catch{
        return $false
    }
}

function Add-FirewallRules(){
    $tcp = @($HttpPort, $GamePort) + ($InstancePort..($InstancePort + 4))
    $udp = @($GamePort) + ($InstancePort..($InstancePort + 4))
    $tcpText = ($tcp | Sort-Object -Unique) -join ","
    $udpText = ($udp | Sort-Object -Unique) -join ","

    foreach($name in @("MP Sectors TCP", "MP Sectors UDP")){
        Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    }

    New-NetFirewallRule -DisplayName "MP Sectors TCP" -Direction Inbound -Protocol TCP -LocalPort $tcpText -Action Allow | Out-Null
    New-NetFirewallRule -DisplayName "MP Sectors UDP" -Direction Inbound -Protocol UDP -LocalPort $udpText -Action Allow | Out-Null
    Write-Host "  Правила брандмауэра добавлены: TCP $tcpText, UDP $udpText" -ForegroundColor Green
}

function Write-LauncherScripts($dir, $pwsh, $javaHome, $snapshot){
    $argsText = "coordinator -HttpPort $HttpPort -GamePort $GamePort -InstancePort $InstancePort " +
        "-MaxSectors $MaxSectors -MaxPlayers $MaxPlayers -MemoryMb $MemoryMb " +
        "-SnapshotDir `"$snapshot`""

    $start = @"
@echo off
setlocal
set "JAVA_HOME=$javaHome"
set "PATH=%JAVA_HOME%\bin;%PATH%"
"$pwsh" -NoProfile -ExecutionPolicy Bypass -File "%~dp0mp-sectors.ps1" $argsText
echo.
pause
"@
    Set-Content -Path (Join-Path $dir "start-server.cmd") -Value $start -Encoding Default

    $stop = @"
@echo off
setlocal
"$pwsh" -NoProfile -ExecutionPolicy Bypass -File "%~dp0mp-sectors.ps1" coordinator-stop
echo.
pause
"@
    Set-Content -Path (Join-Path $dir "stop-server.cmd") -Value $stop -Encoding Default
}

function New-DesktopShortcut($dir, $pwsh){
    try{
        $desktop = [Environment]::GetFolderPath("Desktop")
        $path = Join-Path $desktop "MP Sectors Server.lnk"
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($path)
        $shortcut.TargetPath = Join-Path $dir "start-server.cmd"
        $shortcut.WorkingDirectory = $dir
        $shortcut.IconLocation = "$pwsh,0"
        $shortcut.Description = "MP Sectors campaign server"
        $shortcut.Save()
        Write-Host "  Ярлык на рабочем столе: $path" -ForegroundColor Green
    }catch{
        Write-Host "  Не удалось создать ярлык: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------------------

$ActivationDir = $env:MP_INSTALLER_DIR
if(-not $ActivationDir){ $ActivationDir = (Get-Location).Path }
$ActivationDir = [IO.Path]::GetFullPath($ActivationDir).TrimEnd('\')

$InstallDir = $env:MP_INSTALL_DIR
if(-not $InstallDir){ $InstallDir = Join-Path $ActivationDir "MP-Sectors" }
$InstallDir = [IO.Path]::GetFullPath($InstallDir)

$HttpPort = 8090
$GamePort = 6567
$InstancePort = 6570
$MaxSectors = 5
$MaxPlayers = 10
$MemoryMb = 1024

$NoFirewall = $env:MP_NO_FIREWALL -eq "1"
$NoShortcut = $env:MP_NO_SHORTCUT -eq "1"
$PackageZip = $env:MP_PACKAGE_ZIP

try{ Clear-Host }catch{}
Write-Host "==============================================" -ForegroundColor Green
Write-Host "  MP Sectors - установка сервера" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Папка установщика: $ActivationDir"
Write-Host "Сервер будет установлен в: $InstallDir"
Write-Host ""

New-Item -ItemType Directory -Force $InstallDir | Out-Null

# 1. server package
Write-Step "1/5 Скачивание сервера"
if(-not $PackageZip){
    $local = Get-ChildItem $ActivationDir -Filter "MP-Sectors-*.zip" -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if($local){ $PackageZip = $local.FullName }
}
if(-not $PackageZip){
    $url = $FallbackZip
    try{
        $rel = Invoke-RestMethod -Headers @{ "User-Agent" = "MP-Sectors-Installer" } -Uri $ReleaseApi -TimeoutSec 30
        $asset = $rel.assets | Where-Object { $_.name -match '^MP-Sectors-.*\.zip$' } | Select-Object -First 1
        if($asset){ $url = $asset.browser_download_url }
    }catch{}
    $PackageZip = Join-Path $env:TEMP "MP-Sectors-Setup.zip"
    Download-File $url $PackageZip
}

Write-Host "  Архив: $PackageZip"
Expand-Zip $PackageZip $InstallDir
Write-Host "  Файлы сервера распакованы." -ForegroundColor Green

# 2. client mods next to the installer
Write-Step "2/5 Клиентские моды"
$clientJar = Join-Path $InstallDir "mods\mp-campaign.jar"
$researchJar = Join-Path $InstallDir "client-mods\any-research.jar"
if(Test-Path $clientJar){
    Copy-Item $clientJar (Join-Path $ActivationDir "mp-campaign.jar") -Force
    Write-Host "  mp-campaign.jar -> $ActivationDir" -ForegroundColor Green
}else{
    Write-Host "  mp-campaign.jar не найден в архиве." -ForegroundColor Yellow
}
if(Test-Path $researchJar){
    Copy-Item $researchJar (Join-Path $ActivationDir "any-research.jar") -Force
    Write-Host "  any-research.jar -> $ActivationDir" -ForegroundColor Green
}else{
    Write-Host "  any-research.jar не найден в архиве." -ForegroundColor Yellow
}

# 3. Java
Write-Step "3/5 Java"
$java = Find-Java
if($java){
    Write-Host "  Найдена Java: $java" -ForegroundColor Green
}else{
    $java = Install-Java $InstallDir
    Write-Host "  Java установлена: $java" -ForegroundColor Green
}
$javaHome = Split-Path (Split-Path $java -Parent) -Parent
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

# 4. PowerShell 7
Write-Step "4/5 PowerShell 7"
$pwsh = Find-Pwsh
if($pwsh){
    Write-Host "  Найден: $pwsh" -ForegroundColor Green
}else{
    $pwsh = Install-Pwsh $InstallDir
    Write-Host "  Установлен: $pwsh" -ForegroundColor Green
}

# 5. scripts, campaign placeholder, firewall, shortcut
Write-Step "5/5 Настройка"
$campaign = Join-Path $InstallDir "campaign"
New-Item -ItemType Directory -Force (Join-Path $campaign "sectors"), (Join-Path $campaign "info") | Out-Null

$SnapshotDir = if(Test-Path "F:\"){ "F:\MP-Sectors\backups" } else { Join-Path $InstallDir "backups" }
$SnapshotDir = [IO.Path]::GetFullPath($SnapshotDir)
New-Item -ItemType Directory -Force $SnapshotDir | Out-Null

Write-LauncherScripts $InstallDir $pwsh $javaHome $SnapshotDir
Write-Host "  start-server.cmd / stop-server.cmd созданы." -ForegroundColor Green

if(-not $NoFirewall){
    if(Test-Admin){
        Add-FirewallRules
    }else{
        Write-Host "  Нет прав администратора: правила брандмауэра не добавлены." -ForegroundColor Yellow
        Write-Host "  Запусти установщик от администратора или открой вручную TCP 8090,6567,6570-6574 и UDP 6567,6570-6574." -ForegroundColor Yellow
    }
}

if(-not $NoShortcut){ New-DesktopShortcut $InstallDir $pwsh }

Write-Host ""
Write-Host "==============================================" -ForegroundColor Green
Write-Host "  Готово" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green
Write-Host "Сервер:          $InstallDir"
Write-Host "Клиентские моды: $ActivationDir\mp-campaign.jar"
Write-Host "                 $ActivationDir\any-research.jar"
Write-Host "Кампания:        $campaign  (пустая)"
Write-Host "Снимки:          $SnapshotDir"
Write-Host ""
Write-Host "Кампания НЕ включена в установщик." -ForegroundColor Yellow
Write-Host "Скопируй свою папку campaign в:" -ForegroundColor Yellow
Write-Host "  $campaign" -ForegroundColor Yellow
Write-Host "после этого запусти:" -ForegroundColor Yellow
Write-Host "  $InstallDir\start-server.cmd" -ForegroundColor Yellow
Write-Host ""
Write-Host "Игроки подключаются к IP_ЭТОГО_ПК:6567."
Write-Host "Клиентские моды раздай игрокам (mp-campaign.jar и any-research.jar)."
Write-Host ""
