package com.iceman.ice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iceman.ice.data.AppState
import com.iceman.ice.data.Item
import com.iceman.ice.player.Playback
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(start: Item) {
    val version by AppState.dataVersion.collectAsState()
    var item by remember { mutableStateOf(start) }
    var children by remember { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(version) {
        try {
            AppState.client.metadata(start.key)?.let { item = it; Playback.warm(it) }
            if (start.type == "show" || start.type == "season") children = AppState.client.children(start.key)
            if (start.type == "show") AppState.client.nextEpisode(start.key)?.let { Playback.warm(it) }
        } catch (_: Exception) {}
    }
    when (item.type) {
        "show" -> ShowPage(item, children)
        "season" -> SeasonPage(item, children)
        else -> PlayablePage(item)
    }
}

@Composable
private fun Backdrop(path: String?) {
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        PlexImage(path, 1280, 720, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent, Bg))))
        val nav = LocalNav.current
        IconButton(onClick = { nav.back() }, modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
    }
}

@Composable
private fun Header(item: Item, eyebrow: String?, title: String, meta: String,
                   eyebrowOpens: Item? = null, metaOpens: Item? = null) {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().offset(y = (-70).dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
        PlexImage(item.posterPath, 300, 450, Modifier.size(110.dp, 165.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(14.dp))
        Column {
            if (eyebrow != null) Text(eyebrow + if (eyebrowOpens != null) "  ›" else "", color = LocalAccent.current,
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = if (eyebrowOpens != null) Modifier.clickable { nav.open(eyebrowOpens) } else Modifier)
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(meta, color = Muted, fontSize = 13.sp,
                modifier = if (metaOpens != null) Modifier.clickable { nav.open(metaOpens) } else Modifier)
            val ver by com.iceman.ice.data.Imdb.version.collectAsState()
            key(ver) { item.imdbRating }?.let { Box(Modifier.padding(top = 5.dp)) { ImdbBadge(it, 12) } }
        }
    }
}

private fun metaLine(item: Item): String = listOfNotNull(
    item.year?.toString(), item.contentRating,
    item.durationMs.takeIf { it > 0 }?.let { runtimeString(it) },
).joinToString("  ·  ")

@Composable
private fun WatchedButton(item: Item) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    OutlinedButton(contentPadding = PaddingValues(horizontal = 12.dp), onClick = {
        if (busy) return@OutlinedButton
        busy = true
        scope.launch {
            if (item.isWatched) AppState.client.markUnwatched(item.key) else AppState.client.markWatched(item.key)
            AppState.changed()
            busy = false
        }
    }) {
        Icon(if (item.isWatched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, null,
            tint = if (item.isWatched) LocalAccent.current else Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Watched", maxLines = 1)
    }
}

@Composable
private fun PlayablePage(item: Item) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { Backdrop(item.art ?: item.thumb) }
        item {
            Header(item, if (item.isEpisode) item.grandparentTitle else null, item.title,
                if (item.isEpisode) listOfNotNull(item.episodeCode.takeIf { it.isNotEmpty() }, metaLine(item)).joinToString("  ·  ")
                else metaLine(item),
                eyebrowOpens = if (item.isEpisode) item.showItem() else null,
                metaOpens = if (item.isEpisode) item.seasonItem() else null)
        }
        item {
            Column(Modifier.offset(y = (-56).dp).padding(horizontal = 16.dp)) {
                val inProgress = item.progress != null
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { Playback.play(item) }, contentPadding = PaddingValues(horizontal = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(if (inProgress) "Resume" else "Play", fontWeight = FontWeight.Bold)
                    }
                    if (inProgress) OutlinedButton(onClick = { Playback.play(item, fromStart = true) },
                        contentPadding = PaddingValues(horizontal = 12.dp)) { Text("From start", maxLines = 1) }
                    WatchedButton(item)
                }
                item.progress?.let { p ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProgressLine(p, Modifier.width(160.dp), 6.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("${runtimeString(item.durationMs - item.offsetMs)} left", color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        item { Box(Modifier.offset(y = (-56).dp)) { CastRow(plexCast(item)) } }
        item {
            Column(Modifier.offset(y = (-50).dp).padding(horizontal = 16.dp)) {
                val nav = LocalNav.current
                item.tagline?.let { Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp)) }
                item.summary?.let { Text(it, color = TextColor.copy(alpha = 0.9f), modifier = Modifier.padding(top = 8.dp)) }
                if (item.genres.isNotEmpty()) InfoLine("Genre", item.genres.joinToString(", "))
                LinkLine(if (item.directors.size > 1) "Directors" else "Director", item.directors) { i -> nav.push(Page.Person(null, item.directors[i])) }
                val writers = item.writers.take(4)
                LinkLine(if (writers.size > 1) "Writers" else "Writer", writers) { i -> nav.push(Page.Person(null, writers[i])) }
                val file = listOfNotNull(item.videoResolution?.uppercase()?.let { if (it.all { c -> c.isDigit() }) "${it}p" else it },
                    item.videoCodec?.uppercase(), item.audioCodec?.uppercase(), item.container?.uppercase()).joinToString(" · ")
                if (file.isNotEmpty()) InfoLine("File", file)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(top = 10.dp)) {
        Text(label, color = Muted, modifier = Modifier.width(72.dp), fontSize = 13.sp)
        Text(value, fontSize = 13.sp)
    }
}

@Composable
private fun ShowPage(item: Item, seasons: List<Item>?) {
    val nav = LocalNav.current
    val sr = seerr()
    var schedule by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.tmdbId) {
        val s = sr ?: return@LaunchedEffect
        val tid = item.tmdbId ?: return@LaunchedEffect
        try {
            val x = s.schedule(tid)
            schedule = when {
                x.code.isNotEmpty() -> "Next episode: ${x.code}" + (if (x.name.isEmpty()) "" else " “${x.name}”") +
                    " · " + (if (x.date.isEmpty()) "date not announced" else weekdayDate(x.date))
                x.status == "Ended" || x.status == "Canceled" -> x.status + (if (x.last.isEmpty()) "" else " · last episode ${niceDate(x.last)}")
                else -> null
            }
        } catch (_: Exception) {}
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { Backdrop(item.art ?: item.thumb) }
        item { Header(item, null, item.title, listOfNotNull(item.year?.toString(), item.contentRating,
            item.childCount?.let { "$it season" + if (it == 1) "" else "s" }).joinToString("  ·  ")) }
        item {
            Column(Modifier.offset(y = (-56).dp).padding(horizontal = 16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { Playback.play(item) },
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                        Icon(Icons.Filled.PlayArrow, null); Text("Play next episode", fontWeight = FontWeight.Bold)
                    }
                    WatchedButton(item)
                }
            }
        }
        item { Box(Modifier.offset(y = (-56).dp)) { CastRow(plexCast(item)) } }
        item {
            Column(Modifier.offset(y = (-56).dp).padding(horizontal = 16.dp)) {
                schedule?.let { Text(it, color = if (it.startsWith("Next")) LocalAccent.current else Muted,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                item.summary?.let { Text(it, modifier = Modifier.padding(top = 12.dp)) }
                Text("Seasons", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            }
        }
        if (seasons == null) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { Spinner() } }
        else items(seasons, key = { it.key }) { s ->
            Row(Modifier.fillMaxWidth().offset(y = (-50).dp).clickable { nav.open(s) }.padding(16.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PlexImage(s.thumb, 200, 300, Modifier.size(60.dp, 90.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontWeight = FontWeight.SemiBold)
                    Text("${s.leafCount ?: 0} episodes", color = Muted, fontSize = 13.sp)
                    // Seasons have no score of their own: show the show's.
                    item.imdbRating?.let { Box(Modifier.padding(top = 3.dp)) { ImdbBadge(it, 10) } }
                }
                if (s.isWatched) Icon(Icons.Filled.CheckCircle, null, tint = LocalAccent.current)
            }
        }
    }
}

@Composable
private fun SeasonPage(item: Item, episodes: List<Item>?) {
    val nav = LocalNav.current
    val sr = seerr()
    var coming by remember { mutableStateOf<List<com.iceman.ice.seerr.SeerrEpisode>>(emptyList()) }
    LaunchedEffect(item.key, episodes?.size) {
        val s = sr ?: return@LaunchedEffect
        val eps = episodes ?: return@LaunchedEffect
        val show = item.parentKey ?: return@LaunchedEffect
        val n = item.index ?: return@LaunchedEffect
        try {
            val tid = AppState.client.metadata(show)?.tmdbId ?: return@LaunchedEffect
            val have = eps.mapNotNull { it.index }.toSet()
            coming = s.seasonEpisodes(tid, n).filter { it.number !in have }
        } catch (_: Exception) {}
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { BackBar(item.parentTitle ?: "") }
        item {
            Row(Modifier.padding(16.dp, 4.dp), verticalAlignment = Alignment.Bottom) {
                PlexImage(item.thumb, 200, 300, Modifier.size(90.dp, 135.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(14.dp))
                Column {
                    val show = item.showItem()
                    Text((item.parentTitle ?: "") + if (show != null) "  ›" else "", color = LocalAccent.current,
                        fontWeight = FontWeight.SemiBold,
                        modifier = if (show != null) Modifier.clickable { nav.open(show) } else Modifier)
                    Text(item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("${episodes?.size ?: item.leafCount ?: 0} episodes", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    WatchedButton(item)
                }
            }
        }
        if (episodes == null) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { Spinner() } }
        else items(episodes, key = { it.key }) { ep ->
            Row(Modifier.fillMaxWidth().clickable { nav.open(ep) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    PlexImage(ep.thumb, 320, 180, Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(6.dp)))
                    ep.progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp), 3.dp) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${ep.index?.let { "$it. " } ?: ""}${ep.title}", fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(ep.airDateRaw?.let { niceDate(it) }, ep.durationMs.takeIf { it > 0 }?.let { runtimeString(it) },
                        ep.progress?.let { "${runtimeString(ep.durationMs - ep.offsetMs)} left" }).joinToString("  ·  "),
                        color = Muted, fontSize = 12.sp)
                }
                if (ep.showCheck) Icon(Icons.Filled.CheckCircle, null, tint = LocalAccent.current)
            }
        }
        // Episodes not here yet (from Seerr): ones still to air with their dates, and any that are out but missing.
        if (coming.isNotEmpty()) {
            item { Text("Not in your library yet", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp)) }
            items(coming, key = { "coming-${it.number}" }) { e ->
                val d = parseDay(e.airDate)
                val future = d == null || d.after(java.util.Date())
                Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.number}. ${e.name.ifEmpty { "Episode ${e.number}" }}", color = TextColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                    Text(if (e.airDate.isEmpty()) "Date not announced" else if (future) "Airs ${weekdayDate(e.airDate)}" else "Aired ${niceDate(e.airDate)}",
                        color = if (future) LocalAccent.current else Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
