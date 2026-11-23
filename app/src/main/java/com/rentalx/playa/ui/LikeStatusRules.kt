package com.rentalx.playa.ui

import com.rentalx.playa.data.model.LikeStatus
import com.rentalx.playa.data.model.Song

/**
 * Whether this row is a file on the device rather than a track in a catalogue.
 *
 * The distinction is carried by the id, not by [Song.localUri]: a *downloaded*
 * track has both — a real video id and the file it was written to — and is
 * still the same track the account has an opinion about. A folder file has only
 * the uri it was scanned from, which is also its id.
 *
 * Getting this wrong is what made the heart in Favourites stay dark on a
 * downloaded track that was liked: the row was taken for a device file, so its
 * like was looked for in the device's own Liked playlist, where it had never
 * been and never would be.
 */
internal val Song.isDeviceFile: Boolean
    get() = localUri != null &&
        (videoId.startsWith("content://") || videoId.startsWith("file://"))

/**
 * The heart to draw for a row.
 *
 * A device file is liked or not by the device's Liked playlist alone — it has no
 * catalogue identity to rate. Everything else answers with the account's own
 * rating, and a downloaded copy additionally counts as liked when the listener
 * liked it as a file.
 */
internal fun likeStatusFor(
    isDeviceFile: Boolean,
    localUri: String?,
    durable: LikeStatus?,
    offlineLiked: Set<String>,
): LikeStatus {
    val likedAsFile = localUri != null && localUri in offlineLiked
    return when {
        // A dislike is the listener's last word on the recording, whichever
        // surface it was given on.
        durable == LikeStatus.DISLIKE -> LikeStatus.DISLIKE
        isDeviceFile -> if (likedAsFile) LikeStatus.LIKE else LikeStatus.INDIFFERENT
        durable == LikeStatus.LIKE -> LikeStatus.LIKE
        likedAsFile -> LikeStatus.LIKE
        else -> durable ?: LikeStatus.INDIFFERENT
    }
}

/**
 * Where a tap on that heart should be written.
 *
 * True only for a device file: that one goes into the offline Liked playlist.
 * A downloaded track is rated through the account, exactly as the same track is
 * rated from search or from the player, so the two surfaces can never disagree
 * about it again.
 */
internal fun likeBelongsToOfflinePlaylist(song: Song): Boolean = song.isDeviceFile
