package cz.plain.tvstream

import okhttp3.OkHttpClient

data class Category(val id: String, val name: String)

/**
 * Kanál zdroje. [ref] je neprůhledný odkaz pro daný backend (Xtream „id.m3u8“, TVHeadend uuid, Stalker cmd).
 * Hotová adresa streamu se skládá až při přehrání ([Backend.streamUrl]), takže v cache na disku nejsou hesla.
 */
data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val categoryIds: Set<String>,
    val logoUrl: String?,
    val ref: String,
    val epgId: String? = null,      // Xtream epg_channel_id; kanály bez něj nemají u poskytovatele žádný program
    val kind: ChannelKind = ChannelKind.TV,
    val quality: String? = null,    // „4K“, „FHD“, „HD“, „SD“ podle názvu, pokud ho poskytovatel uvádí
    val language: String? = null,   // předpona názvu „CZ:“, „DK|“ apod.
)

/** Pořad v EPG (časy v ms). */
data class Program(val start: Long, val stop: Long, val title: String, val description: String) {
    fun isNow(now: Long = System.currentTimeMillis()) = now in start until stop
    fun progress(now: Long = System.currentTimeMillis()) =
        if (stop <= start) 0 else (((now - start) * 1000) / (stop - start)).toInt().coerceIn(0, 1000)
}

data class Lineup(val categories: List<Category>, val channels: List<Channel>)

/** Co zdroj umí. Zjišťuje se při načtení (nic se nepředpokládá). */
data class Caps(val live: Boolean = true, val vod: Boolean = false, val series: Boolean = false, val epg: Boolean = true, val radio: Boolean = false)

// ---------------------------------------------------------------- filmy a seriály

data class VodItem(
    val id: String, val name: String, val categoryId: String?, val poster: String?,
    val rating: String?, val year: String?, val added: Long, val ext: String,
)

data class VodInfo(
    val plot: String?, val genre: String?, val year: String?, val durationMin: Int?, val cast: String?,
    val director: String?, val backdrop: String?, val rating: String?, val poster: String?,
)

data class SeriesItem(
    val id: String, val name: String, val categoryId: String?, val poster: String?, val plot: String?,
    val year: String?, val rating: String?, val genre: String?, val backdrop: String?, val lastModified: Long,
)

data class Episode(
    val id: String, val season: Int, val number: Int, val title: String, val plot: String?,
    val durationMin: Int?, val poster: String?, val ext: String,
)

class SeriesDetail(val series: SeriesItem, val seasons: Map<Int, List<Episode>>)

/** Nahrávka z TVHeadendu (DVR). [status]: completed | recording | scheduled | other. */
data class Recording(val id: String, val title: String, val subtitle: String, val channelId: String, val channelName: String,
                     val start: Long, val stop: Long, val status: String, val sizeBytes: Long, val description: String) {
    val playable: Boolean get() = status == "completed" || status == "recording"
}

class Unsupported(what: String) : Exception("Tento zdroj nepodporuje: $what")

/** Průběh načítání pro UI: krok (1..3) a text. */
fun interface Progress { fun update(step: Int, text: String) }

/** Načtené kanály aktuálně přehrávaného / otevřeného zdroje. */
object Session {
    var client: OkHttpClient? = null
}
