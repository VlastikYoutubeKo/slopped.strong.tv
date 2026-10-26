package cz.plain.tvstream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vizualizér rádia. Kreslí z [Analyzer.frame] (skutečné FFT zvuku, který jde do reproduktorů). Když analýza není
 * k dispozici, ukáže pomalou ambientní animaci (viz [VizFrame.real]) – nikdy nepředstírá, že reaguje na hudbu.
 *
 * Režimy: orb | bars | wave | off. Kvalita se sama snižuje podle skutečné doby kreslení ([QualityGovernor]);
 * žádný blur, shader se vytváří jen při změně velikosti nebo barvy, ve snímku se nealokuje (kromě Path u vlny, ta se jen resetuje).
 */
class VisualizerView(ctx: Context) : View(ctx) {
    var mode = "orb"
        set(v) { field = v; invalidate() }
    /** Poloměr obalu v px (orb ho obklopuje); 0 = odvodit z velikosti plochy. */
    var coreRadius = 0f
    /** Střed orbu jako zlomek šířky (artwork sedí uprostřed orbu). */
    var centerFx = 0.5f

    private val gov = QualityGovernor { Settings.vizQuality }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private var running = false
    private var glow: RadialGradient? = null
    private var glowKey = 0L
    private var lastFps = 30

    fun start() { running = true; invalidate() }
    fun stop() { running = false }

    /** Cílová snímková frekvence podle kvality a nastavení; 0 = nekreslit animaci. */
    private fun targetFps(): Int {
        val lvl = gov.current()
        if (lvl == 0 || mode == "off") return 0
        val cap = when (lvl) { 3 -> 30; 2 -> 24; else -> 15 }
        val user = Settings.vizFps
        return if (user > 0) min(user, if (lvl == 3) 60 else cap) else cap
    }

    override fun onDraw(c: Canvas) {
        val t0 = SystemClock.elapsedRealtime()
        val fps = targetFps()
        val f = Playback.analyzer.frame
        if (fps != lastFps && fps > 0) { Playback.analyzer.fps = fps; lastFps = fps }
        if (fps > 0 || mode != "off") when (mode) {
            "orb" -> drawOrb(c, f, gov.current())
            "bars" -> drawBars(c, f, gov.current())
            "wave" -> drawWave(c, f, gov.current())
        }
        val spent = SystemClock.elapsedRealtime() - t0
        if (fps > 0) gov.frameDrawn(spent, 1000L / fps)
        // Snížený pohyb: jeden klidný snímek, žádná smyčka.
        if (running && fps > 0 && !Design.reducedMotion) postInvalidateDelayed((1000L / fps - spent).coerceAtLeast(1))
        else if (running && fps > 0) postInvalidateDelayed(1500)
    }

    // ---------------------------------------------------------------- orb

    private fun glowShader(unit: Float, accent: Int): RadialGradient {
        val key = (unit.toLong() shl 32) xor accent.toLong()
        glow?.let { if (key == glowKey) return it }
        glowKey = key
        return RadialGradient(0f, 0f, unit, intArrayOf(Design.withAlpha(accent, 0.55f), Design.withAlpha(accent, 0.18f), Design.withAlpha(accent, 0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP).also { glow = it }
    }

    private fun drawOrb(c: Canvas, f: VizFrame, level: Int) {
        val cx = width * centerFx; val cy = height * 0.5f
        val base = if (coreRadius > 0f) coreRadius else min(width * 0.45f, height.toFloat()) * 0.27f
        val pulse = 1f + 0.22f * f.bass + 0.1f * f.rms
        val accent = Design.accent
        // záře: jeden předpočítaný gradient, jen se škáluje
        c.save(); c.translate(cx, cy); c.scale(base * 1.9f * pulse / base, base * 1.9f * pulse / base)
        paint.shader = glowShader(base, accent); paint.style = Paint.Style.FILL
        c.drawCircle(0f, 0f, base, paint)
        paint.shader = null
        c.restore()
        // kruh pod artworkem
        paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 2f
        paint.color = Design.withAlpha(accent, 0.55f)
        c.drawCircle(cx, cy, base * 1.06f * (1f + 0.04f * f.bass), paint)
        if (level >= 2) {
            // paprsky kolem: každé pásmo jedna čárka
            val n = if (level == 3) Analyzer.BANDS else Analyzer.BANDS / 2
            val step = Analyzer.BANDS / n
            paint.strokeWidth = resources.displayMetrics.density * (if (level == 3) 3f else 4.5f); paint.strokeCap = Paint.Cap.ROUND
            val r0 = base * 1.2f
            for (i in 0 until n) {
                var v = 0f; for (k in 0 until step) v += f.bands[i * step + k]; v /= step
                val a = (i.toFloat() / n) * 2f * PI.toFloat() - PI.toFloat() / 2
                val len = base * (0.05f + 0.40f * v)
                paint.color = Design.withAlpha(accent, 0.35f + 0.55f * v)
                c.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * (r0 + len), cy + sin(a) * (r0 + len), paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    // ---------------------------------------------------------------- spektrum

    private fun drawBars(c: Canvas, f: VizFrame, level: Int) {
        val n = when (level) { 3 -> 48; 2 -> 32; else -> 16 }
        val step = Analyzer.BANDS.toFloat() / n
        val d = resources.displayMetrics.density
        val area = width - d * 128
        val gap = d * 4
        val bw = (area - gap * (n - 1)) / n
        val baseY = height * 0.94f
        val maxH = height * 0.20f
        paint.style = Paint.Style.FILL
        for (i in 0 until n) {
            var v = 0f; val a = (i * step).toInt(); val b = ((i + 1) * step).toInt().coerceAtMost(Analyzer.BANDS)
            for (k in a until b) v += f.bands[k]; v /= (b - a).coerceAtLeast(1)
            val h = d * 4 + maxH * v.coerceIn(0f, 1f)
            val x = d * 64 + i * (bw + gap)
            paint.color = Design.withAlpha(Design.accent, 0.35f + 0.6f * v)
            rect.set(x, baseY - h, x + bw, baseY)
            c.drawRoundRect(rect, bw / 2, bw / 2, paint)
        }
    }

    // ---------------------------------------------------------------- vlna

    private fun drawWave(c: Canvas, f: VizFrame, level: Int) {
        val d = resources.displayMetrics.density
        val left = d * 64; val w = width - d * 128
        val midY = height * 0.86f; val amp = height * 0.09f
        val n = if (level == 3) Analyzer.WAVE else Analyzer.WAVE / 2
        val step = Analyzer.WAVE / n
        path.reset()
        for (i in 0 until n) {
            val x = left + w * i / (n - 1)
            val y = midY - f.wave[i * step].coerceIn(-1f, 1f) * amp
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
        if (level == 3) { paint.strokeWidth = d * 8; paint.color = Design.withAlpha(Design.accent, 0.16f); c.drawPath(path, paint) }
        paint.strokeWidth = d * 3; paint.color = Design.accent
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
