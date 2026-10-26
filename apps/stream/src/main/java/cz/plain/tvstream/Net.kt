package cz.plain.tvstream

import android.util.JsonReader
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object Net {
    /** Klient pro daný zdroj; u TVHeadendu s přihlašováním (Basic i Digest). */
    fun client(source: Source): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Velké playlisty tečou pomalu (22 MB za ~2 min) – limit je na pauzu mezi daty, ne na celé stažení.
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
        if (source.type == SourceType.TVHEADEND && source.user.isNotEmpty()) {
            b.authenticator(BasicDigestAuthenticator(source.user, source.pass))
        }
        // Zdroj přes HTTPS nesmí být tajně degradován na HTTP (např. přesměrováním nebo odkazem v seznamu).
        // Zdroj zadaný jako http:// má nešifrované spojení výslovně povolené uživatelem; platforma kvůli tomu
        // povoluje cleartext obecně (Android neumí výjimku pro jeden zdroj za běhu), proto se hlídá tady.
        if (source.secure) b.addInterceptor { chain ->
            val req = chain.request()
            if (req.url.scheme == "http" && req.header("X-Purpose") != "image") throw IOException("Spojení přes HTTP zablokováno: zdroj je nastavený jako HTTPS.")
            chain.proceed(req.newBuilder().removeHeader("X-Purpose").build())
        } else b.addInterceptor { chain -> chain.proceed(chain.request().newBuilder().removeHeader("X-Purpose").build()) }
        return b.build()
    }

    fun get(client: OkHttpClient, url: String): String = request(client, url) { it.body?.string() ?: "" }

    /** Streamované čtení JSON – velké odpovědi se nedrží celé v paměti. */
    fun <T> json(client: OkHttpClient, url: String, read: (JsonReader) -> T): T =
        request(client, url) { r -> JsonReader(r.body!!.charStream()).use { read(it) } }

    /** Požadavek s až 3 pokusy u výpadků spojení (ne u chyb serveru). */
    private fun <T> request(client: OkHttpClient, url: String, body: (Response) -> T): T {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(req).execute().use { r ->
                    when {
                        r.code == 401 -> throw HttpError("Server odmítl jméno nebo heslo (401).")
                        r.code == 403 -> throw HttpError("Přístup odepřen (403). Účet nemá oprávnění, nebo je zablokovaný.")
                        r.code == 404 -> throw HttpError("Server tuhle službu nenabízí (404). Buď je špatně adresa, nebo má poskytovatel výpadek.")
                        r.code >= 500 -> throw HttpError("Chyba na straně serveru (${r.code}). Zkus to za chvíli.")
                        !r.isSuccessful -> throw HttpError("Server vrátil chybu ${r.code}.")
                    }
                    return body(r)
                }
            } catch (e: HttpError) {
                throw e
            } catch (e: UnknownHostException) {
                throw HttpError("Adresa serveru neexistuje. Zkontroluj ji (např. http://server:8080).")
            } catch (e: IOException) {
                last = e
                if (e is SSLException) break
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw HttpError(describe(last))
    }

    private fun describe(e: IOException?): String = when (e) {
        is SocketTimeoutException -> if (e.message?.contains("connect", true) == true)
            "Server neodpovídá. Spojení se nenavázalo ani na 3 pokusy, server je nedostupný nebo přetížený."
        else "Server přestal posílat data. Je přetížený nebo spojení vypadlo."
        is ConnectException -> "Server odmítl spojení. Zkontroluj adresu a port."
        is SSLException -> "Zabezpečené spojení selhalo. Zkus adresu s http:// místo https://."
        is InterruptedIOException -> "Načítání trvalo příliš dlouho."
        is java.io.EOFException -> "Server ukončil spojení uprostřed odpovědi. Je přetížený, nebo vypadl."
        null -> "Neznámá chyba sítě."
        else -> "Chyba sítě: ${Redact.url(e.message ?: e.javaClass.simpleName)}"
    }

    const val USER_AGENT = "TVStream/1.1 (Android TV)"
}

class HttpError(msg: String) : Exception(msg)

/**
 * Odpoví na 401 podle hlavičky WWW-Authenticate: Digest (výchozí v TVHeadendu, MD5, qop=auth)
 * nebo Basic. Po druhém neúspěchu to vzdá, aby se nezacyklil.
 */
class BasicDigestAuthenticator(private val user: String, private val pass: String) : Authenticator {
    private val rnd = SecureRandom()

    override fun authenticate(route: Route?, response: Response): okhttp3.Request? {
        var prior = response.priorResponse
        var tries = 1
        while (prior != null) { tries++; prior = prior.priorResponse }
        if (tries > 2) return null

        val challenges = response.headers("WWW-Authenticate")
        val digest = challenges.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
        val req = response.request
        if (digest != null) {
            return req.newBuilder().header("Authorization", digestHeader(digest, req)).build()
        }
        if (challenges.any { it.trim().startsWith("Basic", ignoreCase = true) } || challenges.isEmpty()) {
            return req.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
        }
        return null
    }

    private fun digestHeader(challenge: String, req: okhttp3.Request): String {
        val p = parseParams(challenge.substringAfter(' '))
        val realm = p["realm"] ?: ""
        val nonce = p["nonce"] ?: ""
        val opaque = p["opaque"]
        val algorithm = p["algorithm"] ?: "MD5"
        val qop = p["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val uri = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        val cnonce = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val nc = "00000001"

        var ha1 = md5("$user:$realm:$pass")
        if (algorithm.equals("MD5-sess", true)) ha1 = md5("$ha1:$nonce:$cnonce")
        val ha2 = md5("${req.method}:$uri")
        val resp = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")

        return buildString {
            append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", ")
            append("algorithm=$algorithm, response=\"$resp\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            if (opaque != null) append(", opaque=\"$opaque\"")
        }
    }

    private fun parseParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("(\\w+)=(\"([^\"]*)\"|([^,\\s]*))").findAll(s).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[3].ifEmpty { m.groupValues[4] }
        }
        return out
    }

    private fun md5(s: String) =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
