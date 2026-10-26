package cz.plain.tvstream

/**
 * Normalizovaný model „právě hraje“ (viz docs/RADIO_METADATA.md). Všechna pole kromě stanice jsou nepovinná;
 * nikdy se nic nevymýšlí: chybí-li metadata, UI ukáže jen „Právě posloucháte“ + název stanice.
 */
data class NowPlaying(
    val stationId: String,
    val stationName: String,
    val trackTitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** Pořad (EPG), ne skladba. S [trackTitle] se nepleťte. */
    val programTitle: String? = null,
    val startedAt: Long = 0L,
    /** Délka skladby v ms, jen když ji zdroj uvádí. */
    val duration: Long = 0L,
    val metadataSource: Origin = Origin.NONE,
    val lastUpdated: Long = 0L,
) {
    enum class Origin { NONE, ICY, ID3, EPG, CUSTOM }

    val hasTrack: Boolean get() = !trackTitle.isNullOrBlank()

    /** Skladba, která měla už dávno skončit (a nový údaj nepřišel), se nemá ukazovat jako aktuální. */
    fun isStale(now: Long): Boolean = hasTrack && duration > 0 && startedAt > 0 && now > startedAt + duration + 90_000
}

object IcyParser {
    private val BLOCK = Regex("""StreamTitle='(.*?)'\s*(?:;|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
    /** Oddělovač autora a názvu: mezera + pomlčka/pomlčka-čárka + mezera. „Jay-Z“ ani „Hip-Hop“ se nedělí. */
    private val SEP = Regex("""\s[-–—~]\s""")
    private val JUNK = Regex("""(https?://|www\.|\.(cz|com|fm|net|org|sk)\b)""", RegexOption.IGNORE_CASE)
    private val NON_TRACK_PREFIX = Regex("""^(reklama|advert|advertisement|spot|jingle|zprávy|zpravy|news|promo)\b""", RegexOption.IGNORE_CASE)

    /** Surový blok „StreamTitle='…';StreamUrl='…';“ → samotný titulek, nebo null. Zvládne apostrofy uvnitř názvu. */
    fun streamTitle(raw: String): String? {
        val s = raw.trim()
        if (!s.contains("StreamTitle=")) return s.takeIf { it.isNotEmpty() }
        val m = BLOCK.find(s) ?: return null
        return m.groupValues[1].takeIf { it.isNotBlank() }
    }

    /** Opraví typické zkomolení „UTF-8 přečtené jako Latin-1“ (Ã¡ místo á), jinak vrátí text beze změny. */
    fun fixEncoding(s: String): String {
        if (!(s.contains('Ã') || s.contains('Å') || s.contains('Ä'))) return s
        val fixed = try { String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) } catch (_: Exception) { return s }
        return if (!fixed.contains('�') && fixed.length < s.length) fixed else s
    }

    fun clean(s: String): String = fixEncoding(s).replace(Regex("""[\u0000-\u001F\u007F]"""), " ").replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', ' ')

    class Parsed(val artist: String?, val title: String?, val isTrack: Boolean)

    /**
     * Rozbor titulku. Dělí jen na prvním „ - “ s mezerami po obou stranách; dál už nic (název písně smí mít pomlčky).
     * Jméno stanice, odkazy, reklamy a jingly se za skladbu nepovažují.
     */
    fun parse(rawTitle: String, stationName: String? = null): Parsed {
        val t = clean(rawTitle)
        if (t.isEmpty()) return Parsed(null, null, false)
        if (JUNK.containsMatchIn(t) || NON_TRACK_PREFIX.containsMatchIn(t)) return Parsed(null, t, false)
        if (stationName != null && sameText(t, stationName)) return Parsed(null, t, false)
        val m = SEP.find(t) ?: return Parsed(null, t, true)
        val artist = t.substring(0, m.range.first).trim()
        val title = t.substring(m.range.last + 1).trim()
        if (artist.isEmpty() || title.isEmpty()) return Parsed(null, t, true)
        if (stationName != null && sameText(artist, stationName)) return Parsed(null, t, false)   // „Rádio X - Ranní show“
        return Parsed(artist, title, true)
    }

    private fun sameText(a: String, b: String) = Sorting.fold(a) == Sorting.fold(b)
}

/**
 * Drží aktuální stav „právě hraje“ jedné stanice: sloučí události z ICY / ID3 / EPG, zahodí duplicity
 * a přeposílá jen skutečné změny. Čistá logika (bez Androidu).
 */
class NowPlayingTracker(private val stationId: String, private val stationName: String) {
    var current: NowPlaying = NowPlaying(stationId, stationName)
        private set
    private var lastRaw: String? = null
    private var lastRawAt = 0L

    /** Vrací true, když se zobrazovaná data změnila. */
    fun onIcy(rawStreamTitle: String?, now: Long): Boolean {
        val raw = rawStreamTitle?.let { IcyParser.streamTitle(it) } ?: return false
        if (raw == lastRaw && now - lastRawAt < 600_000) return false   // stejná událost znovu (některé servery ji opakují)
        lastRaw = raw; lastRawAt = now
        val p = IcyParser.parse(raw, stationName)
        val next = if (p.isTrack) current.copy(trackTitle = p.title, artist = p.artist, album = null, artworkUrl = null, startedAt = now, duration = 0L,
            metadataSource = NowPlaying.Origin.ICY, lastUpdated = now)
        else current.copy(trackTitle = null, artist = null, album = null, artworkUrl = null, programTitle = p.title ?: current.programTitle,
            metadataSource = NowPlaying.Origin.ICY, lastUpdated = now)
        return commit(next)
    }

    /** ID3 (HLS, MP3): skladba přímo v polích, ne v jednom textu, takže se nic nedělí. */
    fun onId3(title: String?, artist: String?, album: String?, now: Long): Boolean {
        val t = title?.let { IcyParser.clean(it) }?.takeIf { it.isNotEmpty() } ?: return false
        val a = artist?.let { IcyParser.clean(it) }?.takeIf { it.isNotEmpty() }
        if (a == null && IcyParser.parse(t, stationName).isTrack.not()) return false
        val key = "$a|$t"
        if (key == lastRaw && now - lastRawAt < 600_000) return false
        lastRaw = key; lastRawAt = now
        return commit(current.copy(trackTitle = t, artist = a, album = album?.let { IcyParser.clean(it) }?.takeIf { it.isNotEmpty() },
            artworkUrl = null, startedAt = now, duration = 0L, metadataSource = NowPlaying.Origin.ID3, lastUpdated = now))
    }

    /** Pořad z EPG: nikdy nepřepíše skladbu, jen se ukáže, když skladba není známá. */
    fun onProgram(title: String?): Boolean = commit(current.copy(programTitle = title?.takeIf { it.isNotBlank() }))

    fun onArtwork(url: String?): Boolean = commit(current.copy(artworkUrl = url))

    /** Přepojení streamu: stará skladba už nemusí platit (nová se ohlásí sama). Program zůstává. */
    fun reset() { lastRaw = null; commit(current.copy(trackTitle = null, artist = null, album = null, artworkUrl = null, startedAt = 0L, duration = 0L, metadataSource = NowPlaying.Origin.NONE)) }

    private fun commit(n: NowPlaying): Boolean {
        val changed = n.copy(lastUpdated = current.lastUpdated) != current.copy(lastUpdated = current.lastUpdated)
        current = n
        return changed
    }
}
