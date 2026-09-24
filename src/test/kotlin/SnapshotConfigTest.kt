package com.muedsa.snapshot

import com.muedsa.snapshot.open.ApiCredential
import com.muedsa.snapshot.open.MetricsAccess
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
        assertEquals(4096, config.maxDocumentElements)
        assertEquals(128, config.maxDocumentDepth)
        assertEquals(4096, config.canvas.maxWidth)
        assertEquals(4096, config.canvas.maxHeight)
        assertEquals(16_777_216L, config.canvas.maxPixels)
        assertEquals(10, config.image.maxImageNumOnce)
        assertEquals(5 * 1024 * 1024, config.image.maxSingleImageSize)
        assertEquals(16_777_216L, config.image.maxTotalImagePixels)
        assertEquals(100, config.image.memoryCacheNumLimit)
        assertEquals(10_000, config.image.connectTimeoutMs)
        assertEquals(10_000, config.image.readTimeoutMs)
        assertEquals(false, config.image.allowPrivateHosts)
        assertEquals(6, config.rateLimit.anonymousRequests)
        assertEquals(60_000L, config.rateLimit.anonymousWindowMs)
        assertEquals(60, config.rateLimit.credentialRequests)
        assertEquals(60_000L, config.rateLimit.credentialWindowMs)
        assertEquals(6, config.rateLimit.adminRequests)
        assertEquals(60_000L, config.rateLimit.adminWindowMs)
        assertEquals(false, config.cors.trustProxyHeaders)
        assertEquals(false, config.admin.enabled)
        assertNull(config.admin.token)
        assertNull(config.apiKey)
        assertEquals(emptyList(), config.credentials)
        assertEquals(MetricsAccess.OPEN, config.metricsAccess)
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
            put("snapshot.max-document-elements", "2000")
            put("snapshot.max-document-depth", "64")
            put("snapshot.image.max-total-image-pixels", "8000000")
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
        assertEquals(2000, config.maxDocumentElements)
        assertEquals(64, config.maxDocumentDepth)
        assertEquals(8_000_000L, config.image.maxTotalImagePixels)
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
    fun `admin endpoints require a token or an admin api key`() {
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

        // 只配置 admin: true 的 API Key 也允许启用管理接口。
        val withAdminKey = SnapshotConfigLoader.load(
            MapApplicationConfig(
                "snapshot.admin-endpoints-enabled" to "true",
                "snapshot.api-keys.size" to "1",
                "snapshot.api-keys.0.name" to "ops",
                "snapshot.api-keys.0.key" to "ops-api-key-0123456789",
                "snapshot.api-keys.0.admin" to "true",
            ),
            env = { null },
        )
        assertTrue(withAdminKey.credentials.single().admin)
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
    fun `list settings accept a single comma separated value`() {
        // 环境变量 / -P 覆盖只能传单个字符串，此时按英文逗号分隔。
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig(
                "snapshot.cors.allowed-hosts" to "localhost:3000,127.0.0.1:3000",
                "snapshot.font-family-names" to "Inter,Noto Serif SC",
                "snapshot.access-log-skip-paths" to "/health,/ready",
            ),
            env = { null },
        )

        assertEquals(listOf("localhost:3000", "127.0.0.1:3000"), config.cors.allowedHosts)
        assertEquals(listOf("Inter", "Noto Serif SC"), config.fontFamilyNames)
        assertEquals(setOf("/health", "/ready"), config.accessLog.skipPaths)
    }

    @Test
    fun `list settings accept yaml style lists`() {
        val source = MapApplicationConfig().apply {
            put("snapshot.cors.allowed-hosts", listOf("localhost:3000", "127.0.0.1:3000"))
            put("snapshot.font-family-names", listOf("Inter", "Noto Serif SC"))
            put("snapshot.access-log-skip-paths", listOf("/health"))
        }

        val config = SnapshotConfigLoader.load(source, env = { null })

        assertEquals(listOf("localhost:3000", "127.0.0.1:3000"), config.cors.allowedHosts)
        assertEquals(listOf("Inter", "Noto Serif SC"), config.fontFamilyNames)
        assertEquals(setOf("/health"), config.accessLog.skipPaths)
    }

    @Test
    fun `single element yaml list is still split by commas`() {
        val source = MapApplicationConfig().apply {
            put("snapshot.cors.allowed-hosts", listOf("localhost:3000,example.com"))
        }

        val config = SnapshotConfigLoader.load(source, env = { null })

        assertEquals(listOf("localhost:3000", "example.com"), config.cors.allowedHosts)
    }

    @Test
    fun `error image settings are configurable`() {
        val defaults = SnapshotConfigLoader.load(MapApplicationConfig(), env = { null })
        assertEquals(true, defaults.errorImage.enabled)
        assertEquals(8, defaults.errorImage.maxLines)
        assertEquals(80, defaults.errorImage.maxColumns)
        assertEquals(2, defaults.errorImage.contextLines)

        val configured = SnapshotConfigLoader.load(
            MapApplicationConfig(
                "snapshot.error-image.enabled" to "false",
                "snapshot.error-image.max-lines" to "3",
                "snapshot.error-image.max-columns" to "40",
                "snapshot.error-image.context-lines" to "0",
            ),
            env = { null },
        )
        assertEquals(false, configured.errorImage.enabled)
        assertEquals(3, configured.errorImage.maxLines)
        assertEquals(40, configured.errorImage.maxColumns)
        assertEquals(0, configured.errorImage.contextLines)
    }

    @Test
    fun `invalid error image settings are reported`() {
        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(
                    "snapshot.error-image.max-lines" to "0",
                    "snapshot.error-image.context-lines" to "-1",
                ),
                env = { null },
            )
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("snapshot.error-image.max-lines"), message)
        assertTrue(message.contains("snapshot.error-image.context-lines"), message)
    }

    @Test
    fun `blank api key keeps the render endpoint open`() {
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig("snapshot.api-key" to "   "),
            env = { null },
        )

        assertNull(config.apiKey)
    }

    @Test
    fun `api keys are read from the configuration list`() {
        val source = MapApplicationConfig().apply {
            put("snapshot.api-keys.size", "2")
            put("snapshot.api-keys.0.name", "web-frontend")
            put("snapshot.api-keys.0.key", "web-api-key-0123456789")
            put("snapshot.api-keys.1.name", "ops")
            put("snapshot.api-keys.1.key", "ops-api-key-0123456789")
            put("snapshot.api-keys.1.admin", "true")
        }

        val config = SnapshotConfigLoader.load(source, env = { null })

        assertEquals(2, config.credentials.size)
        assertEquals(ApiCredential("web-frontend", "web-api-key-0123456789", admin = false), config.credentials[0])
        assertEquals(ApiCredential("ops", "ops-api-key-0123456789", admin = true), config.credentials[1])
        assertTrue(config.requiresCredential)
    }

    @Test
    fun `api keys can be provided by environment variable`() {
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig(),
            env = { name ->
                if (name == "SNAPSHOT_API_KEYS") "web:web-api-key-0123456789,partner:partner-api-key-0123456789" else null
            },
        )

        assertEquals(listOf("web", "partner"), config.credentials.map { it.name })
        assertEquals(listOf(false, false), config.credentials.map { it.admin })
    }

    @Test
    fun `environment api keys support the admin flag`() {
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig(),
            env = { name ->
                if (name == "SNAPSHOT_API_KEYS") "web:web-api-key-0123456789,ops:ops-api-key-0123456789:admin" else null
            },
        )

        assertEquals(listOf("web", "ops"), config.credentials.map { it.name })
        assertEquals(listOf(false, true), config.credentials.map { it.admin })

        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(),
                env = { name ->
                    if (name == "SNAPSHOT_API_KEYS") "ops:ops-api-key-0123456789:root" else null
                },
            )
        }
        assertTrue(error.message.orEmpty().contains("'admin'"), error.message.orEmpty())
    }

    @Test
    fun `malformed api keys environment entry is reported`() {
        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(),
                env = { name -> if (name == "SNAPSHOT_API_KEYS") "web-api-key-0123456789" else null },
            )
        }

        assertTrue(error.message.orEmpty().contains("SNAPSHOT_API_KEYS"), error.message.orEmpty())
    }

    @Test
    fun `api key constraints are validated`() {
        val duplicateNames = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(),
                env = { name ->
                    if (name == "SNAPSHOT_API_KEYS") "web:key-0000000000000001,web:key-0000000000000002" else null
                },
            )
        }
        assertTrue(duplicateNames.message.orEmpty().contains("unique"), duplicateNames.message.orEmpty())

        val badName = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(),
                env = { name -> if (name == "SNAPSHOT_API_KEYS") "bad name:key-0000000000000001" else null },
            )
        }
        assertTrue(badName.message.orEmpty().contains("must match"), badName.message.orEmpty())

        val tooShort = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig(),
                env = { name -> if (name == "SNAPSHOT_API_KEYS") "web:short" else null },
            )
        }
        assertTrue(tooShort.message.orEmpty().contains("at least"), tooShort.message.orEmpty())
    }

    @Test
    fun `metrics access mode is parsed and validated`() {
        MetricsAccess.entries.forEach { mode ->
            val value = mode.name.lowercase()
            val config = SnapshotConfigLoader.load(
                MapApplicationConfig("snapshot.metrics-access" to value),
                env = { null },
            )
            assertEquals(mode, config.metricsAccess)
        }

        val error = assertFailsWith<SnapshotConfigurationException> {
            SnapshotConfigLoader.load(
                MapApplicationConfig("snapshot.metrics-access" to "sometimes"),
                env = { null },
            )
        }
        assertTrue(error.message.orEmpty().contains("snapshot.metrics-access"), error.message.orEmpty())
    }

    @Test
    fun `rate limit tiers are configurable`() {
        val config = SnapshotConfigLoader.load(
            MapApplicationConfig(
                "snapshot.rate-limit.requests" to "3",
                "snapshot.rate-limit.window-ms" to "30000",
                "snapshot.rate-limit.credential-requests" to "120",
                "snapshot.rate-limit.credential-window-ms" to "90000",
                "snapshot.rate-limit.admin-requests" to "2",
                "snapshot.rate-limit.admin-window-ms" to "45000",
            ),
            env = { null },
        )

        assertEquals(3, config.rateLimit.anonymousRequests)
        assertEquals(30_000L, config.rateLimit.anonymousWindowMs)
        assertEquals(120, config.rateLimit.credentialRequests)
        assertEquals(90_000L, config.rateLimit.credentialWindowMs)
        assertEquals(2, config.rateLimit.adminRequests)
        assertEquals(45_000L, config.rateLimit.adminWindowMs)
    }
}
