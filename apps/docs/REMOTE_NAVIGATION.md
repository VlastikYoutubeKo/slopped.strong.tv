# Ovládání dálkovým ovladačem

- Horní lišta: ◀▶ mezi kartami (obsah se přepne po 280 ms zastavení), ▼ nebo OK = do obsahu, Zpět z obsahu = na lištu, Zpět na liště = Domů, pak ukončení.
- Seznamy drží zaostřenou položku na pevném místě (`FocusLayoutManager`) a plynule posouvají obsah. Při přepočtu výpisu se fokus vrací podle klíče položky, ne podle pozice (`SimpleAdapter.submit` při shodném obsahu jen překreslí, nic nerozbije fokus).
- Dlouhý OK = možnosti (kanál, zdroj, film). V přehrávači: ▲▼/CH± přepne kanál, ◀▶ posun ±10 s u filmů, OK info lišta / možnosti, Zpět konec.
- Časová osa: ▲▼ kanály, ◀▶ předchozí / další pořad, OK přehrát.
- Klávesnice se neotvírá sama (zachytila by šipky); otevře se stiskem OK na poli. Hledání a zadání zdroje se dá udělat z telefonu (QR + PIN).
- Menu a překryvy mají vlastní posouvání; Zpět je zavře a vrátí fokus.