package com.iceman.ice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.iceman.ice.data.AppState
import com.iceman.ice.data.Item

val Bg = Color(0xFF0B0B0E)
val Panel = Color(0xFF131318)
val CardColor = Color(0xFF1C1C22)
val Muted = Color(0xFF9A9AA6)
val TextColor = Color(0xFFF2F2F5)

val LocalAccent = compositionLocalOf { Color(0xFFFF8C00) }

@Composable
fun IceTheme(content: @Composable () -> Unit) {
    val accentLong by AppState.accent.collectAsState()
    val accent = Color(accentLong)
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.Black, secondary = accent, tertiary = accent,
        background = Bg, surface = Panel, surfaceVariant = CardColor, onBackground = TextColor,
        onSurface = TextColor, onSurfaceVariant = Muted, surfaceContainer = Panel,
        surfaceContainerHigh = CardColor, surfaceContainerHighest = CardColor,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme) {
            // Light text by default everywhere (screens draw on the dark background directly).
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides TextColor, content = content)
        }
    }
}

@Composable
fun Spinner(size: Dp = 48.dp) {
    CircularProgressIndicator(color = LocalAccent.current, strokeWidth = size / 10, modifier = Modifier.size(size),
        trackColor = LocalAccent.current.copy(alpha = 0.2f))
}

@Composable
fun CenterSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted)
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(height / 2)).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height).background(LocalAccent.current))
    }
}

/** Poster image from Plex (falls back to a film icon). */
@Composable
fun PlexImage(path: String?, width: Int, height: Int, modifier: Modifier = Modifier) {
    val url = AppState.client.imageUrl(path, width, height)
    Box(modifier.background(Color(0xFF26262E)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Movie, null, tint = Muted.copy(alpha = 0.5f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
    }
}

/** Poster with title, progress bar and watched check. Tapping the picture opens the info page; like Plex, under
 *  an episode the show name opens the show (all seasons) and "S2 · E5" opens that season's episodes, and under a
 *  season the show name opens the show. */
@Composable
fun PosterCard(item: Item, width: Dp = 120.dp, onOpen: ((Item) -> Unit)? = null, onClick: () -> Unit) {
    val titleTo = if (item.isEpisode) item.showItem() else null
    val subTo = when (item.type) { "episode" -> item.seasonItem(); "season" -> item.showItem(); else -> null }
    Column(Modifier.width(width)) {
        Box(Modifier.clickable(onClick = onClick)) {
            PlexImage(item.posterPath, 300, 450,
                Modifier.width(width).height(width * 1.5f).clip(RoundedCornerShape(8.dp)))
            item.progress?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
            }
            item.imdbRating?.takeIf { !item.isEpisode }?.let { r ->   // not on single episodes
                Text("IMDb %.1f".format(r), color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                        .background(Color(0xFFF5C518), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            if (item.showCheck) {
                Icon(Icons.Filled.CheckCircle, null, tint = LocalAccent.current,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).background(Color.White, CircleShape))
            }
        }
        Text(item.displayTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .clickable { if (titleTo != null && onOpen != null) onOpen(titleTo) else onClick() }
                .padding(top = 6.dp))
        Text(item.displaySubtitle ?: "", fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
                .clickable { if (subTo != null && onOpen != null) onOpen(subTo) else onClick() })
    }
}

@Composable
fun PosterRow(title: String, items: List<Item>, onTitle: (() -> Unit)? = null, onOpen: (Item) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        if (onTitle != null) {
            Text("$title  ›", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onTitle).padding(horizontal = 16.dp, vertical = 2.dp))
        } else {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            items(items, key = { it.key }) { PosterCard(it, onOpen = onOpen) { onOpen(it) } }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp))
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

fun timeString(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun runtimeString(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
