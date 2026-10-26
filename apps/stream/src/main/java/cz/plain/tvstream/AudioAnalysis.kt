package cz.plain.tvstream

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Zachytává PCM zvuk, který přehrávač posílá do zvukového výstupu (jen kopie do kruhového bufferu,
 * nic se nenahrává ani neposílá). Při přenosu zvuku bez dekódování (AC3 passthrough) data nejsou:
 * vizualizér pak ukáže ambientní animaci, a výslovně jako neanalyzovanou.
 */
@OptIn(UnstableApi::class)
class AudioTap : TeeAudioProcessor.AudioBufferSink {
    private val ring = FloatArray(RING)
    @Volatile var written = 0L
        private set
    @Volatile var sampleRate = 44100
        private set
    @Volatile var channels = 2
        private set
    @Volatile var encoding = C.ENCODING_INVALID
        private set
    @Volatile var lastWriteAt = 0L
        private set

    val supported: Boolean get() = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRateHz
        this.channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!supported) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels
        var w = written
        if (encoding == C.ENCODING_PCM_16BIT) {
            val frames = b.remaining() / (2 * ch)
            for (f in 0 until frames) {
                var s = 0
                for (c in 0 until ch) s += b.short.toInt()
                ring[(w and (RING - 1).toLong()).toInt()] = s / (32768f * ch)
                w++
            }
        } else {
            val frames = b.remaining() / (4 * ch)
            for (f in 0 until frames) {
                var s = 0f
                for (c in 0 until ch) s += b.float
                ring[(w and (RING - 1).toLong()).toInt()] = s / ch
                w++
            }
        }
        written = w
        lastWriteAt = SystemClock.elapsedRealtime()
    }

    /** Zkopíruje posledních [n] vzorků (mono, −1…1) v pořadí. Vrací false, když ještě není dost dat. */
    fun snapshot(out: FloatArray, n: Int): Boolean {
        val w = written
        if (w < n) return false
        val start = w - n
        for (i in 0 until n) out[i] = ring[((start + i) and (RING - 1).toLong()).toInt()]
        return true
    }

    companion object { const val RING = 8192 }
}

/** Jeden snímek vizualizace. [real] = z opravdového zvuku; false = ambientní animace bez analýzy. */
class VizFrame(val bands: FloatArray, val wave: FloatArray, val rms: Float, val bass: Float, val real: Boolean)

/**
 * Analýza zvuku mimo UI vlákno: FFT 1024 bodů při cílové snímkové frekvenci, vyhlazení (rychlý nástup,
 * pomalý pokles). Spouští se jen dokud je vizualizér vidět.
 */
class Analyzer(private val tap: AudioTap) {
    companion object { const val BANDS = 48; const val WAVE = 96 }

    @Volatile var frame: VizFrame = VizFrame(FloatArray(BANDS), FloatArray(WAVE), 0f, 0f, false)
        private set
    /** „fft“ = skutečná analýza, „ambient“ = nejsou data (passthrough, ticho před startem). */
    @Volatile var mode = "ambient"
        private set
    @Volatile var fps = 30

    private val fft = Fft(1024)
    private val samples = FloatArray(1024)
    private val spec = FloatArray(512)
    private val raw = FloatArray(BANDS)
    private val smooth = FloatArray(BANDS)
    private val wave = FloatArray(WAVE)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false
    private var phase = 0.0

    @Synchronized fun start() {
        if (running) return
        running = true
        val t = HandlerThread("viz-analyzer", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
    }

    @Synchronized fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null; handler = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t0 = SystemClock.elapsedRealtime()
            compute()
            val wait = (1000L / fps.coerceIn(5, 60)) - (SystemClock.elapsedRealtime() - t0)
            handler?.postDelayed(this, wait.coerceAtLeast(4))
        }
    }

    private fun compute() {
        val fresh = SystemClock.elapsedRealtime() - tap.lastWriteAt < 1200
        val ok = fresh && tap.supported && tap.snapshot(samples, 1024)
        if (ok) {
            mode = "fft"
            fft.spectrum(samples, spec)
            Fft.bands(spec, tap.sampleRate, BANDS, raw)
            for (i in 0 until BANDS) smooth[i] = if (raw[i] > smooth[i]) smooth[i] + (raw[i] - smooth[i]) * 0.65f else smooth[i] + (raw[i] - smooth[i]) * 0.14f
            // průběh: 96 bodů z posledních vzorků, vyhlazený
            val step = 1024 / WAVE
            for (i in 0 until WAVE) { var s = 0f; for (k in 0 until step) s += samples[i * step + k]; wave[i] += (s / step * 3f - wave[i]) * 0.5f }
            val bass = (smooth[0] + smooth[1] + smooth[2] + smooth[3]) / 4f
            frame = VizFrame(smooth.copyOf(), wave.copyOf(), Fft.rms(samples, 1024).let { (it * 3.2f).coerceIn(0f, 1f) }, bass, true)
        } else {
            // Žádná data (passthrough, ticho, start): pomalá ambientní animace, výslovně neanalyzovaná.
            mode = "ambient"
            phase += 0.03
            val b = FloatArray(BANDS) { i -> (0.18f + 0.12f * sin(phase * 1.3 + i * 0.35).toFloat() + 0.06f * sin(phase * 0.7 + i * 0.9).toFloat()).coerceIn(0f, 1f) }
            for (i in 0 until BANDS) smooth[i] += (b[i] - smooth[i]) * 0.1f
            for (i in 0 until WAVE) wave[i] = (0.25f * sin(phase + i * 0.14)).toFloat()
            frame = VizFrame(smooth.copyOf(), wave.copyOf(), 0.25f + 0.1f * sin(phase).toFloat(), 0.3f + 0.1f * sin(phase * 0.6).toFloat(), false)
        }
    }
}

/** Určuje, jak moc se má vizualizér šetřit (podle skutečné doby kreslení). 3 = vše, 0 = minimum. */
class QualityGovernor(private val setting: () -> String) {
    private var level = 3
    private var slow = 0
    private var fast = 0

    fun current(): Int = when (setting()) {
        "high" -> 3
        "balanced" -> 2
        "low" -> 1
        "off" -> 0
        else -> level
    }

    /** Po každém nakresleném snímku (ms): při zpoždění se kvalita sníží, po delší době v pohodě zase zvýší. */
    fun frameDrawn(ms: Long, targetMs: Long) {
        if (setting() != "auto") return
        if (ms > targetMs * 0.8) { fast = 0; if (++slow >= 20 && level > 1) { level--; slow = 0 } }
        else if (ms < targetMs * 0.35) { slow = 0; if (++fast >= 240 && level < 3) { level++; fast = 0 } }
        else { slow = 0; fast = 0 }
    }
}
