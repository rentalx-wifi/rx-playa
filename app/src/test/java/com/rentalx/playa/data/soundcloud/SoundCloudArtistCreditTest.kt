package com.rentalx.playa.data.soundcloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The artist on an imported SoundCloud track is the performer, not the account
 * that uploaded it (issue #51).
 */
class SoundCloudArtistCreditTest {
    private fun artistOf(json: String): String? =
        soundCloudArtistOf(Json.parseToJsonElement(json).jsonObject, "https://soundcloud.com/lsc0x/hekata-go")

    @Test
    fun `publisher metadata wins over the uploader`() {
        assertEquals(
            "Hekata Station",
            artistOf("""{"title":"Go","user":{"username":"Lsc0x"},"publisher_metadata":{"artist":"Hekata Station"}}"""),
        )
    }

    @Test
    fun `without metadata the uploader is the credit`() {
        assertEquals("Lsc0x", artistOf("""{"title":"Go","user":{"username":"Lsc0x"}}"""))
    }

    @Test
    fun `blank metadata does not win`() {
        assertEquals("Lsc0x", artistOf("""{"title":"Go","user":{"username":"Lsc0x"},"publisher_metadata":{"artist":"  "}}"""))
    }

    @Test
    fun `permalink is the last resort`() {
        assertEquals("lsc0x", artistOf("""{"title":"Go"}"""))
    }
}
