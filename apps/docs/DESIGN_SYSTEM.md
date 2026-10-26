# TV Stream – designový systém

Vše je v `Design.kt` (tokeny, povrchy, fokus, pomocné komponenty) a `Components.kt` (sdílené pohledy).
Obrazovky nemají vlastní barvy, velikosti písma ani zvětšení při fokusu; používají tokeny níže.

## Tokeny

### Barvy
| Token | Hodnota | Použití |
|---|---|---|
| `BG` / `ELEVATED` | `#0B0D14` / `#101420` | pozadí, vyvýšené plochy |
| `SURFACE` | `#161A26` při 16 % | ploché skleněné povrchy bez obrysu (minimalistický styl) |
| `SURFACE_SOLID` | `#141826` | velké textové plochy a dialogy (průhlednost zhoršuje čitelnost) |
| `FOCUS_SURFACE` | `#262C40` při 60 % | povrch při fokusu (průhledné prvky) |
| `TEXT` / `TEXT2` / `MUTED` | `#F8FAFC` / `#94A3B8` / `#8394AA` | text. Kontrast ≥ 4,4:1 i na `FOCUS_SURFACE` (MUTED dřív `#64748B` = 3,2:1) |
| `accent` | fialová `#8B5CF6` | konfigurovatelný (modrá, azurová, zelená, oranžová, růžová) |
| `LIVE` / `OK` / `ERROR` / `WARNING` | `#EF4444` / `#22C55E` / `#F87171` / `#E9B44C` | stavy |
| `PLACEHOLDER` | `#1B2030` | podklad chybějícího loga / plakátu |

### Typografie (`Typo`, sp)
| Token | sp | Použití |
|---|---|---|
| `BADGE` | 11,5 | odznaky kvality, „ŽIVĚ“ |
| `CAPTION` | 12,5 | nadpisy sekcí verzálkami, nápověda, datum synchronizace |
| `SMALL` | 14 | metadata, popisky, časy |
| `BODY` | 16 | běžný text, popisy |
| `LABEL` | 18 | názvy v řádcích, tlačítka, položky menu |
| `TITLE` | 22 | nadpisy polic, hodnoty |
| `HEADING` | 28 | nadpisy dialogů a karet |
| `HEADLINE` | 32 | nadpisy obrazovek, detail kanálu |
| `DISPLAY` | 40 | titul filmu, velký text přehrávače |

Písma: **Outfit** (proměnné, váha 300–700) pro UI, **JetBrains Mono** pouze pro čísla, časy, adresy, odznaky a diagnostiku.
**Povolené výjimky:** text kreslený přímo na `Canvas` (časová osa EPG: pořady 14 sp, hlavička 12/13 sp – drží se rozměry bloku, ne škálou) a monogramy log (velikost se odvozuje od dlaždice).

### Rozestupy (`Sp`, dp)
`XS 4 · S 8 · M 12 · L 16 · XL 24 · XXL 32 · XXXL 48` (okraj obrazovky = `XXXL`).
Nové komponenty používají jen tyto tokeny. **Povolené výjimky:** vnitřní rozměry komponent (výška řádku 72, šířka sloupce 204, velikost loga, 3 dp lišta průběhu, 1–3 dp mezery uvnitř textů) – ty jsou vlastností komponenty, ne rozestupem.

### Zaoblení (dp)
`R_XS 6` odznaky · `R_SM 10` náhledy · `R_CTRL 14` ovladatelné prvky (tlačítka, čipy, karty horní lišty, řádky, položky menu) · `R_STD 16` plakáty, pole · `R_LG 22` karty a panely.

### Pohyb
`DURATION 250 ms`, `EASE` (0,25 / 0,46 / 0,45 / 0,94). Snížený pohyb: bez zvětšení a animací, vizualizér kreslí klidný snímek. Vysoký kontrast: silnější obrysy a méně průhledné povrchy.

## Fokus
Jediný pojem „zaostřeno“: zvětšení podle typu prvku, akcentový rámeček 2 dp, záře (`outlineSpotShadowColor`), 250 ms. **Vybráno / aktivní** je jiný stav (`isActivated`): tmavší plocha s akcentem + tenký rámeček; zaostřený prvek ho přebije.

| Token | Zvětšení | Typ prvku |
|---|---|---|
| `SCALE_FIELD` | 1,01 | textová pole, velké plochy |
| `SCALE_ROW` | 1,02 | řádky seznamů, položky menu, nastavení |
| `SCALE_CHIP` | 1,03 | čipy, segmenty |
| `SCALE_CARD` | 1,04 | karty, plakáty, tlačítka, karty horní lišty |

Pravidla: (1) rodiče zvětšovaného prvku mají vypnuté ořezávání (`Design.unclip`), seznamy, které by zvětšení přesahovalo do sousedů, ořezávají na okraji kontejneru; (2) nepropustný povrch se při fokusu nikdy nestává průhledným (záře jinak prosvítá); (3) `PosterCard` má obrys jen kolem plakátu, záře tedy neobklopuje text pod ním.
Obnovení fokusu: seznamy se při změně dat aktualizují na místě (`SimpleAdapter.submit` překreslí, nemění-li se obsah; `LiveItem`, `CatalogEntry`, `RecEntry` mají identitu podle klíče). Po návratu z přehrávače `MainActivity` vrací fokus na původní prvek.

## Komponenty

| Komponenta | Varianty | Stavy | Poznámka |
|---|---|---|---|
| `GlassDrawable` | průhledná / `solid` | default, focused, activated, vysoký kontrast | základ všech povrchů |
| `primaryButton` / `glassButton` / `chip` | primární, skleněné, malý čip | default, focused | `focusable` + popisek |
| `ListRow` | `ChannelRow`, `RecRow`, `EpisodeRow`, search `Row` | default, focused, bez programu, bez náhledu | vodítko (logo/náhled/číslo) · titul · podtitul · průběh · odznak · koncovka; specializace jen přidají vodítko a data |
| `ChannelCard`, `PosterCard`, `RadioCard` | karta kanálu, plakát, stanice | default, focused, bez loga (monogram), rozkoukáno | |
| `Shelf` | police s kartami | prázdná se skryje | |
| `LogoTile` | loga s monogramem | načítá se / načteno / bez loga | `padDp` pro velké kruhové varianty |
| `SmoothScrollView`, `FocusLayoutManager` | kotva fokusu 35–40 % | | co se vejde, se neposouvá (nadpis a hero zůstanou) |
| `TimelineView` | EPG, nahrávky | focused pořad, „Bez programu“, datum okna | virtualizovaná, `Timeline` = čistá matematika |
| `VisualizerView` | orb, spektrum, vlna, vypnuto | ambientní (bez analýzy), skutečné FFT | kvalita se sama snižuje (`QualityGovernor`) |
| Menu / dialog (`showMenu`, `askText`) | výběr, zadání textu | focused, selected, danger | posouvá se, Zpět zavře a vrátí fokus |
| Info lišta přehrávače | TV, film | načítání, chyba, bez programu | nápověda ovládání vpravo |

## Přístupnost (TalkBack)
- `describe(text, selected)` nastaví jedinou větu pro celý prvek (karty, řádky, tlačítka, karty horní lišty, položky menu) a stav „vybráno“ (`stateDescription`, API 30+).
- `decorative()` – ikony a loga (`icon()`, `LogoTile`, náhledy) čtečka přeskočí: stejnou informaci nese text vedle (zamezení dvojímu čtení).
- `polite()` – stavové texty (načítání, chyby, toast) jsou live region; nepřesouvají fokus.
- Nadpisy sekcí (`caption`) jsou `accessibilityHeading`.
- **Neověřeno:** skutečné čtení čtečkou na boxu (TalkBack tam není nainstalován); popisky jsou ověřené přes `uiautomator dump` (zobrazují se jako `content-desc`).

## Kdy je výjimka povolená
1. Canvas text a monogramy (viz výše).
2. Vnitřní rozměry komponenty (výška, šířka sloupce, velikost náhledu) – ne rozestupy mezi komponentami.
3. Barva mimo tokeny jen s důvodem v komentáři u kódu; nové barvy se přidávají do `Design.kt`.
4. Jiné zvětšení při fokusu jen u prvků, které nejsou ve sloupci s jinými (tlačítko v hero banneru má `SCALE_CARD`).

## Minimalistický styl (v2, OTT)
Reakce na „moc velké a moc bloated“: méně prvků, menší měřítko, ploché povrchy.
- **Měřítko rozhraní** (UiScale, Nastavení → Velikost rozhraní): výchozí je Střední (0,8× dp); Velké vrací původní velikost. Implementováno přes densityDpi obalu kontextu, takže všechny dp tokeny zůstávají beze změn.
- **Horní lišta**: jen text (logo, záložky, hledání, hodiny); aktivní záložka má tenké akcentové podtržení (TabDrawable), ne kartu.
- **Povrchy**: bez obrysů v klidu; obrys a záře jen při fokusu. Řádky nemají pozadí, panely jsou ploché.
- **Bez lišt s nápovědou ovládání** a bez ozdob; čipy a filtry jsou jen text.
- **Home hero**: jen text (titul, popis, tlačítko), plakát jen když existuje.
- Výška řádku kanálu 60 dp, lišta 64 dp.