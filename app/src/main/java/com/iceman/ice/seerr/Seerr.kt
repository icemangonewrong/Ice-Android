package com.iceman.ice.seerr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A Seerr search result (something that may not be in the library). */
data class SeerrResult(val id: Int, val kind: String, val title: String, val year: String, val overview: String,
                       val poster: String?, val status: Int) {
    val kindText: String get() = if (kind == "movie") "Movie" else "TV show"
    val statusText: String? get() = Seerr.STATUS_TEXT[status]
}

data class SeerrSeason(val number: Int, val name: String, val episodes: Int, val status: Int)

data class SeerrDetails(val status: Int, val overview: String, val genres: List<String>, val runtime: Int?,
                        val seasons: List<SeerrSeason>, val imdb: Double?)

class SeerrException(msg: String) : IOException(msg)

/**
 * Seerr: search everything (not just the library) and request it. Ice signs in with the Plex account it already
 * has, so requests show up in Seerr under that user as if made on Seerr's own website.
 */
class Seerr(url: String, private val plexToken: String) {
    companion object {
        const val PENDING = 2; const val PROCESSING = 3; const val PARTIAL = 4; const val AVAILABLE = 5
        val STATUS_TEXT = mapOf(2 to "Requested", 3 to "Requested", 4 to "Partly available", 5 to "Available")
        const val DEFAULT_URL = "http://10.66.66.1:5056"

        fun savedUrl(ctx: Context): String {
            val saved = ctx.getSharedPreferences("ice", Context.MODE_PRIVATE).getString("seerrUrl", null) ?: DEFAULT_URL
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
    }

    val base = norm(url)
    @Volatile private var cookie: String? = null

    private fun unreachable() = SeerrException("Can't reach Seerr at $base (is WireGuard on?)")

    private fun send(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
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
        } catch (e: SeerrException) {
            throw e
        } catch (e: Exception) {
            throw unreachable()
        } finally {
            c.disconnect()
        }
    }

    private fun login() {
        cookie = null
        val (code, _) = send("POST", "/auth/plex", JSONObject().put("authToken", plexToken))
        if (code == 403) throw SeerrException("Seerr didn't let this Plex account in.")
        if (code >= 400) throw SeerrException("Seerr sign-in failed ($code).")
        if (cookie == null) throw SeerrException("Seerr signed in but sent no session cookie.")
    }

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        if (cookie == null) login()
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

    suspend fun test(): String = withContext(Dispatchers.IO) {
        cookie = null
        login()
        val me = JSONObject(call("GET", "/auth/me"))
        me.optString("displayName").ifEmpty { me.optString("plexUsername").ifEmpty { "OK" } }
    }

    suspend fun search(query: String): List<SeerrResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val arr = JSONObject(call("GET", "/search?query=$q&page=1")).optJSONArray("results") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = r.optString("mediaType")
            if (kind != "movie" && kind != "tv") return@mapNotNull null
            val date = r.optString(if (kind == "movie") "releaseDate" else "firstAirDate")
            SeerrResult(r.optInt("id"), kind, r.optString(if (kind == "movie") "title" else "name"), date.take(4),
                r.optString("overview"),
                r.optString("posterPath").takeIf { it.isNotEmpty() && it != "null" }?.let { "https://image.tmdb.org/t/p/w342$it" },
                r.optJSONObject("mediaInfo")?.optInt("status", 1) ?: 1)
        }
    }

    suspend fun details(r: SeerrResult): SeerrDetails = withContext(Dispatchers.IO) {
        val d = JSONObject(call("GET", "/${r.kind}/${r.id}"))
        val mi = d.optJSONObject("mediaInfo") ?: JSONObject()
        val genres = d.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name") } } ?: emptyList()
        val seasons = mutableListOf<SeerrSeason>()
        var imdb: Double? = null
        if (r.kind == "tv") {
            val have = HashMap<Int, Int>()
            mi.optJSONArray("seasons")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { have[it.optInt("seasonNumber")] = it.optInt("status", 1) } }
            mi.optJSONArray("requests")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONArray("seasons")?.let { s ->
                    for (j in 0 until s.length()) s.optJSONObject(j)?.let { have.putIfAbsent(it.optInt("seasonNumber"), PENDING) }
                }
            }
            d.optJSONArray("seasons")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.optJSONObject(i) ?: continue
                    val n = s.optInt("seasonNumber", 0)
                    if (n <= 0) continue   // skip Specials
                    seasons += SeerrSeason(n, s.optString("name").ifEmpty { "Season $n" }, s.optInt("episodeCount"), have[n] ?: 1)
                }
            }
        } else {
            imdb = try {
                JSONObject(call("GET", "/movie/${r.id}/ratingscombined")).optJSONObject("imdb")?.optDouble("criticsScore")
                    ?.takeIf { !it.isNaN() && it > 0 }
            } catch (_: Exception) { null }
        }
        SeerrDetails(mi.optInt("status", 1), d.optString("overview").ifEmpty { r.overview }, genres,
            d.optInt("runtime", 0).takeIf { it > 0 }, seasons, imdb)
    }

    suspend fun request(r: SeerrResult, seasons: List<Int>?) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("mediaType", r.kind).put("mediaId", r.id)
        if (r.kind == "tv") body.put("seasons", seasons?.let { JSONArray(it) } ?: "all")
        call("POST", "/request", body)
        Unit
    }
}
