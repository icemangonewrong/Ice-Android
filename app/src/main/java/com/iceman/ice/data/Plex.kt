package com.iceman.ice.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PlexException(message: String) : IOException(message)

// ---------------------------------------------------------------------------
// Models (thin wrappers around Plex's JSON)
// ---------------------------------------------------------------------------

private fun JSONObject.str(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
private fun JSONObject.objs(k: String): List<JSONObject> {
    val a = optJSONArray(k) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

data class Section(val key: String, val title: String, val type: String) {
    val isVideo get() = type == "movie" || type == "show"
}

data class Marker(val type: String, val start: Double, val end: Double) {
    val isIntro get() = type == "intro"
    val isCredits get() = type == "credits"
}

data class ExternalSub(val key: String, val title: String, val lang: String)

/** A subtitle the Plex server found online (OpenSubtitles etc.). */
data class OnlineSub(val key: String, val title: String, val provider: String, val format: String,
                     val score: Double?, val perfect: Boolean, val hi: Boolean, val forced: Boolean) {
    val details: String get() = listOfNotNull(provider.ifEmpty { null }, format.ifEmpty { null }, if (hi) "SDH" else null,
        if (forced) "Forced" else null, score?.let { "match ${if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()}" })
        .joinToString("  ·  ")
}

class Item(val j: JSONObject) {
    val key: String get() = j.optString("ratingKey")
    val type: String get() = j.optString("type")
    val title: String get() = j.str("title") ?: "Untitled"
    val rawTitle: String? get() = j.str("title")
    val summary: String? get() = j.str("summary")
    val year: Int? get() = j.int("year")
    val thumb: String? get() = j.str("thumb")
    val art: String? get() = j.str("art") ?: j.str("grandparentArt")
    val grandparentTitle: String? get() = j.str("grandparentTitle")
    val parentTitle: String? get() = j.str("parentTitle")
    val grandparentKey: String? get() = j.str("grandparentRatingKey")
    val parentKey: String? get() = j.str("parentRatingKey")
    val index: Int? get() = j.int("index")
    val parentIndex: Int? get() = j.int("parentIndex")
    val durationMs: Long get() = j.long("duration") ?: 0
    val offsetMs: Long get() = j.long("viewOffset") ?: 0
    val viewCount: Int get() = j.int("viewCount") ?: 0
    val leafCount: Int? get() = j.int("leafCount")
    val viewedLeafCount: Int? get() = j.int("viewedLeafCount")
    val childCount: Int? get() = j.int("childCount")
    val sectionId: String? get() = j.str("librarySectionID")
    val contentRating: String? get() = j.str("contentRating")
    val rating: Double? get() = j.dbl("audienceRating") ?: j.dbl("rating")
    val tagline: String? get() = j.str("tagline")
    val studio: String? get() = j.str("studio")

    val isPlayable get() = type == "movie" || type == "episode"
    val isEpisode get() = type == "episode"

    /** Show name for episodes, title for everything else. */
    val displayTitle: String get() = if (isEpisode) grandparentTitle ?: title else title

    /** "S2 · E5 · Episode name", or the year. */
    val displaySubtitle: String?
        get() {
            if (isEpisode) return listOfNotNull(parentIndex?.let { "S$it" }, index?.let { "E$it" }, rawTitle)
                .joinToString(" · ")
            if (type == "season") return parentTitle
            return year?.toString()
        }

    val episodeCode: String
        get() = listOfNotNull(parentIndex?.let { "S$it" }, index?.let { "E$it" }).joinToString(" · ")

    val posterPath: String?
        get() = if (isEpisode) j.str("grandparentThumb") ?: j.str("parentThumb") ?: thumb
        else thumb ?: j.str("parentThumb")

    val progress: Float?
        get() = if (durationMs > 0 && offsetMs > 0) (offsetMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    val isWatched: Boolean
        get() {
            val l = leafCount; val v = viewedLeafCount
            return if (l != null && v != null) l > 0 && v >= l else viewCount > 0
        }

    /** Checkmark only on fully watched items. */
    val showCheck get() = isWatched && progress == null

    private val media get() = j.objs("Media").firstOrNull()
    private val part get() = media?.objs("Part")?.firstOrNull()
    val partKey: String? get() = part?.str("key")
    val fileSize: Long get() = part?.long("size") ?: 0
    val container: String? get() = media?.str("container") ?: part?.str("container")
    val bitrateKbps: Int? get() = media?.int("bitrate")
    val videoCodec: String? get() = media?.str("videoCodec")
    val audioCodec: String? get() = media?.str("audioCodec")
    val audioChannels: Int? get() = media?.int("audioChannels")
    val videoResolution: String? get() = media?.str("videoResolution")

    val genres: List<String> get() = j.objs("Genre").mapNotNull { it.str("tag") }
    val directors: List<String> get() = j.objs("Director").mapNotNull { it.str("tag") }
    val cast: List<Pair<String, String?>> get() = j.objs("Role").mapNotNull { r -> r.str("tag")?.let { it to r.str("role") } }

    val markers: List<Marker>
        get() = j.objs("Marker").mapNotNull {
            val t = it.str("type") ?: return@mapNotNull null
            val s = (it.long("startTimeOffset") ?: 0) / 1000.0
            val e = (it.long("endTimeOffset") ?: 0) / 1000.0
            Marker(t, s, e)
        }

    /** Subtitle files Plex keeps next to the video (.srt etc). */
    val externalSubtitles: List<ExternalSub>
        get() = (part?.objs("Stream") ?: emptyList()).filter { it.int("streamType") == 3 && it.str("key") != null }
            .map { ExternalSub(it.str("key")!!, it.str("displayTitle") ?: it.str("title") ?: "Subtitles",
                               it.str("languageCode") ?: "") }

    override fun equals(other: Any?) = other is Item && other.key == key
    override fun hashCode() = key.hashCode()

    companion object {
        fun stub(key: String, type: String) = Item(JSONObject().put("ratingKey", key).put("type", type))
    }
}

data class Server(
    val name: String,
    val clientIdentifier: String,
    val accessToken: String,
    val baseURL: String,
    val allURIs: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("name", name).put("clientIdentifier", clientIdentifier).put("accessToken", accessToken)
        .put("baseURL", baseURL).put("allURIs", JSONArray(allURIs)).toString()

    companion object {
        fun fromJson(s: String?): Server? = try {
            val o = JSONObject(s ?: return null)
            val uris = o.optJSONArray("allURIs")
            Server(o.getString("name"), o.getString("clientIdentifier"), o.getString("accessToken"),
                o.getString("baseURL"), (0 until (uris?.length() ?: 0)).map { uris!!.getString(it) })
        } catch (_: Exception) { null }
    }
}

data class Resource(val name: String, val clientIdentifier: String, val accessToken: String?,
                    val connections: List<Triple<String, Boolean, Boolean>>)  // uri, local, relay

data class Pin(val id: Long, val code: String, val authToken: String?)

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

class PlexClient(val clientId: String) {
    @Volatile var token: String? = null
    @Volatile var server: Server? = null
    var onServerChanged: ((Server) -> Unit)? = null

    private fun headers(tok: String?): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json",
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Version" to "1.0",
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to "Android",
            "X-Plex-Device-Name" to "Ice (Android)",
        )
        if (tok != null) h["X-Plex-Token"] = tok
        return h
    }

    /** Blocking HTTP call. Returns the body. */
    private fun http(url: String, method: String = "GET", tok: String?, timeoutMs: Int = 15000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            for ((k, v) in headers(tok)) c.setRequestProperty(k, v)
            if (method == "POST") { c.doOutput = true; c.outputStream.close() }
            val code = c.responseCode
            if (code !in 200..299) throw PlexException("Plex returned an error ($code).")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // -- sign in -----------------------------------------------------------

    suspend fun createPin(): Pin = withContext(Dispatchers.IO) {
        pin(JSONObject(http("https://plex.tv/api/v2/pins?strong=true", "POST", null)))
    }

    suspend fun checkPin(id: Long): Pin = withContext(Dispatchers.IO) {
        pin(JSONObject(http("https://plex.tv/api/v2/pins/$id", tok = null)))
    }

    private fun pin(o: JSONObject) = Pin(o.getLong("id"), o.getString("code"), o.str("authToken"))

    fun authUrl(pin: Pin): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://app.plex.tv/auth#?clientID=${e(clientId)}&code=${e(pin.code)}" +
            "&context%5Bdevice%5D%5Bproduct%5D=${e(PRODUCT)}"
    }

    // -- servers -----------------------------------------------------------

    suspend fun resources(): List<Resource> = withContext(Dispatchers.IO) {
        val tok = token ?: throw PlexException("Not signed in to Plex.")
        val arr = JSONArray(http("https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", tok = tok))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { (it.str("provides") ?: "").contains("server") }
            .map { r ->
                Resource(r.optString("name"), r.optString("clientIdentifier"), r.str("accessToken"),
                    r.objs("connections").map { Triple(it.optString("uri"), it.optBoolean("local"), it.optBoolean("relay")) })
            }
    }

    suspend fun connect(r: Resource): Server = withContext(Dispatchers.IO) {
        val tok = r.accessToken ?: token ?: ""
        val uris = r.connections.sortedBy { if (it.third) 2 else if (it.second) 0 else 1 }.map { it.first }
        val ok = firstReachable(uris, tok) ?: throw PlexException("Couldn't reach the Plex server on any address.")
        Server(r.name, r.clientIdentifier, tok, ok, uris)
    }

    private fun firstReachable(uris: List<String>, tok: String): String? {
        for (u in uris) {
            try { http("$u/identity", tok = tok, timeoutMs = 5000); return u } catch (_: Exception) {}
        }
        return null
    }

    // -- server requests (with fail-over between addresses) -----------------

    private fun buildUrl(base: String, path: String, query: Map<String, String>): String {
        val b = Uri.parse(base + path).buildUpon()
        for ((k, v) in query) b.appendQueryParameter(k, v)
        return b.build().toString()
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject {
        val s = server ?: throw PlexException("No Plex server selected.")
        return try {
            JSONObject(http(buildUrl(s.baseURL, path, query), tok = s.accessToken))
        } catch (e: PlexException) {
            throw e
        } catch (e: IOException) {
            // That address stopped working (e.g. left home Wi-Fi). Try the others.
            val other = firstReachable(s.allURIs.filter { it != s.baseURL }, s.accessToken) ?: throw e
            val updated = s.copy(baseURL = other)
            server = updated
            onServerChanged?.invoke(updated)
            JSONObject(http(buildUrl(other, path, query), tok = s.accessToken))
        }
    }

    private fun items(o: JSONObject) = o.optJSONObject("MediaContainer")?.objs("Metadata")?.map { Item(it) } ?: emptyList()
    private fun hubs(o: JSONObject) = o.optJSONObject("MediaContainer")?.objs("Hub") ?: emptyList()

    suspend fun sections(): List<Section> = withContext(Dispatchers.IO) {
        get("/library/sections").optJSONObject("MediaContainer")?.objs("Directory")
            ?.map { Section(it.optString("key"), it.optString("title"), it.optString("type")) }
            ?.filter { it.isVideo } ?: emptyList()
    }

    suspend fun continueWatching(): List<Item> = withContext(Dispatchers.IO) {
        try {
            val list = hubs(get("/hubs/home/continueWatching")).flatMap { h -> h.objs("Metadata").map { Item(it) } }
            if (list.isNotEmpty()) return@withContext list
        } catch (_: Exception) {}
        try {
            val hs = hubs(get("/hubs", mapOf("count" to "30")))
            val h = hs.firstOrNull { (it.str("hubIdentifier") ?: "").contains("continue") }
                ?: hs.firstOrNull { (it.str("hubIdentifier") ?: "").contains("ondeck") }
            val list = h?.objs("Metadata")?.map { Item(it) } ?: emptyList()
            if (list.isNotEmpty()) return@withContext list
        } catch (_: Exception) {}
        items(get("/library/onDeck"))
    }

    suspend fun recentlyAdded(section: String, count: Int = 30): List<Item> = withContext(Dispatchers.IO) {
        items(get("/library/sections/$section/recentlyAdded",
            mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "$count")))
    }

    suspend fun library(section: String, sort: String, unwatched: Boolean, start: Int, size: Int): Pair<List<Item>, Int> =
        withContext(Dispatchers.IO) {
            val q = mutableMapOf("sort" to sort, "X-Plex-Container-Start" to "$start", "X-Plex-Container-Size" to "$size")
            if (unwatched) q["unwatched"] = "1"
            val o = get("/library/sections/$section/all", q)
            items(o) to (o.optJSONObject("MediaContainer")?.optInt("totalSize", -1) ?: -1)
        }

    suspend fun children(key: String): List<Item> = withContext(Dispatchers.IO) { items(get("/library/metadata/$key/children")) }

    suspend fun allEpisodes(showKey: String): List<Item> = withContext(Dispatchers.IO) { items(get("/library/metadata/$showKey/allLeaves")) }

    suspend fun metadata(key: String): Item? = withContext(Dispatchers.IO) { items(get("/library/metadata/$key")).firstOrNull() }

    /** Full details including intro/credits markers. */
    suspend fun details(key: String): Item? = withContext(Dispatchers.IO) {
        items(get("/library/metadata/$key", mapOf("includeMarkers" to "1"))).firstOrNull()
    }

    suspend fun nextEpisode(showKey: String): Item? {
        val eps = allEpisodes(showKey)
        return eps.firstOrNull { it.offsetMs > 0 } ?: eps.firstOrNull { it.viewCount == 0 } ?: eps.firstOrNull()
    }

    suspend fun episodeAdjacent(item: Item, offset: Int): Item? {
        if (!item.isEpisode) return null
        val show = item.grandparentKey ?: return null
        val eps = allEpisodes(show)
        val i = eps.indexOfFirst { it.key == item.key }
        if (i < 0) return null
        return eps.getOrNull(i + offset)
    }

    suspend fun search(text: String): List<Item> = withContext(Dispatchers.IO) {
        hubs(get("/hubs/search", mapOf("query" to text, "limit" to "20")))
            .filter { it.str("type") in listOf("movie", "show", "episode") }
            .flatMap { h -> h.objs("Metadata").map { Item(it) } }
    }

    // -- watch state ---------------------------------------------------------

    suspend fun reportTimeline(item: Item, state: String, timeMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        try {
            get("/:/timeline", mapOf("ratingKey" to item.key, "key" to "/library/metadata/${item.key}",
                "state" to state, "time" to "$timeMs", "duration" to "$durationMs"))
        } catch (_: Exception) {}
        Unit
    }

    suspend fun markWatched(key: String) = withContext(Dispatchers.IO) {
        try { get("/:/scrobble", mapOf("identifier" to "com.plexapp.plugins.library", "key" to key)) } catch (_: Exception) {}
        Unit
    }

    suspend fun markUnwatched(key: String) = withContext(Dispatchers.IO) {
        try { get("/:/unscrobble", mapOf("identifier" to "com.plexapp.plugins.library", "key" to key)) } catch (_: Exception) {}
        Unit
    }

    // -- URLs ------------------------------------------------------------------

    fun imageUrl(path: String?, width: Int = 300, height: Int = 450): String? {
        val s = server ?: return null
        path ?: return null
        return buildUrl(s.baseURL, "/photo/:/transcode", mapOf("width" to "$width", "height" to "$height",
            "minSize" to "1", "upscale" to "1", "url" to path, "X-Plex-Token" to s.accessToken))
    }

    /** Direct-play address of the original file (no server-side conversion). */
    fun directUrl(item: Item): String? {
        val s = server ?: return null
        val pk = item.partKey ?: return null
        return buildUrl(s.baseURL, pk, mapOf("X-Plex-Token" to s.accessToken,
            "X-Plex-Client-Identifier" to clientId, "X-Plex-Product" to PRODUCT))
    }

    // -- online subtitles (the same search Plex's own apps use) -------------

    private fun JSONObject.flag(k: String): Boolean = when (val v = opt(k)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", true)
        else -> false
    }

    /** Subtitles the server finds online for this video, best first. */
    suspend fun searchSubtitles(key: String, language: String, hearingImpaired: Boolean): List<OnlineSub> = withContext(Dispatchers.IO) {
        val s = server ?: throw PlexException("No Plex server selected.")
        val o = JSONObject(http(buildUrl(s.baseURL, "/library/metadata/$key/subtitles",
            mapOf("language" to language, "hearingImpaired" to if (hearingImpaired) "1" else "0", "forced" to "0")),
            tok = s.accessToken, timeoutMs = 40000))
        (o.optJSONObject("MediaContainer")?.objs("Stream") ?: emptyList()).mapNotNull {
            val k = it.str("key") ?: return@mapNotNull null
            OnlineSub(k, it.str("title") ?: it.str("displayTitle") ?: "Subtitle", it.str("providerTitle") ?: "",
                (it.str("format") ?: it.str("codec") ?: "").uppercase(), it.dbl("score"),
                it.flag("perfectMatch"), it.flag("hearingImpaired"), it.flag("forced"))
        }.sortedWith(compareBy<OnlineSub> { !it.perfect }.thenByDescending { it.score ?: 0.0 })
    }

    /** Tells the server to fetch that subtitle and keep it with the video (it shows up a moment later). */
    suspend fun downloadSubtitle(key: String, streamKey: String) = withContext(Dispatchers.IO) {
        val s = server ?: throw PlexException("No Plex server selected.")
        http(buildUrl(s.baseURL, "/library/metadata/$key/subtitles", mapOf("key" to streamKey)), method = "PUT",
            tok = s.accessToken, timeoutMs = 40000)
        Unit
    }

    fun streamUrl(key: String): String? {
        val s = server ?: return null
        return buildUrl(s.baseURL, key, mapOf("X-Plex-Token" to s.accessToken))
    }

    companion object {
        const val PRODUCT = "Ice"
    }
}
