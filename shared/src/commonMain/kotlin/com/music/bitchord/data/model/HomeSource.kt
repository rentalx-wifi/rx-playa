package com.music.bitchord.data.model

import kotlinx.serialization.Serializable

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
}
