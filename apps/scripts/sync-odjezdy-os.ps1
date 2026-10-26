# sync-odjezdy-os.ps1 - prevezme tabuli z odjezdy-system (app/) do aplikace Odjezdy TV.
# Spust po kazde zmene v odjezdy-system a pak znovu sestav: .\gradlew.bat :odjezdy:assembleRelease
# Systemove moduly (updater, system, displays, admin) se nekopiruji: na Androidu je nahrazuje assets/shim.js.
param([string]$OsDir = "..\odjezdy-system\app")

$dst = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\odjezdy\src\main\assets\app"))
if (-not (Test-Path "$OsDir\board.js")) { Write-Host "Nenalezeno: $OsDir\board.js"; exit 1 }
if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
New-Item -ItemType Directory -Force $dst | Out-Null

# Beze zmen: tabule, vektorovy renderer, fonty (BUSE + TTF), index zastavek a regionu, motivy, ikony PID, opentype.js
foreach ($f in "board.js", "vector.js", "font.json", "stops.json.gz", "regions.json.gz", "VERSION") { Copy-Item "$OsDir\$f" "$dst\$f" }
foreach ($d in "themes", "fonts", "assets", "vendor") { Copy-Item "$OsDir\$d" "$dst\$d" -Recurse }
Get-ChildItem $dst -Recurse -File | Where-Object { $_.Extension -in ".md" } | Remove-Item

$files = Get-ChildItem $dst -Recurse -File
"{0} souboru, {1:N1} MB, verze {2}" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB), (Get-Content "$dst\VERSION")
