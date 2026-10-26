# Vizuální review TV Stream

Prohlíženo na skutečném zařízení (Strong HP4624gb, Android 14, 1920×1080) přes `adb` (`screencap`, `uiautomator`). Každý screenshot jsem otevřel a vizuálně posoudil; odkazy jsou v `docs/screenshots/`. Rozlišení 1280×720 jsem emuloval (`wm size 1280x720` + `wm density 213`, tedy stejná šířka v dp jako 1080p, jako to dělají skutečné 720p televize) a po testu vrátil. Žádný emulátor se nepoužil.

## Co bylo skutečně zkontrolováno

| # | Obrazovka | 1080p | 720p | Stav |
|---|---|---|---|---|
| 1 | Home (hero, police, fokus) | ✅ | ✅ | testováno |
| 2 | Výběr zdrojů (karty, chyba) | ✅ | ✅ | testováno |
| 3 | EPG kompaktní (3 sloupce) | ✅ | ✅ | testováno |
| 4 | EPG časová osa | ✅ | – | testováno **jen se stavem „Bez programu“**: u dostupných poskytovatelů nemají kanály program, bloky pořadů jsem na zařízení neviděl |
| 5 | Live přehrávač + info lišta | ✅ (načítání) | – | testováno částečně; lišta s programem vidět nebyla |
| 6 | Rádio: seznam, přehrávač, orb, spektrum, vlna, vypnuto | ✅ | ✅ (seznam) | testováno se skutečným ICY streamem |
| 7 | Filmy / detail filmu | ✅ | – | testováno |
| 8 | Seriály / epizody (náhledy, pozadí) | ✅ | – | testováno |
| 9 | Hledání | ✅ | – | testováno |
| 10 | Nastavení | ✅ | – | testováno |
| 11 | Dialogy: menu kanálu, možnosti přehrávače, nastavení akcentu, správa zdroje | ✅ | – | testováno |
| 12 | Stavy: načítání (přehrávač, rádio „zkouším znovu“), prázdno (Oblíbené), chyba (karta zdroje) | ✅ | – | testováno; **konečný červený stav chyby přehrávače jsem nezachytil** (aplikace nejdřív 3× zkouší znovu) |

Neověřeno: čtení TalkBackem (na boxu není), přehrávač živé TV s programem, časová osa s bloky pořadů, vzhled při jiné barvě akcentu, vzhled s velkým písmem systému.

## Nalezené problémy a opravy

Závažnost: **C**ritical (nepoužitelné / nefunkční) · **M**ajor (zřejmě špatné) · m**I**nor · **P**olish.

| Sev. | Problém (pozorováno na screenshotu) | Oprava | Před → po |
|---|---|---|---|
| M | Zaostřený průhledný povrch: záře z fokusu prosvítala výplní, uvnitř karty a tlačítka byl tmavý obdélník | zaostřená plocha je vždy nepropustná | [před](screenshots/1080_home_focus-shelf_before.png) → [po](screenshots/1080_home_focus-shelf.png) |
| M | Home hero: pravá polovina banneru prázdná, nadpis Home sežraný scrollem | hero se skládá jako řádek s pevným boxem pro plakát/ikonu; bez plakátu akcentový přechod + ikona; co se vejde, se neposouvá | [před](screenshots/1080_home_tabfocus_before.png) → [po](screenshots/1080_home_focus-hero.png) |
| M | Časová osa: datum se překrývalo s popiskem první půlhodiny, čas přetékal do pásu s mřížkou | datum má vlastní řádek, popisky půlhodin pod ním | [před](screenshots/1080_live_timeline_before.png) → [po](screenshots/1080_live_timeline_focus.png) |
| M | Rádio mělo přepínač „Časová osa“ a zobrazilo prázdnou časovou osu | časová osa jen pro televizi; rádio vždy kompaktně | [před](screenshots/1080_radio_timeline-bug_before.png) → [po](screenshots/1080_radio_compact.png) |
| M | Radio přehrávač: čtvercový obal mimo střed orbu, paprsky přesahovaly do textu | kruhový obal přesně uprostřed orbu (střed se bere ze skutečné polohy), paprsky kratší, mezera před textem | [před](screenshots/1080_radio_player_orb_before.png) → [po](screenshots/1080_radio_player_orb.png) |
| M | Seriál: dlouhý popis zabral 7 řádků a epizody vyjely z obrazovky | popis max. 3 řádky, epizody vidět hned | po |
| M | Chyba sítě ve zdroji ukazovala `unexpected end of stream on http://…` včetně adresy | srozumitelná věta + `Redact.url` na všech chybových textech (test) | po |
| I | Po návratu z přehrávače skočil fokus na horní lištu | hlavní okno si pamatuje zaostřený prvek a po návratu ho vrátí (ověřeno: stejný prvek před i po) | – |
| I | U karty zdroje a v hledání svítila v liště nesouvisející karta jako „aktivní“ | pod otevřeným detailem/hledáním není žádná karta aktivní | po |
| I | Záře fokusu u plakátu obklopovala i text pod ním | obrys jen kolem plakátu | po |
| I | Info lišta přehrávače: bez programu se název kanálu opakoval dvakrát; čitelnost nad videem | nahoře číslo + zdroj, scrim silnější | – |
| I | Radio bez loga: šedá deska, na rádiu se točil spinner přes rádio vrstvu | akcentový kruh s monogramem, spinner jen nad videem | [po](screenshots/1080_radio_player_bars.png) |
| I | Film s délkou „1 min“ (poskytovatel posílá nesmysl) | délky < 5 min se nezobrazují | po |
| I | Kompaktní Živě: nadpisy skupin a názvy kanálů příliš zkrácené, počet zmáčknutý vedle názvu | počet pod názvem skupiny, sloupce přerozdělené (204 / střed / 252 dp) | [po](screenshots/1080_live_compact.png) |
| P | Kontrast drobného textu (`MUTED`) 3,2–4,1:1 | `#8394AA`, ≥ 4,4:1 na všech površích (viz DESIGN_SYSTEM.md) | – |
| P | Různá zaoblení (12/14/16 dp) u podobných prvků | `R_CTRL 14` pro ovladatelné prvky | – |

## Remote navigation review (D-pad, skutečné zařízení)

| Test | Výsledek |
|---|---|
| Rychlé držení ▼ (40 stisků za sebou) a ▲ (50) v seznamu 64 000 kanálů | všechny stisky se projeví, fokus je vždy na pojmenovaném prvku, aplikace žije |
| Cik-cak skupiny ⇄ seznam (6×) | fokus vždy na platném kanálu |
| 12× ▶ a 12× ◀ po horní liště | fokus nikdy nezmizí |
| Dlouhý OK → menu → Zpět | menu se zavře, fokus se vrátí na prvek |
| Přehrávač OK / Zpět 3× | vždy zpět na původní prvek |
| Poznámka | jeden příkaz `adb input keyevent` s 10–40 klávesami najednou posílá události ve stejné milisekundě a systém jich většinu zahodí; skutečný ovladač (auto-repeat ≥ 30 ms) ani jednotlivé `adb` příkazy tím netrpí. Není to chyba aplikace |

## Skóre

### Design System Quality: 86 / 100 (dříve 74)
| Oblast | Před | Po |
|---|---|---|
| Typografie | 24 velikostí | 9 pojmenovaných tokenů, 0 literálů ve `label(...)` |
| Fokus | 6 hodnot zvětšení | 4 tokeny podle typu prvku, 0 literálů |
| Barvy mimo `Design.kt` | 6 míst | 0 |
| Zaoblení ovladatelných prvků | 12/14/16 | `R_CTRL` |
| Komponenty řádků | 4 kopie | 1 základ `ListRow` + 4 varianty |
| Přístupnost | 2 popisky | popisky + stavy + dekorativní prvky + live regiony (23 míst) |
| Rozestupy | ~380 literálů | `Sp` (189 použití); 360 literálů zůstává, z velké části vnitřní rozměry komponent |
| Dokumentace | tokeny a fokus | tokeny, fokus, komponenty, přístupnost, pravidla výjimek |
Proč ne 90+: část z 360 literálů dp jsou skutečné rozestupy, které ještě nejsou na škále; Nastavení a karta zdroje nejsou na `ListRow`; token-lint není automatický; TalkBack se na zařízení neověřil.

### Visual UI/UX Quality: 84 / 100
Skóre vychází jen z pozorování popsaných výše (screenshoty 1080p, částečně 720p).
| Oblast | Body | Odůvodnění |
|---|---|---|
| Hierarchie a kompozice | 15 / 20 | Jasné: titul → akce → police. Hero a detaily mají silné těžiště. Slabší: hero bez plakátu je jen ikona na přechodu; časová osa bez programu vypadá prázdně (pravda poskytovatele, ne chyba) |
| Typografie a čitelnost | 13 / 20 | Outfit + JetBrains Mono funguje (čísla a metadata jsou mono, nadpisy široké). Drobný text 12,5–14 sp je na hraně pro 3–4 m; dlouhé názvy kanálů od poskytovatelů jsou zkrácené (`(Apple) (MLS) 001: …`), plný název je až v detailu |
| Spacing a konzistence | 14 / 20 | Okraje 48 dp, karty a řádky stejně zaoblené; hero a horní lišta jsou nyní vyvážené. Jemné nekonzistence: výška řádků ve hledání a Živě není stejná |
| Fokus a D-pad | 17 / 20 | Fokus je okamžitě čitelný (rámeček + záře), vybraný ≠ zaostřený, obnovení po změně obsahu i návrat z přehrávače ověřeny. Odečet: nezkoušeno s opravdovým ovladačem a jeho auto-repeat (jen stisky po ≥ 100 ms a nárazové sekvence) a jen na jednom panelu |
| Barvy a kontrast | 13 / 20 | Tmavá paleta soudržná, akcent drží identitu; fialová je na řadě míst dominantní (rámečky, ikony, orb). MUTED opraven, glass povrchy jednotné; povrchy jsou spíš ploché |
| Premium feel | 12 / 20 | Vlastní identita (skleněné povrchy, orb, hero), ale bez zásahu designéra: chybí vlastní ilustrace stavů, úvodní animace a hlubší vrstvení; bez dodaných obrázků působí hero skromně |

## Omezení testovacího prostředí
- Jediné zařízení (1080p panel); 720p je jen emulace rozlišení.
- Žádný čtečkový nástroj (TalkBack) ani kontrola velkého systémového písma.
- Poskytovatelé v testu nemají EPG pro většinu kanálů, proto jsou časové osy bez pořadů; vzhled bloků pořadů je ověřen jen v kódu a unit testech geometrie (`TimelineTest`).
- Výkon vizualizéru jsem neměřil přesně (jen průběžně `top` ~85–90 % jednoho jádra aplikace při přehrávání).
