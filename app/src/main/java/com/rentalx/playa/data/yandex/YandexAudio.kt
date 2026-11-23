package com.rentalx.playa.data.yandex

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One rendition of a Yandex track, ready to open. */
data class YandexStream(
    val url: String,
    val codec: String,
    val bitrateKbps: Int?,
    /**
     * Hex AES-256 key for the whole file, or null when the file arrives clear.
     *
     * The `encraw` transport returns the file encrypted end to end with
     * AES-256-CTR under this key, a twelve-byte zero nonce and the block counter
     * starting at zero — so a reader can decrypt from any offset by working out
     * which block that offset lands in.
     */
    val keyHex: String?,
)

/**
 * What the Android client has to compute to be allowed to download a track.
 *
 * `get-file-info` is signed: the API answers only a request carrying an
 * HMAC-SHA256 over its own parameters under the Android app's key. Without the
 * signature the endpoint answers 403 no matter how valid the OAuth token is,
 * which is what makes this worth its own tested piece rather than three lines
 * inside a request builder.
 */
object YandexAudio {

    /** The key the Android client signs with; the API refuses any other. */
    private const val SIGN_KEY = "p93jhgh689SBReK6ghtw62"

    /** Transports this app can play: an encrypted raw stream, decrypted here. */
    private const val TRANSPORT = "encraw"

    /**
     * The plain transport, asked for by the source when it has no decrypting
     * reader to put in front of the audio.
     *
     * Not every account is offered one — a refusal is a miss, not an error, and
     * the tracks fall through to the next source.
     */
    const val TRANSPORT_DIRECT = "direct"

    /** Codecs asked for, best first. */
    val CODECS = listOf("flac", "flac-mp4", "mp3", "aac", "he-aac", "aac-mp4", "he-aac-mp4")

    /** The API's own words for "this tier". */
    const val QUALITY_LOSSLESS = "lossless"
    const val QUALITY_NORMAL = "nq"
    const val QUALITY_LOW = "lq"

    /**
     * The `sign` parameter for [timestampSec] and [trackId].
     *
     * The signed message is the request's values in their fixed order with the
     * commas taken out — see [signedMessage] — and the base64 is sent without
     * its padding, which is what the client library does and what the server
     * accepts.
     */
    fun sign(
        timestampSec: Long,
        trackId: String,
        quality: String,
        codecs: List<String> = CODECS,
        transport: String = TRANSPORT,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(SIGN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        }
        val digest = mac.doFinal(
            signedMessage(timestampSec, trackId, quality, codecs, transport).toByteArray(Charsets.UTF_8),
        )
        return Base64.getEncoder().encodeToString(digest).trimEnd('=')
    }

    /** The exact string that gets signed, kept apart so a test can pin it. */
    internal fun signedMessage(
        timestampSec: Long,
        trackId: String,
        quality: String,
        codecs: List<String> = CODECS,
        transport: String = TRANSPORT,
    ): String = buildString {
        append(timestampSec)
        append(trackId)
        append(quality)
        append(codecs.joinToString(","))
        append(transport)
    }.replace(",", "")

    /** Whether an HTTP status from the Yandex API means "this network is not allowed". */
    fun isNetworkRefusal(code: Int): Boolean = code == 403 || code == 451

    /**
     * Which codec a [StreamRequest]-shaped ask should try for.
     *
     * The API answers with whatever it has at the requested tier; this only
     * decides which tier to ask for, so a metered connection does not spend a
     * lossless download on a track the listener will skip.
     */
    fun qualityFor(lossless: Boolean, cappedKbps: Int?): String = when {
        lossless -> QUALITY_LOSSLESS
        cappedKbps != null && cappedKbps <= 128 -> QUALITY_LOW
        else -> QUALITY_NORMAL
    }
}
