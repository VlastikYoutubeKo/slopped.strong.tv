package cz.plain.tvstream

/**
 * Rozpoznání rozhlasových stanic mezi kanály. Čistá logika bez Androidu (testovatelná).
 * Pravidla jsou záměrně konzervativní: raději televizi označit jako televizi, než ji skrýt v rádiu.
 * Špatné zařazení jde ručně opravit (UserData.setRadioOverride).
 */
object Classify {
    private val RADIO_CATEGORY = Regex("""(^|[^a-zá-ž])(radio|rádio|radia|rádia|rozhlas|music radio|fm radio)([^a-zá-ž]|$)""", RegexOption.IGNORE_CASE)
    private val RADIO_NAME = Regex("""(^|[^a-zá-ž])(radio|rádio|rozhlas)([^a-zá-ž]|$)|(^|\s)\d{2,3}[.,]\d\s?fm\b""", RegexOption.IGNORE_CASE)
    private val TV_HINT = Regex("""\b(tv|televize|television|televisión|kino|cinema|movie|film|sport|news|hd tv)\b""", RegexOption.IGNORE_CASE)

    fun kind(name: String, categoryNames: Collection<String>, hint: Boolean? = null): ChannelKind {
        if (hint != null) return if (hint) ChannelKind.RADIO else ChannelKind.TV
        if (categoryNames.any { RADIO_CATEGORY.containsMatchIn(it) }) return ChannelKind.RADIO
        // Název sám o sobě: „Radio Impuls“, „Rádio Kiss“, „ČRo Vltava“ (ČRo = Český rozhlas).
        if (RADIO_NAME.containsMatchIn(name) && !TV_HINT.containsMatchIn(name)) return ChannelKind.RADIO
        if (Regex("""^(čro|cro)\s""", RegexOption.IGNORE_CASE).containsMatchIn(name.trim())) return ChannelKind.RADIO
        return ChannelKind.TV
    }
}

enum class ChannelKind { TV, RADIO }

/** Kvalita a jazyk z názvu kanálu („CZ: ČT1 [FHD]“, „DK| DR1 4K“). Jen to, co poskytovatel sám napíše. */
object ChannelMeta {
    class Meta(val quality: String?, val language: String?)

    private val LANG = Regex("""^\s*([A-Za-z]{2,3})\s*[:|\]\-]\s""")
    private val Q4K = Regex("""\b(4k|uhd|2160p?)\b""", RegexOption.IGNORE_CASE)
    private val QFHD = Regex("""\b(fhd|full\s?hd|1080[pi]?)\b""", RegexOption.IGNORE_CASE)
    private val QHD = Regex("""\b(hd|720p?)\b""", RegexOption.IGNORE_CASE)
    private val QSD = Regex("""\b(sd|480p?|576p?)\b""", RegexOption.IGNORE_CASE)

    fun of(name: String): Meta {
        val q = when {
            Q4K.containsMatchIn(name) -> "4K"
            QFHD.containsMatchIn(name) -> "FHD"
            QHD.containsMatchIn(name) -> "HD"
            QSD.containsMatchIn(name) -> "SD"
            else -> null
        }
        return Meta(q, LANG.find(name)?.groupValues?.get(1)?.uppercase())
    }
}
