# Poskytovatelé

Rozhraní `Backend` (`Backends.kt`): `load`, `caps`, `ping`, `streamUrl`, `nowForAll`, `schedule`, `bulkEpg`, `vod*`, `series*`. Implementace: `Xtream`, `Tvheadend`, `StalkerBackend` (`Stalker.kt`). UI se neptá na typ, ale na `Caps` (zjištěné dotazem a uložené v `SourceStats`).

- **Library** drží stav všech zdrojů (načítá ze cache na disku okamžitě, ze sítě když je cache starší než 12 h nebo chybí `caps`). Cache kanálů neobsahuje hesla; adresa streamu se skládá až při přehrání (`Channel.ref` + `Backend.streamUrl`).
- **Xtream**: live + XMLTV (hromadně, uloženo na disk), filmy, seriály. **TVHeadend**: kanály, štítky, EPG, Digest/Basic, profil `pass` → bez profilu → `matroska`. **Stalker/Ministra**: handshake → token (Bearer) → `get_profile` → `get_all_channels` / `get_genres`, odkaz přes `create_link` až při přehrání (`ffmpeg http://…` se očistí), krátké EPG, filmy (bez seriálů).
- Přihlašovací údaje: heslo šifrované klíčem v Android Keystore; `Redact.url` maskuje adresy, jména a hesla v chybách a diagnostice. Zdroj zadaný jako `https://` nelze přesměrováním ani odkazem degradovat na HTTP (`Net.client`); HTTP zdroje jsou označené a při zadávání je upozornění.
- **Stalker ověřen** na skutečném portálu s trial přístupem (placený trial portál, MAC vygenerovaná aplikací): 10 355 kanálů, přehrání ZDF HD. API portálu se hledá postupně: `/server/load.php`, `/portal.php`, `/stalker_portal/server/load.php`… Neověřeno: filmy přes Stalker, seriály nejsou implementovány.
- **Nahrávky (TVHeadend DVR)**: `/api/dvr/entry/grid` + přehrání `/dvrfile/{uuid}`. Ověřeno na skutečném serveru uživatele (370 nahrávek, přehrání 1080p). Karta Nahrávky: seznam nebo časová osa po kanálech.

- Stalker VOD: film se přehrává ve dvou krocích (jako u ostatních klientů): `get_ordered_list&movie_id=ID` vrátí id souboru, pak `create_link` s `cmd=/media/file_<id>.mpg`. Ověřeno na skutečném portálu (film 1080p). Seriály ve Stalkeru používají stejný postup (sezóny a epizody přes `get_ordered_list` s `season_id`/`episode_id`).

- Ministra 5.x (ověřeno na vlastním portálu s Ministra 5.6.5): get_profile chce metriky a otisky z MAC (sn = MD5, device_id = SHA-256, signature, hw_version_2 = SHA-1, timestamp, metrics s andom z handshake) a **nesmí** mít client_type=STB. Aplikace zkouší nejdřív tuto moderní podobu a pokud portál po ní nepustí k seznamu skupin, starší jednoduchou (trial portál ji potřebuje). Kanály vlastního portálu: 2 (jeden Polsat Music), přehrání běží.
