# Log změn – 4K Google TV Box (Strong HP4624gb / SkyworthDigital, Amlogic HP46A)

Zařízení: `adb-142423072502844-mSizzy._adb-tls-connect._tcp` (bezdrátové ladění, mDNS)
Android 14 (SDK 34), build `UKG3.260128.001 / C3.0.12_20260904`, ABI **armeabi-v7a (32 bit)**,
RAM 1,9 GB + zram 478 MB, /data 8,9 GB (volno 5,3 GB), 1920×1080 @ 320 dpi, 4 jádra.

Obecné vrácení:
- odinstalovaný systémový balíček: `.\adb shell cmd package install-existing --user 0 <balíček>`
- vypnutý balíček: `.\adb shell pm enable <balíček>`
- nainstalovaná aplikace: `.\adb uninstall <balíček>`
- nastavení: hodnoty před změnou jsou v `settings_global_before.txt`, `settings_secure_before.txt`, `settings_system_before.txt`
- seznamy balíčků před změnou: `packages_before_original.txt` (nepřepisuje se), `packages_system_before.txt`, `packages_disabled_before.txt`

## 2026-10-25 – průzkum
- Stav před zásahem: uptime 5 dní, load average 12–17, MemAvailable ~190 MB, **swap 100 % plný**.
  Nejvíc paměti: Spotify 240 MB, sporič dreamx 224 MB, system 141 MB, SmartTube 125 MB, launcherx 76+45 MB.
- Max (`com.wbd.stream`), YouTube Music, iQIYI jsou už systémem „archivované“ (jen stub).
- Restart před měřením výchozího stavu (`.\adb reboot`), aby bylo srovnání před/po férové.
- **Po restartu se vypnulo bezdrátové ladění** (TLS mDNS služba zmizela). Box ale poslouchá
  klasický adb na `192.168.1.84:5555` (`service.adb.tcp.port=5555`, bylo nastaveno už dřív)
  a PC je autorizované → `.\adb connect 192.168.1.84:5555`, bez nového párování.
  ⚠️ Port 5555 je otevřený v celé domácí síti (přístup ale chrání RSA autorizace).
- Výchozí měření: `measure_before.txt` (skript `measure.ps1`).

## Debloat 1 – `debloat.ps1` (tvůj skript, beze změn)
Ověřeno proti `pm list packages`: všech 21 balíčků existovalo, žádný nepatří mezi chráněné.
Spuštěno, výstup v `debloat_run.log`, všech 21 = Success.
com.oem.qa, com.sdt.producttest, com.google.android.feedback, com.android.tv.feedbackconsent,
com.android.adservices.api, com.android.ondevicepersonalization.services,
com.android.federatedcompute.services, com.android.healthconnect.controller,
com.android.health.connect.backuprestore, com.android.nearby.halfsheet, com.android.printspooler,
com.android.htmlviewer, com.android.wallpaperbackup, com.android.sharedstoragebackup,
com.android.localtransport, com.android.backupconfirm, com.google.android.syncadapters.calendar,
com.google.android.play.games, com.google.android.youtube.tvmusic, com.iqiyi.i18n.tv, com.neon.sleeptvtimer
Skript taky nastavil animace na 0.5 a spustil `pm trim-caches 999G`.
- **Vrácení:** `powershell -ExecutionPolicy Bypass -File .\debloat.ps1 -Restore`
  (animace zpět: `.\adb shell settings put global window_animation_scale 1` + `transition_animation_scale 1` + `animator_duration_scale 1`)

## Debloat 2 – `debloat_extra.ps1` (nový skript)
| balíček | proč | výsledek |
|---|---|---|
| com.google.android.apps.tv.dreamx | Ambient spořič, při běhu ~220 MB RAM (2. největší proces) | Success |
| com.google.android.marvin.talkback | TalkBack, nepoužívá se | Success |
| com.amlogic.voicesubtitle | „AI titulky z hlasu“, 2 procesy trvale v RAM (~100 MB RSS) | Success |
| com.oemservice | x.cpe = TR‑069 vzdálená správa operátorem (remote factory reset, čtení obrazovky, injektování kláves) | Success |
| com.android.dynsystem | Dynamic System Updates (GSI) | Success |
| com.android.hotspot2.osulogin | Passpoint OSU | Success |
| com.android.devicelockcontroller | Device Lock pro zařízení na splátky | Failure (APEX modul – nejde, nevadí) |
| com.android.cameraextensions | box nemá kameru | Success |

Spořič přepnut: `screensaver_components` `com.google.android.apps.tv.dreamx/.service.Backdrop` → `com.android.dreams.basic/com.android.dreams.basic.Colors`.
- **Vrácení:** `powershell -ExecutionPolicy Bypass -File .\debloat_extra.ps1 -Restore` (vrátí i spořič)
- Ponecháno záměrně: Prime Video, AirPlay, Pigeoncast, Lepší TV, TV Player (uživatelské aplikace),
  setup wizard, Chromecast (mediashell), Google TTS (používá Assistant), com.google.android.tv.axel,
  partnersetup, ctsshim (mohou rozbít aktualizace / nastavení).

## Optimalizace (bez rootu)
| změna | proč | vrácení |
|---|---|---|
| animace window/transition/animator = **0.5** (nastavil debloat.ps1, ponecháno) | svižnější UI. 0 dělá Google TV UI trhané, 0.5 je dobrý kompromis | `settings put global window_animation_scale 1` (+ transition, animator) |
| `settings put global wifi_scan_always_enabled 0` (bylo `null` = výchozí zapnuto) | Wi‑Fi skenování pro polohu na pozadí | `settings delete global wifi_scan_always_enabled` |
| `settings put global ble_scan_always_enabled 0` (bylo `1`) | BLE skenování pro polohu na pozadí. Spárovaný BT ovladač to neovlivní | `settings put global ble_scan_always_enabled 1` |
| `settings put global send_action_app_error 0` (bylo `1`) | neposílat hlášení pádů (feedback app je stejně odstraněná) | `settings put global send_action_app_error 1` |
| `device_config put activity_manager max_cached_processes 20` (bylo 32) | méně procesů v cache = méně swapování do zram (swap byl 100 % plný) | `device_config delete activity_manager max_cached_processes` |
| `cmd appops set <app> RUN_ANY_IN_BACKGROUND ignore` pro Prime Video, Disney+, Max, Lepší TV, TV Player (bylo `allow`) | nesmí běžet na pozadí, když je nepoužíváš. V popředí fungují normálně | `cmd appops set <app> RUN_ANY_IN_BACKGROUND allow` |
| `cmd package compile -m speed-profile -a` | AOT kompilace podle profilů = rychlejší start | `cmd package compile --reset -a` (vrátí výchozí stav) |
| `pm trim-caches 999G` | vyčištění cache aplikací | – (cache se znovu vytvoří) |

Výsledek kompilace: `/data` volné místo 5,6 → 5,9 GB (debloat uvolnil víc, než kompilace zabrala).

Záměrně nezměněno: Spotify, Kodi, SmartTube, Stremio, AirPlay, Pigeoncast a Vodafone TV smí na pozadí
(hudba / přijímání castu / nahrávání). Netflix taky, protože plní řádek „Pokračovat ve sledování“ v launcheru.
Play Protect a ostatní bezpečnostní nastavení jsem nechal beze změny.

## Nainstalované aplikace (oficiální zdroje, ověřené)
APK jsou ve složce `apk_downloads\`. U každého jsem ověřil balíček, minSdk ≤ 34, armeabi-v7a a podpis autora (`apksigner`).
| aplikace | balíček | verze | zdroj | vrácení |
|---|---|---|---|---|
| VLC | org.videolan.vlc | 3.7.1 armeabi-v7a (SHA-256 sedí s .sha256 z VideoLAN) | get.videolan.org | `.\adb uninstall org.videolan.vlc` |
| X-plore (správce souborů) | com.lonelycatgames.Xplore | 4.46.00 | lonelycatgames.com (web autora) | `.\adb uninstall com.lonelycatgames.Xplore` |
| LocalSend (posílání souborů z PC/mobilu, open source, místo „Send Files to TV“) | org.localsend.localsend_app | 1.18.2 arm32v7 | GitHub releases | `.\adb uninstall org.localsend.localsend_app` |
| Projectivy Launcher | com.spocky.projengmenu | 4.72 | GitHub releases (spocky/miproja1) | `.\adb uninstall com.spocky.projengmenu` |
| TV Bro (prohlížeč pro TV, verze se systémovým WebView) | com.phlox.tvwebbrowser | 2.1.6 | GitHub releases | `.\adb uninstall com.phlox.tvwebbrowser` |
Nic placeného ani s předplatným. Projectivy má volitelný „Premium“ nákup v aplikaci, základ je zdarma.

### Launcher
- Zkoušeno: `cmd package set-home-activity com.spocky.projengmenu/.ui.home.MainActivity`. Příkaz prošel, ale Google TV
  ho ignoruje a HOME dál otevírá launcherx. **Spolehlivě to nejde, proto výchozí launcher změněný není.** Předvolbu jsem vrátil:
  `cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity`.
- launcherx jsem nevypínal (`pm disable-user`), protože na Google TV by to rozbilo profily, záložku Aplikace a výsledky Asistenta.
- Pokud chceš Projectivy na tlačítku HOME: v Projectivy → Nastavení → „Přepsat tlačítko Domů“ (používá jeho vlastní
  službu přístupnosti, zapínáš ji sám a vypneš ji tamtéž).

## Vlastní aplikace (zdrojáky: `..\tv-tools`, build `.\gradlew.bat assembleRelease`)
| aplikace | balíček | co dělá | oprávnění | vrácení |
|---|---|---|---|---|
| TV Nástroje | cz.plain.tvtools | monitor RAM/úložiště/CPU/uptime/IP, přepínání animací, seznam aplikací s rychlým spuštěním a detailem, zkratky do nastavení | ACCESS_NETWORK_STATE; WRITE_SECURE_SETTINGS **udělené ručně přes adb** (jen kvůli animacím) | `.\adb uninstall cz.plain.tvtools` (oprávnění zvlášť: `pm revoke cz.plain.tvtools android.permission.WRITE_SECURE_SETTINGS`) |
| Odjezdy TV | cz.plain.odjezdytv | tabule z Odjezdy OS (`image/app/board.js` beze změn, BUSE font, 6 motivů, menu „Kam to dnes bude“, hledání, kraje) + **spořič obrazovky** | INTERNET | `.\adb uninstall cz.plain.odjezdytv` |
| TV Stream | cz.plain.tvstream | živé TV z Xtream Codes a TVHeadend (Basic/Digest), průvodce s EPG, loga, přehrávač Media3 | INTERNET | `.\adb uninstall cz.plain.tvstream` |

- `pm grant cz.plain.tvtools android.permission.WRITE_SECURE_SETTINGS`: tlačítko „Animace“ v TV Nástrojích. Bez něj aplikace jen ukáže návod.
- **Spořič obrazovky:** `settings put secure screensaver_components cz.plain.odjezdytv/.BoardDream`
  (předtím `com.android.dreams.basic/com.android.dreams.basic.Colors` z debloatu a původně `com.google.android.apps.tv.dreamx/.service.Backdrop`).
  Vrácení: `settings put secure screensaver_components com.android.dreams.basic/com.android.dreams.basic.Colors`,
  nebo `debloat_extra.ps1 -Restore` pro původní Ambient.
- Test spořiče: dočasně `settings put system screen_off_timeout 30000` a pak hned zpět na původních `600000` (ověřeno).
- PC zkratky: `..\tv-tools\scripts\tv.ps1` (status, clean, anim, reboot, home, connect, screenshot).

## Měření před / po (`measure.ps1`, po restartu, 3 běhy, průměr)
| | před (19:09, load 4,9) | po (20:18, load 5,2) | rozdíl |
|---|---|---|---|
| Launcher Google TV, studený start | 694 ms | 520 ms | **−25 %** |
| SmartTube, studený start | 1471 ms | 1556 ms | +6 % (v rámci šumu, rozptyl 1393–1840) |
| Obchod Play, studený start | 1600 ms | 1596 ms | beze změny |
| MemAvailable | 452 MB | 564 MB | **+112 MB (+25 %)** |
| balíčků | 154 | 135 | −27 bloat, +8 nových aplikací |
| /data volné | 5,6 GB | 5,7 GB | +0,1 GB i s 8 novými aplikacemi |
První měření „po“ (3 min po startu, load 10,5) vyšlo hůř, protože box po restartu na pozadí kompiloval. Proto jsem měřil znovu (`measure_after2.txt`).
Stav před zásahem (5 dní bez restartu): MemAvailable ~190 MB, swap 100 % plný, load 12–17.

## Závěrečná kontrola po restartu (20:13)
Box naběhl za ~45 s, adb na 5555 funguje, 135 balíčků, všech 8 nových aplikací je nainstalovaných, animace 0.5,
spořič `cz.plain.odjezdytv/.BoardDream`, výchozí launcher zůstává Google TV.
