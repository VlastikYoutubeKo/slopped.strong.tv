package cz.plain.tvtools

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var am: ActivityManager

    private val refresh = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        am = getSystemService(ActivityManager::class.java)

        bindSetting(R.id.btnFreeRam, Settings.ACTION_DREAM_SETTINGS)
        findViewById<Button>(R.id.btnAnim).setOnClickListener { cycleAnimations() }
        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }

        bindSetting(R.id.setStorage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        bindSetting(R.id.setApps, Settings.ACTION_APPLICATION_SETTINGS)
        bindSetting(R.id.setNetwork, Settings.ACTION_WIFI_SETTINGS)
        bindSetting(R.id.setDev, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        bindSetting(R.id.setHome, Settings.ACTION_HOME_SETTINGS)
        bindSetting(R.id.setAll, Settings.ACTION_SETTINGS)

        updateAnimLabel()
        findViewById<Button>(R.id.btnFreeRam).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        updateAnimLabel()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    // ---------- Monitor ----------

    private fun updateStats() {
        findViewById<TextView>(R.id.clock).text =
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val usedPct = ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt()
        findViewById<TextView>(R.id.ram).apply {
            text = "${mb(mi.availMem)} volno"
            setTextColor(getColor(if (mi.lowMemory) R.color.bad else R.color.text))
        }
        findViewById<TextView>(R.id.ramDetail).text = "z ${mb(mi.totalMem)} · použito $usedPct %"
        findViewById<ProgressBar>(R.id.ramBar).progress = usedPct

        val st = StatFs(Environment.getDataDirectory().path)
        val stUsedPct = ((st.totalBytes - st.availableBytes) * 100 / st.totalBytes).toInt()
        findViewById<TextView>(R.id.storage).text = "${gb(st.availableBytes)} volno"
        findViewById<TextView>(R.id.storageDetail).text = "z ${gb(st.totalBytes)} · použito $stUsedPct %"
        findViewById<ProgressBar>(R.id.storageBar).progress = stUsedPct

        // Teplota a /proc/loadavg bývají pro běžné aplikace zakázané (SELinux), proto jen když jdou přečíst.
        val temp = readFirstLine("/sys/class/thermal/thermal_zone0/temp")?.toFloatOrNull()
        val load = readFirstLine("/proc/loadavg")?.split(" ")?.firstOrNull()
        findViewById<TextView>(R.id.cpu).text = cpuFreqText().ifEmpty { "—" }
        findViewById<TextView>(R.id.cpuDetail).text = listOfNotNull(
            "${Runtime.getRuntime().availableProcessors()} jádra",
            temp?.let { String.format(Locale.US, "%.0f °C", if (it > 1000) it / 1000 else it) },
            load?.let { "zátěž $it" }
        ).joinToString(" · ")

        val up = SystemClock.elapsedRealtime() / 1000
        findViewById<TextView>(R.id.uptime).text =
            if (up >= 86400) "${up / 86400} d ${(up % 86400) / 3600} h" else "${up / 3600} h ${(up % 3600) / 60} min"
        findViewById<TextView>(R.id.ip).text = ipAddress() ?: "bez sítě"
    }

    private fun cpuFreqText(): String {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull {
            readFirstLine("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.toLongOrNull()
        }
        return if (freqs.isEmpty()) "" else "${freqs.max() / 1000} MHz"
    }

    private fun ipAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }

    // Pozn.: „Uvolnit RAM“ přes killBackgroundProcesses() na Androidu 14 nefunguje –
    // API smí ukončit jen procesy vlastní aplikace. Ukončení cizích procesů, čištění cache
    // a restart dělá PC skript scripts/tv.ps1 přes adb.

    // ---------- Animace ----------

    private val animKeys = listOf(
        Settings.Global.WINDOW_ANIMATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.ANIMATOR_DURATION_SCALE
    )

    private fun currentAnim(): Float =
        Settings.Global.getFloat(contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)

    private fun updateAnimLabel() {
        val v = currentAnim()
        val label = when {
            v == 0f -> "vypnuto"
            else -> "${trimFloat(v)}×"
        }
        findViewById<Button>(R.id.btnAnim).text = "${getString(R.string.animations)}: $label"
    }

    private fun cycleAnimations() {
        val granted = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AlertDialog.Builder(this)
                .setTitle("Chybí oprávnění")
                .setMessage(
                    "Přepínání animací potřebuje oprávnění, které jde udělit jen z počítače:\n\n" +
                        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n\n" +
                        "Bez něj můžeš animace měnit v Nastavení → Pro vývojáře."
                )
                .setPositiveButton("Otevřít Pro vývojáře") { _, _ ->
                    openSetting(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
                .setNegativeButton("Zavřít", null)
                .show()
            return
        }
        val next = when (currentAnim()) {
            1f -> 0.5f
            0.5f -> 0f
            else -> 1f
        }
        animKeys.forEach { Settings.Global.putFloat(contentResolver, it, next) }
        updateAnimLabel()
        status("Animace nastaveny na ${if (next == 0f) "vypnuto" else trimFloat(next) + "×"}", R.color.ok)
    }

    // ---------- Nastavení ----------

    private fun bindSetting(id: Int, action: String) {
        findViewById<Button>(id).setOnClickListener { openSetting(action) }
    }

    private fun openSetting(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                status("Tuto obrazovku nastavení box nemá", R.color.warn)
            }
        }
    }

    // ---------- Pomocné ----------

    private fun status(text: String, color: Int) {
        findViewById<TextView>(R.id.status).apply {
            this.text = text
            setTextColor(getColor(color))
        }
    }

    companion object {
        private const val REFRESH_MS = 2000L

        fun mb(b: Long) = "${b / (1024 * 1024)} MB"
        fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1024.0 / 1024 / 1024)
        fun trimFloat(f: Float) = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

        fun readFirstLine(path: String): String? =
            try { File(path).bufferedReader().use { it.readLine()?.trim() } } catch (_: Exception) { null }
    }
}
