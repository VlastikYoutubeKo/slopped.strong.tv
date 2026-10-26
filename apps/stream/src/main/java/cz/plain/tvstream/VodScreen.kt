package cz.plain.tvstream

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Položka katalogu (film nebo seriál) jednoho zdroje. */
class CatalogEntry(val src: Source, val movie: VodItem?, val series: SeriesItem?) {
    val id get() = movie?.id ?: series!!.id
    val name get() = movie?.name ?: series!!.name
    val poster get() = movie?.poster ?: series?.poster
    val rating get() = (movie?.rating ?: series?.rating)?.toDoubleOrNull() ?: 0.0
    val year get() = movie?.year ?: series?.year
    val added get() = movie?.added ?: series?.lastModified ?: 0L
    val key get() = if (movie != null) VodActions.movieKey(src, id) else VodActions.seriesKey(src, id)
    val folded: String by lazy(LazyThreadSafetyMode.NONE) { Sorting.fold(name) }
    override fun equals(other: Any?) = other is CatalogEntry && other.key == key
    override fun hashCode() = key.hashCode()
}

/**
 * Filmy a seriály: vlevo kategorie (po zdrojích), vpravo mřížka plakátů. Kategorie se načítá až při zaostření
 * (katalogy mají desetitisíce položek), výsledek se drží v paměti po dobu běhu aplikace.
 */
class VodScreen(act: MainActivity, private val series: Boolean) : Screen(act) {
    private class Cat(val src: Source, val id: String?, val label: String, val sub: String?) {
        override fun equals(other: Any?) = other is Cat && other.src.id == src.id && other.id == id
        override fun hashCode() = src.id.hashCode() * 31 + (id?.hashCode() ?: 0)
    }

    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root
    private val title = act.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = if (series) "Seriály" else "Filmy" }
    private val count = act.label(Typo.SMALL, Design.MUTED, 400, mono = true)
    private val status = act.label(Typo.LABEL, Design.TEXT2, 400, lines = 3).apply { gravity = Gravity.CENTER; visibility = View.GONE; polite() }
    private val cats = SimpleAdapter<Cat>({ c -> catRow(c) }) { v, cat, _ -> bindCat(v as LinearLayout, cat) }
    private val catList = RecyclerView(act)
    private val gridAdapter = SimpleAdapter<CatalogEntry>({ PosterCard(it, 136) }) { v, e, _ ->
        (v as PosterCard).bind(e.name, e.poster, listOfNotNull(e.year, e.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
            progressOf(e), Library.client(e.src))
        v.setOnClickListener { open(e) }
        v.onLongOk { toggleFav(e) }
    }
    private val grid = RecyclerView(act)
    private val sortBtn: View
    private val sourceBtn: android.widget.TextView
    private var pickedSource: String? = null
    private var sort = 0
    private var query = ""
    private var selected = 0
    private var raw: List<CatalogEntry> = emptyList()
    private var loadGen = 0
    private val cache = HashMap<String, List<CatalogEntry>>()
    private var catEntries: List<Cat> = emptyList()
    private var lastVersion = -1
    private val sorts = listOf("Přidané", "Název A–Z", "Hodnocení", "Rok")
    private var switchRunnable: Runnable? = null

    init {
        val c = act
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(Sp.XXXL), c.dp(6), c.dp(Sp.XXXL), c.dp(Sp.M)) }
        head.addView(title)
        head.addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dp(Sp.L) })
        sourceBtn = c.chip("", R.drawable.ic_source) { pickSource() }
        head.addView(sourceBtn, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(42)).apply { marginStart = c.dp(20) })
        head.addView(c.spacer())
        sortBtn = c.glassButton("Řazení: ${sorts[0]}", R.drawable.ic_sort) { pickSort() }
        head.addView(sortBtn, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(Sp.XXXL)))
        head.addView(c.glassButton("Hledat", R.drawable.ic_search) { c.askText("Hledat v katalogu", "název…", query) { q -> query = q; render() } },
            LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(Sp.XXXL)).apply { marginStart = c.dp(10) })
        root.addView(head)

        val body = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        catList.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.35f)
        catList.adapter = cats; catList.itemAnimator = null; catList.clipToPadding = false; catList.clipChildren = false
        catList.setPadding(c.dp(Sp.XXXL), c.dp(6), c.dp(10), c.dp(30))
        body.addView(catList, LinearLayout.LayoutParams(c.dp(250), MATCH_PARENT))

        val span = (c.resources.displayMetrics.widthPixels / c.resources.displayMetrics.density - 250 - 70).let { (it / (136 + 16)).toInt().coerceAtLeast(3) }
        grid.layoutManager = GridLayoutManager(c, span)
        grid.adapter = gridAdapter; grid.itemAnimator = null; grid.clipToPadding = false; grid.clipChildren = false
        grid.setPadding(c.dp(10), c.dp(14), c.dp(Sp.XXXL), c.dp(40))
        val frame = android.widget.FrameLayout(c)
        frame.addView(grid, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(status, android.widget.FrameLayout.LayoutParams(c.dp(560), WRAP_CONTENT, Gravity.CENTER))
        body.addView(frame, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun progressOf(e: CatalogEntry): Float = if (e.movie != null) UserData.progress(e.key)?.takeIf { it.resumable }?.fraction ?: 0f else 0f

    private fun signature() = Library.sources().filter { capable(it) }.joinToString(",") { it.id }
    private var sig = ""

    override fun onShow() { if (sig != signature() || (catEntries.isEmpty() && !loadingCats)) buildCats() else gridAdapter.notifyItemRangeChanged(0, gridAdapter.itemCount) }
    override fun onLibrary() { if (sig != signature()) buildCats() }
    override fun enter(): Boolean = if (gridAdapter.itemCount > 0) grid.focusItem(0) else catList.focusItem(selected)

    // ---------------------------------------------------------------- kategorie

    private fun capable(s: Source): Boolean = Library.stats.get(s.id)?.caps?.let { if (series) it.series else it.vod } == true

    private var loadingCats = false

    private fun pickSource() {
        val all = Library.sources().filter { capable(it) }
        act.showMenu("Zdroj", null, all.map { s -> MenuEntry(s.name, R.drawable.ic_source, detail = s.type.label, selected = s.id == (activeSource(all)?.id)) { pickedSource = s.id; selected = 0; cache.clear(); buildCats() } })
    }

    private fun activeSource(all: List<Source>): Source? = all.firstOrNull { it.id == pickedSource } ?: all.firstOrNull()

    private fun buildCats() {
        sig = signature(); loadingCats = true
        lastVersion = Library.version
        val allCapable = Library.sources().filter { capable(it) }
        val srcs = listOfNotNull(activeSource(allCapable))
        sourceBtn.text = srcs.firstOrNull()?.name ?: ""
        sourceBtn.visibility = if (allCapable.size > 1) View.VISIBLE else View.GONE
        val multi = false
        val out = ArrayList<Cat>()
        cats.submit(emptyList())
        if (srcs.isEmpty()) { showStatus("Žádný zdroj tuto sekci nenabízí."); return }
        showStatus("Načítám kategorie…")
        var pending = srcs.size
        val collected = HashMap<String, List<Cat>>()
        for (s in srcs) Bg.run({
            val b = Library.backend(s)
            val list = if (series) b.seriesCategories() else b.vodCategories()
            listOf(Cat(s, null, if (multi) "Vše · ${s.name}" else "Vše", null)) + list.map { Cat(s, it.id, it.name, if (multi) s.name else null) }
        }) { r, err ->
            collected[s.id] = r ?: emptyList()
            if (err != null && collected.size == srcs.size && collected.values.all { it.isEmpty() }) showStatus("Katalog se nepodařilo načíst: ${Redact.url(SourceForm.errorText(err))}")
            if (--pending == 0) {
                srcs.forEach { out += collected[it.id].orEmpty() }
                catEntries = out
                loadingCats = false
                cats.submit(out)
                // Výchozí je první skutečná kategorie: „Vše“ u velkého poskytovatele znamená stažení desetitisíců položek.
                if (out.isNotEmpty()) { if (selected == 0 && out.size > 1) selected = 1; selected = selected.coerceIn(0, out.size - 1); loadCat(selected) }
            }
        }
    }

    private fun catRow(c: android.content.Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(c.dp(18), c.dp(10), c.dp(14), c.dp(10)); background = Design.glass(c, Design.R_CTRL)
        addView(c.label(Typo.LABEL, Design.TEXT, 500)); addView(c.label(Typo.CAPTION, Design.MUTED, 400))
        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = c.dp(6) }
        Design.focusable(this, Design.R_CTRL, Design.SCALE_ROW) { f -> if (f) debounceSelect(this) }
        setOnClickListener { grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    private fun bindCat(v: LinearLayout, cat: Cat) {
        (v.getChildAt(0) as android.widget.TextView).text = cat.label
        (v.getChildAt(1) as android.widget.TextView).setTextOrGone(cat.sub)
        v.tag = cat
        v.isActivated = catEntries.indexOf(cat) == selected
    }

    private fun debounceSelect(v: View) {
        val cat = v.tag as? Cat ?: return
        val i = catEntries.indexOf(cat)
        switchRunnable?.let { Bg.main.removeCallbacks(it) }
        val r = Runnable { if (i != selected || raw.isEmpty()) { selected = i; loadCat(i) } }
        switchRunnable = r
        Bg.main.postDelayed(r, 320)
    }

    private fun loadCat(i: Int) {
        val cat = catEntries.getOrNull(i) ?: return
        cats.notifyItemRangeChanged(0, cats.itemCount)   // jen přebarvit vybranou, fokus zůstane
        val ck = "${cat.src.id}|${cat.id}"
        cache[ck]?.let { raw = it; render(); return }
        val my = ++loadGen
        raw = emptyList(); gridAdapter.submit(emptyList()); showStatus("Načítám…")
        Bg.run({
            val b = Library.backend(cat.src)
            if (series) b.seriesItems(cat.id).map { CatalogEntry(cat.src, null, it) } else b.vodItems(cat.id).map { CatalogEntry(cat.src, it, null) }
        }) { r, err ->
            if (my != loadGen) return@run
            if (err != null) { showStatus("Nepodařilo se načíst: ${Redact.url(SourceForm.errorText(err))}"); return@run }
            cache[ck] = r.orEmpty(); raw = r.orEmpty(); render()
        }
    }

    private fun showStatus(t: String?) { status.text = t ?: ""; status.visibility = if (t == null) View.GONE else View.VISIBLE }

    private fun render() {
        val q = Sorting.fold(query)
        var list = if (q.isEmpty()) raw else raw.filter { it.folded.contains(q) }
        list = when (sort) {
            1 -> list.sortedBy { it.folded }
            2 -> list.sortedByDescending { it.rating }
            3 -> list.sortedByDescending { it.year?.toIntOrNull() ?: 0 }
            else -> list.sortedByDescending { it.added }
        }
        gridAdapter.submit(list)
        count.text = if (list.size == raw.size) fmtCount(list.size) else "${fmtCount(list.size)} z ${fmtCount(raw.size)}"
        showStatus(if (list.isEmpty()) (if (raw.isEmpty()) "V této kategorii nic není." else "Nic neodpovídá hledání „$query“.") else null)
        grid.scrollToPosition(0)
    }

    private fun pickSort() = act.showMenu("Řazení", null, sorts.mapIndexed { i, n ->
        MenuEntry(n, if (i == sort) R.drawable.ic_check else 0, selected = i == sort) { sort = i; (sortBtn as android.widget.TextView).text = "Řazení: $n"; render() }
    })

    private fun toggleFav(e: CatalogEntry) {
        if (e.movie != null) VodActions.toggleFavoriteMovie(act, e.src, e.movie) else VodActions.toggleFavoriteSeries(act, e.src, e.series!!)
    }

    private fun open(e: CatalogEntry) { act.push(if (e.movie != null) MovieDetailScreen(act, e.src, e.movie) else SeriesDetailScreen(act, e.src, e.series!!)) }
}

// ---------------------------------------------------------------- detail filmu

/** Pozadí detailu: velký obrázek přes celou obrazovku a tmavý přechod zleva, aby text zůstal čitelný. */
fun backdropFrame(act: MainActivity, image: ImageView, content: View): android.widget.FrameLayout {
    val f = android.widget.FrameLayout(act)
    f.addView(image, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    val scrim = View(act).apply {
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Design.withAlpha(Design.BG, 0.97f), Design.withAlpha(Design.BG, 0.88f), Design.withAlpha(Design.BG, 0.45f)))
    }
    f.addView(scrim, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    f.addView(content, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    return f
}

class MovieDetailScreen(act: MainActivity, private val src: Source, private val item: VodItem) : Screen(act) {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(64), act.dp(20), act.dp(64), act.dp(30)) }
    private val backdrop = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.9f }
    private val frame = backdropFrame(act, backdrop, root)
    override val view: View get() = frame
    private val poster = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Design.pill(Design.PLACEHOLDER, Design.R_STD, act); clipToOutline = true }
    private val meta = act.label(Typo.BODY, Design.TEXT2, 500, mono = true)
    private val plot = act.label(Typo.LABEL, Design.TEXT2, 400, lines = 8).apply { setLineSpacing(0f, 1.25f); maxLines = 8 }
    private val people = act.label(Typo.SMALL, Design.MUTED, 400, lines = 3)
    private var info: VodInfo? = null
    private val buttons = LinearLayout(act).apply { clipChildren = false }
    private var primary: View? = null

    init {
        val c = act
        root.addView(poster, LinearLayout.LayoutParams(c.dp(280), c.dp(420)))
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(40), 0, 0, 0) }
        col.addView(c.label(Typo.DISPLAY, Design.TEXT, 600, lines = 2).apply { text = item.name })
        col.addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })
        col.addView(plot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(18) })
        col.addView(people, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.M) })
        col.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(28) })
        root.addView(col, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        Logos.load(item.poster, poster, Library.client(src), 560)
        meta.text = listOfNotNull(item.year, item.rating?.takeIf { it != "0" && it != "0.0" }?.let { "★ $it" }).joinToString(" · ")
        buildButtons()
        Bg.run({ Library.backend(src).vodInfo(item.id) }) { r, _ -> r?.let { info = it; fill(it) } }
    }

    private fun fill(i: VodInfo) {
        meta.text = listOfNotNull(i.year ?: item.year, i.durationMin?.takeIf { it >= 5 }?.let { "$it min" }, (i.rating ?: item.rating)?.takeIf { it != "0" && it != "0.0" }?.let { "★ $it" }, i.genre).joinToString(" · ")
        plot.text = i.plot ?: ""
        people.text = listOfNotNull(i.director?.let { "Režie: $it" }, i.cast?.let { "Hrají: $it" }).joinToString("\n")
        i.backdrop?.let { Logos.load(it, backdrop, Library.client(src), 1280) }
        if (i.poster != null && item.poster == null) Logos.load(i.poster, poster, Library.client(src), 560)
    }

    private fun buildButtons() {
        val c = act
        buttons.removeAllViews()
        val key = VodActions.movieKey(src, item.id)
        val p = UserData.progress(key)?.takeIf { it.resumable }
        val main = c.primaryButton(if (p != null) "Pokračovat od ${mmss(p.positionMs)}" else "Přehrát", R.drawable.ic_play) { VodActions.playMovie(c, src, item, info) }
        buttons.addView(main, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(56)))
        primary = main
        if (p != null) buttons.addView(c.glassButton("Od začátku") { VodActions.playMovie(c, src, item, info, fromStart = true) }, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(56)).apply { marginStart = c.dp(Sp.M) })
        val fav = UserData.isFavorite(key)
        buttons.addView(c.glassButton(if (fav) "V oblíbených" else "Do oblíbených", if (fav) R.drawable.ic_star else R.drawable.ic_star_outline) { VodActions.toggleFavoriteMovie(c, src, item); buildButtons(); buttons.getChildAt(buttons.childCount - 1)?.requestFocus() },
            LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(56)).apply { marginStart = c.dp(Sp.M) })
    }

    override fun onShow() { buildButtons() }
    override fun enter(): Boolean = primary?.requestFocus() ?: false
}

fun mmss(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

// ---------------------------------------------------------------- detail seriálu

class SeriesDetailScreen(act: MainActivity, private val src: Source, private var item: SeriesItem) : Screen(act) {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(64), act.dp(20), act.dp(Sp.XXXL), 0) }
    private val backdrop = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.9f }
    private val frame = backdropFrame(act, backdrop, root)
    override val view: View get() = frame
    private val poster = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Design.pill(Design.PLACEHOLDER, Design.R_STD, act); clipToOutline = true }
    private val plot = act.label(Typo.BODY, Design.TEXT2, 400, lines = 3).apply { setLineSpacing(0f, 1.2f) }
    private val meta = act.label(Typo.SMALL, Design.TEXT2, 500, mono = true)
    private val seasonRow = LinearLayout(act).apply { clipChildren = false; clipToPadding = false }
    private val epList = RecyclerView(act)
    private val status = act.label(Typo.LABEL, Design.TEXT2, 400, lines = 3)
    private val favBtn: android.widget.TextView
    private var detail: SeriesDetail? = null
    private var season = 0
    private val eps = SimpleAdapter<Episode>({ EpisodeRow(it) }) { v, e, _ ->
        (v as EpisodeRow).bind(e, UserData.progress(VodActions.episodeKey(src, item.id, e.id)), Library.client(src))
        v.setOnClickListener { VodActions.playEpisode(act, src, item, e) }
    }

    init {
        val c = act
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(poster, LinearLayout.LayoutParams(c.dp(260), c.dp(390)))
        favBtn = c.glassButton("Do oblíbených", R.drawable.ic_star_outline) { VodActions.toggleFavoriteSeries(c, src, item); updateFav() }
        left.addView(favBtn, LinearLayout.LayoutParams(c.dp(260), c.dp(52)).apply { topMargin = c.dp(Sp.L) })
        root.addView(left, LinearLayout.LayoutParams(c.dp(260), MATCH_PARENT))
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(36), 0, 0, 0); clipChildren = false }
        right.addView(c.label(Typo.HEADLINE, Design.TEXT, 600, lines = 2).apply { text = item.name })
        right.addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(6) })
        right.addView(plot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })
        val seasonScroll = android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setPadding(0, c.dp(10), 0, c.dp(10)) }
        seasonScroll.addView(seasonRow)
        right.addView(seasonScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(10) })
        epList.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.3f)
        epList.adapter = eps; epList.itemAnimator = null; epList.clipToPadding = false; epList.setPadding(0, c.dp(6), c.dp(10), c.dp(40))
        val frame = android.widget.FrameLayout(c)
        frame.addView(epList, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(status, android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        right.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        Logos.load(item.poster, poster, Library.client(src), 520)
        plot.text = item.plot ?: ""
        meta.text = listOfNotNull(item.year, item.rating?.takeIf { it != "0" && it != "0.0" }?.let { "★ $it" }, item.genre).joinToString(" · ")
        status.text = "Načítám epizody…"
        updateFav()
        Bg.run({ Library.backend(src).seriesDetail(item.id) }) { r, err ->
            if (err != null || r == null) { status.text = "Epizody se nepodařilo načíst: ${Redact.url(SourceForm.errorText(err ?: Exception("?")))}"; return@run }
            detail = r
            item = r.series.copy2(item)
            plot.text = item.plot ?: ""
            meta.text = listOfNotNull(item.year, item.rating?.takeIf { it != "0" && it != "0.0" }?.let { "★ $it" }, item.genre).joinToString(" · ")
            r.series.backdrop?.let { Logos.load(it, backdrop, Library.client(src), 1280) }
            if (item.poster != null && r.series.poster != null) Logos.load(r.series.poster, poster, Library.client(src), 520)
            buildSeasons()
        }
    }

    private fun SeriesItem.copy2(old: SeriesItem) = SeriesItem(old.id, name.ifEmpty { old.name }, old.categoryId, old.poster ?: poster, plot ?: old.plot, year ?: old.year, rating ?: old.rating, genre ?: old.genre, backdrop, old.lastModified)

    private fun updateFav() {
        val fav = UserData.isFavorite(VodActions.seriesKey(src, item.id))
        favBtn.text = if (fav) "V oblíbených" else "Do oblíbených"
    }

    private fun buildSeasons() {
        val d = detail ?: return
        if (d.seasons.isEmpty()) { status.text = "Seriál nemá žádné epizody."; return }
        status.visibility = View.GONE
        // Předvybrat sezónu poslední sledované epizody.
        val last = d.seasons.values.flatten().mapNotNull { e -> UserData.progress(VodActions.episodeKey(src, item.id, e.id))?.let { e to it.updated } }.maxByOrNull { it.second }?.first
        season = last?.season ?: d.seasons.keys.first()
        seasonRow.removeAllViews()
        for (s in d.seasons.keys) {
            val b = act.glassButton("Sezóna $s") { selectSeason(s, focusEpisodes = true) }
            b.tag = s
            Design.focusable(b, Design.R_CTRL, Design.SCALE_CARD) { f -> if (f && season != s) selectSeason(s, focusEpisodes = false) }
            seasonRow.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, act.dp(44)).apply { marginEnd = act.dp(Sp.S) })
        }
        selectSeason(season, focusEpisodes = false)
        // Rozkoukaná epizoda dostane fokus.
        val list = d.seasons[season].orEmpty()
        val idx = list.indexOfFirst { it.id == last?.id }.coerceAtLeast(0)
        epList.post { epList.scrollToPosition(idx); epList.post { epList.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() } }
    }

    private fun selectSeason(s: Int, focusEpisodes: Boolean) {
        season = s
        for (i in 0 until seasonRow.childCount) seasonRow.getChildAt(i).isActivated = seasonRow.getChildAt(i).tag == s
        eps.submit(detail?.seasons?.get(s).orEmpty())
        if (focusEpisodes) epList.post { epList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }

    override fun onShow() { eps.notifyItemRangeChanged(0, eps.itemCount); updateFav() }
    override fun enter(): Boolean = epList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: favBtn.requestFocus()
}

class EpisodeRow(ctx: android.content.Context) : ListRow(ctx, padV = Sp.M) {
    private val num = ctx.label(Typo.LABEL, Design.accent, 600, mono = true).apply { gravity = Gravity.CENTER }
    private val state = ctx.label(Typo.SMALL, Design.TEXT2, 500, mono = true)
    private val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Design.pill(Design.PLACEHOLDER, Design.R_SM, ctx); clipToOutline = true; decorative() }

    init {
        val lead = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        lead.addView(thumb, LayoutParams(ctx.dp(150), ctx.dp(84)).apply { marginEnd = ctx.dp(Sp.M) })
        lead.addView(num, LayoutParams(ctx.dp(54), WRAP_CONTENT))
        lead(lead, 216)
        trail(state)
        subView.maxLines = 2; subView.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    fun bind(e: Episode, p: UserData.Progress?, client: okhttp3.OkHttpClient?) {
        thumb.visibility = if (e.poster == null) GONE else VISIBLE
        Logos.load(e.poster, thumb, client, 320)
        num.text = "E${e.number}"
        val done = p != null && p.durationMs > 0 && p.fraction > 0.95f
        state.text = when { done -> "✓ zhlédnuto"; e.durationMin != null -> "${e.durationMin} min"; else -> "" }
        setContent(e.title, e.plot, null, p?.takeIf { it.resumable }?.fraction, "epizoda ${e.number}, ${e.title}, ${state.text}")
    }
}
