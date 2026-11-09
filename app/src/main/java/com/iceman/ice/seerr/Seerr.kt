package com.iceman.ice.seerr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A movie or show from Seerr (search, discover, credits). */
data class SeerrResult(
    val id: Int, val kind: String, val title: String, val year: String, val overview: String,
    val poster: String?, val status: Int,
    val backdrop: String? = null, val ratingKey: String? = null, val votes: Int = 0, val vote: Double? = null,
    val date: String = "", val role: String = "", val department: String = "", val episodes: Int? = null,
) {
    val uid: String get() = "$kind:$id"
    val kindText: String get() = if (kind == "movie") "Movie" else "TV show"
    val statusText: String? get() = Seerr.STATUS_TEXT[status]
    /** "lib" (in your library), "req" (requested) or null. */
    val mark: String? get() = when (status) {
        Seerr.AVAILABLE, Seerr.PARTIAL -> "lib"
        Seerr.PENDING, Seerr.PROCESSING -> "req"
        else -> null
    }
}

data class SeerrSeason(val number: Int, val name: String, val episodes: Int, val status: Int, val airDate: String = "")
data class SeerrPerson(val id: Int?, val name: String, val role: String, val photo: String?)
data class SeerrLink(val id: Int, val name: String, val poster: String? = null)
data class SeerrService(val id: Int, val name: String, val logo: String?)
data class CreditGroup(val name: String, val items: List<SeerrResult>)
data class SeerrEpisode(val number: Int, val name: String, val airDate: String)

data class SeerrTitle(
    val result: SeerrResult, val imdbId: String?, val cert: String, val tagline: String, val runtime: Int?,
    val genres: List<String>, val cast: List<SeerrPerson>, val directors: List<SeerrPerson>, val writers: List<SeerrPerson>,
    val creators: List<SeerrPerson>, val studio: SeerrLink?, val network: SeerrLink?, val providers: List<SeerrLink>,
    val collection: SeerrLink?, val trailer: String?, val seasons: List<SeerrSeason>, val numSeasons: Int?,
)

data class SeerrPersonDetails(
    val id: Int, val name: String, val bio: String, val birthday: String, val deathday: String, val place: String,
    val dept: String, val photo: String?, val known: List<SeerrResult>, val groups: List<CreditGroup>,
)

data class SeerrCollection(val name: String, val overview: String, val backdrop: String?, val parts: List<SeerrResult>)

data class SeerrRequest(
    val requestId: Int, val reqStatus: Int, val kind: String, val tmdbId: Int, val status: Int, val ratingKey: String?,
    val requestedAt: String, val seasonsRequested: List<Int>,
    val title: String = "", val year: String = "", val date: String = "", val poster: String? = null, val cert: String = "",
    val seasons: List<SeerrSeason> = emptyList(),
) {
    fun asResult() = SeerrResult(tmdbId, kind, title, year, "", poster, status, ratingKey = ratingKey, date = date)
}

data class SeerrPage<T>(val items: List<T>, val pages: Int, val total: Int?)

class SeerrException(msg: String) : IOException(msg)

/**
 * Seerr: browse and search everything (not just the library) and request it. Ice signs in with the Plex account it
 * already has, so requests show up in Seerr under that user as if made on Seerr's own website.
 */
class Seerr(url: String, private val plexToken: String) {
    companion object {
        const val UNKNOWN = 1; const val PENDING = 2; const val PROCESSING = 3; const val PARTIAL = 4; const val AVAILABLE = 5
        val STATUS_TEXT = mapOf(2 to "Requested", 3 to "Requested", 4 to "Partly available", 5 to "Available")
        const val DEFAULT_URL = "http://10.66.66.1:5056"
        const val REGION = "US"
        const val IMG = "https://image.tmdb.org/t/p/w342"
        const val BACKDROP = "https://image.tmdb.org/t/p/w1280"
        const val FACE = "https://image.tmdb.org/t/p/w185"
        const val LOGO = "https://image.tmdb.org/t/p/w154"

        // TMDB ids of TV networks and movie studios (the same ones Seerr's own Discover page shows).
        val NETWORKS = listOf("Netflix" to 213, "HBO" to 49, "Max" to 3186, "Disney+" to 2739, "Prime Video" to 1024,
            "Apple TV+" to 2552, "Hulu" to 453, "Paramount+" to 4330, "Peacock" to 3353, "AMC" to 174, "FX" to 88,
            "Showtime" to 67, "Starz" to 318, "Paramount Network" to 2076, "NBC" to 6, "CBS" to 16, "ABC" to 2,
            "FOX" to 19, "History" to 65, "Discovery" to 64)
        val STUDIOS = listOf("A24" to 41077, "Marvel Studios" to 420, "Pixar" to 3, "Walt Disney Pictures" to 2,
            "Warner Bros." to 174, "Universal" to 33, "Paramount" to 4, "Sony Pictures" to 34, "20th Century" to 127928,
            "DreamWorks" to 521, "Lucasfilm" to 1, "Legendary" to 923, "Blumhouse" to 3172, "Lionsgate" to 1632)
        private val SERVICES = listOf("Netflix" to listOf("netflix"), "Disney+" to listOf("disney plus", "disney+"),
            "Max" to listOf("max", "hbo max"), "Prime Video" to listOf("amazon prime video"),
            "Apple TV+" to listOf("apple tv plus", "apple tv+", "apple tv"), "Hulu" to listOf("hulu"),
            "Peacock" to listOf("peacock premium", "peacock"),
            "Paramount+" to listOf("paramount plus", "paramount+", "paramount plus premium"))

        /** No address until it's set up in Settings. (Phones that already used Seerr with the old built-in address
         *  keep it.) */
        fun savedUrl(ctx: Context): String {
            val prefs = ctx.getSharedPreferences("ice", Context.MODE_PRIVATE)
            if (!prefs.contains("seerrUrl")) {
                val used = java.io.File(ctx.cacheDir, "seerr_extras.json").exists()
                prefs.edit().putString("seerrUrl", if (used) DEFAULT_URL else "").apply()
            }
            val saved = prefs.getString("seerrUrl", "") ?: ""
            return if (saved.trimEnd('/') == "http://10.66.66.1:5055") DEFAULT_URL else saved   // first default had the wrong port
        }

        fun saveUrl(ctx: Context, url: String) =
            ctx.getSharedPreferences("ice", Context.MODE_PRIVATE).edit().putString("seerrUrl", url.trim()).apply()

        private var cached: Seerr? = null

        /** Shared connection for this address + Plex account (keeps the sign-in), or null if not set up. */
        fun get(ctx: Context, plexToken: String?): Seerr? {
            val url = savedUrl(ctx).trim()
            if (url.isEmpty() || plexToken.isNullOrEmpty()) return null
            val c = cached
            if (c != null && c.base == norm(url) && c.plexToken == plexToken) return c
            return Seerr(url, plexToken).also { cached = it }
        }

        private fun norm(u: String): String {
            var b = u.trim().trimEnd('/')
            if (!b.contains("://")) b = "http://$b"
            return b
        }

        private fun JSONObject.s(k: String): String = if (has(k) && !isNull(k)) optString(k) else ""
        private fun JSONObject.objs(k: String): List<JSONObject> =
            optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        private fun img(prefix: String, path: String) = path.takeIf { it.isNotEmpty() }?.let { prefix + it }

        /** One movie or show from a list. */
        fun parse(r: JSONObject, fallback: String? = null): SeerrResult? {
            var kind = r.s("mediaType").ifEmpty { fallback ?: "" }
            if (kind.isEmpty()) kind = if (r.has("firstAirDate") || (r.has("name") && !r.has("title"))) "tv" else "movie"
            if (kind != "movie" && kind != "tv") return null
            val id = r.optInt("id", 0).takeIf { it > 0 } ?: return null
            val date = r.s(if (kind == "movie") "releaseDate" else "firstAirDate")
            val mi = r.optJSONObject("mediaInfo") ?: JSONObject()
            val title = if (kind == "movie") r.s("title").ifEmpty { r.s("name") } else r.s("name").ifEmpty { r.s("title") }
            return SeerrResult(id, kind, title, date.take(4), r.s("overview"), img(IMG, r.s("posterPath")),
                mi.optInt("status", UNKNOWN), backdrop = img(BACKDROP, r.s("backdropPath")),
                ratingKey = mi.s("ratingKey").ifEmpty { null }, votes = r.optInt("voteCount", 0),
                vote = r.optDouble("voteAverage").takeIf { !it.isNaN() }, date = date,
                role = r.s("character").ifEmpty { r.s("job") }, department = r.s("department"),
                episodes = if (r.has("episodeCount") && !r.isNull("episodeCount")) r.optInt("episodeCount") else null)
        }

        fun cert(kind: String, d: JSONObject): String {
            if (kind == "movie") {
                for (r in d.optJSONObject("releases")?.objs("results") ?: emptyList()) {
                    if (r.s("iso_3166_1") != REGION) continue
                    val dates = r.objs("release_dates").sortedBy { if (it.optInt("type") == 3) 0 else 1 }
                    dates.map { it.s("certification") }.firstOrNull { it.isNotEmpty() }?.let { return it }
                }
                return ""
            }
            return d.optJSONObject("contentRatings")?.objs("results")
                ?.firstOrNull { it.s("iso_3166_1") == REGION && it.s("rating").isNotEmpty() }?.s("rating") ?: ""
        }

        fun imdbId(d: JSONObject): String? =
            (d.optJSONObject("externalIds")?.s("imdbId") ?: "").ifEmpty { d.s("imdbId") }.ifEmpty { null }
    }

    val base = norm(url)
    @Volatile private var cookie: String? = null

    private fun unreachable() = SeerrException("Can't reach Seerr at $base.")

    /** One request; a connection that's still coming up (WireGuard) gets a few seconds before giving up. */
    private fun send(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        for (wait in listOf(1500L, 3000L, -1L)) {
            try {
                return sendOnce(method, path, body)
            } catch (e: SeerrException) {
                if (wait < 0) throw e
                Thread.sleep(wait)
            }
        }
        throw unreachable()
    }

    private fun sendOnce(method: String, path: String, body: JSONObject?): Pair<Int, String> {
        val c = try { URL("$base/api/v1$path").openConnection() as HttpURLConnection } catch (e: Exception) { throw unreachable() }
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            cookie?.let { c.setRequestProperty("Cookie", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            c.headerFields.entries.filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }.firstOrNull { it.trimStart().startsWith("connect.sid=") }
                ?.let { cookie = it.trim().substringBefore(';') }
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } catch (e: Exception) {
            throw unreachable()
        } finally {
            c.disconnect()
        }
    }

    @Synchronized
    private fun login() {
        cookie = null
        val (code, _) = send("POST", "/auth/plex", JSONObject().put("authToken", plexToken))
        if (code == 403) throw SeerrException("Seerr didn't let this Plex account in.")
        if (code >= 400) throw SeerrException("Seerr sign-in failed ($code).")
        if (cookie == null) throw SeerrException("Seerr signed in but sent no session cookie.")
    }

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        if (cookie == null) synchronized(this) { if (cookie == null) login() }
        repeat(2) { attempt ->
            val (code, text) = send(method, path, body)
            if ((code == 401 || code == 403) && attempt == 0) { login(); return@repeat }
            if (code >= 400) {
                val msg = try { JSONObject(text).optString("message").ifEmpty { null } } catch (_: Exception) { null }
                throw SeerrException(msg ?: "Seerr said $code.")
            }
            return text
        }
        throw SeerrException("Seerr sign-in keeps failing.")
    }

    private fun obj(path: String): JSONObject = JSONObject(call("GET", path).ifEmpty { "{}" })
    private fun arr(path: String): JSONArray = JSONArray(call("GET", path).ifEmpty { "[]" })

    private fun list(path: String, page: Int, params: Map<String, String> = emptyMap()): SeerrPage<SeerrResult> {
        var q = "page=$page"
        for ((k, v) in params.toSortedMap()) if (v.isNotEmpty()) q += "&$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val d = obj(path + (if (path.contains("?")) "&" else "?") + q)
        return SeerrPage(d.objs("results").mapNotNull { parse(it) }, d.optInt("totalPages", 1),
            if (d.has("totalResults")) d.optInt("totalResults") else null)
    }

    suspend fun test(): String = withContext(Dispatchers.IO) {
        cookie = null
        login()
        val me = JSONObject(call("GET", "/auth/me"))
        me.optString("displayName").ifEmpty { me.optString("plexUsername").ifEmpty { "OK" } }
    }

    // -- search -----------------------------------------------------------------

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun search(query: String): List<SeerrResult> = withContext(Dispatchers.IO) {
        obj("/search?query=${q(query)}&page=1").objs("results")
            .filter { it.s("mediaType") == "movie" || it.s("mediaType") == "tv" }.mapNotNull { parse(it) }
    }

    suspend fun searchPeople(query: String): List<SeerrPerson> = withContext(Dispatchers.IO) {
        val dept = mapOf("Acting" to "Actor", "Directing" to "Director", "Writing" to "Writer", "Production" to "Producer")
        obj("/search?query=${q(query)}&page=1").objs("results").filter { it.s("mediaType") == "person" }.map {
            val d = it.s("knownForDepartment")
            SeerrPerson(it.optInt("id"), it.s("name"), dept[d] ?: d, img(FACE, it.s("profilePath")))
        }
    }

    suspend fun findPerson(name: String): Int? {
        val people = searchPeople(name)
        return (people.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: people.firstOrNull())?.id
    }

    // -- discover ---------------------------------------------------------------

    suspend fun trending(page: Int = 1) = withContext(Dispatchers.IO) { list("/discover/trending", page) }
    suspend fun discover(kind: String, page: Int = 1, params: Map<String, String> = emptyMap()) =
        withContext(Dispatchers.IO) { list(if (kind == "movie") "/discover/movies" else "/discover/tv", page, params) }
    suspend fun upcoming(kind: String, page: Int = 1) =
        withContext(Dispatchers.IO) { list(if (kind == "movie") "/discover/movies/upcoming" else "/discover/tv/upcoming", page) }

    suspend fun services(): List<SeerrService> = withContext(Dispatchers.IO) {
        val a = arr("/watchproviders/movies?watchRegion=$REGION")
        val rows = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.sortedBy { it.optInt("displayPriority", 999) }
        SERVICES.mapNotNull { (label, names) ->
            rows.firstOrNull { it.s("name").lowercase() in names }?.let { SeerrService(it.optInt("id"), label, img(LOGO, it.s("logoPath"))) }
        }
    }

    suspend fun genres(kind: String): List<SeerrLink> = withContext(Dispatchers.IO) {
        val a = arr("/genres/${if (kind == "movie") "movie" else "tv"}")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { SeerrLink(it.optInt("id"), it.s("name")) }
    }

    // -- one title --------------------------------------------------------------

    /** IMDb id and age rating for a poster (lists don't include them). */
    suspend fun extra(kind: String, id: Int): Pair<String?, String> = withContext(Dispatchers.IO) {
        val d = obj("/$kind/$id")
        imdbId(d) to cert(kind, d)
    }

    suspend fun title(kind: String, id: Int): SeerrTitle = withContext(Dispatchers.IO) {
        val d = obj("/$kind/$id").put("mediaType", kind)
        val r = parse(d) ?: SeerrResult(id, kind, "", "", "", null, UNKNOWN)
        val mi = d.optJSONObject("mediaInfo") ?: JSONObject()
        val credits = d.optJSONObject("credits") ?: JSONObject()
        val crew = credits.objs("crew")
        fun people(rows: List<JSONObject>, n: Int = 99): List<SeerrPerson> {
            val seen = HashSet<Int>(); val out = ArrayList<SeerrPerson>()
            for (p in rows) {
                val pid = p.optInt("id")
                if (!seen.add(pid)) continue
                out += SeerrPerson(pid, p.s("name"), p.s("character").ifEmpty { p.s("job") }, img(FACE, p.s("profilePath")))
                if (out.size >= n) break
            }
            return out
        }
        fun link(o: JSONObject?) = o?.let { SeerrLink(it.optInt("id"), it.s("name"), img(IMG, it.s("posterPath"))) }
        val trailer = d.objs("relatedVideos").firstOrNull { it.s("type") == "Trailer" }?.s("url")?.ifEmpty { null }
        val providers = d.objs("watchProviders").firstOrNull { it.s("iso_3166_1") == REGION }?.objs("flatrate")
            ?.map { SeerrLink(it.optInt("id"), it.s("name")) } ?: emptyList()
        val seasons = ArrayList<SeerrSeason>()
        if (kind == "tv") {
            val have = HashMap<Int, Int>()
            mi.objs("seasons").forEach { have[it.optInt("seasonNumber")] = it.optInt("status", 1) }
            mi.objs("requests").forEach { rq -> rq.objs("seasons").forEach { have.putIfAbsent(it.optInt("seasonNumber"), PENDING) } }
            for (s in d.objs("seasons")) {
                val n = s.optInt("seasonNumber", 0)
                if (n <= 0) continue   // skip Specials
                seasons += SeerrSeason(n, s.s("name").ifEmpty { "Season $n" }, s.optInt("episodeCount"), have[n] ?: 1, s.s("airDate"))
            }
        }
        val runtime = d.optInt("runtime", 0).takeIf { it > 0 } ?: d.optJSONArray("episodeRunTime")?.optInt(0)?.takeIf { it > 0 }
        SeerrTitle(r, imdbId(d), cert(kind, d), d.s("tagline"), runtime, d.objs("genres").map { it.s("name") },
            people(credits.objs("cast"), 20), people(crew.filter { it.s("job") == "Director" }),
            people(crew.filter { it.s("department") == "Writing" }, 4), people(d.objs("createdBy")),
            link(d.objs("productionCompanies").firstOrNull()), link(d.objs("networks").firstOrNull()), providers,
            link(d.optJSONObject("collection")), trailer, seasons,
            if (d.has("numberOfSeasons")) d.optInt("numberOfSeasons") else null)
    }

    /** Rotten Tomatoes (and IMDb for movies) as Seerr reports them. */
    suspend fun scores(kind: String, id: Int): Pair<Double?, Int?> = withContext(Dispatchers.IO) {
        try {
            if (kind == "movie") {
                val d = obj("/movie/$id/ratingscombined")
                val imdb = d.optJSONObject("imdb")?.optDouble("criticsScore")?.takeIf { !it.isNaN() && it > 0 }
                val rt = d.optJSONObject("rt")?.let { if (it.has("criticsScore")) it.optInt("criticsScore") else null }
                imdb to rt
            } else {
                val d = obj("/tv/$id/ratings")
                null to (if (d.has("criticsScore")) d.optInt("criticsScore") else null)
            }
        } catch (_: Exception) { null to null }
    }

    suspend fun recommendations(kind: String, id: Int): List<SeerrResult> = withContext(Dispatchers.IO) {
        val r = try { list("/$kind/$id/recommendations", 1).items } catch (_: Exception) { emptyList() }
        r.ifEmpty { try { list("/$kind/$id/similar", 1).items } catch (_: Exception) { emptyList() } }
    }

    suspend fun collection(id: Int): SeerrCollection = withContext(Dispatchers.IO) {
        val d = obj("/collection/$id")
        SeerrCollection(d.s("name").ifEmpty { "Collection" }, d.s("overview"), img(BACKDROP, d.s("backdropPath")),
            d.objs("parts").mapNotNull { parse(it, "movie") }.sortedBy { it.date.ifEmpty { "9999" } })
    }

    // -- people -----------------------------------------------------------------

    suspend fun person(id: Int): SeerrPersonDetails = withContext(Dispatchers.IO) {
        val d = obj("/person/$id")
        val c = obj("/person/$id/combined_credits")
        val cast = c.objs("cast").mapNotNull { parse(it) }
        val crew = c.objs("crew").mapNotNull { parse(it) }
        val dept = d.s("knownForDepartment").ifEmpty { "Acting" }
        val pool = if (dept == "Acting") cast else crew
        val seen = HashSet<String>(); val known = ArrayList<SeerrResult>()
        for (x in pool.sortedByDescending { it.votes }) {
            val guest = dept == "Acting" && x.kind == "tv" && (x.episodes ?: 99) < 3
            if (guest || !seen.add(x.uid)) continue
            known += x
            if (known.size >= 30) break
        }
        val groups = LinkedHashMap<String, MutableList<SeerrResult>>()
        if (cast.isNotEmpty()) groups["Acting"] = cast.toMutableList()
        val depName = mapOf("Directing" to "Directing", "Writing" to "Writing", "Production" to "Producing")
        for (x in crew) groups.getOrPut(depName[x.department] ?: "Crew") { ArrayList() } += x
        val merged = groups.mapValues { (_, lst) ->
            val byId = LinkedHashMap<String, SeerrResult>()
            for (x in lst) {   // one row per title (several jobs on one film become one line)
                val m = byId[x.uid]
                byId[x.uid] = if (m == null) x
                else if (x.role.isNotEmpty() && !m.role.contains(x.role)) m.copy(role = if (m.role.isEmpty()) x.role else "${m.role}, ${x.role}")
                else m
            }
            byId.values.sortedByDescending { it.date.ifEmpty { "0000" } }
        }
        val order = if (dept == "Acting") listOf("Acting", "Directing", "Writing", "Producing", "Crew")
                    else listOf("Directing", "Writing", "Producing", "Acting", "Crew")
        SeerrPersonDetails(id, d.s("name"), d.s("biography"), d.s("birthday"), d.s("deathday"), d.s("placeOfBirth"), dept,
            img("https://image.tmdb.org/t/p/h632", d.s("profilePath")), known,
            order.mapNotNull { k -> merged[k]?.takeIf { it.isNotEmpty() }?.let { CreditGroup(k, it) } })
    }

    // -- requests ---------------------------------------------------------------

    suspend fun request(r: SeerrResult, seasons: List<Int>?) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("mediaType", r.kind).put("mediaId", r.id)
        if (r.kind == "tv") body.put("seasons", seasons?.let { JSONArray(it) } ?: "all")
        call("POST", "/request", body)
        Unit
    }

    suspend fun myRequests(take: Int = 100): List<SeerrRequest> = withContext(Dispatchers.IO) {
        val reqs = obj("/request?take=$take&skip=0&filter=all&sort=added&sortDirection=desc").objs("results").mapNotNull { rq ->
            val media = rq.optJSONObject("media") ?: JSONObject()
            val kind = media.s("mediaType").ifEmpty { rq.s("type") }
            val tid = media.optInt("tmdbId", 0)
            if ((kind != "movie" && kind != "tv") || tid == 0) return@mapNotNull null
            SeerrRequest(rq.optInt("id"), rq.optInt("status"), kind, tid, media.optInt("status", 1),
                media.s("ratingKey").ifEmpty { null }, rq.s("createdAt").take(10),
                rq.objs("seasons").map { it.optInt("seasonNumber") }.sorted())
        }
        coroutineScope { reqs.map { async { fill(it) } }.awaitAll() }
    }

    private fun fill(r: SeerrRequest): SeerrRequest = try {
        val d = obj("/${r.kind}/${r.tmdbId}").put("mediaType", r.kind)
        val x = parse(d)
        val out = r.copy(title = x?.title ?: "", year = x?.year ?: "", date = x?.date ?: "", poster = x?.poster,
            cert = cert(r.kind, d),
            seasons = d.objs("seasons").filter { it.optInt("seasonNumber") > 0 }.map {
                SeerrSeason(it.optInt("seasonNumber"), it.s("name"), it.optInt("episodeCount"), 1, it.s("airDate"))
            })
        SeerrExtras.put(out.asResult(), imdbId(d), out.cert)
        out
    } catch (_: Exception) {
        r.copy(title = if (r.kind == "movie") "Movie ${r.tmdbId}" else "Show ${r.tmdbId}")
    }

    data class Schedule(val code: String, val name: String, val date: String, val status: String, val last: String)

    /** A show's release schedule: next episode ("S4 E7", name, date) and status (e.g. "Ended"). */
    suspend fun schedule(tmdbId: Int): Schedule = withContext(Dispatchers.IO) {
        val d = obj("/tv/$tmdbId")
        val n = d.optJSONObject("nextEpisodeToAir") ?: JSONObject()
        val code = if (n.has("seasonNumber")) "S${n.optInt("seasonNumber")} E${n.optInt("episodeNumber")}" else ""
        Schedule(code, n.s("name"), n.s("airDate"), d.s("status"), d.optJSONObject("lastEpisodeToAir")?.s("airDate") ?: "")
    }

    suspend fun seasonEpisodes(tmdbId: Int, season: Int): List<SeerrEpisode> = withContext(Dispatchers.IO) {
        obj("/tv/$tmdbId/season/$season").objs("episodes").map { SeerrEpisode(it.optInt("episodeNumber"), it.s("name"), it.s("airDate")) }
    }
}
