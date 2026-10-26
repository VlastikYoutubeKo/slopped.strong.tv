# measure.ps1 - mereni vykonu boxu (cas studeneho startu + RAM)
# Pouziti: powershell -ExecutionPolicy Bypass -File .\measure.ps1 -Label before
param([string]$Label = "run", [int]$Runs = 3)

$adb = if (Test-Path (Join-Path $PSScriptRoot "adb.exe")) { Join-Path $PSScriptRoot "adb.exe" } else { "adb" }
$line = & $adb devices | Select-String "`tdevice\s*$" | Select-Object -First 1
if (-not $line) { Write-Host "Zadne funkcni zarizeni."; exit 1 }
$serial = $line.ToString().Split("`t")[0].Trim()
function A { & $adb -s $serial @args }

$targets = [ordered]@{
  "SmartTube"     = "org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"
  "Obchod Play"   = "com.android.vending/com.google.android.finsky.tvmainactivity.TvMainActivity"
}
# Aplikace, ktera se da do popredi, kdyz se meri studeny start launcheru
$fg = "org.smarttube.stable/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"

# TotalTime, pripadne WaitTime (kdyz se aktivita presmeruje)
function StartTime($r) {
  foreach ($k in "TotalTime", "WaitTime") {
    $m = $r | Select-String "${k}:\s*(\d+)" | Select-Object -First 1
    if ($m -and [int]$m.Matches[0].Groups[1].Value -gt 0) { return [int]$m.Matches[0].Groups[1].Value }
  }
  return $null
}

$out = [ordered]@{ label = $Label; time = (Get-Date -Format s) }

# Launcher (domovska obrazovka): v popredi je jina aplikace, launcher se zastavi a spusti se HOME intentem
$times = @()
for ($i = 0; $i -lt $Runs; $i++) {
  A shell am start -W -n $fg | Out-Null
  Start-Sleep -Seconds 4
  A shell am force-stop com.google.android.apps.tv.launcherx | Out-Null
  Start-Sleep -Seconds 2
  $t = StartTime (A shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1)
  if ($t) { $times += $t }
  Start-Sleep -Seconds 5
}
$avg = if ($times.Count) { [math]::Round(($times | Measure-Object -Average).Average) } else { $null }
$out["Launcher (studeny start)"] = "avg ${avg} ms ($($times -join ', '))"

foreach ($name in $targets.Keys) {
  $comp = $targets[$name]
  $pkg = $comp.Split("/")[0]
  $times = @()
  for ($i = 0; $i -lt $Runs; $i++) {
    A shell am force-stop $pkg | Out-Null
    Start-Sleep -Seconds 2
    $t = StartTime (A shell am start -W -S -n $comp 2>&1)
    if ($t) { $times += $t }
    Start-Sleep -Seconds 3
    A shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 2
  }
  $avg = if ($times.Count) { [math]::Round(($times | Measure-Object -Average).Average) } else { $null }
  $out[$name] = "avg ${avg} ms ($($times -join ', '))"
}
A shell input keyevent KEYCODE_HOME | Out-Null

Start-Sleep -Seconds 5
$mem = A shell cat /proc/meminfo
$avail = ($mem | Select-String "MemAvailable:\s*(\d+)").Matches.Groups[1].Value
$free  = ($mem | Select-String "MemFree:\s*(\d+)").Matches.Groups[1].Value
$out["MemAvailable MB"] = [math]::Round([int]$avail / 1024)
$out["MemFree MB"] = [math]::Round([int]$free / 1024)
$out["procesu (ps -A)"] = (A shell ps -A | Measure-Object -Line).Lines
$out["balicku"] = (A shell pm list packages | Measure-Object -Line).Lines
$out["/data volne"] = ((A shell df -h /data) | Select-Object -Last 1)

$out.GetEnumerator() | ForEach-Object { "{0,-28} {1}" -f $_.Key, $_.Value } | Tee-Object -FilePath (Join-Path $PSScriptRoot "measure_$Label.txt")
