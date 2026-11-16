package com.music.bitchord.data.vk

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.music.bitchord.BitChordApplication
import com.music.bitchord.BuildConfig
import com.music.bitchord.auth.newOAuthState
import com.music.bitchord.auth.newPkcePair
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class VkNotConfiguredException : IOException("VK ID client id is not configured for this build.")

data class VkAccount(val id: String, val name: String, val avatarUrl: String?)

/** Official VK ID browser + PKCE flow. No WebView and no password interception. */
object VkIdAuth {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val refreshLock = Mutex()

    suspend fun begin(context: Context) {
        val clientId = BuildConfig.VK_ID_CLIENT_ID.takeIf(String::isNotBlank)
            ?: throw VkNotConfiguredException()
        val state = newOAuthState()
        val pkce = newPkcePair()
        BitChordApplication.authStore.vkOAuthState = state
        BitChordApplication.authStore.vkOAuthVerifier = pkce.verifier
        val authorization = Uri.parse("https://id.vk.ru/authorize").buildUpon()
            .appendQueryParameter("state", state)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri())
            .appendQueryParameter("lang_id", "0")
            .appendQueryParameter("scheme", "space_gray")
        // VK grants only the basic identity by default. Builds whose VK app has
        // separately received the restricted Music API approval must request
        // that permission during the same official VK ID flow; otherwise login
        // succeeds but every audio request is rejected for a missing scope.
        if (BuildConfig.VK_AUDIO_SCOPE_APPROVED) {
            authorization.appendQueryParameter("scope", "audio")
        }
        val uri = authorization.build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun complete(uri: Uri): VkAccount {
        val callback = parseVkCallback(uri)
        val expected = BitChordApplication.authStore.vkOAuthState
        val verifier = BitChordApplication.authStore.vkOAuthVerifier
        if (callback.error != null) {
            clearPending()
            throw IOException(callback.error)
        }
        if (expected.isNullOrBlank() || callback.state != expected || verifier.isNullOrBlank() ||
            callback.code.isNullOrBlank() || callback.deviceId.isNullOrBlank()
        ) {
            clearPending()
            throw IOException("VK authorization state did not match")
        }
        return try {
            val root = post(
                "https://id.vk.ru/oauth2/auth",
                FormBody.Builder()
                    .add("client_id", BuildConfig.VK_ID_CLIENT_ID)
                    .add("grant_type", "authorization_code")
                    .add("code_verifier", verifier)
                    .add("device_id", callback.deviceId)
                    .add("code", callback.code)
                    .add("redirect_uri", redirectUri())
                    .add("state", callback.state)
                    .build(),
            )
            val access = saveTokens(root, callback.deviceId)
            userInfo(access, callback.deviceId).also { account ->
                AppSettings.setVkAccount(account.id, account.name, account.avatarUrl)
            }
        } finally {
            clearPending()
        }
    }

    suspend fun accessToken(): String? {
        val store = BitChordApplication.authStore
        val current = store.vkAccessToken ?: return null
        if (store.vkTokenExpiresAtMs > System.currentTimeMillis() + 60_000L) return current
        return refreshLock.withLock {
            val latest = store.vkAccessToken ?: return@withLock null
            if (store.vkTokenExpiresAtMs > System.currentTimeMillis() + 60_000L) return@withLock latest
            val refresh = store.vkRefreshToken ?: return@withLock latest
            val device = store.vkDeviceId ?: return@withLock latest
            runCatching {
                val state = newOAuthState()
                val root = post(
                    "https://id.vk.ru/oauth2/auth",
                    FormBody.Builder()
                        .add("client_id", BuildConfig.VK_ID_CLIENT_ID)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refresh)
                        .add("device_id", device)
                        .add("state", state)
                        .build(),
                )
                saveTokens(root, device)
            }.getOrElse {
                AppSettings.clearVkAccount()
                null
            }
        }
    }

    suspend fun logout() {
        val access = BitChordApplication.authStore.vkAccessToken
        val device = BitChordApplication.authStore.vkDeviceId
        if (!access.isNullOrBlank() && !device.isNullOrBlank() && BuildConfig.VK_ID_CLIENT_ID.isNotBlank()) {
            runCatching {
                post(
                    "https://id.vk.ru/oauth2/logout",
                    FormBody.Builder()
                        .add("client_id", BuildConfig.VK_ID_CLIENT_ID)
                        .add("device_id", device)
                        .add("access_token", access)
                        .build(),
                )
            }
        }
        AppSettings.clearVkAccount()
        clearPending()
    }

    private suspend fun userInfo(access: String, device: String): VkAccount {
        val root = post(
            "https://id.vk.ru/oauth2/user_info?client_id=${Uri.encode(BuildConfig.VK_ID_CLIENT_ID)}",
            FormBody.Builder().add("device_id", device).add("access_token", access).build(),
        )
        val user = (root["user"] as? JsonObject) ?: root
        val id = user.text("user_id") ?: throw IOException("VK profile has no id")
        val name = listOfNotNull(user.text("first_name"), user.text("last_name"))
            .joinToString(" ").ifBlank { "VK" }
        return VkAccount(id, name, user.text("avatar"))
    }

    private fun saveTokens(root: JsonObject, deviceId: String): String {
        val access = root.text("access_token") ?: throw IOException("VK returned no access token")
        val refresh = root.text("refresh_token") ?: BitChordApplication.authStore.vkRefreshToken.orEmpty()
        val idToken = root.text("id_token") ?: BitChordApplication.authStore.vkIdToken.orEmpty()
        val expires = (root.number("expires_in") ?: 3_600L).takeIf { it > 0L } ?: 3_600L
        AppSettings.setVkSession(
            access,
            refresh,
            idToken,
            deviceId,
            System.currentTimeMillis() + expires * 1_000L,
        )
        return access
    }

    private suspend fun post(url: String, body: okhttp3.RequestBody): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("VK authorization failed (${response.code})")
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            root.text("error")?.let { throw IOException(root.text("error_description") ?: it) }
            root
        }
    }

    private fun redirectUri(): String = "${BuildConfig.VK_ID_REDIRECT_SCHEME}://vk.ru/blank.html"

    private fun clearPending() {
        BitChordApplication.authStore.vkOAuthState = null
        BitChordApplication.authStore.vkOAuthVerifier = null
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" }
    private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
}

internal data class VkCallback(
    val code: String?,
    val state: String?,
    val deviceId: String?,
    val error: String?,
)

internal fun parseVkCallback(uri: Uri): VkCallback {
    val payload = uri.getQueryParameter("payload")?.let { raw ->
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }
    fun value(name: String): String? = uri.getQueryParameter(name)
        ?: (payload?.get(name) as? JsonPrimitive)?.contentOrNull
    return VkCallback(
        code = value("code"),
        state = value("state"),
        deviceId = value("device_id"),
        error = value("error_description") ?: value("error"),
    )
}
