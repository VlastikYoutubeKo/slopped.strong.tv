package cz.plain.tvstream

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Domů: pokračovat ve sledování, naposledy sledované, oblíbené, co právě běží a nejčastěji sledované.
 * Nic se samo nepřeskupuje: police se přepočítají jen při návratu na obrazovku nebo při změně dat.
 */
class HomeScreen(act: MainActivity) : Screen(act) {
    private val scroll = SmoothScrollView(act, 0.3f)
    override val view: View get() = scroll

    private val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, act.dp(Sp.S), 0, act.dp(40)) }
    private val greeting = act.label(Typo.HEADLINE, Design.TEXT, 600)
    private val status = act.label(Typo.SMALL, Design.MUTED, 400, mono = true)
    private val health = act.label(Typo.SMALL, Design.ERROR, 500)
    private val cont = Shelf<UserData.Progress>(act, "Pokračovat ve sledování", 170, 306, { PosterCard(it, 170) }) { v, p, _ ->
        (v as PosterCard).bind(p.title, p.poster, p.sub, p.fraction, Library.sources().firstOrNull { it.id == p.sourceId }?.let { Library.client(it) })
        v.setOnClickListener { VodActions.resume(act, p) }
    }
    private val recent = channelShelf("Naposledy sledované")
    private val favs = channelShelf("Oblíbené kanály")
    private val liveNow = channelShelf("Právě běží")
    private val most = channelShelf("Nejčastěji sledované")
    // „Hero“ nahoře: poslední rozkoukaný film / epizoda, velký plakát vpravo a tlačítko pokračovat.
    private val heroScrim = View(act)
    private val heroImage = android.widget.ImageView(act).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
    private val heroTitle = act.label(Typo.HEADLINE, Design.TEXT, 600, lines = 2)
    private val heroSub = act.label(Typo.BODY, Design.TEXT2, 500, mono = true)
    private val heroBar = act.progressBar(4)
    private lateinit var heroBtn: android.widget.TextView
    private val hero = android.widget.FrameLayout(act)
    private var heroItem: UserData.Progress? = null
    private val empty = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE; setPadding(act.dp(Sp.XXXL), act.dp(60), act.dp(Sp.XXXL), 0) }
    private var queueFor = HashMap<String, List<LiveItem>>()
    private var subscribed = HashSet<EpgStore>()
    private val epgListener: (String?) -> Unit = { refreshNowShelf() }

    init {
        val c = act
        val head = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(Sp.XXXL), c.dp(Sp.S), c.dp(Sp.XXXL), c.dp(Sp.M)) }
        head.addView(greeting)
        val sub = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        sub.addView(status); sub.addView(health, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dp(Sp.L) })
        head.addView(sub, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(6) })
        content.addView(head)
        run {
            hero.background = Design.glass(c, Design.R_LG, solid = true); hero.clipToOutline = true
            hero.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, c.dpf(Design.R_LG)) } }
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(Sp.XXL), c.dp(Sp.XL), c.dp(Sp.XXL), c.dp(Sp.XL)); gravity = Gravity.CENTER_VERTICAL }
            txt.addView(c.caption("POKRAČOVAT VE SLEDOVÁNÍ"))
            txt.addView(heroTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.S) })
            txt.addView(heroSub, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(6) })
            txt.addView(heroBar, LinearLayout.LayoutParams(c.dp(300), c.dp(4)).apply { topMargin = c.dp(14) })
            heroBtn = c.primaryButton("Pokračovat", R.drawable.ic_play) { heroItem?.let { VodActions.resume(act, it) } }
            txt.addView(heroBtn, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(52)).apply { topMargin = c.dp(Sp.L + 2) })
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // Plakát / náhrada vpravo: pevná šířka, výška podle textu vlevo.
            val art = android.widget.FrameLayout(c)
            art.addView(heroImage, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            art.addView(heroScrim.apply { background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Design.SURFACE_SOLID, Design.withAlpha(Design.SURFACE_SOLID, 0.0f))) }, android.widget.FrameLayout.LayoutParams(c.dp(Sp.XXXL * 3), MATCH_PARENT, Gravity.START))
            row.addView(art, LinearLayout.LayoutParams(c.dp(380), MATCH_PARENT))
            hero.addView(row, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            content.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(c.dp(Sp.XXXL), 0, c.dp(Sp.XXXL), c.dp(Sp.M)) })
        }
        listOf(cont, recent, favs, liveNow, most).forEach { content.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        content.addView(empty, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroll.addView(content, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun channelShelf(title: String) = Shelf<LiveItem>(act, title, 250, 168, { ChannelCard(it) }) { v, item, i ->
        val card = v as ChannelCard
        card.bind(item, Library.epgOf(item)?.now(item.ch))
        card.setOnClickListener { Actions.playLive(act, item, queueFor[title].orEmpty().ifEmpty { listOf(item) }) }
        card.onLongOk { Actions.channelMenu(act, item) { refresh() } }
    }

    override fun onShow() { refresh(); subscribe() }
    override fun onHide() { subscribed.forEach { it.listeners -= epgListener }; subscribed.clear() }
    override fun onLibrary() { refresh(); subscribe() }

    private fun subscribe() { for (s in Library.sources()) Library.epg(s)?.let { if (subscribed.add(it)) it.listeners += epgListener } }

    override fun enter(): Boolean {
        if (hero.visibility == View.VISIBLE) return heroBtn.requestFocus()
        val shelf = listOf(cont, recent, favs, liveNow, most).firstOrNull { it.visibility == View.VISIBLE }
        return shelf?.focusFirst() ?: empty.getChildAt(empty.childCount - 1)?.requestFocus() ?: false
    }

    private fun refresh() {
        val c = act
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = when { h in 5..9 -> "Dobré ráno"; h in 10..17 -> "Dobrý den"; h in 18..22 -> "Dobrý večer"; else -> "Dobrou noc" }
        val sources = Library.sources()
        status.text = if (sources.isEmpty()) "žádný zdroj" else "${sources.size} ${if (sources.size == 1) "zdroj" else if (sources.size < 5) "zdroje" else "zdrojů"} · ${fmtCount(Library.totalChannels())} kanálů"
        val bad = sources.filter { Library.state(it.id)?.error != null }
        health.text = if (bad.isEmpty()) "" else "● ${bad.joinToString(", ") { it.name }}: nedostupné"
        health.visibility = if (bad.isEmpty()) View.GONE else View.VISIBLE

        val live = sources.map { it.id }.toSet()   // položky smazaných zdrojů se neukazují
        val conts = UserData.continueWatching().filter { it.sourceId in live }.take(15)
        heroItem = conts.firstOrNull()
        hero.visibility = if (heroItem == null) View.GONE else View.VISIBLE
        heroItem?.let { h ->
            heroTitle.text = h.title; heroSub.text = h.sub ?: ""
            heroBtn.describe("Pokračovat: ${h.title}${h.sub?.let { ", $it" } ?: ""}, rozkoukáno ${(h.fraction * 100).toInt()} %"); heroBar.progress = (h.fraction * 1000).toInt()
            // Nejdřív náhrada (ikona + akcentový přechod); plakát ji nahradí, až se skutečně načte.
            val client = Library.sources().firstOrNull { it.id == h.sourceId }?.let { Library.client(it) }
            if (!h.poster.isNullOrEmpty()) Logos.load(h.poster, heroImage, client, 640) {
                heroImage.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; heroImage.setPadding(0, 0, 0, 0); heroImage.clearColorFilter(); heroImage.background = null; heroScrim.visibility = View.VISIBLE
            } else heroImage.setImageDrawable(null)
            heroScrim.visibility = View.GONE
            heroImage.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE; heroImage.setPadding(c.dp(140), c.dp(50), c.dp(140), c.dp(50))
            heroImage.setImageResource(if (h.key.startsWith("ep:")) R.drawable.ic_series else R.drawable.ic_movie)
            heroImage.setColorFilter(Design.withAlpha(Design.accent, 0.55f))
            heroImage.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Design.withAlpha(Design.accent, 0.0f), Design.withAlpha(Design.accent, 0.22f)))
        }
        cont.submit(conts.drop(if (heroItem != null) 1 else 0))
        val recentItems = UserData.recent().mapNotNull { Library.find(it.key) }.take(20)
        val favItems = UserData.favoriteKeys().filter { !it.startsWith("vod:") && !it.startsWith("ep:") && !it.startsWith("sr:") }.mapNotNull { Library.find(it) }.take(30)
        recent.submit(recentItems); queueFor["Naposledy sledované"] = recentItems
        favs.submit(favItems); queueFor["Oblíbené kanály"] = favItems
        val top = UserData.mostPlayedKeys(12).mapNotNull { Library.find(it) }
        most.submit(top); queueFor["Nejčastěji sledované"] = top
        refreshNowShelf()

        val loading = Library.totalChannels() == 0 && sources.any { s -> Library.state(s.id)?.let { it.phase == Library.Phase.LOADING && it.lineup == null } == true }
        val anything = hero.visibility == View.VISIBLE || listOf(cont, recent, favs, liveNow, most).any { it.visibility == View.VISIBLE }
        empty.removeAllViews()
        empty.visibility = if (anything) View.GONE else View.VISIBLE
        if (!anything) when {
            sources.isEmpty() -> {
                empty.addView(c.icon(R.drawable.ic_phone_w, 56, Design.accent))
                empty.addView(c.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = "Přidej první zdroj" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(18) })
                empty.addView(c.label(Typo.LABEL, Design.TEXT2, 400, lines = 3).apply { text = "Nejpohodlnější je vyplnit údaje v telefonu: naskenuješ QR kód a píšeš na mobilu, ne ovladačem."; gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
                    LinearLayout.LayoutParams(c.dp(560), WRAP_CONTENT).apply { topMargin = c.dp(10) })
                val btns = LinearLayout(c).apply { gravity = Gravity.CENTER; clipChildren = false }
                btns.addView(c.primaryButton("Nastavit z telefonu", R.drawable.ic_phone_w) { c.startActivity(Intent(c, WebSetupActivity::class.java)) }, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(56)))
                btns.addView(c.glassButton("Zadat ovladačem") { c.startActivity(Intent(c, EditSourceActivity::class.java)) }, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(56)).apply { marginStart = c.dp(14) })
                empty.addView(btns, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(26) })
            }
            loading -> empty.addView(c.label(Typo.TITLE, Design.TEXT2, 400).apply { text = "Načítám zdroje…" })
            else -> {
                empty.addView(c.icon(R.drawable.ic_live, 48, Design.accent))
                empty.addView(c.label(Typo.HEADING, Design.TEXT, 600).apply { text = "Začni v sekci Živě" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.L) })
                empty.addView(c.label(Typo.BODY, Design.TEXT2, 400, lines = 3).apply { text = "Tady se objeví, co jsi naposledy sledoval, tvoje oblíbené kanály a co právě běží. Kanál přidáš do oblíbených dlouhým stiskem OK."; gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) },
                    LinearLayout.LayoutParams(c.dp(560), WRAP_CONTENT).apply { topMargin = c.dp(Sp.S) })
                empty.addView(c.primaryButton("Otevřít Živě") { c.select("live", focus = true) }, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(56)).apply { topMargin = c.dp(Sp.XL) })
            }
        }
    }

    /** „Právě běží“: z oblíbených a nedávných kanálů ty, u kterých známe program. */
    private fun refreshNowShelf() {
        val pool = (UserData.favoriteKeys() + UserData.recent().map { it.key }).distinct().filter { !it.startsWith("vod:") && !it.startsWith("ep:") && !it.startsWith("sr:") }
            .mapNotNull { Library.find(it) }
        val running = pool.filter { Library.epgOf(it)?.now(it.ch) != null }.take(20)
        liveNow.submit(running); queueFor["Právě běží"] = running
        // Karty ostatních polic ukazují také program: překreslit bez ztráty pozice.
        listOf(recent, favs, most).forEach { it.adapter.notifyItemRangeChanged(0, it.adapter.itemCount) }
    }
}
