package com.rentalx.playa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.rentalx.playa.R
import com.rentalx.playa.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/** Local-only profile editor. It never changes authentication or enables account-only actions. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GuestProfileAlert(
    hazeState: HazeState,
    initialName: String,
    avatarUri: String?,
    existing: Boolean,
    onChooseAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.guest_profile),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
            )
            Spacer(Modifier.height(16.dp))
            if (!avatarUri.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(CircleShape),
                )
            } else {
                Box(
                    Modifier.size(88.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SfIcons.Person,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.choose_avatar),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onChooseAvatar).padding(8.dp),
                )
                if (!avatarUri.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.remove_avatar),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(onClick = onRemoveAvatar).padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PillTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = stringResource(R.string.guest_name),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Text(
                stringResource(R.string.guest_profile_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        AlertRule()
        AlertAction(
            label = stringResource(R.string.save),
            emphasised = true,
            onClick = { onSave(name.trim()) },
            enabled = name.isNotBlank(),
        )
        if (existing) {
            AlertRule()
            AlertAction(
                label = stringResource(R.string.delete_guest_profile),
                emphasised = false,
                destructive = true,
                onClick = onDelete,
            )
        }
        AlertRule()
        AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
    }
}
