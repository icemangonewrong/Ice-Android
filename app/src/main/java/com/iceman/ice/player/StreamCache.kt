package com.iceman.ice.player

import android.os.StatFs
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/**
 * A tiny web server inside the app that sits between the video player and Plex.
 *
 * It downloads the file ahead of where you're watching into a cache on the phone's storage
 * and serves the player from that cache. When the signal drops the player keeps reading
 * what's cached, and the download retries every few seconds and carries on where it stopped.
 */
class StreamCache(
    private val remote: String,
    private val size: Long,
    durationSec: Double,
    aheadSec: Double,
    container: String?,
    cacheRoot: File,
    preloadSec: Double? = null,
    preloadAt: Double = 0.0,
) {
    data class Snapshot(val bufferedBytes: Long, val offline: Boolean, val bytesPerSecond: Double)

    val bytesPerSecond: Double = maxOf(size / maxOf(durationSec, 1.0), 50_000.0)
    private val chunkSize = 4L * 1024 * 1024
    private val behindLimit = 300L * 1024 * 1024
    @Volatile private var aheadLimit: Long
    private val fullAheadLimit: Long
    /** Preload mode: only the first part (and the last few MB) are fetched, and only while allowed,
     *  until promote() turns this into the normal cache for the playing video. */
    @Volatile private var preloading = preloadSec != null
    @Volatile private var hold = preloadSec != null
    private val tailBytes = 4L * 1024 * 1024
    /** Preload: byte ranges to fetch in order (start of file or a resume window, then the end of the file). */
    private var plan: List<Pair<Long, Long>> = emptyList()
    private val dir = File(cacheRoot, "stream-" + UUID.randomUUID())
    private val mime = when ((container ?: "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    private val lock = Object()
    private val chunks = HashMap<Long, Long>()   // chunk index -> bytes stored (contiguous from chunk start)
    @Volatile private var readPos = 0L            // where the player is reading
    @Volatile private var stopped = false
    @Volatile private var offline = false
    private var server: ServerSocket? = null
    private var downloadConn: HttpURLConnection? = null
    private var downloadStart = 0L
    @Volatile private var downloadPos = 0L
    private var activeClient: Any? = null

    init {
        var wanted = (bytesPerSecond * maxOf(aheadSec, 60.0)).toLong()
        val free = try { StatFs(cacheRoot.path).availableBytes } catch (_: Exception) { 0L }
        if (free > 0) wanted = minOf(wanted, maxOf(free - 2_000_000_000L, 200_000_000L))
        fullAheadLimit = minOf(wanted, 12_000_000_000L)
        aheadLimit = fullAheadLimit
        if (preloadSec != null) {
            val want = minOf(maxOf((bytesPerSecond * preloadSec * 1.1).toLong(), 4_000_000L), 80_000_000L)
            val p = mutableListOf<Pair<Long, Long>>()
            if (preloadAt > 1 && durationSec > 0) {
                // Resuming partway: the file's start (headers), then a window around the resume point
                // (estimated from the average bitrate, so start a little before it).
                p.add(0L to 2_000_000L)
                val before = (bytesPerSecond * 10).toLong()
                val est = (size * preloadAt / durationSec).toLong()
                p.add(maxOf(est - before, 0L) to want + before)
            } else p.add(0L to want)
            p.add(size - tailBytes to tailBytes)
            plan = p.filter { it.first in 0 until size }.map { it.first to minOf(it.second, size - it.first) }
        }
    }


    /** Preload only: let it download (true) or hold it so the playing video gets the bandwidth (false). */
    fun setAllowed(on: Boolean) {
        if (!preloading || hold == !on) return
        synchronized(lock) { hold = !on; (lock as Object).notifyAll() }
    }

    /** Last minutes of the video: keep downloading all the way to the end, whatever the buffer setting. */
    fun loadToEnd() {
        if (preloading || aheadLimit >= size) return
        synchronized(lock) { aheadLimit = size; (lock as Object).notifyAll() }
    }

    /** The preloaded video is now playing: become its normal cache. */
    fun promote() {
        synchronized(lock) {
            preloading = false
            hold = false
            aheadLimit = fullAheadLimit
            (lock as Object).notifyAll()
        }
    }

    /** Starts the local server; returns the address to give the player. */
    fun start(): String {
        dir.mkdirs()
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread(name = "ice-cache-accept", isDaemon = true) {
            while (!stopped) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                thread(name = "ice-cache-client", isDaemon = true) { serve(s) }
            }
        }
        thread(name = "ice-cache-download", isDaemon = true) { downloadLoop() }
        return "http://127.0.0.1:${ss.localPort}/media"
    }

    fun stop() {
        stopped = true
        try { server?.close() } catch (_: Exception) {}
        synchronized(lock) {
            try { downloadConn?.disconnect() } catch (_: Exception) {}
            (lock as Object).notifyAll()
        }
        thread(isDaemon = true) { dir.deleteRecursively() }
    }

    fun snapshot(): Snapshot = synchronized(lock) { Snapshot(available(readPos), offline, bytesPerSecond) }

    // -- cache bookkeeping (call with lock held) -------------------------------

    private fun chunkFile(i: Long) = File(dir, "$i.bin")
    private fun chunkLen(i: Long) = minOf(chunkSize, size - i * chunkSize)

    private fun available(pos: Long): Long {
        var p = pos
        var total = 0L
        while (p < size) {
            val ci = p / chunkSize
            val have = chunks[ci] ?: 0
            val within = p - ci * chunkSize
            if (have <= within) break
            val n = have - within
            total += n
            p += n
            if (have < chunkLen(ci)) break
        }
        return total
    }

    private fun firstMissing(pos: Long): Long {
        var ci = maxOf(pos, 0) / chunkSize
        while (ci * chunkSize < size && (chunks[ci] ?: 0) >= chunkLen(ci)) ci++
        return minOf(ci * chunkSize + (chunks[ci] ?: 0), size)
    }

    private fun store(buf: ByteArray, len: Int, offset: Long) {
        var off = offset
        var idx = 0
        while (idx < len) {
            val ci = off / chunkSize
            val within = off - ci * chunkSize
            val n = minOf((len - idx).toLong(), chunkSize - within).toInt()
            val have = chunks[ci] ?: 0
            if (within <= have && within + n > have) {
                val skip = (have - within).toInt()
                try {
                    RandomAccessFile(chunkFile(ci), "rw").use { f ->
                        f.seek(have)
                        f.write(buf, idx + skip, n - skip)
                    }
                    chunks[ci] = within + n
                } catch (_: IOException) {}
            }
            off += n
            idx += n
        }
    }

    private fun trim() {
        val budget = aheadLimit + behindLimit
        var total = chunks.values.sum()
        if (total <= budget) return
        val rp = readPos
        val protectLow = rp - 64L * 1024 * 1024
        val protectHigh = rp + aheadLimit
        fun distance(ci: Long): Long { val s = ci * chunkSize; return if (s < rp) (rp - s) * 2 else s - rp }
        for (ci in chunks.keys.sortedByDescending { distance(it) }) {
            if (total <= budget) break
            val s = ci * chunkSize
            if (s + chunkSize > protectLow && s < protectHigh) continue
            total -= chunks[ci] ?: 0
            chunks.remove(ci)
            chunkFile(ci).delete()
        }
    }

    // -- downloading from Plex -------------------------------------------------

    private fun downloadLoop() {
        val buf = ByteArray(64 * 1024)
        while (!stopped) {
            var start = 0L
            var stopAt = size
            var pre = false
            synchronized(lock) {
                while (!stopped) {
                    if (preloading) {
                        if (hold) { (lock as Object).wait(1000); continue }
                        val step = plan.firstOrNull { (a, n) -> available(a) < n }
                        if (step != null) { start = firstMissing(step.first); stopAt = step.first + step.second; pre = true; break }
                        (lock as Object).wait(1000)
                        continue
                    }
                    // Wait while we're far enough ahead.
                    if (firstMissing(readPos) - readPos > aheadLimit * 9 / 10) { (lock as Object).wait(1000); continue }
                    start = firstMissing(readPos)
                    break
                }
            }
            if (stopped) break
            if (start >= size) { synchronized(lock) { (lock as Object).wait(1000) }; continue }

            var conn: HttpURLConnection? = null
            try {
                conn = URL(remote).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Range", "bytes=$start-")
                synchronized(lock) { downloadConn = conn; downloadStart = start; downloadPos = start }
                val code = conn.responseCode
                if (!(code == 206 || (code == 200 && start == 0L))) throw IOException("HTTP $code")
                val input: InputStream = conn.inputStream
                while (!stopped) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(lock) {
                        store(buf, n, downloadPos)
                        downloadPos += n
                        offline = false
                        (lock as Object).notifyAll()
                    }
                    if (pre) {
                        // Preload: stop at the end of the part we want, or when told to hold.
                        if (downloadPos >= stopAt || hold || !preloading) break
                        continue
                    }
                    // Player jumped somewhere this download isn't heading, or we're far enough ahead: restart/hold.
                    val rp = readPos
                    if (rp < downloadStart || rp > downloadPos + chunkSize * 2) break
                    if (downloadPos - rp > aheadLimit) break
                }
            } catch (_: Exception) {
                if (!stopped) {
                    offline = true
                    sleepQuietly(3000)
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                synchronized(lock) { if (downloadConn === conn) downloadConn = null }
            }
        }
    }

    // -- serving the player ----------------------------------------------------

    private fun serve(s: Socket) {
        val me = Any()
        try {
            s.tcpNoDelay = true
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val header = readHeader(input) ?: return
            val lines = header.split("\r\n")
            val method = lines.firstOrNull()?.substringBefore(' ') ?: "GET"
            var start = 0L
            var end = size - 1
            var partial = false
            for (l in lines) {
                if (!l.lowercase().startsWith("range:")) continue
                val spec = l.substringAfter('=').trim()
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-', "").trim()
                a.toLongOrNull()?.let { start = it; partial = true }
                b.toLongOrNull()?.let { end = minOf(it, size - 1) }
            }
            if (start >= size || start > end) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
            if (partial) sb.append("Content-Range: bytes $start-$end/$size\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
            if (method == "HEAD") return

            synchronized(lock) { activeClient = me; readPos = start; (lock as Object).notifyAll() }
            sendRange(out, start, end + 1, me)
        } catch (_: SocketException) {
        } catch (_: IOException) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun sendRange(out: OutputStream, from: Long, until: Long, me: Any) {
        var pos = from
        val buf = ByteArray(512 * 1024)
        while (pos < until && !stopped) {
            var n: Int
            synchronized(lock) {
                var avail = available(pos)
                while (avail <= 0 && !stopped) {
                    // Not downloaded yet: the player waits here (shows as buffering) instead of failing.
                    if (activeClient === me) readPos = pos
                    (lock as Object).notifyAll()
                    (lock as Object).wait(500)
                    avail = available(pos)
                }
                if (stopped) return
                n = minOf(avail, buf.size.toLong(), until - pos).toInt()
                val ci = pos / chunkSize
                val within = pos - ci * chunkSize
                n = minOf(n.toLong(), (chunks[ci] ?: 0) - within).toInt()
                try {
                    RandomAccessFile(chunkFile(ci), "r").use { f -> f.seek(within); f.readFully(buf, 0, n) }
                } catch (_: IOException) { return }
            }
            out.write(buf, 0, n)
            pos += n
            synchronized(lock) {
                if (activeClient === me) {
                    readPos = pos
                    trim()
                    (lock as Object).notifyAll()
                }
            }
        }
        out.flush()
    }

    private fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (sb.length < 64 * 1024) {
            val c = input.read()
            if (c < 0) return null
            sb.append(c.toChar())
            last4 = (last4 shl 8) or c
            if (last4 == 0x0D0A0D0A) return sb.toString().trimEnd()
        }
        return null
    }

    private fun sleepQuietly(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    companion object {
        fun cleanUp(cacheRoot: File) {
            cacheRoot.listFiles()?.filter { it.name.startsWith("stream-") }?.forEach { it.deleteRecursively() }
        }
    }
}
