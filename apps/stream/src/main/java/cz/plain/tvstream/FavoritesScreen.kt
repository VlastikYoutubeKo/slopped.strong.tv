package cz.plain.tvstream

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Oblíbené: kanály, rádia, filmy a seriály pohromadě. Dlouhý stisk OK položku odebere. */
class FavoritesScreen(act: MainActivity) : Screen(act) {
    private val scroll = SmoothScrollView(act, 0.3f)
    override val view: View get() = scroll
    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, act.dp(6), 0, act.dp(40)) }
    private val empty = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(act.dp(Sp.XXXL), act.dp(70), act.dp(Sp.XXXL), 0) }
    private var queues = HashMap<String, List<LiveItem>>()

    private val tv = Shelf<LiveItem>(act, "Televize", 250, 168, { ChannelCard(it) }) { v, item, _ -> bindChannel(v, item, "tv") }
    private val radio = Shelf<LiveItem>(act, "Rádio", 160, 214, { RadioCard(it) }) { v, item, _ ->
        (v as RadioCard).bind(item, null)
        v.setOnClickListener { Actions.playLive(act, item, queues["radio"].orEmpty().ifEmpty { listOf(item) }) }
        v.onLongOk { Actions.channelMenu(act, item) { refresh() } }
    }
    private val movies = Shelf<Pair<String, UserData.FavMeta>>(act, "Filmy", 170, 306, { PosterCard(it, 170) }) { v, (key, m), _ ->
        val src = Library.sources().firstOrNull { it.id == m.sourceId }
        (v as PosterCard).bind(m.title, m.poster, src?.name, UserData.progress(key)?.takeIf { it.resumable }?.fraction ?: 0f, src?.let { Library.client(it) })
        v.setOnClickListener { src?.let { s -> val p = m.ref.split('|'); act.push(MovieDetailScreen(act, s, VodItem(p[0], m.title, null, m.poster, null, null, 0, p.getOrNull(1) ?: "mp4"))) } ?: act.toast("Zdroj už neexistuje") }
        v.onLongOk { UserData.toggleFavorite(key); act.toast("Odebráno z oblíbených"); refresh() }
    }
    private val series = Shelf<Pair<String, UserData.FavMeta>>(act, "Seriály", 170, 306, { PosterCard(it, 170) }) { v, (key, m), _ ->
        val src = Library.sources().firstOrNull { it.id == m.sourceId }
        (v as PosterCard).bind(m.title, m.poster, src?.name, 0f, src?.let { Library.client(it) })
        v.setOnClickListener { src?.let { s -> act.push(SeriesDetailScreen(act, s, SeriesItem(m.ref, m.title, null, m.poster, null, null, null, null, null, 0))) } ?: act.toast("Zdroj už neexistuje") }
        v.onLongOk { UserData.toggleFavorite(key); act.toast("Odebráno z oblíbených"); refresh() }
    }

    init {
        content.addView(act.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = "Oblíbené" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = act.dp(Sp.XXXL); bottomMargin = act.dp(10) })
        listOf(tv, radio, movies, series).forEach { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        content.addView(empty, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.addView(content, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun bindChannel(v: View, item: LiveItem, q: String) {
        (v as ChannelCard).bind(item, Library.epgOf(item)?.now(item.ch))
        v.setOnClickListener { Actions.playLive(act, item, queues[q].orEmpty().ifEmpty { listOf(item) }) }
        v.onLongOk { Actions.channelMenu(act, item) { refresh() } }
    }

    override fun onShow() = refresh()
    override fun onLibrary() = refresh()
    override fun enter(): Boolean = listOf(tv, radio, movies, series).firstOrNull { it.visibility == View.VISIBLE }?.focusFirst() ?: false

    private fun refresh() {
        val keys = UserData.favoriteKeys().reversed()
        val live = keys.filter { !it.startsWith("vod:") && !it.startsWith("sr:") && !it.startsWith("ep:") }.mapNotNull { Library.find(it) }
        val tvItems = live.filter { it.kind == ChannelKind.TV }; val radioItems = live.filter { it.kind == ChannelKind.RADIO }
        queues["tv"] = tvItems; queues["radio"] = radioItems
        tv.submit(tvItems); radio.submit(radioItems)
        movies.submit(keys.filter { it.startsWith("vod:") }.mapNotNull { k -> UserData.favMeta(k)?.let { k to it } })
        series.submit(keys.filter { it.startsWith("sr:") }.mapNotNull { k -> UserData.favMeta(k)?.let { k to it } })
        val any = listOf(tv, radio, movies, series).any { it.visibility == View.VISIBLE }
        empty.removeAllViews(); empty.visibility = if (any) View.GONE else View.VISIBLE
        if (!any) {
            empty.addView(act.icon(R.drawable.ic_star, 48, Design.accent))
            empty.addView(act.label(Typo.HEADING, Design.TEXT, 600).apply { text = "Zatím nic oblíbeného" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = act.dp(14) })
            empty.addView(act.label(Typo.BODY, Design.TEXT2, 400, lines = 2).apply { text = "Dlouhým stiskem OK na kanálu, filmu nebo seriálu ho přidáš sem."; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(act.dp(520), WRAP_CONTENT).apply { topMargin = act.dp(Sp.S) })
        }
    }
}

/**
 * Hledání napříč všemi zdroji: televizní a rozhlasové kanály podle názvu (bez diakritiky, na velikosti písmen nezáleží).
 * Katalogy filmů a seriálů se prohledávají přímo v sekcích Filmy / Seriály (nejsou celé staženy v telefonu ani na boxu).
 */
class SearchScreen(act: MainActivity) : Screen(act) {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(64), act.dp(14), act.dp(64), 0) }
    override val view: View get() = root
    private val input = EditText(act)
    private val info = act.label(Typo.SMALL, Design.MUTED, 400, mono = true)
    private val list = RecyclerView(act)
    private var results: List<LiveItem> = emptyList()
    private val adapter: SimpleAdapter<LiveItem> = SimpleAdapter<LiveItem>({ Row(it) }) { v, item, _ ->
        (v as Row).bind(item)
        v.setOnClickListener { Actions.playLive(act, item, results.filter { it.kind == item.kind }) }
        v.onLongOk { Actions.channelMenu(act, item) { adapter.notifyDataSetChanged() } }
    }
    private var pending: Runnable? = null

    init {
        val c = act
        input.apply {
            hint = "Hledat kanály a rádia…"; textSize = Typo.TITLE; setSingleLine(); typeface = Design.ui(c, 400)
            setTextColor(Design.TEXT); setHintTextColor(Design.MUTED); background = Design.glass(c, Design.R_STD); setPadding(c.dp(22), 0, c.dp(22), 0)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            Design.focusable(this, Design.R_STD, Design.SCALE_FIELD)
            showSoftInputOnFocus = false
            setOnClickListener { showKeyboard() }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { pending?.let { Bg.main.removeCallbacks(it) }; val r = Runnable { search(s?.toString().orEmpty()) }; pending = r; Bg.main.postDelayed(r, 180) }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { v, _, _ -> (c.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus(); true }
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, c.dp(60)))
        root.addView(info, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(10); bottomMargin = c.dp(6) })
        list.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.3f)
        list.adapter = adapter; list.itemAnimator = null; list.clipToPadding = false; list.setPadding(0, c.dp(6), 0, c.dp(40))
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        search("")
    }

    private var searchGen = 0

    /** Hledání běží mimo hlavní vlákno (desítky tisíc kanálů), zastaralé výsledky se zahodí. */
    private fun search(q: String) {
        val f = Sorting.fold(q)
        val my = ++searchGen
        if (f.isEmpty()) { results = emptyList(); adapter.submit(results); info.text = "Piš název kanálu nebo stanice"; return }
        info.text = "Hledám…"
        Bg.run({ Library.items().filter { it.search.contains(f) }.sortedWith(compareBy({ !it.search.startsWith(f) }, { it.sortKey })).take(200) }) { r, _ ->
            if (my != searchGen) return@run
            results = r.orEmpty(); adapter.submit(results)
            info.text = if (results.isEmpty()) "Nic nenalezeno" else "${results.size}${if (results.size == 200) "+" else ""} výsledků"
        }
    }

    private fun showKeyboard() { (act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0) }

    override fun enter(): Boolean {
        input.requestFocus()
        input.postDelayed({ showKeyboard() }, 200)
        return true
    }

    private class Row(ctx: android.content.Context) : ListRow(ctx) {
        val logo = LogoTile(ctx, 15f)
        init { lead(logo, 76, Sp.XXXL) }
        fun bind(i: LiveItem) {
            logo.bind(i)
            val np = Library.epgOf(i)?.now(i.ch)
            setContent(i.ch.name, np?.let { "${hhmm(it.start)} ${it.title}" } ?: i.src.name, if (i.kind == ChannelKind.RADIO) "RÁDIO" else i.ch.quality)
        }
    }
}
