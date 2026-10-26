package cz.plain.tvstream

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/** Nastavení: všechno z jednoho sloupce, hodnoty se mění přes menu nebo přepínač. Změna akcentu aplikaci znovu vykreslí. */
class SettingsScreen(act: MainActivity) : Screen(act) {
    private val scroll = SmoothScrollView(act, 0.3f)
    override val view: View get() = scroll
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(act.dp(Sp.XXXL), act.dp(6), act.dp(Sp.XXXL), act.dp(40)) }
    private var first: View? = null

    init { scroll.addView(col, MATCH_PARENT, WRAP_CONTENT) }

    override fun onShow() = build()
    override fun enter(): Boolean = first?.requestFocus() ?: false

    private class Row(val label: String, val desc: String?, val value: () -> String, val onClick: () -> Unit)

    private fun build() {
        val c = act
        col.removeAllViews(); first = null
        col.addView(c.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = "Nastavení" })

        section("Vzhled")
        row("Barva akcentu", "Zvýrazňuje fokus, tlačítka a vizualizér.", { Design.ACCENTS.getOrElse(Settings.accentIndex) { Design.ACCENTS[0] }.first }) {
            c.showMenu("Barva akcentu", null, Design.ACCENTS.mapIndexed { i, (n, color) ->
                MenuEntry(n, if (i == Settings.accentIndex) R.drawable.ic_check else 0, selected = i == Settings.accentIndex) { Settings.accentIndex = i; Design.apply(c); c.recreate() }
            })
        }
        toggle("Snížený pohyb", "Bez zvětšování při fokusu a bez animací. Vizualizér se zastaví.", { Settings.reducedMotion }) { Settings.reducedMotion = it; Design.apply(c) }
        toggle("Vysoký kontrast", "Silnější obrysy a méně průhledné povrchy.", { Settings.highContrast }) { Settings.highContrast = it; Design.apply(c); c.recreate() }
        toggle("Pozadí s jemnými záblesky", null, { Settings.ambient }) { Settings.ambient = it; Design.apply(c); c.recreate() }

        section("Živé vysílání")
        row("Výchozí zobrazení průvodce", "Kompaktní seznam, nebo časová osa jako v televizi.", { if (Settings.epgMode == "timeline") "Časová osa" else "Kompaktní" }) {
            c.showMenu("Zobrazení průvodce", null, listOf("compact" to "Kompaktní seznam", "timeline" to "Časová osa").map { (id, n) -> MenuEntry(n, if (Settings.epgMode == id) R.drawable.ic_check else 0, selected = Settings.epgMode == id) { Settings.epgMode = id; build() } })
        }
        row("Řazení kanálů", null, { SortMode.of(Settings.sort).label }) {
            c.showMenu("Řazení kanálů", null, SortMode.entries.map { m -> MenuEntry(m.label, if (m.id == Settings.sort) R.drawable.ic_check else 0, selected = m.id == Settings.sort) { Settings.sort = m.id; build() } })
        }

        section("Rádio")
        row("Vizualizace", "Reaguje na skutečný zvuk, pokud ho přehrávač umožňuje.", { vizName(Settings.vizMode) }) {
            c.showMenu("Vizualizace", null, listOf("orb", "bars", "wave", "off").map { id -> MenuEntry(vizName(id), if (Settings.vizMode == id) R.drawable.ic_check else 0, selected = Settings.vizMode == id) { Settings.vizMode = id; build() } })
        }
        row("Kvalita vizualizace", "Automaticky se snižuje, když je box pomalý.", { qualityName(Settings.vizQuality) }) {
            c.showMenu("Kvalita vizualizace", null, listOf("auto", "high", "balanced", "low", "off").map { id -> MenuEntry(qualityName(id), if (Settings.vizQuality == id) R.drawable.ic_check else 0, selected = Settings.vizQuality == id) { Settings.vizQuality = id; build() } })
        }
        row("Snímková frekvence", "Nižší šetří výkon i teplo.", { if (Settings.vizFps == 0) "Automaticky" else "${Settings.vizFps} fps" }) {
            c.showMenu("Snímková frekvence", null, listOf(0, 15, 24, 30, 60).map { f -> MenuEntry(if (f == 0) "Automaticky" else "$f fps", if (Settings.vizFps == f) R.drawable.ic_check else 0, selected = Settings.vizFps == f) { Settings.vizFps = f; build() } })
        }
        toggle("Rozpoznat „jen zvuk“ automaticky", "Stream bez videa se ukáže jako rádio, ne jako černá obrazovka.", { Settings.audioOnlyAuto }) { Settings.audioOnlyAuto = it }
        toggle("Hrát na pozadí", "Rádio hraje dál po odchodu z přehrávače, dokud ho nezastavíš.", { Settings.radioBackground }) { Settings.radioBackground = it }
        toggle("Ztlumit rozhraní při nečinnosti", "Po chvíli se ztlumí jas a obsah se jemně posouvá (ochrana OLED).", { Settings.radioDim }) { Settings.radioDim = it }
        toggle("Dohledávat obaly alb (iTunes)", "Pošle interpreta a název písně službě Apple. Ve výchozím stavu vypnuto.", { Settings.coverLookup }) { Settings.coverLookup = it }

        section("Zdroje")
        row("Správa zdrojů", "Přidání, úprava, pořadí a smazání.", { "${Library.sources().size}" }) { c.select("sources", focus = true) }
        row("Obnovit všechny zdroje", "Znovu načte kanály; program (EPG) se stáhne znovu.", { "" }) { Library.sources().forEach { Library.refresh(it); Library.epgReset(it) }; c.toast("Obnovuji zdroje…") }

        section("Přehrávání")
        row("Poměr stran videa", null, { aspectName(Settings.aspect) }) {
            c.showMenu("Poměr stran", null, listOf("fit", "fill", "zoom").map { id -> MenuEntry(aspectName(id), if (Settings.aspect == id) R.drawable.ic_check else 0, selected = Settings.aspect == id) { Settings.aspect = id; build() } })
        }
        toggle("Diagnostika streamu", "V přehrávači ukáže kodek, datový tok a stav metadat. Bez hesel a přístupových odkazů.", { Settings.diagnostics }) { Settings.diagnostics = it }

        section("Pokročilé")
        row("Obrázky v paměti", "Loga, plakáty a obaly. Stahují se znovu podle potřeby.", { mb(Logos.diskUsage(c)) }) { val n = Logos.clearCache(c); c.toast("Smazáno ${mb(n)}"); build() }
        row("Program (EPG) v paměti", "Stažený program zdrojů.", { mb(c.cacheDir.listFiles { f -> f.name.startsWith("epg-") }?.sumOf { it.length() } ?: 0) }) { val n = Library.clearEpgCaches(); c.toast("Smazáno ${mb(n)}"); build() }
        row("Vymazat historii", "Naposledy sledované, počty zhlédnutí a rozkoukané filmy. Oblíbené zůstanou.", { "" }) { UserData.clearHistory(); c.toast("Historie vymazána"); build() }
        row("O aplikaci", null, { "TV Stream 1.1" }) {}
    }

    private fun section(t: String) {
        col.addView(act.caption(t), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = act.dp(30); bottomMargin = act.dp(Sp.S) })
    }

    private fun toggle(label: String, desc: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        lateinit var v: TextView
        v = row(label, desc, { if (get()) "Zapnuto" else "Vypnuto" }) { set(!get()); v.text = if (get()) "Zapnuto" else "Vypnuto"; v.setTextColor(if (get()) Design.accent else Design.TEXT2) }
        v.setTextColor(if (get()) Design.accent else Design.TEXT2)
    }

    /** Řádek nastavení; vrací pohled s hodnotou (kvůli přepínačům). */
    private fun row(label: String, desc: String?, value: () -> String, onClick: () -> Unit): TextView {
        val c = act
        val valueView = c.label(Typo.BODY, Design.TEXT2, 500, mono = false).apply { text = value() }
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(22), c.dp(14), c.dp(22), c.dp(14)); background = Design.glass(c, Design.R_CTRL)
            val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            left.addView(c.label(Typo.LABEL, Design.TEXT, 500).apply { text = label })
            desc?.let { left.addView(c.label(Typo.SMALL, Design.MUTED, 400, lines = 2).apply { text = it }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(3) }) }
            addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(valueView)
            Design.focusable(this, Design.R_CTRL, Design.SCALE_ROW)
            setOnClickListener { onClick() }
        }
        col.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = c.dp(Sp.S) })
        if (first == null) first = r
        return valueView
    }

    private fun vizName(id: String) = when (id) { "orb" -> "Zářící koule"; "bars" -> "Spektrum"; "wave" -> "Vlna"; else -> "Vypnuto" }
    private fun qualityName(id: String) = when (id) { "high" -> "Vysoká"; "balanced" -> "Vyvážená"; "low" -> "Nízká"; "off" -> "Vypnuto"; else -> "Automaticky" }
    private fun aspectName(id: String) = when (id) { "fill" -> "Vyplnit"; "zoom" -> "Přiblížit"; else -> "Přizpůsobit" }
    private fun mb(b: Long) = if (b < 1024 * 1024) "${b / 1024} kB" else "%.1f MB".format(b / 1048576.0)
}
