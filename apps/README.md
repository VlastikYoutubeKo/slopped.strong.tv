# tv-tools – vlastní aplikace pro Android TV box (Strong 4K Google TV, Android 14)

Tři aplikace v jednom Gradle projektu (AGP 9.4, Gradle 9.6, compileSdk 37, targetSdk 34, minSdk 28/30).
Všechny jsou ovladatelné D-padem, mají leanback banner a jsou čitelné z gauče (bezpečné okraje 48/27 dp).

| modul | aplikace | balíček |
|---|---|---|
| `app` | **TV Nástroje**: monitor RAM/úložiště/CPU, animace, seznam aplikací, zkratky do nastavení | `cz.plain.tvtools` |
| `odjezdy` | **Odjezdy TV**: tabule z Odjezdy OS + spořič obrazovky | `cz.plain.odjezdytv` |
| `stream` | **TV Stream**: Xtream Codes + TVHeadend, průvodce s EPG, přehrávač | `cz.plain.tvstream` |

## Build a instalace
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
..\platform-tools\adb.exe install -r stream\build\outputs\apk\release\stream-release.apk
```
Release je podepsaný debug klíčem, takže jde rovnou instalovat přes adb. Projekt otevřeš i v Android Studiu (File → Open → tato složka).

## TV Nástroje (`app`)
- Monitor: volná RAM, úložiště, frekvence CPU, uptime, IP. Teplotu a zátěž Android 14 běžné aplikaci nedovolí číst, proto chybí.
- Animace 1× → 0,5× → vypnuto potřebují oprávnění, které jde dát jen z PC:
  `adb shell pm grant cz.plain.tvtools android.permission.WRITE_SECURE_SETTINGS`
- „Uvolnit RAM“ jsem úmyslně neudělal: od Androidu 14 smí `killBackgroundProcesses()` ukončit jen vlastní procesy.
  Ukončení procesů, čištění cache a restart dělá PC skript `scripts\tv.ps1`.

## Odjezdy TV (`odjezdy`)
- Spouští **beze změn** tabuli z `Desktop\odjezdy-system\app` (verze 0.5.17: `board.js`, `vector.js`, 38 motivů,
  BUSE + TTF písma, opentype.js) ve WebView na plném 1920×1080. LCD motivy (DB, SBB, PID tablo, E-ink…) tak kreslí
  vyhlazeným písmem, LED motivy body.
- `assets/shim.js` napodobí, co tabule potřebuje z Node.js (`fs`, `path`, `zlib`, `os`, `process`, `Buffer` = npm
  `buffer`). Systémovou vrstvu OS nahrazuje neškodnými náhradami: `updater` (nic se nestahuje), `system` (Wi-Fi,
  instalátor, restart), `displays` (jeden displej), `admin` (žádný HTTP server na TV). `/dev/fb0` je `<canvas>`
  a překreslují se jen řádky, které tabule opravdu zapíše.
- `Buffer.prototype.compare` je v shimu přepsaný. Polyfill při každém volání kopíroval celý buffer, což dávalo
  10 s na snímek. Teď 60–130 ms.
- Požadavky na `app.odjezdy.online` a `api.transitous.org` vyřizuje nativní proxy (API neposílají CORS).
  Konfigurace a offline cache se ukládají do úložiště aplikace a sdílí je aplikace i spořič.
- Aktualizace z odjezdy-system: `scripts\sync-odjezdy-os.ps1` a pak nový build.
- Ovládání: OK = Enter (menu / potvrdit), Zpět = Esc / konec, ▲▼ menu, ◀▶ na tabuli = oblíbené zastávky,
  ◀▶ v krajích = stránky, Menu = motiv (T). Při hledání se otevře klávesnice Androidu, Zpět ji zavře a šipky pak vybírají.
- Spořič: Nastavení → Systém → Ambientní režim / Spořič → „Odjezdy TV“ (na boxu už nastaveno).

## TV Stream (`stream`)
- **Xtream Codes**: `player_api.php` (kategorie a kanály čtené po částech, ani 54 000 kanálů nezahltí paměť), stream `.m3u8` (nebo `.ts`).
- **TVHeadend**: `/api/channel/grid`, `/api/channeltag/grid`, EPG `/api/epg/events/grid`, stream `/stream/channel/{uuid}`. Přihlášení
  **Digest i Basic** (OkHttp Authenticator), platí i pro stream a loga. Když server odmítne profil `pass`, zkusí se výchozí profil a pak `matroska`.
- Hesla jsou uložená šifrovaně (AES-GCM, klíč v Android Keystore).
- **Nastavení z telefonu**: Domů → „Nastavit z telefonu“ → QR kód (adresa + PIN). Na telefonu stránka pro přidání, úpravu
  a smazání zdrojů, vložený M3U odkaz se rozebere. Server (`WebSetupServer`, porty 8090–8093) běží jen na té obrazovce,
  každé volání API vyžaduje PIN a po 5 špatných pokusech se vypne. Heslo se zpět do prohlížeče neposílá.
- **EPG**:
  - Xtream: `get_short_epg` bývá u levných poskytovatelů rozbitý (HTTP 500), proto se bere **XMLTV** (`xmltv.php`): jedno stažení
    na pozadí, zpracování proudem (96 MB ≈ 2 min na boxu), uložení na disk (platí 3 h, při výpadku serveru 48 h). Kanály bez
    `epg_channel_id` program nemají a UI to říká.
  - TVHeadend: „teď“ pro všechny kanály jedním dotazem + program po kanálech.
  - Chyba se nikdy neukládá jako „kanál nemá program“, zobrazí se důvod a po minutě se zkusí znovu.
- **Loga**: paměť → disk (max. 50 MB) → síť; neúspěch (např. 502 při DDoS) se pamatuje jen 90 s.
- Průvodce: skupiny | kanály | detail s EPG. ◀ ze seznamu do skupin, OK přehrát. Přehrávač: ▲▼/CH± přepínání, OK info lišta.
- **Testovací server** (Xtream + TVH s Digest + EPG + video):
  ```powershell
  node scripts\fake-iptv-server.mjs
  adb reverse tcp:8099 tcp:8099
  ```
  Na boxu pak zdroj `http://127.0.0.1:8099`. Xtream `demo`/`demo`, TVHeadend `tvh`/`tajne`.
## scripts/
- `tv.ps1`: zkratky z PC (`status`, `clean`, `anim 0|0.5|1`, `reboot`, `home`, `connect`, `screenshot`)
- `sync-odjezdy-os.ps1`: převezme tabuli z Odjezdy OS
- `fake-iptv-server.mjs`: testovací IPTV server

## TV Stream 1.1
Viz docs/ (DESIGN_SYSTEM, PROVIDER_ARCHITECTURE, RADIO_METADATA, REMOTE_NAVIGATION). Testy: gradlew :stream:testDebugUnitTest. Zkušební server: scripts/fake-iptv-server.mjs.

### Design a vizuální kontrola
Docs: docs/DESIGN_SYSTEM.md (tokeny, komponenty, přístupnost) a docs/VISUAL_REVIEW.md (screenshoty z boxu, nálezy, skóre). Screenshoty: docs/screenshots/.
