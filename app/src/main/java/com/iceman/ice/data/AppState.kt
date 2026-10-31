package com.iceman.ice.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

/** Settings, sign-in and the shared Plex client. Saved between launches. */
object AppState {
    val ACCENTS = listOf(
        "Orange" to 0xFFFF8C00, "Red" to 0xFFE5383B, "Pink" to 0xFFFF4F9A, "Purple" to 0xFF9B5CFF,
        "Blue" to 0xFF2F8CFF, "Teal" to 0xFF14B8A6, "Green" to 0xFF3CC45A, "Gold" to 0xFFF5C518,
    )
    val BUFFER_CHOICES = listOf(2, 5, 10, 20, 30)

    private lateinit var prefs: SharedPreferences
    lateinit var client: PlexClient
        private set

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _server = MutableStateFlow<Server?>(null)
    val server: StateFlow<Server?> = _server
    private val _accent = MutableStateFlow(0xFFFF8C00)
    val accent: StateFlow<Long> = _accent
    private val _excluded = MutableStateFlow<Set<String>>(emptySet())
    val excluded: StateFlow<Set<String>> = _excluded
    private val _bufferMinutes = MutableStateFlow(2)
    val bufferMinutes: StateFlow<Int> = _bufferMinutes
    private val _tapToPause = MutableStateFlow(false)
    val tapToPause: StateFlow<Boolean> = _tapToPause
    private val _audioDelays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val audioDelays: StateFlow<Map<String, Int>> = _audioDelays

    /** Bumped when something changed on the server (watched state, progress) so screens reload. */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion

    var sections: List<Section> = emptyList()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ice", Context.MODE_PRIVATE)
        val id = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("clientId", it).apply()
        }
        client = PlexClient(id)
        client.token = prefs.getString("token", null)
        client.server = Server.fromJson(prefs.getString("server", null))
        client.onServerChanged = { s -> prefs.edit().putString("server", s.toJson()).apply(); _server.value = s }
        _signedIn.value = client.token != null
        _server.value = client.server
        _accent.value = prefs.getLong("accent", 0xFFFF8C00)
        _excluded.value = prefs.getStringSet("excluded", emptySet())!!.toSet()
        _bufferMinutes.value = prefs.getInt("bufferMinutes", 2).let { if (it in BUFFER_CHOICES) it else 2 }
        _tapToPause.value = prefs.getBoolean("tapToPause", false)
        _audioDelays.value = try {
            val o = JSONObject(prefs.getString("audioDelays", "{}")!!)
            o.keys().asSequence().associateWith { o.getInt(it) }
        } catch (_: Exception) { emptyMap() }
    }

    fun setToken(token: String) {
        client.token = token
        prefs.edit().putString("token", token).apply()
        _signedIn.value = true
    }

    fun setServer(s: Server?) {
        client.server = s
        sections = emptyList()
        prefs.edit().putString("server", s?.toJson()).apply()
        _server.value = s
    }

    fun signOut() {
        client.token = null
        prefs.edit().remove("token").remove("server").apply()
        setServer(null)
        _signedIn.value = false
    }

    fun setAccent(c: Long) { _accent.value = c; prefs.edit().putLong("accent", c).apply() }

    fun setExcluded(key: String, hidden: Boolean) {
        val s = _excluded.value.toMutableSet()
        if (hidden) s.add(key) else s.remove(key)
        _excluded.value = s
        prefs.edit().putStringSet("excluded", s).apply()
        changed()
    }

    fun isExcluded(item: Item) = item.sectionId?.let { it in _excluded.value } ?: false
    val visibleSections get() = sections.filter { it.key !in _excluded.value }

    fun setBufferMinutes(m: Int) { _bufferMinutes.value = m; prefs.edit().putInt("bufferMinutes", m).apply() }
    fun setTapToPause(on: Boolean) { _tapToPause.value = on; prefs.edit().putBoolean("tapToPause", on).apply() }

    fun setAudioDelay(device: String, ms: Int) {
        val m = _audioDelays.value.toMutableMap()
        if (ms == 0) m.remove(device) else m[device] = ms
        _audioDelays.value = m
        prefs.edit().putString("audioDelays", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun changed() { _dataVersion.value += 1 }

    suspend fun loadSections(): List<Section> {
        if (sections.isEmpty()) sections = client.sections()
        return sections
    }
}
