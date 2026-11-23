package com.rentalx.playa.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * Encrypted DNS for music hosts commonly poisoned by access providers.
 *
 * This changes name resolution only. It is deliberately not presented as a
 * VPN: a destination-IP or DPI block still fails normally and the caller's
 * timeout/error path keeps the rest of Playa usable.
 */
object ResilientServiceDns : Dns {
    private data class Cached(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!isResilientServiceHost(hostname)) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { now < it.expiresAtMs }?.let { return it.addresses }

        val encrypted = runCatching { queryCloudflare(hostname) }.getOrDefault(emptyList())
        val addresses = encrypted.ifEmpty {
            try {
                Dns.SYSTEM.lookup(hostname)
            } catch (failure: UnknownHostException) {
                throw failure
            } catch (failure: Throwable) {
                throw UnknownHostException(hostname).apply { initCause(failure) }
            }
        }
        if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
        cache[hostname] = Cached(addresses, now + CACHE_MS)
        return addresses
    }

    private fun queryCloudflare(hostname: String): List<InetAddress> {
        val encoded = java.net.URLEncoder.encode(hostname, Charsets.UTF_8.name())
        val connection = URI("https://1.1.1.1/dns-query?name=$encoded&type=A&ct=application/dns-json")
            .toURL().openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = DOH_TIMEOUT_MS
            connection.readTimeout = DOH_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/dns-json")
            connection.setRequestProperty("User-Agent", "RXMusic-DNS/1")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            parseDnsAddresses(connection.inputStream.bufferedReader().use { it.readText() })
                .map(InetAddress::getByName)
        } finally {
            connection.disconnect()
        }
    }

    private const val DOH_TIMEOUT_MS = 2_500
    private const val CACHE_MS = 10 * 60_000L
    private const val MAX_CACHE_ENTRIES = 256
}

internal fun isResilientServiceHost(raw: String): Boolean {
    val host = raw.trim().lowercase().trimEnd('.')
    return RESILIENT_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

internal fun parseDnsAddresses(raw: String): List<String> = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    root["Answer"]?.jsonArray.orEmpty().mapNotNull { answer ->
        val value = answer.jsonObject
        if (value["type"]?.jsonPrimitive?.intOrNull != 1) return@mapNotNull null
        value["data"]?.jsonPrimitive?.content?.takeIf(::isIpv4Literal)
    }.distinct()
}.getOrDefault(emptyList())

private fun isIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() in 0..255
    }
}

private val RESILIENT_SUFFIXES = setOf(
    "spotify.com",
    "scdn.co",
    "soundcloud.com",
    "soundcloud.cloud",
    "sndcdn.com",
    "youtube.com",
    "youtubei.googleapis.com",
    "googlevideo.com",
    "ytimg.com",
)
