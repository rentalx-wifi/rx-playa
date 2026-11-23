package com.rentalx.playa.net

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Splits the TLS handshake so an on-path filter cannot read it in one piece.
 *
 * Access providers in the region throttle music hosts by inspecting the
 * ClientHello, which carries the SNI in clear text. This is a userspace
 * approximation of what `zapret`/`tpws` do on a router: the connection is
 * tunnelled through a loopback CONNECT proxy owned by this app, and the first
 * handshake record is rewritten into two records and written in two delayed
 * segments. Filters that look only at the first segment, or that do not
 * reassemble the record layer, see an unrecognisable hello.
 *
 * Deliberate limits, stated rather than papered over:
 *  - This is not a VPN. It only covers traffic the app itself proxies, it
 *    cannot change the destination IP, and a destination-address block, an
 *    account-level restriction or a service that has withdrawn from a country
 *    remains unreachable.
 *  - Raw-socket tricks (fake packets, bad sequence numbers) need root or a
 *    kernel queue and are not attempted here.
 *  - QUIC/UDP is not tunnelled; callers reach these hosts over TCP.
 */
object DesyncProxy {

    /** Ports are chosen by the OS; this is the loopback listener, when started. */
    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = AtomicBoolean(false)

    /** Hosts whose handshake is worth rewriting. Anything else is tunnelled untouched. */
    private val REWRITTEN_HOST_SUFFIXES = listOf(
        "youtube.com",
        "youtu.be",
        "ytimg.com",
        "googlevideo.com",
        "googleusercontent.com",
        "ggpht.com",
        "spotify.com",
        "scdn.co",
        "spotifycdn.com",
        "soundcloud.com",
        "sndcdn.com",
        "soundcloud.cloud",
        "music.yandex.ru",
        "yandex.net",
    )

    /**
     * How aggressively the handshake is disturbed.
     *
     * SPLIT is the cheap one: one cut, two records. MULTISPLIT cuts the hello at
     * several offsets, so no single segment carries a contiguous record and a
     * filter that reassembles only adjacent segments sees fragments. SEGMENTS
     * leaves the record layer alone and only spreads the bytes over several TCP
     * writes, for filters that classify per segment rather than per stream.
     */
    enum class Strategy(val key: String, val display: String) {
        SPLIT("split", "split"),
        MULTISPLIT("multisplit", "multisplit"),
        SEGMENTS("segments", "segments");

        companion object {
            fun of(key: String?): Strategy = entries.firstOrNull { it.key == key } ?: SPLIT
        }
    }

    /** Positions used by MULTISPLIT, in bytes into the first record. */
    private val MULTISPLIT_POSITIONS = intArrayOf(2, 5, 9, 14)

    /** Where in the first record to cut. Small values keep the SNI split. */
    private const val SPLIT_POSITION = 2

    /** Delay between the two writes, in milliseconds. Enough to leave as two segments. */
    private const val SEGMENT_GAP_MS = 6L

    /** Largest first record worth buffering before falling back to a plain tunnel. */
    private const val MAX_HELLO_BYTES = 16_384

    /** How long to wait for the rest of a handshake before giving up politely. */
    private const val HELLO_TIMEOUT_MS = 900

    /** Proof the caller is this process; the port itself is visible to every app. */
    private val accessToken: String = java.util.UUID.randomUUID().toString()

    private val slots = java.util.concurrent.Semaphore(64)

    /** Credentials the shared HTTP client presents after the 407 challenge. */
    fun authorization(): Pair<String, String> = "rx" to accessToken

    /** Whether the app should route through this proxy right now. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Set when the listener supplied their own VLESS server. */
    @Volatile
    private var vless: VlessEndpoint? = null

    fun configureVless(endpoint: VlessEndpoint?) {
        vless = endpoint
    }

    fun hasVless(): Boolean = vless != null

    /** Which disturbance a rewritten handshake gets. */
    @Volatile
    var strategy: Strategy = Strategy.SPLIT

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) start() else stop()
    }

    fun selectStrategy(value: Strategy) {
        strategy = value
    }

    /**
     * Selector handed to OkHttp once, at construction.
     *
     * Reading [proxyOrNull] per connection is what makes the switch instant and
     * reversible: no client is rebuilt, and every new connection simply asks
     * the same question again.
     */
    fun proxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(proxyOrNull() ?: Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: java.net.SocketAddress, ioe: java.io.IOException) = Unit
    }

    /** The proxy OkHttp should use, or null to talk to the internet directly. */
    fun proxyOrNull(): Proxy? {
        val bound = server ?: return null
        if (!enabled) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), bound.localPort))
    }

    @Synchronized
    private fun start() {
        if (running.get()) return
        val listener = runCatching {
            ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        }.getOrNull() ?: return
        server = listener
        running.set(true)
        Thread({ acceptLoop(listener) }, "desync-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running.get()) {
            if (!slots.tryAcquire()) continue
            val client = try {
                listener.accept()
            } catch (_: Throwable) {
                return
            }
            Thread({
                try {
                    runCatching { handle(client) }
                } finally {
                    slots.release()
                }
            }, "desync-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { inbound ->
            inbound.soTimeout = HELLO_TIMEOUT_MS
            val requestHead = readHeaders(inbound.getInputStream()) ?: return
            // Past the head this is a byte pipe: a read timeout here would kill
            // every idle keep-alive after the handshake window.
            inbound.soTimeout = 0
            val requestLine = requestHead.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                runCatching {
                    inbound.getOutputStream().write(
                        "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                }
                return
            }
            if (!authorized(requestHead)) {
                runCatching {
                    inbound.getOutputStream().write(
                        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"rx\"\r\n" +
                            "Content-Length: 0\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                    )
                }
                return
            }
            val target = parseAuthority(parts[1]) ?: return
            val endpoint = vless
            val upstream = runCatching {
                if (endpoint != null) {
                    Vless.connect(endpoint, target.first, target.second).apply { soTimeout = 0 }
                } else {
                    Socket().apply {
                        connect(InetSocketAddress(target.first, target.second), 8_000)
                        soTimeout = 0
                    }
                }
            }.getOrNull() ?: run {
                runCatching {
                    inbound.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                }
                return
            }

            upstream.use { outbound ->
                runCatching {
                    inbound.getOutputStream().write(
                        "HTTP/1.1 200 Connection Established\r\n\r\n"
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                }.onFailure { return }

                val rewrite = enabled && endpoint == null && shouldRewrite(target.first)
                val clientIn = inbound.getInputStream()
                val serverOut = outbound.getOutputStream()
                if (rewrite) {
                    val head = readFirstRecord(clientIn, serverOut) ?: return
                    sendDesynced(head, serverOut, strategy)
                }
                val upstreamPump = Thread({
                    runCatching { pump(clientIn, serverOut) }
                }, "desync-up").apply { isDaemon = true; start() }
                runCatching { pump(outbound.getInputStream(), inbound.getOutputStream()) }
                runCatching { upstreamPump.join(2_000) }
            }
        }
    }

    private fun authorized(head: String): Boolean {
        val line = head.lineSequence().firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }
            ?: return false
        val encoded = line.substringAfter(':').trim().removePrefix("Basic").trim()
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.ISO_8859_1)
        }.getOrNull() ?: return false
        return decoded.endsWith(accessToken)
    }

    private fun shouldRewrite(host: String): Boolean {
        val lowered = host.lowercase()
        return REWRITTEN_HOST_SUFFIXES.any { lowered == it || lowered.endsWith(".$it") }
    }

    private fun parseAuthority(authority: String): Pair<String, Int>? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null
        val host: String
        val portText: String?
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end < 0) return null
            host = trimmed.substring(1, end)
            portText = trimmed.substring(end + 1).removePrefix(":")
        } else {
            val colon = trimmed.lastIndexOf(':')
            host = if (colon > 0) trimmed.substring(0, colon) else trimmed
            portText = if (colon > 0) trimmed.substring(colon + 1) else null
        }
        if (host.isEmpty()) return null
        val port = portText?.toIntOrNull() ?: 443
        if (port !in 1..65535) return null
        return host to port
    }

    /**
     * Reads the first TLS record, forwarding anything that is not one.
     *
     * Returns the buffered bytes when they start with a handshake record, and
     * null after already having forwarded them when they do not - the caller
     * then tunnels the rest normally.
     */
    private fun readFirstRecord(clientIn: InputStream, serverOut: OutputStream): ByteArray? {
        val buffer = ByteArray(MAX_HELLO_BYTES)
        var filled = 0
        val started = System.currentTimeMillis()
        while (filled < buffer.size) {
            val read = runCatching { clientIn.read(buffer, filled, buffer.size - filled) }.getOrElse { -1 }
            if (read <= 0) {
                if (filled > 0) serverOut.write(buffer, 0, filled)
                return null
            }
            filled += read
            if (filled < 5) continue
            if (buffer[0] != TLS_HANDSHAKE) {
                serverOut.write(buffer, 0, filled)
                return null
            }
            val recordLength = ((buffer[3].toInt() and 0xff) shl 8) or (buffer[4].toInt() and 0xff)
            if (recordLength <= 0 || recordLength > MAX_HELLO_BYTES - 5) {
                serverOut.write(buffer, 0, filled)
                return null
            }
            if (filled >= recordLength + 5) return buffer.copyOf(filled)
            if (System.currentTimeMillis() - started > HELLO_TIMEOUT_MS) {
                serverOut.write(buffer, 0, filled)
                return null
            }
        }
        serverOut.write(buffer, 0, filled)
        return null
    }

    /** Applies the selected disturbance, then forwards any remaining bytes. */
    private fun sendDesynced(hello: ByteArray, out: OutputStream, strategy: Strategy) {
        when (strategy) {
            Strategy.SEGMENTS -> writeInSegments(hello, out, 3)
            Strategy.MULTISPLIT -> {
                val positions = MULTISPLIT_POSITIONS.filter { it > 0 && it < hello.size - 5 }
                val rewritten = TlsFragment.splitRecord(hello, positions)
                if (rewritten == null) out.write(hello) else writeInSegments(rewritten, out, positions.size + 1)
            }
            Strategy.SPLIT -> {
                val cut = splitAt()
                val rewritten = TlsFragment.splitFirstRecord(hello, cut) ?: run {
                    out.write(hello)
                    return
                }
                out.write(rewritten, 0, 5 + cut)
                out.flush()
                Thread.sleep(SEGMENT_GAP_MS)
                out.write(rewritten, 5 + cut, rewritten.size - (5 + cut))
                out.flush()
            }
        }
    }

    /** Even chunks with a pause between them, so the kernel emits separate segments. */
    private fun writeInSegments(bytes: ByteArray, out: OutputStream, parts: Int) {
        val chunk = (bytes.size / parts.coerceAtLeast(1)).coerceAtLeast(1)
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunk).coerceAtMost(bytes.size)
            out.write(bytes, offset, end - offset)
            out.flush()
            offset = end
            if (offset < bytes.size) Thread.sleep(SEGMENT_GAP_MS)
        }
    }

    private fun splitAt(): Int = SPLIT_POSITION + Random.nextInt(0, 2)

    private fun readHeaders(input: InputStream): String? {
        val collected = StringBuilder(128)
        var previous = 0
        while (collected.length < 8_192) {
            val byte = input.read()
            if (byte < 0) return null
            collected.append(byte.toChar())
            val code = byte.toInt()
            if (previous == '\r'.code && code == '\n'.code) {
                val size = collected.length
                if (size >= 4 && collected[size - 4] == '\r' && collected[size - 3] == '\n') {
                    return collected.toString()
                }
            }
            previous = code
        }
        return null
    }

    private fun pump(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(8_192)
        while (true) {
            val read = from.read(buffer)
            if (read <= 0) return
            to.write(buffer, 0, read)
            to.flush()
        }
    }

    private const val TLS_HANDSHAKE = 0x16.toByte()
}

/** Record-layer surgery, separated so it can be exercised without a socket. */
object TlsFragment {

    /**
     * Rewrites one handshake record as several, cut at [positions].
     *
     * Invalid, duplicated or out-of-range positions are ignored rather than
     * refused: a strategy tuned for a long hello must still do something useful
     * on a short one.
     */
    fun splitRecord(input: ByteArray, positions: List<Int>): ByteArray? {
        if (input.size < 5) return null
        if (input[0] != 0x16.toByte()) return null
        val recordLength = ((input[3].toInt() and 0xff) shl 8) or (input[4].toInt() and 0xff)
        if (recordLength <= 0 || input.size < recordLength + 5) return null
        val cuts = positions.distinct().filter { it > 0 && it < recordLength }.sorted()
        if (cuts.isEmpty()) return null

        val head = input.copyOfRange(0, 5)
        val payload = input.copyOfRange(5, recordLength + 5)
        val tail = input.copyOfRange(recordLength + 5, input.size)
        val bounds = listOf(0) + cuts + recordLength
        val out = java.io.ByteArrayOutputStream(input.size + 5 * bounds.size)
        for (index in 0 until bounds.size - 1) {
            val from = bounds[index]
            val to = bounds[index + 1]
            val size = to - from
            if (size <= 0) continue
            out.write(head[0].toInt()); out.write(head[1].toInt()); out.write(head[2].toInt())
            out.write((size shr 8) and 0xff); out.write(size and 0xff)
            out.write(payload, from, size)
        }
        out.write(tail)
        return out.toByteArray()
    }

    /**
     * Rewrites one TLS handshake record as two, so the plain-text SNI sits in
     * the second of them. Bytes after the first record are copied verbatim -
     * a ClientHello is one record in practice, but nothing here assumes it.
     */
    fun splitFirstRecord(input: ByteArray, splitAt: Int): ByteArray? {
        if (input.size < 5) return null
        if (input[0] != 0x16.toByte()) return null
        val recordLength = ((input[3].toInt() and 0xff) shl 8) or (input[4].toInt() and 0xff)
        if (recordLength <= 0 || input.size < recordLength + 5) return null
        if (splitAt <= 0 || splitAt >= recordLength) return null

        val head = input.copyOfRange(0, 5)
        val payload = input.copyOfRange(5, recordLength + 5)
        val tail = input.copyOfRange(recordLength + 5, input.size)

        val first = ByteArray(5 + splitAt)
        head.copyInto(first, 0, 0, 3)
        first[3] = ((splitAt shr 8) and 0xff).toByte()
        first[4] = (splitAt and 0xff).toByte()
        payload.copyInto(first, 5, 0, splitAt)

        val restLength = recordLength - splitAt
        val second = ByteArray(5 + restLength)
        head.copyInto(second, 0, 0, 3)
        second[3] = ((restLength shr 8) and 0xff).toByte()
        second[4] = (restLength and 0xff).toByte()
        payload.copyInto(second, 5, splitAt, recordLength)

        return first + second + tail
    }
}
