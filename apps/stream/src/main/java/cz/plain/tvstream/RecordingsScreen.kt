package cz.plain.tvstream

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nahrávka + zdroj, ze kterého pochází. */
class RecEntry(val src: Source, val rec: Recording) {
    override fun equals(other: Any?) = other is RecEntry && other.rec.id == rec.id && other.src.id == src.id && other.rec.status == rec.status
    override fun hashCode() = rec.id.hashCode()
}

/**
 * Nahrávky z TVHeadendu (DVR): seznam podle data, nebo časová osa po kanálech (stejné ovládání jako průvodce).
 * Přehrává se přes /dvrfile/{uuid}; pozice se pamatuje a nahrávka se objeví v „Pokračovat ve sledování“.
 */
class RecordingsScreen(act: MainActivity) : Screen(act) {
    private val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root
    private val count = act.label(Typo.SMALL, Design.MUTED, 400, mono = true)
    private val status = act.label(Typo.LABEL, Design.TEXT2, 400, lines = 3).apply { gravity = Gravity.CENTER; visibility = View.GONE; polite() }
    private val list = RecyclerView(act)
    private val adapter: SimpleAdapter<RecEntry> = SimpleAdapter({ RecRow(it) }) { v, e, _ ->
        (v as RecRow).bind(e)
        v.setOnClickListener { play(e) }
    }
    private val modeChip: TextView
    private val sourceChip: TextView
    private val timeline: TimelineView
    private val listFrame: android.widget.FrameLayout
    private var mode = "list"
    private var entries: List<RecEntry> = emptyList()
    private var pickedSource: String? = null
    private var loading = false
    private val byChannel = HashMap<String, List<Pair<Program, RecEntry>>>()

    init {
        val c = act
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(Sp.XXXL), c.dp(6), c.dp(Sp.XXXL), c.dp(Sp.M)) }
        head.addView(c.label(Typo.HEADLINE, Design.TEXT, 600).apply { text = "Nahrávky" })
        head.addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = c.dp(Sp.L) })
        sourceChip = c.chip("", R.drawable.ic_source) { pickSource() }
        head.addView(sourceChip, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(42)).apply { marginStart = c.dp(20) })
        head.addView(c.spacer())
        modeChip = c.chip("Seznam", R.drawable.ic_list) { toggleMode() }
        head.addView(modeChip, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(42)))
        head.addView(c.chip("Obnovit", R.drawable.ic_refresh) { load() }, LinearLayout.LayoutParams(WRAP_CONTENT, c.dp(42)).apply { marginStart = c.dp(10) })
        root.addView(head)

        list.layoutManager = FocusLayoutManager(c, LinearLayoutManager.VERTICAL, 0.35f)
        list.adapter = adapter; list.itemAnimator = null; list.clipToPadding = false; list.clipChildren = false
        list.setPadding(c.dp(Sp.XXXL), c.dp(6), c.dp(Sp.XXXL), c.dp(40))
        listFrame = android.widget.FrameLayout(c).apply { clipChildren = true }
        listFrame.addView(list, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        listFrame.addView(status, android.widget.FrameLayout.LayoutParams(c.dp(560), WRAP_CONTENT, Gravity.CENTER))
        root.addView(listFrame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        timeline = TimelineView(act,
            onPlay = { item, p -> entryFor(item, p)?.let { play(it) } ?: act.toast("V tomto čase není nahrávka") },
            onMenu = { item -> entryFor(item, null)?.let { play(it) } },
            programsOf = { item -> byChannel[item.key].orEmpty().map { it.first } })
        timeline.view.visibility = View.GONE
        root.addView(timeline.view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun tvhSources() = Library.sources().filter { it.type == SourceType.TVHEADEND }
    private fun active(): Source? = tvhSources().let { all -> all.firstOrNull { it.id == pickedSource } ?: all.firstOrNull() }

    override fun onShow() { if (entries.isEmpty() && !loading) load() else adapter.notifyItemRangeChanged(0, adapter.itemCount) }
    override fun onLibrary() { sourceChip.visibility = if (tvhSources().size > 1) View.VISIBLE else View.GONE }
    override fun enter(): Boolean = if (mode == "timeline") timeline.focusFirst(null) else list.focusItem(0)

    private fun pickSource() = act.showMenu("Zdroj", null, tvhSources().map { s ->
        MenuEntry(s.name, R.drawable.ic_source, selected = s.id == active()?.id) { pickedSource = s.id; entries = emptyList(); load() }
    })

    private fun toggleMode() {
        mode = if (mode == "list") "timeline" else "list"
        modeChip.text = if (mode == "list") "Seznam" else "Časová osa"
        listFrame.visibility = if (mode == "list") View.VISIBLE else View.GONE
        timeline.view.visibility = if (mode == "timeline") View.VISIBLE else View.GONE
    }

    private fun load() {
        val src = active() ?: run { show("Nahrávky nabízí jen zdroj TVHeadend."); return }
        sourceChip.text = src.name; sourceChip.visibility = if (tvhSources().size > 1) View.VISIBLE else View.GONE
        loading = true; show("Načítám nahrávky…")
        Bg.run({ Library.backend(src).recordings() }) { r, err ->
            loading = false
            if (err != null) { show("Nahrávky se nepodařilo načíst: ${Redact.url(SourceForm.errorText(err))}\nUživatel musí mít v TVHeadendu právo k nahrávání (DVR)."); return@run }
            entries = r.orEmpty().map { RecEntry(src, it) }
            byChannel.clear()
            for ((key, group) in entries.groupBy { "${src.id}|${it.rec.channelId}" }) {
                if (Library.find(key) != null) byChannel[key] = group.map { Program(it.rec.start, it.rec.stop, it.rec.title, it.rec.description) to it }.sortedBy { it.first.start }
            }
            adapter.submit(entries)
            timeline.submit(byChannel.keys.mapNotNull { Library.find(it) }.sortedBy { it.ch.number.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
            entries.firstOrNull { it.rec.playable }?.let { timeline.resetWindow(it.rec.start) }
            val done = entries.count { it.rec.status == "completed" }
            count.text = "${entries.size} · hotových $done"
            show(if (entries.isEmpty()) "Žádné nahrávky. Naplánuj je v TVHeadendu." else null)
        }
    }

    private fun show(t: String?) { status.text = t ?: ""; status.visibility = if (t == null) View.GONE else View.VISIBLE }

    private fun entryFor(item: LiveItem, p: Program?): RecEntry? {
        val group = byChannel[item.key] ?: return null
        return (if (p != null) group.firstOrNull { it.first.start == p.start } else null)?.second
    }

    private fun play(e: RecEntry) {
        if (!e.rec.playable) { act.toast(if (e.rec.status == "scheduled") "Nahrávka teprve začne" else "Tuto nahrávku nelze přehrát"); return }
        VodActions.playRecording(act, e.src, e.rec)
    }

    private class RecRow(ctx: android.content.Context) : ListRow(ctx, padV = Sp.M) {
        init { subView.typeface = Design.mono(ctx, 400) }

        fun bind(e: RecEntry) {
            val r = e.rec
            val mb = if (r.sizeBytes > 0) " · ${if (r.sizeBytes > 1 shl 30) "%.1f GB".format(r.sizeBytes / 1073741824.0) else "${r.sizeBytes shr 20} MB"}" else ""
            val sub = "${r.channelName} · ${SimpleDateFormat("EEE d. M. HH:mm", Locale.getDefault()).format(Date(r.start))}–${hhmm(r.stop)}$mb"
            val badge = when (r.status) { "recording" -> "NAHRÁVÁ"; "scheduled" -> "NAPLÁNOVÁNO"; "completed" -> null; else -> "NEDOSTUPNÉ" }
            val p = UserData.progress(VodActions.recordingKey(e.src, r.id))
            setContent(r.title + if (r.subtitle.isNotBlank()) " · ${r.subtitle}" else "", sub, badge, p?.takeIf { it.resumable }?.fraction)
            badgeView.setTextColor(if (r.status == "recording") Design.LIVE else Design.accent)
            alpha = if (r.playable) 1f else 0.6f
        }
    }
}
