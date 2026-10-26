package cz.plain.tvstream

import android.net.Uri
import java.util.UUID

/** Společná logika formuláře zdroje – pro ovladač (EditSourceActivity) i pro web (WebSetupServer). */
object SourceForm {

    /** Vložený celý M3U odkaz (…get.php?username=…&password=…) → (adresa, jméno, heslo). */
    fun fromM3u(raw: String): Triple<String, String, String>? {
        val t = raw.trim()
        if (!t.contains("username=")) return null
        val u = Uri.parse(if (t.startsWith("http")) t else "http://$t")
        val user = u.getQueryParameter("username") ?: return null
        val pass = u.getQueryParameter("password") ?: return null
        return Triple("${u.scheme}://${u.authority}", user, pass)
    }

    fun build(id: String?, type: SourceType, name: String, url: String, user: String, pass: String): Source {
        val cleanUrl = url.trim()
        val host = Uri.parse(if (cleanUrl.startsWith("http")) cleanUrl else "http://$cleanUrl").host
        return Source(
            id = id ?: UUID.randomUUID().toString(),
            type = type,
            name = name.trim().ifEmpty { host ?: type.label },
            url = cleanUrl,
            user = user.trim(),
            pass = pass,
        )
    }

    /** null = v pořádku, jinak (nadpis, vysvětlení). */
    fun validate(s: Source): Pair<String, String>? = when {
        s.url.isEmpty() -> "Chybí adresa serveru" to "Vyplň ji ve tvaru http://server:port."
        s.type == SourceType.XTREAM && (s.user.isEmpty() || s.pass.isEmpty()) ->
            "Chybí přihlašovací údaje" to "Xtream Codes potřebuje uživatelské jméno i heslo."
        s.type == SourceType.STALKER && !StalkerProto.macOk(StalkerProto.normalizeMac(s.user)) ->
            "Neplatná MAC adresa" to "Zadej ji ve tvaru 00:1A:79:12:34:56 (12 hexadecimálních znaků)."
        else -> null
    }

    fun errorText(e: Throwable): String =
        Redact.url(if (e is HttpError) e.message!! else "Neočekávaná chyba: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}${e.stackTrace.firstOrNull { it.className.startsWith("cz.plain") }?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""}")
}
