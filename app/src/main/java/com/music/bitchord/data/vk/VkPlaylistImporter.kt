package com.music.bitchord.data.vk

import com.music.bitchord.data.importing.BasicExternalPlaylistTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.Reader

/** Reads the user's metadata export, not VK credentials or protected audio. */
object VkPlaylistImporter {
    private val json = Json { ignoreUnknownKeys = true }
    private val trackSeparator = Regex("\\s+[—–-]\\s+")
    private const val MAX_TEXT_CHARS = 4 * 1024 * 1024

    fun readText(reader: Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) return text.toString()
            require(text.length + count <= MAX_TEXT_CHARS) { "The track-list file is too large" }
            text.append(buffer, 0, count)
        }
    }

    fun parse(input: String): List<BasicExternalPlaylistTrack> {
        require(input.length <= MAX_TEXT_CHARS) { "The track list is too large" }
        val text = input.trim().removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "The track list is empty" }
        val tracks = if (text.startsWith('[') || text.startsWith('{')) {
            parseJson(text)
        } else {
            parseText(text)
        }
        require(tracks.isNotEmpty()) { "No track metadata found" }
        return tracks
    }

    private fun parseJson(text: String): List<BasicExternalPlaylistTrack> {
        val root = json.parseToJsonElement(text)
        val rows = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["tracks"] as? JsonArray)
                ?: (root["items"] as? JsonArray)
                ?: ((root["response"] as? JsonObject)?.get("items") as? JsonArray)
                ?: (root["response"] as? JsonArray)
            else -> null
        }
        require(rows != null) { "The JSON must contain a track array" }
        return rows.mapIndexed { index, element ->
            val row = element as? JsonObject
            require(row != null) { "Invalid track at row ${index + 1}" }
            val album = row["album"]
            val duration = (row["duration_ms"] as? JsonPrimitive)?.longOrNull
                ?: (row["duration"] as? JsonPrimitive)?.longOrNull?.let {
                    require(it in 0..Long.MAX_VALUE / 1000) { "Invalid duration at row ${index + 1}" }
                    it * 1000
                }
                ?: 0L
            track(
                artist = row.text("artist").orEmpty(),
                title = row.text("title").orEmpty(),
                rowNumber = index + 1,
                album = (album as? JsonObject)?.text("title") ?: (album as? JsonPrimitive)?.contentOrNull,
                durationMs = duration,
            )
        }
    }

    private fun parseText(text: String): List<BasicExternalPlaylistTrack> {
        val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        val first = lines.firstOrNull() ?: return emptyList()
        if (!first.startsWith('"') && !first.contains('\t') && trackSeparator.containsMatchIn(first)) {
            return lines.mapIndexed { index, line ->
                val split = trackSeparator.find(line)
                require(split != null) { "Use Artist — Title at row ${index + 1}" }
                track(line.substring(0, split.range.first), line.substring(split.range.last + 1), index + 1)
            }
        }
        val delimiter = listOf('\t', ';', ',').firstOrNull(first::contains)
        require(delimiter != null) { "Use Artist — Title, CSV/TSV, or a VK JSON export" }
        val rows = csvRows(text, delimiter).filterNot { row -> row.all(String::isBlank) }
        val headers = rows.first().map { it.trim().lowercase() }
        val artistColumn = headers.indexOfFirst { it in setOf("artist", "artists", "исполнитель") }
        val titleColumn = headers.indexOfFirst { it in setOf("title", "track", "название", "трек") }
        val hasHeader = artistColumn >= 0 && titleColumn >= 0
        val artistIndex = if (hasHeader) artistColumn else 0
        val titleIndex = if (hasHeader) titleColumn else 1
        return rows.drop(if (hasHeader) 1 else 0).mapIndexed { index, row ->
            track(row.getOrNull(artistIndex).orEmpty(), row.getOrNull(titleIndex).orEmpty(), index + 1)
        }
    }

    private fun csvRows(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row.add(field.toString())
            field.setLength(0)
        }
        while (index < text.length) {
            val char = text[index++]
            when {
                char == '"' && quoted && index < text.length && text[index] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index < text.length && text[index] == '\n') index++
                    finishField()
                    rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(char)
            }
        }
        require(!quoted) { "Unclosed CSV quote" }
        finishField()
        rows.add(row)
        return rows
    }

    private fun track(
        artist: String,
        title: String,
        rowNumber: Int,
        album: String? = null,
        durationMs: Long = 0L,
    ): BasicExternalPlaylistTrack {
        require(artist.isNotBlank() && title.isNotBlank() && durationMs >= 0L) {
            "Missing artist/title or invalid duration at row $rowNumber"
        }
        return BasicExternalPlaylistTrack(title.trim(), artist.trim(), album, durationMs, null)
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
}
