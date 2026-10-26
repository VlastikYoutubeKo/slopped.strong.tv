# tv.ps1 - zkratky pro Android TV box pres adb (to, co aplikace bez systemovych opravneni neumi)
# Pouziti:  powershell -ExecutionPolicy Bypass -File .\tv.ps1 <prikaz> [hodnota]
#   status            RAM, uloziste, uptime, top procesy
#   clean             ukonci procesy na pozadi (am kill-all) + vycisti cache (pm trim-caches)
#   anim <0|0.5|1>    rychlost animaci
#   reboot            restart boxu a cekani, nez nabehne
#   home              restart domovske obrazovky (launcheru)
#   connect           znovu pripoji box (192.168.1.84:5555)
#   screenshot        ulozi screenshot do aktualni slozky
param([Parameter(Position = 0)][string]$Cmd = "status", [Parameter(Position = 1)][string]$Value)

$adb = Join-Path $PSScriptRoot "..\..\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$BoxIp = "192.168.1.84:5555"

function Serial {
  $line = & $adb devices | Select-String "`tdevice\s*$" | Select-Object -First 1
  if (-not $line) { & $adb connect $BoxIp | Out-Null; Start-Sleep 2; $line = & $adb devices | Select-String "`tdevice\s*$" | Select-Object -First 1 }
  if (-not $line) { Write-Host "Box neni pripojeny. Zapni na nem ladeni pres USB/sit a zkus: tv.ps1 connect"; exit 1 }
  $line.ToString().Split("`t")[0].Trim()
}
function A { & $adb -s (Serial) @args }
function MemMB { [int]((A shell cat /proc/meminfo | Select-String "MemAvailable:\s*(\d+)").Matches[0].Groups[1].Value / 1024) }

switch ($Cmd) {
  "status" {
    Write-Host "RAM k dispozici: $(MemMB) MB"
    A shell "df -h /data | tail -1; uptime"
    A shell "dumpsys meminfo | grep -A12 'Total PSS by process' | tail -11"
  }
  "clean" {
    $before = MemMB
    A shell am kill-all
    A shell pm trim-caches 999G
    Start-Sleep 2
    Write-Host "RAM k dispozici: $before MB -> $(MemMB) MB"
  }
  "anim" {
    if ($Value -notin "0", "0.5", "1") { Write-Host "Pouziti: tv.ps1 anim 0|0.5|1"; exit 1 }
    foreach ($k in "window_animation_scale", "transition_animation_scale", "animator_duration_scale") { A shell settings put global $k $Value }
    Write-Host "Animace: $Value"
  }
  "reboot" {
    A reboot
    Write-Host "Restartuji, cekam na box..."
    Start-Sleep 40
    for ($i = 0; $i -lt 30; $i++) {
      & $adb connect $BoxIp 2>&1 | Out-Null
      $b = & $adb -s $BoxIp shell getprop sys.boot_completed 2>$null
      if ("$b".Trim() -eq "1") { Write-Host "Box nabehl."; exit 0 }
      Start-Sleep 5
    }
    Write-Host "Box se neozval - zkontroluj ho (ladeni se mohlo po restartu vypnout)."
  }
  "home" { A shell am force-stop com.google.android.apps.tv.launcherx; A shell input keyevent KEYCODE_HOME }
  "connect" { & $adb connect $BoxIp }
  "screenshot" {
    $f = "tv_$(Get-Date -Format yyyyMMdd_HHmmss).png"
    A shell screencap -p /data/local/tmp/tv_shot.png; A pull /data/local/tmp/tv_shot.png $f | Out-Null; A shell rm /data/local/tmp/tv_shot.png
    Write-Host "Ulozeno: $f"
  }
  default { Get-Content $PSCommandPath -TotalCount 10 | Select-Object -Skip 1 }
}
