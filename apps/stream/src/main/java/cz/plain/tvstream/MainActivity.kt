package cz.plain.tvstream

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Obrazovka uvnitř hlavního okna. Vlastní si svůj pohled a stav, aby po přepnutí karty zůstal scroll i fokus. */
abstract class Screen(val act: MainActivity) {
    abstract val view: View
    open fun onShow() {}
    open fun onHide() {}
    /** Změnila se knihovna zdrojů (kanály, stav, synchronizace). */
    open fun onLibrary() {}
    /** Předá fokus dovnitř obsahu (z horní lišty). Vrací false, když není co zaostřit. */
    open fun enter(): Boolean = view.requestFocus()
    /** Zpracuje tlačítko Zpět uvnitř obrazovky; false = nechat to na hlavním okně. */
    open fun onBack(): Boolean = false
    val ctx get() = act
}

class MenuEntry(val label: String, val icon: Int = 0, val detail: String? = null, val danger: Boolean = false, val selected: Boolean = false, val onClick: () -> Unit)

/**
 * Hlavní okno: horní navigace (Domů, Živě, Filmy, Seriály, Rádio, Oblíbené, Zdroje, Nastavení), obsah,
 * zásobník detailů a překryvné menu. Přehrávání je ve vlastní aktivitě ([PlayerActivity]).
 */
class MainActivity : Activity() {
    private class Tab(val id: String, val label: String, val icon: Int, val visible: () -> Boolean, val create: () -> Screen) { var view: LinearLayout? = null; var text: TextView? = null; var ic: android.widget.ImageView? = null }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var clock: TextView
    private lateinit var healthDot: View
    private lateinit var miniPlayer: TextView
    private lateinit var brand: TextView
    private val miniListener = object : Playback.Listener() {
        override fun onItem(item: PlayItem?) { updateMini() }
        override fun onMode(mode: Playback.Mode) { updateMini() }
        override fun onState(state: Playback.State, error: String?) { updateMini() }
        override fun onNowPlaying(np: NowPlaying) { updateMini() }
    }
    private lateinit var overlay: FrameLayout
    private lateinit var toast: TextView
    private lateinit var frame: FrameLayout
    private val tabs = ArrayList<Tab>()
    private val screens = HashMap<String, Screen>()
    private val stack = ArrayList<Screen>()
    private var current = "home"
    private var menuFocusBack: View? = null
    private var switchRunnable: Runnable? = null
    private var shownTabs: List<String> = emptyList()

    private val libListener: () -> Unit = { refreshTabs(); screens[current]?.onLibrary(); stack.lastOrNull()?.onLibrary() }

    private val tick = object : Runnable { override fun run() { clock.text = hhmm(System.currentTimeMillis()); main.postDelayed(this, 20_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this); UserData.init(this); Library.init(this); Playback.init(this)

        frame = FrameLayout(this).apply { background = AmbientBackground() }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildBar(), LinearLayout.LayoutParams(MATCH_PARENT, dp(76)))
        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        column.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        frame.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        overlay = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Design.withAlpha(Color.BLACK, 0.62f)); isClickable = true }
        frame.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        toast = label(Typo.BODY, Design.TEXT, 500).apply {
            polite()
            setPadding(dp(22), dp(Sp.M), dp(22), dp(Sp.M)); background = Design.glass(this@MainActivity, Design.R_CTRL, solid = true); visibility = View.GONE; alpha = 0f
        }
        frame.addView(toast, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(Sp.XXXL) })
        setContentView(frame)

        defineTabs()
        Library.listeners += libListener
        Library.start()
        refreshTabs()
        select(intent.getStringExtra("tab") ?: current, focus = false)
        tabRow.post { tabRow.getChildAt(tabs.filter { it.visible() }.indexOfFirst { it.id == current }.coerceAtLeast(0))?.requestFocus() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.getStringExtra("tab")?.let { select(it, focus = true) } }

    /** Rádio hraje na pozadí: v liště je tlačítko „právě hraje“, které vrátí do přehrávače. */
    private fun updateMini() {
        val it = Playback.item
        val show = it != null && Playback.mode == Playback.Mode.AUDIO && Playback.state != Playback.State.IDLE && Playback.state != Playback.State.ERROR
        miniPlayer.visibility = if (show) View.VISIBLE else View.GONE
        brand.text = if (show) "" else "TV Stream"   // místo v liště pro tlačítko „právě hraje“
        if (show) { val np = Playback.nowPlaying; miniPlayer.text = if (np.hasTrack) "${np.artist?.let { a -> "$a – " } ?: ""}${np.trackTitle}" else it!!.title }
    }

    /** Fokus před odchodem do přehrávače: po návratu se vrací na stejné místo (jinak by skončil na horní liště). */
    private var focusBeforePause: View? = null

    override fun onResume() { super.onResume(); Playback.listeners += miniListener; updateMini(); Library.start();
        focusBeforePause?.let { f -> main.post { if (f.isAttachedToWindow && f.visibility == View.VISIBLE && f.isFocusable) f.requestFocus() }; focusBeforePause = null }; main.post(tick); screens[current]?.onShow(); stack.lastOrNull()?.onShow() }
    override fun onPause() { super.onPause(); focusBeforePause = currentFocus?.takeIf { f -> tabs.none { it.view === f } }; Playback.listeners -= miniListener; main.removeCallbacks(tick); screens[current]?.onHide() }
    override fun onDestroy() { Library.listeners -= libListener; super.onDestroy() }

    // ---------------------------------------------------------------- horní lišta

    private fun defineTabs() {
        fun caps(f: (Caps) -> Boolean) = Library.sources().any { s -> Library.stats.get(s.id)?.caps?.let(f) == true }
        tabs += Tab("home", "Domů", R.drawable.ic_home, { true }) { HomeScreen(this) }
        tabs += Tab("live", "Živě", R.drawable.ic_live, { Library.sources().isNotEmpty() }) { LiveScreen(this, ChannelKind.TV) }
        tabs += Tab("movies", "Filmy", R.drawable.ic_movie, { caps { it.vod } }) { VodScreen(this, series = false) }
        tabs += Tab("series", "Seriály", R.drawable.ic_series, { caps { it.series } }) { VodScreen(this, series = true) }
        tabs += Tab("radio", "Rádio", R.drawable.ic_radio, { Library.sources().any { (Library.stats.get(it.id)?.radio ?: 0) > 0 } }) { LiveScreen(this, ChannelKind.RADIO) }
        tabs += Tab("recordings", "Nahrávky", R.drawable.ic_list, { Library.sources().any { it.type == SourceType.TVHEADEND } }) { RecordingsScreen(this) }
        tabs += Tab("favorites", "Oblíbené", R.drawable.ic_star, { true }) { FavoritesScreen(this) }
        tabs += Tab("sources", "Zdroje", R.drawable.ic_source, { true }) { SourcesScreen(this) }
        tabs += Tab("settings", "Nastavení", R.drawable.ic_settings, { true }) { SettingsScreen(this) }
    }

    private fun buildBar(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(Sp.XXXL), dp(Sp.S), dp(Sp.XXXL), dp(Sp.S)); clipChildren = false; clipToPadding = false }
        // značka aplikace
        val mark = label(Typo.LABEL, Design.TEXT, 600).also { brand = it }.apply {
            text = "TV Stream"; compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_tv_w, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Design.accent)
        }
        bar.addView(mark)
        bar.addView(View(this), LinearLayout.LayoutParams(dp(36), 1))
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        bar.addView(tabRow, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        healthDot = View(this).apply { background = Design.pill(Design.ERROR, 5f, this@MainActivity); visibility = View.GONE }
        bar.addView(healthDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) })
        miniPlayer = label(Typo.SMALL, Design.TEXT, 500).apply {
            visibility = View.GONE; setPadding(dp(Sp.L), 0, dp(Sp.L), 0); gravity = Gravity.CENTER_VERTICAL; maxWidth = dp(190)
            compoundDrawablePadding = dp(Sp.S); setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_volume, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Design.accent)
            background = Design.glass(this@MainActivity, Design.R_CTRL); Design.focusable(this, Design.R_CTRL, Design.SCALE_CARD)
            setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }
        }
        bar.addView(miniPlayer, LinearLayout.LayoutParams(WRAP_CONTENT, dp(46)).apply { marginEnd = dp(14) })
        val search = icon(R.drawable.ic_search, 22, Design.TEXT2).apply {
            setPadding(dp(11), dp(11), dp(11), dp(11)); background = Design.glass(this@MainActivity, Design.R_CTRL)
            Design.focusable(this, Design.R_CTRL); setOnClickListener { push(SearchScreen(this@MainActivity)) }
            describe("Hledat")
        }
        bar.addView(search, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(Sp.L) })
        clock = label(Typo.TITLE, Design.TEXT, 500, mono = true)
        bar.addView(clock)
        return bar
    }

    private fun refreshTabs() {
        val visible = tabs.filter { it.visible() }
        val ids = visible.map { it.id }
        // Řada se přestaví jen když se změní sada karet; jinak by každé hlášení knihovny zrušilo fokus.
        if (ids != shownTabs) {
            val hadFocus = tabs.firstOrNull { it.view?.hasFocus() == true }
            tabRow.removeAllViews()
            for (t in visible) {
                val v = t.view ?: makeTab(t).also { t.view = it }
                (v.parent as? ViewGroup)?.removeView(v)
                tabRow.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, dp(46)).apply { marginEnd = dp(Sp.XS) })
            }
            shownTabs = ids
            hadFocus?.view?.requestFocus()
        }
        visible.forEach { styleTab(it) }
        if (visible.none { it.id == current }) {
            select("home", focus = false)
        }
        // Tečka: některý zdroj je nedostupný.
        healthDot.visibility = if (Library.sources().any { Library.state(it.id)?.error != null }) View.VISIBLE else View.GONE
    }

    private fun makeTab(t: Tab): LinearLayout {
        val ic = icon(t.icon, 22, Design.TEXT2)
        val tx = label(Typo.BODY, Design.TEXT, 500).apply { visibility = View.GONE; setPadding(dp(Sp.S), 0, 0, 0) }
        t.ic = ic; t.text = tx
        val v = LinearLayout(this)
        v.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(Sp.M), 0, dp(Sp.M), 0)
            background = Design.glass(this@MainActivity, Design.R_CTRL)
            addView(ic); addView(tx); tx.text = t.label
            describe(t.label)
            // Fokus na kartu přepne obsah (s krátkou prodlevou, ať rychlé procházení kartami nestaví obrazovky).
            Design.focusable(this, Design.R_CTRL, Design.SCALE_CARD) { f ->
                styleTab(t, f)
                if (f) {
                    switchRunnable?.let { main.removeCallbacks(it) }
                    val r = Runnable { if (v.hasFocus() && current != t.id) select(t.id, focus = false) }
                    switchRunnable = r; main.postDelayed(r, 280)
                }
            }
            setOnClickListener { select(t.id, focus = true) }
            // DOLŮ z karty vstoupí do obsahu.
            onKey(KeyEvent.KEYCODE_DPAD_DOWN) { select(t.id, focus = true); true }
        }
        return v
    }
    private fun styleTab(t: Tab, focused: Boolean = t.view?.isFocused == true) {
        // Nad kartou otevřený detail / hledání: žádná karta není „aktivní“ (jinak svítí karta, která s obsahem nesouvisí).
        val active = t.id == current && stack.isEmpty()
        t.view?.isActivated = active
        t.view?.describe(t.label, selected = active)
        t.text?.visibility = if (active || focused) View.VISIBLE else View.GONE
        t.text?.setTextColor(if (active) Design.TEXT else Design.TEXT2)
        t.ic?.setColorFilter(if (active) Design.accent else if (focused) Design.TEXT else Design.TEXT2)
    }

    /** Otevře Živě omezené na jeden zdroj. */
    fun openLive(sourceId: String) {
        select("live", focus = true)
        (screens["live"] as? LiveScreen)?.showSource(sourceId)
    }

    /** Přepne obsah na kartu; [focus] = rovnou předat fokus dovnitř. */
    fun select(id: String, focus: Boolean) {
        val tab = tabs.firstOrNull { it.id == id && it.visible() } ?: tabs.first()
        while (stack.isNotEmpty()) pop(restore = false)
        if (current != tab.id) screens[current]?.let { it.onHide(); it.view.visibility = View.GONE }
        current = tab.id
        val sc = screens.getOrPut(tab.id) { tab.create().also { s -> content.addView(s.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) } }
        sc.view.visibility = View.VISIBLE
        sc.onShow()
        tabs.forEach { styleTab(it) }
        if (focus) { if (!sc.enter()) tab.view?.requestFocus() }
    }

    // ---------------------------------------------------------------- zásobník detailů

    fun push(s: Screen) {
        (stack.lastOrNull() ?: screens[current])?.let { it.view.visibility = View.GONE }
        stack += s
        content.addView(s.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        tabs.forEach { styleTab(it) }
        s.onShow()
        s.enter()
    }

    fun pop(restore: Boolean = true) {
        val s = stack.removeLastOrNull() ?: return
        s.onHide(); content.removeView(s.view)
        tabs.forEach { styleTab(it) }
        val below = stack.lastOrNull() ?: screens[current]
        below?.view?.visibility = View.VISIBLE
        if (restore) { below?.onShow(); below?.let { if (!it.enter()) tabRow.requestFocus() } }
    }

    // ---------------------------------------------------------------- překryvné menu a hlášení

    fun showMenu(title: String, subtitle: String?, entries: List<MenuEntry>) {
        menuFocusBack = currentFocus
        overlay.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Design.glass(this@MainActivity, Design.R_LG, solid = true); setPadding(dp(28), dp(26), dp(28), dp(22))
            clipChildren = false; clipToPadding = false
        }
        panel.addView(label(Typo.HEADING, Design.TEXT, 600).apply { text = title })
        subtitle?.let { panel.addView(label(Typo.SMALL, Design.TEXT2, 400, mono = true).apply { text = it }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(Sp.XS) }) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        var first: View? = null
        entries.forEach { e ->
            val tint = if (e.danger) Design.ERROR else if (e.selected) Design.accent else Design.TEXT
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
                background = Design.glass(this@MainActivity, Design.R_CTRL).also { }
                isActivated = e.selected
                describe(listOfNotNull(e.label, e.detail).joinToString(", "), selected = e.selected)
                if (e.icon != 0) addView(icon(e.icon, 22, tint), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
                addView(label(Typo.LABEL, tint, 500).apply { text = e.label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                e.detail?.let { addView(label(Typo.SMALL, Design.TEXT2, 400, mono = true).apply { text = it }) }
                Design.focusable(this, Design.R_CTRL, Design.SCALE_ROW)
                setOnClickListener { hideMenu(); e.onClick() }
            }
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(Sp.S) })
            if (first == null || e.selected) first = row
        }
        // Dlouhé menu (řazení, filtry) se posouvá; panel nikdy nepřeroste obrazovku.
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(list) }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
        overlay.addView(panel, FrameLayout.LayoutParams(dp(460), WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(Sp.XL); bottomMargin = dp(Sp.XL) })
        overlay.visibility = View.VISIBLE
        (content.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        first?.requestFocus()
    }

    /** Vstup textu v překryvu (hledání, názvy). Klávesnice se otevře sama; Zpět zavře a vrátí fokus. */
    fun askText(title: String, hint: String, initial: String = "", onDone: (String) -> Unit) {
        menuFocusBack = currentFocus
        overlay.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Design.glass(this@MainActivity, Design.R_LG, solid = true); setPadding(dp(28), dp(26), dp(28), dp(Sp.XL))
            clipChildren = false; clipToPadding = false
        }
        panel.addView(label(Typo.HEADING, Design.TEXT, 600).apply { text = title })
        val input = android.widget.EditText(this).apply {
            setText(initial); setSelection(initial.length); textSize = Typo.TITLE; setSingleLine(); this.hint = hint
            typeface = Design.ui(this@MainActivity, 400); setTextColor(Design.TEXT); setHintTextColor(Design.MUTED)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = Design.glass(this@MainActivity, Design.R_CTRL); setPadding(dp(18), 0, dp(18), 0)
            Design.focusable(this, Design.R_CTRL, 1f)
        }
        panel.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(Sp.L) })
        val done = { val t = input.text.toString().trim(); hideMenu(); onDone(t) }
        input.setOnEditorActionListener { _, _, _ -> done(); true }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        btns.addView(primaryButton("Hotovo") { done() }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(52)))
        btns.addView(glassButton("Zrušit") { hideMenu() }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(52)).apply { marginStart = dp(Sp.M) })
        panel.addView(btns, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        overlay.addView(panel, FrameLayout.LayoutParams(dp(640), WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(120) })
        overlay.visibility = View.VISIBLE
        (content.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        input.requestFocus()
        input.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0) }, 150)
    }

    fun hideMenu() {
        overlay.visibility = View.GONE; overlay.removeAllViews()
        (content.parent as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        menuFocusBack?.let { if (it.isAttachedToWindow) it.requestFocus() }
    }

    val menuOpen get() = overlay.visibility == View.VISIBLE

    fun toast(msg: String) {
        toast.text = msg; toast.visibility = View.VISIBLE
        toast.animate().cancel(); toast.animate().alpha(1f).setDuration(if (Design.reducedMotion) 0 else 180).withEndAction {
            toast.animate().alpha(0f).setStartDelay(2400).setDuration(if (Design.reducedMotion) 0 else 300).withEndAction { toast.visibility = View.GONE }.start()
        }.start()
    }

    // ---------------------------------------------------------------- tlačítko Zpět

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            menuOpen -> hideMenu()
            stack.isNotEmpty() -> { if (!stack.last().onBack()) pop() }
            tabRow.hasFocus() -> {
                if (current != "home") select("home", focus = false).also { tabRow.getChildAt(0)?.requestFocus() } else finish()
            }
            screens[current]?.onBack() == true -> {}
            else -> tabs.firstOrNull { it.id == current }?.view?.requestFocus()   // z obsahu zpět na horní lištu
        }
    }
}
