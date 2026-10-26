package cz.plain.tvstream

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Osobní data uživatele, jen lokálně v zařízení: oblíbené, historie sledování, rozkoukané filmy a epizody,
 * ruční zařazení kanálů do rádia. Nic se neodesílá.
 *
 * Klíče: kanál „zdroj|kanál“, film „vod:zdroj|id“, epizoda „ep:zdroj|seriál|epizoda“.
 */
object UserData {
    private lateinit var sp: SharedPreferences
    private val favorites = LinkedHashSet<String>()
    /** Nejnovější první. */
    private val recent = ArrayList<Recent>()
    private val plays = HashMap<String, Int>()
    private val progress = LinkedHashMap<String, Progress>()
    private val radioOverride = HashMap<String, Boolean>()

    class Recent(val key: String, val kind: String, val ts: Long)

    /** Rozkoukaný film / epizoda. [sub] = např. „Seriál · S2E5“. */
    class Progress(
        val key: String, val sourceId: String, val title: String, val sub: String?, val poster: String?,
        val positionMs: Long, val durationMs: Long, val updated: Long, val ref: String,   // ref: id filmu, nebo „seriál|epizoda“
    ) {
        val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
        override fun equals(other: Any?) = other is Progress && other.key == key
        override fun hashCode() = key.hashCode()
        /** Dokoukané (>95 %) se z „Pokračovat“ ztrácí; začaté (<3 %) také. */
        val resumable: Boolean get() = durationMs > 0 && fraction in 0.03f..0.95f
    }

    @Synchronized fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("userdata", Context.MODE_PRIVATE)
        try { JSONArray(sp.getString("fav", "[]")).let { a -> for (i in 0 until a.length()) favorites += a.getString(i) } } catch (_: Exception) {}
        try { JSONArray(sp.getString("recent", "[]")).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { recent += Recent(it.getString("k"), it.optString("t"), it.optLong("ts")) } } } catch (_: Exception) {}
        try { JSONObject(sp.getString("plays", "{}")).let { o -> o.keys().forEach { plays[it] = o.getInt(it) } } } catch (_: Exception) {}
        try { JSONObject(sp.getString("radio", "{}")).let { o -> o.keys().forEach { radioOverride[it] = o.getBoolean(it) } } } catch (_: Exception) {}
        try {
            JSONArray(sp.getString("progress", "[]")).let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    progress[it.getString("k")] = Progress(it.getString("k"), it.optString("s"), it.optString("ti"), it.optString("sub").ifEmpty { null },
                        it.optString("po").ifEmpty { null }, it.optLong("p"), it.optLong("d"), it.optLong("u"), it.optString("r"))
                }
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- oblíbené

    @Synchronized fun isFavorite(key: String) = key in favorites
    @Synchronized fun favoriteKeys(): List<String> = favorites.toList()

    /** Přepne oblíbenou položku, vrací nový stav. */
    @Synchronized fun toggleFavorite(key: String): Boolean {
        val now = if (key in favorites) { favorites.remove(key); false } else { favorites += key; true }
        sp.edit().putString("fav", JSONArray(favorites.toList()).toString()).apply()
        return now
    }

    // Název a plakát oblíbených filmů a seriálů (kanály se berou z knihovny).
    data class FavMeta(val title: String, val poster: String?, val sourceId: String, val ref: String)

    @Synchronized fun putFavMeta(key: String, m: FavMeta) {
        val o = try { JSONObject(sp.getString("favmeta", "{}")) } catch (_: Exception) { JSONObject() }
        o.put(key, JSONObject().put("t", m.title).put("p", m.poster ?: "").put("s", m.sourceId).put("r", m.ref))
        sp.edit().putString("favmeta", o.toString()).apply()
    }

    @Synchronized fun favMeta(key: String): FavMeta? = try {
        JSONObject(sp.getString("favmeta", "{}")).optJSONObject(key)?.let { FavMeta(it.optString("t"), it.optString("p").ifEmpty { null }, it.optString("s"), it.optString("r")) }
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- historie a „nejsledovanější“

    /** Zapíše, že se kanál právě přehrává ([kind] „live“ nebo „radio“). */
    @Synchronized fun played(key: String, kind: String) {
        recent.removeAll { it.key == key }
        recent.add(0, Recent(key, kind, System.currentTimeMillis()))
        while (recent.size > 80) recent.removeAt(recent.size - 1)
        plays[key] = (plays[key] ?: 0) + 1
        sp.edit()
            .putString("recent", JSONArray().also { a -> recent.forEach { a.put(JSONObject().put("k", it.key).put("t", it.kind).put("ts", it.ts)) } }.toString())
            .putString("plays", JSONObject(plays as Map<*, *>).toString())
            .apply()
    }

    /** Klíče nejčastěji přehrávaných kanálů (alespoň 2 přehrání), nejčastější první. */
    @Synchronized fun mostPlayedKeys(n: Int): List<String> = plays.entries.filter { it.value > 1 }.sortedByDescending { it.value }.take(n).map { it.key }

    @Synchronized fun recent(kind: String? = null): List<Recent> = recent.filter { kind == null || it.kind == kind }
    @Synchronized fun playCount(key: String) = plays[key] ?: 0
    @Synchronized fun lastPlayed(key: String): Long = recent.firstOrNull { it.key == key }?.ts ?: 0L

    // ---------------------------------------------------------------- rozkoukané filmy a epizody

    @Synchronized fun saveProgress(p: Progress) {
        progress.remove(p.key)
        progress[p.key] = p
        while (progress.size > 200) progress.remove(progress.keys.first())
        val a = JSONArray()
        progress.values.forEach {
            a.put(JSONObject().put("k", it.key).put("s", it.sourceId).put("ti", it.title).put("sub", it.sub ?: "").put("po", it.poster ?: "")
                .put("p", it.positionMs).put("d", it.durationMs).put("u", it.updated).put("r", it.ref))
        }
        sp.edit().putString("progress", a.toString()).apply()
    }

    @Synchronized fun progress(key: String): Progress? = progress[key]

    /** Pokračovat ve sledování: nejnovější první. Od každého seriálu jen poslední epizoda. */
    @Synchronized fun continueWatching(): List<Progress> {
        val seen = HashSet<String>()
        return progress.values.sortedByDescending { it.updated }.filter { it.resumable }.filter {
            val group = if (it.key.startsWith("ep:")) it.key.substringBeforeLast('|') else it.key
            seen.add(group)
        }
    }

    // ---------------------------------------------------------------- ruční zařazení do rádia

    @Synchronized fun radioOverride(key: String): Boolean? = radioOverride[key]

    /** [radio] = true/false vynutí zařazení, null vrátí automatické rozpoznání. */
    @Synchronized fun setRadioOverride(key: String, radio: Boolean?) {
        if (radio == null) radioOverride.remove(key) else radioOverride[key] = radio
        sp.edit().putString("radio", JSONObject(radioOverride as Map<*, *>).toString()).apply()
    }

    /** Vymaže historii (oblíbené a ruční zařazení zůstávají). */
    @Synchronized fun clearHistory() {
        recent.clear(); plays.clear(); progress.clear()
        sp.edit().remove("recent").remove("plays").remove("progress").apply()
    }
}
