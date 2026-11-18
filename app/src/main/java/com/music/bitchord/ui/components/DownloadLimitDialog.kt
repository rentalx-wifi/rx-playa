package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R

/**
 * "Download all", with the amount left to the listener.
 *
 * A release can be hundreds of rows and a phone has the room for a fraction of
 * them, so the honest tap is not "all of it" but "how much". A choice of null
 * means the whole release, which is the old behaviour and still the top button.
 */
data class DownloadLimitRequest(
    val title: String,
    val total: Int,
    val onChoose: (Int?) -> Unit,
)

@Composable
fun DownloadLimitDialog(request: DownloadLimitRequest, onDismiss: () -> Unit) {
    val steps = listOf(10, 25, 50).filter { it < request.total }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title.ifBlank { stringResource(R.string.download) }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.download_limit_message, request.total),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    steps.forEach { step ->
                        OutlinedButton(
                            onClick = {
                                request.onChoose(step)
                                onDismiss()
                            },
                        ) {
                            Text(stringResource(R.string.download_limit_count, step))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    request.onChoose(null)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.download_limit_all, request.total))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
