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
    val sr = seerr()
    val extras by com.iceman.ice.seerr.SeerrExtras.data.collectAsState()
    val ver by com.iceman.ice.data.Imdb.version.collectAsState()
    LaunchedEffect(r.uid) { com.iceman.ice.seerr.SeerrExtras.want(sr, r) }
    val e = extras[r.uid]
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SeerrPoster(r.poster, 50.dp, 75.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(r.title, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(r.year.ifEmpty { null }, e?.cert?.ifEmpty { null }, r.kindText, r.statusText).joinToString(" · "),
                fontSize = 12.sp, color = Muted)
            key(ver) { com.iceman.ice.data.Imdb.rating(e?.imdb) }?.let { Box(Modifier.padding(top = 3.dp)) { ImdbBadge(it, 10) } }
        }
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
