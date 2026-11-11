package com.iceman.ice.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    data class Library(val section: Section, val sort: String? = null) : Page()
    data class Title(val result: com.iceman.ice.seerr.SeerrResult) : Page()
    data class Person(val id: Int?, val name: String) : Page()
    data class Grid(val title: String, val kinds: List<String>, val source: GridSource, val sorts: Boolean = true) : Page()
    data class Collection(val id: Int, val name: String) : Page()
    data object Requests : Page()
    data class RequestStatus(val request: com.iceman.ice.seerr.SeerrRequest) : Page()
}

class Nav {
    var tab by mutableIntStateOf(0)
    val stacks = List(5) { mutableStateListOf<Page>() }
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
    // Reload when Home is shown again or the app comes back from the background, so Recently Added keeps up.
    var tick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(version, excluded, tick) {
        try {
            val c = AppState.client
            cw = c.continueWatching().filter { !AppState.isExcluded(it) }
            AppState.loadSections()
            val old = recent.toMap()
            recent = AppState.visibleSections.map { s ->
                s to (try { c.recentlyAdded(s.key) } catch (_: Exception) { old.entries.firstOrNull { it.key.key == s.key }?.value ?: emptyList() })
            }
            err = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (cw == null) err = e.message ?: "Couldn't reach the Plex server."
        }
    }
    if (cw == null && err == null) { CenterSpinner(); return }
    if (cw == null) { Message(err!!); return }
    val server by AppState.server.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(server?.name ?: "Home", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp)) }
        item { PosterRow("Continue Watching", cw!!) { nav.open(it) } }
        items(recent, key = { it.first.key }) { (s, list) ->
            PosterRow("Recently Added · ${s.title}", list,
                onTitle = { nav.push(Page.Library(s, if (s.type == "show") "episode.addedAt:desc" else "addedAt:desc")) }) { nav.open(it) }
        }
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
fun LibraryGridScreen(section: Section, initialSort: String? = null) {
    val nav = LocalNav.current
    var sort by remember { mutableStateOf(initialSort ?: "titleSort") }
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
                if (sort == "imdb" || sort == "popular") {
                    // Plex can't sort by these: load the whole library and sort it here with IMDb's numbers.
                    val all = mutableListOf<Item>()
                    while (true) {
                        val (list, t) = AppState.client.library(section.key, "titleSort", unwatched, all.size, 300)
                        all.addAll(list)
                        if (list.size < 300 || (t >= 0 && all.size >= t)) break
                    }
                    val sorted = if (sort == "imdb") all.sortedByDescending { it.imdbRating ?: -1.0 }
                                 else all.sortedByDescending { com.iceman.ice.data.Imdb.votes(it.imdbId) ?: -1 }
                    items.clear(); items.addAll(sorted); total = items.size
                    loading = false
                    return@launch
                }
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
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sorts = listOfNotNull("A–Z" to "titleSort",
                if (section.type == "show") "Newest episode" to "episode.addedAt:desc" else null,
                "Recently added" to "addedAt:desc", "Release date" to "originallyAvailableAt:desc",
                "IMDb rating" to "imdb", "Popular" to "popular")
            for ((label, key) in sorts) {
                FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(label) })
            }
            FilterChip(selected = unwatched, onClick = { unwatched = !unwatched }, label = { Text("Unwatched") })
        }
        if (items.isEmpty() && loading) { CenterSpinner(); return }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), state = grid, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.key }) { PosterCard(it, 110.dp, onOpen = { o -> nav.open(o) }) { nav.open(it) } }
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
    var others by remember { mutableStateOf<List<com.iceman.ice.seerr.SeerrResult>>(emptyList()) }
    var othersNote by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf<List<com.iceman.ice.seerr.SeerrPerson>>(emptyList()) }
    val ctx = LocalContext.current
    LaunchedEffect(query) {
        val t = query.trim()
        if (t.length < 2) { results = emptyList(); others = emptyList(); othersNote = null; people = emptyList(); return@LaunchedEffect }
        delay(400)
        searching = true
        // Newest first (library and Seerr alike).
        results = try {
            AppState.client.search(t).filter { !AppState.isExcluded(it) }
                .sortedByDescending { it.airDateRaw ?: it.year?.let { y -> "$y-00-00" } ?: "0000" }
        } catch (_: Exception) { emptyList() }
        // Everything else (not in the library), from Seerr, to request.
        val sr = com.iceman.ice.seerr.Seerr.get(ctx, AppState.client.token)
        if (sr != null) {
            people = try { sr.searchPeople(t).sortedBy { if (it.photo != null) 0 else 1 }.take(12) } catch (_: Exception) { emptyList() }
            try {
                // Things in Plex that showed up above are left out here; anything Seerr knows is in the library but
                // Plex's search missed stays (with Play).
                val keys = results.map { it.key }.toSet()
                val tmdb = results.mapNotNull { i -> i.tmdbId?.let { "${i.type}:$it" } }.toSet()
                val names = results.map { "${it.title.lowercase()}|${it.year ?: ""}" }.toSet()
                others = sr.search(t).filter { r ->
                    r.status != com.iceman.ice.seerr.Seerr.AVAILABLE || !(
                        (r.ratingKey != null && r.ratingKey in keys) ||
                        "${if (r.kind == "movie") "movie" else "show"}:${r.id}" in tmdb ||
                        "${r.title.lowercase()}|${r.year}" in names)
                }.sortedByDescending { it.date.ifEmpty { "0000" } }
                othersNote = if (others.isEmpty()) "Nothing else found." else null
            } catch (e: Exception) { others = emptyList(); othersNote = "Couldn't search Seerr: ${e.message}" }
        }
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Movies and shows") },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (searching) { CenterSpinner(); return }
        if (query.trim().length >= 2 && results.isEmpty() && others.isEmpty() && othersNote == null) { Message("No results"); return }
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
            if (people.isNotEmpty()) item {
                Text("People", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    items(people, key = { "${it.id}" }) { p -> CastCard(p, 92.dp) { nav.push(Page.Person(p.id, p.name)) } }
                }
            }
            if (others.isNotEmpty() || othersNote != null) {
                item {
                    Text("From Seerr · request what you don't have", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp))
                    othersNote?.let { Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                items(others, key = { "seerr-${it.kind}-${it.id}" }) { r -> SeerrRow(r) { openResult(nav, r) } }
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

        SettingsHeader("Requests (Seerr)", "Search shows everything, not just your library, and lets you request what's missing through Seerr. Ice signs in with your Plex account. Over WireGuard the address is usually ${com.iceman.ice.seerr.Seerr.DEFAULT_URL}.")
        SeerrSettings()

        SettingsHeader("About")
        val ctx = LocalContext.current
        val build = remember {
            try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName?.substringAfterLast('.') ?: "?" }
            catch (_: Exception) { "?" }
        }
        Text("Ice for Android  ·  build $build")
        Text("The newest build number is shown on the download page.", fontSize = 12.sp, color = Muted)
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/icemangonewrong/Ice-Android/releases/latest")))
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Check for updates") }
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
