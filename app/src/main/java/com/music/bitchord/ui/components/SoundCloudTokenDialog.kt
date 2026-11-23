package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.soundcloud.SoundCloudOAuth
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Connect SoundCloud with an OAuth token from another client.
 *
 * Shown where the official flow cannot run — it needs the broker's client
 * secret, which is not in the APK. The token is verified before it is kept, so
 * the error here is the real answer from SoundCloud.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SoundCloudTokenDialog(
    hazeState: HazeState,
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (working || token.isBlank()) return
        working = true
        error = null
        scope.launch {
            try {
                SoundCloudOAuth.signInWithToken(token)
                onConnected()
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: context.getString(R.string.failed)
            }
            working = false
        }
    }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.soundcloud_token_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = error ?: stringResource(R.string.soundcloud_token_hint),
                modifier = Modifier.padding(top = 4.dp, bottom = if (error != null) 4.dp else 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            // The reason a session did not work is not an instruction: the sheet
            // used to replace the one line explaining what a token is with the
            // complaint that the old one expired, leaving nothing to act on
            // (issue #48). Both are shown now.
            if (error != null) {
                Text(
                    text = stringResource(R.string.soundcloud_token_hint),
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            PillTextField(
                value = token,
                onValueChange = {
                    token = it
                    error = null
                },
                placeholder = stringResource(R.string.google_cookie_import_hint),
                enabled = !working,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.sign_in),
            emphasised = true,
            onClick = ::connect,
            enabled = token.isNotBlank() && !working,
        )
        AlertRule()
        AlertAction(
            label = stringResource(R.string.cancel),
            emphasised = false,
            onClick = onDismiss,
            enabled = !working,
        )
    }
}
