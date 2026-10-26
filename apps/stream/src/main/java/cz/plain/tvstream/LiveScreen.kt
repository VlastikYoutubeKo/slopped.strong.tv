package cz.plain.tvstream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** Položka panelu skupin. */
class RailEntry(val id: String, val label: String, val sub: String?, val count: Int, val test: (LiveItem) -> Boolean)

/** Malé tlačítko v záhlaví obrazovky (řazení, filtry…). */
fun Context.chip(text: String, icon: Int = 0, onClick: () -> Unit): TextView = label(Typo.SMALL, Design.TEXT, 500).apply {
    this.text = text
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(14), 0, dp(Sp.L), 0)
    compoundDrawablePadding = dp(Sp.S)
    background = Design.glass(this@chip, Design.R_CTRL)
    // Minimalisticky bez ikon: čip je text.
    Design.focusable(this, Design.R_CTRL, Design.SCALE_CHIP)
    setOnClickListener { onClick() }
}

/**
 * Živé vysílání (a rádio): skupiny | kanály | detail s EPG. Seznam je virtualizovaný (tisíce kanálů),
 * řazení / filtry / hledání se počítají mimo hlavní vlákno a zaostřený kanál se po změně udrží.
 */
class LiveScreen(act: MainActivity, private val kind: ChannelKind) : Screen(act) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val isRadio = kind == ChannelKind.RADIO

    private val root = FrameLayout(act)
    override val view: View get() = root

    // stav
    private var rail: List<RailEntry> = emptyList()
    private var railIndex = 0
    private var visible: List<LiveItem> = emptyList()
    private var sort = SortMode.of(if (isRadio) Settings.radioSort else Settings.sort)
    private var filters = Filters()
    private var query = ""
    private var libVersion = -1
    private var gen = 0
    private var focusedKey: String? = null
    private var loadedOnce = false

    // pohledy
    private val title = act.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = if (isRadio) "Rádio" else "Živě" }
    private val count = act.label(Typo.SMALL, Design.MUTED, 400, mono = true)
    private val hintsCompact = act.hintBar("OK" to "Přehrát", "Podržet OK" to "Možnosti", "◀" to "Skupiny", "Zpět" to "Nahoru")
    private val hintsTimeline = act.hintBar("OK" to "Přehrát", "Podržet OK" to "Možnosti", "◀▶" to "Pořad", "▲▼" to "Kanál", "Zpět" to "Nahoru")
    private val sortChip: TextView
    private val filterChip: TextView
    private val queryChip: TextView
    private val modeChip: TextView
    private val groupChip: TextView
    private val timeline: TimelineView
    private val bodyRef: View
    // Časová osa jen pro televizi: rádio nemá program po časech, vždy kompaktní seznam.
    private var mode = if (kind == ChannelKind.RADIO) "compact" else Settings.epgMode
    private val railList = RecyclerView(act)
    private val list = RecyclerView(act)
    private val listTitle = act.caption("")
    private val empty = act.label(Typo.LABEL, Design.TEXT2, 400, lines = 4).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f); visibility = View.GONE }
    private val detail = Detail()

    private val railAdapter = SimpleAdapter<RailEntry>({ c -> railRow(c) }) { v, e, i -> bindRail(v, e, i) }
    private val adapter = SimpleAdapter<LiveItem>({ c -> ChannelRow(c) }) { v, it, i -> (v as ChannelRow).bind(it) }

    private val epgListener: (String?) -> Unit = { id ->
        // Nepřekreslovat celý seznam: jen viditelné řádky a detail. Fokus a scroll se nemění.
        val lm = list.layoutManager as LinearLayoutManager
        val a = lm.findFirstVisibleItemPosition(); val b = lm.findLastVisibleItemPosition()
        if (mode == "timeline") timeline.refresh()
        else if (a >= 0 && b >= a) adapter.notifyItemRangeChanged(a, b - a + 1)
        detail.refresh()
        headerNote()
    }
    private val subscribed = HashSet<EpgStore>()

    init {
        val c = act
        root.setPadding(0, 0, 0, 0)
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // záhlaví
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(Sp.XXXL), c.dp(Sp.XS), c.dp(Sp.XXXL), c.dp(10)); clipChildren = false; clipToPadding = false }
        val titleBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(title); titleBox.addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.XS) })
        head.addView(titleBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sortChip = c.chip("", R.drawable.ic_sort) { sortMenu() }
        filterChip = c.chip("Filtry", R.drawable.ic_equalizer) { filterMenu() }
        queryChip = c.chip("Hledat", R.drawable.ic_search) { searchPrompt() }
        modeChip = c.chip("", R.drawable.ic_grid) { toggleMode() }
        groupChip = c.chip("", R.drawable.ic_list) { groupMenu() }
        timeline = TimelineView(act, { item, _ -> Actions.playLive(act, item, visible) }, { it2 -> Actions.channelMenu(act, it2) { adapter.notifyDataSetChanged(); timeline.refresh(); recomputeRailCounts() } })
        listOf(groupChip, modeChip, queryChip, filterChip, sortChip).forEach { head.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(42)).apply { marginStart = c.dp(10) }) }
        col.addView(head)

        // tělo: skupiny | seznam | detail
        val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(c.dp(40), 0, c.dp(40), 0); clipChildren = false; clipToPadding = false }
        col.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        bodyRef = body
        col.addView(timeline.view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val railBox = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(Sp.S), c.dp(Sp.S), c.dp(Sp.S), c.dp(Sp.S)); clipChildren = true; clipToPadding = false }
        railBox.addView(c.caption("Skupiny").apply { setPadding(c.dp(Sp.M), 0, 0, c.dp(Sp.S)) })
        railList.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.4f)
        railList.adapter = railAdapter; railList.itemAnimator = null; railList.overScrollMode = View.OVER_SCROLL_NEVER
        railList.clipChildren = true; railList.clipToPadding = false; railList.setPadding(c.dp(Sp.XS), c.dp(6), c.dp(Sp.XS), c.dp(6))
        railBox.addView(railList, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        body.addView(railBox, LinearLayout.LayoutParams(c.dp(204), MATCH_PARENT).apply { marginEnd = c.dp(Sp.S) })

        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        listTitle.setPadding(c.dp(14), c.dp(14), 0, c.dp(Sp.S)); mid.addView(listTitle)
        val frame = FrameLayout(c).apply { clipChildren = true; clipToPadding = false }
        list.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.4f)
        list.adapter = adapter; list.itemAnimator = null; list.overScrollMode = View.OVER_SCROLL_NEVER
        list.clipChildren = true; list.clipToPadding = false
        list.setPadding(c.dp(10), c.dp(Sp.S), c.dp(10), c.dp(Sp.S))
        frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { setMargins(c.dp(30), 0, c.dp(30), 0) })
        mid.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        body.addView(mid, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = c.dp(14) })

        body.addView(detail.view, LinearLayout.LayoutParams(c.dp(252), MATCH_PARENT))

        // nápověda ovládání (jemná, bez velkých tlačítek)
        // Nápověda ovládání se záměrně nezobrazuje: rozhraní je minimalistické a ovládání je pro D-pad samozřejmé.
        col.addView(hintsCompact.apply { visibility = View.GONE }); col.addView(hintsTimeline.apply { visibility = View.GONE })
        updateChips()
        applyMode()
    }

    private fun applyMode() {
        val tl = mode == "timeline"
        bodyRef.visibility = if (tl) View.GONE else View.VISIBLE
        modeChip.visibility = if (isRadio) View.GONE else View.VISIBLE
        timeline.view.visibility = if (tl) View.VISIBLE else View.GONE
        updateChips()
    }

    private fun toggleMode() {
        mode = if (mode == "timeline") "compact" else "timeline"
        Settings.epgMode = mode
        applyMode()
        if (mode == "timeline") timeline.submit(visible)
    }

    private fun groupMenu() = act.showMenu("Skupina", null, rail.take(400).mapIndexed { i, e ->
        MenuEntry(e.label + (e.sub?.let { "  ·  $it" } ?: ""), if (i == railIndex) R.drawable.ic_check else 0, detail = fmtCount(e.count), selected = i == railIndex) { chooseRail(i) }
    })

    // ---------------------------------------------------------------- životní cyklus

    override fun onShow() {
        subscribeEpg()
        if (!loadedOnce || libVersion != Library.version) recompute(firstTime = !loadedOnce)
        main.post { adapter.notifyItemRangeChanged(0, adapter.itemCount) }   // oblíbené / řazení mohlo být změněno jinde
    }

    /** Zúží výpis na jeden zdroj (z karty zdroje). */
    fun showSource(id: String) { filters = Filters(sources = setOf(id)); railIndex = 0; updateChips(); recompute(false) }

    override fun onHide() { subscribed.forEach { it.listeners -= epgListener }; subscribed.clear() }

    override fun onLibrary() {
        if (libVersion != Library.version) { recompute(false); subscribeEpg() }
        headerNote()
    }

    override fun enter(): Boolean {
        if (mode == "timeline") return timeline.focusFirst(focusedKey)
        if (visible.isNotEmpty()) return list.focusItem(0)
        return railList.focusItem(0)
    }

    override fun onBack(): Boolean {
        if (list.hasFocus()) { railList.getChildAt(railIndex.coerceAtLeast(0).coerceAtMost(maxOf(0, railList.childCount - 1)))?.requestFocus(); return true }
        return false
    }

    private fun subscribeEpg() {
        for (s in Library.sources()) Library.epg(s)?.let { if (subscribed.add(it)) it.listeners += epgListener }
    }

    // ---------------------------------------------------------------- výpočet výpisu (mimo hlavní vlákno)

    private fun recompute(firstTime: Boolean) {
        val my = ++gen
        val ver = Library.version
        val q = Sorting.fold(query)
        val sortMode = sort
        val f = filters
        val railId = rail.getOrNull(railIndex)?.id ?: "all"
        worker.execute {
            val all = Library.items().filter { it.kind == kind }
            val multi = Library.sources().size > 1
            // rail: počty za skupiny (jednou za verzi knihovny, jinak se jen znovu filtruje)
            val entries = buildRail(all, multi)
            val idx = entries.indexOfFirst { it.id == railId }.let { if (it < 0) 0 else it }
            var list = all.asSequence().filter(entries[idx].test)
            if (f.quality.isNotEmpty()) list = list.filter { it.ch.quality in f.quality }
            if (f.language.isNotEmpty()) list = list.filter { it.ch.language in f.language }
            if (f.sources.isNotEmpty()) list = list.filter { it.src.id in f.sources }
            if (f.epgOnly) list = list.filter { Library.backend(it.src).hasEpg(it.ch) }
            if (q.isNotEmpty()) { val words = q.split(' ').filter { it.isNotEmpty() }; list = list.filter { i -> words.all { w -> i.search.contains(w) } } }
            val sorted = Sorting.sort(list.toList(), sortMode) { it.sortKey }
            main.post {
                if (my != gen) return@post
                libVersion = ver; loadedOnce = true
                rail = entries; railIndex = idx
                railAdapter.submit(entries)
                visible = sorted
                adapter.submit(sorted)
                if (mode == "timeline") timeline.submit(sorted)
                railList.post { markRail() }
                afterSubmit()
            }
        }
    }

    private fun buildRail(all: List<LiveItem>, multi: Boolean): List<RailEntry> {
        val counts = HashMap<String, Int>()
        var fav = 0; var recent = 0
        val recentKeys = UserData.recent(if (isRadio) "radio" else "live").map { it.key }.toHashSet()
        for (i in all) {
            for (cat in i.ch.categoryIds) counts.merge("${i.src.id}|$cat", 1, Int::plus)
            if (i.favorite) fav++
            if (i.key in recentKeys) recent++
        }
        val out = ArrayList<RailEntry>()
        out += RailEntry("all", if (isRadio) "Všechny stanice" else "Všechny kanály", null, all.size) { true }
        out += RailEntry("fav", "Oblíbené", null, fav) { it.favorite }
        out += RailEntry("recent", "Naposledy sledované", null, recent) { it.key in recentKeys }
        for (src in Library.sources()) for (cat in Library.categories(src)) {
            val n = counts["${src.id}|${cat.id}"] ?: 0
            if (n == 0) continue
            val sid = src.id; val cid = cat.id
            out += RailEntry("$sid|$cid", cat.name, if (multi) src.name else null, n) { it.src.id == sid && cid in it.ch.categoryIds }
        }
        return out
    }

    private fun afterSubmit() {
        val shown = visible
        count.text = "${fmtCount(shown.size)} ${if (isRadio) "stanic" else "kanálů"}"
        listTitle.text = "${rail.getOrNull(railIndex)?.label ?: ""}  ·  ${fmtCount(shown.size)}".uppercase()
        val note = when {
            Library.sources().isEmpty() -> "Zatím tu nic není.\nPřidej zdroj v sekci Zdroje."
            shown.isEmpty() && Library.sources().any { Library.state(it.id)?.phase == Library.Phase.LOADING && Library.state(it.id)?.lineup == null } -> "Načítám kanály…"
            shown.isEmpty() && (query.isNotEmpty() || filters.active) -> "Nic neodpovídá hledání nebo filtrům."
            shown.isEmpty() && railIndex == 1 -> "Zatím žádné oblíbené.\nDlouhým stiskem OK na kanálu ho přidáš."
            shown.isEmpty() && railIndex == 2 -> "Zatím jsi nic nesledoval."
            shown.isEmpty() -> if (isRadio) "Žádné rozhlasové stanice." else "Žádné kanály."
            else -> null
        }
        empty.text = note ?: ""; empty.visibility = if (note == null) View.GONE else View.VISIBLE
        // Po změně výpisu se zaostřená položka (podle klíče) drží na místě, ať se nic neresetuje.
        val key = focusedKey
        if (key != null && list.hasFocus()) {
            val idx = shown.indexOfFirst { it.key == key }
            if (idx >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, (list.height * 0.4f).toInt() - act.dp(36))
            list.post { list.findViewHolderForAdapterPosition(maxOf(0, idx))?.itemView?.requestFocus() }
        } else detail.show(shown.firstOrNull())
        headerNote()
    }

    private fun headerNote() {
        val loading = Library.sources().firstNotNullOfOrNull { s -> Library.epg(s)?.progressNote?.let { s.name to it } }
        title.alpha = 1f
        count.text = "${fmtCount(visible.size)} ${if (isRadio) "stanic" else "kanálů"}" + (loading?.let { "  ·  EPG: ${it.second.removeSuffix("…")}…" } ?: "")
    }

    // ---------------------------------------------------------------- panel skupin

    private fun railRow(c: Context): View {
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(14), c.dp(10), c.dp(Sp.M), c.dp(10))
            background = Design.glass(c, Design.R_CTRL)
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(c.label(Typo.BODY, Design.TEXT2, 500))
            col.addView(c.label(Typo.BADGE, Design.MUTED, 400).apply { visibility = View.GONE })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(c.label(Typo.CAPTION, Design.MUTED, 400, mono = true).apply { visibility = View.GONE })
            layoutParams = ViewGroup.MarginLayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = c.dp(3) }
            Design.focusable(this, Design.R_CTRL, Design.SCALE_ROW)
            // Posluchač se nastaví jednou; aktuální pozici nese tag (řádek se recykluje).
            setOnFocusChangeListenerSafe { f -> if (f) { main.removeCallbacks(applyRail); applyRailPos = (tag as? Int) ?: 0; main.postDelayed(applyRail, 320) } }
        }
        return v
    }

    private fun bindRail(v: View, e: RailEntry, pos: Int) {
        val ll = v as LinearLayout
        val col = ll.getChildAt(0) as LinearLayout
        (col.getChildAt(0) as TextView).text = e.label
        (col.getChildAt(1) as TextView).apply { text = listOfNotNull(e.sub, fmtCount(e.count)).joinToString("  ·  "); visibility = View.VISIBLE }
        ll.isActivated = pos == railIndex
        (col.getChildAt(0) as TextView).setTextColor(if (pos == railIndex) Design.TEXT else Design.TEXT2)
        ll.tag = pos
        ll.setOnClickListener { chooseRail(pos); list.requestFocus() }
    }

    private var applyRailPos = 0
    private val applyRail = Runnable { if (railList.hasFocus()) chooseRail(applyRailPos) }

    private fun chooseRail(pos: Int) {
        if (pos == railIndex || pos !in rail.indices) return
        railIndex = pos
        markRail()
        recompute(false)
    }

    private fun markRail() {
        for (i in 0 until railList.childCount) {
            val ch = railList.getChildAt(i)
            val p = railList.getChildAdapterPosition(ch)
            ch.isActivated = p == railIndex
            ((ch as LinearLayout).getChildAt(0) as LinearLayout).let { (it.getChildAt(0) as TextView).setTextColor(if (p == railIndex) Design.TEXT else Design.TEXT2) }
        }
    }

    // ---------------------------------------------------------------- menu: řazení, filtry, hledání

    private fun updateChips() {
        sortChip.text = sort.label
        modeChip.text = if (mode == "timeline") "Časová osa" else "Kompaktní"
        groupChip.text = rail.getOrNull(railIndex)?.label ?: "Všechny"
        groupChip.visibility = if (mode == "timeline") View.VISIBLE else View.GONE
        filterChip.text = if (filters.active) "Filtry (${listOf(filters.quality.size, filters.language.size, filters.sources.size, if (filters.epgOnly) 1 else 0).sum()})" else "Filtry"
        queryChip.text = if (query.isEmpty()) "Hledat" else "„$query“"
        queryChip.setTextColor(if (query.isEmpty()) Design.TEXT else Design.accent)
        filterChip.setTextColor(if (filters.active) Design.accent else Design.TEXT)
    }

    private fun sortMenu() = act.showMenu("Řazení", null, SortMode.entries.map { m ->
        MenuEntry(m.label, if (m == sort) R.drawable.ic_check else 0, selected = m == sort) {
            sort = m; if (isRadio) Settings.radioSort = m.id else Settings.sort = m.id
            updateChips(); recompute(false)
        }
    })

    private fun searchPrompt() = act.askText(if (isRadio) "Hledat stanici" else "Hledat kanál", "Název kanálu", query) { q -> query = q; updateChips(); recompute(false) }

    private fun filterMenu() {
        val all = Library.items().filter { it.kind == kind }
        val e = ArrayList<MenuEntry>()
        e += MenuEntry("Kvalita", R.drawable.ic_equalizer, detail = if (filters.quality.isEmpty()) "vše" else filters.quality.joinToString(",")) { pickSet("Kvalita", all.mapNotNull { it.ch.quality }.groupingBy { it }.eachCount(), filters.quality) { filters = filters.copy(quality = it) } }
        e += MenuEntry("Jazyk", R.drawable.ic_list, detail = if (filters.language.isEmpty()) "vše" else filters.language.joinToString(",")) { pickSet("Jazyk", all.mapNotNull { it.ch.language }.groupingBy { it }.eachCount(), filters.language) { filters = filters.copy(language = it) } }
        if (Library.sources().size > 1) e += MenuEntry("Zdroj", R.drawable.ic_source, detail = if (filters.sources.isEmpty()) "vše" else "${filters.sources.size}") {
            val names = Library.sources().associate { it.id to it.name }
            pickSet("Zdroj", all.groupingBy { names[it.src.id] ?: it.src.id }.eachCount(), filters.sources.mapNotNull { names[it] }.toSet()) { picked -> filters = filters.copy(sources = Library.sources().filter { it.name in picked }.map { it.id }.toSet()) }
        }
        e += MenuEntry("Jen kanály s programem (EPG)", if (filters.epgOnly) R.drawable.ic_check else 0, selected = filters.epgOnly) { filters = filters.copy(epgOnly = !filters.epgOnly); updateChips(); recompute(false) }
        if (filters.active) e += MenuEntry("Zrušit všechny filtry", R.drawable.ic_close, danger = true) { filters = Filters(); updateChips(); recompute(false) }
        act.showMenu("Filtry", null, e)
    }

    /** Výběr více hodnot z množiny: položky se přepínají a menu se znovu otevře, dokud uživatel nedá Zpět. */
    private fun pickSet(title: String, options: Map<String, Int>, selected: Set<String>, apply: (Set<String>) -> Unit) {
        if (options.isEmpty()) { act.toast("Poskytovatel tuto informaci neuvádí"); return }
        val cur = selected.toMutableSet()
        fun show(): Unit = act.showMenu(title, "více hodnot lze kombinovat", options.entries.sortedByDescending { it.value }.take(24).map { (k, n) ->
            MenuEntry(k, if (k in cur) R.drawable.ic_check else 0, detail = fmtCount(n), selected = k in cur) { if (!cur.add(k)) cur.remove(k); apply(cur.toSet()); updateChips(); recompute(false); show() }
        })
        show()
    }

    // ---------------------------------------------------------------- řádek kanálu

    private inner class ChannelRow(val c: Context) : ListRow(c, heightDp = 60) {
        private val number = c.label(Typo.SMALL, Design.MUTED, 500, mono = true).apply { gravity = Gravity.END }
        private val logo = LogoTile(c, 13f)
        private val fav = c.icon(R.drawable.ic_star, 16, Design.accent)
        private var item: LiveItem? = null

        init {
            val lead = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            lead.addView(number, LayoutParams(c.dp(46), WRAP_CONTENT))
            lead.addView(logo, LayoutParams(c.dp(52), c.dp(36)).apply { marginStart = c.dp(6) })
            lead(lead, 104, 36)
            trail(fav)
            setOnFocusChangeListenerSafe { f -> if (f) item?.let { onRowFocus(it) } }
            setOnClickListener { item?.let { Actions.playLive(act, it, visible) } }
            onLongOk { item?.let { Actions.channelMenu(act, it) { adapter.notifyDataSetChanged(); recomputeRailCounts() } } }
        }

        fun bind(i: LiveItem) {
            item = i
            number.text = if (i.ch.number > 0) i.ch.number.toString() else ""
            logo.bind(i)
            val store = Library.epgOf(i)
            store?.ensureNow(i.ch)
            val now = store?.now(i.ch)
            fav.visibility = if (i.favorite) View.VISIBLE else View.INVISIBLE
            setContent(i.ch.name, now?.let { "${hhmm(it.start)}  ${it.title}" }, i.ch.quality?.takeIf { i.kind == ChannelKind.TV }, now?.let { it.progress() / 1000f },
                listOfNotNull(i.ch.number.takeIf { it > 0 }?.let { "kanál $it" }, i.ch.name, now?.title, i.ch.quality, if (i.favorite) "oblíbený" else null).joinToString(", "))
        }
    }

    private fun recomputeRailCounts() { recompute(false) }

    private fun onRowFocus(i: LiveItem) {
        focusedKey = i.key
        main.removeCallbacks(showDetail); pendingDetail = i
        main.postDelayed(showDetail, 150)
    }

    private var pendingDetail: LiveItem? = null
    private val showDetail = Runnable { detail.show(pendingDetail) }

    // ---------------------------------------------------------------- detail

    private inner class Detail {
        private val c = act
        private val logo = LogoTile(c, 20f, Design.R_STD)
        private val name = c.label(Typo.HEADING, Design.TEXT, 600, lines = 2)
        private val meta = c.label(Typo.SMALL, Design.MUTED, 400, lines = 2)
        private val live = c.liveBadge()
        private val time = c.label(Typo.SMALL, Design.TEXT2, 400, mono = true)
        private val nowTitle = c.label(Typo.TITLE, Design.TEXT, 600, lines = 2)
        private val progress = c.progressBar(4)
        private val desc = c.label(Typo.SMALL, Design.TEXT2, 400, lines = 3).apply { setLineSpacing(0f, 1.15f) }
        private val nowBlock = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        private val note = c.label(Typo.SMALL, Design.MUTED, 400, lines = 4).apply { setLineSpacing(0f, 1.15f) }
        private val next = object : LinearLayout(c) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val full = (1 until childCount).maxOfOrNull { getChildAt(it).height } ?: 0
                for (i in 0 until childCount) getChildAt(i).let { it.alpha = if (it.bottom <= height && (i == 0 || it.height >= full)) 1f else 0f }
            }
        }.apply { orientation = LinearLayout.VERTICAL }
        private var item: LiveItem? = null

        val view = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Design.glass(c, Design.R_LG)
            setPadding(c.dp(22), c.dp(20), c.dp(22), c.dp(14))
            addView(logo, LinearLayout.LayoutParams(c.dp(104), c.dp(62)))
            addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.M) })
            addView(meta, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.XS) })
            val badgeRow = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            badgeRow.addView(live); badgeRow.addView(time, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dp(10) })
            nowBlock.addView(badgeRow)
            nowBlock.addView(nowTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })
            nowBlock.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, c.dp(Sp.XS)).apply { topMargin = c.dp(Sp.M) })
            nowBlock.addView(desc, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.M) })
            addView(nowBlock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(18) })
            addView(note, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(18) })
            addView(next, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(Sp.L) })
        }

        fun show(i: LiveItem?) { item = i; refresh() }
        fun refresh() = bind(item)

        private fun bind(i: LiveItem?) {
            if (i == null) { view.visibility = View.INVISIBLE; return }
            view.visibility = View.VISIBLE
            logo.bind(i); name.text = i.ch.name
            val cats = Library.categories(i.src).filter { it.id in i.ch.categoryIds }.joinToString(", ") { it.name }
            meta.text = listOfNotNull(if (i.ch.number > 0) "Kanál ${i.ch.number}" else null, cats.ifBlank { null }, if (Library.sources().size > 1) i.src.name else null).joinToString("  ·  ")
            val epg = Library.epgOf(i)
            val sched = epg?.schedule(i.ch)
            val t = System.currentTimeMillis()
            val now = sched?.firstOrNull { it.isNow(t) } ?: epg?.now(i.ch)
            val upcoming = sched.orEmpty().filter { it.start >= (now?.stop ?: t) - 1000 }.take(6)
            val av = epg?.availability(i.ch)
            val msg = when {
                now != null || upcoming.isNotEmpty() -> null
                av == null -> "Program není k dispozici."
                av.kind == EpgStore.Kind.READY -> "Pro tento kanál teď není v programu nic."
                av.kind == EpgStore.Kind.ERROR -> "Program se nepodařilo načíst. ${av.text}"
                else -> av.text
            }
            note.setTextOrGone(msg)
            note.setTextColor(if (av?.kind == EpgStore.Kind.ERROR) Design.ERROR else Design.MUTED)
            nowBlock.visibility = if (now != null) View.VISIBLE else View.GONE
            if (now != null) {
                time.text = "${hhmm(now.start)}–${hhmm(now.stop)}"
                nowTitle.text = now.title; progress.progress = now.progress(t)
                desc.setTextOrGone(now.description.ifBlank { null })
            }
            next.removeAllViews()
            if (upcoming.isNotEmpty()) {
                next.addView(c.caption("Dále"))
                upcoming.forEach { p ->
                    val r = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
                    r.addView(c.label(Typo.SMALL, Design.MUTED, 500, mono = true).apply { text = hhmm(p.start) }, LinearLayout.LayoutParams(c.dp(58), WRAP_CONTENT))
                    r.addView(c.label(Typo.BODY, Design.TEXT2, 400).apply { text = p.title }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    next.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })
                }
            }
        }
    }
}

/** Bezpečné nastavení posluchače fokusu bez ztráty zvětšení a záře z [Design.focusable]. */
fun View.setOnFocusChangeListenerSafe(extra: (Boolean) -> Unit) {
    val prev = onFocusChangeListener
    onFocusChangeListener = View.OnFocusChangeListener { v, f -> prev?.onFocusChange(v, f); extra(f) }
}
