package com.iceman.ice.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.iceman.ice.data.AppState
import com.iceman.ice.data.Imdb
import com.iceman.ice.data.Item
import com.iceman.ice.seerr.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Discover: browse everything (not just your library) through Seerr, open any title, actor or director, and
// request. IMDb scores come from IMDb's own ratings file (Imdb).

val ImdbYellow = Color(0xFFF5C518)
val LibGreen = Color(0xFF22A447)
val ReqAmber = Color(0xFFD98A00)

/** Where a "see all" list gets its titles from. */
data class GridSource(val special: String? = null, val params: Map<String, String> = emptyMap(),
                      val genre: Map<String, Int> = emptyMap())

@Composable
fun seerr(): Seerr? = Seerr.get(LocalContext.current, AppState.client.token)

@Composable
fun ImdbBadge(score: Double, size: Int = 11) {
    Text("IMDb %.1f".format(score), color = Color.Black, fontSize = size.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.background(ImdbYellow, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
fun OutlineBadge(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextColor.copy(alpha = 0.85f),
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
fun StatusDot(mark: String?, modifier: Modifier = Modifier) {
    when (mark) {
        "lib" -> Icon(Icons.Filled.CheckCircle, "In your library", tint = LibGreen,
            modifier = modifier.size(22.dp).background(Color.White, CircleShape))
        "req" -> Icon(Icons.Filled.Schedule, "Requested", tint = Color.White,
            modifier = modifier.size(22.dp).background(ReqAmber, CircleShape).padding(2.dp))
    }
}

/** The Plex item for a Seerr title that's in the library. Seerr usually says which one; when it doesn't, look it
 *  up in Plex by its TMDB id (or title and year). */
suspend fun findInLibrary(r: SeerrResult): Item? {
    val want = if (r.kind == "movie") "movie" else "show"
    r.ratingKey?.let { rk -> return Item(JSONObject().put("ratingKey", rk).put("type", want).put("title", r.title)) }
    val hits = try { AppState.client.search(r.title).filter { it.type == want } } catch (_: Exception) { return null }
    return hits.firstOrNull { it.tmdbId == r.id }
        ?: hits.firstOrNull { it.title.equals(r.title, ignoreCase = true) && (it.year?.toString() ?: "") == r.year }
}

/** Play a movie, or a show's next episode. */
suspend fun playFromLibrary(item: Item) {
    if (item.type == "show") {
        try { AppState.client.nextEpisode(item.key)?.let { com.iceman.ice.player.Playback.play(it) } } catch (_: Exception) {}
    } else com.iceman.ice.player.Playback.play(item)
}

private val uiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

/** In your library: its normal page (to play). Otherwise its title page. */
fun openResult(nav: Nav, r: SeerrResult) {
    if (r.status == Seerr.AVAILABLE) {
        uiScope.launch {
            val it = findInLibrary(r)
            if (it != null) nav.open(it) else nav.push(Page.Title(r))
        }
    } else nav.push(Page.Title(r))
}

private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
fun parseDay(s: String): Date? = try { dayFmt.parse(s.take(10)) } catch (_: Exception) { null }
/** "Sat, Nov 15" (adds the year when it isn't this year). */
fun weekdayDate(s: String): String {
    val d = parseDay(s) ?: return s
    val y = SimpleDateFormat("yyyy", Locale.US)
    return SimpleDateFormat(if (y.format(d) == y.format(Date())) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.US).format(d)
}
fun niceDate(s: String): String = parseDay(s)?.let { SimpleDateFormat("MMM d, yyyy", Locale.US).format(it) } ?: s
fun shortDate(s: String): String {
    val d = parseDay(s) ?: return ""
    val y = SimpleDateFormat("yyyy", Locale.US)
    return SimpleDateFormat(if (y.format(d) == y.format(Date())) "MMM d" else "MMM d, yyyy", Locale.US).format(d)
}

// ---------------------------------------------------------------------------
// Poster: long press shows a Request button on it (the title page has one too)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeerrCard(r: SeerrResult, width: Dp = 110.dp) {
    val nav = LocalNav.current
    val sr = seerr()
    val scope = rememberCoroutineScope()
    val extras by SeerrExtras.data.collectAsState()
    val ver by Imdb.version.collectAsState()
    val e = extras[r.uid]
    var showReq by remember { mutableStateOf(false) }
    var reqState by remember { mutableStateOf("") }   // "", "working", "done", "failed"
    LaunchedEffect(r.uid) { SeerrExtras.want(sr, r) }
    val mark = if (reqState == "done") "req" else r.mark
    Column(Modifier.width(width).combinedClickable(
        onClick = { if (showReq) showReq = false else openResult(nav, r) },
        onLongClick = { if (mark == null || r.mark == "lib") showReq = true })) {
        Box {
            Box(Modifier.width(width).height(width * 1.5f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262E)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Movie, null, tint = Muted.copy(alpha = 0.5f))
                if (r.poster != null) AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            }
            key(ver) { Imdb.rating(e?.imdb) }?.let { Box(Modifier.align(Alignment.TopStart).padding(5.dp)) { ImdbBadge(it, 10) } }
            StatusDot(mark, Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (showReq && r.mark == "lib") {
                Button(onClick = { showReq = false; scope.launch { findInLibrary(r)?.let { playFromLibrary(it) } } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                    Text("▶ Play", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (showReq && mark == null) {
                Button(onClick = {
                    if (sr == null || reqState == "working") return@Button
                    reqState = "working"
                    scope.launch {
                        reqState = try { sr.request(r, null); "done" } catch (_: Exception) { "failed" }
                        if (reqState == "done") showReq = false
                    }
                }, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                    Text(when (reqState) { "working" -> "Requesting…"; "failed" -> "Try again"; else -> "+ Request" },
                        fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Text(r.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        val sub = if (r.role.isNotEmpty()) r.role
                  else listOf(r.year, e?.cert?.ifEmpty { null } ?: r.kindText).filter { it.isNotEmpty() }.joinToString(" · ")
        Text(sub, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SeerrCardRow(title: String, items: List<SeerrResult>, onTitle: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Text(if (onTitle != null) "$title  ›" else title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = (if (onTitle != null) Modifier.clickable(onClick = onTitle) else Modifier).padding(horizontal = 16.dp, vertical = 2.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            items(items, key = { it.uid }) { SeerrCard(it) }
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else TextColor, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) accent else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
private fun Tile(text: String, logo: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(52.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) {
            AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TopBar(title: String) {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Discover tab
// ---------------------------------------------------------------------------

private val ROWS = listOf(Triple("trending", "Trending this week", "all"), Triple("pop_movie", "Popular movies", "movie"),
    Triple("pop_tv", "Popular shows", "tv"), Triple("up_movie", "Coming soon · Movies", "movie"),
    Triple("up_tv", "Coming soon · Shows", "tv"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen() {
    val nav = LocalNav.current
    val sr = seerr()
    var kind by rememberSaveable { mutableStateOf("all") }
    var rows by remember { mutableStateOf<Map<String, List<SeerrResult>>>(emptyMap()) }
    var failed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var services by remember { mutableStateOf<List<SeerrService>>(emptyList()) }
    var genres by remember { mutableStateOf<List<Pair<String, Map<String, Int>>>>(emptyList()) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        val s = sr ?: return@LaunchedEffect
        failed = emptyMap()
        coroutineScope {
            for ((k, _, _) in ROWS) launch {
                try {
                    val items = when (k) {
                        "trending" -> s.trending().items
                        "pop_movie" -> s.discover("movie").items
                        "pop_tv" -> s.discover("tv").items
                        "up_movie" -> s.upcoming("movie").items
                        else -> s.upcoming("tv").items
                    }
                    rows = rows + (k to items)
                } catch (e: Exception) { failed = failed + (k to (e.message ?: "Couldn't reach Seerr.")) }
            }
            launch { services = try { s.services() } catch (_: Exception) { emptyList() } }
            launch {
                val m = async { try { s.genres("movie") } catch (_: Exception) { emptyList() } }
                val t = async { try { s.genres("tv") } catch (_: Exception) { emptyList() } }
                val by = sortedMapOf<String, MutableMap<String, Int>>()
                m.await().forEach { by.getOrPut(it.name) { mutableMapOf() }["movie"] = it.id }
                t.await().forEach { by.getOrPut(it.name) { mutableMapOf() }["tv"] = it.id }
                genres = by.map { it.key to it.value.toMap() }
            }
        }
    }
    val kinds = if (kind == "all") listOf("movie", "tv") else listOf(kind)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Discover", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "All", "movie" to "Movies", "tv" to "Shows").forEach { (k, t) -> Chip(t, kind == k) { kind = k } }
                Chip("Requests") { nav.push(Page.Requests) }
            }
        }
        if (sr == null) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Discover and requests use Seerr. Set it up in Settings → Requests (Seerr).", color = Muted)
                    Button(onClick = { nav.switchTab(4) }, modifier = Modifier.padding(top = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                        Text("Open Settings", fontWeight = FontWeight.Bold)
                    }
                }
            }
            return@LazyColumn
        }
        if (failed.size == ROWS.size) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failed.values.first(), color = Muted)
                OutlinedButton(onClick = { rows = emptyMap(); attempt++ }, modifier = Modifier.padding(top = 10.dp)) { Text("Try again") }
            }
        }
        for ((k, title, rk) in ROWS) {
            if (kind != "all" && rk != "all" && rk != kind) continue
            val items = rows[k]
            if (items == null) {
                if (failed[k] == null) item(key = "ph-$k") {
                    Column(Modifier.padding(top = 18.dp)) {
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                        Box(Modifier.fillMaxWidth().height(120.dp).padding(16.dp)) { Spinner(28.dp) }
                    }
                }
            } else {
                val shown = items.filter { kind == "all" || it.kind == kind }
                item(key = "row-$k-$kind") {
                    SeerrCardRow(title, shown) {
                        nav.push(when (k) {
                            "trending" -> Page.Grid(title, listOf("all"), GridSource(special = "trending"), sorts = false)
                            "up_movie" -> Page.Grid(title, listOf("movie"), GridSource(special = "upcoming"), sorts = false)
                            "up_tv" -> Page.Grid(title, listOf("tv"), GridSource(special = "upcoming"), sorts = false)
                            else -> Page.Grid(title, listOf(rk), GridSource())
                        })
                    }
                }
            }
        }
        if (services.isNotEmpty()) item {
            SectionHeader("Streaming services")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                services.forEach { s ->
                    Tile(s.name, s.logo, Modifier.weight(1f)) {
                        nav.push(Page.Grid(s.name, kinds, GridSource(params = mapOf("watchProviders" to "${s.id}", "watchRegion" to Seerr.REGION))))
                    }
                }
            }
        }
        item {
            if (kind != "movie") {
                SectionHeader("Networks")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Seerr.NETWORKS.forEach { (n, id) -> Chip(n) { nav.push(Page.Grid(n, listOf("tv"), GridSource(params = mapOf("network" to "$id")))) } }
                }
            }
            if (kind != "tv") {
                SectionHeader("Studios")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Seerr.STUDIOS.forEach { (n, id) -> Chip(n) { nav.push(Page.Grid(n, listOf("movie"), GridSource(params = mapOf("studio" to "$id")))) } }
                }
            }
        }
        val shownGenres = genres.filter { g -> kinds.any { g.second[it] != null } }
        if (shownGenres.isNotEmpty()) item {
            SectionHeader("Genres")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                shownGenres.forEach { (name, ids) ->
                    Tile(name, null, Modifier.weight(1f)) { nav.push(Page.Grid(name, kinds.filter { ids[it] != null }, GridSource(genre = ids))) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Full list (see all / service / network / studio / genre)
// ---------------------------------------------------------------------------

private val SORTS = listOf("Popular" to "popularity.desc", "Newest" to "date.desc", "Top rated" to "vote_average.desc")

@Composable
fun GridScreen(title: String, kinds: List<String>, source: GridSource, sorts: Boolean) {
    val sr = seerr()
    var kind by rememberSaveable { mutableStateOf(kinds.first()) }
    var sort by rememberSaveable { mutableStateOf("popularity.desc") }
    var items by remember { mutableStateOf<List<SeerrResult>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<Int?>(null) }
    var total by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var sortOpen by remember { mutableStateOf(false) }

    suspend fun fetch(s: Seerr, k: String, p: Int): SeerrPage<SeerrResult> {
        if (source.special == "trending") return s.trending(p)
        if (source.special == "upcoming") return s.upcoming(k, p)
        val params = source.params.toMutableMap()
        source.genre[k]?.let { params["genre"] = "$it" }
        var so = sort
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (so == "date.desc") {
            so = if (k == "movie") "primary_release_date.desc" else "first_air_date.desc"
            params[if (k == "movie") "primaryReleaseDateLte" else "firstAirDateLte"] = today
        } else if (so == "vote_average.desc") params["voteCountGte"] = "300"
        params["sortBy"] = so
        return s.discover(k, p, params)
    }

    fun loadMore() {
        val s = sr ?: return
        val pg = pages
        if (loading || (pg != null && page >= minOf(pg, 30))) return
        loading = true
        val my = gen; val next = page + 1; val k = kind
        scope.launch {
            try {
                val res = fetch(s, k, next)
                if (my != gen) return@launch
                val seen = items.map { it.uid }.toSet()
                items = items + res.items.filter { it.uid !in seen }
                page = next; pages = res.pages
                if (next == 1) total = res.total
                if (items.isEmpty()) error = "Nothing here."
            } catch (e: Exception) { if (my == gen) error = e.message }
            if (my == gen) loading = false
        }
    }

    LaunchedEffect(kind, sort) {
        gen++; items = emptyList(); page = 0; pages = null; total = null; error = null; loading = false
        loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    total?.let { t ->
                        val word = if (source.special == "trending") "title" else if (kind == "movie") "movie" else "show"
                        Text("%,d %s%s".format(t, word, if (t == 1) "" else "s"), color = Muted, fontSize = 14.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (kinds.size > 1) kinds.forEach { k -> Chip(if (k == "movie") "Movies" else "Shows", kind == k) { kind = k } }
                        Spacer(Modifier.weight(1f))
                        if (sorts) Box {
                            Chip((SORTS.firstOrNull { it.second == sort }?.first ?: "Popular") + "  ▾") { sortOpen = true }
                            DropdownMenu(sortOpen, { sortOpen = false }) {
                                SORTS.forEach { (n, v) -> DropdownMenuItem(text = { Text(n) }, onClick = { sort = v; sortOpen = false }) }
                            }
                        }
                    }
                    error?.let { Text(it, color = Muted, modifier = Modifier.padding(top = 10.dp)) }
                }
            }
            items(items, key = { it.uid }) { r ->
                SeerrCard(r, 104.dp)
                if (r.uid == items.lastOrNull()?.uid) LaunchedEffect(r.uid, items.size) { loadMore() }
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(32.dp) } }
        }
    }
}

// ---------------------------------------------------------------------------
// Cast
// ---------------------------------------------------------------------------

@Composable
fun CastCard(p: SeerrPerson, width: Dp = 104.dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.width(width).height(width * 1.25f).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, null, tint = Muted)
            if (p.photo != null) AsyncImage(model = p.photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.height(58.dp).padding(8.dp)) {
            Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            if (p.role.isNotEmpty()) Text(p.role, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CastRow(people: List<SeerrPerson>, title: String = "Cast") {
    if (people.isEmpty()) return
    val nav = LocalNav.current
    Column(Modifier.padding(top = 16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(16.dp, 10.dp)) {
            items(people, key = { "${it.id}:${it.name}:${it.role}" }) { p -> CastCard(p) { nav.push(Page.Person(p.id, p.name)) } }
        }
    }
}

/** Cast from a Plex item (Plex gives names and photos; Seerr finds the person by name). */
fun plexCast(item: Item): List<SeerrPerson> = item.castPhotos.take(20).map { (n, r, photo) -> SeerrPerson(null, n, r, photo) }

/** Directors / writers / studio etc. as tappable names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkLine(label: String, names: List<String>, onClick: (Int) -> Unit) {
    if (names.isEmpty()) return
    Row(Modifier.padding(top = 10.dp)) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            names.forEachIndexed { i, n ->
                Text(n, color = LocalAccent.current, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clickable { onClick(i) })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Title page
// ---------------------------------------------------------------------------

private val LOCKED_ST = setOf(Seerr.PENDING, Seerr.PROCESSING, Seerr.AVAILABLE)

@Composable
fun TitleScreen(start: SeerrResult) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val sr = seerr()
    val scope = rememberCoroutineScope()
    val ver by Imdb.version.collectAsState()
    var info by remember { mutableStateOf<SeerrTitle?>(null) }
    var scores by remember { mutableStateOf<Pair<Double?, Int?>>(null to null) }
    var recs by remember { mutableStateOf<List<SeerrResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var state by remember { mutableStateOf("") }
    var reqError by remember { mutableStateOf<String?>(null) }
    var libItem by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(start.uid) {
        val s = sr ?: run { error = "Set your Seerr address in Settings first."; return@LaunchedEffect }
        try {
            val t = s.title(start.kind, start.id)
            info = t
            SeerrExtras.put(t.result, t.imdbId, t.cert)
            picked = t.seasons.filter { it.status !in LOCKED_ST }.map { it.number }.toSet()
            if (t.result.status == Seerr.AVAILABLE || t.result.status == Seerr.PARTIAL) libItem = findInLibrary(t.result)
            scores = s.scores(start.kind, start.id)
            recs = s.recommendations(start.kind, start.id)
        } catch (e: Exception) { error = "Couldn't load from Seerr: ${e.message}" }
    }
    val t = info
    val r = t?.result ?: start
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                if (r.backdrop != null) AsyncImage(model = r.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent, Bg))))
                IconButton(onClick = { nav.back() }, modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
            Row(Modifier.fillMaxWidth().offset(y = (-70).dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    Box(Modifier.size(110.dp, 165.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262E))) {
                        if (r.poster != null) AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    StatusDot(if (state == "done") "req" else r.mark, Modifier.align(Alignment.TopEnd).padding(5.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    t?.tagline?.takeIf { it.isNotEmpty() }?.let { Text(it, color = LocalAccent.current, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2) }
                    Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val bits = mutableListOf(r.year)
                    if (t != null) {
                        if (r.kind == "movie") t.runtime?.let { m -> bits += if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
                        if (r.kind == "tv") t.numSeasons?.let { n -> bits += if (n == 1) "1 season" else "$n seasons" }
                        bits += t.genres.take(2)
                    }
                    Text(bits.filter { it.isNotEmpty() }.joinToString(" · "), color = Muted, fontSize = 13.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        t?.cert?.takeIf { it.isNotEmpty() }?.let { OutlineBadge(it) }
                        (key(ver) { Imdb.rating(t?.imdbId) } ?: scores.first)?.let { ImdbBadge(it) }
                        scores.second?.let { Text("RT $it%", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) }
                    }
                }
            }
        }
        item {
            Column(Modifier.offset(y = (-56).dp).padding(horizontal = 16.dp)) {
                error?.let { Text(it, color = Muted) }
                if (t == null && error == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
                if (t != null) {
                    val st = r.status
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val lib = libItem
                        if ((st == Seerr.AVAILABLE || st == Seerr.PARTIAL) && lib != null) {
                            Button(onClick = { scope.launch { playFromLibrary(lib) } },
                                colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                                Icon(Icons.Filled.PlayArrow, null); Text("Play", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { nav.open(lib) }) { Text("Open") }
                        }
                        val canRequest = if (r.kind == "movie") st != Seerr.AVAILABLE && st != Seerr.PENDING && st != Seerr.PROCESSING
                                         else t.seasons.any { it.status !in LOCKED_ST }
                        when {
                            state == "done" -> Text("✓  Requested", color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                            r.kind == "movie" && (st == Seerr.PENDING || st == Seerr.PROCESSING) ->
                                Text("Requested", color = ReqAmber, fontWeight = FontWeight.SemiBold)
                            canRequest -> Button(onClick = {
                                val s = sr ?: return@Button
                                state = "working"; reqError = null
                                scope.launch {
                                    try { s.request(r, if (r.kind == "tv") picked.sorted() else null); state = "done" }
                                    catch (e: Exception) { state = ""; reqError = "Couldn't request: ${e.message}" }
                                }
                            }, enabled = state != "working" && (r.kind == "movie" || picked.isNotEmpty()),
                                colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                                Icon(Icons.Filled.Add, null); Text(if (state == "working") "Requesting…" else "Request", fontWeight = FontWeight.Bold)
                            }
                            r.kind == "tv" -> Text("Every season is in your library or requested.", color = Muted, fontSize = 13.sp)
                        }
                        t.trailer?.let { url ->
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Trailer") }
                        }
                    }
                    reqError?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                    if (r.kind == "tv") t.seasons.forEach { s ->
                        val locked = s.status in LOCKED_ST
                        Row(Modifier.fillMaxWidth().clickable(enabled = !locked && state != "done") {
                            picked = if (s.number in picked) picked - s.number else picked + s.number
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (locked || s.number in picked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                                tint = if (locked) Muted else LocalAccent.current)
                            Spacer(Modifier.width(10.dp))
                            Text(s.name)
                            Spacer(Modifier.width(8.dp))
                            Text(listOfNotNull("${s.episodes} ep", Seerr.STATUS_TEXT[s.status]).joinToString(" · "), fontSize = 12.sp, color = Muted)
                        }
                    }
                }
            }
        }
        if (t != null) {
            item { Box(Modifier.offset(y = (-56).dp)) { CastRow(t.cast) } }
            item {
                Column(Modifier.offset(y = (-50).dp).padding(horizontal = 16.dp)) {
                    if (r.overview.isNotEmpty()) Text(r.overview, color = TextColor.copy(alpha = 0.9f), modifier = Modifier.padding(top = 8.dp))
                    LinkLine(if (t.directors.size > 1) "Directors" else "Director", t.directors.map { it.name }) { i -> nav.push(Page.Person(t.directors[i].id, t.directors[i].name)) }
                    LinkLine("Created by", t.creators.map { it.name }) { i -> nav.push(Page.Person(t.creators[i].id, t.creators[i].name)) }
                    LinkLine("Writers", t.writers.map { it.name }) { i -> nav.push(Page.Person(t.writers[i].id, t.writers[i].name)) }
                    t.network?.let { n -> LinkLine("Network", listOf(n.name)) { nav.push(Page.Grid(n.name, listOf("tv"), GridSource(params = mapOf("network" to "${n.id}")))) } }
                    if (r.kind == "movie") t.studio?.let { s -> LinkLine("Studio", listOf(s.name)) { nav.push(Page.Grid(s.name, listOf("movie"), GridSource(params = mapOf("studio" to "${s.id}")))) } }
                    val provs = t.providers.take(4)
                    LinkLine("Streaming on", provs.map { it.name }) { i ->
                        nav.push(Page.Grid(provs[i].name, if (r.kind == "movie") listOf("movie", "tv") else listOf("tv", "movie"),
                            GridSource(params = mapOf("watchProviders" to "${provs[i].id}", "watchRegion" to Seerr.REGION))))
                    }
                    t.collection?.let { c ->
                        Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f))
                            .clickable { nav.push(Page.Collection(c.id, c.name)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp, 60.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF26262E))) {
                                if (c.poster != null) AsyncImage(model = c.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Part of the ${c.name.removeSuffix(" Collection")} collection", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("See every movie in it", color = Muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            }
            if (recs.isNotEmpty()) item { Box(Modifier.offset(y = (-50).dp)) { SeerrCardRow("More like this", recs) } }
        }
    }
}

// ---------------------------------------------------------------------------
// Actor / director
// ---------------------------------------------------------------------------

@Composable
fun PersonScreen(personId: Int?, name: String) {
    val sr = seerr()
    val extras by SeerrExtras.data.collectAsState()
    val ver by Imdb.version.collectAsState()
    var p by remember { mutableStateOf<SeerrPersonDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("new") }
    var showAll by remember { mutableStateOf(false) }
    var bioOpen by remember { mutableStateOf(false) }
    LaunchedEffect(personId, name) {
        val s = sr ?: run { error = "Set your Seerr address in Settings first."; return@LaunchedEffect }
        try {
            val pid = personId ?: s.findPerson(name) ?: run { error = "Couldn't find $name in Seerr."; return@LaunchedEffect }
            p = s.person(pid)
        } catch (e: Exception) { error = "Couldn't load from Seerr: ${e.message}" }
    }
    val d = p
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TopBar(d?.name ?: name) }
        if (d == null) {
            item { if (error != null) Text(error!!, color = Muted, modifier = Modifier.padding(16.dp)) else Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner() } }
            return@LazyColumn
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Box(Modifier.size(110.dp, 140.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, null, tint = Muted)
                    if (d.photo != null) AsyncImage(model = d.photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(d.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val dept = mapOf("Acting" to "Actor", "Directing" to "Director", "Writing" to "Writer", "Production" to "Producer")[d.dept] ?: d.dept
                    if (dept.isNotEmpty()) Text(dept, color = LocalAccent.current, fontWeight = FontWeight.Bold)
                    if (d.birthday.isNotEmpty()) Text("Born ${niceDate(d.birthday)}", color = Muted, fontSize = 12.sp)
                    if (d.place.isNotEmpty()) Text(d.place, color = Muted, fontSize = 12.sp)
                    if (d.deathday.isNotEmpty()) Text("Died ${niceDate(d.deathday)}", color = Muted, fontSize = 12.sp)
                }
            }
            if (d.bio.isNotEmpty()) {
                Text(d.bio, fontSize = 14.sp, color = TextColor.copy(alpha = 0.9f), maxLines = if (bioOpen) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
                if (!bioOpen) Text("More", color = LocalAccent.current, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp).clickable { bioOpen = true })
            }
        }
        if (d.known.isNotEmpty()) item { SeerrCardRow("Known for", d.known) }
        if (d.groups.isNotEmpty()) {
            val g = d.groups.firstOrNull { it.name == group } ?: d.groups[0]
            val list = when (sort) {
                "imdb" -> { ver; g.items.sortedByDescending { Imdb.rating(extras[it.uid]?.imdb) ?: -1.0 } }
                "pop" -> g.items.sortedByDescending { it.votes }
                else -> g.items
            }
            val shown = if (showAll) list else list.take(40)
            item {
                Text("Filmography", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.groups.forEach { x -> Chip("${x.name}  ${x.items.size}", x.name == g.name) { group = x.name; showAll = false } }
                }
                Row(Modifier.padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort", color = Muted, fontSize = 12.sp)
                    listOf("new" to "Newest", "imdb" to "IMDb rating", "pop" to "Popular").forEach { (k, t) ->
                        Chip(t, sort == k) { sort = k; if (k == "imdb") g.items.forEach { SeerrExtras.want(sr, it) } }
                    }
                }
            }
            items(shown, key = { "${g.name}:${it.uid}" }) { r -> Box(Modifier.padding(16.dp, 4.dp)) { CreditRow(r) } }
            if (shown.size < list.size) item {
                OutlinedButton(onClick = { showAll = true }, modifier = Modifier.padding(16.dp)) { Text("Show all ${list.size}") }
            }
        }
    }
}

/** One title in a filmography, IMDb-app style: real poster, title with room to wrap, year and role, age rating
 *  and IMDb score, and an in-library mark or Request button. */
@Composable
fun CreditRow(r: SeerrResult) {
    val nav = LocalNav.current
    val sr = seerr()
    val scope = rememberCoroutineScope()
    val extras by SeerrExtras.data.collectAsState()
    val ver by Imdb.version.collectAsState()
    var requested by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(r.uid) { SeerrExtras.want(sr, r) }
    val e = extras[r.uid]
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
        .clickable { openResult(nav, r) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262E))) {
            if (r.poster != null) AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(listOf(r.year, r.role).filter { it.isNotEmpty() }.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 2)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                e?.cert?.takeIf { it.isNotEmpty() }?.let { OutlineBadge(it) }
                key(ver) { Imdb.rating(e?.imdb) }?.let { ImdbBadge(it, 10) }
                if (r.kind == "tv") Text(r.episodes?.let { "TV · $it ep" } ?: "TV", color = Muted, fontSize = 11.sp)
            }
        }
        when (if (requested) "req" else r.mark) {
            "lib" -> Icon(Icons.Filled.CheckCircle, "In your library", tint = LibGreen)
            "req" -> Icon(Icons.Filled.Schedule, "Requested", tint = ReqAmber)
            else -> IconButton(onClick = {
                val s = sr ?: return@IconButton
                working = true
                scope.launch { try { s.request(r, null); requested = true } catch (_: Exception) {}; working = false }
            }, enabled = !working, modifier = Modifier.size(36.dp).background(LocalAccent.current, CircleShape)) {
                Icon(Icons.Filled.Add, "Request", tint = Color.Black)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Collection
// ---------------------------------------------------------------------------

@Composable
fun CollectionScreen(id: Int, name: String) {
    val sr = seerr()
    val scope = rememberCoroutineScope()
    var c by remember { mutableStateOf<SeerrCollection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reqState by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        val s = sr ?: return@LaunchedEffect
        try { c = s.collection(id) } catch (e: Exception) { error = e.message }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(name)
        val col = c
        if (col == null) { if (error != null) Message(error!!) else CenterSpinner(); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    val have = col.parts.count { it.status == Seerr.AVAILABLE }
                    Text("${col.parts.size} movies · $have in your library", color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                    if (col.overview.isNotEmpty()) Text(col.overview, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    val missing = col.parts.filter { it.mark == null }
                    if (missing.isNotEmpty() || reqState == "done") {
                        Button(onClick = {
                            val s = sr ?: return@Button
                            reqState = "working"
                            scope.launch {
                                var failed = 0
                                var parts = col.parts
                                for (r in missing) {
                                    try { s.request(r, null); parts = parts.map { if (it.uid == r.uid) it.copy(status = Seerr.PENDING) else it } }
                                    catch (_: Exception) { failed++ }
                                }
                                c = col.copy(parts = parts)
                                reqState = "done"
                                if (failed > 0) note = "$failed couldn't be requested."
                            }
                        }, enabled = reqState == "", modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                            Text(when (reqState) { "working" -> "Requesting…"; "done" -> "✓  Requested"; else -> "+  Request missing (${missing.size})" },
                                fontWeight = FontWeight.Bold)
                        }
                    }
                    note?.let { Text(it, color = Muted, fontSize = 12.sp) }
                }
            }
            items(col.parts, key = { it.uid }) { SeerrCard(it, 104.dp) }
        }
    }
}

// ---------------------------------------------------------------------------
// Your requests (plainly: what's here, what's on its way, what isn't out yet)
// ---------------------------------------------------------------------------

data class EpisodeState(val number: Int, val name: String, val airDate: String, val state: String)
data class SeasonProgress(val number: Int, val name: String, val episodes: List<EpisodeState>)

suspend fun tvProgress(sr: Seerr, r: SeerrRequest): List<SeasonProgress> {
    val have = HashSet<String>()
    r.ratingKey?.let { rk -> try { AppState.client.allEpisodes(rk).forEach { have += "${it.parentIndex}:${it.index}" } } catch (_: Exception) {} }
    val today = parseDay(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
    val numbers = r.seasonsRequested.ifEmpty { r.seasons.map { it.number } }
    return numbers.map { n ->
        val eps = try { sr.seasonEpisodes(r.tmdbId, n) } catch (_: Exception) { emptyList() }
        SeasonProgress(n, r.seasons.firstOrNull { it.number == n }?.name?.ifEmpty { null } ?: "Season $n", eps.map { e ->
            val d = parseDay(e.airDate)
            val st = when {
                "$n:${e.number}" in have -> "have"
                d != null && today != null && !d.after(today) -> "missing"
                else -> "future"
            }
            EpisodeState(e.number, e.name, e.airDate, st)
        })
    }
}

fun progressText(prog: List<SeasonProgress>): Pair<String, Color> {
    val all = prog.flatMap { it.episodes }
    if (all.isEmpty()) return "" to Muted
    val have = all.count { it.state == "have" }
    val missing = all.count { it.state == "missing" }
    val future = all.filter { it.state == "future" }
    val out = have + missing
    val next = future.map { it.airDate }.filter { it.isNotEmpty() }.minOrNull() ?: ""
    return when {
        missing == 0 && out > 0 && future.isNotEmpty() ->
            ("All $out episodes out so far are here · ${future.size} more coming" + if (next.isEmpty()) "" else ", next ${shortDate(next)}") to LibGreen
        missing == 0 && out > 0 -> "All $out episodes are here" to LibGreen
        out == 0 -> ("Not out yet" + if (next.isEmpty()) "" else " · starts ${shortDate(next)}") to Muted
        else -> ("$have of $out episodes out so far are here · $missing still coming in" +
            if (future.isNotEmpty()) " · ${future.size} not out yet" else "") to ReqAmber
    }
}

fun requestState(r: SeerrRequest): Pair<String, Color> = when {
    r.reqStatus == 3 -> "Declined" to Color(0xFFE5383B)
    r.status == Seerr.AVAILABLE -> "Available" to LibGreen
    r.status == Seerr.PARTIAL -> "Partly available" to Color(0xFF2F8CFF)
    r.kind == "movie" && parseDay(r.date)?.after(Date()) == true -> "Not out yet" to Color(0xFF6A6A76)
    r.status == Seerr.PROCESSING || r.reqStatus == 2 -> "Requested" to ReqAmber
    else -> "Waiting for approval" to Color(0xFF8A5CFF)
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp))
}

private val FILTERS = listOf("all" to "All", "waiting" to "Requested", "partial" to "Partly available", "available" to "Available")

@Composable
fun RequestsScreen() {
    val sr = seerr()
    var items by remember { mutableStateOf<List<SeerrRequest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        val s = sr ?: run { error = "Set your Seerr address in Settings first."; return@LaunchedEffect }
        error = null
        try { items = s.myRequests() } catch (e: Exception) { error = e.message }
    }
    fun matching(k: String): List<SeerrRequest> {
        val all = items ?: emptyList()
        return when (k) {
            "waiting" -> all.filter { it.status != Seerr.AVAILABLE && it.status != Seerr.PARTIAL && it.reqStatus != 3 }
            "partial" -> all.filter { it.status == Seerr.PARTIAL }
            "available" -> all.filter { it.status == Seerr.AVAILABLE }
            else -> all
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopBar("Requests")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEach { (k, t) -> Chip(if (items == null) t else "$t  ${matching(k).size}", filter == k) { filter = k } }
            }
        }
        when {
            error != null -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = Muted)
                    OutlinedButton(onClick = { attempt++ }, modifier = Modifier.padding(top = 10.dp)) { Text("Try again") }
                }
            }
            items == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner() } }
            matching(filter).isEmpty() -> item { Text("Nothing here.", color = Muted, modifier = Modifier.padding(16.dp)) }
            else -> items(matching(filter), key = { it.requestId }) { r -> Box(Modifier.padding(16.dp, 4.dp)) { RequestRow(r) } }
        }
    }
}

@Composable
private fun RequestRow(r: SeerrRequest) {
    val nav = LocalNav.current
    val sr = seerr()
    val needsProgress = r.kind == "tv" && r.status != Seerr.AVAILABLE && r.reqStatus != 3
    var progress by remember { mutableStateOf<List<SeasonProgress>?>(null) }
    LaunchedEffect(r.requestId) { if (needsProgress && sr != null) progress = tvProgress(sr, r) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
        .clickable { if (r.kind == "tv") nav.push(Page.RequestStatus(r)) else openResult(nav, r.asResult()) }.padding(8.dp)) {
        Box(Modifier.size(64.dp, 96.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262E))) {
            if (r.poster != null) AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(r.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            val bits = mutableListOf(r.year, if (r.kind == "movie") "Movie" else "TV show", r.cert)
            if (r.kind == "tv" && r.seasonsRequested.isNotEmpty())
                bits += (if (r.seasonsRequested.size == 1) "Season " else "Seasons ") + r.seasonsRequested.joinToString(", ")
            if (r.kind == "movie" && parseDay(r.date)?.after(Date()) == true) bits += "comes out ${niceDate(r.date)}"
            Text(bits.filter { it.isNotEmpty() }.joinToString(" · "), color = Muted, fontSize = 12.sp)
            val pr = progress
            if (pr != null) {
                val (t, c) = progressText(pr)
                if (t.isNotEmpty()) Text(t, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            } else if (needsProgress) Text("Checking episodes…", color = Muted, fontSize = 12.sp)
            val (w, c) = requestState(r)
            StatusPill(w, c)
            if (r.requestedAt.isNotEmpty()) Text("Requested ${niceDate(r.requestedAt)}", color = Muted, fontSize = 11.sp)
        }
    }
}

/** A show's request, episode by episode: here / out but not here yet / not out yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestStatusScreen(r: SeerrRequest) {
    val nav = LocalNav.current
    val sr = seerr()
    var progress by remember { mutableStateOf<List<SeasonProgress>?>(null) }
    LaunchedEffect(r.requestId) { if (sr != null) progress = tvProgress(sr, r) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TopBar(r.title)
            Row(Modifier.padding(horizontal = 16.dp)) {
                Box(Modifier.size(100.dp, 150.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF26262E))) {
                    if (r.poster != null) AsyncImage(model = r.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val (w, c) = requestState(r)
                    StatusPill(w, c)
                    progress?.let { val (t, col) = progressText(it); Text(t, color = col, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                }
            }
            Row(Modifier.padding(16.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                r.ratingKey?.let { rk ->
                    Button(onClick = { nav.open(Item(JSONObject().put("ratingKey", rk).put("type", "show").put("title", r.title))) },
                        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
                        Icon(Icons.Filled.PlayArrow, null); Text("Open", fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedButton(onClick = { nav.push(Page.Title(r.asResult())) }) { Text("Title page") }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("have" to "In your library", "missing" to "On its way", "future" to "Not out yet").forEach { (st, t) ->
                    Box(Modifier.size(16.dp, 12.dp).then(tileStyle(st)))
                    Spacer(Modifier.width(4.dp))
                    Text(t, color = Muted, fontSize = 11.sp)
                }
            }
        }
        val pr = progress
        if (pr == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner() } }
        else items(pr, key = { it.number }) { s ->
            Column(Modifier.padding(16.dp, 14.dp, 16.dp, 0.dp)) {
                Text(s.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (s.episodes.isEmpty()) Text("No episode list yet.", color = Muted, fontSize = 12.sp)
                else {
                    val have = s.episodes.count { it.state == "have" }
                    val out = s.episodes.count { it.state != "future" }
                    Text("$have of ${s.episodes.size} here · $out out so far", color = Muted, fontSize = 12.sp)
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.episodes.forEach { e ->
                            Column(Modifier.size(76.dp, 46.dp).then(tileStyle(e.state)), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                val fg = if (e.state == "future") Muted else Color.White
                                Text("E${e.number}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
                                Text(when (e.state) { "have" -> "✓"; "missing" -> "coming in"; else -> shortDate(e.airDate).ifEmpty { "TBA" } },
                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tileStyle(st: String): Modifier = when (st) {
    "have" -> Modifier.clip(RoundedCornerShape(6.dp)).background(LibGreen.copy(alpha = 0.75f)).border(1.5.dp, LibGreen, RoundedCornerShape(6.dp))
    "missing" -> Modifier.clip(RoundedCornerShape(6.dp)).background(ReqAmber.copy(alpha = 0.25f)).border(1.5.dp, ReqAmber, RoundedCornerShape(6.dp))
    else -> Modifier.clip(RoundedCornerShape(6.dp)).border(1.5.dp, Color.Gray, RoundedCornerShape(6.dp))
}
