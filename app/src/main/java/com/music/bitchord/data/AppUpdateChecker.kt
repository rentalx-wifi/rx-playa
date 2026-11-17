package com.music.bitchord.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    /** The repository this fork publishes to; nothing upstream is ever fetched. */
    private const val RELEASES_API =
        "https://api.github.com/repos/rentalx-wifi/rx-playa/releases/latest"

    /**
     * Asks GitHub for the latest release of this fork and offers it when its
     * version is newer than the one running.
     *
     * Nothing here is fatal: a phone with no network, a rate-limited API or a
     * repository that has no release yet simply leaves no update on offer.
     */
    suspend fun check(context: Context? = null, force: Boolean = false) {
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
                    .url(RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else response.body?.string()
                }
            }
        }.getOrNull() ?: return

        val release = runCatching { JSONObject(json) }.getOrNull() ?: return
        // Only a successful answer counts as a check. Recording the attempt made
        // a launch without a connection look like an up-to-date phone, and the
        // next attempt was an hour away.
        prefs?.edit()?.putLong(KEY_LAST_CHECK, now)?.apply()
        val tag = release.optString("tag_name").ifBlank { return }
        if (tag == dismissedTag) return
        if (versionOrdinal(tag) <= versionOrdinal(BuildConfig.VERSION_NAME)) return

        val assets = release.optJSONArray("assets")
        // More than one APK can hang off a release, and installing the wrong one
        // is an INSTALL_FAILED_NO_MATCHING_ABIS at the end of the download. The
        // release names its files by architecture ("...-arm64.apk",
        // "...-arm32.apk"), not by the ABI string the platform reports
        // ("arm64-v8a"), so the match is made on the short names. A universal
        // build is the next best answer, and only then anything at all.
        var byAbi: String? = null
        var universal: String? = null
        var firstApk: String? = null
        for (index in 0 until (assets?.length() ?: 0)) {
            val asset = assets?.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url").ifBlank { null } ?: continue
            if (firstApk == null) firstApk = url
            if (universal == null && name.contains("universal", ignoreCase = true)) universal = url
            if (byAbi == null && abiTokens().any { name.contains(it, ignoreCase = true) }) byAbi = url
        }
        val apkUrl = byAbi ?: universal ?: firstApk
        _available.value = UpdateInfo(
            version = tag,
            releaseUrl = release.optString("html_url"),
            apkUrl = apkUrl,
            notes = release.optString("body").takeIf { it.isNotBlank() },
        )
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
     * The number at the end of a version name: `1.9-rx-playa-v35` and `v35` both
     * answer 35. Releases are tagged `vNN`, and only that number decides whether
     * an update is newer, so a rename of the display name cannot break it.
     */
    private fun versionOrdinal(name: String): Int =
        Regex("""v?(\d+)\s*$""").find(name.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

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
            Http.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                val body = response.body ?: error("Empty download body")
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
            }
            _download.value = DownloadState.Ready(target)
        }.onFailure { error ->
            _download.value = if (downloadCancelled) {
                DownloadState.Idle
            } else {
                DownloadState.Failed(error.message ?: "Download failed")
            }
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
