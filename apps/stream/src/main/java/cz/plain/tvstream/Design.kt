package cz.plain.tvstream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Centrální design systém TV Stream (viz docs/DESIGN_SYSTEM.md).
 * Tokeny, písma, pohyb a skleněné povrchy jsou tady; obrazovky nepoužívají vlastní barvy.
 */
object Design {
    // ---------------------------------------------------------------- tokeny
    val BG = 0xFF0B0D14.toInt()
    val ELEVATED = 0xFF101420.toInt()
    val SURFACE = argb(0.16f, 22, 26, 38)
    /** Nepropustný povrch pro velké textové plochy (průhlednost zhoršuje čitelnost). */
    val SURFACE_SOLID = 0xFF141826.toInt()
    val FOCUS_SURFACE = argb(0.60f, 38, 44, 64)
    val BLUE = 0xFF3B82F6.toInt()
    val LIVE = 0xFFEF4444.toInt()
    val OK = 0xFF22C55E.toInt()
    val ERROR = 0xFFF87171.toInt()
    val TEXT = 0xFFF8FAFC.toInt()
    val TEXT2 = 0xFF94A3B8.toInt()
    /** Tlumený text: kontrast ≥ 4,4:1 na všech površích (dřívější #64748B měl 3,2–4,1:1). */
    val MUTED = 0xFF8394AA.toInt()
    /** Podklad pro chybějící logo / plakát. */
    val PLACEHOLDER = 0xFF1B2030.toInt()
    val WARNING = 0xFFE9B44C.toInt()
    val HAIRLINE = argb(0.09f, 255, 255, 255)

    /** Nabídka akcentů v Nastavení. */
    val ACCENTS: List<Pair<String, Int>> = listOf(
        "Fialová" to 0xFF8B5CF6.toInt(),
        "Modrá" to 0xFF3B82F6.toInt(),
        "Azurová" to 0xFF06B6D4.toInt(),
        "Zelená" to 0xFF22C55E.toInt(),
        "Oranžová" to 0xFFF59E0B.toInt(),
        "Růžová" to 0xFFEC4899.toInt(),
    )

    var accent = ACCENTS[0].second
        private set
    var reducedMotion = false
        private set
    var highContrast = false
        private set
    var ambient = true
        private set

    const val R_XS = 6f     // odznaky, malé pilulky
    const val R_CTRL = 14f  // ovladatelné prvky: tlačítka, čipy, karty horní lišty, řádky, položky menu
    const val R_STD = 16f   // dp
    const val R_LG = 22f
    const val R_SM = 10f
    /** Zvětšení při fokusu podle typu prvku (viz docs/DESIGN_SYSTEM.md). */
    const val SCALE_FIELD = 1.01f  // textová pole, velké plochy
    const val SCALE_ROW = 1.02f    // řádky seznamů, položky menu
    const val SCALE_CHIP = 1.03f   // čipy, segmenty, malá tlačítka
    const val SCALE_CARD = 1.04f   // karty, plakáty, karty horní lišty, tlačítka
    const val FOCUS_SCALE = SCALE_CARD
    const val DURATION = 250L
    val EASE = PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f)

    /** Přečte uživatelská nastavení (akcent, snížený pohyb…). */
    fun apply(ctx: Context) {
        accent = ACCENTS.getOrElse(Settings.accentIndex) { ACCENTS[0] }.second
        reducedMotion = Settings.reducedMotion
        highContrast = Settings.highContrast
        ambient = Settings.ambient
    }

    fun argb(a: Float, r: Int, g: Int, b: Int) = Color.argb((a * 255).toInt().coerceIn(0, 255), r, g, b)
    fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
    fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(s: Int, e: Int) = (s + (e - s) * t).toInt().coerceIn(0, 255)
        return Color.argb(ch(Color.alpha(from), Color.alpha(to)), ch(Color.red(from), Color.red(to)), ch(Color.green(from), Color.green(to)), ch(Color.blue(from), Color.blue(to)))
    }

    // ---------------------------------------------------------------- písma
    private val faces = HashMap<String, Typeface>()

    /** Outfit (proměnné písmo): tloušťka 300–700. */
    fun ui(ctx: Context, weight: Int = 400): Typeface = faces.getOrPut("u$weight") { build(ctx, "fonts/outfit.ttf", weight) }

    /** JetBrains Mono – jen pro adresy, počty, časy a diagnostiku. */
    fun mono(ctx: Context, weight: Int = 400): Typeface = faces.getOrPut("m$weight") { build(ctx, "fonts/jetbrains_mono.ttf", weight) }

    private fun build(ctx: Context, path: String, weight: Int): Typeface = try {
        Typeface.Builder(ctx.assets, path).setFontVariationSettings("'wght' $weight").build() ?: Typeface.DEFAULT
    } catch (_: Exception) { Typeface.DEFAULT }

    // ---------------------------------------------------------------- povrchy
    fun glass(ctx: Context, radiusDp: Float = R_STD, solid: Boolean = false) = GlassDrawable(ctx, radiusDp, solid)

    fun pill(color: Int, radiusDp: Float = 8f, ctx: Context? = null): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = (ctx?.dp(radiusDp.toInt()) ?: radiusDp.toInt()).toFloat() }

    /** Tenká lišta průběhu (pořad, film…). */
    fun progress(ctx: Context): LayerDrawable {
        val r = ctx.dp(2).toFloat()
        val track = GradientDrawable().apply { setColor(withAlpha(Color.WHITE, 0.14f)); cornerRadius = r }
        val bar = GradientDrawable().apply { setColor(accent); cornerRadius = r }
        return LayerDrawable(arrayOf(track, ClipDrawable(bar, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
    }

    // ---------------------------------------------------------------- fokus a pohyb
    /**
     * Udělá z pohledu ovladatelný prvek: fokus = zvětšení, akcentový stín (záře), případně vlastní reakce.
     * Rodiče musí mít vypnuté ořezávání ([unclip]), jinak by zvětšení sousedy zakrývalo.
     */
    fun focusable(v: View, radiusDp: Float = R_STD, scale: Float = FOCUS_SCALE, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true
        v.isClickable = true
        val r = v.context.dp(radiusDp.toInt()).toFloat()
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, r) }
        }
        v.clipToOutline = false
        v.setOnFocusChangeListener { view, focused ->
            val s = if (focused && !reducedMotion) scale else 1f
            view.animate().cancel()
            if (reducedMotion) { view.scaleX = s; view.scaleY = s }
            else view.animate().scaleX(s).scaleY(s).setDuration(DURATION).setInterpolator(EASE).start()
            view.translationZ = if (focused) view.context.dp(14).toFloat() else 0f
            if (focused) {
                view.outlineSpotShadowColor = withAlpha(accent, 0.85f)
                view.outlineAmbientShadowColor = withAlpha(accent, 0.45f)
            }
            onFocus?.invoke(focused)
        }
    }

    /** Vypne ořezávání potomků u rodičů (zvětšený a zářící prvek se nesmí ořezat). */
    fun unclip(v: View, levels: Int = 3) {
        var p: View? = v
        var n = 0
        while (p != null && n++ <= levels) {
            (p as? ViewGroup)?.let { it.clipChildren = false; it.clipToPadding = false }
            p = p.parent as? View
        }
    }
}

/** Velikost rozhraní: obalí kontext se změněnou hustotou, takže dp i sp se zmenší (nebo zvětší) všude stejně. */
object UiScale {
    fun wrap(base: Context): Context {
        val s = try { base.getSharedPreferences("settings", Context.MODE_PRIVATE).getFloat("uiScale", Settings.DEFAULT_SCALE) } catch (_: Exception) { Settings.DEFAULT_SCALE }
        if (s == 1f) return base
        val c = android.content.res.Configuration(base.resources.configuration)
        c.densityDpi = (c.densityDpi * s).toInt().coerceAtLeast(120)
        return base.createConfigurationContext(c)
    }
}

/**
 * Typografická škála (sp). Výjimky: texty vykreslené přímo na Canvas v časové ose EPG a monogramy log
 * (velikost se odvozuje od velikosti dlaždice).
 */
object Typo {
    const val BADGE = 11.5f     // odznaky kvality, ŽIVĚ
    const val CAPTION = 12.5f   // nadpisy sekcí verzálkami, nápověda
    const val SMALL = 14f       // metadata, popisky, časy
    const val BODY = 16f        // běžný text, popisy
    const val LABEL = 18f       // názvy v řádcích, tlačítka, menu
    const val TITLE = 22f       // nadpisy polic, karty
    const val HEADING = 28f     // nadpisy dialogů a detailů
    const val HEADLINE = 32f    // nadpisy obrazovek
    const val DISPLAY = 40f     // titul filmu, velký text přehrávače
}

/** Rozestupy (dp). Jiné hodnoty jen pro vnitřní rozměry komponent (viz docs/DESIGN_SYSTEM.md). */
object Sp {
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
    const val XXL = 32
    const val XXXL = 48   // okraj obrazovky
}

/**
 * Skleněný povrch: průhledná výplň + jemný odlesk + tenký obrys. Bez rozmazání (drahé na slabém GPU).
 * Stavy: focused = světlejší povrch + akcentový rámeček; activated/selected = vybraná položka (jiný pojem než fokus).
 */
class GlassDrawable(ctx: Context, radiusDp: Float, private val solid: Boolean) : Drawable() {
    private val density = ctx.resources.displayMetrics.density
    private val r = radiusDp * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var focused = false
    private var active = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        var f = false
        var a = false
        for (s in state) when (s) {
            android.R.attr.state_focused -> f = true
            android.R.attr.state_activated, android.R.attr.state_selected -> a = true
        }
        if (f == focused && a == active) return false
        focused = f; active = a
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        gloss.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), Design.withAlpha(Color.WHITE, 0.06f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val hc = Design.highContrast
        val accent = Design.accent
        val base = if (solid) Design.SURFACE_SOLID else if (hc) Design.argb(0.70f, 22, 26, 38) else Design.SURFACE
        fill.color = when {
            // Nepropustná plocha: stín fokusu (záře) jinak prosvítá průhlednou výplní a kreslí uvnitř prvku tmavý pruh.
            focused -> Design.blend(0xFF1E2438.toInt(), accent, 0.14f)
            active -> Design.blend(base, accent, 0.20f)
            else -> base
        }
        // Minimalisticky: v klidu bez obrysu, rámeček jen u zaostřeného prvku (a ve vysokém kontrastu).
        val stroke = when { focused -> 2f * density; hc -> density; else -> 0f }
        line.strokeWidth = stroke
        line.color = when {
            focused -> accent
            hc -> Design.withAlpha(Color.WHITE, 0.35f)
            else -> Color.TRANSPARENT
        }
        val half = stroke / 2f
        rect.set(bounds.left + half, bounds.top + half, bounds.right - half, bounds.bottom - half)
        c.drawRoundRect(rect, r, r, fill)
        if (stroke > 0f) c.drawRoundRect(rect, r, r, line)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Pozadí obrazovek: tmavý podklad se dvěma velmi jemnými záblesky (akcent vlevo nahoře, modrá vpravo dole). */
class AmbientBackground : Drawable() {
    private val p = Paint()
    private var a: RadialGradient? = null
    private var b: RadialGradient? = null

    override fun onBoundsChange(r: android.graphics.Rect) {
        val w = r.width().toFloat()
        val h = r.height().toFloat()
        a = RadialGradient(w * 0.12f, -h * 0.05f, w * 0.65f, Design.withAlpha(Design.accent, 0.16f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        b = RadialGradient(w * 0.95f, h * 1.05f, w * 0.55f, Design.withAlpha(Design.BLUE, 0.09f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        c.drawColor(Design.BG)
        if (!Design.ambient) return
        p.shader = a; c.drawRect(bounds, p)
        p.shader = b; c.drawRect(bounds, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

// ---------------------------------------------------------------- pomocné komponenty

fun Context.dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** Text podle typografické škály. [weight] 300–700, [mono] jen pro adresy, počty, časy a diagnostiku. */
fun Context.label(size: Float, color: Int = Design.TEXT, weight: Int = 400, mono: Boolean = false, lines: Int = 1): TextView = TextView(this).apply {
    textSize = size
    setTextColor(color)
    typeface = if (mono) Design.mono(this@label, weight) else Design.ui(this@label, weight)
    includeFontPadding = false
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

/** Malý nadpis sekce verzálkami s prostrkáním. */
fun Context.caption(text: String): TextView = label(Typo.CAPTION, Design.MUTED, 600).apply {
    isAccessibilityHeading = true
    this.text = text.uppercase()
    letterSpacing = 0.14f
}

// ---------------------------------------------------------------- přístupnost (TalkBack)

/** Popis pro čtečku obrazovky + stav „vybráno“. Prvek s popisem se čte jako celek, potomci se nečtou zvlášť. */
fun View.describe(text: String?, selected: Boolean = false) {
    contentDescription = text
    if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = if (selected) "vybráno" else null
}

/** Ozdobný prvek (ikona, logo): čtečka ho přeskočí, protože stejnou informaci nese text vedle. */
fun View.decorative() { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

/** Hlášení změny textu (načítání, chyba) bez přesunu fokusu. */
fun View.polite() { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }

fun Context.icon(res: Int, sizeDp: Int = 24, tint: Int = Design.TEXT): ImageView = ImageView(this).apply {
    decorative()
    setImageResource(res)
    setColorFilter(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun ImageView.tint(color: Int) = apply { setColorFilter(color) }

/** Hlavní akce: plná akcentová barva, fokus = zvětšení + záře. */
fun Context.primaryButton(text: String, icon: Int = 0, onClick: () -> Unit): TextView = label(Typo.LABEL, Color.WHITE, 600).apply {
    this.text = text
    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
    setPadding(dp(28), 0, dp(28), 0)
    val shape = GradientDrawable().apply { setColor(Design.accent); cornerRadius = dpf(Design.R_STD) }
    background = object : LayerDrawable(arrayOf<Drawable>(shape)) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dpf(2f); color = Design.TEXT }
        private var f = false
        override fun isStateful() = true
        override fun onStateChange(s: IntArray): Boolean { val n = s.contains(android.R.attr.state_focused); if (n != f) { f = n; invalidateSelf() }; return super.onStateChange(s) }
        override fun draw(c: Canvas) {
            super.draw(c)
            if (f) c.drawRoundRect(RectF(bounds).apply { inset(dpf(1f), dpf(1f)) }, dpf(Design.R_STD), dpf(Design.R_STD), ring)
        }
    }
    if (icon != 0) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(10); compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
    Design.focusable(this)
    setOnClickListener { onClick() }
}

/** Sekundární tlačítko ve skleněném provedení. */
fun Context.glassButton(text: String, icon: Int = 0, tint: Int = Design.TEXT, onClick: () -> Unit): TextView = label(Typo.LABEL, tint, 500).apply {
    this.text = text
    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
    setPadding(dp(22), 0, dp(22), 0)
    background = Design.glass(this@glassButton)
    if (icon != 0) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(10); compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tint) }
    Design.focusable(this)
    setOnClickListener { onClick() }
}

/**
 * Nápověda ovládání: tenký obrys s popiskem + tlumený text, žádné plné „pilulky“.
 * [key] je krátký text („OK“, „▲▼“, „◀“…).
 */
fun Context.hint(key: String, caption: String): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(label(Typo.BADGE, Design.MUTED, 500, mono = true).apply {
        text = key
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(3), dp(7), dp(3))
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), Design.withAlpha(Design.MUTED, 0.6f)); cornerRadius = dpf(6f) }
    })
    addView(label(Typo.SMALL, Design.MUTED, 400).apply { text = caption; setPadding(dp(8), 0, dp(20), 0) })
}

/** Lišta nápovědy do patičky obrazovky. */
fun Context.hintBar(vararg pairs: Pair<String, String>): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    pairs.forEach { addView(hint(it.first, it.second)) }
}

fun Context.progressBar(heightDp: Int = 3): android.widget.ProgressBar =
    android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = Design.progress(this@progressBar)
        minimumHeight = dp(heightDp)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

fun Context.liveBadge(): TextView = label(Typo.BADGE, Color.WHITE, 700).apply {
    text = "ŽIVĚ"
    letterSpacing = 0.12f
    background = Design.pill(Design.LIVE, 6f, this@liveBadge)
    setPadding(dp(8), dp(3), dp(8), dp(3))
}

fun hhmm(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

fun fmtCount(n: Int): String = "%,d".format(n).replace(',', ' ')
