package com.muedsa.snapshot

import com.muedsa.snapshot.open.ADMIN_TOKEN_IDENTITY
import com.muedsa.snapshot.open.ApiCredential
import com.muedsa.snapshot.open.CredentialStore
import com.muedsa.snapshot.open.configureRoutingForTests
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 访问控制：多 API Key、管理凭据与分层限流。
 */
class AccessControlTest {

    @Test
    fun `credential store resolves client and admin keys`() {
        val store = CredentialStore(
            credentials = listOf(
                ApiCredential(name = "web", key = WEB_KEY),
                ApiCredential(name = "ops", key = OPS_KEY, admin = true),
            ),
            adminToken = ADMIN_TOKEN,
        )

        val client = assertNotNull(store.resolve(WEB_KEY))
        assertEquals("web", client.label)
        assertTrue(client.isAuthenticated)
        assertTrue(!client.isAdmin)

        val admin = assertNotNull(store.resolve(OPS_KEY))
        assertEquals("ops", admin.label)
        assertTrue(admin.isAdmin)

        val token = assertNotNull(store.resolve(ADMIN_TOKEN))
        assertEquals(ADMIN_TOKEN_IDENTITY, token.label)
        assertTrue(token.isAdmin)

        assertNull(store.resolve(UNKNOWN_KEY))
        assertNull(store.resolve(null))
    }

    @Test
    fun `multiple api keys authenticate while unknown key is rejected`() = testApplication {
        application {
            configureRoutingForTests(
                adminToken = null,
                credentials = listOf(
                    ApiCredential(name = "web-frontend", key = WEB_KEY),
                    ApiCredential(name = "partner-a", key = PARTNER_KEY),
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, render(apiKey = WEB_KEY).status)
        // 轮换期间新旧 Key 同时在列，两者都应可用。
        assertEquals(HttpStatusCode.OK, render(apiKey = PARTNER_KEY).status)
        assertEquals(HttpStatusCode.Unauthorized, render(apiKey = UNKNOWN_KEY).status)
        assertEquals(HttpStatusCode.Unauthorized, render(apiKey = null).status)
    }

    @Test
    fun `admin credential reaches admin endpoints while client credential is forbidden`() = testApplication {
        application {
            configureRoutingForTests(
                adminToken = ADMIN_TOKEN,
                credentials = listOf(
                    ApiCredential(name = "web-frontend", key = WEB_KEY),
                    ApiCredential(name = "ops", key = OPS_KEY, admin = true),
                ),
            )
        }

        // 带 admin 标记的 API Key 与管理令牌都能访问管理接口。
        assertEquals(HttpStatusCode.OK, client.get("/fonts") { header("X-API-Key", OPS_KEY) }.status)

        val forbidden = client.get("/fonts") { header("X-API-Key", WEB_KEY) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(forbidden.body<String>().contains("FORBIDDEN"))

        val unauthorized = client.get("/fonts")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        assertEquals(
            HttpStatusCode.OK,
            client.get("/fonts") { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }.status,
        )
    }

    @Test
    fun `admin token still authenticates admin endpoints`() = testApplication {
        application {
            configureRoutingForTests(adminToken = ADMIN_TOKEN)
        }

        assertEquals(HttpStatusCode.OK, client.get("/fonts") { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/fonts").status)
    }

    @Test
    fun `authenticated callers use their own rate limit bucket`() = testApplication {
        configure(
            overrides = {
                put("snapshot.api-key", WEB_KEY)
                put("snapshot.rate-limit.credential-requests", "2")
                put("snapshot.rate-limit.credential-window-ms", "60000")
            },
        )

        val first = render(apiKey = WEB_KEY)
        assertEquals(HttpStatusCode.OK, first.status)
        assertTrue(first.headers["X-RateLimit-Limit"]?.isNotEmpty() == true)
        assertTrue(first.headers["X-RateLimit-Remaining"]?.isNotEmpty() == true)

        assertEquals(HttpStatusCode.OK, render(apiKey = WEB_KEY).status)

        val limited = render(apiKey = WEB_KEY)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers[HttpHeaders.RetryAfter]?.isNotEmpty() == true)

        // 凭据配额耗尽不影响匿名桶。
        assertEquals(HttpStatusCode.OK, client.get("/health").status)

        val metrics = client.get("/metrics").body<String>()
        assertTrue(
            metrics.contains("""snapshot_rate_limited_total{scope="credential"} 1"""),
            metrics.lineSequence().filter { it.contains("rate_limited") }.joinToString("\n"),
        )
    }

    @Test
    fun `anonymous callers keep the per ip bucket`() = testApplication {
        configure()

        repeat(6) {
            assertEquals(HttpStatusCode.OK, render(apiKey = null).status)
        }

        val limited = render(apiKey = null)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)

        val metrics = client.get("/metrics").body<String>()
        assertTrue(
            metrics.contains("""snapshot_rate_limited_total{scope="anonymous"} 1"""),
            metrics.lineSequence().filter { it.contains("rate_limited") }.joinToString("\n"),
        )
    }

    @Test
    fun `metrics can require an api key`() = testApplication {
        configure(
            overrides = {
                put("snapshot.api-key", WEB_KEY)
                put("snapshot.metrics-access", "credential")
            },
        )

        assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
        assertEquals(HttpStatusCode.OK, client.get("/metrics") { header("X-API-Key", WEB_KEY) }.status)
        // 探针始终开放。
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
    }

    @Test
    fun `metrics can require an admin credential`() = testApplication {
        configure(
            overrides = {
                put("snapshot.api-key", WEB_KEY)
                put("snapshot.admin-token", ADMIN_TOKEN)
                put("snapshot.metrics-access", "admin")
            },
        )

        assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)

        val forbidden = client.get("/metrics") { header("X-API-Key", WEB_KEY) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        assertEquals(
            HttpStatusCode.OK,
            client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }.status,
        )
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.render(apiKey: String?): HttpResponse =
        client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            if (apiKey != null) header("X-API-Key", apiKey)
            setBody("<Snapshot type=\"png\"><Container width=\"2\" height=\"2\" color=\"#FFFF0000\"/></Snapshot>")
        }

    private companion object {
        const val WEB_KEY = "web-api-key-0123456789"
        const val PARTNER_KEY = "partner-api-key-0123456789"
        const val OPS_KEY = "ops-api-key-0123456789"
        const val UNKNOWN_KEY = "unknown-api-key-0123456789"
        const val ADMIN_TOKEN = "admin-token-0123456789"
    }
}
