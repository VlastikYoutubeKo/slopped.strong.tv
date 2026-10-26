package cz.plain.odjezdytv

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * Tabule Odjezdy OS na celé obrazovce. Ovladač se mapuje na klávesy z README Odjezdy OS:
 *   OK = Enter (na tabuli otevře menu „Kam to dnes bude“), Zpět = Esc / ukončení na tabuli,
 *   ▲▼ = pohyb v menu, ◀▶ na tabuli = přepnutí oblíbené zastávky, číslice 1–9 = oblíbené,
 *   ◀▶ v procházení krajů = o stránku, Menu / ◀▶ v úvodním menu = vzhled tabule (T).
 * Při hledání zastávky se otevře klávesnice Androidu a text se posílá rovnou do tabule.
 */
class MainActivity : Activity() {

    private lateinit var host: BoardHost
    private lateinit var input: EditText
    private var mode = "start"
    private var typing = false
    private var imeShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        host = BoardHost(this, emptyList()) { finish() }

        // Skryté pole pro psaní názvu zastávky klávesnicí Androidu.
        input = EditText(this).apply {
            alpha = 0f
            isFocusable = false // fokus (a klávesnici) dostane jen při hledání zastávky
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) {
                    if (typing) host.view.evaluateJavascript("__odj.setQuery(${JSONObject.quote(s.toString())})", null)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ -> stopTyping(); true }
        }
        val root = FrameLayout(this)
        root.addView(host.view)
        root.addView(input, FrameLayout.LayoutParams(1, 1))
        // Klávesnici jde zavřít i tlačítkem Zpět (to dostane jen klávesnice) – pak ukončit psaní,
        // aby šipky znovu vybíraly ve výsledcích.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (typing && imeShown && !insets.isVisible(WindowInsets.Type.ime())) stopTyping()
            imeShown = insets.isVisible(WindowInsets.Type.ime())
            v.onApplyWindowInsets(insets)
        }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); host.resume() }
    override fun onPause() { host.pause(); super.onPause() }
    override fun onDestroy() { host.destroy(); super.onDestroy() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (typing) {
            // Když je klávesnice vidět, šipky a OK dostává ona. Pokud dorazí sem, je už zavřená
            // (Zpět ji schová) → ukončit psaní a klávesu předat tabuli (výběr ve výsledcích).
            val nav = event.keyCode in NAV_KEYS
            if (!nav && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            stopTyping()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        }
        val k = mapKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when {
            k == "t" && mode == "setup" -> { startTyping(); return true } // Menu = znovu otevřít klávesnici
            k == BACK && mode == "board" -> finish()
            k == BACK -> host.key("\u001b")
            k == FAV_NEXT || k == FAV_PREV -> when (mode) {
                "board" -> cycleFavorite(if (k == FAV_NEXT) 1 else -1)
                "browse" -> host.key(if (k == FAV_NEXT) "\u001b[6~" else "\u001b[5~") // stránka dolů / nahoru
                "start" -> host.key("t")
            }
            else -> host.key(k)
        }
        refreshMode()
        return true
    }

    private fun mapKey(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
        KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
        KeyEvent.KEYCODE_DPAD_RIGHT -> FAV_NEXT
        KeyEvent.KEYCODE_DPAD_LEFT -> FAV_PREV
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> BACK
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> "t"
        KeyEvent.KEYCODE_DEL -> "\u007f"
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> ('1' + (code - KeyEvent.KEYCODE_1)).toString()
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (code - KeyEvent.KEYCODE_A)).toString()
        else -> null
    }

    /** ◀▶ na tabuli: další / předchozí oblíbená zastávka (stejně jako klávesy 1–9). */
    private fun cycleFavorite(dir: Int) {
        host.eval("JSON.stringify([__odj.favorites(), __odj.stop()])") { res ->
            val arr = org.json.JSONArray(org.json.JSONTokener(res).nextValue() as String)
            val favs = arr.getJSONArray(0)
            val n = favs.length()
            if (n < 2) return@eval
            var i = (0 until n).firstOrNull { favs.getString(it) == arr.getString(1) } ?: -1
            i = ((i + dir) % n + n) % n
            host.key((i + 1).toString())
        }
    }

    /** Po každé klávese zjistí režim tabule; v režimu hledání otevře klávesnici. */
    private fun refreshMode() {
        host.eval("__odj.mode()") { res ->
            val newMode = res.trim('"')
            // Klávesnici otevřít jen při vstupu do hledání, ne po jejím zavření.
            if (newMode == "setup" && mode != "setup" && !typing) startTyping()
            mode = newMode
        }
    }

    private fun startTyping() {
        typing = true
        // Pokračovat v již zadaném textu (např. první písmeno napsané v úvodním menu).
        host.eval("__odj.query()") { q ->
            input.setText(org.json.JSONTokener(q).nextValue() as? String ?: "")
            input.setSelection(input.text.length)
        }
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun stopTyping() {
        typing = false
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        input.isFocusable = false
        refreshMode()
    }

    companion object {
        private const val BACK = "<back>"
        private const val FAV_NEXT = "<fav+>"
        private const val FAV_PREV = "<fav->"
        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        )
    }
}
