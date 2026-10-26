package cz.plain.tvstream

import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pravidlo shody výsledku s dotazem (čistá logika, testovaná): žádný „přibližný“ obal k jiné písni. */
object CoverMatch {
    private fun tokens(s: String): Set<String> =
        Sorting.fold(s.replace(Regex("""\(.*?\)|\[.*?]"""), " ").replace(Regex("""(?i)\b(feat|ft|featuring)\b.*"""), " "))
            .split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()

    /** Dotazovaný i nalezený název musí mít shodné slovo-po-slově (pořadí nehraje roli), stejně tak interpret. */
    fun accept(queryArtist: String, queryTitle: String, resultArtist: String, resultTitle: String): Boolean {
        val qt = tokens(queryTitle); val rt = tokens(resultTitle)
        val qa = tokens(queryArtist); val ra = tokens(resultArtist)
        if (qt.isEmpty() || qa.isEmpty()) return false
        val titleOk = qt == rt || (qt.size >= 2 && rt.containsAll(qt)) || (rt.size >= 2 && qt.containsAll(rt))
        val artistOk = qa.any { it in ra } && (qa == ra || ra.containsAll(qa) || qa.containsAll(ra) || qa.intersect(ra).size >= 2)
        return titleOk && artistOk
    }
}

/**
 * Dohledání obalu alba přes veřejné iTunes Search API. Zapíná se výslovně v Nastavení (do služby se posílá
 * jen „interpret + název“, nic jiného). Výsledky se cachují a požadavky mají odstup 3 s.
 */
object CoverLookup {
    private val io = Executors.newSingleThreadExecutor()
    private val cache = LruCache<String, String>(300)   // dotaz → adresa obalu, nebo "" = nenalezeno
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    @Volatile private var lastAt = 0L

    fun find(artist: String, title: String, cb: (String?) -> Unit) {
        val q = "$artist - $title"
        cache.get(q)?.let { cb(it.ifEmpty { null }); return }
        io.execute {
            val wait = 3000 - (System.currentTimeMillis() - lastAt)
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return@execute }
            lastAt = System.currentTimeMillis()
            val url = try {
                val u = "https://itunes.apple.com/search?media=music&entity=song&limit=5&term=${android.net.Uri.encode("$artist $title")}"
                http.newCall(Request.Builder().url(u).header("User-Agent", Net.USER_AGENT).build()).execute().use { r ->
                    if (!r.isSuccessful) null else {
                        val arr = JSONObject(r.body!!.string()).optJSONArray("results")
                        var best: String? = null
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            if (CoverMatch.accept(artist, title, o.optString("artistName"), o.optString("trackName"))) {
                                best = o.optString("artworkUrl100").takeIf { it.startsWith("http") }?.replace("100x100bb", "600x600bb"); break
                            }
                        }
                        best
                    }
                }
            } catch (_: Exception) { null }
            cache.put(q, url ?: "")
            cb(url)
        }
    }
}
