package com.iceman.ice.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.sp
import com.iceman.ice.data.AppState
import com.iceman.ice.player.Playback

@Composable
fun IceRoot() {
    IceTheme {
        val signedIn by AppState.signedIn.collectAsState()
        val server by AppState.server.collectAsState()
        val showFull by Playback.showFull.collectAsState()
        Box(Modifier.fillMaxSize().background(Bg)) {
            when {
                !signedIn -> Box(Modifier.systemBarsPadding()) { SignInScreen() }
                server == null -> Box(Modifier.systemBarsPadding()) { ServerPickerScreen() }
                else -> MainTabs()
            }
            // One video view for the whole app: full screen, or shrunk into the mini player's spot.
            val active by Playback.active.collectAsState()
            val mini by MiniSpot.rect.collectAsState()
            val density = LocalDensity.current
            if (showFull) Box(Modifier.fillMaxSize().background(Color.Black))
            if (active || showFull) {
                val z by Playback.zoom.collectAsState()
                val px by Playback.panX.collectAsState()
                val py by Playback.panY.collectAsState()
                val m = if (showFull) Modifier.fillMaxSize().clipToBounds()
                    .graphicsLayer { scaleX = z; scaleY = z; translationX = px; translationY = py }
                else mini?.let { r ->
                    with(density) {
                        Modifier.offset { IntOffset(r.left.toInt(), r.top.toInt()) }
                            .size(r.width.toDp(), r.height.toDp()).clip(RoundedCornerShape(6.dp))
                    }
                } ?: Modifier.size(1.dp)
                VideoSurface(m)
            }
            if (showFull) PlayerScreen()
        }
    }
}

@Composable
private fun MainTabs() {
    val nav = remember { Nav() }
    val active by Playback.active.collectAsState()
    val showFull by Playback.showFull.collectAsState()
    CompositionLocalProvider(LocalNav provides nav) {
        BackHandler(enabled = nav.current != null && !showFull) { nav.back() }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (val p = nav.current) {
                    is Page.Detail -> androidx.compose.runtime.key(p) { DetailScreen(p.item) }
                    is Page.Library -> androidx.compose.runtime.key(p) { LibraryGridScreen(p.section) }
                    null -> when (nav.tab) {
                        0 -> HomeScreen()
                        1 -> LibrariesScreen()
                        2 -> SearchScreen()
                        else -> SettingsScreen()
                    }
                }
            }
            if (active && !showFull) MiniPlayer()
            val accent = LocalAccent.current
            NavigationBar(containerColor = Panel) {
                val tabs = listOf("Home" to Icons.Filled.Home, "Libraries" to Icons.Filled.GridView,
                    "Search" to Icons.Filled.Search, "Settings" to Icons.Filled.Settings)
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = nav.tab == i, onClick = { nav.switchTab(i) },
                        icon = { Icon(icon, label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = accent, selectedTextColor = accent,
                            indicatorColor = accent.copy(alpha = 0.15f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }
    }
}

/** Paused video along the bottom after pressing the down arrow / back. Tap it to open the full player. */
/** Where the mini player's picture goes on screen. */
object MiniSpot { val rect = kotlinx.coroutines.flow.MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null) }

@Composable
private fun MiniPlayer() {
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { MiniSpot.rect.value = null } }
    val item by Playback.item.collectAsState()
    val playing by Playback.playing.collectAsState()
    val pos by Playback.positionMs.collectAsState()
    val dur by Playback.durationMs.collectAsState()
    Column(Modifier.fillMaxWidth().padding(8.dp, 6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C22))) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable { Playback.expand() }, verticalAlignment = Alignment.CenterVertically) {
                // The video view itself is placed over this spot by IceRoot.
                Box(Modifier.size(88.dp, 50.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)
                    .onGloballyPositioned { MiniSpot.rect.value = it.boundsInRoot() })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item?.displayTitle ?: "", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(item?.let { if (it.isEpisode) it.displaySubtitle else null } ?: "${timeString(pos)} / ${timeString(dur)}",
                        fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { Playback.togglePause() }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = { Playback.stop() }) { Icon(Icons.Filled.Close, "Stop") }
        }
        ProgressLine(if (dur > 0) pos.toFloat() / dur else 0f, Modifier.fillMaxWidth(), 3.dp)
    }
}
