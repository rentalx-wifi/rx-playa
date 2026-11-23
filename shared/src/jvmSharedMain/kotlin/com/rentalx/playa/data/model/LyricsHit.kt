package com.rentalx.playa.data.model

/**
 * One song matched from words of its lyric rather than from its name.
 *
 * Lives here, in the model package, because the screen that draws it is shared
 * UI and the client that fetches it is the phone's: this is the only shape the
 * two sides can both see. Nothing else about LRCLIB is exposed — no lyrics
 * text, no synced timings — since the point of the row is to name a song the
 * listener half-remembers, and the field does the rest.
 */
data class LyricsHit(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    /** Length in seconds; LRCLIB omits it on some community entries. */
    val durationSeconds: Int,
) {
    /**
     * What a tap on this row searches for: "artist - title", the order every
     * catalogue reads as a song and not as an artist named after the title.
     */
    val searchQuery: String
        get() = listOfNotNull(artistName.takeIf { it.isNotBlank() }, trackName)
            .joinToString(" - ")
}
