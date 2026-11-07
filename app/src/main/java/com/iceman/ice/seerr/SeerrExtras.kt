package com.iceman.ice.seerr

import com.iceman.ice.data.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * IMDb id + age rating for list entries (lists don't include them): fetched in the background a few at a time and
 * kept on the phone, so each title is only looked up once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object SeerrExtras {
    data class Extra(val imdb: String?, val cert: String, val t: Long)

    private val _data = MutableStateFlow<Map<String, Extra>>(emptyMap())
    val data: StateFlow<Map<String, Extra>> = _data
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    @Volatile private var loaded = false
    @Volatile private var saveQueued = false

    private val file: File? get() = try { File(AppState.appContext.cacheDir, "seerr_extras.json") } catch (_: Exception) { null }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(file?.takeIf { it.exists() }?.readText() ?: return)
            val m = HashMap<String, Extra>()
            for (k in o.keys()) {
                val e = o.optJSONObject(k) ?: continue
                m[k] = Extra(e.optString("imdb").ifEmpty { null }, e.optString("cert"), e.optLong("t"))
            }
            _data.update { m + it }
        } catch (_: Exception) {}
    }

    fun get(r: SeerrResult): Extra? { load(); return _data.value[r.uid] }

    fun put(r: SeerrResult, imdb: String?, cert: String) {
        load()
        _data.update { it + (r.uid to Extra(imdb, cert, System.currentTimeMillis())) }
        queueSave()
    }

    fun want(sr: Seerr?, r: SeerrResult) {
        sr ?: return
        load()
        val e = _data.value[r.uid]
        if (e != null && (e.cert.isNotEmpty() || System.currentTimeMillis() - e.t < 3 * 86400_000L)) return
        if (!pending.add(r.uid)) return
        scope.launch {
            try {
                val (imdb, cert) = sr.extra(r.kind, r.id)
                put(r, imdb, cert)
            } catch (_: Exception) {
            } finally { pending.remove(r.uid) }
        }
    }

    private fun queueSave() {
        if (saveQueued) return
        saveQueued = true
        scope.launch {
            delay(4000)
            saveQueued = false
            val o = JSONObject()
            for ((k, e) in _data.value) o.put(k, JSONObject().put("imdb", e.imdb ?: "").put("cert", e.cert).put("t", e.t))
            try { file?.writeText(o.toString()) } catch (_: Exception) {}
        }
    }
}
