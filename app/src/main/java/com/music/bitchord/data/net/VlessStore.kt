package com.music.bitchord.data.net

import android.content.Context
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.net.DesyncProxy
import com.music.bitchord.net.Vless
import com.music.bitchord.net.VlessEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The listener's own VLESS key, kept encrypted and applied to the local proxy.
 *
 * A key is a credential: whoever holds it can spend the server's quota. It
 * therefore lives in the same encrypted store as the account tokens, never in a
 * backup, and is never logged. Only [VlessEndpoint.label] - a host and a port -
 * is ever shown on screen.
 */
object VlessStore {

    private const val FILE = "rxplays_vless"
    private const val PLAIN_FILE = "rxplays_vless_plain"
    private const val KEY_LINK = "link"
    private const val KEY_ENABLED = "enabled"

    private val _link = MutableStateFlow("")
    val link = _link.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val store = EncryptedPrefs.open(context, FILE, PLAIN_FILE)
        prefs = store
        _link.value = store.getString(KEY_LINK, "").orEmpty()
        _enabled.value = store.getBoolean(KEY_ENABLED, false)
        apply()
    }

    /** Validates and stores a key; the returned failure is what the dialog shows. */
    fun save(raw: String): Result<VlessEndpoint> {
        val parsed = Vless.parse(raw)
        parsed.onSuccess { endpoint ->
            _link.value = raw.trim()
            prefs?.edit()?.putString(KEY_LINK, _link.value)?.apply()
            _enabled.value = true
            prefs?.edit()?.putBoolean(KEY_ENABLED, true)?.apply()
            DesyncProxy.configureVless(endpoint)
            DesyncProxy.setEnabled(true)
        }
        return parsed
    }

    fun setEnabled(value: Boolean) {
        val endpoint = Vless.parse(_link.value).getOrNull()
        if (value && endpoint == null) {
            _enabled.value = false
            return
        }
        _enabled.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        DesyncProxy.configureVless(if (value) endpoint else null)
        DesyncProxy.setEnabled(value)
    }

    fun clear() {
        _link.value = ""
        _enabled.value = false
        prefs?.edit()?.remove(KEY_LINK)?.putBoolean(KEY_ENABLED, false)?.apply()
        DesyncProxy.configureVless(null)
        DesyncProxy.setEnabled(false)
    }

    /**
     * Opens one tunnel and closes it, so the listener learns whether the key
     * works without leaving the app.
     *
     * A success here means: TCP to the server, the TLS handshake with the given
     * SNI, and the VLESS header were all accepted. It does not download a track,
     * which keeps the probe cheap and the answer quick.
     */
    suspend fun testConnection(): Result<Long> = withContext(Dispatchers.IO) {
        val parsed = com.music.bitchord.net.Vless.parse(_link.value).getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("Сначала добавьте ключ"))
        val started = System.currentTimeMillis()
        runCatching {
            com.music.bitchord.net.Vless.connect(parsed, "music.youtube.com", 443).use { }
            System.currentTimeMillis() - started
        }
    }

    /** Human-readable, key-free description of what is configured. */
    fun describe(): String {
        val endpoint = Vless.parse(_link.value).getOrNull() ?: return "Ключ не добавлен"
        return if (_enabled.value) "Включено: ${endpoint.label}" else "Сохранено: ${endpoint.label}"
    }

    private fun apply() {
        val endpoint = Vless.parse(_link.value).getOrNull()
        DesyncProxy.configureVless(if (_enabled.value) endpoint else null)
        DesyncProxy.setEnabled(_enabled.value && endpoint != null)
    }
}
