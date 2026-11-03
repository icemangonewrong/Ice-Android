package com.iceman.ice.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.iceman.ice.data.AppState
import com.iceman.ice.data.Item
import com.iceman.ice.data.Marker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Everything about what's playing. One player for the whole app (full screen or the mini player). */
object Playback {
    data class Track(val id: Int, val name: String)

    private lateinit var app: Context
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null

    // -- Android Auto: the car screen's drawing surface (video goes there while connected) --------
    /** True while Ice is open on an Android Auto screen. */
    val carConnected = MutableStateFlow(false)
    private var carSurface: android.view.Surface? = null
    private var carW = 0
    private var carH = 0

    fun attachCarSurface(surface: android.view.Surface, w: Int, h: Int) {
        carSurface = surface; carW = w; carH = h
        connectCar()
    }

    fun resizeCarSurface(w: Int, h: Int) {
        carW = w; carH = h
        try { player?.vlcVout?.setWindowSize(w, h) } catch (_: Exception) {}
    }

    fun detachCarSurface() {
        val p = player
        if (p != null && carSurface != null) try { p.vlcVout.detachViews() } catch (_: Exception) {}
        carSurface = null
        // Back to the phone's own video view, if it has one.
        layout?.let { connectedView = null; connect(it) }
    }

    private fun connectCar() {
        val p = player ?: return
        val s = carSurface ?: return
        try {
            try { p.detachViews() } catch (_: Exception) {}
            connectedView = null
            val vout = p.vlcVout
            if (vout.areViewsAttached()) vout.detachViews()
            vout.setVideoSurface(s, null)
            vout.setWindowSize(carW, carH)
            vout.attachViews()
            p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        } catch (_: Exception) {}
    }
    private var cache: StreamCache? = null
    // Subtitles start off (like Plex), whatever the file marks as default; Plex's choice or yours wins.
    private var pickedSub: Int? = null
    private var plexEmbeddedSub: Int? = null
    private var plexExternalSubUrl: String? = null
    /** The next episode's first ~20 seconds, fetched near the end of this one so it starts at once. */
    private class Preload(val key: String, val cache: StreamCache, val url: String, val item: Item, val after: Item?)
    private var preload: Preload? = null
    private var preloadPending: String? = null
    private var preloadedExtras: Preload? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private val main = Handler(Looper.getMainLooper())

    // -- state the screens watch ----------------------------------------------
    val active = MutableStateFlow(false)
    val item = MutableStateFlow<Item?>(null)
    val playing = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val bufferedSec = MutableStateFlow(0.0)
    val offline = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val nextItem = MutableStateFlow<Item?>(null)
    val skipMarker = MutableStateFlow<Marker?>(null)
    val upNextSeconds = MutableStateFlow<Int?>(null)
    val subtitleDelayMs = MutableStateFlow(0)
    val audioDevice = MutableStateFlow("Phone speaker")
    val toast = MutableStateFlow<String?>(null)
    /** true = full-screen player, false = mini player (or nothing). */
    val showFull = MutableStateFlow(false)
    val switching = MutableStateFlow(false)
    /** Picture zoom (full-screen player only) and how far it's moved when zoomed. */
    val zoom = MutableStateFlow(1f)
    val panX = MutableStateFlow(0f)
    val panY = MutableStateFlow(0f)
    private var zoomStep = 0

    private var markers: List<Marker> = emptyList()
    private var skipped = mutableSetOf<Marker>()
    private var upNextCancelled = false
    private var upNextDeadline = 0L
    private var upNextLeftMs = 10_000L
    private var lastReport = 0L
    private var hasPlayed = false
    private var wantsPlay = false
    private var pendingSubs: List<String> = emptyList()
    private var layout: VLCVideoLayout? = null
    private var finishing = false

    private const val UP_NEXT_MS = 10_000L

    fun init(context: Context) {
        app = context.applicationContext
        StreamCache.cleanUp(app.cacheDir)
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = audioRouteChanged()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = audioRouteChanged()
        }, main)
        audioDevice.value = currentOutputName()
        setupMediaSession()
    }

    // -- media session: steering-wheel / headphone / car buttons ------------------------------------
    private var session: android.support.v4.media.session.MediaSessionCompat? = null

    private fun setupMediaSession() {
        val ms = android.support.v4.media.session.MediaSessionCompat(app, "Ice")
        ms.setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
            override fun onPlay() { if (!wantsPlay) togglePause() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { previous() }
            override fun onFastForward() { skip(10) }
            override fun onRewind() { skip(-10) }
            override fun onSeekTo(pos: Long) { seekTo(pos) }
            override fun onStop() { stop() }
        })
        session = ms
        scope.launch {
            kotlinx.coroutines.flow.combine(active, playing, item) { a, p, i -> Triple(a, p, i) }.collect { (a, p, i) ->
                val S = android.support.v4.media.session.PlaybackStateCompat::class.java
                val state = android.support.v4.media.session.PlaybackStateCompat.Builder()
                    .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_FAST_FORWARD or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_REWIND or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SEEK_TO or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP)
                    .setState(when {
                        !a -> android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
                        p -> android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                        else -> android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
                    }, positionMs.value, 1f)
                    .build()
                ms.setPlaybackState(state)
                if (i != null) ms.setMetadata(android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, i.displayTitle)
                    .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, i.displaySubtitle ?: "")
                    .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, durationMs.value)
                    .build())
                ms.isActive = a
                @Suppress("UNUSED_VARIABLE") val unused = S
            }
        }
    }

    private fun vlc(): LibVLC = libVLC ?: LibVLC(app, arrayListOf(
        "--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames", "--http-reconnect",
    )).also { libVLC = it }

    // -- video surface ------------------------------------------------------------

    /** Shows the picture in this view (the full player or the mini player's thumbnail). */
    private var connectedView: VLCVideoLayout? = null

    /** The one video view for the whole app (it moves/resizes between full screen and the mini player). */
    fun attach(view: VLCVideoLayout) {
        if (layout === view) return
        layout = view
        connectedView = null
        main.post { connect(view) }
    }

    private fun connect(view: VLCVideoLayout) {
        val p = player ?: return
        if (carSurface != null) return   // the car screen has the video while connected
        if (layout !== view || connectedView === view) return
        try {
            try { p.detachViews() } catch (_: Exception) {}
            p.attachViews(view, null, true, true)
            p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            connectedView = view
        } catch (_: Exception) {}
    }

    fun detach(view: VLCVideoLayout) {
        if (layout !== view) return
        if (connectedView === view) try { player?.detachViews() } catch (_: Exception) {}
        connectedView = null
        layout = null
    }

    // -- start / stop -----------------------------------------------------------------

    fun play(target: Item, fromStart: Boolean = false) {
        error.value = null
        // In the car the video goes to the car screen; the phone just shows the mini player.
        if (!carConnected.value) showFull.value = true
        scope.launch {
            val c = AppState.client
            var t = target
            try {
                if (t.type == "show") t = c.nextEpisode(t.key) ?: run { error.value = "No episodes found."; return@launch }
                if (t.partKey == null || t.fileSize == 0L) t = c.metadata(t.key) ?: t
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't reach the Plex server."
                return@launch
            }
            start(t, fromStart)
        }
    }

    private suspend fun start(t: Item, fromStart: Boolean) {
        stopInternal(report = true, refresh = false)
        val remote = AppState.client.directUrl(t) ?: run { error.value = "Couldn't find the file for this video."; return }

        item.value = t
        nextItem.value = null
        markers = emptyList()
        skipped.clear()
        skipMarker.value = null
        upNextSeconds.value = null
        upNextCancelled = false
        upNextDeadline = 0
        upNextLeftMs = UP_NEXT_MS
        subtitleDelayMs.value = 0
        pickedSub = null
        plexEmbeddedSub = null
        plexExternalSubUrl = null
        durationMs.value = t.durationMs
        var resume = if (fromStart) 0L else t.offsetMs
        if (t.durationMs > 0 && resume > t.durationMs - 120_000) resume = 0
        positionMs.value = resume
        hasPlayed = false
        finishing = false
        active.value = true
        buffering.value = true
        switching.value = false
        wantsPlay = true

        // Route through the on-phone cache when we know the file size (almost always).
        var url = remote
        val pre = preload
        preload = null
        if (pre != null && pre.key == t.key) {
            // Already preloaded: start from what's on the phone.
            pre.cache.promote()
            cache = pre.cache
            url = pre.url
            preloadedExtras = pre
        } else pre?.cache?.stop()
        if (cache == null && t.fileSize > 0 && t.durationMs > 0) {
            val sc = StreamCache(remote, t.fileSize, t.durationMs / 1000.0,
                AppState.bufferMinutes.value * 60.0, t.container, app.cacheDir)
            try {
                url = withContext(Dispatchers.IO) { sc.start() }
                cache = sc
            } catch (_: Exception) { sc.stop() }
        }

        val p = MediaPlayer(vlc())
        player = p
        connectedView = null
        if (carSurface != null) connectCar() else layout?.let { connect(it) }
        p.setEventListener { e -> onEvent(e) }
        val m = Media(vlc(), Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=1500")
        if (resume > 1000) m.addOption(":start-time=${resume / 1000.0}")
        p.media = m
        m.release()
        p.play()
        applyAudioDelay(toastIfChanged = false)

        ticker?.cancel()
        ticker = scope.launch { while (isActive) { tick(); delay(500) } }
        fetchExtras(t)
    }

    private fun fetchExtras(t: Item) {
        scope.launch {
            val c = AppState.client
            val early = preloadedExtras?.takeIf { it.key == t.key }
            preloadedExtras = null
            val full = early?.item ?: try { c.details(t.key) } catch (_: Exception) { null }
            val nxt = if (early != null) early.after
                else if (t.isEpisode) try { c.episodeAdjacent(t, 1) } catch (_: Exception) { null } else null
            if (item.value?.key != t.key) return@launch
            if (full != null) {
                markers = full.markers
                pendingSubs = full.externalSubtitles.mapNotNull { c.streamUrl(it.key) }
                plexEmbeddedSub = full.selectedEmbeddedSubtitle
                plexExternalSubUrl = full.externalSubtitles.firstOrNull { it.selected }?.let { c.streamUrl(it.key) }
                if (hasPlayed) applySubChoice()
                addPendingSubs()
            }
            nextItem.value = nxt
        }
    }

    private fun addPendingSubs() {
        val p = player ?: return
        if (!hasPlayed || pendingSubs.isEmpty()) return
        val subs = pendingSubs
        pendingSubs = emptyList()
        for (u in subs) try {
            val chosen = pickedSub == null && u == plexExternalSubUrl
            p.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(u), chosen)
            if (chosen) rememberCurrentSubLater()
        } catch (_: Exception) {}
    }

    /** Stops playback and saves the position to Plex. */
    fun stop() {
        scope.launch { stopInternal(report = true, refresh = true) }
    }

    private suspend fun stopInternal(report: Boolean, refresh: Boolean) {
        ticker?.cancel()
        val it0 = item.value
        val pos = positionMs.value
        val dur = durationMs.value
        player?.let { p ->
            p.setEventListener(null)
            try { p.stop() } catch (_: Exception) {}
            try { p.detachViews() } catch (_: Exception) {}
            p.release()
        }
        player = null
        connectedView = null
        cache?.stop()
        cache = null
        val wasActive = active.value
        active.value = false
        playing.value = false
        buffering.value = false
        item.value = null
        nextItem.value = null
        skipMarker.value = null
        upNextSeconds.value = null
        offline.value = false
        if (refresh) {
            showFull.value = false
            resetZoom()
            preload?.cache?.stop()
            preload = null
        }
        if (report && wasActive && it0 != null) {
            val c = AppState.client
            scope.launch {
                c.reportTimeline(it0, "stopped", pos, dur)
                if (dur > 0 && pos.toDouble() / dur > 0.9) c.markWatched(it0.key)
                AppState.changed()
            }
        }
    }

    // -- controls -------------------------------------------------------------------------

    fun togglePause() {
        val p = player ?: return
        if (wantsPlay) { wantsPlay = false; p.pause() } else { wantsPlay = true; p.play() }
        playing.value = wantsPlay
        reportNow()
    }

    fun pause() { if (wantsPlay) togglePause() }

    fun seekTo(ms: Long) {
        val p = player ?: return
        val d = durationMs.value
        val t = ms.coerceIn(0, if (d > 0) d - 2000 else ms)
        p.time = t
        positionMs.value = t
    }

    fun skip(seconds: Int) = seekTo(positionMs.value + seconds * 1000L)

    /** More than 5 seconds in: back to the start. Within the first 5 seconds: previous episode. */
    fun previous() {
        val cur = item.value ?: return
        if (positionMs.value > 5000 || !cur.isEpisode) {
            seekTo(0)
            if (!wantsPlay) togglePause()
            return
        }
        switching.value = true
        scope.launch {
            val prev = try { AppState.client.episodeAdjacent(cur, -1) } catch (_: Exception) { null }
            switching.value = false
            if (prev != null) switchTo(prev, markWatched = false)
            else { seekTo(0); showToast("This is the first episode") }
        }
    }

    fun next() { nextItem.value?.let { switchTo(it, markWatched = false) } }

    private fun switchTo(other: Item, markWatched: Boolean) {
        val cur = item.value
        val pos = positionMs.value
        val dur = durationMs.value
        val c = AppState.client
        if (cur != null) scope.launch {
            if (markWatched) { c.reportTimeline(cur, "stopped", dur, dur); c.markWatched(cur.key) }
            else c.reportTimeline(cur, "stopped", pos, dur)
            AppState.changed()
        }
        active.value = false   // keep the old one from being reported again
        switching.value = true
        scope.launch { start(other, fromStart = true) }
    }

    fun minimize() { pause(); showFull.value = false }

    // -- zoom -------------------------------------------------------------------------------

    /** Width / height of the picture (falls back to 16:9 until VLC knows). */
    private fun videoAspect(): Float {
        val t = try { player?.currentVideoTrack } catch (_: Exception) { null }
        if (t != null && t.width > 0 && t.height > 0) {
            val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
            return t.width * sar / t.height
        }
        return 16f / 9f
    }

    /** How much to enlarge the fitted picture so it fills a w×h screen with no black bars. */
    private fun fillScale(w: Float, h: Float): Float {
        if (w <= 0 || h <= 0) return 1f
        val a = videoAspect()
        val fitW = minOf(w, h * a)
        val fitH = fitW / a
        return maxOf(w / fitW, h / fitH)
    }

    /** Double tap in step mode: 1 of 3, 2 of 3, fill the screen, then back to normal. */
    fun zoomStepTap(w: Float, h: Float) {
        // After a pinch to a custom size, a double tap goes back to normal.
        if (zoomStep < 0) { resetZoom(announce = true); return }
        val fill = fillScale(w, h)
        val levels = if (fill > 1.02f) listOf(1f, 1 + (fill - 1) / 3, 1 + 2 * (fill - 1) / 3, fill)
                     else listOf(1f, 1.1f, 1.2f, 1.33f)
        zoomStep = (zoomStep + 1) % 4
        zoom.value = levels[zoomStep]
        panX.value = 0f; panY.value = 0f
        showToast(when (zoomStep) { 0 -> "Original size"; 3 -> "Zoom 3 of 3 · fill screen"; else -> "Zoom $zoomStep of 3" })
    }

    /** Pinch mode: scale by [factor] and move by [dx],[dy], kept inside the screen. */
    fun pinch(factor: Float, dx: Float, dy: Float, w: Float, h: Float) {
        val z = (zoom.value * factor).coerceIn(1f, 4f)
        if (z != zoom.value || dx != 0f || dy != 0f) zoomStep = if (z == 1f) 0 else -1
        zoom.value = z
        val maxX = (z - 1) * w / 2
        val maxY = (z - 1) * h / 2
        panX.value = (panX.value + dx).coerceIn(-maxX, maxX)
        panY.value = (panY.value + dy).coerceIn(-maxY, maxY)
    }

    fun resetZoom(announce: Boolean = false) {
        val was = zoom.value != 1f
        zoomStep = 0; zoom.value = 1f; panX.value = 0f; panY.value = 0f
        if (announce && was) showToast("Original size")
    }
    fun expand() { if (active.value) showFull.value = true }

    fun skipCurrentMarker() {
        val m = currentMarker() ?: return
        skipped.add(m)
        skipMarker.value = null
        if (m.isCredits && m.end >= durationMs.value / 1000.0 - 15) {
            if (nextItem.value != null) playUpNextNow() else seekTo(durationMs.value - 2000)
        } else seekTo((m.end * 1000).toLong())
    }

    fun playUpNextNow() { nextItem.value?.let { upNextSeconds.value = null; switchTo(it, markWatched = true) } }

    fun cancelUpNext() { upNextCancelled = true; upNextDeadline = 0; upNextSeconds.value = null }

    // -- tracks, subtitle timing, audio delay ---------------------------------------------

    fun audioTracks(): List<Track> = player?.audioTracks?.map { Track(it.id, it.name ?: "Track ${it.id}") }?.filter { it.id >= 0 } ?: emptyList()
    fun subtitleTracks(): List<Track> = player?.spuTracks?.map { Track(it.id, it.name ?: "Track ${it.id}") }?.filter { it.id >= 0 } ?: emptyList()
    fun currentAudioTrack(): Int = player?.audioTrack ?: -1
    fun currentSubtitleTrack(): Int = player?.spuTrack ?: -1
    fun selectAudio(id: Int) { player?.setAudioTrack(id) }
    fun selectSubtitle(id: Int) { pickedSub = id; player?.setSpuTrack(id) }

    /** Subtitles off unless you picked one here or in Plex (files can mark a track as default; Plex ignores that). */
    private fun applySubChoice() {
        val p = player ?: return
        val ids = p.spuTracks?.map { it.id }?.filter { it >= 0 } ?: emptyList()
        val want = pickedSub ?: plexEmbeddedSub?.let { n -> ids.getOrNull(n - 1) }
            ?: if (plexExternalSubUrl != null) return else -1
        if (p.spuTrack != want) p.setSpuTrack(want)
    }

    private fun rememberCurrentSubLater() {
        scope.launch {
            delay(1500)
            val id = player?.spuTrack ?: return@launch
            if (id >= 0) pickedSub = id
        }
    }

    fun setSubtitleDelay(ms: Int) {
        subtitleDelayMs.value = ms
        player?.setSpuDelay(ms * 1000L)
    }

    fun audioDelayMs(): Int = AppState.audioDelays.value[audioDevice.value] ?: 0

    fun setAudioDelay(ms: Int) {
        AppState.setAudioDelay(audioDevice.value, ms)
        player?.setAudioDelay(ms * 1000L)
    }

    private fun applyAudioDelay(toastIfChanged: Boolean) {
        val ms = audioDelayMs()
        player?.setAudioDelay(ms * 1000L)
        if (toastIfChanged && active.value) showToast("Audio: ${audioDevice.value}" + if (ms != 0) "  ·  delay ${if (ms > 0) "+" else ""}$ms ms" else "")
    }

    private fun audioRouteChanged() {
        val name = currentOutputName()
        if (name == audioDevice.value) return
        audioDevice.value = name
        applyAudioDelay(toastIfChanged = true)
    }

    /** The name of where sound is going (a Bluetooth device's name, or the phone speaker). */
    private fun currentOutputName(): String {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val prefer = mutableListOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        if (Build.VERSION.SDK_INT >= 31) prefer.add(1, AudioDeviceInfo.TYPE_BLE_HEADSET)
        for (t in prefer) {
            outs.firstOrNull { it.type == t }?.let { d ->
                val n = d.productName?.toString()?.trim().orEmpty()
                return if (n.isNotEmpty() && n != Build.MODEL) n else when (t) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                    else -> "Headphones"
                }
            }
        }
        return "Phone speaker"
    }

    // -- events & ticking -------------------------------------------------------------------

    private fun onEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> { playing.value = true; buffering.value = false; applySubChoice() }
            MediaPlayer.Event.Paused -> playing.value = false
            MediaPlayer.Event.Buffering -> buffering.value = e.buffering < 100f
            MediaPlayer.Event.EndReached -> finished()
            MediaPlayer.Event.EncounteredError -> {
                if (!hasPlayed) error.value = "This video couldn't be played.\nCheck your connection to the Plex server."
            }
            MediaPlayer.Event.LengthChanged -> if (e.lengthChanged > 0) durationMs.value = e.lengthChanged
        }
    }

    private fun tick() {
        val p = player ?: return
        val t = p.time
        if (t >= 0) positionMs.value = t
        val len = p.length
        if (len > 0) durationMs.value = len
        val isPlaying = p.isPlaying
        playing.value = isPlaying || (wantsPlay && buffering.value)
        if (isPlaying && t > 0 && !hasPlayed) {
            hasPlayed = true
            applySubChoice()
            scope.launch { delay(1000); applySubChoice() }
            addPendingSubs()
        }
        cache?.snapshot()?.let { s ->
            bufferedSec.value = s.bufferedBytes / s.bytesPerSecond
            offline.value = s.offline
        }
        updateMarkers()
        preloadNext()
        if (System.currentTimeMillis() - lastReport > 10_000) reportNow()
    }

    /** With 5 minutes or less left, fetch the first ~20 s of the next episode so it starts at once.
     *  Only while this episode has a comfortable cushion loaded, so it never stalls the one you're watching. */
    private fun preloadNext() {
        val nxt = nextItem.value ?: return
        val cur = item.value ?: return
        val dur = durationMs.value / 1000.0
        val left = dur - positionMs.value / 1000.0
        if (!hasPlayed || dur <= 0 || left > 300) return
        val pre = preload
        if (pre != null && pre.key == nxt.key) {
            pre.cache.setAllowed(bufferedSec.value >= minOf(60.0, maxOf(left - 2, 0.0)))
            return
        }
        if (preloadPending == nxt.key) return
        preloadPending = nxt.key
        val c = AppState.client
        val minutes = AppState.bufferMinutes.value
        scope.launch {
            try {
                val full = try { c.details(nxt.key) } catch (_: Exception) { null } ?: nxt
                val after = try { c.episodeAdjacent(full, 1) } catch (_: Exception) { null }
                if (item.value?.key != cur.key || nextItem.value?.key != nxt.key) return@launch
                val remote = c.directUrl(full) ?: return@launch
                if (full.fileSize <= 0 || full.durationMs <= 0) return@launch
                val sc = StreamCache(remote, full.fileSize, full.durationMs / 1000.0, minutes * 60.0,
                    full.container, app.cacheDir, preloadSec = 20.0)
                val url = try { withContext(Dispatchers.IO) { sc.start() } } catch (_: Exception) { sc.stop(); return@launch }
                if (item.value?.key != cur.key || nextItem.value?.key != nxt.key) { sc.stop(); return@launch }
                preload?.cache?.stop()
                preload = Preload(full.key, sc, url, full, after)
            } finally {
                if (preloadPending == nxt.key) preloadPending = null
            }
        }
    }

    private fun reportNow() {
        val it0 = item.value ?: return
        if (offline.value) return
        lastReport = System.currentTimeMillis()
        val pos = positionMs.value; val dur = durationMs.value
        val st = if (wantsPlay) "playing" else "paused"
        scope.launch { AppState.client.reportTimeline(it0, st, pos, dur) }
    }

    private fun currentMarker(): Marker? {
        val cur = positionMs.value / 1000.0
        return markers.firstOrNull { cur >= it.start - 0.5 && cur < it.end - 1 }
    }

    private fun updateMarkers() {
        if (finishing || switching.value) { skipMarker.value = null; return }
        val cur = positionMs.value / 1000.0
        val dur = durationMs.value / 1000.0
        val credits = markers.firstOrNull { it.isCredits && it.end >= dur - 15 }
        val startAt = credits?.start ?: if (dur > 180) dur - 30 else null
        val inUpNext = nextItem.value != null && !upNextCancelled && startAt != null && cur >= startAt && dur - cur > 1
        if (inUpNext) {
            val now = System.currentTimeMillis()
            if (upNextDeadline == 0L) { upNextLeftMs = UP_NEXT_MS; upNextDeadline = now + UP_NEXT_MS }
            if (!wantsPlay || buffering.value) upNextDeadline = now + upNextLeftMs   // don't count down while paused
            val left = upNextDeadline - now
            upNextLeftMs = maxOf(left, 0)
            upNextSeconds.value = ((left + 999) / 1000).toInt().coerceAtLeast(0)
            if (left <= 0) { playUpNextNow(); return }
        } else if (upNextSeconds.value != null || upNextDeadline != 0L) {
            if (!(startAt != null && cur >= startAt)) { upNextCancelled = false }
            upNextSeconds.value = null
            upNextDeadline = 0
        }
        val m = currentMarker()
        skipMarker.value = if (m != null && m !in skipped && upNextSeconds.value == null) m else null
    }

    private fun finished() {
        if (finishing) return
        finishing = true
        val cur = item.value ?: return
        val dur = durationMs.value
        val go = if (upNextCancelled) null else nextItem.value
        val c = AppState.client
        scope.launch {
            c.reportTimeline(cur, "stopped", dur, dur)
            c.markWatched(cur.key)
            AppState.changed()
            val nxt = go ?: if (!upNextCancelled && cur.isEpisode) try { c.episodeAdjacent(cur, 1) } catch (_: Exception) { null } else null
            if (nxt != null) { active.value = false; start(nxt, fromStart = true) }
            else { active.value = false; stopInternal(report = false, refresh = true) }
        }
    }

    // -- online subtitles --------------------------------------------------------------

    var subtitleSearchLanguage: String
        get() = app.getSharedPreferences("ice", Context.MODE_PRIVATE).getString("subSearchLang", null)
            ?: (java.util.Locale.getDefault().language.takeIf { it.length == 2 } ?: "en")
        set(v) { app.getSharedPreferences("ice", Context.MODE_PRIVATE).edit().putString("subSearchLang", v).apply() }

    /** Has the server download that subtitle, then switches to it once it shows up with the video. */
    fun downloadSubtitle(sub: com.iceman.ice.data.OnlineSub) {
        val it0 = item.value ?: return
        val key = it0.key
        val c = AppState.client
        showToast("Downloading subtitles…")
        scope.launch {
            try {
                val before = (try { c.details(key) } catch (_: Exception) { null })?.externalSubtitles?.map { it.key }?.toSet() ?: emptySet()
                c.downloadSubtitle(key, sub.key)
                var found: com.iceman.ice.data.ExternalSub? = null
                for (i in 0 until 20) {
                    delay(1500)
                    val subs = (try { c.details(key) } catch (_: Exception) { null })?.externalSubtitles ?: continue
                    found = subs.lastOrNull { it.key !in before }
                    if (found != null) break
                }
                if (item.value?.key != key) return@launch
                val url = found?.let { c.streamUrl(it.key) }
                if (url == null) { showToast("The server didn't get those subtitles. Try another one."); return@launch }
                player?.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(url), true)
                rememberCurrentSubLater()
                showToast("Subtitles: ${sub.title}")
            } catch (e: Exception) {
                if (item.value?.key == key) showToast("Couldn't download: ${e.message}")
            }
        }
    }

    fun showToast(text: String) {
        toast.value = text
        scope.launch { delay(3500); if (toast.value == text) toast.value = null }
    }

    /** For "Previous / Next" from headphone or steering-wheel buttons later. */
    @Suppress("unused")
    private val cacheDir: File get() = app.cacheDir
}
