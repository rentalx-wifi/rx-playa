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
 * What a pasted SoundCloud token can look like. Everything else — a URL, a
 * cookie, a fragment of JSON — is refused before a request is made.
 */
private val TOKEN_SHAPE = Regex("^[A-Za-z0-9._~-]{16,}$")

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
        if (working) return
        val candidate = token.trim()
        if (candidate.isEmpty()) return
        // A token is one long run of letters, digits and dashes. A colon, a
        // percent sign, quotes or braces mean a URL, a cookie or a piece of a
        // JSON reply was pasted instead — and SoundCloud would answer that with
        // a session error, which is exactly the unhelpful answer this dialog
        // used to give (issue #48). Say what is wrong here, without a request.
        if (!TOKEN_SHAPE.matches(candidate)) {
            error = context.getString(R.string.soundcloud_token_not_a_token)
            return
        }
        working = true
        error = null
        scope.launch {
            try {
                SoundCloudOAuth.signInWithToken(candidate)
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
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            // Where a token comes from, spelled out: the dialog used to name a
            // "SoundCloud client" and leave the rest to imagination (issue #48).
            Text(
                text = stringResource(R.string.soundcloud_token_help),
                modifier = Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PillTextField(
                value = token,
                onValueChange = {
                    token = it
                    error = null
                },
                placeholder = stringResource(R.string.soundcloud_token_placeholder),
                enabled = !working,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            )
            if (error != null) {
                Text(
                    text = error ?: "",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
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
