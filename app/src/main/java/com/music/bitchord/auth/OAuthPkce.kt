package com.music.bitchord.auth

import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

internal data class PkcePair(val verifier: String, val challenge: String)

internal fun newOAuthState(): String = randomUrlToken(32)

internal fun newPkcePair(): PkcePair {
    val verifier = randomUrlToken(64)
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
    return PkcePair(verifier, digest.base64Url())
}

private fun randomUrlToken(bytes: Int): String = ByteArray(bytes)
    .also(SecureRandom()::nextBytes)
    .base64Url()

private fun ByteArray.base64Url(): String =
    Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

/** One-shot relay for browser OAuth callbacks delivered to MainActivity. */
object OAuthRedirect {
    private val mutable = MutableStateFlow<Uri?>(null)
    val pending = mutable.asStateFlow()

    fun consume(intent: Intent?) {
        val uri = intent?.data ?: return
        val soundCloud = uri.scheme.equals("rxmusic", true) &&
            uri.host.equals("oauth", true) && uri.path == "/soundcloud"
        val vk = uri.scheme.equals(BuildConfig.VK_ID_REDIRECT_SCHEME, true) &&
            uri.host.equals("vk.ru", true)
        if (soundCloud || vk) mutable.value = uri
    }

    fun handled(uri: Uri) {
        if (mutable.value == uri) mutable.value = null
    }
}
