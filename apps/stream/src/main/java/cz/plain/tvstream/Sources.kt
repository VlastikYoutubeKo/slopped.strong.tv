package cz.plain.tvstream

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SourceType(val label: String) {
    XTREAM("Xtream Codes"), TVHEADEND("TVHeadend"), STALKER("Stalker / Ministra"),
}

/**
 * Zdroj kanálů. U Stalkeru je [url] adresa portálu a [user] MAC adresa zařízení (heslo se nepoužívá).
 */
data class Source(
    val id: String = UUID.randomUUID().toString(),
    val type: SourceType,
    val name: String,
    val url: String,
    val user: String,
    val pass: String,
) {
    /** Adresa bez koncového lomítka; když chybí schéma, doplní http://. */
    val baseUrl: String
        get() = url.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }

    val secure: Boolean get() = baseUrl.startsWith("https://")

    /** Jen název serveru bez schématu, portu a cest (do karet a diagnostiky, nikdy ne přihlašovací údaje). */
    val host: String
        get() = try { android.net.Uri.parse(baseUrl).host ?: baseUrl } catch (_: Exception) { baseUrl }
}

/**
 * Uložené zdroje. Hesla jsou šifrovaná klíčem v Android Keystore (AES-GCM),
 * takže v datech aplikace neleží v čitelné podobě. Pořadí v seznamu = pořadí v aplikaci.
 */
class SourceStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun all(): List<Source> {
        val arr = JSONArray(sp.getString("list", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            try {
                Source(
                    id = o.getString("id"),
                    type = SourceType.valueOf(o.getString("type")),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    user = o.optString("user"),
                    pass = Crypto.decrypt(o.optString("pass")),
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    /** Uloží zdroj; existující zůstane na svém místě, nový se přidá nakonec. */
    fun save(s: Source) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list += s
        write(list)
    }

    fun delete(id: String) = write(all().filter { it.id != id })

    /** Posune zdroj o [dir] míst (−1 doleva, +1 doprava). */
    fun move(id: String, dir: Int) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + dir
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        write(list)
    }

    private fun write(list: List<Source>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("type", s.type.name); put("name", s.name)
                put("url", s.url); put("user", s.user); put("pass", Crypto.encrypt(s.pass))
            })
        }
        sp.edit().putString("list", arr.toString()).apply()
    }
}

/** Stav zdroje pro karty: poslední synchronizace, počty, výsledek posledního připojení. Bez přihlašovacích údajů. */
class SourceStats(ctx: Context) {
    class Stat(val lastSync: Long, val ok: Boolean?, val message: String?, val channels: Int, val radio: Int, val movies: Int, val series: Int, val caps: Caps?)

    private val sp = ctx.applicationContext.getSharedPreferences("source_stats", Context.MODE_PRIVATE)

    fun get(id: String): Stat? = sp.getString(id, null)?.let {
        try {
            val o = JSONObject(it)
            val c = o.optJSONObject("caps")
            Stat(o.optLong("t"), if (o.has("ok")) o.optBoolean("ok") else null, o.optString("msg").ifEmpty { null },
                o.optInt("ch"), o.optInt("radio"), o.optInt("movies", -1), o.optInt("series", -1),
                c?.let { x -> Caps(x.optBoolean("live", true), x.optBoolean("vod"), x.optBoolean("series"), x.optBoolean("epg", true), x.optBoolean("radio")) })
        } catch (_: Exception) { null }
    }

    fun update(id: String, f: (Stat?) -> Stat) {
        val s = f(get(id))
        sp.edit().putString(id, JSONObject().apply {
            put("t", s.lastSync); s.ok?.let { put("ok", it) }; s.message?.let { put("msg", it) }
            put("ch", s.channels); put("radio", s.radio); put("movies", s.movies); put("series", s.series)
            s.caps?.let { c -> put("caps", JSONObject().put("live", c.live).put("vod", c.vod).put("series", c.series).put("epg", c.epg).put("radio", c.radio)) }
        }.toString()).apply()
    }

    fun clear(id: String) = sp.edit().remove(id).apply()
}

private object Crypto {
    private const val ALIAS = "tvstream-credentials"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        val data = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(c.doFinal(data, 12, data.size - 12))
    }
}

/** Odstranění tajemství z adres a textů (logy, diagnostika, chybové hlášky). */
object Redact {
    private val QUERY = Regex("""([?&](?:password|pass|pwd|token|username|user|key|auth|ticket|mac)=)[^&\s]+""", RegexOption.IGNORE_CASE)
    private val XTREAM_PATH = Regex("""/(live|movie|series)/[^/]+/[^/]+/""")
    private val USERINFO = Regex("""(https?://)[^/@\s]+@""")

    fun url(s: String): String = s.replace(QUERY, "$1***").replace(XTREAM_PATH, "/$1/***/***/").replace(USERINFO, "$1***@")
}
