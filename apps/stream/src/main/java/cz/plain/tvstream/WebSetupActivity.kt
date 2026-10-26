package cz.plain.tvstream

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * „Nastavit z telefonu“: spustí WebSetupServer, ukáže QR kód (adresa + PIN), adresu a PIN a průběžně vypisuje,
 * co se z telefonu děje. Odchodem z obrazovky se server vypne (a s ním i PIN).
 */
class WebSetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var server: WebSetupServer? = null
    private lateinit var log: LinearLayout
    private val events = ArrayDeque<String>()

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(UiScale.wrap(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this); Library.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ip = lanIp()
        val srv = WebSetupServer(this) { e -> main.post { event(e) } }
        val port = try { srv.start() } catch (e: Exception) { 0 }
        server = srv
        val address = if (ip != null && port > 0) "http://$ip:$port" else null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = AmbientBackground()
            setPadding(dp(80), dp(28), dp(80), dp(28))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(caption("Nastavit z telefonu"))
        left.addView(label(Typo.HEADLINE, Design.TEXT, 600, lines = 2).apply { text = "Naskenuj kód\ntelefonem" }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        listOf("Telefon musí být na stejné Wi-Fi jako televize.", "Naskenuj QR kód fotoaparátem, PIN se doplní sám.", "Přidej nebo uprav zdroje. Na televizi se změny objeví hned.").forEachIndexed { i, s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(Typo.BODY, Design.accent, 600, mono = true).apply { text = (i + 1).toString(); gravity = Gravity.CENTER; background = Design.pill(Design.withAlpha(Design.accent, 0.16f), 15f, this@WebSetupActivity) }, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(label(Typo.LABEL, Design.TEXT2, 400, lines = 2).apply { text = s; setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            left.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(if (i == 0) 22 else 12) })
        }
        val facts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        facts.addView(fact("Adresa", address?.removePrefix("http://") ?: "není síť"), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(Sp.L) })
        facts.addView(fact("PIN", srv.pin.chunked(3).joinToString(" "), big = true))
        left.addView(facts, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(26) })
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(log, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(Sp.L) })
        left.addView(label(Typo.SMALL, Design.MUTED, 400).apply { text = "Zpět = hotovo. Server se tím vypne." }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        root.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(56) })

        // QR na bílé kartě: kontrast pro fotoaparát telefonu.
        val card = FrameLayout(this).apply { background = Design.pill(Color.WHITE, Design.R_LG, this@WebSetupActivity); setPadding(dp(18), dp(18), dp(18), dp(18)) }
        if (address != null) card.addView(ImageView(this).apply { setImageBitmap(qr("$address/#${srv.pin}", 600)) }, FrameLayout.LayoutParams(dp(300), dp(300)))
        else card.addView(label(Typo.LABEL, Design.BG, 600, lines = 4).apply { text = "Televize není připojená k síti."; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(300), dp(300)))
        root.addView(card, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        setContentView(root)
        event(if (address != null) "Čekám na telefon…" else "Nepodařilo se spustit server (síť / port)")
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }

    private fun fact(label: String, value: String, big: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Design.glass(this@WebSetupActivity, Design.R_STD, solid = true)
        setPadding(dp(22), dp(14), dp(26), dp(Sp.L))
        addView(caption(label))
        addView(label(if (big) 26f else 22f, if (big) Design.accent else Design.TEXT, 600, mono = true, lines = 1).apply { text = value; ellipsize = null; if (big) letterSpacing = 0.04f }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private fun event(e: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        events.addFirst("$time   $e")
        while (events.size > 3) events.removeLast()
        log.removeAllViews()
        events.forEachIndexed { i, s -> log.addView(label(Typo.BODY, if (i == 0) Design.TEXT else Design.MUTED, 400, mono = true).apply { text = s }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }) }
    }

    private fun qr(content: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }
}
