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
    private var cache: StreamCache? = null
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
    }

    private fun vlc(): LibVLC = libVLC ?: LibVLC(app, arrayListOf(
        "--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames", "--http-reconnect",
    )).also { libVLC = it }

    // -- video surface ------------------------------------------------------------

    /** Shows the picture in this view (the full player or the mini player's thumbnail). */
    fun attach(view: VLCVideoLayout) {
        if (layout === view) return
        val p = player
        if (p != null) {
            try { p.detachViews() } catch (_: Exception) {}
            p.attachViews(view, null, true, true)
            p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            // VLC only starts drawing on a new view when the video track restarts.
            val track = p.videoTrack
            if (track >= 0) main.postDelayed({
                if (player === p && layout === view) { p.videoTrack = -1; p.videoTrack = track }
            }, 150)
        }
        layout = view
    }

    fun detach(view: VLCVideoLayout) {
        if (layout !== view) return
        try { player?.detachViews() } catch (_: Exception) {}
        layout = null
    }

    // -- start / stop -----------------------------------------------------------------

    fun play(target: Item, fromStart: Boolean = false) {
        error.value = null
        showFull.value = true
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
        if (t.fileSize > 0 && t.durationMs > 0) {
            val sc = StreamCache(remote, t.fileSize, t.durationMs / 1000.0,
                AppState.bufferMinutes.value * 60.0, t.container, app.cacheDir)
            try {
                url = withContext(Dispatchers.IO) { sc.start() }
                cache = sc
            } catch (_: Exception) { sc.stop() }
        }

        val p = MediaPlayer(vlc())
        player = p
        layout?.let { p.attachViews(it, null, true, true); p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT }
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
            val full = try { c.details(t.key) } catch (_: Exception) { null }
            val nxt = if (t.isEpisode) try { c.episodeAdjacent(t, 1) } catch (_: Exception) { null } else null
            if (item.value?.key != t.key) return@launch
            if (full != null) {
                markers = full.markers
                pendingSubs = full.externalSubtitles.mapNotNull { c.streamUrl(it.key) }
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
        for (u in subs) try { p.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(u), false) } catch (_: Exception) {}
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
        if (refresh) showFull.value = false
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
    fun selectSubtitle(id: Int) { player?.setSpuTrack(id) }

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
            MediaPlayer.Event.Playing -> { playing.value = true; buffering.value = false }
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
        if (isPlaying && t > 0 && !hasPlayed) { hasPlayed = true; addPendingSubs() }
        cache?.snapshot()?.let { s ->
            bufferedSec.value = s.bufferedBytes / s.bytesPerSecond
            offline.value = s.offline
        }
        updateMarkers()
        if (System.currentTimeMillis() - lastReport > 10_000) reportNow()
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

    fun showToast(text: String) {
        toast.value = text
        scope.launch { delay(3500); if (toast.value == text) toast.value = null }
    }

    /** For "Previous / Next" from headphone or steering-wheel buttons later. */
    @Suppress("unused")
    private val cacheDir: File get() = app.cacheDir
}
