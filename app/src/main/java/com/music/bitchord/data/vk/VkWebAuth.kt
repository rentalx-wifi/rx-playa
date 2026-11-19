package com.music.bitchord.data.vk

import android.net.Uri
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * VK Music sign-in that actually carries the `audio` permission.
 *
 * VK ID refuses the Music API to third-party apps, so its token authenticates
 * the account and nothing else — that is why the official flow was gated
 * behind `VK_AUDIO_SCOPE_APPROVED`, and why the app looked like it had no VK
 * login at all. The implicit web flow with a client id that already holds the
 * audio permission does return a token `audio.search` accepts, and it is the
 * one open-source players use.
 *
 * The password never reaches this app: [VkWebLoginScreen] shows VK's own page,
 * so 2FA, captcha and the device-confirmation screen are handled by VK itself
 * and arrive as an ordinary redirect. All this object does is read the token
 * out of that redirect, check it against `users.get`, and keep it in the same
 * session store the VK ID flow used.
 */
class VkTokenRejectedException(message: String) : IOException(message)

object VkWebAuth {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()

    /** The page to load in the WebView. */
    fun authorizationUrl(): String = "https://oauth.vk.com/authorize".toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId())
        .addQueryParameter("scope", "audio,offline")
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("display", "touch")
        .addQueryParameter("response_type", "token")
        .addQueryParameter("revoke", "1")
        .build()
        .toString()

    /**
     * The token out of VK's redirect, or null while the page is still VK's own.
     *
     * A refusal comes back with `error` instead of a token — VK puts that in
     * the fragment too, so both halves are read from the same place.
     */
    fun parseRedirect(uri: Uri): Redirect? {
        if (!uri.toString().startsWith(REDIRECT_URI)) return null
        val fragment = uri.fragment.orEmpty()
        if (fragment.isBlank()) return null
        val params = fragment.split('&').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
        }.toMap()
        val error = params["error_description"] ?: params["error"]
        if (error != null) return Redirect(error = error)
        val token = params["access_token"] ?: return null
        return Redirect(
            accessToken = token,
            userId = params["user_id"],
            expiresInSeconds = params["expires_in"]?.toLongOrNull() ?: 0L,
        )
    }

    /** Checks the token against VK itself and stores the session when it answers. */
    suspend fun complete(redirect: Redirect): VkAccount {
        val token = redirect.accessToken ?: throw IOException(redirect.error ?: "VK returned no token")
        val account = profile(token)
        AppSettings.setVkSession(
            token,
            refreshToken = "",
            idToken = "",
            deviceId = "",
            // 0 is VK's "does not expire"; a year is the honest stand-in for a
            // session that has no stated end and is re-checked on every launch.
            expiresAtMs = System.currentTimeMillis() +
                (redirect.expiresInSeconds.takeIf { it > 0L } ?: ONE_YEAR_SECONDS) * 1_000L,
        )
        AppSettings.setVkAccount(account.id, account.name, account.avatarUrl)
        return account
    }

    /**
     * Whether the stored token still opens `users.get`.
     *
     * Run at launch and whenever the account screen opens: a token VK has
     * revoked is otherwise only discovered at the first track, as a failed
     * search with no explanation. The cleared session is the point — an
     * invalid token that stays on disk is a login that looks connected.
     */
    suspend fun validate(): Boolean {
        val token = AppSettings.vkAccessToken.value.takeIf(String::isNotBlank) ?: return false
        return runCatching {
            profile(token)
            true
        }.getOrElse { error ->
            // Only VK's own refusal ends the session: a dead network says
            // nothing about the token, and logging out on it would cost the
            // account every time the phone starts offline.
            if (error is VkTokenRejectedException) AppSettings.clearVkAccount()
            false
        }
    }

    suspend fun logout() {
        // No server call: the token was issued for the web flow and the
        // browser session it came from is not kept. Dropping it here is what
        // makes the account read as signed out everywhere else in the app.
        AppSettings.clearVkAccount()
    }

    fun isAuthenticated(): Boolean = AppSettings.vkAccessToken.value.isNotBlank()
    private suspend fun profile(token: String): VkAccount = withContext(Dispatchers.IO) {
        val url = "https://api.vk.com/method/users.get".toHttpUrl().newBuilder()
            .addQueryParameter("access_token", token)
            .addQueryParameter("fields", "photo_200")
            .addQueryParameter("v", "5.199")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("VK profile request failed (${response.code})")
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            (root["error"] as? JsonObject)?.let { error ->
                // VK answered and refused the token. A network failure never
                // reaches this branch, which is what lets [validate] tell the
                // two apart.
                throw VkTokenRejectedException(error.text("error_msg") ?: "VK rejected the token")
            }
            val row = (root["response"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw IOException("VK profile is empty")
            VkAccount(
                id = row.text("id") ?: throw IOException("VK profile has no id"),
                name = listOfNotNull(row.text("first_name"), row.text("last_name"))
                    .joinToString(" ").ifBlank { "VK" },
                avatarUrl = row.text("photo_200"),
            )
        }
    }

    private fun clientId(): String =
        BuildConfig.VK_AUDIO_CLIENT_ID.takeIf(String::isNotBlank)
            ?: throw VkNotConfiguredException()

    data class Redirect(
        val accessToken: String? = null,
        val userId: String? = null,
        val expiresInSeconds: Long = 0L,
        val error: String? = null,
    )

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }

    private const val REDIRECT_URI = "https://oauth.vk.com/blank.html"
    private const val ONE_YEAR_SECONDS = 365L * 24L * 60L * 60L
}
