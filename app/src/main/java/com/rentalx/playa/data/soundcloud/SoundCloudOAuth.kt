package com.rentalx.playa.data.soundcloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.rentalx.playa.PlayaApplication
import com.rentalx.playa.BuildConfig
import com.rentalx.playa.auth.newOAuthState
import com.rentalx.playa.auth.newPkcePair
import com.rentalx.playa.data.Http
import com.rentalx.playa.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
private data class SoundCloudBrokerConfig(
    val configured: Boolean = false,
    val clientId: String = "",
    val redirectUri: String = "",
)

@Serializable
private data class SoundCloudTokenRequest(
    val code: String = "",
    val codeVerifier: String = "",
    val refreshToken: String = "",
)

@Serializable
private data class SoundCloudTokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
    val scope: String = "",
    val token_type: String = "",
)

class SoundCloudNotConfiguredException : IOException(
    "SoundCloud sign-in needs an official API application configured on the RX Playa backend.",
)

/** Official OAuth 2.1 + PKCE session. The SoundCloud client secret never enters the APK. */
object SoundCloudOAuth {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val refreshLock = Mutex()
    private val client = Http.client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun isConfigured(): Boolean = try {
        val config = configuration()
        config.configured && config.clientId.isNotBlank() && config.redirectUri == REDIRECT_URI
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun begin(context: Context) {
        val config = configuration()
        if (!config.configured || config.clientId.isBlank() || config.redirectUri != REDIRECT_URI) {
            throw SoundCloudNotConfiguredException()
        }
        val state = newOAuthState()
        val pkce = newPkcePair()
        PlayaApplication.authStore.soundCloudOAuthState = state
        PlayaApplication.authStore.soundCloudOAuthVerifier = pkce.verifier
        val uri = Uri.parse("https://secure.soundcloud.com/authorize").buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("display", "popup")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun complete(uri: Uri): SoundCloudAccount {
        require(uri.scheme == "rxmusic" && uri.host == "oauth" && uri.path == "/soundcloud") {
            "Invalid SoundCloud callback"
        }
        uri.getQueryParameter("error")?.let { error ->
            clearPending()
            throw IOException(uri.getQueryParameter("error_description") ?: error)
        }
        val expectedState = PlayaApplication.authStore.soundCloudOAuthState
        val verifier = PlayaApplication.authStore.soundCloudOAuthVerifier
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        if (expectedState.isNullOrBlank() || state != expectedState || verifier.isNullOrBlank() || code.isNullOrBlank()) {
            clearPending()
            throw IOException("SoundCloud authorization state did not match")
        }
        return try {
            val token = brokerToken("exchange", SoundCloudTokenRequest(code = code, codeVerifier = verifier))
            save(token)
            SoundCloudAccountApi.me().also { account ->
                AppSettings.setSoundCloudAccount(account.id, account.name, account.avatarUrl)
            }
        } finally {
            clearPending()
        }
    }

    suspend fun accessToken(): String? {
        val store = PlayaApplication.authStore
        val current = store.soundCloudAccessToken ?: return null
        if (store.soundCloudTokenExpiresAtMs > System.currentTimeMillis() + TOKEN_SKEW_MS) return current
        return refreshLock.withLock {
            val latest = store.soundCloudAccessToken ?: return@withLock null
            if (store.soundCloudTokenExpiresAtMs > System.currentTimeMillis() + TOKEN_SKEW_MS) return@withLock latest
            val refresh = store.soundCloudRefreshToken ?: return@withLock null
            runCatching {
                brokerToken("refresh", SoundCloudTokenRequest(refreshToken = refresh)).also(::save).access_token
            }.getOrElse {
                AppSettings.clearSoundCloudAccount()
                null
            }
        }
    }

    /** Forces one refresh after an API 401 even when the local clock says the token is fresh. */
    internal suspend fun refreshAccessToken(): String? = refreshLock.withLock {
        val refresh = PlayaApplication.authStore.soundCloudRefreshToken ?: return@withLock null
        return@withLock runCatching {
            brokerToken("refresh", SoundCloudTokenRequest(refreshToken = refresh)).also(::save).access_token
        }.getOrElse {
            AppSettings.clearSoundCloudAccount()
            null
        }
    }

    /**
     * Sign-in with a token copied from another SoundCloud client.
     *
     * The official flow above needs a client secret, and a secret cannot live
     * in an APK: it belongs to the broker, so that path works only where the
     * backend is deployed. This one works everywhere and is what the account
     * screen falls back to — the token is checked against `/me` before
     * anything is stored, so a wrong or expired one is refused here rather
     * than at the first play.
     */
    suspend fun signInWithToken(raw: String): SoundCloudAccount {
        val token = raw.trim()
            .removePrefix("OAuth ")
            .removePrefix("oauth ")
            .trim()
        require(token.isNotBlank()) { "SoundCloud token is empty" }
        AppSettings.setSoundCloudSession(token, "", System.currentTimeMillis() + DIRECT_TOKEN_MS)
        return try {
            SoundCloudAccountApi.me().also { account ->
                AppSettings.setSoundCloudAccount(account.id, account.name, account.avatarUrl)
            }
        } catch (error: Exception) {
            AppSettings.clearSoundCloudAccount()
            throw error
        }
    }

    /**
     * Whether the stored session is still accepted, checked once per launch.
     *
     * Only an explicit rejection ends the session: a dead network says nothing
     * about the token, and logging out on it would make the account look lost
     * every time the phone starts offline.
     */
    suspend fun validate(): Boolean {
        if (accessToken() == null) return false
        return runCatching {
            SoundCloudAccountApi.me()
            true
        }.getOrElse { error ->
            if (error is IOException && error.message.orEmpty().contains("401")) {
                AppSettings.clearSoundCloudAccount()
            }
            false
        }
    }

    suspend fun disconnect() {
        val token = accessToken()
        if (token != null) {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("https://api.soundcloud.com/disconnect")
                    .header("Authorization", "OAuth $token")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                runCatching { client.newCall(request).execute().close() }
            }
        }
        AppSettings.clearSoundCloudAccount()
        clearPending()
    }

    private suspend fun configuration(): SoundCloudBrokerConfig = withContext(Dispatchers.IO) {
        val endpoint = brokerUrl("config")
        val request = Request.Builder().url(endpoint).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SoundCloudNotConfiguredException()
            json.decodeFromString<SoundCloudBrokerConfig>(response.body.string())
        }
    }

    private suspend fun brokerToken(action: String, body: SoundCloudTokenRequest): SoundCloudTokenResponse =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(SoundCloudTokenRequest.serializer(), body)
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(brokerUrl(action)).post(payload).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(if (response.code == 503) {
                        "SoundCloud sign-in is not configured on the RX Playa backend"
                    } else {
                        "SoundCloud authorization failed (${response.code})"
                    })
                }
                json.decodeFromString<SoundCloudTokenResponse>(response.body.string())
            }
        }

    private fun save(token: SoundCloudTokenResponse) {
        require(token.access_token.isNotBlank() && token.refresh_token.isNotBlank() && token.expires_in > 0)
        AppSettings.setSoundCloudSession(
            token.access_token,
            token.refresh_token,
            System.currentTimeMillis() + token.expires_in * 1_000L,
        )
    }

    private fun brokerUrl(path: String): String {
        val base = BuildConfig.RX_MUSIC_BACKEND.toHttpUrlOrNull()
            ?: throw SoundCloudNotConfiguredException()
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1")) { "OAuth backend must use HTTPS" }
        return base.newBuilder()
            .addPathSegments("api/integrations/soundcloud/$path")
            .build().toString()
    }

    private fun clearPending() {
        PlayaApplication.authStore.soundCloudOAuthState = null
        PlayaApplication.authStore.soundCloudOAuthVerifier = null
    }

    private const val REDIRECT_URI = "rxmusic://oauth/soundcloud"
    private const val TOKEN_SKEW_MS = 60_000L

    /** How long a pasted token is trusted; SoundCloud's own tokens do not expire. */
    private const val DIRECT_TOKEN_MS = 365L * 24L * 60L * 60L * 1_000L
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
