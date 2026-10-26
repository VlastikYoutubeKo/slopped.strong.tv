package cz.plain.odjezdytv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * WebView, ve kterém běží board.js z Odjezdy OS (assets/app) přes shim.js.
 * - https://appassets.androidplatform.net/… = soubory z assets (stránka, shim)
 * - https://app.odjezdy.online/… = nativní proxy (API neposílá CORS hlavičky, stejně jako řeší server.js)
 */
@SuppressLint("SetJavaScriptEnabled")
class BoardHost(
    private val ctx: Context,
    private val args: List<String>,
    private val onExit: () -> Unit,
) {
    val view: WebView = WebView(ctx)
    private val main = Handler(Looper.getMainLooper())

    init {
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = false
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        view.addJavascriptInterface(Bridge(), "Native")
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i("OdjezdyTV", "console ${m.messageLevel()}: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url
                return when (url.host) {
                    ASSET_HOST -> asset(url.path.orEmpty().trimStart('/'))
                    in API_HOSTS -> proxy(url.toString())
                    else -> WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest) = true
        }
        view.loadUrl("https://$ASSET_HOST/host.html")
    }

    /** Klávesa ve formátu, který board.js čte z terminálu (např. "\u001b[A", "\r", "s"). */
    fun key(k: String) = view.evaluateJavascript("__key(${JSONObject.quote(k)})", null)

    fun eval(js: String, cb: (String) -> Unit) = view.evaluateJavascript(js) { cb(it) }

    fun pause() { view.onPause(); view.pauseTimers() }
    fun resume() { view.resumeTimers(); view.onResume() }
    fun destroy() { view.destroy() }

    private fun asset(path: String): WebResourceResponse {
        val mime = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        return try {
            WebResourceResponse(mime, "utf-8", ctx.assets.open(path))
        } catch (_: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    // Stejný User-Agent jako board.js (Transitous vyžaduje kontakt na projekt).
    private val userAgent by lazy {
        val v = try { ctx.assets.open("app/VERSION").bufferedReader().use { it.readText().trim() } } catch (_: Exception) { "android" }
        "OdjezdyOS/$v (+https://github.com/odjezdy-online/odjezdy-system; Android TV)"
    }

    private fun proxy(url: String): WebResourceResponse {
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            c.disconnect()
            WebResourceResponse("application/json", "utf-8", code, c.responseMessage ?: "OK", headers, ByteArrayInputStream(body))
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", headers, ByteArrayInputStream((e.message ?: "").toByteArray()))
        }
    }

    /** Synchronní funkce pro shim.js (fs, os, process). */
    private inner class Bridge {
        private val prefs = ctx.getSharedPreferences("odjezdy", Context.MODE_PRIVATE)

        @JavascriptInterface fun args(): String = JSONArray(args).toString()

        @JavascriptInterface fun readText(path: String): String? =
            try { ctx.assets.open(path).bufferedReader().use { it.readText() } } catch (_: Exception) { null }

        @JavascriptInterface fun readGz(path: String): String? =
            try { GZIPInputStream(ctx.assets.open(path)).bufferedReader().use { it.readText() } } catch (_: Exception) { null }

        @JavascriptInterface fun exists(path: String): Boolean =
            try { ctx.assets.open(path).close(); true } catch (_: Exception) { (ctx.assets.list(path)?.isNotEmpty() == true) }

        @JavascriptInterface fun list(path: String): String = JSONArray(ctx.assets.list(path)?.toList() ?: emptyList<String>()).toString()

        @JavascriptInterface fun isDir(path: String): Boolean =
            try { ctx.assets.list(path)?.isNotEmpty() == true } catch (_: Exception) { false }

        // Binární soubory (TTF písma pro LCD motivy) – přes most jde jen text, proto Base64.
        @JavascriptInterface fun readBase64(path: String): String? =
            try { Base64.encodeToString(ctx.assets.open(path).use { it.readBytes() }, Base64.NO_WRAP) } catch (_: Exception) { null }

        // Trvalé soubory tabule (/etc/odjezdy/config.json, /var/lib/odjezdy/board-cache.json …) – sdílí je
        // aplikace i spořič. Starší verze ukládala konfiguraci pod klíčem „config“.
        @JavascriptInterface fun fileLoad(path: String): String? =
            prefs.getString("file:$path", null) ?: if (path == CONFIG) prefs.getString("config", null) else null
        @JavascriptInterface fun fileSave(path: String, text: String) { prefs.edit().putString("file:$path", text).apply() }
        @JavascriptInterface fun fileDelete(path: String) { prefs.edit().remove("file:$path").apply() }

        @JavascriptInterface fun meminfo(): String = try { File("/proc/meminfo").readText() } catch (_: Exception) { "" }

        // IP pro stavový řádek tabule; NetworkInterface nepotřebuje žádné oprávnění.
        @JavascriptInterface fun ip(): String = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress ?: ""
        } catch (_: Exception) { "" }

        @JavascriptInterface fun exit(code: Int) { main.post(onExit) }

        @JavascriptInterface fun log(s: String) { Log.i("OdjezdyTV", s) }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        // odjezdy.online (ČR) + Transitous (zastávky ve světě) – nic jiného WebView nesmí.
        private val API_HOSTS = setOf("app.odjezdy.online", "api.transitous.org")
        private const val CONFIG = "/etc/odjezdy/config.json"
    }
}
