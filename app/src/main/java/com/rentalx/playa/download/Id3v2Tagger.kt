package com.rentalx.playa.download

import com.rentalx.playa.data.lyrics.WORD_LYRICS_FIELD
import java.io.ByteArrayOutputStream

/**
 * Writes an ID3v2.3 tag onto an MP3 download.
 *
 * v2.3 rather than the newer v2.4 because it is the one every car stereo and
 * every file manager reads; its cost is that UTF-8 is not a legal encoding for
 * its text frames, so every string goes out as UTF-16 with a byte-order mark,
 * which is what the standard asks for and what the readers expect to see.
 *
 * The tag is built in front of the audio, and an ID3v2 tag the source already
 * carried is dropped first: two tags in one file means every reader picks the
 * first, and the first would be the other one's.
 *
 * Same contract as the other taggers — the original array comes back untouched
 * when there is nothing safe to write, so [MediaTagger] can tell "nothing to do"
 * from "these bytes happen to be equal".
 */
internal object Id3v2Tagger {

    private const val HEADER_BYTES = 10
    private const val ENCODING_UTF16: Byte = 1

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        /** The A2 form, under the name the other taggers use — see [WORD_LYRICS_FIELD]. */
        wordLyrics: String? = null,
    ): ByteArray = runCatching {
        val frames = ByteArrayOutputStream()
        textFrame(frames, "TIT2", title)
        textFrame(frames, "TPE1", artist)
        album?.takeIf { it.isNotBlank() }?.let { textFrame(frames, "TALB", it) }
        // The encoder field, which is the one place a player says where the file
        // came from. Left out for anything else that would only repeat it.
        textFrame(frames, "TSSE", "RX Playa")
        lyrics?.takeIf { it.isNotBlank() }?.let { unsynchronisedLyrics(frames, it) }
        if (!wordLyrics.isNullOrBlank()) userTextFrame(frames, WORD_LYRICS_FIELD, wordLyrics)
        cover?.takeIf { it.isNotEmpty() }?.let { picture(it, coverMime, frames) }

        val body = frames.toByteArray()
        if (body.isEmpty()) return@runCatching bytes

        val start = audioStart(bytes)
        val out = ByteArray(HEADER_BYTES + body.size + (bytes.size - start))
        // "ID3", v2.3.0, no flags, then the size as four syncsafe bytes.
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = 3
        out[4] = 0
        out[5] = 0
        out[6] = ((body.size shr 21) and 0x7F).toByte()
        out[7] = ((body.size shr 14) and 0x7F).toByte()
        out[8] = ((body.size shr 7) and 0x7F).toByte()
        out[9] = (body.size and 0x7F).toByte()
        body.copyInto(out, HEADER_BYTES)
        bytes.copyInto(out, HEADER_BYTES + body.size, start, bytes.size)
        out
    }.getOrDefault(bytes)

    /** Where the audio starts: past an ID3v2 tag the file already had, if any. */
    private fun audioStart(bytes: ByteArray): Int {
        if (bytes.size < HEADER_BYTES) return 0
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return 0
        // A set high bit is not a size, it is a file that only looks like it
        // starts with a tag — and a size this walk trusted would cut audio.
        if ((6..9).any { bytes[it].toInt() and 0x80 != 0 }) return 0
        val size = ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
        // The footer, when the tag declares one, is part of the tag.
        val footer = if (bytes[5].toInt() and 0x10 != 0) 10 else 0
        val end = HEADER_BYTES + size + footer
        return if (end <= bytes.size) end else 0
    }

    private fun textFrame(out: ByteArrayOutputStream, id: String, value: String) {
        if (value.isBlank()) return
        frame(out, id, byteArrayOf(ENCODING_UTF16) + utf16(value))
    }

    /**
     * `TXXX` with a description of this app's own: the enhanced (A2) lyrics,
     * which no standard frame carries and which other players simply ignore.
     */
    private fun userTextFrame(out: ByteArrayOutputStream, description: String, value: String) {
        frame(out, "TXXX", byteArrayOf(ENCODING_UTF16) + utf16(description) + utf16(value))
    }

    private fun unsynchronisedLyrics(out: ByteArrayOutputStream, lyrics: String) {
        // Encoding, a three-letter language, the content descriptor, the words.
        frame(
            out,
            "USLT",
            byteArrayOf(ENCODING_UTF16) + "und".toByteArray(Charsets.US_ASCII) + utf16("") + utf16(lyrics),
        )
    }

    private fun picture(bytes: ByteArray, mime: String, out: ByteArrayOutputStream) {
        val type = if (mime.contains("png", ignoreCase = true)) "image/png" else "image/jpeg"
        // The description's encoding only, then the MIME as ASCII, the picture
        // type (3 = front cover), an empty description, and the image itself.
        frame(
            out,
            "APIC",
            byteArrayOf(0) +
                type.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0) +
                byteArrayOf(3) +
                byteArrayOf(0) +
                bytes,
        )
    }

    /** Frame header plus payload; v2.3 sizes are plain big-endian, not syncsafe. */
    private fun frame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        val size = payload.size
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(0) // status flags
        out.write(0) // format flags
        out.write(payload)
    }

    /** UTF-16 with a little-endian BOM, which is what v2.3 text frames carry. */
    private fun utf16(value: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
}
