package com.muedsa.snapshot.open

import java.net.Inet6Address
import java.net.InetAddress

private val IPV6_CHARACTERS = Regex("[0-9a-fA-F:]+")

/** 只接受数字 IP，绝不把请求头或配置中的主机名交给 DNS 解析。 */
internal fun parseNumericIp(value: String): ByteArray? {
    if (value.isEmpty() || value != value.trim()) return null
    if (value.contains(':')) {
        // XFF 使用裸 IPv6；拒绝 zone id、方括号和嵌入 IPv4 等歧义形式。
        if (value.length > 39 || !IPV6_CHARACTERS.matches(value)) return null
        return (runCatching { InetAddress.getByName(value) }.getOrNull() as? Inet6Address)?.address
    }
    val parts = value.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
            (part.length > 1 && part[0] == '0')
        ) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet > 255) return null
        bytes[index] = octet.toByte()
    }
    return bytes
}

internal class TrustedProxyCidr private constructor(
    private val network: ByteArray,
    private val prefixBits: Int,
) {
    fun contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false
        val fullBytes = prefixBits / 8
        for (index in 0 until fullBytes) {
            if (address[index] != network[index]) return false
        }
        val remainingBits = prefixBits % 8
        if (remainingBits == 0) return true
        val mask = 0xff shl (8 - remainingBits)
        return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        fun parse(value: String): TrustedProxyCidr? {
            val parts = value.split('/')
            if (parts.size !in 1..2) return null
            val ip = parseNumericIp(parts[0]) ?: return null
            val bits = if (parts.size == 1) ip.size * 8 else {
                if (parts[1].isEmpty() || parts[1].any { it !in '0'..'9' }) return null
                parts[1].toIntOrNull() ?: return null
            }
            if (bits !in 0..ip.size * 8) return null
            return TrustedProxyCidr(ip, bits)
        }
    }
}

/** 从 TCP 对端反向走 XFF；只跨越可信代理，绝不采用不可信节点左侧的可伪造内容。 */
internal fun resolveClientIp(peer: String, headers: List<String>?, trusted: List<TrustedProxyCidr>): String {
    if (trusted.isEmpty() || headers.isNullOrEmpty()) return peer
    val peerIp = parseNumericIp(peer) ?: return peer
    if (trusted.none { it.contains(peerIp) }) return peer
    if (headers.size > 32 || headers.sumOf { it.length } > 2048) return peer
    val hops = headers.flatMap { it.split(',') }
    if (hops.isEmpty() || hops.size > 32) return peer
    var leftmostTrusted: String? = null
    for (hop in hops.asReversed()) {
        val label = hop.trim()
        val address = parseNumericIp(label) ?: return peer
        val canonical = InetAddress.getByAddress(address).hostAddress
        if (trusted.none { it.contains(address) }) return canonical
        leftmostTrusted = canonical
    }
    return leftmostTrusted ?: peer
}
