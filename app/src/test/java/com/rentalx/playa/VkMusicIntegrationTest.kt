package com.rentalx.playa

import com.rentalx.playa.data.vk.isVkSourceTrack
import com.rentalx.playa.data.vk.parseVkMusicSearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkMusicIntegrationTest {
    @Test
    fun `approved VK audio response keeps provider identity and metadata`() {
        val root = Json.parseToJsonElement(
            """
            {
              "response": {
                "count": 1,
                "items": [{
                  "owner_id": -12,
                  "id": 34,
                  "artist": "Artist",
                  "title": "Track",
                  "duration": 185,
                  "album": {"thumb": {"photo_600": "https://sun9.example/cover.jpg"}}
                }]
              }
            }
            """.trimIndent(),
        ).jsonObject
        val song = parseVkMusicSearch(root).single().song()
        assertEquals("vk-audio:-12:34", song.videoId)
        assertEquals("3:05", song.durationText)
        assertEquals("VK Music", song.playbackSource)
        assertTrue(song.isVkSourceTrack)
    }
}
