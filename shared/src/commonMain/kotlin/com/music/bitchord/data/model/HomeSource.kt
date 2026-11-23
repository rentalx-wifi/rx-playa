package com.music.bitchord.data.model

import kotlinx.serialization.Serializable

/**
 * What this source is called wherever a bare string is all there is room for:
 * the player's quality badge, a log line, the source name in nerd stats.
 */
const val FLASH_MUSIC_NAME = "FlashMusic"

/** Metadata provenance and taste signals, independent of the source that eventually serves audio. */
@Serializable
enum class HomeSource(val label: String, val badge: String) {
    YOUTUBE("YouTube Music", "YT"),
    YANDEX("Yandex Music", "Я"),
    SPOTIFY("Spotify", "Sp"),
    SOUNDCLOUD("SoundCloud", "SC"),
    VK("VK Music", "VK"),
    LOCAL("Local", "L"),
    ADDONS("Addons", "+"),

    /**
     * The listener's own FlashMusic server.
     *
     * Provenance only, like the rest of this enum: it says where the row was
     * found, not that the audio will come from there. FlashMusic is also a
     * played-from source, but a row found in its catalogue is still a row the
     * player may end up resolving elsewhere.
     */
    FLASHMUSIC(FLASH_MUSIC_NAME, "FM"),
}
