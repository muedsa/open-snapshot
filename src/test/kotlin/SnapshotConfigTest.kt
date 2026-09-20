package com.muedsa.snapshot

import com.muedsa.snapshot.open.SnapshotConfigLoader
import com.muedsa.snapshot.open.SnapshotConfigurationException
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotConfigTest {

    @Test
    fun `empty configuration falls back to defaults`() {
        val config = SnapshotConfigLoader.load(MapApplicationConfig(), env = { null })

        assertEquals(1_048_576L, config.maxRequestSize)
        assertEquals(4, config.maxConcurrentRenders)
        assertEquals(30_000L, config.maxRenderTimeoutMs)
        assertEquals(4096, config.canvas.maxWidth)
        assertEquals(4096, config.canvas.maxHeight)
        assertEquals(16_777_216L, config.canvas.maxPixels)
        assertEquals(10, config.image.maxImageNumOnce)
        assertEquals(5 * 1024 * 1024, config.image.maxSingleImageSize)
        assertEquals(100, config.image.memoryCacheNumLimit)
        assertEquals(10_000, config.image.connectTimeoutMs)
        assertEquals(10_000, config.image.readTimeoutMs)
        assertEquals(false, config.image.allowPrivateHosts)
        assertEquals(6, config.rateLimit.requests)
        assertEquals(60_000L, config.rateLimit.windowMs)
        assertEquals(false, config.cors.trustProxyHeaders)
        assertEquals(false, config.admin.enabled)
        assertNull(config.admin.token)
        assertNull(config.apiKey)
        assertEquals(true, config.accessLog.enabled)
        assertEquals(setOf("/health", "/ready", "/metrics"), config.accessLog.skipPaths)
        assertEquals(true, config.metricsEnabled)
        assertEquals(emptyList(), config.fontFamilyNames)
    }

    @Test
    fun `values are read from configuration paths`() {
        val source = MapApplicationConfig().apply {
            put("snapshot.max-request-size", "2048")
            put("snapshot.max-concurrent-renders", "2")
            put("snapshot.image.connect-timeout-ms", "1500")
            put("snapshot.image.read-timeout-ms", "2500")
            put("snapshot.image.allow-private-hosts", "true")
            put("snapshot.metrics-enabled", "false")
            put("snapshot.access-log-enabled", "false")
            put("snapshot.access-log-skip-paths", listOf("/health"))
            put("snapshot.cors.allowed-hosts", listOf("localhost:3000"))
            put("snapshot.font-family-names", listOf("Inter", "Noto Serif SC"))
        }

        val config = SnapshotConfigLoader.load(source, env = { null })

        assertEquals(2048L, config.maxRequestSize)
        assertEquals(2, config.maxConcurrentRenders)
        assertEquals(1500, config.image.connectTimeoutMs)
        assertEquals(2500, config.image.readTimeoutMs)
        assertTrue(config.image.allowPrivateHosts)
        assertEquals(false, config.metricsEnabled)
        assertEquals(false, config.accessLog.enabled)
        assertEquals(setOf("/health"), config.accessLog.skipPaths)
        assertEquals(listOf("localhost:3000"), config.cors.allowedHosts)
        assertEquals(listOf("Inter", "Noto Serif SC"), config.fontFamilyNames)
    }

    @Test
    fun `all problems are reported at once`() {
        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(
                    "snapshot.max-request-size" to "-1",
                    "snapshot.metrics-enabled" to "maybe",
                    "snapshot.image.max-image-pixels" to "abc",
                ),
                env = { null },
            )
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("snapshot.max-request-size"), message)
        assertTrue(message.contains("snapshot.metrics-enabled"), message)
        assertTrue(message.contains("snapshot.image.max-image-pixels"), message)
    }

    @Test
    fun `admin endpoints require a token`() {
        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig("snapshot.admin-endpoints-enabled" to "true"),
                env = { null },
            )
        }
        assertTrue(error.message.orEmpty().contains("SNAPSHOT_ADMIN_TOKEN"))

        val config = SnapshotConfigLoader.load(
            MapApplicationConfig(
                "snapshot.admin-endpoints-enabled" to "true",
                "snapshot.admin-token" to "configured-token",
            ),
            env = { null },
        )
        assertEquals(true, config.admin.enabled)
        assertEquals("configured-token", config.admin.token)
    }

    @Test
    fun `short api key is rejected`() {
        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig("snapshot.api-key" to "short"),
                env = { null },
            )
        }

        assertTrue(error.message.orEmpty().contains("snapshot.api-key"), error.message.orEmpty())
    }

    @Test
    fun `environment variables take precedence over configuration`() {
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig(
                "snapshot.api-key" to "config-api-key-000000",
                "snapshot.admin-endpoints-enabled" to "true",
                "snapshot.admin-token" to "config-admin-token",
            ),
            env = { name ->
                when (name) {
                    "SNAPSHOT_API_KEY" -> "environment-api-key-111111"
                    "SNAPSHOT_ADMIN_TOKEN" -> "environment-admin-token"
                    else -> null
                }
            },
        )

        assertEquals("environment-api-key-111111", config.apiKey)
        assertEquals("environment-admin-token", config.admin.token)
    }

    @Test
    fun `blank api key keeps the render endpoint open`() {
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig("snapshot.api-key" to "   "),
            env = { null },
        )

        assertNull(config.apiKey)
    }
}
