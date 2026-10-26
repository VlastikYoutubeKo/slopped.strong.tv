package cz.plain.tvstream

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Kanál zdroje + zdroj, ze kterého pochází. Stabilní identita napříč obnovením EPG i seznamu. */
class LiveItem(val src: Source, val ch: Channel, override val index: Int) : Sortable {
    val key: String = "${src.id}|${ch.id}"
    override fun equals(other: Any?) = other is LiveItem && other.key == key
    override fun hashCode() = key.hashCode()
    override val name: String get() = ch.name
    override val number: Int get() = ch.number
    override val favorite: Boolean get() = UserData.isFavorite(key)
    override val lastPlayed: Long get() = UserData.lastPlayed(key)
    override val plays: Int get() = UserData.playCount(key)
    /** Rádio nebo televize: ruční oprava má přednost před automatickým rozpoznáním. */
    val kind: ChannelKind get() = when (UserData.radioOverride(key)) { true -> ChannelKind.RADIO; false -> ChannelKind.TV; null -> ch.kind }
    val search: String by lazy(LazyThreadSafetyMode.NONE) { Sorting.fold(ch.name) }
    val sortKey: String get() = search
}

/**
 * Knihovna zdrojů: načte seznamy všech zdrojů najednou (z disku okamžitě, ze sítě na pozadí), drží stav
 * (načítá se / hotovo / chyba), počty a poslední synchronizaci. Obrazovky se přihlásí přes [listeners].
 */
object Library {
    enum class Phase { IDLE, LOADING, READY, ERROR }

    class State(val source: Source) {
        @Volatile var phase = Phase.IDLE
        @Volatile var note = ""
        @Volatile var error: String? = null
        @Volatile var lineup: Lineup? = null
        @Volatile var fromCache = false
        @Volatile var syncedAt = 0L
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val states = LinkedHashMap<String, State>()
    private val clients = HashMap<String, OkHttpClient>()
    private val backends = HashMap<String, Backend>()
    private val epgs = HashMap<String, EpgStore>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private const val TTL = 12 * 3_600_000L
    private const val FORMAT = "V2"

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    val store get() = SourceStore(app)
    val stats get() = SourceStats(app)

    private fun fireChanged() { main.post { listeners.forEach { it() } } }

    /** Zvyšuje se při každé změně seznamu kanálů; obrazovky podle něj poznají, že mají přepočítat výpis. */
    @Volatile var version = 0
        private set

    private fun lineupChanged() { itemsCache = null; version++ }

    @Synchronized fun sources(): List<Source> = store.all()

    @Synchronized fun state(id: String): State? = states[id]

    /** Zdroj se změnil (úprava nebo smazání): zahodí se klient, backend, EPG i cache. */
    @Synchronized fun forget(id: String, deleteCache: Boolean) {
        states.remove(id); clients.remove(id); backends.remove(id); lineupChanged()
        epgs.remove(id)?.shutdown()
        if (deleteCache) { cacheFile(id).delete(); File(app.cacheDir, "epg-$id.tsv").delete(); stats.clear(id) }
        fireChanged()
    }

    @Synchronized fun client(src: Source): OkHttpClient = clients.getOrPut(src.id) { Net.client(src) }
    @Synchronized fun backend(src: Source): Backend = backends.getOrPut(src.id) { Backends.of(src, client(src)) }

    /** Spustí načtení všech zdrojů (ze cache hned, ze sítě když je starší než 12 h). Bezpečné volat opakovaně. */
    fun start() { sources().forEach { ensure(it, force = false) } }

    fun refresh(src: Source) = ensure(src, force = true)

    @Synchronized private fun ensure(src: Source, force: Boolean) {
        val st = states.getOrPut(src.id) { State(src) }
        if (st.phase == Phase.LOADING) return
        if (!force && st.phase == Phase.READY && System.currentTimeMillis() - st.syncedAt < TTL) return
        st.phase = Phase.LOADING
        st.note = "Načítám…"
        st.error = null
        fireChanged()
        io.execute {
            // 1) cache z disku: průvodce i Domů jsou hned
            var cacheAge = Long.MAX_VALUE
            if (st.lineup == null) readCache(src.id)?.let { (l, t) ->
                st.lineup = l; lineupChanged(); st.fromCache = true; st.syncedAt = t; cacheAge = System.currentTimeMillis() - t
                st.phase = Phase.READY; fireChanged()
            } else cacheAge = System.currentTimeMillis() - st.syncedAt
            // 2) síť, jen když je cache stará (nebo chybí, nebo uživatel vynutil obnovení)
            val needCaps = stats.get(src.id)?.caps == null
            if (!force && st.lineup != null && cacheAge < TTL && !needCaps) { st.phase = Phase.READY; fireChanged(); return@execute }
            try {
                val b = backend(src)
                val l = b.load { step, msg -> st.note = "Krok $step ze 3 · $msg"; fireChanged() }
                val caps = try { b.caps() } catch (_: Exception) { Caps() }
                st.lineup = l; lineupChanged(); st.fromCache = false; st.syncedAt = System.currentTimeMillis(); st.error = null
                st.phase = Phase.READY
                writeCache(src.id, l, st.syncedAt)
                stats.update(src.id) { old ->
                    SourceStats.Stat(st.syncedAt, true, null, l.channels.size, l.channels.count { it.kind == ChannelKind.RADIO },
                        old?.movies ?: -1, old?.series ?: -1, caps.copy(radio = l.channels.any { it.kind == ChannelKind.RADIO }))
                }
            } catch (e: Exception) {
                val msg = SourceForm.errorText(e)
                st.error = msg
                st.phase = if (st.lineup != null) Phase.READY else Phase.ERROR   // staré kanály zůstanou použitelné
                stats.update(src.id) { old -> SourceStats.Stat(old?.lastSync ?: 0, false, msg, old?.channels ?: 0, old?.radio ?: 0, old?.movies ?: -1, old?.series ?: -1, old?.caps) }
            }
            fireChanged()
        }
    }

    @Volatile private var itemsCache: List<LiveItem>? = null

    /** Všechny kanály všech zdrojů (jen hotové), v pořadí zdrojů a poskytovatele. Drží se v paměti, dokud se kanály nezmění. */
    fun items(): List<LiveItem> {
        itemsCache?.let { return it }
        val out = ArrayList<LiveItem>()
        for (src in sources()) {
            val l = states[src.id]?.lineup ?: continue
            for (ch in l.channels) out += LiveItem(src, ch, out.size)
        }
        itemsCache = out
        return out
    }

    /** Počet kanálů všech načtených zdrojů (levné, nestaví seznam položek). */
    fun totalChannels(): Int = states.values.sumOf { it.lineup?.channels?.size ?: 0 }

    fun categories(src: Source): List<Category> = states[src.id]?.lineup?.categories.orEmpty()

    fun find(key: String): LiveItem? {
        val sid = key.substringBefore('|')
        val src = sources().firstOrNull { it.id == sid } ?: return null
        val l = states[sid]?.lineup ?: return null
        val cid = key.substringAfter('|')
        val idx = l.channels.indexOfFirst { it.id == cid }
        return if (idx >= 0) LiveItem(src, l.channels[idx], idx) else null
    }

    /** Průběžně připravený EPG zdroje (stahování se spustí jednou, při prvním dotazu po načtení kanálů). */
    @Synchronized fun epg(src: Source): EpgStore? {
        epgs[src.id]?.let { return it }
        val l = states[src.id]?.lineup ?: return null
        val s = EpgStore(backend(src), app.cacheDir, src.id)
        epgs[src.id] = s
        s.start(l.channels)
        return s
    }

    fun epgOf(item: LiveItem): EpgStore? = epg(item.src)

    /** Zahodí stažený program zdroje (stáhne se znovu). */
    @Synchronized fun epgReset(src: Source) { epgs.remove(src.id)?.shutdown(); File(app.cacheDir, "epg-${src.id}.tsv").delete() }

    @Synchronized fun clearEpgCaches(): Long {
        var n = 0L
        epgs.values.forEach { it.shutdown() }; epgs.clear()
        app.cacheDir.listFiles { f -> f.name.startsWith("epg-") }?.forEach { n += it.length(); it.delete() }
        return n
    }

    // ---------------------------------------------------------------- cache na disku (bez hesel)

    private fun cacheFile(id: String) = File(File(app.filesDir, "lineup").apply { mkdirs() }, "$id.gz")

    private fun writeCache(id: String, l: Lineup, t: Long) {
        try {
            val tmp = File(cacheFile(id).path + ".tmp")
            GZIPOutputStream(tmp.outputStream().buffered(1 shl 16)).bufferedWriter().use { w ->
                w.write("$FORMAT\t$t\n")
                for (c in l.categories) w.write("C\t${c.id.t()}\t${c.name.t()}\n")
                for (c in l.channels) w.write("H\t${c.id.t()}\t${c.number}\t${c.name.t()}\t${c.categoryIds.joinToString(",") { it.t().replace(',', '\u201A') }}\t${(c.logoUrl ?: "").t()}\t${c.ref.t()}\t${(c.epgId ?: "").t()}\t${c.kind.ordinal}\t${c.quality ?: ""}\t${c.language ?: ""}\n")
            }
            tmp.renameTo(cacheFile(id))
        } catch (_: Exception) { /* cache je jen urychlení */ }
    }

    private fun readCache(id: String): Pair<Lineup, Long>? = try {
        val f = cacheFile(id)
        if (!f.exists()) null else GZIPInputStream(f.inputStream().buffered(1 shl 16)).bufferedReader().useLines { lines ->
            val it = lines.iterator()
            val head = it.next().split('\t')
            if (head[0] != FORMAT) return@useLines null
            val cats = ArrayList<Category>()
            val chans = ArrayList<Channel>(60_000)
            while (it.hasNext()) {
                val x = it.next().split('\t')
                when (x[0]) {
                    "C" -> cats += Category(x[1], x[2])
                    "H" -> chans += Channel(
                        id = x[1], number = x[2].toInt(), name = x[3], categoryIds = if (x[4].isEmpty()) emptySet() else x[4].split(',').map { it.replace('\u201A', ',') }.toSet(),
                        logoUrl = x[5].ifEmpty { null }, ref = x[6], epgId = x[7].ifEmpty { null }, kind = ChannelKind.entries[x[8].toInt()],
                        quality = x[9].ifEmpty { null }, language = x.getOrNull(10)?.ifEmpty { null },
                    )
                }
            }
            Lineup(cats, chans) to head[1].toLong()
        }
    } catch (_: Exception) { null }

    private fun String.t() = replace('\t', ' ').replace('\n', ' ')
}
