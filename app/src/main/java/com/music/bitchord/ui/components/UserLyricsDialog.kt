package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.UserLyricsStore
import com.music.bitchord.data.model.Song

/**
 * Write a track's words down by hand.
 *
 * The field takes both shapes people arrive with: a page of lines typed out,
 * and an LRC file pasted from a lyrics site — [UserLyricsStore] works out which
 * it got and the player lights the second kind up line by line. There is no
 * switch for that, deliberately: what the text *is* is right there in it.
 *
 * Seeded with what is already stored, so this is the same dialog for writing
 * words and for correcting them. Saving an emptied field is [UserLyricsStore.clear]
 * — see there for why that is not an entry of nothing.
 *
 * A window [Dialog], not an overlay in the tree, because it opens from the song
 * menu, which can itself be inside the player's own window; and a plain surface
 * because a separate window has no haze to sample.
 */
@Composable
internal fun UserLyricsDialog(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember(song.videoId, song.localUri) {
        mutableStateOf(UserLyricsStore.entry(context, song)?.text.orEmpty())
    }
    // Set only by a paste that found nothing, and cleared by the first edit: a
    // complaint about an empty clipboard is an answer to one press, not a
    // standing verdict on the field.
    var clipboardEmpty by remember { mutableStateOf(false) }

    // Replaces rather than appends. The field is already holding whatever was
    // saved, and the paste this button exists for is a whole file off a lyrics
    // site — inserting one into the middle of another helps nobody.
    fun paste() {
        val pasted = clipboard.getText()?.text.orEmpty()
        clipboardEmpty = pasted.isBlank()
        if (pasted.isNotBlank()) text = pasted
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(alertWidth())
                .heightIn(max = DIALOG_MAX_HEIGHT)
                .clip(RoundedCornerShape(alertCorner()))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp)) {
                Text(
                    text = stringResource(R.string.user_lyrics_dialog_title),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.user_lyrics_dialog_hint),
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                LyricsTextArea(value = text, onValueChange = {
                    text = it
                    clipboardEmpty = false
                })

                val interactionSource = remember { MutableInteractionSource() }
                Text(
                    text = stringResource(R.string.user_lyrics_paste),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = ::paste,
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (clipboardEmpty) {
                    Text(
                        text = stringResource(R.string.user_lyrics_clipboard_empty),
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.End,
                    )
                }
            }
            AlertRule()
            AlertAction(
                label = stringResource(R.string.save),
                emphasised = true,
                onClick = {
                    UserLyricsStore.save(context, song, text)
                    onDismiss()
                },
            )
            AlertRule()
            AlertAction(label = stringResource(R.string.cancel), emphasised = false, onClick = onDismiss)
        }
    }
}

/**
 * The words themselves.
 *
 * Grows with what is in it up to [AREA_MAX_HEIGHT] and then scrolls, rather
 * than reserving a fixed block: a two-line chorus and a whole song are the same
 * dialog, and the card should be the size of the answer it holds.
 */
@Composable
private fun LyricsTextArea(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        minLines = 5,
        maxLines = 14,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AREA_MIN_HEIGHT, max = AREA_MAX_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private val AREA_MIN_HEIGHT = 108.dp
private val AREA_MAX_HEIGHT = 264.dp

/** The card stays clear of the screen's own edges however long the text gets. */
private val DIALOG_MAX_HEIGHT = 560.dp
