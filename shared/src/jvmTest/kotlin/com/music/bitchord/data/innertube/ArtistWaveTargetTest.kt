package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArtistWaveTargetTest {
    @Test
    fun artistMixRetainsTheExactWatchTarget() {
        val response = Json.parseToJsonElement(
            """{
              "header":{"musicImmersiveHeaderRenderer":{
                "title":{"runs":[{"text":"Artist"}]},
                "startRadioButton":{"buttonRenderer":{"navigationEndpoint":{"watchEndpoint":{
                  "playlistId":"RDEMartistmix","videoId":"abcdefghijk","params":"wAEB%3D"
                }}}}
              }}
            }""",
        ).jsonObject

        val target = assertNotNull(InnertubeParser.parseArtistPage(response).radio)
        assertEquals("RDEMartistmix", target.playlistId)
        assertEquals("abcdefghijk", target.videoId)
        assertEquals("wAEB=", target.params)
    }
}
