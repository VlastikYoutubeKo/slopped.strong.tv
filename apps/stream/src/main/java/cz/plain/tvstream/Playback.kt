package cz.plain.tvstream

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.metadata.icy.IcyHeaders
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Co se přehrává. [resolve] složí adresu (u Stalkeru jde do sítě, proto se volá mimo hlavní vlákno). */
class PlayItem(
    val key: String,
    val src: Source,
    val title: String,
    val subtitle: String? = null,
    val logo: String? = null,
    val kind: ChannelKind = ChannelKind.TV,
    val live: Boolean = true,
    val liveItem: LiveItem? = null,
    val resumeMs: Long = 0,
    /** Průběžné ukládání pozice u filmů a epizod: (pozice, délka). */
    val onProgress: ((Long, Long) -> Unit)? = null,
    val resolve: () -> String,
)

/**
 * Jediný přehrávač celé aplikace (živé vysílání, rádio, filmy). Díky tomu rádio hraje dál při procházení
 * a přepnutí rádio ⇄ video neznamená restart streamu. Podrobnosti: docs/RADIO_METADATA.md.
 */
@OptIn(UnstableApi::class)
object Playback {
    enum class Mode { UNKNOWN, VIDEO, AUDIO }
    enum class State { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, ERROR }

    open class Listener {
        open fun onItem(item: PlayItem?) {}
        open fun onMode(mode: Mode) {}
        open fun onState(state: State, error: String?) {}
        open fun onNowPlaying(np: NowPlaying) {}
        open fun onDiagnostics() {}
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val listeners = CopyOnWriteArrayList<Listener>()

    val tap = AudioTap()
    val analyzer = Analyzer(tap)

    @Volatile var item: PlayItem? = null; private set
    @Volatile var mode = Mode.UNKNOWN; private set
    @Volatile var state = State.IDLE; private set
    @Volatile var error: String? = null; private set
    @Volatile var manualAudio = false; private set
    /** Seznam, po kterém se přepíná ▲▼ / CH± (televize nebo rádio), a pozice v něm. */
    @Volatile var queue: List<LiveItem> = emptyList()
    @Volatile var queueIndex = -1
    var tracker = NowPlayingTracker("", ""); private set
    val nowPlaying: NowPlaying get() = tracker.current

    // diagnostika
    @Volatile var codec: String? = null; private set
    @Volatile var bitrate = 0; private set
    @Volatile var icyBitrate = 0; private set
    @Volatile var icyName: String? = null; private set
    @Volatile var icyGenre: String? = null; private set
    @Volatile var hasIcyHeaders = false; private set
    @Volatile var metaTransport = "žádný"; private set
    @Volatile var lastMetaAt = 0L; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var videoFormat: String? = null; private set

    private var variant = 0
    /** Určení typu obsahu: 0 = podle adresy, 1 = vynutit prostý proud (zvuk/video), 2 = vynutit HLS. Mění se jen po chybě rozboru. */
    private var typeGuess = 0
    private var retries = 0
    private var audioTimer: Runnable? = null
    private var gen = 0

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    @Volatile private var created = false

    val player: ExoPlayer by lazy {
        created = true
        val factory = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(tap)))
                    .build()
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // Menší buffer než výchozích 50 s: box má jen 2 GB RAM.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 25_000, 1_500, 3_000).build()
        ExoPlayer.Builder(app, factory).setLoadControl(load)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().also { it.addListener(playerListener) }
    }

    // ---------------------------------------------------------------- ovládání

    fun play(it: PlayItem) {
        val my = ++gen
        stopInternal(keepItem = false)
        item = it
        variant = 0; typeGuess = 0; retries = 0; manualAudio = false
        mode = Mode.UNKNOWN; error = null; lastError = null
        codec = null; bitrate = 0; icyBitrate = 0; icyName = null; icyGenre = null; hasIcyHeaders = false; metaTransport = "žádný"; lastMetaAt = 0
        tracker = NowPlayingTracker(it.key, it.title)
        setState(State.LOADING)
        listeners.forEach { l -> l.onItem(it); l.onMode(mode); l.onNowPlaying(tracker.current) }
        it.liveItem?.let { li -> UserData.played(li.key, if (it.kind == ChannelKind.RADIO) "radio" else "live") }
        io.execute {
            val url = try { it.resolve() } catch (e: Exception) { main.post { if (my == gen) fail(SourceForm.errorText(e)) }; return@execute }
            main.post { if (my == gen) startUrl(url) }
        }
    }

    private fun startUrl(baseUrl: String) {
        val it = item ?: return
        val url = urlVariant(it, baseUrl)
        val ds = OkHttpDataSource.Factory(Library.client(it.src))
            .setUserAgent(Net.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))   // server s ICY pošle i titulky skladeb
        // Rychlejší start u živého MPEG-TS (TVHeadend „pass“, Xtream .ts).
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val mi = MediaItem.Builder().setUri(url).apply {
            // Přípona adresy někdy lže (např. „.m3u8“ vede na internetové rádio): po chybě rozboru se typ určí jinak.
            when (typeGuess) { 1 -> setMimeType(androidx.media3.common.MimeTypes.AUDIO_MPEG); 2 -> setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
        }.build()
        val src = DefaultMediaSourceFactory(ds, extractors).createMediaSource(mi)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
        player.setMediaSource(src, if (it.live) C.TIME_UNSET else it.resumeMs)
        player.prepare()
        player.playWhenReady = true
        applyAnalysis()
    }

    /** TVHeadend: profil „pass“ smí jen někteří uživatelé; záložní pokusy bez profilu a s „matroska“. */
    private fun urlVariant(it: PlayItem, url: String): String {
        if (it.src.type != SourceType.TVHEADEND) return url
        val base = url.substringBefore('?')
        return when (variant) { 0 -> url; 1 -> base; else -> "$base?profile=matroska" }
    }

    fun stop() { gen++; stopInternal(keepItem = false); listeners.forEach { it.onItem(null) } }

    private fun stopInternal(keepItem: Boolean) {
        cancelAudioTimer()
        item?.let { saveProgress(it) }
        if (created) { player.stop(); player.clearMediaItems() }
        analyzer.stop()
        if (!keepItem) item = null
        mode = Mode.UNKNOWN
        setState(State.IDLE)
    }

    fun togglePause() { if (player.isPlaying || player.playWhenReady) player.pause() else player.play() }

    /** Vynutí režim jen zvuk (a vypne dekódování videa), nebo ho vrátí. */
    fun forceAudio(on: Boolean) {
        manualAudio = on
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, on).build()
        setMode(if (on) Mode.AUDIO else Mode.UNKNOWN)
        if (!on) scheduleAudioDecision()
    }

    // ---------------------------------------------------------------- rozpoznání „jen zvuk“

    private fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        applyAnalysis()
        listeners.forEach { it.onMode(m) }
    }

    /** Vizualizér se krmí zvukem jen v režimu zvuk (nebo když ho UI výslovně chce). */
    private fun applyAnalysis() { if (mode == Mode.AUDIO) analyzer.start() else analyzer.stop() }

    private fun cancelAudioTimer() { audioTimer?.let { main.removeCallbacks(it) }; audioTimer = null }

    /**
     * Rozhodnutí videa/zvuku: nikdy podle přípony adresy. Jakmile přehrávač ohlásí stopy, ukazuje-li
     * video, je to video. Bez videa se krátce počká (rádio 0,7 s, televize 2,5 s – video stopa se
     * někdy objeví se zpožděním), pak je to zvuk. Pozdní video přepne zpět bez restartu streamu.
     */
    private fun scheduleAudioDecision() {
        cancelAudioTimer()
        if (mode == Mode.VIDEO || manualAudio) return
        val wait = if (item?.kind == ChannelKind.RADIO) 700L else 2500L
        val r = Runnable { if (mode != Mode.VIDEO && state != State.IDLE && state != State.ERROR && Settings.audioOnlyAuto) setMode(Mode.AUDIO) }
        audioTimer = r
        main.postDelayed(r, wait)
    }

    // ---------------------------------------------------------------- události přehrávače

    private val playerListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val video = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 && it.isSupported(false) }
            val audioFmt = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)
            audioFmt?.let { f -> codec = f.sampleMimeType?.substringAfter('/')?.uppercase(); bitrate = if (f.averageBitrate > 0) f.averageBitrate else f.bitrate.coerceAtLeast(0) }
            tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.let { f ->
                videoFormat = "${f.sampleMimeType?.substringAfter('/')?.uppercase() ?: "?"} ${if (f.width > 0) "${f.width}×${f.height}" else ""}".trim()
            }
            if (video && !manualAudio) { cancelAudioTimer(); setMode(Mode.VIDEO) }
            else if (!video && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO } && mode != Mode.AUDIO) scheduleAudioDecision()
            listeners.forEach { it.onDiagnostics() }
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && !manualAudio) { cancelAudioTimer(); setMode(Mode.VIDEO) }
        }

        override fun onPlaybackStateChanged(s: Int) {
            when (s) {
                Player.STATE_BUFFERING -> setState(State.BUFFERING)
                Player.STATE_READY -> { retries = 0; setState(if (player.playWhenReady) State.PLAYING else State.PAUSED) }
                Player.STATE_ENDED -> setState(State.IDLE)
                else -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (state != State.BUFFERING && state != State.ERROR && state != State.LOADING) setState(if (isPlaying) State.PLAYING else State.PAUSED)
        }

        override fun onMetadata(metadata: Metadata) {
            var changed = false
            val now = System.currentTimeMillis()
            for (i in 0 until metadata.length()) {
                when (val e = metadata[i]) {
                    is IcyInfo -> { metaTransport = "ICY (v proudu)"; lastMetaAt = now; changed = tracker.onIcy(e.title, now) || changed }
                    is IcyHeaders -> { hasIcyHeaders = true; icyName = e.name; icyGenre = e.genre; icyBitrate = e.bitrate.coerceAtLeast(0) }
                    is TextInformationFrame -> {
                        val v = e.values.firstOrNull()
                        when (e.id) {
                            "TIT2" -> { id3.title = v }
                            "TPE1" -> { id3.artist = v }
                            "TALB" -> { id3.album = v }
                        }
                        if (e.id == "TIT2" || e.id == "TPE1") { metaTransport = "ID3 (HLS / MP3)"; lastMetaAt = now; changed = tracker.onId3(id3.title, id3.artist, id3.album, now) || changed }
                    }
                }
            }
            if (changed) { afterMetaChange(); listeners.forEach { it.onNowPlaying(tracker.current) } }
            listeners.forEach { it.onDiagnostics() }
        }

        override fun onPlayerError(e: PlaybackException) = handleError(e)
    }

    private class Id3 { var title: String? = null; var artist: String? = null; var album: String? = null }
    private val id3 = Id3()

    private fun afterMetaChange() {
        val np = tracker.current
        if (Settings.coverLookup && np.hasTrack && np.artist != null) {
            CoverLookup.find(np.artist, np.trackTitle!!) { url ->
                main.post { if (tracker.current.trackTitle == np.trackTitle && tracker.onArtwork(url)) listeners.forEach { it.onNowPlaying(tracker.current) } }
            }
        }
    }

    /** Pořad z EPG pro stanici, která nehlásí skladby. Nikdy nepřepíše skladbu. */
    fun setProgram(title: String?) { if (tracker.onProgram(title)) listeners.forEach { it.onNowPlaying(tracker.current) } }

    // ---------------------------------------------------------------- chyby

    private fun httpCode(e: Throwable?): Int? {
        var t = e
        while (t != null) { if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode; t = t.cause }
        return null
    }

    private fun httpReason(code: Int) = when (code) {
        401 -> "Server odmítl přihlášení (401)"
        403 -> "Uživatel nemá oprávnění ke streamování (403). V TVHeadendu zapni právo „Streaming“ a povol profil."
        404 -> if (item?.live == false) "Soubor na serveru neexistuje (404)" else "Kanál nebo profil na serveru neexistuje (404)"
        503 -> "Server nemá volný tuner nebo je vyčerpaný limit připojení (503)"
        else -> "Server odmítl stream (HTTP $code)"
    }

    private fun handleError(e: PlaybackException) {
        val it = item ?: return
        val code = httpCode(e)
        lastError = "${e.errorCodeName}${code?.let { c -> " (HTTP $c)" } ?: ""}"
        // Špatně odhadnutý typ (adresa „.m3u8“ je ve skutečnosti MP3/AAC proud, nebo naopak): zkusit druhý typ.
        if (typeGuess < 2 && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            typeGuess = if (typeGuess == 0) 1 else 2
            setState(State.LOADING, "Zkouším jiný typ proudu…")
            val my = gen
            io.execute { val u = try { it.resolve() } catch (_: Exception) { return@execute }; main.post { if (my == gen) startUrl(u) } }
            return
        }
        // Odmítnutý profil (TVHeadend): hned zkusit další variantu adresy.
        if (code != null && it.src.type == SourceType.TVHEADEND && code in listOf(400, 403, 404, 406, 500, 503) && variant < 2) {
            variant++
            setState(State.LOADING, "Server odmítl profil (HTTP $code), zkouším jiný…")
            val my = gen
            io.execute { val u = try { it.resolve() } catch (_: Exception) { return@execute }; main.postDelayed({ if (my == gen) startUrl(u) }, 400) }
            return
        }
        // Živé streamy občas vypadnou: 3× znovu s prodlevou. Reconnect: stará skladba už nemusí platit.
        if (retries < 3 && (code == null || code >= 500)) {
            retries++
            tracker.reset(); listeners.forEach { l -> l.onNowPlaying(tracker.current) }
            setState(State.LOADING, "Výpadek, zkouším znovu ($retries/3)…")
            val my = gen
            main.postDelayed({ if (my == gen) { player.prepare(); player.playWhenReady = true } }, 2000L * retries)
            return
        }
        fail(when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpReason(code ?: 0)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Nelze se připojit k serveru"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "Tento formát box neumí dekódovat"
            else -> "Chyba přehrávání (${e.errorCodeName})"
        })
    }

    private fun fail(msg: String) { error = msg; setState(State.ERROR, msg) }

    private fun setState(s: State, msg: String? = null) {
        state = s
        if (s != State.ERROR) error = msg   // průběžná zpráva (např. „Výpadek, zkouším znovu“) se ukáže, ale chybou není
        listeners.forEach { it.onState(s, msg ?: error) }
    }

    // ---------------------------------------------------------------- pozice filmů

    private fun saveProgress(it: PlayItem) {
        val cb = it.onProgress ?: return
        if (!created) return
        val d = player.duration
        if (d > 0 && player.currentPosition > 0) cb(player.currentPosition, d)
    }

    /** Volá aktivita přehrávače jednou za pár sekund (film/epizoda). */
    fun tickProgress() { item?.let { saveProgress(it) } }

    fun diagnostics(): List<Pair<String, String>> {
        val it = item
        val age = if (lastMetaAt > 0) "před ${(System.currentTimeMillis() - lastMetaAt) / 1000} s" else "dosud žádná"
        return listOf(
            "Zdroj" to (it?.src?.host ?: "–"),
            "Režim" to when (mode) { Mode.AUDIO -> if (manualAudio) "zvuk (ručně)" else "zvuk"; Mode.VIDEO -> "video"; else -> "zjišťuje se" },
            "Metadata" to metaTransport,
            "Poslední titulek" to age,
            "ICY hlavičky" to if (hasIcyHeaders) "ano${icyName?.let { n -> " · $n" } ?: ""}${icyGenre?.let { g -> " · $g" } ?: ""}" else "ne",
            "Kodek" to (codec ?: "–"),
            "Datový tok" to (if (bitrate > 0) "${bitrate / 1000} kb/s" else if (icyBitrate > 0) "${icyBitrate / 1000} kb/s (ICY)" else "–"),
            "Video" to (videoFormat ?: "–"),
            "Analýza zvuku" to if (analyzer.mode == "fft") "skutečné FFT (${tap.sampleRate / 1000} kHz)" else "ambientní animace (bez analýzy)",
            "Chyba" to (lastError ?: "–"),
        )
    }
}
