package cz.plain.tvstream

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.random.Random

/**
 * Obrazovka rádia: vizualizér na pozadí, vlevo obal (nebo logo stanice) uprostřed orbu, vpravo „právě hraje“.
 * Texty ukazují jen to, co stanice skutečně ohlásila (viz [NowPlaying]); jinak „Právě posloucháte“ + název stanice.
 */
class RadioView(ctx: Context) : FrameLayout(ctx) {
    val viz = VisualizerView(ctx)
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(64), ctx.dp(40), ctx.dp(64), ctx.dp(40)) }
    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private val logo = LogoTile(ctx, 44f, 110f, padDp = 36)
    private val artFrame = FrameLayout(ctx)
    private val caption = ctx.caption("PRÁVĚ HRAJE")
    private val title = ctx.label(Typo.DISPLAY, Design.TEXT, 600, lines = 2)
    private val artist = ctx.label(Typo.HEADING, Design.TEXT2, 500)
    private val album = ctx.label(Typo.LABEL, Design.MUTED, 400)
    private val station = ctx.label(Typo.TITLE, Design.TEXT, 500)
    private val program = ctx.label(Typo.BODY, Design.TEXT2, 400)
    private val badges = ctx.label(Typo.SMALL, Design.MUTED, 500, mono = true)
    private val state = ctx.label(Typo.BODY, Design.TEXT2, 500)
    private val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var shownArt: String? = null
    private var lastTrack: String? = null

    init {
        setBackgroundColor(Design.BG)
        addView(viz, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        art.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
        }
        // Bez loga: akcentový přechod místo prázdné šedé desky.
        logo.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Design.withAlpha(Design.accent, 0.38f), Design.PLACEHOLDER)).apply { cornerRadius = ctx.dpf(110f) }
        val size = ctx.dp(220)
        logo.clipToOutline = true
        logo.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) } }
        viz.coreRadius = size / 2f
        artFrame.addView(logo, LayoutParams(size, size, Gravity.CENTER))
        artFrame.addView(art, LayoutParams(size, size, Gravity.CENTER))
        content.addView(artFrame, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.45f))
        viz.centerFx = 0.45f / 2f

        textCol.addView(caption)
        textCol.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
        textCol.addView(artist, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(6) })
        textCol.addView(album, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(Sp.XS) })
        val line = View(ctx).apply { setBackgroundColor(Design.withAlpha(Color.WHITE, 0.12f)) }
        textCol.addView(line, LinearLayout.LayoutParams(ctx.dp(120), ctx.dp(1)).apply { topMargin = ctx.dp(26); bottomMargin = ctx.dp(18) })
        textCol.addView(station)
        textCol.addView(program, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(3) })
        textCol.addView(badges, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(Sp.M) })
        textCol.addView(state, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ctx.dp(Sp.M) })
        content.addView(textCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.55f).apply { marginStart = ctx.dp(Sp.XL) })
        addView(content, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Spektrum a vlna kreslí u spodního okraje, proto se obsah zvedne; u orbu je uprostřed. */
    fun setMode(mode: String) {
        viz.mode = mode
        val bottom = if (mode == "bars" || mode == "wave") dp(190) else dp(40)
        content.setPadding(content.paddingLeft, dp(40), content.paddingRight, bottom)
    }

    private fun dp(v: Int) = context.dp(v)

    fun bindStation(item: PlayItem) {
        logo.bind(item.title, item.logo, Library.client(item.src), 320)
        station.text = item.title
        art.setImageDrawable(null); art.visibility = GONE; shownArt = null
    }

    fun bind(np: NowPlaying) {
        val now = System.currentTimeMillis()
        val trackKey = np.trackTitle + "|" + np.artist
        val show = np.hasTrack && !np.isStale(now)
        if (trackKey != lastTrack && !Design.reducedMotion) { textCol.alpha = 0f; textCol.animate().alpha(1f).setDuration(Design.DURATION).start() }
        lastTrack = trackKey
        caption.text = if (show) "PRÁVĚ HRAJE" else "PRÁVĚ POSLOUCHÁTE"
        if (show) {
            title.text = np.trackTitle
            artist.setTextOrGone(np.artist)
            album.setTextOrGone(np.album)
        } else {
            title.text = np.stationName
            artist.visibility = GONE; album.visibility = GONE
        }
        station.visibility = if (show) VISIBLE else GONE
        station.text = np.stationName
        program.setTextOrGone(np.programTitle?.let { if (show) "Pořad: $it" else it })
        // obal jen když ho zdroj poskytl (nebo ho dohledala volitelná služba); jinak logo stanice
        if (show && np.artworkUrl != null) {
            if (shownArt != np.artworkUrl) {
                shownArt = np.artworkUrl; art.visibility = VISIBLE
                Logos.load(np.artworkUrl, art, null, 480) { }
            }
        } else { art.setImageDrawable(null); art.visibility = GONE; shownArt = null }
        badges.text = when (np.metadataSource) {
            NowPlaying.Origin.ICY -> "metadata: ICY"
            NowPlaying.Origin.ID3 -> "metadata: ID3"
            NowPlaying.Origin.EPG -> "pořad z programu"
            NowPlaying.Origin.CUSTOM -> "metadata: vlastní"
            NowPlaying.Origin.NONE -> ""
        }.let { b -> listOfNotNull(b.ifEmpty { null }, Playback.codec, Playback.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" }).joinToString("  ·  ") }
    }

    fun setState(text: String?, error: Boolean) {
        state.text = text ?: ""
        state.setTextColor(if (error) Design.ERROR else Design.TEXT2)
        state.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    /** Ochrana obrazovky: obsah se jednou za čas jemně posune (jiné body svítí), vizualizér se nehýbe. */
    fun shiftRandom() {
        val d = resources.displayMetrics.density
        val dx = Random.nextInt(-24, 25) * d; val dy = Random.nextInt(-16, 17) * d
        content.animate().translationX(dx).translationY(dy).setDuration(if (Design.reducedMotion) 0 else 1500).start()
    }

    /** Orb sedí přesně za obalem: střed se bere ze skutečné polohy obalu. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (width > 0 && artFrame.width > 0) {
            val cx = (artFrame.left + content.left + artFrame.width / 2f) / width
            if (kotlin.math.abs(cx - viz.centerFx) > 0.002f) viz.centerFx = cx
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); viz.start() }
    override fun onDetachedFromWindow() { viz.stop(); super.onDetachedFromWindow() }
}
