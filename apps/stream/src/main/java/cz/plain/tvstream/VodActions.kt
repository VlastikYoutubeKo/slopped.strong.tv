package cz.plain.tvstream

import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Práce na pozadí s výsledkem na hlavním vlákně (síť nikdy neběží v UI). */
object Bg {
    val io = Executors.newFixedThreadPool(3)
    val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (T?, Throwable?) -> Unit) {
        io.execute {
            var v: T? = null; var err: Throwable? = null
            try { v = work() } catch (e: Throwable) { err = e }
            main.post { done(v, err) }
        }
    }
}

/**
 * Přehrání filmů a epizod a práce s rozkoukanými. Klíče: film „vod:zdroj|id“, epizoda „ep:zdroj|seriál|epizoda“,
 * seriál (oblíbené) „sr:zdroj|id“. Pozice se ukládá jen lokálně.
 */
object VodActions {
    fun movieKey(src: Source, id: String) = "vod:${src.id}|$id"
    fun seriesKey(src: Source, id: String) = "sr:${src.id}|$id"
    fun episodeKey(src: Source, seriesId: String, epId: String) = "ep:${src.id}|$seriesId|$epId"

    private fun launch(act: MainActivity, item: PlayItem) {
        Playback.queue = emptyList(); Playback.queueIndex = -1
        Playback.play(item)
        act.startActivity(Intent(act, PlayerActivity::class.java))
    }

    fun playMovie(act: MainActivity, src: Source, item: VodItem, info: VodInfo?, fromStart: Boolean = false) {
        val key = movieKey(src, item.id)
        val resume = if (fromStart) 0L else UserData.progress(key)?.takeIf { it.resumable }?.positionMs ?: 0L
        val sub = listOfNotNull(info?.year ?: item.year, info?.genre).joinToString(" · ").ifEmpty { null }
        launch(act, PlayItem(
            key = key, src = src, title = item.name, subtitle = src.name, logo = item.poster, live = false, resumeMs = resume,
            onProgress = { p, d -> UserData.saveProgress(UserData.Progress(key, src.id, item.name, sub, item.poster, p, d, System.currentTimeMillis(), "${item.id}|${item.ext}")) },
            resolve = { Library.backend(src).vodUrl(item) },
        ))
    }

    fun playEpisode(act: MainActivity, src: Source, series: SeriesItem, ep: Episode, fromStart: Boolean = false) {
        val key = episodeKey(src, series.id, ep.id)
        val resume = if (fromStart) 0L else UserData.progress(key)?.takeIf { it.resumable }?.positionMs ?: 0L
        val label = "S${ep.season}E${ep.number} · ${ep.title}"
        launch(act, PlayItem(
            key = key, src = src, title = series.name, subtitle = label, logo = ep.poster ?: series.poster, live = false, resumeMs = resume,
            onProgress = { p, d -> UserData.saveProgress(UserData.Progress(key, src.id, series.name, label, series.poster ?: ep.poster, p, d, System.currentTimeMillis(), "${series.id}|${ep.id}|${ep.ext}|${ep.season}|${ep.number}")) },
            resolve = { Library.backend(src).episodeUrl(ep, series) },
        ))
    }

    fun recordingKey(src: Source, id: String) = "rec:${src.id}|$id"

    fun playRecording(act: MainActivity, src: Source, rec: Recording, fromStart: Boolean = false) {
        val key = recordingKey(src, rec.id)
        val resume = if (fromStart) 0L else UserData.progress(key)?.takeIf { it.resumable }?.positionMs ?: 0L
        val sub = "${rec.channelName} · ${java.text.SimpleDateFormat("d. M. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(rec.start))}"
        launch(act, PlayItem(
            key = key, src = src, title = rec.title, subtitle = sub, live = false, resumeMs = resume,
            onProgress = { p, d -> UserData.saveProgress(UserData.Progress(key, src.id, rec.title, sub, null, p, d, System.currentTimeMillis(), rec.id)) },
            resolve = { Library.backend(src).recordingUrl(rec) },
        ))
    }

    /** Pokračovat ze Domů: film nebo epizoda z uložené pozice (stačí údaje z historie, bez dotazu na server). */
    fun resume(act: MainActivity, p: UserData.Progress) {
        val src = Library.sources().firstOrNull { it.id == p.sourceId } ?: run { act.toast("Zdroj už neexistuje"); return }
        val parts = p.ref.split('|')
        if (p.key.startsWith("rec:")) {
            playRecording(act, src, Recording(p.ref, p.title, "", "", p.sub?.substringBefore(" · ") ?: "", 0, 0, "completed", 0, ""))
        } else if (p.key.startsWith("ep:")) {
            val sid = parts.getOrNull(0) ?: return
            val ep = Episode(parts.getOrNull(1) ?: return, parts.getOrNull(3)?.toIntOrNull() ?: 1, parts.getOrNull(4)?.toIntOrNull() ?: 1, p.sub ?: p.title, null, null, null, parts.getOrNull(2) ?: "mp4")
            val series = SeriesItem(sid, p.title, null, p.poster, null, null, null, null, null, 0)
            playEpisode(act, src, series, ep)
        } else {
            val item = VodItem(parts.getOrNull(0) ?: return, p.title, null, p.poster, null, null, 0, parts.getOrNull(1) ?: "mp4")
            playMovie(act, src, item, null)
        }
    }

    fun toggleFavoriteMovie(act: MainActivity, src: Source, item: VodItem): Boolean {
        val key = movieKey(src, item.id)
        val on = UserData.toggleFavorite(key)
        if (on) UserData.putFavMeta(key, UserData.FavMeta(item.name, item.poster, src.id, "${item.id}|${item.ext}"))
        act.toast(if (on) "Přidáno do oblíbených" else "Odebráno z oblíbených")
        return on
    }

    fun toggleFavoriteSeries(act: MainActivity, src: Source, item: SeriesItem): Boolean {
        val key = seriesKey(src, item.id)
        val on = UserData.toggleFavorite(key)
        if (on) UserData.putFavMeta(key, UserData.FavMeta(item.name, item.poster, src.id, item.id))
        act.toast(if (on) "Přidáno do oblíbených" else "Odebráno z oblíbených")
        return on
    }
}
