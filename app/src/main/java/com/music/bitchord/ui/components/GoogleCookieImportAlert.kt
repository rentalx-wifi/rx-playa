package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.auth.AuthStore
import dev.chrisbanes.haze.HazeState

@Composable
fun GoogleCookieImportAlert(
    hazeState: HazeState,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Google's own page, for the accounts it still lets through an embedded browser. */
    onWebSignIn: () -> Unit = {},
    /** Shown above the instructions when the browser sign-in was refused. */
    notice: String? = null,
) {
    // Keep the session in memory only, never in saved state or screenshots/recents.
    var cookie by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var helpShown by remember { mutableStateOf(false) }
    fun dismiss() {
        cookie = ""
        onDismiss()
    }
    fun importCookie() {
        val normalized = AuthStore.normalizeGoogleCookieHeader(cookie)
        if (normalized == null) {
            invalid = true
            return
        }
        cookie = ""
        onImport(normalized)
    }
    Dialog(
        onDismissRequest = ::dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
    ) {
        Box(Modifier.fillMaxSize().imePadding()) {
            AlertScaffold(hazeState, ::dismiss, frosted = false) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.google_cookie_import_title), style = MaterialTheme.typography.titleMedium)
                    notice?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(R.string.google_cookie_import_description),
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Where the header comes from, step by step: the dialog asked
                    // for a "Cookie" most people have never had to copy, and the
                    // answer lived in chat and nowhere else (issue #32).
                    TextButton(
                        onClick = { helpShown = !helpShown },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(R.string.google_cookie_help_title),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Icon(
                            imageVector = if (helpShown) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp),
                        )
                    }
                    if (helpShown) {
                        // A long seven-step text in an alert that has no scroll
                        // of its own would run off the screen: bound it and let
                        // it scroll.
                        Text(
                            stringResource(R.string.google_cookie_help_body),
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PillTextField(
                        value = cookie,
                        onValueChange = {
                            invalid = it.length > 65_536
                            cookie = if (invalid) "" else it
                        },
                        placeholder = stringResource(R.string.google_cookie_import_hint),
                        isPassword = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { importCookie() }),
                    )
                    if (invalid) {
                        Text(
                            stringResource(R.string.google_cookie_import_invalid),
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                AlertRule()
                AlertAction(stringResource(R.string.google_cookie_import_button), true, ::importCookie, enabled = cookie.isNotBlank())
                AlertRule()
                AlertAction(stringResource(R.string.google_web_signin_action), false, onWebSignIn)
                AlertRule()
                AlertAction(stringResource(R.string.cancel), false, ::dismiss)
            }
        }
    }
}
