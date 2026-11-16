package com.music.bitchord.net

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A VLESS endpoint the listener pasted, and the client side of it.
 *
 * VLESS itself is small: a fixed header naming the target, then a byte pipe.
 * The transports around it are where the complexity lives, so this accepts the
 * shape that covers ordinary hosted keys - TCP with TLS, no Reality, no
 * WebSocket - and refuses the rest loudly instead of failing in a way that looks
 * like a broken network. Traffic for this app is then tunnelled by
 * [DesyncProxy]: every CONNECT becomes one VLESS request to the same server.
 *
 * That is deliberately narrower than a VPN. It does not create a tunnel for the
 * phone, it only carries this app's music traffic, which is exactly what the
 * listener asked for and avoids needing a userspace TCP stack.
 */
data class VlessEndpoint(
    val uuid: String,
    val host: String,
    val port: Int,
    val sni: String?,
    val tls: Boolean,
    val name: String?,
) {
    /** `host:port`, for a status line that does not leak the key. */
    val label: String get() = (name?.takeIf { it.isNotBlank() } ?: host) + ":" + port
}

object Vless {

    /**
     * Parses `vless://uuid@host:port?...#name`.
     *
     * Only parameters that change the wire format are honoured. Anything that
     * would need machinery this client does not have is rejected with a reason,
     * because a key that silently connects nowhere is worse than a refusal.
     */
    fun parse(link: String): Result<VlessEndpoint> {
        val trimmed = link.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Это не VLESS-ключ"))
        }
        val uri = runCatching { URI(trimmed) }
            .getOrElse { return Result.failure(IllegalArgumentException("Ключ не разбирается")) }
        val uuid = uri.userInfo?.substringBefore(':')?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("В ключе нет UUID"))
        if (uuidBytes(uuid) == null) {
            return Result.failure(IllegalArgumentException("UUID в ключе некорректен"))
        }
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("В ключе нет адреса сервера"))
        val port = if (uri.port > 0) uri.port else 443

        val query = parseQuery(uri.rawQuery)
        val transport = query["type"]?.lowercase() ?: "tcp"
        if (transport != "tcp") {
            return Result.failure(
                IllegalArgumentException("Транспорт «$transport» не поддерживается: нужен type=tcp"),
            )
        }
        val security = query["security"]?.lowercase() ?: "none"
        if (security != "tls" && security != "none") {
            return Result.failure(
                IllegalArgumentException("Шифрование «$security» не поддерживается: нужен security=tls"),
            )
        }
        val flow = query["flow"].orEmpty()
        if (flow.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException("flow=$flow не поддерживается: уберите flow из ключа"),
            )
        }
        return Result.success(
            VlessEndpoint(
                uuid = uuid,
                host = host,
                port = port,
                sni = query["sni"]?.takeIf { it.isNotBlank() },
                tls = security == "tls",
                name = uri.fragment?.takeIf { it.isNotBlank() }?.let(::decodeComponent),
            ),
        )
    }

    /** Opens a tunnel to [targetHost]:[targetPort] through [endpoint]. */
    fun connect(endpoint: VlessEndpoint, targetHost: String, targetPort: Int): Socket {
        var socket: Socket? = null
        try {
            socket = if (endpoint.tls) {
                val plain = Socket()
                plain.connect(InetSocketAddress(endpoint.host, endpoint.port), 15_000)
                plain.soTimeout = 15_000
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val secure = factory.createSocket(plain, endpoint.sni ?: endpoint.host, endpoint.port, true) as SSLSocket
                val parameters = secure.sslParameters
                parameters.serverNames = listOf(SNIHostName(endpoint.sni ?: endpoint.host))
                secure.sslParameters = parameters
                secure.startHandshake()
                secure
            } else {
                Socket().apply {
                    connect(InetSocketAddress(endpoint.host, endpoint.port), 15_000)
                }
            }
            val header = requestHeader(endpoint, targetHost, targetPort)
            val out: OutputStream = socket.getOutputStream()
            out.write(header)
            out.flush()
            return socket
        } catch (failure: Throwable) {
            runCatching { socket?.close() }
            throw failure
        }
    }

    /**
     * The VLESS request header for one proxied connection.
     *
     * Address type follows what the target is: a domain when the client asked by
     * name, which keeps DNS at the exit side and is what every server expects,
     * and a literal otherwise.
     */
    fun requestHeader(endpoint: VlessEndpoint, targetHost: String, targetPort: Int): ByteArray {
        val uuid = uuidBytes(endpoint.uuid)
            ?: throw IllegalArgumentException("UUID в ключе некорректен")
        val address = targetHost.trim()
        val ipv4 = parseIpv4(address)
        val ipv6 = if (ipv4 == null) parseIpv6(address) else null
        val payload = when {
            ipv4 != null -> byteArrayOf(0x01) + ipv4
            ipv6 != null -> byteArrayOf(0x03) + ipv6
            else -> {
                val bytes = address.toByteArray(Charsets.UTF_8)
                byteArrayOf(0x02, bytes.size.toByte()) + bytes
            }
        }
        return byteArrayOf(
            0x00,                       // protocol version
        ) + uuid + byteArrayOf(
            0x00,                       // no addons
            0x01,                       // command: TCP
        ) + byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xff).toByte()) + payload
    }

    /** Hyphenated or bare hex UUID to the sixteen bytes VLESS authenticates with. */
    fun uuidBytes(uuid: String): ByteArray? {
        val hex = uuid.replace("-", "").trim()
        if (hex.length != 32 || !hex.all { it.isLetterOrDigit() }) return null
        val out = ByteArray(16)
        for (index in 0 until 16) {
            val value = hex.substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            out[index] = value.toByte()
        }
        return out
    }

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun parseIpv6(text: String): ByteArray? {
        if (!text.contains(':')) return null
        return runCatching { InetAddress.getByName(text) }
            .getOrNull()
            ?.address
            ?.takeIf { it.size == 16 }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=').lowercase()
            val value = decodeComponent(pair.substringAfter('=', ""))
            key to value
        }.toMap()

    private fun decodeComponent(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
