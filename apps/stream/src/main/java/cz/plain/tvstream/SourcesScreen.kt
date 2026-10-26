package cz.plain.tvstream

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Zdroje: vodorovná řada skleněných karet (typ, server, stav, počty, poslední synchronizace) + karta „Přidat“.
 * OK otevře zdroj, dlouhé OK nabídne správu (otevřít, upravit, test, obnovit, pořadí, informace, smazat).
 * Přihlašovací údaje se nikde neukazují.
 */
class SourcesScreen(act: MainActivity) : Screen(act) {
    private val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    override val view: View get() = box
    private val list = RecyclerView(act)
    private val adapter = SimpleAdapter<Source?>({ c -> SourceCard(c) }) { v, s, _ -> (v as SourceCard).bind(s) }
    private val worker = Executors.newSingleThreadExecutor()
    private val summary = act.label(Typo.SMALL, Design.MUTED, 400, mono = true)

    init {
        val c = act
        val head = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(Sp.XXXL), c.dp(6), c.dp(Sp.XXXL), c.dp(14)) }
        head.addView(c.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = "Zdroje" })
        head.addView(summary, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = c.dp(6) })
        box.addView(head)
        list.layoutManager = FocusLayoutManager(c, LinearLayoutManager.HORIZONTAL, leadDp = 48)
        list.adapter = adapter; list.itemAnimator = null; list.overScrollMode = View.OVER_SCROLL_NEVER
        list.clipChildren = false; list.clipToPadding = false; list.setPadding(c.dp(Sp.XXXL), c.dp(Sp.L), c.dp(Sp.XXXL), c.dp(Sp.L))
        box.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, c.dp(290)))
        box.addView(c.label(Typo.SMALL, Design.MUTED, 400, lines = 3).apply {
            text = "Heslo se ukládá šifrovaně (Android Keystore). Zdroj zadaný jako http:// přenáší data nešifrovaně. U zdroje je to označeno."
            setPadding(c.dp(Sp.XXXL), c.dp(10), c.dp(Sp.XXXL), 0); setLineSpacing(0f, 1.2f)
        })
        box.addView(c.spacer())
    }

    override fun onShow() = reload()
    override fun onLibrary() = reload()

    private fun reload() {
        val src = Library.sources()
        adapter.submit(src + listOf<Source?>(null))
        summary.text = "${src.size} ${if (src.size == 1) "zdroj" else if (src.size in 2..4) "zdroje" else "zdrojů"}"
    }

    override fun enter(): Boolean = list.focusItem(0)

    // ---------------------------------------------------------------- karta

    private inner class SourceCard(val c: android.content.Context) : LinearLayout(c) {
        private val type = c.label(Typo.BADGE, Design.accent, 700).apply { letterSpacing = 0.12f }
        private val lock = c.label(Typo.BADGE, Design.MUTED, 600, mono = true)
        private val name = c.label(Typo.HEADING, Design.TEXT, 600, lines = 2)
        private val host = c.label(Typo.SMALL, Design.TEXT2, 400, mono = true)
        private val dot = View(c)
        private val status = c.label(Typo.SMALL, Design.TEXT2, 500, lines = 2)
        private val counts = c.label(Typo.SMALL, Design.TEXT2, 400, lines = 2).apply { setLineSpacing(0f, 1.15f) }
        private val sync = c.label(Typo.CAPTION, Design.MUTED, 400)
        private val icon = c.icon(R.drawable.ic_source, 26, Design.accent)
        private val bar = c.progressBar(3)
        private var src: Source? = null

        init {
            orientation = VERTICAL
            setPadding(c.dp(Sp.XL), c.dp(22), c.dp(Sp.XL), c.dp(20))
            background = Design.glass(c, Design.R_LG)
            val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(icon, LayoutParams(c.dp(26), c.dp(26)))
            top.addView(type, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dp(10) })
            top.addView(c.spacer()); top.addView(lock)
            addView(top)
            addView(name, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.L) })
            addView(host, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(3) })
            val st = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
            st.addView(dot, LayoutParams(c.dp(9), c.dp(9)).apply { marginEnd = c.dp(9) }); st.addView(status, LayoutParams(0, WRAP_CONTENT, 1f))
            addView(st, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.L) })
            addView(counts, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = c.dp(Sp.S) })
            addView(c.spacer().also { it.layoutParams = LayoutParams(1, 0, 1f) })
            addView(bar, LayoutParams(MATCH_PARENT, c.dp(3)).apply { bottomMargin = c.dp(Sp.S) })
            addView(sync)
            layoutParams = ViewGroup.MarginLayoutParams(c.dp(290), c.dp(240)).apply { marginEnd = c.dp(18) }
            Design.focusable(this, Design.R_LG)
            setOnClickListener { src?.let { open(it) } ?: addMenu() }
            onLongOk { src?.let { manage(it) } ?: addMenu() }
        }

        fun bind(s: Source?) {
            src = s
            val add = s == null
            icon.setImageResource(if (add) R.drawable.ic_add else R.drawable.ic_source)
            if (add) {
                type.text = "NOVÝ ZDROJ"; lock.text = ""; name.text = "Přidat zdroj"; host.text = "Xtream Codes · TVHeadend · Stalker"
                dot.visibility = GONE; status.text = "Z telefonu (QR) nebo ovladačem"; counts.text = ""; sync.text = ""; bar.visibility = GONE
                status.setTextColor(Design.TEXT2); name.setTextColor(Design.TEXT)
                return
            }
            s!!
            val st = Library.state(s.id); val stat = Library.stats.get(s.id)
            type.text = s.type.label.uppercase()
            lock.text = if (s.secure) "HTTPS" else "HTTP"
            lock.setTextColor(if (s.secure) Design.OK else Design.MUTED)
            name.text = s.name; name.setTextColor(Design.TEXT)
            host.text = s.host
            dot.visibility = VISIBLE
            val (color, text) = when {
                st?.phase == Library.Phase.LOADING -> Design.accent to (st.note.ifBlank { "Načítám…" })
                st?.error != null -> Design.ERROR to st.error!!
                st?.phase == Library.Phase.READY -> Design.OK to (if (st.fromCache) "Z paměti" else "Připojeno")
                stat?.ok == false -> Design.ERROR to (stat.message ?: "Nedostupné")
                else -> Design.MUTED to "Zatím nenačteno"
            }
            dot.background = Design.pill(color, 5f, c); status.text = text; status.setTextColor(if (color == Design.ERROR) Design.ERROR else Design.TEXT2)
            val ch = st?.lineup?.channels ?: emptyList()
            val parts = ArrayList<String>()
            if (stat != null || ch.isNotEmpty()) {
                val tv = ch.count { it.kind == ChannelKind.TV }; val radio = ch.count { it.kind == ChannelKind.RADIO }
                parts += "${fmtCount(if (ch.isNotEmpty()) tv else (stat?.channels ?: 0) - (stat?.radio ?: 0))} kanálů"
                (if (ch.isNotEmpty()) radio else stat?.radio ?: 0).takeIf { it > 0 }?.let { parts += "${fmtCount(it)} rádií" }
                stat?.movies?.takeIf { it >= 0 }?.let { parts += "${fmtCount(it)} filmů" }
                stat?.series?.takeIf { it >= 0 }?.let { parts += "${fmtCount(it)} seriálů" }
            }
            counts.text = parts.joinToString("  ·  ")
            sync.text = "Synchronizace: ${ago(st?.syncedAt?.takeIf { it > 0 } ?: stat?.lastSync ?: 0)}"
            bar.visibility = if (st?.phase == Library.Phase.LOADING) VISIBLE else GONE
            bar.progress = 500
            describe("${s.name}, ${s.type.label}, ${if (s.secure) "HTTPS" else "HTTP"}, $text, $counts")
        }
    }

    // ---------------------------------------------------------------- akce

    private fun open(s: Source) { act.openLive(s.id) }

    private fun addMenu() = act.showMenu("Přidat zdroj", null, listOf(
        MenuEntry("Z telefonu (QR kód)", R.drawable.ic_phone_w, "doporučeno") { act.startActivity(Intent(act, WebSetupActivity::class.java)) },
        MenuEntry("Ovladačem", R.drawable.ic_edit) { act.startActivity(Intent(act, EditSourceActivity::class.java)) },
    ))

    private fun manage(s: Source) {
        val idx = Library.sources().indexOfFirst { it.id == s.id }
        val n = Library.sources().size
        val e = ArrayList<MenuEntry>()
        e += MenuEntry("Otevřít", R.drawable.ic_play) { open(s) }
        e += MenuEntry("Upravit", R.drawable.ic_edit) { act.startActivity(Intent(act, EditSourceActivity::class.java).putExtra("id", s.id)) }
        e += MenuEntry("Otestovat připojení", R.drawable.ic_wifi) { test(s) }
        e += MenuEntry("Obnovit kanály", R.drawable.ic_refresh) { Library.refresh(s); Library.epgReset(s); act.toast("Obnovuji ${s.name}…") }
        if (idx > 0) e += MenuEntry("Posunout doleva", R.drawable.ic_back) { Library.store.move(s.id, -1); Library.listeners.forEach { it() } }
        if (idx in 0 until n - 1) e += MenuEntry("Posunout doprava", R.drawable.ic_swap) { Library.store.move(s.id, +1); Library.listeners.forEach { it() } }
        e += MenuEntry("Informace o zdroji", R.drawable.ic_info) { info(s) }
        e += MenuEntry("Smazat", R.drawable.ic_delete, danger = true) { confirmDelete(s) }
        act.showMenu(s.name, s.host, e)
    }

    private fun test(s: Source) {
        act.toast("Zkouším ${s.name}…")
        worker.execute {
            val msg = try { "OK · " + Library.backend(s).ping() } catch (e: Exception) { SourceForm.errorText(e) }
            act.runOnUiThread { act.showMenu("Test připojení", s.name, listOf(MenuEntry(msg, if (msg.startsWith("OK")) R.drawable.ic_check else R.drawable.ic_error, danger = !msg.startsWith("OK")) {})) }
        }
    }

    private fun info(s: Source) {
        val st = Library.state(s.id); val stat = Library.stats.get(s.id)
        val ch = st?.lineup?.channels
        fun yn(b: Boolean?) = when (b) { true -> "ano"; false -> "ne"; null -> "neznámé" }
        val caps = stat?.caps
        fun line(k: String, v: String) = MenuEntry(k, 0, v) {}
        act.showMenu("Informace o zdroji", s.name, listOf(
            line("Typ", s.type.label), line("Server", s.host), line("Spojení", if (s.secure) "HTTPS (šifrované)" else "HTTP (nešifrované)"),
            line("Poslední synchronizace", ago(st?.syncedAt?.takeIf { it > 0 } ?: stat?.lastSync ?: 0)),
            line("Kanály", ch?.let { fmtCount(it.size) } ?: "–"), line("Rádio", ch?.let { fmtCount(it.count { c -> c.kind == ChannelKind.RADIO }) } ?: "–"),
            line("EPG", yn(caps?.epg)), line("Filmy", yn(caps?.vod)), line("Seriály", yn(caps?.series)),
            line("Stav", st?.error?.let { Redact.url(it) } ?: if (st?.phase == Library.Phase.READY) "v pořádku" else "–"),
        ))
    }

    private fun confirmDelete(s: Source) = act.showMenu("Smazat zdroj „${s.name}“?", "Smaže se i uložená paměť kanálů a programu.", listOf(
        MenuEntry("Ano, smazat", R.drawable.ic_delete, danger = true) { Library.store.delete(s.id); Library.forget(s.id, true); act.toast("Zdroj smazán"); reload() },
        MenuEntry("Ne", R.drawable.ic_close) {},
    ))
}
