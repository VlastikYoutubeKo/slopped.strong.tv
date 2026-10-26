# debloat.ps1 - 4K Google TV Box (Android 14)
# Spusteni ve slozce platform-tools:
#   powershell -ExecutionPolicy Bypass -File .\debloat.ps1            (debloat + zrychleni)
#   powershell -ExecutionPolicy Bypass -File .\debloat.ps1 -Restore   (vrati vsechno zpet)
param([switch]$Restore)

$adb = if (Test-Path (Join-Path $PSScriptRoot "adb.exe")) { Join-Path $PSScriptRoot "adb.exe" } else { "adb" }

# Vybere funkcni zarizeni (stav "device"), offline duplikaty ignoruje
$line = & $adb devices | Select-String "`tdevice\s*$" | Select-Object -First 1
if (-not $line) { Write-Host "Zadne funkcni zarizeni. Zkontroluj: .\adb devices"; exit 1 }
$serial = $line.ToString().Split("`t")[0].Trim()
Write-Host "Zarizeni: $serial"
function A { & $adb -s $serial @args }

# --- A) Bezpecne k odstraneni ---
$remove = @(
  "com.oem.qa",                                   # tovarni QA/test
  "com.sdt.producttest",                          # tovarni test produktu
  "com.google.android.feedback",                  # zpetna vazba Googlu
  "com.android.tv.feedbackconsent",
  "com.android.adservices.api",                   # reklamni API Androidu
  "com.android.ondevicepersonalization.services", # personalizace
  "com.android.federatedcompute.services",        # telemetrie / federated learning
  "com.android.healthconnect.controller",         # Health Connect
  "com.android.health.connect.backuprestore",
  "com.android.nearby.halfsheet",                 # Nearby popupy
  "com.android.printspooler",                     # tisk
  "com.android.htmlviewer",
  "com.android.wallpaperbackup",
  "com.android.sharedstoragebackup",
  "com.android.localtransport",
  "com.android.backupconfirm",
  "com.google.android.syncadapters.calendar",
  "com.google.android.play.games",
  "com.google.android.youtube.tvmusic",           # YouTube Music
  "com.iqiyi.i18n.tv",                            # iQIYI
  "com.neon.sleeptvtimer"                         # duplicitni sleep timer (com.grubel... zustava)
)

# --- B) Volitelne: odkomentuj radky, ktere nepouzivas ---
$optional = @(
  # "com.amazon.amazonvideo.livingroom",          # Prime Video
  # "com.airplaytouch",                           # AirPlay
  # "com.pigeoncast.screen.mirroring.android.tv", # screen mirroring
  # "cz.tvprogram.lepsitv",                       # Lepsi TV
  # "com.google.android.apps.tv.dreamx",          # sporic obrazovky
  # "com.google.android.marvin.talkback"          # TalkBack
  # POZOR: com.sdt.ota / com.sdt.upgrade = aktualizace od Strongu, radeji nech
)

$all = $remove + $optional

if ($Restore) {
  foreach ($p in $all) {
    $r = A shell cmd package install-existing --user 0 $p 2>&1
    Write-Host ("{0,-55} {1}" -f $p, ($r -join " "))
  }
  Write-Host "Obnoveno. Doporucuji restart: .\adb reboot"
  exit 0
}

# Zaloha seznamu balicku pred zmenami
A shell pm list packages | Out-File (Join-Path $PSScriptRoot "packages_before.txt") -Encoding ascii

foreach ($p in $all) {
  $r = A shell pm uninstall -k --user 0 $p 2>&1
  Write-Host ("{0,-55} {1}" -f $p, ($r -join " "))
}

# Zrychleni: kratsi animace
A shell settings put global window_animation_scale 0.5
A shell settings put global transition_animation_scale 0.5
A shell settings put global animator_duration_scale 0.5

# Vycisteni cache
A shell pm trim-caches 999G

Write-Host ""
Write-Host "Hotovo. Doporucuji restart: .\adb reboot"
Write-Host "Zpet: powershell -ExecutionPolicy Bypass -File .\debloat.ps1 -Restore"
