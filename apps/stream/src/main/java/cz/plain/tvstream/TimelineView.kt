package cz.plain.tvstream

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Průvodce jako časová osa: vlevo kanály, vpravo bloky pořadů podle času. Seznam je virtualizovaný (tisíce kanálů),
 * vodorovný posun je společný pro všechny řádky a řídí ho jen zaostřený pořad (◀▶ = předchozí / další pořad).
 * Geometrie je čistá matematika ([Timeline]), kreslí se jen to, co je vidět.
 */
object Timeline {
    /** Posun bloku v px od levého okraje okna; záporný = začal dřív, než okno začíná. */
    fun x(startMs: Long, windowStartMs: Long, pxPerMin: Float) = (startMs - windowStartMs) / 60_000f * pxPerMin

    /** Čas zarovnaný dolů na půlhodinu. */
    fun floor30(ms: Long) = ms - Math.floorMod(ms, 30 * 60_000L)

    /** Program, který obsahuje [t]; jinak null. */
    fun at(programs: List<Program>, t: Long): Program? = programs.firstOrNull { t >= it.start && t < it.stop }

    /** Následující pořad po [t] (jeho začátek je po [t]). */
    fun next(programs: List<Program>, t: Long): Program? = programs.filter { it.start > t }.minByOrNull { it.start }

    /** Předchozí pořad před pořadem obsahujícím [t]. */
    fun previous(programs: List<Program>, t: Long): Program? {
        val cur = at(programs, t)
        val ref = cur?.start ?: t
        return programs.filter { it.start < ref }.maxByOrNull { it.start }
    }

    /**
     * Kam posunout okno, aby byl pořad vidět: pokud začátek leží před oknem nebo konec za ním, okno se přesune
     * tak, aby pořad začínal 20 min od levého okraje. Jinak zůstane.
     */
    fun scrollTarget(windowStartMs: Long, visibleMin: Float, p: Program): Long {
        val s = (p.start - windowStartMs) / 60_000f
        val e = (p.stop - windowStartMs) / 60_000f
        val fits = (p.stop - p.start) / 60_000f <= visibleMin - 20
        return if (s < 5f || (fits && e > visibleMin - 5f) || (!fits && s > visibleMin - 30f)) floor30(p.start - 20 * 60_000L) else windowStartMs
    }
}

class TimelineView(
    private val act: MainActivity,
    private val onPlay: (LiveItem, Program?) -> Unit,
    private val onMenu: (LiveItem) -> Unit,
    /** Zdroj pořadů řádku; null = EPG zdroje (průvodce). Nahrávky dodávají vlastní bloky. */
    private val programsOf: ((LiveItem) -> List<Program>)? = null,
) {
    private val c = act
    val view = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    val pxPerMin = c.dpf(4.2f)
    private val channelColPx = c.dp(228)
    private val ruler = Ruler(c)
    val list = RecyclerView(c)
    private val infoTitle = c.label(Typo.TITLE, Design.TEXT, 600, lines = 1)
    private val infoTime = c.label(Typo.SMALL, Design.TEXT2, 500, mono = true)
    private val infoDesc = c.label(Typo.SMALL, Design.TEXT2, 400, lines = 2).apply { setLineSpacing(0f, 1.1f) }
    private var items: List<LiveItem> = emptyList()

    var windowStart = Timeline.floor30(System.currentTimeMillis() - 30 * 60_000L)
        private set
    var focusTime = System.currentTimeMillis()
        private set
    private var anim: ValueAnimator? = null
    private var stripWidth = c.resources.displayMetrics.widthPixels - c.dp(96) - channelColPx
    private val adapter = SimpleAdapter<LiveItem>({ Row(it) }) { v, it, _ -> (v as Row).bind(it) }

    init {
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(c.dp(Sp.XXXL) + channelColPx, 0, c.dp(Sp.XXXL), c.dp(6)) }
        head.addView(ruler, LinearLayout.LayoutParams(MATCH_PARENT, c.dp(Sp.XXL + Sp.S)))
        view.addView(head)
        list.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.4f)
        list.adapter = adapter; list.itemAnimator = null; list.overScrollMode = View.OVER_SCROLL_NEVER
        list.clipChildren = false; list.clipToPadding = false; list.setPadding(c.dp(Sp.XXXL), c.dp(Sp.XS), c.dp(Sp.XXXL), c.dp(Sp.XS))
        list.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> val w = (r - l) - c.dp(96) - channelColPx; if (w > 0 && w != stripWidth) { stripWidth = w; ruler.invalidate() } }
        val frame = android.widget.FrameLayout(c).apply { clipChildren = true; clipToPadding = false }
        frame.addView(list, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val info = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; background = Design.glass(c, Design.R_CTRL, solid = true); setPadding(c.dp(18), c.dp(10), c.dp(18), c.dp(10))
        }
        val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(infoTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); top.addView(infoTime)
        info.addView(top); info.addView(infoDesc, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(2) })
        view.addView(info, LinearLayout.LayoutParams(MATCH_PARENT, c.dp(86)).apply { setMargins(c.dp(Sp.XXXL), c.dp(6), c.dp(Sp.XXXL), c.dp(2)) })
    }

    fun submit(list: List<LiveItem>) { items = list; adapter.submit(list) }

    /** Okno a výchozí čas zaostření (např. nejnovější nahrávka). */
    fun resetWindow(t: Long) { windowStart = Timeline.floor30(t - 30 * 60_000L); focusTime = t; invalidateAll() }

    /** Nová data programu: překreslit viditelné řádky a informační panel. */
    fun refresh() {
        for (i in 0 until list.childCount) (list.getChildAt(i) as? Row)?.strip?.invalidate()
        (list.focusedChild as? Row)?.let { showInfo(it) }
    }

    fun focusFirst(key: String?): Boolean {
        val idx = if (key != null) items.indexOfFirst { it.key == key }.coerceAtLeast(0) else 0
        if (items.isEmpty()) return false
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, (list.height * 0.35f).toInt())
        list.post { list.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() ?: list.post { list.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() } }
        return true
    }

    private fun moveWindow(target: Long) {
        if (target == windowStart) return
        anim?.cancel()
        if (Design.reducedMotion) { windowStart = target; invalidateAll(); return }
        val from = windowStart.toDouble()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Design.DURATION; interpolator = Design.EASE
            addUpdateListener { windowStart = (from + (target - from) * (it.animatedValue as Float)).toLong(); invalidateAll() }
            start()
        }
    }

    private fun invalidateAll() {
        ruler.invalidate()
        for (i in 0 until list.childCount) (list.getChildAt(i) as? Row)?.strip?.invalidate()
    }

    private fun showInfo(r: Row) {
        val item = r.item ?: return
        val p = Timeline.at(r.programs(), focusTime)
        if (p == null) {
            infoTitle.text = item.ch.name; infoTime.text = ""
            infoDesc.text = if (r.programs().isEmpty()) "Poskytovatel k tomuto kanálu žádný program nedodává." else "V tuto dobu nic v programu není."
        } else {
            infoTitle.text = p.title; infoTime.text = "${hhmm(p.start)}–${hhmm(p.stop)}  ·  ${item.ch.name}"
            infoDesc.text = p.description
        }
    }

    // ---------------------------------------------------------------- časová osa nahoře

    private inner class Ruler(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.dpf(13f); typeface = Design.mono(c, 500); color = Design.MUTED }
        private val line = Paint().apply { color = Design.HAIRLINE; strokeWidth = c.dpf(1f) }
        private val now = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.accent; strokeWidth = c.dpf(2f) }
        override fun onDraw(cv: Canvas) {
            var t = windowStart
            val end = windowStart + (width / pxPerMin * 60_000f).toLong() + 30 * 60_000L
            while (t <= end) {
                val x = Timeline.x(t, windowStart, pxPerMin)
                cv.drawLine(x, height * 0.5f, x, height.toFloat(), line)
                cv.drawText(hhmm(t), x + c.dpf(6f), height * 0.92f, p)
                t += 30 * 60_000L
            }
            // datum levého okraje (u nahrávek z minulých dnů jinak není poznat, o který den jde)
            p.color = Design.accent
            val dp0 = p.textSize; p.textSize = c.dpf(12f)
            cv.drawText(java.text.SimpleDateFormat("EEEE d. M.", java.util.Locale.getDefault()).format(java.util.Date(windowStart + 30 * 60_000L)), c.dpf(6f), height * 0.38f, p)
            p.textSize = dp0
            p.color = Design.MUTED
            val nx = Timeline.x(System.currentTimeMillis(), windowStart, pxPerMin)
            if (nx in 0f..width.toFloat()) cv.drawLine(nx, 0f, nx, height.toFloat(), now)
        }
    }

    // ---------------------------------------------------------------- řádek

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        var item: LiveItem? = null
        val logo = LogoTile(ctx, 12f)
        val name = ctx.label(Typo.BODY, Design.TEXT, 600)
        val num = ctx.label(Typo.CAPTION, Design.MUTED, 500, mono = true)
        val strip = Strip(ctx)
        private var cached: List<Program> = emptyList()

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Design.glass(ctx, Design.R_CTRL)
            val left = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(10), 0, c.dp(6), 0) }
            left.addView(logo, LayoutParams(c.dp(46), c.dp(Sp.XXL)))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(c.dp(10), 0, 0, 0) }
            col.addView(name); col.addView(num)
            left.addView(col, LayoutParams(0, WRAP_CONTENT, 1f))
            addView(left, LayoutParams(channelColPx, MATCH_PARENT))
            addView(strip, LayoutParams(0, MATCH_PARENT, 1f))
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, c.dp(56)).apply { bottomMargin = c.dp(Sp.XS) }
            Design.focusable(this, Design.R_CTRL, 1f) { f -> if (f) { strip.invalidate(); showInfo(this) } else strip.invalidate() }
            setOnClickListener { item?.let { onPlay(it, Timeline.at(cached, focusTime)) } }
            onLongOk { item?.let(onMenu) }
            onKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT) { code -> step(code == KeyEvent.KEYCODE_DPAD_RIGHT) }
        }

        fun programs(): List<Program> = cached

        fun bind(i: LiveItem) {
            item = i
            logo.bind(i); name.text = i.ch.name
            num.text = if (i.ch.number > 0) i.ch.number.toString() else i.src.name
            val own = programsOf
            if (own != null) cached = own(i) else {
                val store = Library.epgOf(i)
                store?.ensureNow(i.ch)
                cached = store?.schedule(i.ch).orEmpty()
            }
            strip.invalidate()
        }

        /** ◀▶: předchozí / další pořad v řádku; na kraji se nic nestane (okno se neposouvá do prázdna). */
        private fun step(forward: Boolean): Boolean {
            val progs = cached
            val target = if (forward) Timeline.next(progs, focusTime) else Timeline.previous(progs, focusTime)
            if (target == null) {
                // Bez dalšího pořadu posun o půl hodiny, ať se dá procházet i nezaplněná osa.
                focusTime += if (forward) 30 * 60_000L else -30 * 60_000L
                moveWindow(Timeline.floor30(focusTime - 20 * 60_000L).coerceAtLeast(windowStart - 60 * 60_000L))
                showInfo(this); strip.invalidate()
                return true
            }
            focusTime = target.start
            moveWindow(Timeline.scrollTarget(windowStart, stripWidth / pxPerMin, target))
            showInfo(this); strip.invalidate()
            return true
        }

        inner class Strip(ctx: Context) : View(ctx) {
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.dpf(14f); typeface = Design.ui(c, 500) }
            private val rect = RectF()
            private val gap = c.dpf(3f)
            private val radius = c.dpf(8f)
            override fun onDraw(cv: Canvas) {
                val focused = this@Row.isFocused
                val now = System.currentTimeMillis()
                if (cached.isEmpty()) {
                    fill.color = Design.withAlpha(Color.WHITE, 0.04f); rect.set(gap, gap * 2, width - gap, height - gap * 2)
                    cv.drawRoundRect(rect, radius, radius, fill)
                    txt.color = Design.MUTED; cv.drawText("Bez programu", c.dpf(14f), height / 2f + txt.textSize / 3, txt)
                    return
                }
                for (p in cached) {
                    val l = Timeline.x(p.start, windowStart, pxPerMin) + gap
                    val r = Timeline.x(p.stop, windowStart, pxPerMin) - gap
                    if (r < 0 || l > width) continue
                    val cur = focused && focusTime >= p.start && focusTime < p.stop
                    fill.color = when {
                        cur -> Design.withAlpha(Design.accent, 0.85f)
                        p.isNow(now) -> Design.withAlpha(Design.accent, 0.22f)
                        else -> Design.withAlpha(Color.WHITE, 0.07f)
                    }
                    rect.set(l.coerceAtLeast(-radius), gap * 2, r, height - gap * 2)
                    cv.drawRoundRect(rect, radius, radius, fill)
                    txt.color = if (cur) Color.WHITE else Design.TEXT
                    val tx = l.coerceAtLeast(0f) + c.dpf(10f)
                    val room = r - tx - c.dpf(8f)
                    if (room > c.dpf(24f)) cv.drawText(TextUtils.ellipsize(p.title, android.text.TextPaint(txt), room, TextUtils.TruncateAt.END).toString(), tx, height / 2f + txt.textSize / 3, txt)
                }
                // „teď“
                val nx = Timeline.x(now, windowStart, pxPerMin)
                if (nx in 0f..width.toFloat()) { fill.color = Design.accent; cv.drawRect(nx - 1f, 0f, nx + 1f, height.toFloat(), fill) }
            }
        }
    }
}
