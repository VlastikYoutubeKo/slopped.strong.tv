package cz.plain.tvstream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Malý HTTP server pro nastavení zdrojů z telefonu / PC (assets/web/index.html + JSON API).
 * Běží jen po dobu, kdy je otevřená obrazovka WebSetupActivity. Každé volání API musí mít PIN
 * (hlavička X-Pin), který je vidět jen na TV; po 5 špatných pokusech se server vypne.
 * Hesla se do prohlížeče nikdy neposílají zpět (jen příznak, že heslo je uložené).
 */
class WebSetupServer(private val ctx: Context, private val onEvent: (String) -> Unit) {

    val pin: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    var port = 0
        private set

    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var badPins = 0
    @Volatile private var greeted = false

    fun start(): Int {
        for (p in listOf(8090, 8091, 8092, 8093)) {
            try { server = ServerSocket(p); port = p; break } catch (_: Exception) {}
        }
        val s = server ?: throw IllegalStateException("Žádný volný port 8090–8093")
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: SocketException) { break }
                pool.execute { handle(c) }
            }
        }, "web-setup").start()
        return port
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun handle(sock: Socket) = sock.use { c ->
        c.soTimeout = 15_000
        val req = try { read(BufferedInputStream(c.getInputStream())) } catch (_: Exception) { return@use } ?: return@use
        // Test zdroje u velkých playlistů trvá i 2 minuty – odpověď čeká, tak delší limit.
        c.soTimeout = 0
        val out = c.getOutputStream()
        try {
            route(req, out)
        } catch (e: Exception) {
            send(out, 500, "application/json", JSONObject().put("error", e.message ?: "chyba").toString())
        }
    }

    private fun read(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val (method, target) = lines[0].split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 64_000) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = input.read(body, off, len - off); if (n < 0) break; off += n }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return Req(method, path, query, headers, String(body, Charsets.UTF_8))
    }

    private fun send(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 500 to "Server Error")[code] ?: "OK"
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun json(out: OutputStream, o: Any, code: Int = 200) = send(out, code, "application/json", o.toString())

    // ---------------------------------------------------------------- API

    private fun route(r: Req, out: OutputStream) {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            if (!greeted) { greeted = true; onEvent("Telefon otevřel stránku nastavení") }
            val accent = "#%06X".format(0xFFFFFF and Design.accent)
            send(out, 200, "text/html", ctx.assets.open("web/index.html").bufferedReader().use { it.readText() }.replace("--accent: #5b8cff;", "--accent: $accent;"))
            return
        }
        if (!r.path.startsWith("/api/")) { send(out, 404, "text/plain", "Nenalezeno"); return }

        if (r.headers["x-pin"] != pin) {
            if (++badPins >= 5) { onEvent("5× špatný PIN – server vypnut"); stop() }
            json(out, JSONObject().put("error", "Špatný PIN. Opiš ho z obrazovky televize."), 401)
            return
        }
        val store = SourceStore(ctx)
        when {
            r.method == "GET" && r.path == "/api/sources" -> json(out, JSONArray().apply {
                store.all().forEach { s ->
                    put(JSONObject().put("id", s.id).put("type", s.type.name).put("name", s.name).put("url", s.url)
                        .put("user", s.user).put("hasPassword", s.pass.isNotEmpty()))
                }
            })

            r.method == "POST" && r.path == "/api/sources" -> {
                val o = JSONObject(r.body)
                val id = o.optString("id").ifEmpty { null }
                val old = id?.let { store.get(it) }
                val type = runCatching { SourceType.valueOf(o.optString("type")) }.getOrDefault(SourceType.XTREAM)
                var url = o.optString("url"); var user = o.optString("user"); var pass = o.optString("pass")
                if (type == SourceType.STALKER) user = StalkerProto.normalizeMac(user)
                SourceForm.fromM3u(url)?.let { (u, n, p) -> url = u; user = n; pass = p }
                if (pass.isEmpty() && old != null) pass = old.pass   // prázdné heslo = ponechat uložené
                val s = SourceForm.build(old?.id, type, o.optString("name"), url, user, pass)
                SourceForm.validate(s)?.let { (t, m) -> json(out, JSONObject().put("ok", false).put("error", "$t. $m"), 400); return }

                if (o.optBoolean("test", true)) {
                    onEvent("Zkouším připojení k ${s.name}…")
                    val lineup = try { Backends.of(s, Net.client(s)).load() } catch (e: Exception) {
                        onEvent("${s.name}: nepodařilo se připojit")
                        json(out, JSONObject().put("ok", false).put("error", SourceForm.errorText(e))); return
                    }
                    store.save(s); Library.forget(s.id, deleteCache = old != null); Library.start()
                    onEvent("Uloženo: ${s.name} · ${fmtCount(lineup.channels.size)} kanálů")
                    json(out, JSONObject().put("ok", true).put("id", s.id).put("channels", lineup.channels.size).put("categories", lineup.categories.size))
                } else {
                    store.save(s); Library.forget(s.id, deleteCache = old != null); Library.start()
                    onEvent("Uloženo bez testu: ${s.name}")
                    json(out, JSONObject().put("ok", true).put("id", s.id))
                }
            }

            r.method == "DELETE" && r.path == "/api/sources" -> {
                val s = r.query["id"]?.let { store.get(it) }
                if (s == null) { json(out, JSONObject().put("error", "Zdroj neexistuje"), 404); return }
                store.delete(s.id); Library.forget(s.id, deleteCache = true)
                onEvent("Smazáno: ${s.name}")
                json(out, JSONObject().put("ok", true))
            }

            else -> send(out, 404, "text/plain", "Nenalezeno")
        }
    }
}
