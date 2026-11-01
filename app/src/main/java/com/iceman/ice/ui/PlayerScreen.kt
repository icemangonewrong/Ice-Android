package com.iceman.ice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.iceman.ice.data.AppState
import com.iceman.ice.player.Playback
import kotlinx.coroutines.delay
import org.videolan.libvlc.util.VLCVideoLayout

/** The picture. Moves between the full player and the mini player. */
@Composable
fun VideoSurface(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> VLCVideoLayout(ctx).also { Playback.attach(it) } },
        update = { Playback.attach(it) },
        onRelease = { Playback.detach(it) },
        modifier = modifier,
    )
}

@Composable
fun PlayerScreen() {
    val item by Playback.item.collectAsState()
    val active by Playback.active.collectAsState()
    val playing by Playback.playing.collectAsState()
    val buffering by Playback.buffering.collectAsState()
    val switching by Playback.switching.collectAsState()
    val pos by Playback.positionMs.collectAsState()
    val dur by Playback.durationMs.collectAsState()
    val buffered by Playback.bufferedSec.collectAsState()
    val offline by Playback.offline.collectAsState()
    val error by Playback.error.collectAsState()
    val next by Playback.nextItem.collectAsState()
    val marker by Playback.skipMarker.collectAsState()
    val upNext by Playback.upNextSeconds.collectAsState()
    val toast by Playback.toast.collectAsState()
    val tapToPause by AppState.tapToPause.collectAsState()

    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf<Long?>(null) }
    var showAudioDelay by remember { mutableStateOf(false) }
    var showSubTiming by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler { Playback.minimize() }

    // Hide the controls 3 seconds after the last touch while playing.
    LaunchedEffect(touched, playing, scrubbing, menuOpen) {
        if (playing && scrubbing == null && !menuOpen) { delay(3000); controls = false }
    }
    fun poke() { controls = true; touched++ }

    // The picture itself is drawn underneath by IceRoot (one video view for the whole app).
    Box(Modifier.fillMaxSize()) {

        // Tap the picture: pause/play if turned on in Settings, otherwise show/hide the controls.
        Box(Modifier.fillMaxSize().pointerInput(tapToPause) {
            detectTapGestures {
                if (tapToPause) { Playback.togglePause(); poke() }
                else if (controls) controls = false else poke()
            }
        })

        if ((buffering || switching || !active) && error == null) Box(Modifier.align(Alignment.Center)) { Spinner(56.dp) }

        Column(Modifier.align(Alignment.TopCenter).padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (offline) Pill(if (buffering) "No signal — will pick up where it left off"
                              else "No signal · playing from ${timeString((buffered * 1000).toLong())} saved")
            toast?.let { Spacer(Modifier.height(6.dp)); Pill(it) }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 640.dp
            val barHeight: Dp = if (compact) 150.dp else 100.dp

            // Skip Intro / Up Next float above the bar and stay up even when the bar hides.
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = if (controls) barHeight + 16.dp else 20.dp),
                horizontalAlignment = Alignment.End) {
                val n = next
                if (upNext != null && n != null) {
                    UpNextCard(n.title, n.episodeCode, upNext!!)
                } else marker?.let { m ->
                    Text(if (m.isIntro) "Skip Intro" else "Skip Credits", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.75f))
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .clickable { Playback.skipCurrentMarker() }.padding(horizontal = 20.dp, vertical = 11.dp))
                }
            }

            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
                IconButton(onClick = { Playback.minimize() }, modifier = Modifier.padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Minimize", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }

            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().background(Color(0xE6101014)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) { poke() }) {
                    ScrubBar(pos, dur, buffered, onDrag = { scrubbing = it; poke() },
                        onSeek = { Playback.seekTo(it); scrubbing = null; poke() })
                    val shownPos = scrubbing ?: pos
                    val info: @Composable () -> Unit = {
                        Column(Modifier.widthIn(max = 280.dp)) {
                            Text(item?.displayTitle ?: "", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = Color.White)
                            val sub = item?.let { if (it.isEpisode) it.displaySubtitle else it.year?.toString() } ?: ""
                            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("${timeString(shownPos)} / ${timeString(dur)}" +
                                if (buffered > 1) "  ·  ${timeString((buffered * 1000).toLong())} loaded" else "",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.65f))
                        }
                    }
                    val transport: @Composable () -> Unit = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BarButton(Icons.Filled.SkipPrevious, 28.dp) { Playback.previous(); poke() }
                            Spacer(Modifier.width(14.dp)); Divider(); Spacer(Modifier.width(16.dp))
                            BarButton(Icons.Filled.Replay10, 32.dp) { Playback.skip(-10); poke() }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
                                .clickable { Playback.togglePause(); poke() }, contentAlignment = Alignment.Center) {
                                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause",
                                    tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            BarButton(Icons.Filled.Forward10, 32.dp) { Playback.skip(10); poke() }
                            val isEp = item?.isEpisode == true
                            Spacer(Modifier.width(16.dp))
                            Box(Modifier.width(2.dp)) { if (isEp) Divider() }
                            Spacer(Modifier.width(14.dp))
                            Box(Modifier.size(48.dp)) {
                                if (isEp) BarButton(Icons.Filled.SkipNext, 28.dp, enabled = next != null) { Playback.next(); poke() }
                            }
                        }
                    }
                    val right: @Composable () -> Unit = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                BarButton(Icons.Filled.ClosedCaption, 26.dp) { menuOpen = true; poke() }
                                TracksMenu(menuOpen, onDismiss = { menuOpen = false; poke() },
                                    onSubtitleTiming = { menuOpen = false; showSubTiming = true })
                            }
                            BarButton(Icons.Filled.GraphicEq, 26.dp) { showAudioDelay = true; poke() }
                        }
                    }
                    if (compact) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { info() }
                            right()
                        }
                        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { transport() }
                    } else {
                        Box(Modifier.fillMaxWidth().height(barHeight - 22.dp).padding(horizontal = 24.dp)) {
                            Box(Modifier.align(Alignment.CenterStart)) { info() }
                            Box(Modifier.align(Alignment.Center)) { transport() }
                            Box(Modifier.align(Alignment.CenterEnd)) { right() }
                        }
                    }
                }
            }
        }

        error?.let { msg ->
            Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.8f)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { Playback.stop() }, colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current,
                    contentColor = Color.Black)) { Text("Close") }
            }
        }
    }

    if (showAudioDelay) {
        AdjustDialog("Audio delay", "For ${Playback.audioDevice.value}. Saved for this device and used automatically whenever it's connected.",
            Playback.audioDelayMs(), 50, "◀ Sound earlier", "Sound later ▶",
            describe = { if (it < 0) "Sound plays earlier" else if (it > 0) "Sound plays later" else "No adjustment" },
            onChange = { Playback.setAudioDelay(it) }, onDismiss = { showAudioDelay = false; poke() })
    }
    if (showSubTiming) {
        AdjustDialog("Subtitle timing", "For this video.", Playback.subtitleDelayMs.value, 100, "◀ Earlier", "Later ▶",
            describe = { if (it < 0) "Subtitles show earlier" else if (it > 0) "Subtitles show later" else "No adjustment" },
            onChange = { Playback.setSubtitleDelay(it) }, onDismiss = { showSubTiming = false; poke() })
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(2.dp).height(30.dp).background(Color.White.copy(alpha = 0.25f)))
}

@Composable
private fun BarButton(icon: ImageVector, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size + 20.dp)) {
        Icon(icon, null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(size))
    }
}

@Composable
private fun UpNextCard(title: String, code: String, seconds: Int) {
    Column(Modifier.width(300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xF0121216))
        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text("UP NEXT · PLAYING IN $seconds", color = LocalAccent.current, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(code, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { Playback.playUpNextNow() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) { Text("Play now") }
            OutlinedButton(onClick = { Playback.cancelUpNext() }) { Text("Cancel", color = Color.White) }
        }
    }
}

/** Played (accent), loaded ahead (light), rest (dim). Drag or tap to seek. */
@Composable
private fun ScrubBar(pos: Long, dur: Long, bufferedSec: Double, onDrag: (Long) -> Unit, onSeek: (Long) -> Unit) {
    val accent = LocalAccent.current
    var dragX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }
    fun timeAt(x: Float) = if (dur <= 0) 0L else ((x / width).coerceIn(0f, 1f) * dur).toLong()
    Canvas(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp)
        .pointerInput(dur) {
            detectTapGestures { o -> onSeek(timeAt(o.x)) }
        }
        .pointerInput(dur) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragX = o.x; onDrag(timeAt(o.x)) },
                onDragEnd = { dragX?.let { onSeek(timeAt(it)) }; dragX = null },
                onDragCancel = { dragX = null },
                onHorizontalDrag = { change, _ -> dragX = change.position.x; onDrag(timeAt(change.position.x)) },
            )
        }) {
        width = size.width
        val active = dragX != null
        val th = if (active) 7.dp.toPx() else 4.dp.toPx()
        val y = size.height / 2 - th / 2
        val px = dragX ?: if (dur > 0) size.width * (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val bx = if (dur > 0) (size.width * ((pos + bufferedSec * 1000) / dur).toFloat().coerceIn(0f, 1f)).coerceAtLeast(px) else px
        val r = CornerRadius(th / 2, th / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y), Size(size.width, th), r)
        drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(0f, y), Size(bx, th), r)
        drawRoundRect(accent, Offset(0f, y), Size(px, th), r)
        if (active) drawCircle(accent, 9.dp.toPx(), Offset(px, size.height / 2))
    }
}

@Composable
private fun TracksMenu(open: Boolean, onDismiss: () -> Unit, onSubtitleTiming: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        val audio = Playback.audioTracks()
        val subs = Playback.subtitleTracks()
        val curA = Playback.currentAudioTrack()
        val curS = Playback.currentSubtitleTrack()
        if (audio.isNotEmpty()) {
            Text("Audio", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            audio.forEach { t ->
                DropdownMenuItem(text = { Text((if (t.id == curA) "✓  " else "     ") + t.name) },
                    onClick = { Playback.selectAudio(t.id); onDismiss() })
            }
            HorizontalDivider()
        }
        Text("Subtitles", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        DropdownMenuItem(text = { Text((if (curS < 0) "✓  " else "     ") + "Off") },
            onClick = { Playback.selectSubtitle(-1); onDismiss() })
        subs.forEach { t ->
            DropdownMenuItem(text = { Text((if (t.id == curS) "✓  " else "     ") + t.name) },
                onClick = { Playback.selectSubtitle(t.id); onDismiss() })
        }
        HorizontalDivider()
        val d = Playback.subtitleDelayMs.value
        DropdownMenuItem(text = { Text("Subtitle timing…" + if (d != 0) "  (${if (d > 0) "+" else ""}$d ms)" else "") },
            onClick = onSubtitleTiming)
    }
}

@Composable
private fun AdjustDialog(title: String, note: String, initial: Int, step: Int, minus: String, plus: String,
                         describe: (Int) -> String, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableIntStateOf(initial) }
    fun set(n: Int) { v = n.coerceIn(-5000, 5000); onChange(v) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF1D1D23)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(note, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Text(if (v == 0) "0 ms" else "${if (v > 0) "+" else ""}$v ms", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp))
            Text(describe(v), color = Muted)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { set(v - step) }) { Text(minus) }
                OutlinedButton(onClick = { set(v + step) }) { Text(plus) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { set(0) }) { Text("Reset to 0") }
                Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current,
                    contentColor = Color.Black)) { Text("Done") }
            }
        }
    }
}
