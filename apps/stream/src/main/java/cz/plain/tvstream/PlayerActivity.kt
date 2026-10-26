package cz.plain.tvstream

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Přehrávač: video vrstva, rádio vrstva (vizualizér, právě hraje) a společná info lišta. Přehrávač samotný je
 * sdílený ([Playback]); tahle obrazovka ho jen ukazuje a ovládá. Rozhoduje se podle skutečných stop streamu, ne podle adresy.
 *
 * Ovládání: ▲▼ / CH± přepnutí kanálu (živě), ◀▶ posun ±10 s (filmy), OK info lišta / možnosti, Zpět konec.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var video: PlayerView
    private lateinit var radio: RadioView
    private lateinit var bar: InfoBar
    private lateinit var spinner: ProgressBar
    private lateinit var dimmer: View
    private lateinit var overlay: FrameLayout
    private lateinit var diag: TextView
    private var optionsOpen = false
    private var dimmed = false
    private var seekAccum = 0L
    private val sp get() = Playback

    private val listener = object : Playback.Listener() {
        override fun onItem(item: PlayItem?) { if (item == null) { if (!finishing) finish() } else { bindItem(item) } }
        override fun onMode(mode: Playback.Mode) { applyMode() }
        override fun onState(state: Playback.State, error: String?) { applyState(state, error) }
        override fun onNowPlaying(np: NowPlaying) { radio.bind(np); updateProgram() }
        override fun onDiagnostics() { if (Settings.diagnostics) refreshDiag() }
    }
    private var finishing = false
    private val epgListener: (String?) -> Unit = { bar.bind(); updateProgram() }
    private var epg: EpgStore? = null

    private val tickRun = object : Runnable {
        override fun run() {
            bar.tick()
            if (Playback.item?.live == false) Playback.tickProgress()
            if (Settings.diagnostics) refreshDiag()
            main.postDelayed(this, 1000)
        }
    }
    private val dimRun = Runnable { setDim(true) }
    private val shiftRun = object : Runnable { override fun run() { radio.shiftRandom(); main.postDelayed(this, 60_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Settings.init(this); UserData.init(this); Library.init(this); Playback.init(this)
        if (Playback.item == null) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = PlayerView(this).apply { useController = false; setShutterBackgroundColor(Color.BLACK); keepScreenOn = true; player = Playback.player }
        radio = RadioView(this)
        spinner = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Design.accent); visibility = View.GONE }
        bar = InfoBar()
        dimmer = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f; visibility = View.GONE }
        diag = label(Typo.CAPTION, Design.TEXT, 400, mono = true, lines = 0).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = Design.glass(this@PlayerActivity, Design.R_CTRL, solid = true); visibility = View.GONE
        }
        overlay = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Design.withAlpha(Color.BLACK, 0.55f)); isClickable = true }

        root.addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(radio, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(spinner, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        root.addView(bar.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(diag, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(dp(Sp.XL), dp(Sp.XL), dp(Sp.XL), 0) })
        root.addView(dimmer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        applyAspect()
    }

    override fun onStart() {
        super.onStart()
        if (Playback.item == null) { finish(); return }
        Playback.listeners += listener
        bindItem(Playback.item!!)
        applyMode(); applyState(Playback.state, Playback.error); radio.bind(Playback.nowPlaying)
        video.player = Playback.player
        if (Playback.mode != Playback.Mode.AUDIO && Playback.item?.live != null && Playback.state == Playback.State.PAUSED && Playback.item?.live == true) Playback.player.playWhenReady = true
        main.post(tickRun)
        if (Settings.diagnostics) { diag.visibility = View.VISIBLE; refreshDiag() } else diag.visibility = View.GONE
        wake()
    }

    override fun onStop() {
        super.onStop()
        Playback.listeners -= listener
        epg?.listeners?.remove(epgListener); epg = null
        main.removeCallbacksAndMessages(null)
        Playback.tickProgress()
        // Rádio může hrát dál na pozadí (volitelně); video se na pozadí zastaví, ať neběží dekodér ani síť.
        val keep = Playback.mode == Playback.Mode.AUDIO && Settings.radioBackground
        if (!keep && !isFinishing) Playback.player.playWhenReady = false
        video.player = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            optionsOpen -> closeOptions()
            bar.visible && Playback.item?.live == true && false -> bar.hide()
            else -> {
                val keep = Playback.mode == Playback.Mode.AUDIO && Settings.radioBackground
                finishing = true
                if (!keep) Playback.stop()
                finish()
            }
        }
    }

    // ---------------------------------------------------------------- stav

    private fun bindItem(item: PlayItem) {
        radio.bindStation(item)
        bar.bind()
        epg?.listeners?.remove(epgListener)
        epg = item.liveItem?.let { Library.epgOf(it) }?.also { it.listeners += epgListener }
        updateProgram()
        applyMode()
    }

    /** Pořad z EPG pro stanici, která nehlásí skladby (nikdy nepřepíše skladbu). */
    private fun updateProgram() {
        val li = Playback.item?.liveItem ?: return
        if (Playback.item?.kind == ChannelKind.RADIO) Playback.setProgram(epg?.now(li.ch)?.title)
    }

    private fun applyMode() {
        val item = Playback.item
        val audio = Playback.mode == Playback.Mode.AUDIO ||
            (Playback.mode == Playback.Mode.UNKNOWN && item?.kind == ChannelKind.RADIO)
        radio.visibility = if (audio) View.VISIBLE else View.GONE
        video.visibility = if (audio) View.INVISIBLE else View.VISIBLE
        radio.setMode(Settings.vizMode)
        if (audio) { main.removeCallbacks(shiftRun); main.postDelayed(shiftRun, 60_000); armDim() } else { main.removeCallbacks(shiftRun); setDim(false) }
        bar.view.visibility = if (audio) View.GONE else View.VISIBLE
        if (audio) bar.hide()
    }

    private fun applyState(state: Playback.State, error: String?) {
        val busy = state == Playback.State.LOADING || state == Playback.State.BUFFERING
        spinner.visibility = if (busy && radio.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        val err = state == Playback.State.ERROR
        radio.setState(when { err -> error; busy -> error ?: "Načítám…"; state == Playback.State.PAUSED -> "Pozastaveno"; else -> null }, err)
        bar.status(when { err -> error; busy -> error ?: "Načítám…"; state == Playback.State.PAUSED -> "Pozastaveno"; else -> null }, err)
        if (err || busy || state == Playback.State.PAUSED) { if (Playback.mode != Playback.Mode.AUDIO) bar.show(keep = true) }
        // Konec filmu / epizody.
        if (state == Playback.State.IDLE && Playback.item != null && Playback.item?.live == false && Playback.player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
            finishing = true; Playback.stop(); finish()
        }
    }

    private fun applyAspect() {
        video.resizeMode = when (Settings.aspect) { "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
    }

    private fun refreshDiag() {
        diag.text = Playback.diagnostics().joinToString("\n") { (k, v) -> "%-17s %s".format(k, v) }
    }

    // ---------------------------------------------------------------- ochrana obrazovky (rádio)

    private fun armDim() {
        main.removeCallbacks(dimRun)
        if (Playback.mode == Playback.Mode.AUDIO && Settings.radioDim) main.postDelayed(dimRun, 90_000)
    }

    private fun setDim(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        if (on) { dimmer.visibility = View.VISIBLE; dimmer.animate().alpha(0.62f).setDuration(if (Design.reducedMotion) 0 else 1800).start() }
        else dimmer.animate().alpha(0f).setDuration(if (Design.reducedMotion) 0 else 250).withEndAction { dimmer.visibility = View.GONE }.start()
    }

    private fun wake() { setDim(false); armDim() }

    // ---------------------------------------------------------------- ovládání

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) wake()
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (optionsOpen) return super.onKeyDown(keyCode, event)
        val item = Playback.item ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP ->
                if (item.live) zap(-1) else bar.show()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN ->
                if (item.live) zap(+1) else bar.show()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (!item.live) seekBy(-10_000) else bar.show()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (!item.live) seekBy(10_000) else bar.show()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> Playback.togglePause()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_INFO -> {
                if (Playback.mode == Playback.Mode.AUDIO || keyCode == KeyEvent.KEYCODE_MENU || bar.visible) showOptions() else bar.show()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun seekBy(deltaMs: Long) {
        val p = Playback.player
        val d = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, d))
        bar.show(); bar.tick()
    }

    private fun zap(dir: Int) {
        val q = Playback.queue
        if (q.isEmpty()) { bar.show(); return }
        val n = q.size
        val i = (Playback.queueIndex + dir + n) % n
        Playback.queueIndex = i
        Playback.play(Actions.playItemOf(q[i]))
        bar.show()
    }

    // ---------------------------------------------------------------- menu možností

    private fun showOptions() {
        val item = Playback.item ?: return
        val audio = Playback.mode == Playback.Mode.AUDIO
        val entries = ArrayList<MenuEntry>()
        val playing = Playback.state == Playback.State.PLAYING
        entries += MenuEntry(if (playing) "Pozastavit" else "Přehrát", if (playing) R.drawable.ic_pause else R.drawable.ic_play) { Playback.togglePause() }
        if (item.live && Playback.queue.size > 1) {
            entries += MenuEntry("Předchozí ${if (item.kind == ChannelKind.RADIO) "stanice" else "kanál"}", R.drawable.ic_prev) { zap(-1) }
            entries += MenuEntry("Další ${if (item.kind == ChannelKind.RADIO) "stanice" else "kanál"}", R.drawable.ic_next) { zap(+1) }
        }
        item.liveItem?.let { li ->
            val fav = li.favorite
            entries += MenuEntry(if (fav) "Odebrat z oblíbených" else "Přidat do oblíbených", if (fav) R.drawable.ic_star_outline else R.drawable.ic_star) { UserData.toggleFavorite(li.key) }
        }
        if (audio) {
            val modes = listOf("orb" to "Zářící koule", "bars" to "Spektrum", "wave" to "Vlna", "off" to "Vypnuto")
            entries += MenuEntry("Vizualizace", R.drawable.ic_equalizer, detail = modes.first { it.first == Settings.vizMode }.second) {
                val next = modes[(modes.indexOfFirst { it.first == Settings.vizMode } + 1) % modes.size]
                Settings.vizMode = next.first; radio.setMode(next.first)
            }
            entries += MenuEntry("Zobrazit video", R.drawable.ic_tv) { Playback.forceAudio(false) }
        } else {
            entries += MenuEntry("Jen zvuk", R.drawable.ic_volume) { Playback.forceAudio(true) }
            val aspects = listOf("fit" to "Přizpůsobit", "fill" to "Vyplnit", "zoom" to "Přiblížit")
            entries += MenuEntry("Poměr stran", R.drawable.ic_grid, detail = aspects.first { it.first == Settings.aspect }.second) {
                Settings.aspect = aspects[(aspects.indexOfFirst { it.first == Settings.aspect } + 1) % aspects.size].first; applyAspect()
            }
        }
        entries += MenuEntry("Diagnostika", R.drawable.ic_info, detail = if (Settings.diagnostics) "zapnuto" else "vypnuto") {
            Settings.diagnostics = !Settings.diagnostics; diag.visibility = if (Settings.diagnostics) View.VISIBLE else View.GONE; if (Settings.diagnostics) refreshDiag()
        }
        if (Playback.state == Playback.State.ERROR) entries += MenuEntry("Zkusit znovu", R.drawable.ic_refresh) { Playback.play(item) }
        entries += MenuEntry("Zastavit a zavřít", R.drawable.ic_close, danger = true) { finishing = true; Playback.stop(); finish() }

        overlay.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Design.glass(this@PlayerActivity, Design.R_LG, solid = true); setPadding(dp(28), dp(Sp.XL), dp(28), dp(22))
            clipChildren = false; clipToPadding = false
        }
        panel.addView(label(Typo.TITLE, Design.TEXT, 600).apply { text = item.title })
        item.subtitle?.let { panel.addView(label(Typo.SMALL, Design.TEXT2, 400).apply { text = it }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) }) }
        var first: View? = null
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        for (e in entries) {
            val tint = if (e.danger) Design.ERROR else Design.TEXT
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0); background = Design.glass(this@PlayerActivity, Design.R_CTRL)
                if (e.icon != 0) addView(icon(e.icon, 22, tint), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
                addView(label(Typo.LABEL, tint, 500).apply { text = e.label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                e.detail?.let { addView(label(Typo.SMALL, Design.TEXT2, 400, mono = true).apply { text = it }) }
                Design.focusable(this, Design.R_CTRL, Design.SCALE_ROW)
                setOnClickListener { closeOptions(); e.onClick() }
            }
            rows.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { topMargin = dp(Sp.S) })
            if (first == null) first = row
        }
        panel.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(rows) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        overlay.addView(panel, FrameLayout.LayoutParams(dp(460), WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(20); bottomMargin = dp(20) })
        overlay.visibility = View.VISIBLE; optionsOpen = true
        first?.requestFocus()
    }

    private fun closeOptions() { overlay.visibility = View.GONE; overlay.removeAllViews(); optionsOpen = false; window.decorView.requestFocus() }

    // ---------------------------------------------------------------- info lišta (televize, filmy)

    private inner class InfoBar {
        private val logo = LogoTile(this@PlayerActivity, 18f)
        private val channel = label(Typo.LABEL, Design.TEXT2, 500)
        private val title = label(Typo.HEADLINE, Design.TEXT, 600)
        private val time = label(Typo.BODY, Design.TEXT2, 400, mono = true)
        private val progress = progressBar(4)
        private val next = label(Typo.BODY, Design.MUTED, 400)
        private val clock = label(Typo.TITLE, Design.TEXT, 500, mono = true)
        private val statusText = label(Typo.BODY, Design.TEXT2, 500, lines = 4).apply { polite() }
        var visible = false; private set
        private val hideRun = Runnable { hide() }

        val view = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Design.withAlpha(Color.BLACK, 0.72f), Design.withAlpha(Color.BLACK, 0.94f)))
            setPadding(dp(Sp.XXXL), dp(130), dp(Sp.XXXL), dp(Sp.XXL)); alpha = 0f
            addView(logo, LinearLayout.LayoutParams(dp(132), dp(78)).apply { bottomMargin = dp(Sp.XS) })
            val col = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(Sp.XL), 0, dp(Sp.XL), 0) }
            col.addView(channel)
            col.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.S) })
            val tl = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            tl.addView(time)
            tl.addView(progress, LinearLayout.LayoutParams(dp(300), dp(Sp.XS)).apply { marginStart = dp(Sp.L) })
            col.addView(tl, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.M) })
            col.addView(next, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            col.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clock)
        }

        init {
            // nápověda ovládání vpravo nad hodinami (ikony tlačítek ovladače)
            val live = Playback.item?.live != false
            val hints = if (live) listOf("▲▼" to "kanál", "OK" to "možnosti") else listOf("◀▶" to "±10 s", "OK" to "možnosti")
            (view.getChildAt(1) as? LinearLayout)?.addView(hintBar(*hints.toTypedArray()), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(Sp.M) })
        }

        fun bind() {
            val it = Playback.item ?: return
            logo.bind(it.title, it.logo, Library.client(it.src), 200)
            val q = Playback.queue
            channel.text = if (it.live) listOfNotNull(it.liveItem?.ch?.number?.takeIf { n -> n > 0 }?.toString(), it.title).joinToString("   ") + (if (q.size > 1) "      ${Playback.queueIndex + 1} / ${q.size}" else "")
            else it.subtitle ?: it.src.name
            tick()
            if (!it.live) { title.text = it.title; next.visibility = View.GONE; return }
            val li = it.liveItem
            val e = li?.let { x -> Library.epgOf(x) }
            val t = System.currentTimeMillis()
            val sched = li?.let { x -> e?.schedule(x.ch) }
            val now = sched?.firstOrNull { p -> p.isNow(t) } ?: li?.let { x -> e?.now(x.ch) }
            val nx = sched.orEmpty().firstOrNull { p -> p.start >= (now?.stop ?: t) - 1000 }
            // Bez programu by se název kanálu zopakoval dvakrát: nahoře pak číslo a zdroj, velký titul je kanál.
            val num = it.liveItem?.ch?.number?.takeIf { n -> n > 0 }?.toString()
            val idx = if (q.size > 1) "      ${Playback.queueIndex + 1} / ${q.size}" else ""
            channel.text = listOfNotNull(num, if (now != null) it.title else it.src.name).joinToString("   ") + idx
            title.text = now?.title ?: it.title
            time.text = now?.let { p -> "${hhmm(p.start)} – ${hhmm(p.stop)}   ·   ${remaining(p, t)}" } ?: "Bez programu"
            progress.visibility = if (now == null) View.GONE else View.VISIBLE
            progress.progress = now?.progress(t) ?: 0
            next.text = nx?.let { p -> "Dále   ${hhmm(p.start)}   ${p.title}" } ?: ""
            next.visibility = if (nx == null) View.GONE else View.VISIBLE
        }

        /** Hodiny a u filmů pozice. */
        fun tick() {
            clock.text = hhmm(System.currentTimeMillis())
            val it = Playback.item ?: return
            if (!it.live) {
                val p = Playback.player
                val d = p.duration.takeIf { x -> x > 0 }
                time.text = if (d != null) "${mmss(p.currentPosition)} / ${mmss(d)}" else mmss(p.currentPosition)
                progress.visibility = if (d != null) View.VISIBLE else View.GONE
                progress.progress = if (d != null) (p.currentPosition * 1000 / d).toInt() else 0
            }
        }

        fun status(msg: String?, error: Boolean = false) {
            statusText.text = msg ?: ""
            statusText.setTextColor(if (error) Design.ERROR else Design.TEXT2)
            statusText.visibility = if (msg == null) View.GONE else View.VISIBLE
            if (msg == null && visible) { view.removeCallbacks(hideRun); view.postDelayed(hideRun, HIDE_MS) }
        }

        fun show(keep: Boolean = false) {
            if (Playback.mode == Playback.Mode.AUDIO) return
            visible = true
            view.animate().cancel(); view.animate().alpha(1f).setDuration(if (Design.reducedMotion) 0 else 150).start()
            view.removeCallbacks(hideRun)
            if (!keep) view.postDelayed(hideRun, HIDE_MS)
        }

        fun hide() { visible = false; view.animate().alpha(0f).setDuration(if (Design.reducedMotion) 0 else 250).start() }
    }

    private fun remaining(p: Program, now: Long): String { val m = ((p.stop - now) / 60_000).coerceAtLeast(0); return if (m >= 60) "zbývá ${m / 60} h ${m % 60} min" else "zbývá $m min" }

    companion object { private const val HIDE_MS = 5000L }
}
