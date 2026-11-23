package com.music.bitchord.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.AppUpdateChecker
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * The update prompt, on its own so the app root stays readable.
 *
 * [com.music.bitchord.MainActivity]'s `BitChordApp` is one composable holding the
 * whole phone UI and is already close to the JVM's method-size ceiling; the
 * dialog's four callbacks pushed it over, and the compiler answered with
 * "Method too large". Reading [AppUpdateChecker.available] here instead of
 * passing the value in also means a dismissed update stops drawing anything at
 * all rather than leaving an empty branch in the root.
 */
@Composable
fun UpdatePromptHost(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by AppUpdateChecker.available.collectAsStateWithLifecycle()
    val whatsNew by AppUpdateChecker.whatsNew.collectAsStateWithLifecycle()

    update?.let { info ->
        UpdateAvailableDialog(
            version = info.version,
            notes = info.notes,
            beta = info.isBeta,
            hazeState = hazeState,
            onDismiss = { AppUpdateChecker.dismiss() },
            // A release with no APK attached has nothing to stream: send the
            // listener to the page instead of leaving the button dead.
            onDownload = {
                if (info.apkUrl == null) {
                    openReleasePage(context, info.releaseUrl)
                    AppUpdateChecker.dismiss()
                } else {
                    scope.launch { AppUpdateChecker.downloadApk(context) }
                }
            },
            onCancelDownload = { AppUpdateChecker.cancelDownload() },
            onInstall = {
                val ready = AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready
                // Silently doing nothing here is exactly what a user reports as
                // "the update button does not work": the installer can refuse
                // to open (a ROM without one, a revoked unknown-sources grant),
                // and without this it looks like a dead button.
                val started = ready != null && AppUpdateChecker.installApk(context, ready.file)
                if (started) {
                    // These notes were on screen a moment ago: the "what's new"
                    // card after the install would be the same text a second time.
                    AppUpdateChecker.markNotesShown(info.version)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.something_went_wrong),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onOpenReleasePage = {
                openReleasePage(context, info.releaseUrl)
                AppUpdateChecker.dismiss()
            },
            modifier = modifier,
        )
    }

    // Never at the same time as an offer — one is a build to install, the other
    // the changelog of the build already installed — but the offer wins if a
    // check somehow leaves both, because installing is the more pressing thing.
    if (update == null) {
        whatsNew?.let { notes ->
            WhatsNewDialog(
                version = notes.version,
                notes = notes.notes,
                hazeState = hazeState,
                onDismiss = { AppUpdateChecker.dismissWhatsNew() },
                modifier = modifier,
            )
        }
    }
}

/** Opens a release page in whatever browser the phone has. */
private fun openReleasePage(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
