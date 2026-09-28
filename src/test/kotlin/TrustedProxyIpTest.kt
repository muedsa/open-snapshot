package com.muedsa.snapshot

import com.muedsa.snapshot.open.TrustedProxyCidr
import com.muedsa.snapshot.open.parseNumericIp
import com.muedsa.snapshot.open.resolveClientIp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedProxyIpTest {
    private fun cidr(value: String) = assertNotNull(TrustedProxyCidr.parse(value))

    @Test
    fun `cidr boundaries and address families are respected`() {
        val ipv4 = cidr("10.0.0.0/24")
        assertTrue(ipv4.contains(assertNotNull(parseNumericIp("10.0.0.255"))))
        assertFalse(ipv4.contains(assertNotNull(parseNumericIp("10.0.1.0"))))
        assertFalse(ipv4.contains(assertNotNull(parseNumericIp("2001:db8::1"))))
        val ipv6 = cidr("2001:db8::/32")
        assertTrue(ipv6.contains(assertNotNull(parseNumericIp("2001:db8:abcd::1"))))
        assertFalse(ipv6.contains(assertNotNull(parseNumericIp("2001:db9::1"))))
        assertTrue(cidr("192.0.2.4").contains(assertNotNull(parseNumericIp("192.0.2.4"))))
    }

    @Test
    fun `only trusted hops are traversed from the connection peer`() {
        val trusted = listOf(cidr("10.0.0.1/32"), cidr("10.0.0.2/32"))
        assertEquals("203.0.113.7", resolveClientIp("10.0.0.2", listOf("1.2.3.4, 203.0.113.7, 10.0.0.1"), trusted))
        assertEquals("203.0.113.7", resolveClientIp("10.0.0.2", listOf("1.2.3.4", "203.0.113.7, 10.0.0.1"), trusted))
        assertEquals("198.51.100.5", resolveClientIp("10.0.0.2", listOf("198.51.100.5, 10.0.0.1"), trusted))
        assertEquals("10.0.0.2", resolveClientIp("10.0.0.2", listOf("203.0.113.7"), emptyList()))
        assertEquals("198.51.100.10", resolveClientIp("198.51.100.10", listOf("1.2.3.4"), trusted))
    }

    @Test
    fun `invalid or oversized traversed chains fall back to peer`() {
        val trusted = listOf(cidr("10.0.0.0/24"))
        for (header in listOf("unknown", "203.0.113.7, unknown", "203.0.113.7, ", "[2001:db8::1]")) {
            assertEquals("10.0.0.2", resolveClientIp("10.0.0.2", listOf(header), trusted))
        }
        assertEquals("10.0.0.2", resolveClientIp("10.0.0.2", List(33) { "10.0.0.1" }, trusted))
        assertEquals("10.0.0.2", resolveClientIp("10.0.0.2", listOf("x".repeat(2049)), trusted))
        // 首个不可信节点左侧的数据不可用于改变结果，也不需要解析。
        assertEquals("203.0.113.7", resolveClientIp("10.0.0.2", listOf("invalid, 203.0.113.7"), trusted))
    }

    @Test
    fun `ipv6 proxy chain and invalid literals`() {
        val trusted = listOf(cidr("2001:db8:1::/48"))
        assertEquals("2001:db8:2:0:0:0:0:9", resolveClientIp("2001:db8:1::2", listOf("1.2.3.4, 2001:db8:2::9, 2001:db8:1::1"), trusted))
        assertEquals(
            resolveClientIp("2001:db8:1::2", listOf("2001:db8:2::9"), trusted),
            resolveClientIp("2001:db8:1::2", listOf("2001:0db8:0002:0:0:0:0:9"), trusted),
        )
        for (value in listOf("example.com", "127.0.0.1.evil", "01.2.3.4", "256.1.1.1", "fe80::1%eth0", "[::1]")) {
            assertNull(parseNumericIp(value), value)
            assertNull(TrustedProxyCidr.parse(value), value)
        }
        for (value in listOf("10.0.0.0/33", "::1/129", "10.0.0.1/-1", "10.0.0.1/24/1")) {
            assertNull(TrustedProxyCidr.parse(value), value)
        }
    }
}
