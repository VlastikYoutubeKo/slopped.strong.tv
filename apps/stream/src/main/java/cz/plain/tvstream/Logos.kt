package cz.plain.tvstream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Obrázky (loga kanálů, plakáty, obaly). Pořadí: paměť (LRU ~8 MB) → disk (cacheDir/logos, max ~80 MB) → síť
 * přes klienta zdroje (TVHeadend má loga za přihlášením). Servery pod zátěží občas vrací 502: neúspěch se
 * proto pamatuje jen 90 s. Zastaralé požadavky (řádek už ukazuje jinou položku) se po stažení zahodí.
 */
object Logos {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val plain: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }
    private var writes = 0

    /**
     * Nastaví obrázek do ImageView; [onLoaded] se zavolá, jen když obrázek opravdu je (skryje náhradní monogram).
     * [client] = klient zdroje (přihlášení), [maxPx] = největší strana po zmenšení.
     */
    fun load(url: String?, into: ImageView, client: OkHttpClient?, maxPx: Int = 160, onLoaded: () -> Unit = {}) {
        into.tag = url
        into.setImageDrawable(null)
        if (url == null) return
        cache.get("$maxPx|$url")?.let { into.setImageBitmap(it); onLoaded(); return }
        failedAt[url]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return else failedAt.remove(url) }
        val dir = File(into.context.cacheDir, "logos")
        val file = File(dir, hash(url))
        val flightKey = "$maxPx|$url"
        // Jeden požadavek na adresu, i když ji chce víc řádků.
        if (!inFlight.add(flightKey)) { main.postDelayed({ if (into.tag == url) load(url, into, client, maxPx, onLoaded) }, 400); return }
        io.execute {
            var bmp: Bitmap? = null
            try {
                if (file.exists()) bmp = decode(file.readBytes(), maxPx)
                if (bmp == null) {
                    val c = (client ?: plain).newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                    c.newCall(Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).header("X-Purpose", "image").build()).execute().use { r ->
                        if (r.isSuccessful) {
                            val bytes = r.body!!.bytes()
                            bmp = decode(bytes, maxPx)
                            if (bmp != null) save(dir, file, bytes)
                        }
                    }
                }
            } catch (_: Exception) { }
            inFlight.remove(flightKey)
            val b = bmp
            if (b == null) { failedAt[url] = System.currentTimeMillis(); return@execute }
            cache.put(flightKey, b)
            main.post { if (into.tag == url) { into.setImageBitmap(b); onLoaded() } }
        }
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun save(dir: File, file: File, bytes: ByteArray) {
        try {
            dir.mkdirs()
            file.writeBytes(bytes)
            // Každých 40 zápisů zkontrolovat velikost a smazat nejstarší.
            if (++writes % 40 == 0) {
                val files = dir.listFiles() ?: return
                var total = files.sumOf { it.length() }
                if (total > MAX_DISK) for (f in files.sortedBy { it.lastModified() }) {
                    total -= f.length(); f.delete()
                    if (total < MAX_DISK * 0.8) break
                }
            }
        } catch (_: Exception) { }
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun clearCache(ctx: android.content.Context): Long {
        val files = File(ctx.cacheDir, "logos").listFiles() ?: return 0
        val total = files.sumOf { it.length() }
        files.forEach { it.delete() }
        cache.evictAll(); failedAt.clear()
        return total
    }

    fun diskUsage(ctx: android.content.Context): Long = File(ctx.cacheDir, "logos").listFiles()?.sumOf { it.length() } ?: 0L

    private const val RETRY_MS = 90_000L
    private const val MAX_DISK = 80L * 1024 * 1024
}
