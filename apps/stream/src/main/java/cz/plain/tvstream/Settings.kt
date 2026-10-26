package cz.plain.tvstream

import android.content.Context
import android.content.SharedPreferences

/** Uživatelská nastavení (jedno místo pravdy). Hodnoty se čtou při každém použití, takže změna platí hned. */
object Settings {
    const val DEFAULT_SCALE = 0.8f

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        Design.apply(ctx)
    }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun i(k: String, d: Int) = sp.getInt(k, d)

    // Vzhled
    /** Velikost rozhraní: násobek hustoty obrazovky (0,8 odpovídá obvyklým 240 dpi televizí; box hlásí 320 dpi, takže vše bylo o třetinu větší). */
    var uiScale: Float get() = sp.getFloat("uiScale", DEFAULT_SCALE); set(v) = sp.edit().putFloat("uiScale", v).apply()
    var accentIndex: Int get() = i("accent", 0); set(v) = sp.edit().putInt("accent", v).apply()
    var reducedMotion: Boolean get() = b("reducedMotion", false); set(v) = sp.edit().putBoolean("reducedMotion", v).apply()
    var highContrast: Boolean get() = b("highContrast", false); set(v) = sp.edit().putBoolean("highContrast", v).apply()
    var ambient: Boolean get() = b("ambient", true); set(v) = sp.edit().putBoolean("ambient", v).apply()

    // Živé vysílání
    /** „compact“ = tři sloupce, „timeline“ = časová osa. */
    var epgMode: String get() = s("epgMode", "compact"); set(v) = sp.edit().putString("epgMode", v).apply()
    var sort: String get() = s("sort", "provider"); set(v) = sp.edit().putString("sort", v).apply()
    var radioSort: String get() = s("radioSort", "provider"); set(v) = sp.edit().putString("radioSort", v).apply()

    // Rádio
    /** orb | bars | wave | off */
    var vizMode: String get() = s("vizMode", "orb"); set(v) = sp.edit().putString("vizMode", v).apply()
    /** auto | high | balanced | low | off */
    var vizQuality: String get() = s("vizQuality", "auto"); set(v) = sp.edit().putString("vizQuality", v).apply()
    var vizFps: Int get() = i("vizFps", 0); set(v) = sp.edit().putInt("vizFps", v).apply()
    var radioDim: Boolean get() = b("radioDim", true); set(v) = sp.edit().putBoolean("radioDim", v).apply()
    var radioBackground: Boolean get() = b("radioBackground", true); set(v) = sp.edit().putBoolean("radioBackground", v).apply()
    /** Externí dohledávání obalů alb (iTunes Search). Ve výchozím stavu vypnuto: posílá název písně třetí straně. */
    var coverLookup: Boolean get() = b("coverLookup", false); set(v) = sp.edit().putBoolean("coverLookup", v).apply()
    var audioOnlyAuto: Boolean get() = b("audioOnlyAuto", true); set(v) = sp.edit().putBoolean("audioOnlyAuto", v).apply()

    // Přehrávání / ladění
    var diagnostics: Boolean get() = b("diagnostics", false); set(v) = sp.edit().putBoolean("diagnostics", v).apply()
    var aspect: String get() = s("aspect", "fit"); set(v) = sp.edit().putString("aspect", v).apply()
}
