package com.muedsa.snapshot

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiKeyAuthTest {

    private val pngSource =
        "<Snapshot type=\"png\"><Container width=\"2\" height=\"2\" color=\"#FFFF0000\"/></Snapshot>"

    @Test
    fun `render endpoint is open when no api key is configured`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `render endpoint requires the configured api key`() = testApplication {
        configure(overrides = { put("snapshot.api-key", TEST_API_KEY) })

        val unauthorized = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertTrue(unauthorized.body<String>().contains("UNAUTHORIZED"))
        assertEquals("Bearer", unauthorized.headers[HttpHeaders.WWWAuthenticate])

        val withApiKeyHeader = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-API-Key", TEST_API_KEY)
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, withApiKeyHeader.status)

        val withBearer = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header(HttpHeaders.Authorization, "Bearer $TEST_API_KEY")
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, withBearer.status)
    }

    @Test
    fun `wrong api key is rejected`() = testApplication {
        configure(overrides = { put("snapshot.api-key", TEST_API_KEY) })

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-API-Key", "wrong-api-key-0000000000")
            setBody(pngSource)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.body<String>().contains("UNAUTHORIZED"))
    }

    @Test
    fun `anonymous access can be enabled without accepting invalid credentials`() = testApplication {
        configure(
            overrides = {
                put("snapshot.api-key", TEST_API_KEY)
                put("snapshot.anonymous-access-enabled", "true")
            }
        )

        val anonymous = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, anonymous.status)

        val invalid = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-API-Key", "wrong-api-key-0000000000")
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)

        val authenticated = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-API-Key", TEST_API_KEY)
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, authenticated.status)
    }

    @Test
    fun `probe and metrics endpoints stay open with api key configured`() = testApplication {
        configure(overrides = { put("snapshot.api-key", TEST_API_KEY) })

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
        assertEquals(HttpStatusCode.OK, client.get("/metrics").status)
    }

    companion object {
        private const val TEST_API_KEY = "test-api-key-0123456789"
    }
}
