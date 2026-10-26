package cz.plain.tvstream

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Čistá logika protokolu Stalker / Ministra (testovaná bez sítě). Portál se zadává jako adresa „…/c/“ nebo
 * „…/stalker_portal/c/“; API leží v „…/server/load.php“.
 */
object StalkerProto {
    /** Kořen portálu: adresa bez koncového „/c“. */
    fun root(portal: String): String = portal.trim().trimEnd('/').removeSuffix("/c")

    fun loadUrl(portal: String): String = root(portal) + "/server/load.php"

    /** Portály mají API na různých místech; zkouší se po řadě, použije se první, který odpoví tokenem. */
    fun loadUrls(portal: String): List<String> {
        val r = root(portal)
        return listOf("$r/server/load.php", "$r/portal.php", "$r/stalker_portal/server/load.php", "$r/c/server/load.php", "$r/stalker_portal/portal.php").distinct()
    }

    /** Relativní adresa loga (např. „/stalker_portal/misc/logos/1.png“ nebo „logos/1.png“) vůči portálu. */
    fun absolute(portal: String, u: String): String {
        if (u.startsWith("http")) return u
        val r = root(portal)
        val origin = Regex("""^(https?://[^/]+)""").find(r)?.value ?: r
        return if (u.startsWith("/")) origin + u else "$r/$u"
    }

    /** „ffmpeg http://…“, „auto http://…“, „ffrt http://…“ → jen adresa. */
    fun cleanCmd(cmd: String): String {
        val c = cmd.trim()
        val i = c.indexOf("http")
        return if (i > 0 && c.substring(0, i).isNotBlank() && !c.substring(0, i).contains("://")) c.substring(i).trim() else c
    }

    /** Nová MAC adresa ve stylu zařízení MAG (00:1A:79:xx:xx:xx), např. pro zkušební přístup. */
    fun randomMac(rnd: java.util.Random = java.security.SecureRandom()): String =
        "00:1A:79:%02X:%02X:%02X".format(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))

    fun macOk(mac: String) = Regex("""^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$""").matches(mac.trim())

    fun normalizeMac(mac: String): String {
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
        return if (hex.length == 12) hex.chunked(2).joinToString(":") else mac.trim().uppercase()
    }

    class Chan(val id: String, val number: Int, val name: String, val genreId: String?, val logo: String?, val cmd: String)

    fun parseChannels(json: String): List<Chan> {
        val root = JSONObject(json)
        val js = root.opt("js")
        val arr: JSONArray = when (js) {
            is JSONObject -> js.optJSONArray("data") ?: JSONArray()
            is JSONArray -> js
            else -> JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            Chan(id, o.optInt("number", i + 1), o.optString("name").trim(), o.optString("tv_genre_id").ifEmpty { null }, o.optString("logo").ifEmpty { null }, o.optString("cmd"))
        }
    }

    fun parseGenres(json: String): List<Category> {
        val js = JSONObject(json).opt("js") as? JSONArray ?: return emptyList()
        return (0 until js.length()).mapNotNull { i -> js.optJSONObject(i)?.let { Category(it.optString("id"), it.optString("title")) } }
            .filter { it.id.isNotEmpty() && it.id != "*" }
    }

    fun parseEpg(json: String): List<Program> {
        val js = JSONObject(json).opt("js") as? JSONArray ?: return emptyList()
        return (0 until js.length()).mapNotNull { i ->
            val o = js.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optLong("start_timestamp"); val e = o.optLong("stop_timestamp")
            if (s <= 0 || e <= s) null else Program(s * 1000, e * 1000, o.optString("name"), o.optString("descr"))
        }.sortedBy { it.start }
    }
}

/**
 * Adaptér Stalker / Ministra. Sdílí s ostatními jen rozhraní [Backend]. Zdroj: [Source.url] = portál,
 * [Source.user] = MAC adresa zařízení; heslo se nepoužívá.
 *
 * Ověřeno jen proti simulovanému portálu (scripts/fake-iptv-server.mjs); se skutečným portálem se nezkoušel,
 * protože žádný není k dispozici. Podle protokolu: handshake → token (Bearer) → get_profile → get_all_channels,
 * odkaz na stream vzniká až při přehrání (create_link), protože je krátkodobý a bývá vázaný na token.
 */
class StalkerBackend(override val source: Source, private val c: OkHttpClient) : Backend {
    private val s = source
    private val candidates = StalkerProto.loadUrls(s.baseUrl)
    @Volatile private var api = candidates.first()
    private val mac = StalkerProto.normalizeMac(s.user)
    @Volatile private var token: String? = null

    private fun call(query: String, auth: Boolean = true): String {
        if (auth && token == null) handshake()
        return try { checked(raw(query)) } catch (e: HttpError) {
            // Token mohl vypršet: jednou znovu přihlásit.
            if (auth && e.message?.contains("401") == true) { token = null; handshake(); raw(query) } else throw e
        }
    }

    /** Portál někdy odpoví prostým textem („Unauthorized request.“): ukáže se srozumitelně místo chyby rozboru. */
    private fun checked(body: String): String {
        val s = body.trimStart()
        if (!s.startsWith("{") && !s.startsWith("[")) throw HttpError("Portál odpověděl textem místo dat: „${Redact.url(body.trim()).take(80)}“. Portál po ověření zařízení nepustil dál. U vlastního Ministra zkontroluj, že jeho ověření uživatele (auth_simple) funguje; jinak je MAC u poskytovatele neaktivní.")
        return body
    }

    private fun raw(query: String): String {
        val b = Request.Builder().url("$api?$query&JsHttpRequest=1-xml")
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3")
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=$mac; stb_lang=cs; timezone=Europe/Prague")
        token?.let { b.header("Authorization", "Bearer $it") }
        try {
            c.newCall(b.build()).execute().use { r ->
                if (r.code == 401) throw HttpError("Portál odmítl zařízení (401). Zkontroluj MAC adresu, nebo ji u poskytovatele aktivuj.")
                if (r.code == 404) throw HttpError("Portál tu nenabízí rozhraní Stalker (404). Zkontroluj adresu (končí obvykle /c/).")
                if (!r.isSuccessful) throw HttpError("Portál vrátil chybu ${r.code}.")
                return r.body?.string().orEmpty()
            }
        } catch (e: HttpError) { throw e } catch (e: java.io.IOException) { throw HttpError("Portál neodpovídá: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun hex(alg: String, s: String) = java.security.MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun handshake() {
        // Dvě podoby profilu: moderní Ministra 5.x (metriky a otisky, bez client_type) a starší jednoduchá (client_type=STB).
        // Zkouší se po řadě; platí ta, po které portál pustí k seznamu skupin.
        var firstErr: HttpError? = null
        for (modern in listOf(true, false)) {
            try { handshakeOnce(modern); if (probe()) return else throw HttpError("Portál po ověření zařízení nepustil dál (odpověděl „Authorization failed“). U vlastního Ministra zkontroluj ověření uživatele (auth_simple), u poskytovatele aktivitu MAC.") }
            catch (e: HttpError) { if (firstErr == null) firstErr = e }
        }
        throw firstErr!!
    }

    /** Povolí portál po přihlášení seznam skupin? (Neověřené zařízení dostane prostý text.) */
    private fun probe(): Boolean = try { val s = raw("type=itv&action=get_genres").trimStart(); s.startsWith("{") || s.startsWith("[") } catch (_: HttpError) { false }


    private fun handshakeOnce(modern: Boolean) {
        token = null
        var t: String? = null
        var random = ""
        var lastErr: HttpError? = null
        for (u in listOf(api) + candidates.filter { it != api }) {
            api = u
            try {
                val body = raw("type=stb&action=handshake&token=")
                val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
                t = js?.optString("token"); random = js?.optString("random").orEmpty().lowercase()
                if (!t.isNullOrEmpty()) break
            } catch (e: HttpError) { lastErr = e }
        }
        if (t.isNullOrEmpty()) throw lastErr ?: HttpError("Portál neodpověděl jako Stalker / Ministra. Zkoušel jsem /server/load.php i /portal.php; zkontroluj adresu a MAC.")
        token = t
        val sn = hex("MD5", mac).take(13).uppercase()
        val dev = hex("SHA-256", mac).uppercase()
        val sig = hex("SHA-256", mac + sn + dev + dev).uppercase()
        val metrics = JSONObject().put("mac", mac).put("sn", sn).put("type", "STB").put("model", "MAG250").put("uid", "").put("random", random).toString()
        val query = if (modern)
            "type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r23-250;%20PORTAL%20version:%205.6.1&num_banks=2&sn=$sn&stb_type=MAG250&image_version=218&video_out=hdmi" +
                "&device_id=$dev&device_id2=$dev&signature=$sig&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0&metrics=${enc(metrics)}&hw_version_2=${hex("SHA-1", mac)}" +
                "&timestamp=${System.currentTimeMillis() / 1000}&api_signature=262&prehash="
        else "type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14;&num_banks=2&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi&auth_second_step=0"
        val prof = try { raw(query) } catch (_: HttpError) { null }
        val js = try { JSONObject(prof ?: "").optJSONObject("js") } catch (_: Exception) { null }
        js?.optString("token")?.takeIf { it.isNotEmpty() }?.let { token = it }   // portál může po profilu vydat nový token
        // „Not valid MAC“ = MAC adresa není v administraci portálu.
        val msg = js?.optString("msg")?.takeIf { m -> m.contains("not valid", true) || m.contains("blocked", true) || m.contains("denied", true) }
        if (!msg.isNullOrEmpty() && !modern) throw HttpError("Portál odmítl zařízení ($mac): „$msg“. MAC adresa musí být v portálu zaregistrovaná a aktivní (u vlastního portálu ji přidej v administraci, u poskytovatele ji nech aktivovat).")
        if (!msg.isNullOrEmpty()) throw HttpError("Portál odmítl zařízení ($mac): „$msg“.")
    }

    override fun ping(): String {
        handshake()
        return "Stalker · zařízení $mac"
    }

    override fun caps(): Caps = Caps(live = true, vod = vodOk, series = seriesOk, epg = true, radio = true)
    @Volatile private var vodOk = false
    @Volatile private var seriesOk = false

    override fun load(progress: Progress): Lineup {
        progress.update(1, "Přihlašuji zařízení…")
        handshake()
        progress.update(2, "Načítám skupiny…")
        val cats = StalkerProto.parseGenres(call("type=itv&action=get_genres"))
        val catName = cats.associate { it.id to it.name }
        progress.update(3, "Načítám kanály…")
        val ch = StalkerProto.parseChannels(call("type=itv&action=get_all_channels"))
        vodOk = try { StalkerProto.parseGenres(call("type=vod&action=get_categories")).isNotEmpty() } catch (_: Exception) { false }
        seriesOk = try { StalkerProto.parseGenres(call("type=series&action=get_categories")).isNotEmpty() } catch (_: Exception) { false }
        // Některé portály mají seriály jako kategorie filmů („VOD Series - …“).
        if (!seriesOk) seriesOk = try { seriesViaVod().isNotEmpty() } catch (_: Exception) { false }
        val channels = ch.map { x ->
            val meta = ChannelMeta.of(x.name)
            Channel(
                id = x.id, number = x.number, name = x.name, categoryIds = setOfNotNull(x.genreId), logoUrl = x.logo?.let { StalkerProto.absolute(s.baseUrl, it) },
                ref = x.cmd, epgId = x.id, kind = Classify.kind(x.name, listOfNotNull(catName[x.genreId])), quality = meta.quality, language = meta.language,
            )
        }
        return Lineup(cats, channels)
    }

    /** Odkaz na stream vzniká při přehrání (create_link); funguje jen na pozadí, ne v UI vlákně. */
    override fun streamUrl(ch: Channel): String {
        val cmd = ch.ref
        val body = call("type=itv&action=create_link&cmd=${enc(cmd)}&forced_storage=undefined&disable_ad=0")
        val link = JSONObject(body).optJSONObject("js")?.optString("cmd").orEmpty()
        val url = StalkerProto.cleanCmd(link.ifEmpty { cmd })
        if (!url.startsWith("http")) throw HttpError("Portál nevrátil odkaz na stream (možná chybí předplatné nebo je zařízení neaktivní).")
        return url
    }

    override fun nowForAll(): Map<String, Program>? = null

    override fun schedule(ch: Channel): List<Program> {
        val now = System.currentTimeMillis()
        return StalkerProto.parseEpg(call("type=itv&action=get_short_epg&ch_id=${enc(ch.id)}&size=10")).filter { it.stop > now }
    }

    // Filmy: jen seznam podle kategorií (série zatím nejsou podporovány).
    override fun vodCategories(): List<Category> = StalkerProto.parseGenres(call("type=vod&action=get_categories"))

    override fun vodItems(categoryId: String?): List<VodItem> {
        val out = ArrayList<VodItem>()
        var page = 1
        while (page <= 20) {
            val js = JSONObject(call("type=vod&action=get_ordered_list&category=${enc(categoryId ?: "*")}&p=$page")).optJSONObject("js") ?: break
            val data = js.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                out += VodItem(o.optString("id"), o.optString("name"), categoryId, o.optString("screenshot_uri").ifEmpty { null }, o.optString("rating_imdb").ifEmpty { null },
                    o.optString("year").ifEmpty { null }, 0L, o.optString("cmd"))   // ext = cmd (u Stalkeru nese odkaz na přehrání)
            }
            val total = js.optInt("total_items", 0); val per = js.optInt("max_page_items", data.length()).coerceAtLeast(1)
            if (page * per >= total) break
            page++
        }
        return out
    }

    // ---------------------------------------------------------------- seriály (podle dokumentace protokolu; ověřuje se na skutečném portálu)

    private fun seriesViaVod(): List<Category> = StalkerProto.parseGenres(call("type=vod&action=get_categories")).filter { it.name.contains("series", true) || it.name.contains("seri", true) }

    override fun seriesCategories(): List<Category> {
        val own = try { StalkerProto.parseGenres(call("type=series&action=get_categories")) } catch (_: Exception) { emptyList() }
        if (own.isNotEmpty()) { viaVod = false; return own }
        viaVod = true
        return seriesViaVod()
    }
    @Volatile private var viaVod = false

    override fun seriesItems(categoryId: String?): List<SeriesItem> {
        val out = ArrayList<SeriesItem>()
        var page = 1
        while (page <= 20) {
            val js = JSONObject(call("type=${if (viaVod) "vod" else "series"}&action=get_ordered_list&category=${enc(categoryId ?: "*")}&p=$page")).optJSONObject("js") ?: break
            val data = js.optJSONArray("data") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val o = data.getJSONObject(i)
                out += SeriesItem("${o.optString("id")}~${o.optString("cmd")}", o.optString("name"), categoryId, o.optString("screenshot_uri").ifEmpty { null },
                    o.optString("description").ifEmpty { null }, o.optString("year").ifEmpty { null }, o.optString("rating_imdb").ifEmpty { null },
                    o.optString("genres_str").ifEmpty { null }, null, 0L)
            }
            val total = js.optInt("total_items", 0); val per = js.optInt("max_page_items", data.length()).coerceAtLeast(1)
            if (page * per >= total) break
            page++
        }
        return out
    }

    override fun seriesDetail(id: String): SeriesDetail {
        val sid = id.substringBefore('~'); val cmd = id.substringAfter('~', "")
        val seasons = sortedMapOf<Int, MutableList<Episode>>()
        fun list(type: String, season: String): org.json.JSONArray? = try {
            JSONObject(call("type=$type&action=get_ordered_list&movie_id=${enc(sid)}&season_id=${enc(season)}&episode_id=0&p=1")).optJSONObject("js")?.optJSONArray("data")
        } catch (_: Exception) { null }
        // Sezóny a epizody jsou u některých portálů pod type=series, u jiných pod type=vod.
        for (type in listOf("series", "vod")) {
            val data = list(type, "0") ?: continue
            for (i in 0 until data.length()) {
                val d = data.getJSONObject(i)
                val eps = d.optJSONArray("series")
                val seasonNo = Regex("""(\d+)""").find(d.optString("name"))?.value?.toIntOrNull() ?: (i + 1)
                if (eps != null && eps.length() > 0) {
                    for (k in 0 until eps.length()) seasons.getOrPut(seasonNo) { ArrayList() } += Episode("${d.optString("id")}-${eps.optInt(k)}", seasonNo, eps.optInt(k), "Epizoda ${eps.optInt(k)}", null, null, null, "N~${d.optString("cmd").ifEmpty { cmd }}~${eps.optInt(k)}")
                } else {
                    val seasonId = d.optString("id")
                    val ep = list(type, seasonId) ?: continue
                    for (k in 0 until ep.length()) {
                        val e = ep.getJSONObject(k)
                        val n = e.optInt("series_number", k + 1)
                        seasons.getOrPut(seasonNo) { ArrayList() } += Episode(e.optString("id"), seasonNo, n, e.optString("name").ifEmpty { "Epizoda $n" }, e.optString("description").ifEmpty { null }, null, e.optString("screenshot_uri").ifEmpty { null }, "S~$type~$sid~$seasonId~${e.optString("id")}")
                    }
                }
            }
            if (seasons.isNotEmpty()) break
        }
        if (seasons.isEmpty()) throw HttpError("Portál nevrátil použitelné epizody.")
        seasons.values.forEach { it.sortBy { e -> e.number } }
        return SeriesDetail(SeriesItem(id, "", null, null, null, null, null, null, null, 0L), seasons)
    }

    override fun episodeUrl(ep: Episode, series: SeriesItem): String {
        val p = ep.ext.split('~')
        if (p[0] == "S") {
            // S~typ~film~sezóna~epizoda: id souboru se zjistí zvlášť, odkaz se tvoří jako u filmu
            val fid = fileId(p[1], p[2], p[3], p[4])
            return linkFor(if (fid != null) "/media/file_$fid.mpg" else "/media/${p[4]}.mpg", "epizodu")
        }
        val cmd = p.getOrElse(1) { "" }; val n = p.getOrElse(2) { "" }
        val body = call("type=vod&action=create_link&cmd=${enc(cmd)}&series=$n&forced_storage=undefined&disable_ad=0&download=0")
        val link = ((try { JSONObject(body).opt("js") } catch (_: Exception) { null }) as? JSONObject)?.optString("cmd").orEmpty()
        val url = StalkerProto.cleanCmd(link.ifEmpty { cmd })
        if (!url.startsWith("http")) throw HttpError("Portál nevrátil odkaz na epizodu. Odpověď: ${Redact.url(body).take(200)}")
        return url
    }

    override fun vodInfo(id: String): VodInfo = VodInfo(null, null, null, null, null, null, null, null, null)

    /** Soubor filmu: nejdřív id souboru (get_ordered_list s movie_id), pak odkaz pro „/media/file_ID.mpg“. */
    private fun fileId(type: String, movieId: String, seasonId: String = "0", episodeId: String = "0"): String? = try {
        JSONObject(call("type=$type&action=get_ordered_list&movie_id=${enc(movieId)}&season_id=${enc(seasonId)}&episode_id=${enc(episodeId)}&p=1"))
            .optJSONObject("js")?.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    private fun linkFor(cmd: String, what: String): String {
        val body = call("type=vod&action=create_link&cmd=${enc(cmd)}&series=&forced_storage=undefined&disable_ad=0&download=0")
        val link = ((try { JSONObject(body).opt("js") } catch (_: Exception) { null }) as? JSONObject)?.optString("cmd").orEmpty()
        val url = StalkerProto.cleanCmd(link.ifEmpty { cmd })
        if (!url.startsWith("http")) throw HttpError("Portál nevrátil odkaz na $what. cmd: ${cmd.take(60)} · odpověď: ${Redact.url(body).take(200)}")
        return url
    }

    override fun vodUrl(item: VodItem): String {
        val fid = fileId("vod", item.id)
        return linkFor(if (fid != null) "/media/file_$fid.mpg" else item.ext, "film")
    }
}
