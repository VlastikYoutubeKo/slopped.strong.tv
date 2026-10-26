package cz.plain.tvstream

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Přidání / úprava zdroje ovladačem. Vlevo návod k vybranému typu (a cesta k pohodlnějšímu zadání z telefonu),
 * vpravo formulář a stavová karta (průběh, úspěch, srozumitelná chyba). Údaje se nikdy nevypisují do logu.
 */
class EditSourceActivity : Activity() {
    private var type = SourceType.XTREAM
    private var existing: Source? = null
    private var busy = false

    private val segs = HashMap<SourceType, TextView>()
    private lateinit var guideTitle: TextView
    private lateinit var guideSteps: LinearLayout
    private lateinit var url: EditText
    private lateinit var urlHelp: TextView
    private lateinit var httpWarn: TextView
    private lateinit var userLabel: TextView
    private lateinit var user: EditText
    private lateinit var passBox: LinearLayout
    private lateinit var genMac: TextView
    private lateinit var pass: EditText
    private lateinit var name: EditText
    private lateinit var statusCard: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var statusTitle: TextView
    private lateinit var statusMsg: TextView
    private lateinit var primary: TextView

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(UiScale.wrap(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this); Library.init(this)
        existing = intent.getStringExtra("id")?.let { SourceStore(this).get(it) }
        existing?.let { type = it.type }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = AmbientBackground()
            setPadding(dp(56), dp(30), dp(56), dp(30)); clipChildren = false; clipToPadding = false
        }
        root.addView(buildGuide(), LinearLayout.LayoutParams(dp(320), MATCH_PARENT).apply { marginEnd = dp(36) })
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(buildForm()) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        setContentView(root)
        setType(type)
        (if (existing == null) segs[type] else url)?.requestFocus()
    }

    // ---------------------------------------------------------------- levý panel

    private fun buildGuide() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Design.glass(this@EditSourceActivity, Design.R_LG, solid = true)
        setPadding(dp(28), dp(30), dp(28), dp(Sp.XL))
        addView(icon(R.drawable.ic_source, 40, Design.accent))
        addView(label(Typo.HEADING, Design.TEXT, 600).apply { text = if (existing == null) "Nový zdroj" else "Upravit zdroj" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(Sp.L) })
        guideTitle = label(Typo.BODY, Design.TEXT2, 400, lines = 4).apply { setLineSpacing(0f, 1.2f) }
        addView(guideTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.S) })
        guideSteps = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(guideSteps, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(20) })
        addView(glassButton("Vyplnit z telefonu", R.drawable.ic_phone) { startActivity(Intent(this@EditSourceActivity, WebSetupActivity::class.java)); finish() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
    }

    private fun showGuide() {
        guideTitle.text = when (type) {
            SourceType.XTREAM -> "Údaje dostaneš od poskytovatele IPTV, obvykle jako „Xtream Codes“."
            SourceType.TVHEADEND -> "Server TVHeadend ve tvé síti. Uživatel musí mít povolené streamování."
            SourceType.STALKER -> "Portál Stalker / Ministra. Přihlašuje se MAC adresou zařízení, kterou ti aktivoval poskytovatel."
        }
        val steps = when (type) {
            SourceType.XTREAM -> listOf("Adresa serveru" to "např. http://server.cz:8080", "Jméno a heslo" to "z údajů od poskytovatele", "Celý M3U odkaz?" to "vlož ho do adresy, údaje se vytáhnou samy")
            SourceType.TVHEADEND -> listOf("Adresa" to "obvykle http://IP-adresa:9981", "Uživatel a heslo" to "účet z TVHeadendu (Basic i Digest)", "Kanály a EPG" to "načtou se samy, včetně štítků")
            SourceType.STALKER -> listOf("Adresa portálu" to "obvykle končí /c/", "MAC adresa" to "ve tvaru 00:1A:79:12:34:56", "Heslo" to "nepoužívá se")
        }
        guideSteps.removeAllViews()
        steps.forEachIndexed { i, (t, d) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label(Typo.SMALL, Design.accent, 600, mono = true).apply { text = (i + 1).toString(); gravity = Gravity.CENTER; background = Design.pill(Design.withAlpha(Design.accent, 0.16f), 14f, this@EditSourceActivity) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(2), 0, 0) }
            col.addView(label(Typo.BODY, Design.TEXT, 500).apply { text = t })
            col.addView(label(Typo.SMALL, Design.MUTED, 400, lines = 2).apply { text = d }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(3) })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            guideSteps.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
        }
    }

    // ---------------------------------------------------------------- formulář

    private fun buildForm() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(6), dp(6), dp(10))
        addView(caption("Typ serveru"))
        val seg = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (t in SourceType.entries) {
            val b = label(Typo.LABEL, Design.TEXT, 500).apply {
                text = t.label; gravity = Gravity.CENTER; background = Design.glass(this@EditSourceActivity, Design.R_CTRL)
                Design.focusable(this, Design.R_CTRL, Design.SCALE_CHIP); setOnClickListener { setType(t) }
            }
            segs[t] = b
            seg.addView(b, LinearLayout.LayoutParams(0, dp(Sp.XXXL), 1f).apply { marginEnd = dp(10) })
        }
        addView(seg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.S) })

        url = field(this, "Adresa serveru", existing?.url, InputType.TYPE_TEXT_VARIATION_URI, top = 18)
        httpWarn = label(Typo.SMALL, Design.WARNING, 400, lines = 2).apply { text = "Nešifrované spojení (HTTP): přihlašovací údaje půjdou sítí čitelně. Pokud server umí HTTPS, použij ho."; visibility = View.GONE }
        addView(httpWarn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        urlHelp = label(Typo.SMALL, Design.MUTED, 400, lines = 2).also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
        url.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { val v = s.toString().trim(); httpWarn.visibility = if (v.startsWith("http://") || (v.isNotEmpty() && !v.startsWith("https://"))) View.VISIBLE else View.GONE }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val pair = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val left = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        passBox = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        user = field(left, "Uživatelské jméno", existing?.user)
        userLabel = left.getChildAt(0) as TextView
        genMac = glassButton("Vygenerovat MAC", R.drawable.ic_refresh) { user.setText(StalkerProto.randomMac()); user.setSelection(user.text.length) }
        left.addView(genMac, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { topMargin = dp(Sp.S) })
        pass = field(passBox, "Heslo", existing?.pass, InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pair.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(Sp.L) })
        pair.addView(passBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pair, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.L) })
        name = field(this, "Název v seznamu (nepovinné)", existing?.name, top = 16)

        statusCard = LinearLayout(this@EditSourceActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(14)); visibility = View.GONE
            background = Design.glass(this@EditSourceActivity, Design.R_CTRL, solid = true)
        }
        spinner = ProgressBar(this@EditSourceActivity).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Design.accent) }
        statusCard.addView(spinner, LinearLayout.LayoutParams(dp(28), dp(28)))
        val col = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(Sp.L), 0, 0, 0) }
        statusTitle = label(Typo.LABEL, Design.TEXT, 600); statusMsg = label(Typo.BODY, Design.TEXT2, 400, lines = 3).apply { setLineSpacing(0f, 1.15f) }
        col.addView(statusTitle); col.addView(statusMsg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.XS) })
        statusCard.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(statusCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        val buttons = LinearLayout(this@EditSourceActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        primary = primaryButton(if (existing == null) "Připojit a uložit" else "Uložit a znovu připojit") { save(force = false) }
        buttons.addView(primary, LinearLayout.LayoutParams(WRAP_CONTENT, dp(56)))
        buttons.addView(glassButton("Uložit bez testu") { save(force = true) }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(56)).apply { marginStart = dp(Sp.M) })
        addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18); bottomMargin = dp(Sp.S) })
    }

    /** Pole s popiskem nad sebou (popisek zůstává vidět i po vyplnění). */
    private fun field(parent: LinearLayout, label: String, value: String?, variation: Int = 0, top: Int = 0): EditText {
        parent.addView(caption(label), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) })
        val e = EditText(this).apply {
            setText(value ?: ""); textSize = Typo.LABEL; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or variation
            typeface = Design.ui(this@EditSourceActivity, 400)
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Design.TEXT); setHintTextColor(Design.MUTED)
            background = Design.glass(this@EditSourceActivity, Design.R_CTRL); setPadding(dp(18), 0, dp(18), 0)
            Design.focusable(this, Design.R_CTRL, Design.SCALE_FIELD)
            // Klávesnice se neotvírá sama (zachytila by šipky): otevře se stiskem OK na poli.
            showSoftInputOnFocus = false
            setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, 0) }
        }
        parent.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { topMargin = dp(Sp.S) })
        return e
    }

    private fun setType(t: SourceType) {
        type = t
        segs.forEach { (k, v) -> v.isActivated = k == t; v.setTextColor(if (k == t) Design.accent else Design.TEXT) }
        url.hint = when (t) { SourceType.XTREAM -> "http://server.cz:8080"; SourceType.TVHEADEND -> "http://192.168.1.10:9981"; SourceType.STALKER -> "http://portal.cz/c/" }
        urlHelp.text = when (t) {
            SourceType.XTREAM -> "Jen adresa serveru, nebo celý M3U odkaz. Údaje z něj se doplní samy."
            SourceType.TVHEADEND -> "Webové rozhraní TVHeadendu, výchozí port 9981."
            SourceType.STALKER -> "Adresa portálu, obvykle končí /c/."
        }
        userLabel.text = (if (t == SourceType.STALKER) "MAC adresa zařízení" else "Uživatelské jméno").uppercase()
        user.hint = if (t == SourceType.STALKER) "00:1A:79:12:34:56" else ""
        passBox.visibility = if (t == SourceType.STALKER) View.GONE else View.VISIBLE
        genMac.visibility = if (t == SourceType.STALKER) View.VISIBLE else View.GONE
        showGuide()
    }

    // ---------------------------------------------------------------- uložení a test

    private fun save(force: Boolean) {
        if (busy) return
        SourceForm.fromM3u(url.text.toString())?.let { (u, n, p) -> url.setText(u); user.setText(n); pass.setText(p) }
        val userText = if (type == SourceType.STALKER) StalkerProto.normalizeMac(user.text.toString()) else user.text.toString()
        val s = SourceForm.build(existing?.id, type, name.text.toString(), url.text.toString(), userText, pass.text.toString())
        SourceForm.validate(s)?.let { (t, m) ->
            status(false, t, m, error = true)
            (if (s.url.isEmpty()) url else if (s.user.isEmpty() || type == SourceType.STALKER) user else pass).requestFocus()
            return
        }
        if (force) { saveAndFinish(s); return }
        busy = true; primary.isEnabled = false
        status(true, "Připojuji se…", s.host)
        Bg.run({ Backends.of(s, Net.client(s)).let { b -> b.load { step, msg -> Bg.main.post { status(true, "Krok $step ze 3", msg) } } } }) { lineup, err ->
            busy = false; primary.isEnabled = true
            if (lineup != null) {
                status(false, "Připojeno a uloženo", "${fmtCount(lineup.channels.size)} kanálů · ${fmtCount(lineup.categories.size)} skupin", ok = true)
                SourceStore(this).save(s); Library.forget(s.id, deleteCache = existing != null); Library.start()
                Bg.main.postDelayed({ finish() }, 1200)
            } else {
                status(false, "Nepodařilo se připojit", Redact.url(SourceForm.errorText(err ?: Exception("?"))), error = true)
                primary.requestFocus()
            }
        }
    }

    private fun saveAndFinish(s: Source) {
        SourceStore(this).save(s); Library.forget(s.id, deleteCache = existing != null); Library.start(); finish()
    }

    private fun status(spin: Boolean, title: String, msg: String, ok: Boolean = false, error: Boolean = false) {
        statusCard.visibility = View.VISIBLE
        spinner.visibility = if (spin) View.VISIBLE else View.GONE
        statusTitle.text = title; statusMsg.text = msg
        statusTitle.setTextColor(when { error -> Design.ERROR; ok -> Design.OK; else -> Design.TEXT })
    }
}
