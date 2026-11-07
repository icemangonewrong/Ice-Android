package com.iceman.ice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.iceman.ice.data.AppState
import com.iceman.ice.seerr.Seerr
import com.iceman.ice.seerr.SeerrDetails
import com.iceman.ice.seerr.SeerrResult
import kotlinx.coroutines.launch

@Composable
fun SeerrPoster(url: String?, w: Dp, h: Dp) {
    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Color(0xFF26262E)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Movie, null, tint = Muted.copy(alpha = 0.5f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun SeerrRow(r: SeerrResult, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SeerrPoster(r.poster, 50.dp, 75.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(r.title, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(r.year.ifEmpty { null }, r.kindText, r.statusText).joinToString(" · "), fontSize = 12.sp, color = Muted)
        }
    }
}

private val LOCKED = setOf(Seerr.PENDING, Seerr.PROCESSING, Seerr.AVAILABLE)

/** Something not in the library: details and a Request button (pick seasons for shows). */
@Composable
fun RequestScreen(r: SeerrResult) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<SeerrDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var state by remember { mutableStateOf("") }          // "", "working", "done"
    var requestError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r) {
        val sr = Seerr.get(ctx, AppState.client.token)
        if (sr == null) { error = "Set your Seerr address in Settings first."; return@LaunchedEffect }
        try {
            val d = sr.details(r)
            info = d
            picked = d.seasons.filter { it.status !in LOCKED }.map { it.number }.toSet()
        } catch (e: Exception) { error = "Couldn't load from Seerr: ${e.message}" }
    }
    fun send(seasons: List<Int>?) {
        val sr = Seerr.get(ctx, AppState.client.token) ?: return
        state = "working"; requestError = null
        scope.launch {
            try { sr.request(r, seasons); state = "done" }
            catch (e: Exception) { state = ""; requestError = "Couldn't request: ${e.message}" }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                SeerrPoster(r.poster, 110.dp, 165.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Not in your library", color = LocalAccent.current, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(r.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val bits = listOfNotNull(r.year.ifEmpty { null }, r.kindText, info?.runtime?.let { "$it min" }) + (info?.genres ?: emptyList()).take(3)
                    Text(bits.joinToString(" · "), fontSize = 12.sp, color = Muted)
                    info?.imdb?.let {
                        Text("IMDb %.1f".format(it), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(top = 6.dp).background(Color(0xFFF5C518), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            Text(info?.overview ?: r.overview, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(16.dp))
        }
        val d = info
        when {
            error != null -> item { Text(error!!, color = Muted) }
            d == null -> item { CenterSpinner() }
            r.kind == "movie" -> item {
                if (d.status in LOCKED) Text("✓  ${Seerr.STATUS_TEXT[d.status]}", color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
                else RequestButton("Request", state, enabled = true) { send(null) }
                requestError?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
            }
            else -> {
                item { Text("Seasons", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp)) }
                items(d.seasons, key = { it.number }) { s ->
                    val locked = s.status in LOCKED
                    val on = locked || s.number in picked
                    Row(Modifier.fillMaxWidth().clickable(enabled = !locked && state != "done") {
                        picked = if (s.number in picked) picked - s.number else picked + s.number
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                            tint = if (locked) Muted else LocalAccent.current)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.name)
                            Text(listOfNotNull("${s.episodes} episodes", Seerr.STATUS_TEXT[s.status]).joinToString(" · "), fontSize = 12.sp, color = Muted)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(10.dp))
                    if (d.seasons.all { it.status in LOCKED }) Text("Every season is already available or requested.", color = Muted)
                    else RequestButton("Request selected seasons", state, enabled = picked.isNotEmpty()) { send(picked.sorted()) }
                    requestError?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun RequestButton(title: String, state: String, enabled: Boolean, onClick: () -> Unit) {
    if (state == "done") {
        Text("✓  Requested", color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
        return
    }
    Button(onClick = onClick, enabled = enabled && state != "working", modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black)) {
        Text(if (state == "working") "Requesting…" else title, fontWeight = FontWeight.Bold)
    }
}

/** Settings: the Seerr address and a test button. */
@Composable
fun SeerrSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Seerr.savedUrl(ctx)) }
    var result by remember { mutableStateOf("") }
    OutlinedTextField(url, { url = it; Seerr.saveUrl(ctx, it) }, singleLine = true, label = { Text("Seerr address") },
        placeholder = { Text(Seerr.DEFAULT_URL) }, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = {
            Seerr.saveUrl(ctx, url)
            val sr = Seerr.get(ctx, AppState.client.token)
            if (sr == null) { result = "Enter the address first."; return@OutlinedButton }
            result = "Checking…"
            scope.launch { result = try { "Connected as ${sr.test()}" } catch (e: Exception) { e.message ?: "Failed" } }
        }) { Text("Test connection") }
        Spacer(Modifier.width(12.dp))
        Text(result, fontSize = 12.sp, color = Muted)
    }
}
