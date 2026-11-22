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
     * The two channels.
     *
     * Stable reads GitHub's `latest`, which by GitHub's own rule never points
     * at a pre-release. Beta reads the release list instead, newest first, so
     * a test build is visible while it is still a pre-release and the stable
     * one replaces it the moment it is published.
     */
    private const val RELEASES_LATEST =
        "https://api.github.com/repos/rentalx-wifi/rx-playa/releases/latest"

    private const val RELEASES_ALL =
        "https://api.github.com/repos/rentalx-wifi/rx-playa/releases?per_page=20"

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
        val json = runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(if (beta) RELEASES_ALL else RELEASES_LATEST)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            }
        }.getOrNull() ?: return

        val release = runCatching {
            if (beta) {
                val list = JSONArray(json)
                (0 until list.length())
                    .mapNotNull { list.optJSONObject(it) }
                    .firstOrNull { !it.optBoolean("draft", false) }
            } else {
                JSONObject(json)
            }
        }.getOrNull() ?: return
        // Only a successful answer counts as a check. Recording the attempt made
        // a launch without a connection look like an up-to-date phone, and the
        // next attempt was an hour away.
        prefs?.edit()?.putLong(KEY_LAST_CHECK, now)?.apply()
        val tag = release.optString("tag_name").ifBlank { return }
        if (tag == dismissedTag) return
        // A release may state the versionCode it was built with, which is the
        // only number that decides whether Android will install it. Two betas
        // of one feature share an ordinal (v46b1, v46b2), so the tag alone is
        // not enough to tell them apart.
        val publishedCode = versionCodeIn(release.optString("body"))
        if (publishedCode != null) {
            if (publishedCode <= BuildConfig.VERSION_CODE) return
        } else if (versionOrdinal(tag) <= versionOrdinal(BuildConfig.VERSION_NAME)) {
            return
        }

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
            version = tag,
            releaseUrl = release.optString("html_url"),
            apkUrl = apkUrl,
            notes = release.optString("body").takeIf { it.isNotBlank() },
            isBeta = release.optBoolean("prerelease", false),
            apkBytes = apkBytes,
            versionCode = publishedCode,
        )
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
        val tag = _available.value?.version
        _available.value = null
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
