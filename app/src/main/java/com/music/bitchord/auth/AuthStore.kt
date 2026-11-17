package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Encrypted-at-rest storage for credentials.
 *
 * Account credentials live here: the YouTube Music session cookie and the
 * bearer tokens for Discord and Yandex Music. None of them is a
 * password: the Google one is typed into accounts.google.com inside a WebView,
 * and the Discord one is read out of a completed login session. But both grant
 * full access to their account, so they don't go in the plain prefs the
 * scrobbler tokens use.
 *
 * A restored file whose keyset this device can't unwrap is recreated, and a
 * Keystore that can't be used at all degrades to plain prefs rather than
 * crashing on launch; see [EncryptedPrefs]. Kept out of backups for the first
 * reason, in backup_rules.xml and data_extraction_rules.xml.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences =
        EncryptedPrefs.open(context, "bitchord_auth", "bitchord_auth_plain")

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    /**
     * The durable account registry. Credentials remain in this encrypted store;
     * the old single-cookie entry is migrated lazily so an update never logs a
     * listener out.
     */
    var sessions: List<GoogleAccountSession>
        get() {
            val saved = sessionsFromJson(prefs.getString(KEY_SESSIONS, null))
            if (saved.isNotEmpty()) return saved
            val legacy = cookie ?: return emptyList()
            val profile = YouTubeProfile(
                profileId = profileId(channelPageId, channelDataSyncId, channelName ?: "Personal"),
                name = channelName ?: "Personal",
                pageId = channelPageId,
                dataSyncId = channelDataSyncId,
                authUser = channelAuthUser,
                isBrandAccount = channelPageId != null,
            )
            return listOf(GoogleAccountSession(
                accountId = sessionId(legacy, channelDataSyncId), cookie = legacy,
                profiles = listOf(profile), activeProfileId = profile.profileId,
            )).also { replaceSessions(it) }
        }
        set(value) = replaceSessions(value)

    var activeAccountId: String?
        get() = prefs.getString(KEY_ACTIVE_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_ACCOUNT, value).apply()

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE_PROFILE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    val activeSession: GoogleAccountSession?
        get() = sessions.firstOrNull { it.accountId == activeAccountId }
            ?: sessions.firstOrNull()

    fun replaceSessions(value: List<GoogleAccountSession>) {
        prefs.edit().putString(KEY_SESSIONS, value.toJson()).apply()
    }

    fun upsertSession(session: GoogleAccountSession, activate: Boolean = true) {
        val existing = sessions
        val index = existing.indexOfFirst { it.accountId == session.accountId }
        val next = if (index >= 0) {
            existing.toMutableList().also { it[index] = session }
        } else {
            existing + session
        }
        replaceSessions(next)
        if (activate) select(session.accountId, session.activeProfileId)
    }

    fun select(accountId: String, profileId: String?) {
        activeAccountId = accountId
        activeProfileId = profileId
    }

    fun removeAccount(accountId: String): GoogleAccountSession? {
        val remaining = sessions.filterNot { it.accountId == accountId }
        replaceSessions(remaining)
        val fallback = remaining.firstOrNull()
        select(fallback?.accountId.orEmpty(), fallback?.activeProfileId)
        prefs.edit().putString(KEY_COOKIE, fallback?.cookie).apply()
        BrowserSession.clearGoogleCookies()
        return fallback
    }

    val isSignedIn: Boolean
        get() = activeSession?.cookie?.let { hasApiSid(it) } == true

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /** OAuth bearer token used only with Yandex Music REST and Ynison hosts. */
    var yandexMusicToken: String?
        get() = prefs.getString(KEY_YANDEX_MUSIC_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_YANDEX_MUSIC_TOKEN, value).apply()

    /** Official SoundCloud OAuth 2.1 session. Refresh tokens are single-use. */
    var soundCloudAccessToken: String?
        get() = prefs.getString(KEY_SOUNDCLOUD_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SOUNDCLOUD_ACCESS_TOKEN, value).apply()

    var soundCloudRefreshToken: String?
        get() = prefs.getString(KEY_SOUNDCLOUD_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SOUNDCLOUD_REFRESH_TOKEN, value).apply()

    var soundCloudTokenExpiresAtMs: Long
        get() = prefs.getLong(KEY_SOUNDCLOUD_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_SOUNDCLOUD_EXPIRES_AT, value).apply()

    var soundCloudOAuthState: String?
        get() = prefs.getString(KEY_SOUNDCLOUD_OAUTH_STATE, null)
        set(value) = prefs.edit().putString(KEY_SOUNDCLOUD_OAUTH_STATE, value).apply()

    var soundCloudOAuthVerifier: String?
        get() = prefs.getString(KEY_SOUNDCLOUD_OAUTH_VERIFIER, null)
        set(value) = prefs.edit().putString(KEY_SOUNDCLOUD_OAUTH_VERIFIER, value).apply()

    /** VK ID session. Audio access remains separately gated by VK approval. */
    var vkAccessToken: String?
        get() = prefs.getString(KEY_VK_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_VK_ACCESS_TOKEN, value).apply()

    var vkRefreshToken: String?
        get() = prefs.getString(KEY_VK_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_VK_REFRESH_TOKEN, value).apply()

    var vkIdToken: String?
        get() = prefs.getString(KEY_VK_ID_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_VK_ID_TOKEN, value).apply()

    var vkDeviceId: String?
        get() = prefs.getString(KEY_VK_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_VK_DEVICE_ID, value).apply()

    var vkTokenExpiresAtMs: Long
        get() = prefs.getLong(KEY_VK_EXPIRES_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_VK_EXPIRES_AT, value).apply()

    var vkOAuthState: String?
        get() = prefs.getString(KEY_VK_OAUTH_STATE, null)
        set(value) = prefs.edit().putString(KEY_VK_OAUTH_STATE, value).apply()

    var vkOAuthVerifier: String?
        get() = prefs.getString(KEY_VK_OAUTH_VERIFIER, null)
        set(value) = prefs.edit().putString(KEY_VK_OAUTH_VERIFIER, value).apply()

    /**
     * The WebDAV server password. Encrypted like every other credential here:
     * a backup export carries the server URL and username in plain prefs, but
     * never this.
     */
    var webdavPassword: String?
        get() = prefs.getString(KEY_WEBDAV_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_WEBDAV_PASSWORD, value).apply()

    /**
     * The SMB share password. Encrypted like every other credential here:
     * exports carry the server, share and username in plain prefs, but never
     * this.
     */
    var smbPassword: String?
        get() = prefs.getString(KEY_SMB_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_SMB_PASSWORD, value).apply()

    /**
     * The channel the listener chose to act as, if they chose one.
     *
     * Stored beside the cookie rather than in the plain settings because it is
     * only meaningful with that cookie and must not outlive it: a `dataSyncId`
     * from one login, replayed under another, is answered with 401 on every
     * request. [signOut] and [onNewSession] both clear it for that reason.
     *
     * A null [channelPageId] with a [channelDataSyncId] set is a real state,
     * not an absent one — it is the account's own channel, deliberately chosen
     * over a brand channel the web player would otherwise default to.
     */
    val channelPageId: String? get() = prefs.getString(KEY_CHANNEL_PAGE_ID, null)
    val channelDataSyncId: String? get() = prefs.getString(KEY_CHANNEL_DATASYNC_ID, null)

    /** The chosen channel's display name, for the settings row. */
    val channelName: String? get() = prefs.getString(KEY_CHANNEL_NAME, null)

    /** Which Google account in the jar it belongs to; null means "as the shell says". */
    val channelAuthUser: String? get() = prefs.getString(KEY_CHANNEL_AUTH_USER, null)

    fun selectChannel(
        pageId: String?,
        dataSyncId: String?,
        name: String?,
        authUser: String? = null,
    ) = prefs.edit()
        .putString(KEY_CHANNEL_PAGE_ID, pageId)
        .putString(KEY_CHANNEL_DATASYNC_ID, dataSyncId)
        .putString(KEY_CHANNEL_NAME, name)
        .putString(KEY_CHANNEL_AUTH_USER, authUser)
        .apply()

    /**
     * The chosen channel's name, once something knows it.
     *
     * Separate from [selectChannel] because the two are learned at different
     * times: a channel picked in the in-app browser is identified by ids the
     * moment it is picked, and named only after the next account fetch comes
     * back to say what it is called.
     */
    fun setChannelName(name: String?) =
        prefs.edit().putString(KEY_CHANNEL_NAME, name).apply()

    /** Back to whichever channel YouTube Music serves by default. */
    fun clearChannel() = prefs.edit()
        .remove(KEY_CHANNEL_PAGE_ID)
        .remove(KEY_CHANNEL_DATASYNC_ID)
        .remove(KEY_CHANNEL_NAME)
        .remove(KEY_CHANNEL_AUTH_USER)
        .apply()

    /**
     * A fresh login lands here. The cookie is new, so any channel chosen under
     * the old one names an identity this session cannot act as.
     */
    fun onNewSession(cookie: String) {
        this.cookie = cookie
        clearChannel()
    }

    /**
     * Signs out of YouTube Music only — the Discord login is a separate account.
     */
    fun signOut() {
        prefs.edit().remove(KEY_COOKIE).remove(KEY_SESSIONS)
            .remove(KEY_ACTIVE_ACCOUNT).remove(KEY_ACTIVE_PROFILE).apply()
        clearChannel()
        // The in-app browser keeps its own copy of the Google login, and a
        // sign-out that leaves it in place is not one: the next sign-in is
        // waved straight through as the account just signed out of, with no
        // opportunity to choose another. See [BrowserSession].
        BrowserSession.clearGoogleCookies()
    }

    companion object {
        /**
         * Whether a cookie header carries a secret Innertube requests can be
         * signed with.
         *
         * Matched on the cookie *name*, which reads as pedantry and is not. The
         * test used to be `cookie.contains("SAPISID")`, and `__Secure-3PAPISID`
         * contains "SAPISID" — so a jar holding only the `__Secure-` forms, which
         * is what a partitioned-cookie login produces, passed a check for a
         * cookie it did not have. The app then declared itself signed in and made
         * every request unsigned, which Google answers as a stranger. Library
         * reads degraded quietly and history was never written at all.
         */
        fun hasApiSid(cookieHeader: String): Boolean =
            cookieHeader.split(';').any { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name in API_SID_NAMES && value.isNotEmpty()
            }

        /** Accepts a browser's Cookie header, never a full request or Set-Cookie attributes. */
        fun normalizeGoogleCookieHeader(input: String): String? {
            if (input.length > 65_536 || input.any { it.code < 32 || it.code > 126 }) return null
            val header = input.trim().let {
                if (it.startsWith("Cookie:", ignoreCase = true)) it.substringAfter(':').trim() else it
            }
            if (header.isEmpty() || header.any { it.code < 32 || it.code > 126 }) return null
            val entries = LinkedHashMap<String, String>()
            for (entry in header.split(';')) {
                if (entry.isBlank()) continue
                val separator = entry.indexOf('=')
                if (separator <= 0) return null
                val name = entry.substring(0, separator).trim()
                val value = entry.substring(separator + 1).trim()
                if (!COOKIE_NAME.matches(name) || name.lowercase() in COOKIE_ATTRIBUTES ||
                    value.any { it <= ' ' || it == ',' || it == '"' || it == '\\' }
                ) return null
                val previous = entries.put(name, value)
                if (previous != null && previous != value) return null
            }
            val normalized = entries.entries.joinToString("; ") { (name, value) -> "$name=$value" }
            return normalized.takeIf(::hasApiSid)
        }

        private val COOKIE_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val COOKIE_ATTRIBUTES = setOf("path", "domain", "expires", "max-age", "samesite")
        private val API_SID_NAMES =
            setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID")

        private const val KEY_COOKIE = "cookie"
        private const val KEY_SESSIONS = "google_account_sessions_v2"
        private const val KEY_ACTIVE_ACCOUNT = "active_google_account_id_v2"
        private const val KEY_ACTIVE_PROFILE = "active_youtube_profile_id_v2"
        private const val KEY_CHANNEL_PAGE_ID = "channel_page_id"
        private const val KEY_CHANNEL_DATASYNC_ID = "channel_datasync_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_CHANNEL_AUTH_USER = "channel_auth_user"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_YANDEX_MUSIC_TOKEN = "yandex_music_token"
        private const val KEY_SOUNDCLOUD_ACCESS_TOKEN = "soundcloud_access_token"
        private const val KEY_SOUNDCLOUD_REFRESH_TOKEN = "soundcloud_refresh_token"
        private const val KEY_SOUNDCLOUD_EXPIRES_AT = "soundcloud_expires_at"
        private const val KEY_SOUNDCLOUD_OAUTH_STATE = "soundcloud_oauth_state"
        private const val KEY_SOUNDCLOUD_OAUTH_VERIFIER = "soundcloud_oauth_verifier"
        private const val KEY_VK_ACCESS_TOKEN = "vk_access_token"
        private const val KEY_VK_REFRESH_TOKEN = "vk_refresh_token"
        private const val KEY_VK_ID_TOKEN = "vk_id_token"
        private const val KEY_VK_DEVICE_ID = "vk_device_id"
        private const val KEY_VK_EXPIRES_AT = "vk_expires_at"
        private const val KEY_VK_OAUTH_STATE = "vk_oauth_state"
        private const val KEY_VK_OAUTH_VERIFIER = "vk_oauth_verifier"
        private const val KEY_WEBDAV_PASSWORD = "webdav_password"
        private const val KEY_SMB_PASSWORD = "smb_password"
    }
}
