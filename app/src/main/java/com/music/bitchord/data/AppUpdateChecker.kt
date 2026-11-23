package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import java.io.File

/**
 * The update that this fork's own GitHub releases offer, if it is newer.
 *
 * Upstream's checker pointed at BitChord's repository; this fork must never
 * offer an upstream APK, so it was silenced when the fork started. It now reads
 * this project's own `releases/latest` instead: one request at launch, the tag
 * compared with the running version, and an APK asset if the release carries
 * one. A listener who never opens GitHub still gets told there is a new build,
 * and installs it from here.
 */
object AppUpdateChecker {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val apkUrl: String?,
        /** The release's own Markdown body, shown as this update's "what's new". */
        val notes: String?,
        /** A test build off the beta channel, marked as such in the prompt. */
        val isBeta: Boolean = false,
        /** The asset's size as GitHub states it, or null when it does not. */
        val apkBytes: Long? = null,
        /** The versionCode the release states in its notes. */
        val versionCode: Int? = null,
    )

    private const val CACHE_SUBDIR = "updates"

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()

    /** Where this update's APK download currently stands, for the dialog's progress row. */
    sealed interface DownloadState {
        data object Idle : DownloadState
        data class Downloading(val fraction: Float) : DownloadState
        data class Ready(val file: File) : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download = _download.asStateFlow()

    /** Set from the UI thread when the user cancels; polled between network reads. */
    @Volatile
    private var downloadCancelled = false

    /**
     * The notes of the release this phone is already running, offered once.
     *
     * Separate from [available]: that one is a build to install, this one is
     * the changelog of the build already installed, and the two never stand
     * at the same time (a newer release is the first case, the running one the
     * second). The version is the one written down here, so dismissing it is
     * what makes the card "once".
     */
    data class WhatsNew(val version: String, val notes: String)

    private val _whatsNew = MutableStateFlow<WhatsNew?>(null)
    val whatsNew = _whatsNew.asStateFlow()

    /**
     * The two channels.
     *
     * Stable reads GitHub's `latest`, which by GitHub's own rule never points
     * at a pre-release. Beta reads the release list instead, newest first, so
     * a test build is visible while it is still a pre-release and the stable
     * one replaces it the moment it is published.
     */
    /**
     * Where the releases live, newest location first.
     *
     * The project moved from a personal account to the `rx-playa-team`
     * organisation, and an installed phone cannot be told about that: it keeps
     * asking the address it was built with. Both are tried, so a build made
     * before the move still finds the new releases and one made after it still
     * works if the old repository is what answers.
     */
    private val RELEASE_HOSTS = listOf(
        "https://api.github.com/repos/rx-playa-team/rx-playa",
        "https://api.github.com/repos/rentalx-wifi/rx-playa",
    )

    private const val LATEST_PATH = "/releases/latest"
    private const val LIST_PATH = "/releases?per_page=20"

    /**
     * The release [beta]'s channel is offering right now, or null when GitHub
     * did not answer.
     *
     * Both [check] and [newestVersion] ask this question; only what they do
     * with the answer differs — one offers the build, the other compares it.
     * Stable asks for `latest`; beta asks for the list and takes the newest by
     * version, so a pre-release is visible there while a published one is
     * invisible to the stable channel.
     */
    private suspend fun releaseFrom(beta: Boolean): JSONObject? {
        val json = runCatching {
            withContext(Dispatchers.IO) {
                val path = if (beta) LIST_PATH else LATEST_PATH
                RELEASE_HOSTS.firstNotNullOfOrNull { host ->
                    val request = Request.Builder()
                        .url(host + path)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                    Http.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null else response.body?.string()
                    }
                }
            }
        }.getOrNull() ?: return null
        return runCatching {
            if (beta) {
                val list = JSONArray(json)
                val offered = (0 until list.length())
                    .mapNotNull { list.optJSONObject(it) }
                    .filter { !it.optBoolean("draft", false) }
                    .map { it to versionOf(it) }
                // The list comes back newest-created first, which is not the same
                // as newest by version: a backported stable published after a
                // beta would win on date and carry the older number. The issue
                // asks for the newest of the two lines, so the order is ignored
                // and the versions decide.
                val newest = newestOf(offered.map { it.second })
                (offered.firstOrNull { it.second == newest } ?: offered.firstOrNull())?.first
            } else {
                JSONObject(json)
            }
        }.getOrNull()
    }

    /** What a release calls itself, read the same way wherever it is needed. */
    private fun versionOf(release: JSONObject): String? =
        releaseVersion(
            title = release.optString("name"),
            tag = release.optString("tag_name"),
            assetNames = assetNames(release),
        )

    /** The file names a release carries, which can be the only place its version is written. */
    private fun assetNames(release: JSONObject): List<String> {
        val assets = release.optJSONArray("assets") ?: return emptyList()
        return (0 until assets.length()).mapNotNull { assets.optJSONObject(it)?.optString("name") }
    }

    /**
     * The newest version the [beta] channel offers, without offering it.
     *
     * Asked before a channel switch, because that switch's confirmation has to
     * say which way the version moves and only the target channel knows where
     * it ends. Null when the channel cannot be reached or its newest release
     * carries no version at all: there is then nothing to compare.
     */
    suspend fun newestVersion(beta: Boolean): String? {
        val release = releaseFrom(beta) ?: return null
        return releaseVersion(
            title = release.optString("name"),
            tag = release.optString("tag_name"),
            assetNames = assetNames(release),
        )
    }

    /**
     * Asks GitHub for the latest release of this fork and offers it when its
     * version is newer than the one running.
     *
     * Nothing here is fatal: a phone with no network, a rate-limited API or a
     * repository that has no release yet simply leaves no update on offer.
     */
    suspend fun check(
        context: Context? = null,
        force: Boolean = false,
        beta: Boolean = AppSettings.betaUpdates.value,
    ) {
        context?.let { app ->
            prefs = app.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
            dismissedTag = prefs?.getString(KEY_DISMISSED_UPDATE, null)
        }
        // Once a quarter of an hour at most unless asked for. The check runs on
        // every return to the foreground, and without this a flapping connection
        // would mean a GitHub request per resume.
        val now = System.currentTimeMillis()
        val last = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L
        if (!force && now - last < CHECK_INTERVAL_MS) return
        val release = releaseFrom(beta) ?: return
        // Only a successful answer counts as a check. Recording the attempt made
        // a launch without a connection look like an up-to-date phone, and the
        // next attempt was an hour away.
        prefs?.edit()?.putLong(KEY_LAST_CHECK, now)?.apply()
        val tag = release.optString("tag_name").ifBlank { return }
        // Rolling releases are named for their channel, not for a version:
        // "beta" and "stable" say nothing about which build is inside. The
        // version is the first real one among the title, the tag and the asset
        // names (issue #38).
        val version = releaseVersion(
            title = release.optString("name"),
            tag = tag,
            assetNames = assetNames(release),
        )
        val notes = release.optString("body").takeIf { it.isNotBlank() }
        // A release may state the versionCode it was built with, which is the
        // only number that decides whether Android will install it. Two betas
        // of one feature share an ordinal (v46b1, v46b2), so the tag alone is
        // not enough to tell them apart.
        val publishedCode = versionCodeIn(release.optString("body"))
        val newer = if (publishedCode != null) {
            publishedCode > BuildConfig.VERSION_CODE
        } else {
            version != null && compareVersions(version, BuildConfig.VERSION_NAME) > 0
        }
        if (!newer) {
            // Nothing to install — but the release may be the build the phone is
            // already running, and then its body is what "what's new" shows.
            offerWhatsNew(version, notes)
            forgetOfferFromOtherChannel(beta)
            return
        }
        // "Remind me later" silences this prompt, not the notes above it.
        if (tag == dismissedTag) return

        val assets = release.optJSONArray("assets")
        val candidates = buildList {
            for (index in 0 until (assets?.length() ?: 0)) {
                val asset = assets?.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url").ifBlank { null } ?: continue
                add(name to url)
            }
        }
        // No asset of this app's own is no update. Offering the release anyway
        // would send a phone to the TV build and a TV to the phone one — an
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE at the end of the download, or
        // worse, an install over the other app's package.
        //
        // Size and versionCode travel with the offer so the download can prove
        // it is whole: a truncated 190 MB APK installs as "файл повреждён", and
        // the phone that downloaded it had no way to tell the cut file apart
        // from a good one — see [downloadApk].
        val apkUrl = chooseApk(candidates) ?: return
        val apkBytes = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .firstOrNull { it.optString("browser_download_url") == apkUrl }
            ?.optLong("size")
            ?.takeIf { it > 0 }
        _available.value = UpdateInfo(
            // The version, not the tag: a rolling release calls itself "beta",
            // and that is not a number anybody can compare or recognise.
            version = version ?: tag,
            releaseUrl = release.optString("html_url"),
            apkUrl = apkUrl,
            notes = notes,
            isBeta = release.optBoolean("prerelease", false),
            apkBytes = apkBytes,
            versionCode = publishedCode,
        )
        offeredChannel = beta
        offeredTag = tag
    }

    /** Which channel the current offer came from, so the other one can take it back. */
    @Volatile
    private var offeredChannel: Boolean? = null

    /**
     * The release tag behind the current offer.
     *
     * Kept apart from [UpdateInfo.version]: that one is the number the dialog
     * shows, this is the name GitHub knows the release by, and "Remind me
     * later" has to silence the tag the next check will look up.
     */
    @Volatile
    private var offeredTag: String? = null

    /**
     * Offers the notes of the build the phone is already running, once.
     *
     * The caller has already established that this release is nothing to
     * install; the only thing left it can be is the version in the caller's
     * hands. Whether it is worth showing is decided by the version written down
     * for the last notes the listener read: newer than that means this install
     * brought changes nobody has been told about (an APK taken straight from
     * the releases page, say), and the same or older means they have been.
     */
    private fun offerWhatsNew(version: String?, notes: String?) {
        if (version == null || notes == null) return
        if (compareVersions(version, BuildConfig.VERSION_NAME) != 0) return
        val seen = AppSettings.lastWhatsNewVersion.value
        if (seen == null) {
            // A fresh install has no earlier version to be newer than: recording
            // it here keeps the very first launch from announcing the app itself.
            AppSettings.setLastWhatsNewVersion(version)
            return
        }
        if (compareVersions(version, seen) <= 0) return
        _whatsNew.value = WhatsNew(version = version, notes = notes)
    }

    /** The notes for [version] are on screen now, so the card above never repeats them. */
    fun markNotesShown(version: String) {
        AppSettings.setLastWhatsNewVersion(version)
    }

    fun dismissWhatsNew() {
        _whatsNew.value?.let { markNotesShown(it.version) }
        _whatsNew.value = null
    }

    /**
     * Drops a prompt that the channel just switched away from.
     *
     * A beta build offered under the beta channel is not an answer the stable
     * one gave, and leaving it on screen after the switch would offer the very
     * thing the switch was made to avoid. A download in flight is left alone:
     * the file is already being written and the prompt is its only way to an
     * install.
     */
    private fun forgetOfferFromOtherChannel(beta: Boolean) {
        if (offeredChannel == null || offeredChannel == beta) return
        if (_download.value !is DownloadState.Idle) return
        _available.value = null
        offeredChannel = null
        offeredTag = null
    }

    /**
     * The APK in [assets] this app may install: this app's own platform, and its
     * own architecture where the release splits them.
     *
     * [assets] is name to download URL. The release names its files by
     * architecture ("…-arm64.apk", "…-arm32.apk") rather than by the ABI string
     * the platform reports ("arm64-v8a"), so the match is made on the short
     * names; a universal build is the next best answer, and only then anything at
     * all — but never a file of the other platform.
     */
    internal fun chooseApk(
        assets: List<Pair<String, String>>,
        prefix: String = BuildConfig.UPDATE_APK_PREFIX,
        abis: List<String> = abiTokens(),
    ): String? {
        var byAbi: String? = null
        var universal: String? = null
        var firstApk: String? = null
        for ((name, url) in assets) {
            if (!name.startsWith(prefix, ignoreCase = true)) continue
            if (firstApk == null) firstApk = url
            if (universal == null && name.contains("universal", ignoreCase = true)) universal = url
            if (byAbi == null && abis.any { name.contains(it, ignoreCase = true) }) byAbi = url
        }
        return byAbi ?: universal ?: firstApk
    }

    /** "Remind me later", or the release page has been opened instead. */
    fun dismiss() {
        val tag = offeredTag
        _available.value = null
        offeredChannel = null
        offeredTag = null
        if (tag != null) {
            dismissedTag = tag
            runCatching { prefs?.edit()?.putString(KEY_DISMISSED_UPDATE, tag)?.apply() }
        }
    }

    /** The tag "Remind me later" was last used on, so a rotation cannot re-ask. */
    @Volatile
    private var dismissedTag: String? = null
    private var prefs: android.content.SharedPreferences? = null
    private const val KEY_DISMISSED_UPDATE = "dismissed_update_tag"
    private const val KEY_LAST_CHECK = "last_update_check_at"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    /**
     * What this phone's architecture is called in release asset names.
     *
     * `Build.SUPPORTED_ABIS` answers `arm64-v8a`; a release file is called
     * `...-arm64.apk`. The first entry is the primary ABI, and that is what the
     * APK to download has to match.
     */
    private fun abiTokens(): List<String> {
        val primary = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            primary.startsWith("arm64") -> listOf("arm64")
            primary.startsWith("armeabi") -> listOf("armeabi", "arm32", "armv7")
            primary.startsWith("x86_64") -> listOf("x86_64", "x64")
            primary.startsWith("x86") -> listOf("x86")
            else -> emptyList()
        }
    }

    /**
     * A version comparable across release tags and installed version names.
     *
     * The revision number of the fork is the number, and the beta of that
     * revision is the tie-break, so `v46b9` is above `v46b8` and `v45` is below
     * both. All the shapes this project uses resolve to it: `v46b8`, `v45`,
     * `rx-playa-v46b8`, `1.9-rx-playa-v46b8`. The last `vNN` wins, because a
     * version name carries the display version in front of it and `1.9` must not
     * be read as revision 1.
     *
     * A name with no revision at all falls back to its beta, and to 0 without
     * one: that is a stable build of the base version, and any tagged revision
     * is newer than it.
     */
/**
     * The version a release actually carries.
     *
     * A tag is the first guess, but rolling releases are tagged with the channel
     * name, and an organisation may name the release differently again. The
     * first candidate that looks like a version wins; null when none does, which
     * is the honest answer for a release nobody labelled.
     */
    internal fun releaseVersion(title: String?, tag: String?, assetNames: List<String>): String? =
        (listOfNotNull(title, tag) + assetNames).firstNotNullOfOrNull { versionToken(it) }

    /**
     * The version-shaped run inside [text], without the words around it.
     *
     * "RX Playa v1.9.4" and "rxplaya-v1.9.4-universal.apk" both carry a version
     * and neither *is* one; the comparison needs the run itself.
     */
    internal fun versionToken(text: String): String? =
        Regex("""v\d+(?:\.\d+)*(?:b\d+)?""", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.value
            ?: Regex("""beta\d+""", RegexOption.IGNORE_CASE).find(text)?.value

    /**
     * Which of two versions is newer: -1, 0 or 1.
     *
     * Two shapes live in this project. Test builds are numbered `v<ordinal>b<n>`
     * and compare as one integer (see [versionOrdinal]); published versions are
     * dotted like `1.9.4`, where "1.10" is newer than "1.9" and an integer
     * comparison of the leading `v1` would call them equal. Dotted versions are
     * therefore compared component by component, and anything else falls back to
     * the ordinal.
     */
    internal fun compareVersions(a: String, b: String): Int {
        val left = versionToken(a) ?: a
        val right = versionToken(b) ?: b
        val leftParts = dottedParts(left)
        val rightParts = dottedParts(right)
        if (leftParts != null && rightParts != null) {
            for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
                val one = leftParts.getOrElse(index) { 0 }
                val two = rightParts.getOrElse(index) { 0 }
                if (one != two) return if (one > two) 1 else -1
            }
            return 0
        }
        return versionOrdinal(left).compareTo(versionOrdinal(right))
    }

    /**
     * The newest of [versions], with the ones nobody labelled ignored.
     *
     * Used by the beta channel, which sees stables and pre-releases together
     * and has to pick one: "newest" is the highest version by [compareVersions],
     * not the first in the list. Null when nothing in it carries a version.
     */
    internal fun newestOf(versions: List<String?>): String? =
        versions.filterNotNull().maxWithOrNull(::compareVersions)

    /**
     * Which way a channel switch moves this phone's version.
     *
     * [Rollback] is not a failure — a beta line folded back into stable is a
     * legitimate reason to move to it — it is only the case that needs saying
     * out loud, because what the newer build added may not be there after.
     */
    enum class ChannelChange { Update, Rollback }

    /**
     * Whether going from [current] to [target] is an update or a rollback.
     *
     * The two versions decide it and nothing else: switching to beta can move
     * the phone backwards (the beta line ended below stable) and switching to
     * stable can move it forwards (stable has just overtaken the running beta).
     * Reading the direction off the switch itself would therefore state the
     * opposite of what the install does.
     */
    internal fun channelChange(current: String, target: String): ChannelChange =
        if (compareVersions(target, current) < 0) ChannelChange.Rollback else ChannelChange.Update

    /** "v1.9.4"/"1.9.4" as numbers, or null when the string is not dotted. */
    private fun dottedParts(version: String): List<Int>? {
        val body = version.removePrefix("v").removePrefix("V")
        if (!body.contains('.')) return null
        val numbers = body.substringBefore('b').split('.')
        val parsed = numbers.map { it.toIntOrNull() ?: return null }
        return parsed.ifEmpty { null }
    }

    internal fun versionOrdinal(name: String): Int {
        val trimmed = name.trim()
        val revision = Regex("""v(\d+)(?:b(\d+))?""", RegexOption.IGNORE_CASE)
            .findAll(trimmed)
            .lastOrNull()
        if (revision != null) {
            val number = revision.groupValues[1].toIntOrNull() ?: 0
            val beta = revision.groupValues[2].toIntOrNull() ?: 0
            return number * BETA_UNIT + beta
        }
        val beta = Regex("""beta(\d+)""", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return beta ?: 0
    }

    /** Room for a beta number inside one revision. */
    private const val BETA_UNIT = 10_000

    /**
     * The versionCode a release states in its notes, when it states one.
     *
     * Published as `versionCode: 80` in the body: readable by whoever opens
     * the release page, and the one number the updater can compare without
     * trusting a tag naming scheme.
     */
    private fun versionCodeIn(body: String): Int? =
        Regex("""versionCode\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Wipes any APK left over from a previous run. Called once at cold start
     * so a downloaded update is only ever "Install Now" for the session that
     * downloaded it — the next launch starts clean rather than trying to work
     * out whether a leftover file is still good.
     */
    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
    }

    /**
     * Streams the current update's APK into the app cache, reporting progress
     * through [download]. A finished file survives a cancelled dialog: until
     * the state is reset, "Install Now" comes straight back without a second
     * download.
     */
    suspend fun downloadApk(context: Context): Unit = withContext(Dispatchers.IO) {
        val info = _available.value ?: return@withContext
        val url = info.apkUrl ?: return@withContext
        downloadCancelled = false
        _download.value = DownloadState.Downloading(0f)

        runCatching {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            // Drop anything left over from an earlier attempt.
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "bitchord-${info.version}.apk")

            val request = Request.Builder().url(url).build()
            // The APK is a couple of hundred megabytes, and this runs on phones
            // that leave Wi-Fi half way through it: the shared client's 30 s read
            // timeout is sized for API calls, not for a stream that pauses while
            // the phone hands over between towers.
            val client = Http.client.newBuilder()
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
                // GitHub states the size twice — in the release JSON and on the
                // response itself — and either is better than trusting a stream
                // that ends early.
                val expected = info.apkBytes ?: body.contentLength().takeIf { it > 0 }
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (downloadCancelled) {
                                _download.value = DownloadState.Idle
                                return@withContext
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            total?.let {
                                _download.value =
                                    DownloadState.Downloading((readTotal.toFloat() / it).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                // A dropped connection ends the stream politely: the loop above
                // reads -1 and the file is simply short. Without this check that
                // short file was reported as Ready and the installer called it
                // corrupted.
                if (expected != null && target.length() != expected) {
                    error("Download incomplete: ${target.length()} of $expected bytes")
                }
                verifyApk(context, target, info.versionCode)
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            File(context.cacheDir, CACHE_SUBDIR).listFiles()?.forEach { it.delete() }
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
        }
    }

    /**
     * Refuses a file the system cannot read as this app's own next version.
     *
     * Belt to the size check's braces: a captive-portal HTML page, a redirect
     * body or a half-written file all land here instead of in the installer.
     */
    private fun verifyApk(context: Context, file: File, expectedCode: Int?) {
        check(file.length() > 0) { "Downloaded file is empty" }
        val info = context.packageManager
            .getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("Downloaded file is not an APK")
        check(info.packageName == context.packageName) {
            "Downloaded APK is for ${info.packageName}, not ${context.packageName}"
        }
        if (expectedCode != null && info.longVersionCode < expectedCode) {
            error("Downloaded APK is version ${info.longVersionCode}, wanted $expectedCode")
        }
    }

    /** Stops an in-flight download; the next read loop sees this and bails. */
    fun cancelDownload() {
        downloadCancelled = true
    }

    /** Back to square one after a failure, so the dialog offers Download again. */
    fun resetDownload() {
        _download.value = DownloadState.Idle
    }

    /**
     * Hands a downloaded APK to the system installer.
     *
     * Sideloaded apps need the user's blessing per app ("install unknown apps");
     * without it the installer intent silently does nothing on most ROMs, so
     * the user is sent to that one switch first and taps Install again after.
     * Some TV builds have no such settings screen; there the installer is
     * launched anyway and shows its own "not allowed" prompt with a way in.
     */
    fun installApk(context: Context, file: File): Boolean {
        if (_available.value == null || !file.exists()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val opened = runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return true
        }
        // Wrapped: a ROM without an installer activity answers
        // ActivityNotFoundException, and a crash on the Install button is a
        // worse outcome than nothing happening at all.
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                    )
                    // Some installers read the URI from the clip data rather than
                    // the intent data, and refuse the grant without it.
                    .also { intent -> intent.clipData = android.content.ClipData.newRawUri("apk", uri) }
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true),
            )
            true
        }.getOrDefault(false)
    }

}
