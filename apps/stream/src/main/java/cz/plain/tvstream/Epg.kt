package cz.plain.tvstream

import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * EPG pro aktuální zdroj. Dva způsoby podle backendu:
 *  - hromadně (Xtream: XMLTV, jedno stažení na pozadí, uložené na disk),
 *  - po kanálech / „teď“ pro všechny naráz (TVHeadend), líně a jen pro viditelné kanály.
 * Neúspěch se nikdy neukládá jako „kanál nemá program“: zobrazí se důvod a za chvíli se zkusí znovu.
 */
class EpgStore(private val backend: Backend, private val cacheDir: File, private val key: String) {

    enum class Kind { READY, LOADING, NONE, ERROR }
    class Avail(val kind: Kind, val text: String = "")
    private enum class Phase { IDLE, LOADING, READY, FAILED }

    private class Entry(val programs: List<Program>, val at: Long, val error: String?)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val bulkIo = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val nowAll = ConcurrentHashMap<String, Program>()
    @Volatile private var bulk: Map<String, List<Program>>? = null
    @Volatile private var phase = Phase.IDLE
    @Volatile private var note = ""
    @Volatile private var bulkError = ""
    @Volatile private var stopped = false

    /** Posluchači na hlavním vlákně: přibyla data pro kanál (id) nebo pro všechny (null). Více obrazovek může poslouchat zároveň. */
    val listeners = java.util.concurrent.CopyOnWriteArrayList<(String?) -> Unit>()
    private fun fire(id: String?) { for (l in listeners) l(id) }

    /** Text pro hlavičku průvodce, když se program ještě stahuje. */
    val progressNote: String? get() = if (phase == Phase.LOADING) note.ifBlank { "Stahuji program…" } else null

    private val refresher = object : Runnable {
        override fun run() { if (!stopped) { refreshNow(); main.postDelayed(this, 5 * 60_000L) } }
    }

    fun start(channels: List<Channel>) {
        main.post(refresher)
        startBulk(channels)
    }

    /** Hromadný program (Xtream XMLTV). Běží na pozadí s nízkou prioritou, ostatní může dál fungovat bez něj. */
    private fun startBulk(channels: List<Channel>) {
        bulkIo.execute {
            phase = Phase.LOADING
            note = "Stahuji program…"
            try {
                val m = backend.bulkEpg(channels, File(cacheDir, "epg-$key.tsv")) { msg ->
                    note = msg
                    main.post { if (!stopped) fire(null) }
                }
                if (m == null) phase = Phase.IDLE else { bulk = m; phase = Phase.READY }
            } catch (e: Exception) {
                bulkError = SourceForm.errorText(e)
                phase = Phase.FAILED
            }
            main.post { if (!stopped) fire(null) }
        }
    }

    /** „Co běží teď“ pro všechny kanály naráz (TVHeadend); při neúspěchu zkusí znovu po 30 s. */
    private fun refreshNow() {
        io.execute {
            val result: Map<String, Program>? = try { backend.nowForAll() } catch (_: Exception) { emptyMap<String, Program>() }
            if (result == null) return@execute              // backend „teď“ pro všechny neumí
            if (result.isNotEmpty()) { nowAll.clear(); nowAll.putAll(result); main.post { if (!stopped) fire(null) } }
            else main.postDelayed({ if (!stopped) refreshNow() }, 30_000)
        }
    }

    fun now(ch: Channel): Program? {
        val t = System.currentTimeMillis()
        bulk?.get(ch.id)?.firstOrNull { it.isNow(t) }?.let { return it }
        cache[ch.id]?.programs?.firstOrNull { it.isNow(t) }?.let { return it }
        return nowAll[ch.id]?.takeIf { it.isNow(t) }
    }

    /** Program od teď; null = zatím nenačteno (požadavek se zařadí / čeká se na stažení XMLTV). */
    fun schedule(ch: Channel): List<Program>? {
        val t = System.currentTimeMillis()
        bulk?.let { return it[ch.id].orEmpty().filter { p -> p.stop > t } }
        if (!backend.hasEpg(ch)) return emptyList()
        if (phase == Phase.LOADING) return null
        val e = cache[ch.id]
        val ttl = if (e?.error != null) RETRY_MS else TTL
        if (e == null || t - e.at >= ttl) request(ch)
        return e?.programs?.filter { it.stop > t }
    }

    /** Proč je (ne)vidět program – pro text v detailu. */
    fun availability(ch: Channel): Avail {
        if (!backend.hasEpg(ch)) return Avail(Kind.NONE, "Poskytovatel k tomuto kanálu žádný program nedodává.")
        if (bulk != null) return Avail(Kind.READY)
        if (phase == Phase.LOADING) return Avail(Kind.LOADING, note.ifBlank { "Stahuji program…" })
        val e = cache[ch.id]
        return when {
            e == null -> Avail(Kind.LOADING, "Načítám program…")
            e.error != null -> Avail(Kind.ERROR, e.error)
            else -> Avail(Kind.READY)
        }
    }

    /** Pro řádek seznamu: když není hromadný program, načíst program kanálu (TVHeadend bez „teď“). */
    fun ensureNow(ch: Channel) {
        if (bulk == null && phase != Phase.LOADING && nowAll.isEmpty() && backend.hasEpg(ch) && now(ch) == null) schedule(ch)
    }

    private fun request(ch: Channel) {
        if (!inFlight.add(ch.id)) return
        io.execute {
            val entry = try {
                Entry(backend.schedule(ch), System.currentTimeMillis(), null)
            } catch (e: Exception) {
                Entry(emptyList(), System.currentTimeMillis(), SourceForm.errorText(e))
            }
            cache[ch.id] = entry
            inFlight.remove(ch.id)
            main.post { if (!stopped) fire(ch.id) }
        }
    }

    fun shutdown() {
        stopped = true
        main.removeCallbacks(refresher)
        io.shutdownNow()
        bulkIo.shutdownNow()
    }

    companion object {
        private const val TTL = 10 * 60_000L
        private const val RETRY_MS = 60_000L
    }
}
