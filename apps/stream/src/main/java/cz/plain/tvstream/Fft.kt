package cz.plain.tvstream

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFT radix-2 (in-place) a převod na pásma pro vizualizér. Čistá logika bez Androidu (testovaná).
 * Běží mimo UI vlákno (viz Analyzer).
 */
class Fft(val size: Int = 1024) {
    init { require(size >= 8 && size and (size - 1) == 0) { "size musí být mocnina dvou" } }

    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { -sin(2 * PI * it / size).toFloat() }
    private val rev = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val hann = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / (size - 1))).toFloat() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)

    /** Amplitudové spektrum prvních size/2 binů (0…1 pro plný sinus). [samples] musí mít aspoň [size] hodnot. */
    fun spectrum(samples: FloatArray, out: FloatArray) {
        for (i in 0 until size) { re[rev[i]] = samples[i] * hann[i]; im[rev[i]] = 0f }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        val norm = 4f / size   // Hann má průměr 0,5 a spektrum je jednostranné: sinus o amplitudě 1 → ≈ 1
        for (i in 0 until min(out.size, size / 2)) out[i] = hypot(re[i], im[i]) * norm
    }

    companion object {
        /**
         * Logaritmická pásma od [lowHz] do [highHz]; každé pásmo = největší amplituda v něm, převedená na 0…1
         * (decibelová škála −70…0 dB, ať tiché pasáže nezmizí a hlasité nepřeřídí).
         */
        fun bands(spec: FloatArray, sampleRate: Int, count: Int, out: FloatArray, lowHz: Float = 40f, highHz: Float = 14000f) {
            val binHz = sampleRate.toFloat() / (spec.size * 2)
            val hi = min(highHz, sampleRate / 2f - binHz)
            for (b in 0 until count) {
                val f0 = lowHz * (hi / lowHz).pow(b.toFloat() / count)
                val f1 = lowHz * (hi / lowHz).pow((b + 1f) / count)
                val i0 = max(1, (f0 / binHz).toInt())
                val i1 = min(spec.size - 1, max(i0, (f1 / binHz).toInt()))
                var m = 0f
                for (i in i0..i1) m = max(m, spec[i])
                out[b] = level(m)
            }
        }

        fun level(amp: Float): Float {
            if (amp <= 1e-6f) return 0f
            val db = 20f * (ln(amp.toDouble()) / ln(10.0)).toFloat()
            return ((db + 70f) / 70f).coerceIn(0f, 1f)
        }

        fun rms(samples: FloatArray, n: Int): Float {
            var s = 0.0
            for (i in 0 until n) s += samples[i] * samples[i]
            return sqrt(s / max(1, n)).toFloat()
        }
    }
}
