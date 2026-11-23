package com.rentalx.playa.data.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpotifyPersonalizationTest {
    @Test fun rawPagePositionsNotFilteredRowsAdvancePagination() = runBlocking {
        val offsets = mutableListOf<Int>()
        val rows = collectSpotifyPages<Int> { offset ->
            offsets += offset
            when (offset) {
                0 -> Triple((1..99).toList(), 103, 100)
                100 -> Triple(listOf(100, 101, 102), 103, 3)
                else -> error("Unexpected page")
            }
        }
        assertEquals(listOf(0, 100), offsets)
        assertEquals((1..102).toList(), rows)
    }

    @Test fun emptyPrematurePageIsNotSuccessfulCompletion(): Unit = runBlocking {
        assertFailsWith<IOException> {
            collectSpotifyPages<Int> { offset ->
                if (offset == 0) Triple(listOf(1), 2, 1) else Triple(emptyList(), 2, 0)
            }
        }
    }

    @Test fun networkFailureDoesNotReturnPartialLikes(): Unit = runBlocking {
        assertFailsWith<IOException> {
            collectSpotifyPages<Int> { offset ->
                if (offset == 0) Triple(listOf(1), 2, 1) else throw IOException("offline")
            }
        }
    }

    @Test fun cancellationIsNotReportedAsEmptyOrPartialSuccess() = runBlocking {
        var requests = 0
        assertFailsWith<CancellationException> {
            collectSpotifyPages<Int> { offset ->
                requests++
                if (offset == 0) Triple(listOf(1), 3, 1) else throw CancellationException("cancelled")
            }
        }
        assertEquals(2, requests)
    }

    @Test fun realEmptyLibraryIsSuccessful() = runBlocking {
        assertEquals(emptyList(), collectSpotifyPages<Int> { Triple(emptyList(), 0, 0) })
    }

    @Test fun paginationCanContinueAfterAnEmbedPrefixWithoutRestartingAtZero() = runBlocking {
        val offsets = mutableListOf<Int>()
        val rows = collectSpotifyPages<Int>(startOffset = 100) { offset ->
            offsets += offset
            when (offset) {
                100 -> Triple(listOf(101, 102), 103, 2)
                102 -> Triple(listOf(103), 103, 1)
                else -> error("Unexpected offset $offset")
            }
        }
        assertEquals(listOf(100, 102), offsets)
        assertEquals(listOf(101, 102, 103), rows)
    }

    @Test fun likesKeepActualTimestampAndSeparateArtistCredits() {
        val root = Json.parseToJsonElement("""{"data":{"me":{"library":{"tracks":{"totalCount":1,"items":[
          {"addedAt":{"isoString":"2026-01-02T10:00:00Z"},"track":{"_uri":"spotify:track:t1","data":{
            "name":"Song","artists":{"items":[{"profile":{"name":"First"}},{"profile":{"name":"Second"}}]},
            "trackDuration":{"totalMilliseconds":180000},"albumOfTrack":{"name":"Album"}
          }}}
        ]}}}}}""").jsonObject
        val track = parseLikedPage(root).first.single()
        assertEquals(listOf("First", "Second"), track.artists)
        assertEquals(Instant.parse("2026-01-02T10:00:00Z").toEpochMilli(), track.likedAtMs)
    }

    @Test fun unknownLikeTimestampStaysUnknown() {
        val root = Json.parseToJsonElement("""{"data":{"me":{"library":{"tracks":{"totalCount":1,"items":[
          {"track":{"_uri":"spotify:track:t1","data":{"name":"Song","artists":{"items":[]}}}}
        ]}}}}}""").jsonObject
        assertNull(parseLikedPage(root).first.single().likedAtMs)
    }

    @Test fun standardApiPlaylistFallbackKeepsOwnerAndLargestArtwork() {
        val root = Json.parseToJsonElement("""{"total":1,"items":[{
          "id":"playlist","name":"My mix","owner":{"display_name":"Listener"},
          "images":[{"url":"small","width":64},{"url":"large","width":640}]
        }]}""").jsonObject
        val playlist = parseRestPlaylistPage(root).first.single()
        assertEquals("playlist", playlist.id)
        assertEquals("Listener", playlist.owner)
        assertEquals("large", playlist.imageUrl)
    }

    @Test fun standardApiLikedFallbackKeepsTimestampArtistsAndMetadata() {
        val root = Json.parseToJsonElement("""{"total":1,"items":[{
          "added_at":"2026-01-02T10:00:00Z","track":{"id":"track","name":"Song",
          "duration_ms":180000,"artists":[{"name":"First"},{"name":"Second"}],
          "album":{"name":"Album","images":[{"url":"cover","width":300}]}}
        }]}""").jsonObject
        val track = parseRestTrackPage(root, liked = true).first.single()
        assertEquals(listOf("First", "Second"), track.artists)
        assertEquals("Album", track.album)
        assertEquals("cover", track.imageUrl)
        assertEquals(Instant.parse("2026-01-02T10:00:00Z").toEpochMilli(), track.likedAtMs)
    }
}
