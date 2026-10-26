package cz.plain.odjezdytv

import android.service.dreams.DreamService

/**
 * Spořič obrazovky: tabule Odjezdy OS s naposledy vybranou zastávkou a motivem
 * (bez úvodního menu). Jakékoli tlačítko spořič ukončí.
 */
class BoardDream : DreamService() {

    private var host: BoardHost? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        host = BoardHost(this, listOf("--no-menu")) { finish() }.also { setContentView(it.view) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        host?.resume()
    }

    override fun onDreamingStopped() {
        host?.pause()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        host?.destroy()
        host = null
        super.onDetachedFromWindow()
    }
}
