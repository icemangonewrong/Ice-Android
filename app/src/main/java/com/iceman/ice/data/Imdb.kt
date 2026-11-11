package com.iceman.ice.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * IMDb scores for everything, from IMDb's own free daily ratings file
 * (https://datasets.imdbws.com/title.ratings.tsv.gz, free for personal use). Plex only has the IMDb score when a
 * library's ratings source is set to IMDb, never for seasons and often not in lists; this covers every movie, show
 * and episode by IMDb ID (tt1234567), which Plex and Seerr both give. Downloaded about once a day (~8 MB) and kept
 * as two compact sorted arrays, saved so later launches load instantly.
 */
object Imdb {
    private const val SOURCE = "https://datasets.imdbws.com/title.ratings.tsv.gz"
    private const val DAY = 24 * 3600 * 1000L

    /** Bumped whenever new ratings are loaded, so posters redraw with them. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile private var ids = IntArray(0)
    @Volatile private var vals = ByteArray(0)
    /** Number of IMDb votes: IMDb's own measure of how popular a title is. */
    @Volatile private var votes = IntArray(0)
    private var started = false

    fun rating(imdbId: String?): Double? {
        val s = imdbId ?: return null
        if (!s.startsWith("tt")) return null
        val n = s.substring(2).toIntOrNull() ?: return null
        val a = ids
        val v = vals
        val i = java.util.Arrays.binarySearch(a, n)
        return if (i >= 0 && i < v.size) v[i].toInt() / 10.0 else null
    }

    fun votes(imdbId: String?): Int? {
        val s = imdbId ?: return null
        if (!s.startsWith("tt")) return null
        val n = s.substring(2).toIntOrNull() ?: return null
        val a = ids
        val c = votes
        val i = java.util.Arrays.binarySearch(a, n)
        return if (i >= 0 && i < c.size && c.size == a.size) c[i] else null
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val dir = File(context.cacheDir, "imdb").apply { mkdirs() }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (loadSaved(dir)) _version.value++
            while (true) {
                val age = System.currentTimeMillis() - (File(dir, "stamp").takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L)
                val wait = if (age >= DAY || ids.isEmpty() || votes.size != ids.size) {
                    try { download(dir); _version.value++; DAY } catch (e: Exception) { android.util.Log.w("Ice", "IMDb ratings: $e"); 3600_000L }
                } else DAY - age
                delay(maxOf(60_000L, wait))
            }
        }
    }

    private fun loadSaved(dir: File): Boolean {
        val a = File(dir, "ids.bin"); val b = File(dir, "ratings.bin")
        if (!a.exists() || !b.exists()) return false
        val idBytes = a.readBytes(); val v = b.readBytes()
        if (v.isEmpty() || idBytes.size != v.size * 4) return false
        val ints = IntArray(v.size)
        ByteBuffer.wrap(idBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
        val c = File(dir, "votes.bin")
        var vts = IntArray(0)
        if (c.exists()) {
            val cb = c.readBytes()
            if (cb.size == idBytes.size) { vts = IntArray(v.size); ByteBuffer.wrap(cb).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(vts) }
        }
        ids = ints; vals = v; votes = vts
        return true
    }

    private fun download(dir: File) {
        val c = URL(SOURCE).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "Ice")
        var newIds = IntArray(1_800_000); var newVals = ByteArray(1_800_000); var newVotes = IntArray(1_800_000); var n = 0
        var sorted = true; var last = -1
        try {
            if (c.responseCode != 200) throw java.io.IOException("IMDb said ${c.responseCode}")
            java.io.InputStreamReader(GZIPInputStream(c.inputStream.buffered(1 shl 16)), Charsets.US_ASCII).buffered(1 shl 16).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("tt")) continue
                    val t1 = line.indexOf('\t'); if (t1 < 0) continue
                    val t2 = line.indexOf('\t', t1 + 1).let { if (it < 0) line.length else it }
                    val id = line.substring(2, t1).toIntOrNull() ?: continue
                    val r = line.substring(t1 + 1, t2).toDoubleOrNull() ?: continue
                    val cnt = if (t2 < line.length) line.substring(t2 + 1).trim().toIntOrNull() ?: 0 else 0
                    if (n == newIds.size) { newIds = newIds.copyOf(n * 2); newVals = newVals.copyOf(n * 2); newVotes = newVotes.copyOf(n * 2) }
                    if (id <= last) sorted = false
                    last = id
                    newIds[n] = id; newVals[n] = Math.round(r * 10).toInt().coerceIn(0, 100).toByte(); newVotes[n] = cnt; n++
                }
            }
        } finally { c.disconnect() }
        if (n == 0) throw java.io.IOException("empty ratings file")
        var outIds = newIds.copyOf(n); var outVals = newVals.copyOf(n); var outVotes = newVotes.copyOf(n)
        if (!sorted) {   // IMDb's file is sorted already; just in case
            val order = (0 until n).sortedBy { outIds[it] }
            outIds = IntArray(n) { newIds[order[it]] }; outVals = ByteArray(n) { newVals[order[it]] }; outVotes = IntArray(n) { newVotes[order[it]] }
        }
        val vb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        vb.asIntBuffer().put(outVotes)
        File(dir, "votes.bin.tmp").writeBytes(vb.array()); File(dir, "votes.bin.tmp").renameTo(File(dir, "votes.bin"))
        val buf = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asIntBuffer().put(outIds)
        File(dir, "ids.bin.tmp").writeBytes(buf.array()); File(dir, "ids.bin.tmp").renameTo(File(dir, "ids.bin"))
        File(dir, "ratings.bin.tmp").writeBytes(outVals); File(dir, "ratings.bin.tmp").renameTo(File(dir, "ratings.bin"))
        File(dir, "stamp").writeText(System.currentTimeMillis().toString())
        vals = outVals; votes = outVotes; ids = outIds
    }
}
