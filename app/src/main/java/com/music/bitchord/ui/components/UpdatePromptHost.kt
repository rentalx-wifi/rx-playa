package com.music.bitchord.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    update?.let { info ->
        UpdateAvailableDialog(
            version = info.version,
            notes = info.notes,
            hazeState = hazeState,
            onDismiss = { AppUpdateChecker.dismiss() },
            onDownload = { scope.launch { AppUpdateChecker.downloadApk(context) } },
            onCancelDownload = { AppUpdateChecker.cancelDownload() },
            onInstall = {
                val ready = AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready
                if (ready != null) AppUpdateChecker.installApk(context, ready.file)
            },
            onOpenReleasePage = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                AppUpdateChecker.dismiss()
            },
            modifier = modifier,
        )
    }
}
