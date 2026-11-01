package com.iceman.ice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iceman.ice.data.AppState
import com.iceman.ice.data.Item
import com.iceman.ice.data.Resource
import com.iceman.ice.data.Section
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Navigation: four tabs, each with its own stack of pages
// ---------------------------------------------------------------------------

sealed class Page {
    data class Detail(val item: Item) : Page()
    data class Library(val section: Section) : Page()
}

class Nav {
    var tab by mutableIntStateOf(0)
    val stacks = List(4) { mutableStateListOf<Page>() }
    val current: Page? get() = stacks[tab].lastOrNull()

    fun open(item: Item) { stacks[tab].add(Page.Detail(item)) }
    fun push(p: Page) { stacks[tab].add(p) }
    fun back(): Boolean {
        val s = stacks[tab]
        if (s.isEmpty()) return false
        s.removeAt(s.lastIndex)
        return true
    }
    fun switchTab(i: Int) {
        if (i == tab) stacks[i].clear() else tab = i
    }
}

val LocalNav = compositionLocalOf { Nav() }

@Composable
fun BackBar(title: String = "") {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Sign in & server picker
// ---------------------------------------------------------------------------

@Composable
fun SignInScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Ice", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Sign in with your Plex account. Your browser will open: approve the sign-in, then come back here.",
            color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (waiting) return@Button
            waiting = true; err = null
            scope.launch {
                try {
                    val c = AppState.client
                    val pin = c.createPin()
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.authUrl(pin))))
                    repeat(180) {
                        delay(2000)
                        val p = c.checkPin(pin.id)
                        if (p.authToken != null) { AppState.setToken(p.authToken); return@launch }
                    }
                    err = "Sign-in timed out. Try again."
                } catch (e: Exception) {
                    err = e.message ?: "Couldn't reach plex.tv."
                } finally { waiting = false }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            if (waiting) { Spinner(18.dp); Spacer(Modifier.width(10.dp)); Text("Waiting for Plex…") }
            else Text("Sign in with Plex")
        }
        err?.let { Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable
fun ServerPickerScreen() {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<Resource>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { servers = AppState.client.resources(); if (servers!!.isEmpty()) err = "No servers found on this Plex account." }
        catch (e: Exception) { err = e.message ?: "Couldn't load servers." }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Choose server", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (servers == null && err == null) CenterSpinner()
        servers?.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(CardColor)
                .clickable(enabled = connecting == null) {
                    connecting = r.clientIdentifier; err = null
                    scope.launch {
                        try { AppState.setServer(AppState.client.connect(r)) }
                        catch (e: Exception) { err = e.message }
                        connecting = null
                    }
                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (connecting == r.clientIdentifier) Spinner(20.dp) else Icon(Icons.Filled.ChevronRight, null, tint = Muted)
            }
        }
        err?.let { Text(it, color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { AppState.signOut() }) { Text("Sign out", color = Color(0xFFFF6B6B)) }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val version by AppState.dataVersion.collectAsState()
    val excluded by AppState.excluded.collectAsState()
    var cw by remember { mutableStateOf<List<Item>?>(null) }
    var recent by remember { mutableStateOf<List<Pair<Section, List<Item>>>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version, excluded) {
        try {
            val c = AppState.client
            cw = c.continueWatching().filter { !AppState.isExcluded(it) }
            AppState.loadSections()
            recent = AppState.visibleSections.map { s -> s to (try { c.recentlyAdded(s.key) } catch (_: Exception) { emptyList() }) }
            err = null
        } catch (e: Exception) { if (cw == null) err = e.message ?: "Couldn't reach the Plex server." }
    }
    if (cw == null && err == null) { CenterSpinner(); return }
    if (cw == null) { Message(err!!); return }
    val server by AppState.server.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(server?.name ?: "Home", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) }
        item { PosterRow("Continue Watching", cw!!) { nav.open(it) } }
        items(recent, key = { it.first.key }) { (s, list) -> PosterRow("Recently Added · ${s.title}", list) { nav.open(it) } }
    }
}

// ---------------------------------------------------------------------------
// Libraries
// ---------------------------------------------------------------------------

@Composable
fun LibrariesScreen() {
    val nav = LocalNav.current
    val excluded by AppState.excluded.collectAsState()
    var sections by remember { mutableStateOf<List<Section>?>(null) }
    LaunchedEffect(excluded) { sections = try { AppState.loadSections(); AppState.visibleSections } catch (_: Exception) { emptyList() } }
    Column(Modifier.fillMaxSize()) {
        Text("Libraries", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        if (sections == null) { CenterSpinner(); return }
        sections!!.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { nav.push(Page.Library(s)) }.padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.type == "show") Icons.Filled.Tv else Icons.Filled.Movie, null, tint = LocalAccent.current)
                Spacer(Modifier.width(14.dp))
                Text(s.title, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, null, tint = Muted)
            }
        }
    }
}

@Composable
fun LibraryGridScreen(section: Section) {
    val nav = LocalNav.current
    var sort by remember { mutableStateOf("titleSort") }
    var unwatched by remember { mutableStateOf(false) }
    val items = remember(sort, unwatched) { mutableStateListOf<Item>() }
    var total by remember(sort, unwatched) { mutableIntStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    fun loadMore() {
        if (loading || (total >= 0 && items.size >= total)) return
        loading = true
        scope.launch {
            try {
                val (list, t) = AppState.client.library(section.key, sort, unwatched, items.size, 60)
                items.addAll(list); total = if (t >= 0) t else if (list.isEmpty()) items.size else -1
                if (list.isEmpty()) total = items.size
            } catch (_: Exception) { total = items.size }
            loading = false
        }
    }
    LaunchedEffect(sort, unwatched) { loadMore() }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > items.size - 20 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd) loadMore() }
    Column(Modifier.fillMaxSize()) {
        BackBar(section.title)
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, key) in listOf("A–Z" to "titleSort", "Recently added" to "addedAt:desc", "Release date" to "originallyAvailableAt:desc")) {
                FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(label) })
            }
            FilterChip(selected = unwatched, onClick = { unwatched = !unwatched }, label = { Text("Unwatched") })
        }
        if (items.isEmpty() && loading) { CenterSpinner(); return }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.key }) { PosterCard(it, 110.dp) { nav.open(it) } }
        }
    }
}

// ---------------------------------------------------------------------------
// Search
// ---------------------------------------------------------------------------

@Composable
fun SearchScreen() {
    val nav = LocalNav.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val t = query.trim()
        if (t.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        results = try { AppState.client.search(t).filter { !AppState.isExcluded(it) } } catch (_: Exception) { emptyList() }
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Movies and shows") },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (searching) { CenterSpinner(); return }
        if (query.trim().length >= 2 && results.isEmpty()) { Message("No results"); return }
        LazyColumn {
            items(results, key = { it.key }) { item ->
                Row(Modifier.fillMaxWidth().clickable { nav.open(item) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlexImage(item.posterPath, 120, 180, Modifier.size(50.dp, 75.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (item.isEpisode) item.title else item.displayTitle, fontWeight = FontWeight.SemiBold)
                        Text(if (item.isEpisode) "${item.displayTitle} · ${item.displaySubtitle}" else item.displaySubtitle ?: item.type,
                            fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    val server by AppState.server.collectAsState()
    val accent by AppState.accent.collectAsState()
    val excluded by AppState.excluded.collectAsState()
    val buffer by AppState.bufferMinutes.collectAsState()
    val tap by AppState.tapToPause.collectAsState()
    val delays by AppState.audioDelays.collectAsState()
    var sections by remember { mutableStateOf(AppState.sections) }
    LaunchedEffect(Unit) { sections = try { AppState.loadSections() } catch (_: Exception) { emptyList() } }
    val switchColors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current, checkedThumbColor = Color.White)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        SettingsHeader("Server")
        Text("Connected to ${server?.name ?: "—"}")
        Text(server?.baseURL ?: "", fontSize = 12.sp, color = Muted)
        OutlinedButton(onClick = { AppState.setServer(null) }, modifier = Modifier.padding(top = 8.dp)) { Text("Change server") }

        SettingsHeader("Accent color", "Used for buttons, progress bars, the loading circle and highlights.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((name, c) in AppState.ACCENTS) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                    .border(3.dp, if (c == accent) Color.White else Color.Transparent, CircleShape)
                    .clickable { AppState.setAccent(c) })
            }
        }
        Text(AppState.ACCENTS.firstOrNull { it.second == accent }?.first ?: "", color = Muted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp))

        SettingsHeader("Libraries", "Hidden libraries are left out of Home, Continue Watching, Libraries and Search.")
        sections.forEach { s ->
            ToggleRow(s.title, s.key !in excluded, switchColors) { AppState.setExcluded(s.key, !it) }
        }

        SettingsHeader("Buffering", "How much video to download ahead so it keeps playing through dead spots. " +
            "It's saved on the phone's storage (not memory) and deleted when you stop watching.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in AppState.BUFFER_CHOICES) FilterChip(selected = buffer == m, onClick = { AppState.setBufferMinutes(m) },
                label = { Text("$m min") })
        }

        SettingsHeader("Player")
        ToggleRow("Pause when the screen is tapped", tap, switchColors) { AppState.setTapToPause(it) }
        Text("When this is off, tapping the video just shows or hides the controls.", fontSize = 12.sp, color = Muted)
        Text("Double tap the video to zoom in steps (a 4th double tap goes back to normal). Pinch to zoom further and drag to move it.",
            fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))

        SettingsHeader("Audio delay", "Set from the player's audio delay button. Each Bluetooth device keeps its own " +
            "setting, used automatically when that device is connected.")
        if (delays.isEmpty()) Text("No devices adjusted yet", color = Muted)
        delays.toSortedMap().forEach { (dev, ms) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(dev, modifier = Modifier.weight(1f))
                Text("${if (ms > 0) "+" else ""}$ms ms", color = Muted)
                TextButton(onClick = { AppState.setAudioDelay(dev, 0) }) { Text("Reset") }
            }
        }

        SettingsHeader("Account")
        TextButton(onClick = { AppState.signOut() }) { Text("Sign out", color = Color(0xFFFF6B6B)) }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsHeader(title: String, note: String? = null) {
    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
    if (note != null) Text(note, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ToggleRow(label: String, on: Boolean, colors: androidx.compose.material3.SwitchColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = colors)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}
