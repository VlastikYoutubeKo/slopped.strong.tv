package cz.plain.tvstream

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Adaptér poskytovatele. Každý typ zdroje (Xtream, TVHeadend, Stalker) ho implementuje samostatně. */
interface Backend {
    val source: Source
    fun load(progress: Progress = Progress { _, _ -> }): Lineup
    /** Co zdroj umí (zjišťuje se dotazem, nic se nepředpokládá). */
    fun caps(): Caps
    /** Rychlá kontrola spojení a účtu bez stahování kanálů; vrací lidsky čitelné shrnutí, nebo vyhodí [HttpError]. */
    fun ping(): String
    /** Hotová adresa streamu kanálu. Smí jít do sítě (Stalker), volat mimo hlavní vlákno. */
    fun streamUrl(ch: Channel): String

    /** Aktuální pořad pro všechny kanály jedním dotazem; null = backend to neumí. */
    fun nowForAll(): Map<String, Program>?
    /** Program kanálu od teď – dotaz na jeden kanál. */
    fun schedule(ch: Channel): List<Program>
    /** Má kanál vůbec nějaký program? (Xtream: jen s epg_channel_id.) */
    fun hasEpg(ch: Channel): Boolean = true
    /** Program všech kanálů najednou (XMLTV), klíč = Channel.id; výsledek se kvůli pomalým serverům ukládá na disk. */
    fun bulkEpg(channels: List<Channel>, cacheFile: File, progress: (String) -> Unit): Map<String, List<Program>>? = null

    // Filmy a seriály (jen kde je zdroj nabízí)
    fun vodCategories(): List<Category> = throw Unsupported("filmy")
    fun vodItems(categoryId: String?): List<VodItem> = throw Unsupported("filmy")
    fun vodInfo(id: String): VodInfo = throw Unsupported("filmy")
    fun vodUrl(item: VodItem): String = throw Unsupported("filmy")
    fun seriesCategories(): List<Category> = throw Unsupported("seriály")
    fun seriesItems(categoryId: String?): List<SeriesItem> = throw Unsupported("seriály")
    fun seriesDetail(id: String): SeriesDetail = throw Unsupported("seriály")
    fun episodeUrl(ep: Episode, series: SeriesItem): String = throw Unsupported("seriály")

    // Nahrávky (jen TVHeadend)
    fun recordings(): List<Recording> = throw Unsupported("nahrávky")
    fun recordingUrl(rec: Recording): String = throw Unsupported("nahrávky")
}

object Backends {
    fun of(source: Source, client: OkHttpClient): Backend = when (source.type) {
        SourceType.XTREAM -> Xtream(source, client)
        SourceType.TVHEADEND -> Tvheadend(source, client)
        SourceType.STALKER -> StalkerBackend(source, client)
    }
}

/**
 * Xtream Codes API: player_api.php (kategorie, živé kanály, filmy, seriály). Seznamy bývají obří
 * (desítky tisíc položek, desítky MB) – čtou se streamovaně a ukládá se jen to, co aplikace potřebuje.
 * Program z get_short_epg bývá u levných poskytovatelů rozbitý (HTTP 500), proto se bere z XMLTV.
 */
class Xtream(override val source: Source, private val c: OkHttpClient) : Backend {
    private val s = source
    private val base = "${s.baseUrl}/player_api.php?username=${enc(s.user)}&password=${enc(s.pass)}"
    private var ext = "m3u8"

    override fun load(progress: Progress): Lineup {
        progress.update(1, "Přihlašuji se…")
        val info = try { JSONObject(Net.get(c, base)) } catch (e: org.json.JSONException) {
            throw HttpError("Server neodpověděl jako Xtream Codes. Zkontroluj adresu.")
        }
        val ui = info.optJSONObject("user_info") ?: throw HttpError("Server neodpověděl jako Xtream Codes. Zkontroluj adresu.")
        if (ui.optInt("auth", 0) != 1) throw HttpError("Přihlášení odmítnuto. Zkontroluj jméno a heslo.")
        val status = ui.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", true)) {
            val exp = ui.optString("exp_date").toLongOrNull()?.let { " (platnost do ${java.text.DateFormat.getDateInstance().format(java.util.Date(it * 1000))})" } ?: ""
            throw HttpError("Účet není aktivní: $status$exp")
        }

        // HLS (m3u8) je pro ExoPlayer spolehlivější než surový TS, pokud ho server nabízí.
        val formats = ui.optJSONArray("allowed_output_formats")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        ext = if (formats.isEmpty() || "m3u8" in formats) "m3u8" else "ts"

        progress.update(2, "Načítám skupiny…")
        val categories = Net.json(c, "$base&action=get_live_categories") { r ->
            readArray(r) { o -> Category(o["category_id"] ?: "", o["category_name"] ?: "") }
        }
        val catName = categories.associate { it.id to it.name }

        progress.update(3, "Načítám kanály…")
        var n = 0
        val channels = Net.json(c, "$base&action=get_live_streams") { r ->
            readArray(r) { o ->
                val id = o["stream_id"] ?: return@readArray null
                if (++n % 2000 == 0) progress.update(3, "Načítám kanály… ${fmtCount(n)}")
                val name = (o["name"] ?: "").trim()
                val cat = o["category_id"]
                val meta = ChannelMeta.of(name)
                Channel(
                    id = id,
                    number = o["num"]?.toDoubleOrNull()?.toInt() ?: n,
                    name = name,
                    categoryIds = setOfNotNull(cat),
                    logoUrl = o["stream_icon"]?.takeIf { it.startsWith("http") },
                    ref = id,
                    epgId = o["epg_channel_id"]?.trim()?.takeIf { it.isNotEmpty() },
                    kind = Classify.kind(name, listOfNotNull(catName[cat])),
                    quality = meta.quality,
                    language = meta.language,
                )
            }
        }.sortedBy { it.number }
        return Lineup(categories, channels)
    }

    override fun streamUrl(ch: Channel) = "${s.baseUrl}/live/${enc(s.user)}/${enc(s.pass)}/${ch.ref}.$ext"

    override fun ping(): String {
        val ui = try { JSONObject(Net.get(c, base)).optJSONObject("user_info") } catch (e: org.json.JSONException) { null } ?: throw HttpError("Server neodpověděl jako Xtream Codes. Zkontroluj adresu.")
        if (ui.optInt("auth", 0) != 1) throw HttpError("Přihlášení odmítnuto. Zkontroluj jméno a heslo.")
        val exp = ui.optString("exp_date").toLongOrNull()?.let { "do ${java.text.DateFormat.getDateInstance().format(java.util.Date(it * 1000))}" } ?: "bez omezení"
        val status = ui.optString("status").ifEmpty { "Active" }
        return "Účet: $status, platí $exp · připojení ${ui.optString("active_cons", "?")}/${ui.optString("max_connections", "?")}"
    }

    /** Filmy a seriály: levný dotaz na kategorie. Server bez nich vrací 404 (jako tvůj poskytovatel). */
    override fun caps(): Caps {
        fun has(action: String) = try { Net.get(c, "$base&action=$action").trim().let { it.startsWith("[") && it.length > 2 } } catch (_: Exception) { false }
        return Caps(live = true, vod = has("get_vod_categories"), series = has("get_series_categories"), epg = true, radio = true)
    }

    override fun nowForAll(): Map<String, Program>? = null

    override fun hasEpg(ch: Channel) = ch.epgId != null

    override fun schedule(ch: Channel): List<Program> {
        val o = JSONObject(Net.get(c, "$base&action=get_short_epg&stream_id=${ch.id}&limit=8"))
        val list = o.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val e = list.getJSONObject(i)
            val start = e.optLong("start_timestamp") * 1000
            val stop = e.optLong("stop_timestamp") * 1000
            if (start == 0L || stop == 0L) null
            else Program(start, stop, b64(e.optString("title")), b64(e.optString("description")))
        }.filter { it.stop > System.currentTimeMillis() }.sortedBy { it.start }
    }

    // Xtream posílá názvy a popisy v Base64.
    private fun b64(s: String): String =
        try { String(Base64.decode(s, Base64.DEFAULT)).trim() } catch (_: Exception) { s }

    // ---------------------------------------------------------------- filmy

    override fun vodCategories(): List<Category> = Net.json(c, "$base&action=get_vod_categories") { r ->
        readArray(r) { o -> Category(o["category_id"] ?: return@readArray null, o["category_name"] ?: "") }
    }

    override fun vodItems(categoryId: String?): List<VodItem> {
        val q = if (categoryId.isNullOrEmpty()) "" else "&category_id=${enc(categoryId)}"
        return Net.json(c, "$base&action=get_vod_streams$q") { r ->
            readArray(r) { o ->
                VodItem(
                    id = o["stream_id"] ?: return@readArray null, name = (o["name"] ?: "").trim(), categoryId = o["category_id"],
                    poster = o["stream_icon"]?.takeIf { it.startsWith("http") }, rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                    year = o["year"]?.takeIf { it.isNotBlank() } ?: yearOf(o["name"]), added = (o["added"]?.toLongOrNull() ?: 0L) * 1000,
                    ext = o["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4",
                )
            }
        }
    }

    override fun vodInfo(id: String): VodInfo {
        val o = JSONObject(Net.get(c, "$base&action=get_vod_info&vod_id=${enc(id)}"))
        val i = o.optJSONObject("info") ?: JSONObject()
        return VodInfo(
            plot = i.optString("plot").nonBlank() ?: i.optString("description").nonBlank(), genre = i.optString("genre").nonBlank(),
            year = i.optString("releasedate").nonBlank()?.take(4) ?: i.optString("year").nonBlank(),
            durationMin = i.optInt("duration_secs", 0).takeIf { it > 0 }?.div(60) ?: parseMinutes(i.optString("duration")),
            cast = i.optString("cast").nonBlank() ?: i.optString("actors").nonBlank(), director = i.optString("director").nonBlank(),
            backdrop = firstUrl(i.opt("backdrop_path")), rating = i.optString("rating").nonBlank(), poster = i.optString("movie_image").nonBlank(),
        )
    }

    override fun vodUrl(item: VodItem) = "${s.baseUrl}/movie/${enc(s.user)}/${enc(s.pass)}/${item.id}.${item.ext}"

    // ---------------------------------------------------------------- seriály

    override fun seriesCategories(): List<Category> = Net.json(c, "$base&action=get_series_categories") { r ->
        readArray(r) { o -> Category(o["category_id"] ?: return@readArray null, o["category_name"] ?: "") }
    }

    override fun seriesItems(categoryId: String?): List<SeriesItem> {
        val q = if (categoryId.isNullOrEmpty()) "" else "&category_id=${enc(categoryId)}"
        return Net.json(c, "$base&action=get_series$q") { r ->
            readArray(r) { o ->
                SeriesItem(
                    id = o["series_id"] ?: return@readArray null, name = (o["name"] ?: "").trim(), categoryId = o["category_id"],
                    poster = o["cover"]?.takeIf { it.startsWith("http") }, plot = o["plot"]?.nonBlank(),
                    year = o["releaseDate"]?.nonBlank()?.take(4) ?: o["year"]?.nonBlank(), rating = o["rating"]?.nonBlank(),
                    genre = o["genre"]?.nonBlank(), backdrop = null, lastModified = (o["last_modified"]?.toLongOrNull() ?: 0L) * 1000,
                )
            }
        }
    }

    override fun seriesDetail(id: String): SeriesDetail {
        val o = JSONObject(Net.get(c, "$base&action=get_series_info&series_id=${enc(id)}"))
        val i = o.optJSONObject("info") ?: JSONObject()
        val series = SeriesItem(
            id = id, name = i.optString("name"), categoryId = i.optString("category_id").nonBlank(), poster = i.optString("cover").nonBlank(),
            plot = i.optString("plot").nonBlank(), year = i.optString("releaseDate").nonBlank()?.take(4), rating = i.optString("rating").nonBlank(),
            genre = i.optString("genre").nonBlank(), backdrop = firstUrl(i.opt("backdrop_path")), lastModified = 0L,
        )
        val seasons = sortedMapOf<Int, MutableList<Episode>>()
        val eps = o.opt("episodes")
        val groups: List<JSONArray> = when (eps) {
            is JSONObject -> eps.keys().asSequence().mapNotNull { eps.optJSONArray(it) }.toList()
            is JSONArray -> (0 until eps.length()).mapNotNull { eps.optJSONArray(it) }
            else -> emptyList()
        }
        for (arr in groups) for (k in 0 until arr.length()) {
            val e = arr.optJSONObject(k) ?: continue
            val inf = e.optJSONObject("info")
            val season = e.optInt("season", 1)
            seasons.getOrPut(season) { ArrayList() } += Episode(
                id = e.optString("id"), season = season, number = e.optInt("episode_num", k + 1),
                title = e.optString("title").nonBlank() ?: "Epizoda ${e.optInt("episode_num", k + 1)}",
                plot = inf?.optString("plot")?.nonBlank(), durationMin = inf?.optInt("duration_secs", 0)?.takeIf { it > 0 }?.div(60),
                poster = inf?.optString("movie_image")?.nonBlank(), ext = e.optString("container_extension").nonBlank() ?: "mp4",
            )
        }
        seasons.values.forEach { it.sortBy { e -> e.number } }
        return SeriesDetail(series, seasons)
    }

    override fun episodeUrl(ep: Episode, series: SeriesItem) = "${s.baseUrl}/series/${enc(s.user)}/${enc(s.pass)}/${ep.id}.${ep.ext}"

    // ---------------------------------------------------------------- XMLTV (hromadný program)

    override fun bulkEpg(channels: List<Channel>, cacheFile: File, progress: (String) -> Unit): Map<String, List<Program>> {
        val wanted = channels.mapNotNull { it.epgId }.toHashSet()
        val byEpg = HashMap<String, MutableList<Program>>()
        val now = System.currentTimeMillis()
        // Čerstvá kopie na disku (do 3 h) = program je okamžitě a server se nezatěžuje (hlavně při DDoS / pomalém serveru).
        var ok = cacheFile.exists() && now - cacheFile.lastModified() < 3 * H && readCache(cacheFile, byEpg)
        if (!ok) {
            try {
                var attempt = 0
                while (true) {
                    try { download(wanted, byEpg, progress); break } catch (e: IOException) {
                        if (++attempt >= 2) throw e
                        byEpg.clear(); Thread.sleep(2000)
                    }
                }
                writeCache(cacheFile, byEpg)
                ok = true
            } catch (e: Exception) {
                // Server nejde: starší kopie (do 48 h) je lepší než nic.
                byEpg.clear()
                if (cacheFile.exists() && now - cacheFile.lastModified() < 48 * H && readCache(cacheFile, byEpg)) ok = true
                else throw if (e is HttpError) e else HttpError("Program (EPG) se nepodařilo stáhnout: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val out = HashMap<String, List<Program>>(byEpg.size * 2)
        for (ch in channels) ch.epgId?.let { id -> byEpg[id]?.let { list -> out[ch.id] = list.sortedBy { it.start } } }
        return out
    }

    private fun download(wanted: Set<String>, out: MutableMap<String, MutableList<Program>>, progress: (String) -> Unit) {
        progress("Stahuji program (EPG)…")
        val req = Request.Builder()
            .url("${s.baseUrl}/xmltv.php?username=${enc(s.user)}&password=${enc(s.pass)}")
            .header("User-Agent", Net.USER_AGENT).build()
        c.newBuilder().readTimeout(120, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError("Server nenabízí XMLTV program (HTTP ${r.code}).")
            val body = r.body ?: throw HttpError("Server poslal prázdný program.")
            var next = 0L
            val counting = object : FilterInputStream(body.byteStream()) {
                var total = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                    if (n > 0) { total += n; if (total >= next) { next = total + 2_000_000; progress("Zpracovávám program… ${total / 1_048_576} MB") } }
                }
            }
            // Jen na pozadí a s nízkou prioritou, ať průvodce zůstane plynulý.
            Thread.currentThread().priority = Thread.MIN_PRIORITY
            parseXmltv(counting, wanted, out)
        }
        if (out.isEmpty()) throw HttpError("Poskytovatel poslal program, ale pro tvé kanály v něm nic není.")
    }

    private fun parseXmltv(input: InputStream, wanted: Set<String>, out: MutableMap<String, MutableList<Program>>) {
        val now = System.currentTimeMillis()
        val from = now - 2 * H
        val to = now + 30 * H
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                val ch = p.getAttributeValue(null, "channel")
                if (ch != null && ch in wanted) {
                    val start = Xmltv.time(p.getAttributeValue(null, "start"))
                    val stop = Xmltv.time(p.getAttributeValue(null, "stop"))
                    var title = ""
                    var desc = ""
                    var depth = 1
                    while (depth > 0) {
                        ev = p.next()
                        if (ev == XmlPullParser.START_TAG) {
                            when (p.name) {
                                "title" -> { val t = p.nextText(); if (title.isEmpty()) title = Xmltv.clean(t, 200) }
                                "desc" -> { val t = p.nextText(); if (desc.isEmpty()) desc = Xmltv.clean(t, 300) }
                                else -> depth++
                            }
                        } else if (ev == XmlPullParser.END_TAG) depth--
                        else if (ev == XmlPullParser.END_DOCUMENT) break
                    }
                    if (start != null && stop != null && stop > from && start < to && title.isNotEmpty()) {
                        val list = out.getOrPut(ch) { ArrayList(12) }
                        // Popis jen u pořadů, které běží nebo brzy začnou (paměť: desítky tisíc kanálů); ostatním stačí název.
                        val needDesc = stop > now && start < now + 3 * H
                        if (list.size < 14) list += Program(start, stop, title, if (needDesc) desc else "")
                    }
                }
            }
            ev = p.next()
        }
    }

    private fun writeCache(f: File, data: Map<String, List<Program>>) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((id, list) in data) for (p in list)
                    w.write("${id.replace('\t', ' ')}\t${p.start}\t${p.stop}\t${p.title.replace('\t', ' ')}\t${p.description.replace('\t', ' ')}\n")
            }
            tmp.renameTo(f)
        } catch (_: Exception) { /* cache je jen urychlení */ }
    }

    private fun readCache(f: File, out: MutableMap<String, MutableList<Program>>): Boolean = try {
        val nowMs = System.currentTimeMillis()
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val x = line.split('\t', limit = 5)
                if (x.size < 5) continue
                val st = x[1].toLong(); val en = x[2].toLong()
                out.getOrPut(x[0]) { ArrayList(12) } += Program(st, en, x[3], if (en > nowMs && st < nowMs + 3 * H) x[4].take(300) else "")
            }
        }
        out.isNotEmpty()
    } catch (_: Exception) { out.clear(); false }

    private companion object { const val H = 3_600_000L }
}

/** Čistá pomocná logika XMLTV (bez Androidu, testovaná). */
object Xmltv {
    fun clean(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().take(max)

    /** XMLTV čas: „20261025213000 +0200“ (zóna i vteřiny nepovinné; bez zóny se bere UTC). */
    fun time(raw: String?): Long? {
        val t = raw?.trim() ?: return null
        if (t.length < 12 || !t.take(12).all { it.isDigit() }) return null
        return try {
            val hasSec = t.length >= 14 && t[12].isDigit()
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(t.substring(0, 4).toInt(), t.substring(4, 6).toInt() - 1, t.substring(6, 8).toInt(),
                    t.substring(8, 10).toInt(), t.substring(10, 12).toInt(), if (hasSec) t.substring(12, 14).toInt() else 0)
            }
            var ms = cal.timeInMillis
            val off = t.substring(if (hasSec) 14 else 12).trim()
            if (off.length >= 5 && (off[0] == '+' || off[0] == '-')) {
                val sign = if (off[0] == '-') -1 else 1
                ms -= sign * (off.substring(1, 3).toInt() * 60 + off.substring(3, 5).toInt()) * 60_000L
            }
            ms
        } catch (_: Exception) { null }
    }
}

/** TVHeadend HTTP API: kanály, štítky a EPG; stream /stream/channel/{uuid}. */
class Tvheadend(override val source: Source, private val c: OkHttpClient) : Backend {
    private val s = source

    override fun load(progress: Progress): Lineup {
        progress.update(1, "Přihlašuji se…")
        progress.update(2, "Načítám štítky…")
        val tags = JSONObject(Net.get(c, "${s.baseUrl}/api/channeltag/grid?limit=1000")).optJSONArray("entries") ?: JSONArray()
        val categories = (0 until tags.length()).map { tags.getJSONObject(it) }
            .filter { it.optBoolean("enabled", true) && !it.optBoolean("internal", false) }
            .map { Category(it.getString("uuid"), it.optString("name")) }
            .sortedBy { it.name.lowercase() }
        val tagName = categories.associate { it.id to it.name }

        progress.update(3, "Načítám kanály…")
        val grid = JSONObject(Net.get(c, "${s.baseUrl}/api/channel/grid?limit=10000")).optJSONArray("entries") ?: JSONArray()
        val channels = (0 until grid.length()).map { grid.getJSONObject(it) }
            .filter { it.optBoolean("enabled", true) }
            .map { o ->
                val t = o.optJSONArray("tags") ?: JSONArray()
                val uuid = o.getString("uuid")
                val ids = (0 until t.length()).map { t.getString(it) }.toSet()
                val name = o.optString("name").trim()
                val meta = ChannelMeta.of(name)
                Channel(
                    id = uuid, number = o.optInt("number", 0), name = name, categoryIds = ids,
                    logoUrl = icon(o.optString("icon_public_url")), ref = uuid,
                    kind = Classify.kind(name, ids.mapNotNull { tagName[it] }), quality = meta.quality, language = meta.language,
                )
            }
            .sortedWith(compareBy({ if (it.number > 0) it.number else Int.MAX_VALUE }, { it.name.lowercase() }))
        return Lineup(categories, channels)
    }

    override fun ping(): String {
        val o = JSONObject(Net.get(c, "${s.baseUrl}/api/serverinfo"))
        return "TVHeadend ${o.optString("sw_version", "?")} · ${o.optString("name", "server")}"
    }

    /** profile=pass = originální MPEG-TS bez překódování (nejmenší zátěž serveru i boxu). */
    override fun streamUrl(ch: Channel) = "${s.baseUrl}/stream/channel/${ch.ref}?profile=pass"

    override fun caps() = Caps(live = true, vod = false, series = false, epg = true, radio = true)

    /** DVR: hotové, běžící i naplánované nahrávky; přehrává se přes /dvrfile/{uuid} (stejné přihlášení jako stream). */
    override fun recordings(): List<Recording> {
        val arr = JSONObject(Net.get(c, "${s.baseUrl}/api/dvr/entry/grid?limit=1000")).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Recording(
                id = o.getString("uuid"), title = o.optString("disp_title").ifBlank { o.optString("title") }, subtitle = o.optString("disp_subtitle"),
                channelId = o.optString("channel"), channelName = o.optString("channelname"),
                start = o.optLong("start") * 1000, stop = o.optLong("stop") * 1000,
                status = when (o.optString("sched_status")) { "completed" -> "completed"; "recording" -> "recording"; "scheduled" -> "scheduled"; else -> "other" },
                sizeBytes = o.optLong("filesize"), description = o.optString("disp_description").ifBlank { o.optString("disp_summary") },
            )
        }.sortedByDescending { it.start }
    }

    override fun recordingUrl(rec: Recording) = "${s.baseUrl}/dvrfile/${rec.id}"

    // „imagecache/12“ je relativní k serveru (a chráněné stejným přihlášením).
    private fun icon(u: String): String? = when {
        u.isBlank() -> null
        u.startsWith("http") -> u
        else -> "${s.baseUrl}/${u.trimStart('/')}"
    }

    override fun nowForAll(): Map<String, Program> {
        val arr = JSONObject(Net.get(c, "${s.baseUrl}/api/epg/events/grid?mode=now&limit=5000")).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .associate { it.optString("channelUuid") to event(it) }
    }

    override fun schedule(ch: Channel): List<Program> {
        val arr = JSONObject(Net.get(c, "${s.baseUrl}/api/epg/events/grid?channel=${ch.id}&limit=10"))
            .optJSONArray("entries") ?: JSONArray()
        val now = System.currentTimeMillis()
        return (0 until arr.length()).map { event(arr.getJSONObject(it)) }.filter { it.stop > now }.sortedBy { it.start }
    }

    private fun event(e: JSONObject) = Program(
        start = e.optLong("start") * 1000,
        stop = e.optLong("stop") * 1000,
        title = e.optString("title"),
        description = e.optString("description").ifBlank { e.optString("summary").ifBlank { e.optString("subtitle") } },
    )
}

/**
 * Pole objektů z JsonReaderu; každý objekt se předá jako mapa jednoduchých hodnot (vnořené se přeskočí).
 * Některé Xtream servery posílají místo pole objekt { "0": {...}, "1": {...} } – zvládne obojí.
 */
internal fun <T> readArray(r: JsonReader, map: (Map<String, String>) -> T?): List<T> {
    val out = ArrayList<T>()
    val readObj = {
        val o = HashMap<String, String>(16)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> o[k] = r.nextString()
                JsonToken.BOOLEAN -> o[k] = r.nextBoolean().toString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        map(o)?.let { out += it }
    }
    when (r.peek()) {
        JsonToken.BEGIN_ARRAY -> { r.beginArray(); while (r.hasNext()) readObj(); r.endArray() }
        JsonToken.BEGIN_OBJECT -> { r.beginObject(); while (r.hasNext()) { r.nextName(); readObj() }; r.endObject() }
        else -> r.skipValue()
    }
    return out
}

internal fun enc(s: String) = Uri.encode(s)
internal fun String.nonBlank(): String? = trim().takeIf { it.isNotEmpty() && it != "null" }
private fun yearOf(name: String?): String? = name?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1) }
private fun parseMinutes(d: String): Int? {
    val p = d.trim().split(':').mapNotNull { it.toIntOrNull() }
    return when (p.size) { 3 -> p[0] * 60 + p[1]; 2 -> p[0]; else -> d.trim().toIntOrNull() }?.takeIf { it > 0 }
}
private fun firstUrl(v: Any?): String? = when (v) {
    is JSONArray -> (0 until v.length()).map { v.optString(it) }.firstOrNull { it.startsWith("http") }
    is String -> v.takeIf { it.startsWith("http") }
    else -> null
}
