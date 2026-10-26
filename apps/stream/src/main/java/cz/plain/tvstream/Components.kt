package cz.plain.tvstream

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Dlaždice loga; bez loga ukáže monogram (první písmena názvu). */
class LogoTile(ctx: Context, private val textSp: Float = 13f, radiusDp: Float = Design.R_SM, padDp: Int = 6) : FrameLayout(ctx) {
    private val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val mono = ctx.label(textSp, Design.TEXT2, 600).apply { gravity = Gravity.CENTER }

    init {
        decorative()
        background = Design.pill(Design.PLACEHOLDER, radiusDp, ctx)
        val pad = ctx.dp(padDp)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(pad, pad, pad, pad) })
        addView(mono, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(name: String, logoUrl: String?, client: okhttp3.OkHttpClient?, maxPx: Int = 160) {
        mono.text = monogram(name)
        mono.visibility = VISIBLE
        Logos.load(logoUrl, image, client, maxPx) { mono.visibility = GONE }
    }

    fun bind(item: LiveItem) = bind(item.ch.name, item.ch.logoUrl, Library.client(item.src))

    /** „ČT24“ → ČT24, „CZ: Nova Cinema HD“ → NC, „Prima“ → PR. */
    private fun monogram(name: String): String {
        val words = name.replace(Regex("""^[A-Za-z]{2,3}\s*[:|]\s*"""), "").split(' ', '-', '_', '(', '[').filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        val first = words.firstOrNull() ?: return "?"
        return when {
            first.length <= 4 -> first
            words.size >= 2 && words[1][0].isLetter() -> "${first[0]}${words[1][0]}"
            else -> first.take(2)
        }.uppercase()
    }
}

/** Fokus na položku [pos] seznamu; když ještě není rozložená, počká na další snímek. */
fun RecyclerView.focusItem(pos: Int = 0): Boolean {
    findViewHolderForAdapterPosition(pos)?.itemView?.let { return it.requestFocus() }
    if ((adapter?.itemCount ?: 0) <= pos) return false
    post { findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    return true
}

/** Univerzální adaptér: `create` složí pohled jednou, `bind` ho naplní. Fokus a klik řeší pohled sám. */
class SimpleAdapter<T>(private val create: (Context) -> View, private val bind: (View, T, Int) -> Unit) : RecyclerView.Adapter<SimpleAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v)

    var items: List<T> = emptyList()
        private set

    /**
     * Nahradí seznam. Je-li stejně dlouhý a položky se rovnají (viz equals), jen se překreslí na místě: fokus ani posun se neztratí.
     * Při jiném obsahu se vše obnoví.
     */
    fun submit(list: List<T>) {
        val old = items
        items = list
        if (old.size == list.size && old.indices.all { old[it] == list[it] }) notifyItemRangeChanged(0, list.size) else notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(create(parent.context))
    override fun onBindViewHolder(holder: VH, position: Int) = bind(holder.itemView, items[position], position)
    override fun getItemCount() = items.size
}

/**
 * Seznam, který při D-pad pohybu drží zaostřenou položku na pevném místě (svisle ~[anchor] výšky,
 * vodorovně [leadDp] od okraje) a plynule posouvá obsah. Žádné skoky, žádné zakrývání zaostřené položky.
 */
class FocusLayoutManager(private val ctx: Context, orientation: Int, private val anchor: Float = 0.4f, private val leadDp: Int = 0) :
    LinearLayoutManager(ctx, orientation, false) {

    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean {
        if (!parent.isAttachedToWindow) return false
        val vertical = orientation == VERTICAL
        val delta = if (vertical) child.top + child.height / 2 - (parent.height * anchor).toInt()
        else child.left - ctx.dp(leadDp) - parent.paddingLeft
        if (delta == 0) return false
        if (immediate || Design.reducedMotion) { if (vertical) parent.scrollBy(0, delta) else parent.scrollBy(delta, 0) }
        else if (vertical) parent.smoothScrollBy(0, delta, Design.EASE, Design.DURATION.toInt())
        else parent.smoothScrollBy(delta, 0, Design.EASE, Design.DURATION.toInt())
        return true
    }
}

/** ScrollView, který zaostřený prvek plynule vystředí (výchozí skok na okraj na TV nevypadá dobře). */
class SmoothScrollView(ctx: Context, private val anchor: Float = 0.35f) : ScrollView(ctx) {
    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()
    init { isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false; clipToPadding = false }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        focused ?: return
        val r = Rect(); focused.getDrawingRect(r)
        offsetDescendantRectToMyCoords(focused, r)
        // Co se vejde na obrazovku bez posunu, se neposouvá (nahoře zůstane nadpis a hero); dál se drží kotva.
        val fitsAtTop = r.bottom + dpPx(24) <= height
        val delta = if (fitsAtTop) -scrollY else r.top - (height * anchor).toInt() - scrollY
        if (delta != 0) { if (Design.reducedMotion) scrollBy(0, delta) else smoothScrollBy(0, delta) }
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0   // posun řeší requestChildFocus
}

/** Vodorovná police: nadpis + řada karet. Vrací RecyclerView pro napojení adaptéru. */
class Shelf<T>(ctx: Context, title: String, itemWidthDp: Int, itemHeightDp: Int, create: (Context) -> View, bind: (View, T, Int) -> Unit) : LinearLayout(ctx) {
    val adapter = SimpleAdapter(create, bind)
    val list = RecyclerView(ctx)
    val titleView = ctx.label(Typo.TITLE, Design.TEXT, 600).apply { text = title }

    init {
        orientation = VERTICAL
        clipChildren = false; clipToPadding = false
        addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(Sp.XXXL); bottomMargin = ctx.dp(6) })
        list.layoutManager = FocusLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, leadDp = 48)
        list.adapter = adapter
        list.clipChildren = false; list.clipToPadding = false
        list.setPadding(ctx.dp(Sp.XXXL), ctx.dp(14), ctx.dp(Sp.XXXL), ctx.dp(18))
        list.itemAnimator = null
        list.overScrollMode = View.OVER_SCROLL_NEVER
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dp(itemHeightDp + 32)))
    }

    fun submit(items: List<T>) { adapter.submit(items); visibility = if (items.isEmpty()) GONE else VISIBLE }

    fun firstFocusable(): View? = list.findViewHolderForAdapterPosition(0)?.itemView

    /** Fokus na první kartu; když ještě není rozložená, počká na další snímek. */
    fun focusFirst(): Boolean {
        firstFocusable()?.let { return it.requestFocus() }
        if (adapter.itemCount == 0) return false
        list.post { firstFocusable()?.requestFocus() }
        return true
    }
}

// ---------------------------------------------------------------- karty

/** Karta kanálu na Domů: logo, název, běžící pořad a průběh. */
class ChannelCard(ctx: Context) : LinearLayout(ctx) {
    val logo = LogoTile(ctx, 16f)
    val name = ctx.label(Typo.BODY, Design.TEXT, 600)
    val sub = ctx.label(Typo.SMALL, Design.TEXT2, 400)
    val bar = ctx.progressBar(3)
    val live = ctx.liveBadge()
    val fav = ctx.icon(R.drawable.ic_star, 16, Design.accent)

    init {
        orientation = VERTICAL
        setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(Sp.M))
        background = Design.glass(ctx, Design.R_LG)
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(logo, LayoutParams(ctx.dp(84), ctx.dp(52)))
        top.addView(View(ctx), LayoutParams(0, 1, 1f))
        top.addView(fav)
        addView(top)
        addView(name, LayoutParams(-1, -2).apply { topMargin = ctx.dp(Sp.M) })
        addView(sub, LayoutParams(-1, -2).apply { topMargin = ctx.dp(3) })
        addView(bar, LayoutParams(-1, ctx.dp(3)).apply { topMargin = ctx.dp(10) })
        layoutParams = ViewGroup.MarginLayoutParams(ctx.dp(250), ctx.dp(168)).apply { marginEnd = ctx.dp(Sp.L) }
        Design.focusable(this, Design.R_LG)
    }

    fun bind(item: LiveItem, np: Program?) {
        logo.bind(item)
        name.text = item.ch.name
        fav.visibility = if (item.favorite) VISIBLE else INVISIBLE
        sub.text = np?.let { "${hhmm(it.start)}  ${it.title}" } ?: item.src.name
        bar.visibility = if (np == null) INVISIBLE else VISIBLE
        bar.progress = np?.progress() ?: 0
        describe(listOfNotNull(item.ch.name, np?.title ?: item.src.name, if (item.favorite) "oblíbený" else null).joinToString(", "))
    }
}

/** Plakát filmu / seriálu (2:3) s názvem pod ním a případným průběhem. */
class PosterCard(ctx: Context, private val w: Int = 170) : LinearLayout(ctx) {
    val poster = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val frame = FrameLayout(ctx)
    val title = ctx.label(Typo.SMALL, Design.TEXT, 500, lines = 2)
    val meta = ctx.label(Typo.CAPTION, Design.MUTED, 400, mono = true)
    val bar = ctx.progressBar(3)
    val fallback = ctx.label(Typo.BODY, Design.TEXT2, 600, lines = 4).apply { gravity = Gravity.CENTER; setPadding(ctx.dp(10), 0, ctx.dp(10), 0) }

    init {
        orientation = VERTICAL
        frame.background = Design.pill(Design.PLACEHOLDER, Design.R_STD, ctx)
        frame.clipToOutline = true
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, ctx.dpf(Design.R_STD)) }
        }
        frame.addView(fallback, FrameLayout.LayoutParams(-1, -1))
        frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
        frame.addView(bar, FrameLayout.LayoutParams(-1, ctx.dp(3), Gravity.BOTTOM).apply { setMargins(ctx.dp(Sp.S), 0, ctx.dp(Sp.S), ctx.dp(Sp.S)) })
        addView(frame, LayoutParams(ctx.dp(w), ctx.dp(w * 3 / 2)))
        addView(title, LayoutParams(ctx.dp(w), -2).apply { topMargin = ctx.dp(Sp.S) })
        addView(meta, LayoutParams(ctx.dp(w), -2).apply { topMargin = ctx.dp(2) })
        layoutParams = ViewGroup.MarginLayoutParams(-2, -2).apply { marginEnd = ctx.dp(Sp.L); bottomMargin = ctx.dp(6) }
        Design.focusable(this, Design.R_STD)
        // Záře fokusu jen kolem plakátu, ne kolem celé karty i s textem pod ním.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, ctx.dp(w * 3 / 2), ctx.dpf(Design.R_STD)) }
        }
    }

    fun bind(name: String, posterUrl: String?, sub: String?, progress: Float, client: okhttp3.OkHttpClient?) {
        title.text = name
        meta.text = sub ?: ""
        meta.visibility = if (sub.isNullOrBlank()) GONE else VISIBLE
        fallback.text = name
        fallback.visibility = VISIBLE
        Logos.load(posterUrl, poster, client, 360) { fallback.visibility = GONE }
        bar.visibility = if (progress > 0f) VISIBLE else GONE
        bar.progress = (progress * 1000).toInt()
        describe(listOfNotNull(name, sub, if (progress > 0f) "rozkoukáno ${(progress * 100).toInt()} %" else null).joinToString(", "))
    }
}

/** Čtvercová karta rozhlasové stanice. */
class RadioCard(ctx: Context) : LinearLayout(ctx) {
    val logo = LogoTile(ctx, 22f, Design.R_STD)
    val name = ctx.label(Typo.BODY, Design.TEXT, 600, lines = 2).apply { gravity = Gravity.CENTER }
    val sub = ctx.label(Typo.CAPTION, Design.TEXT2, 400).apply { gravity = Gravity.CENTER }
    val fav = ctx.icon(R.drawable.ic_star, 14, Design.accent)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ctx.dp(Sp.M), ctx.dp(14), ctx.dp(Sp.M), ctx.dp(Sp.M))
        background = Design.glass(ctx, Design.R_LG)
        addView(logo, LayoutParams(ctx.dp(112), ctx.dp(112)))
        addView(name, LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        addView(sub, LayoutParams(-1, -2).apply { topMargin = ctx.dp(2) })
        layoutParams = ViewGroup.MarginLayoutParams(ctx.dp(160), ctx.dp(214)).apply { marginEnd = ctx.dp(14); bottomMargin = ctx.dp(Sp.S) }
        Design.focusable(this, Design.R_LG)
    }

    fun bind(item: LiveItem, line: String?) {
        logo.bind(item)
        name.text = item.ch.name
        sub.text = line ?: item.src.name
        fav.visibility = GONE
        describe("${item.ch.name}, ${line ?: item.src.name}")
    }
}

// ---------------------------------------------------------------- řádky seznamů

/**
 * Základ řádků seznamů: [vodítko] (logo / náhled / číslo) | titul, podtitul, průběh | odznak + [koncovka].
 * Varianty (kanál, nahrávka, epizoda, hledání) jen přidají vodítko a plní data; rozložení, fokus, zaoblení,
 * rozestupy a přístupnost (celý řádek se čte jako jedna věta z [setContent]) jsou společné.
 */
open class ListRow(ctx: Context, heightDp: Int = 0, padV: Int = Sp.S) : LinearLayout(ctx) {
    val titleView = ctx.label(Typo.LABEL, Design.TEXT, 600)
    val subView = ctx.label(Typo.SMALL, Design.TEXT2, 400)
    val badgeView = ctx.label(Typo.BADGE, Design.TEXT2, 600, mono = true).apply {
        setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
        background = Design.pill(Design.withAlpha(Design.TEXT2, 0.16f), Design.R_XS, ctx)
    }
    val barView = ctx.progressBar(3)
    private val column = LinearLayout(ctx).apply { orientation = VERTICAL }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(Sp.M), ctx.dp(padV), ctx.dp(Sp.L), ctx.dp(padV))
        background = Design.glass(ctx, Design.R_CTRL)
        column.addView(titleView)
        column.addView(subView, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
        column.addView(barView, LayoutParams(MATCH_PARENT, ctx.dp(3)).apply { topMargin = ctx.dp(6) })
        addView(column, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(Sp.M) })
        addView(badgeView, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ctx.dp(Sp.S) })
        layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, if (heightDp > 0) ctx.dp(heightDp) else WRAP_CONTENT).apply { bottomMargin = ctx.dp(Sp.S) }
        Design.focusable(this, Design.R_CTRL, Design.SCALE_ROW)
    }

    /** Vodítko vlevo (logo, náhled, číslo). Šířka a výška v dp, výška 0 = podle obsahu. */
    fun lead(v: View, widthDp: Int, heightDp: Int = 0) {
        addView(v, 0, LayoutParams(context.dp(widthDp), if (heightDp > 0) context.dp(heightDp) else WRAP_CONTENT))
    }

    /** Koncovka vpravo za odznakem (oblíbené, čas, stav). */
    fun trail(v: View) { addView(v, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(Sp.S) }) }

    /** Naplní řádek. [progress] 0…1 (null = bez lišty). [description] = věta pro čtečku obrazovky. */
    fun setContent(title: String, sub: String?, badge: String? = null, progress: Float? = null, description: String? = null) {
        titleView.text = title
        subView.setTextOrGone(sub)
        badgeView.text = badge ?: ""; badgeView.visibility = if (badge.isNullOrEmpty()) GONE else VISIBLE
        barView.visibility = if (progress == null) GONE else VISIBLE
        barView.progress = ((progress ?: 0f) * 1000).toInt()
        describe(description ?: listOfNotNull(title, sub, badge).joinToString(", "))
    }
}

// ---------------------------------------------------------------- drobnosti

/** Rozloží potomky vodorovně s mezerou. */
fun Context.row(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    views.forEach { addView(it) }
}

fun Context.spacer(weight: Float = 1f): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, weight) }

/** Zachytí klávesu na pohledu; vrací true = zpracováno. */
fun View.onKey(vararg codes: Int, handler: (Int) -> Boolean) {
    setOnKeyListener { _, code, ev -> if (ev.action == KeyEvent.ACTION_DOWN && (codes.isEmpty() || code in codes)) handler(code) else false }
}

/** Dlouhý stisk OK / Enter na pohledu. Krátký stisk zůstává normálním klikem. */
fun View.onLongOk(handler: () -> Unit) {
    isLongClickable = true
    setOnLongClickListener { handler(); true }
}

val Int.ok: Boolean get() = this == KeyEvent.KEYCODE_DPAD_CENTER || this == KeyEvent.KEYCODE_ENTER || this == KeyEvent.KEYCODE_NUMPAD_ENTER

fun Context.dim(color: Int, a: Float) = Design.withAlpha(color, a)

fun Context.transparent() = Color.TRANSPARENT

/** Čas „před 5 min“ / „včera“ pro karty zdrojů. */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "nikdy"
    val m = (now - ms) / 60_000
    return when {
        m < 1 -> "právě teď"
        m < 60 -> "před $m min"
        m < 60 * 24 -> "před ${m / 60} h"
        else -> "před ${m / (60 * 24)} d"
    }
}

fun TextView.setTextOrGone(s: String?) { text = s ?: ""; visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE }
