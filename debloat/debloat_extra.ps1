# debloat_extra.ps1 - druha vlna debloatu (navazuje na debloat.ps1)
#   powershell -ExecutionPolicy Bypass -File .\debloat_extra.ps1            (odstrani)
#   powershell -ExecutionPolicy Bypass -File .\debloat_extra.ps1 -Restore   (vrati zpet)
param([switch]$Restore)

$adb = if (Test-Path (Join-Path $PSScriptRoot "adb.exe")) { Join-Path $PSScriptRoot "adb.exe" } else { "adb" }
$line = & $adb devices | Select-String "`tdevice\s*$" | Select-Object -First 1
if (-not $line) { Write-Host "Zadne funkcni zarizeni. Zkontroluj: .\adb devices"; exit 1 }
$serial = $line.ToString().Split("`t")[0].Trim()
Write-Host "Zarizeni: $serial"
function A { & $adb -s $serial @args }

$remove = @(
  "com.google.android.apps.tv.dreamx",     # Ambient sporic Google TV, ~220 MB RAM pri behu
  "com.google.android.marvin.talkback",    # TalkBack (cteni obrazovky) - nepouziva se
  "com.amlogic.voicesubtitle",             # Amlogic "AI titulky z hlasu", 2 procesy stale v RAM
  "com.oemservice",                        # x.cpe - TR-069 vzdalena sprava operatorem (remote reset, cteni obrazovky)
  "com.android.dynsystem",                 # Dynamic System Updates (GSI) - na TV k nicemu
  "com.android.hotspot2.osulogin",         # Passpoint OSU prihlaseni
  "com.android.devicelockcontroller",      # Device Lock pro zarizeni na splatky
  "com.android.cameraextensions"           # rozsireni kamery - box nema kameru
)

# Puvodni sporic (dreamx Backdrop) -> po odstraneni zakladni "Barvy"
$dreamOld = "com.google.android.apps.tv.dreamx/.service.Backdrop"
$dreamNew = "com.android.dreams.basic/com.android.dreams.basic.Colors"

if ($Restore) {
  foreach ($p in $remove) {
    $r = A shell cmd package install-existing --user 0 $p 2>&1
    Write-Host ("{0,-45} {1}" -f $p, ($r -join " "))
  }
  A shell settings put secure screensaver_components $dreamOld
  Write-Host "Obnoveno. Doporucuji restart: .\adb reboot"
  exit 0
}

foreach ($p in $remove) {
  $r = A shell pm uninstall -k --user 0 $p 2>&1
  Write-Host ("{0,-45} {1}" -f $p, ($r -join " "))
}
A shell settings put secure screensaver_components $dreamNew
Write-Host "Sporic nastaven na: $dreamNew"
Write-Host "Zpet: powershell -ExecutionPolicy Bypass -File .\debloat_extra.ps1 -Restore"
