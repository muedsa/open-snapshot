package com.muedsa.snapshot

import io.ktor.client.request.*
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.Base64
import com.muedsa.snapshot.open.configureImageCache
import com.muedsa.snapshot.open.configureRoutingForTests
import com.muedsa.snapshot.open.isBlockedImageAddress

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        // loads default configuration
        configure()
        // verify server root returns 200
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `health endpoint returns ok`() = testApplication {
        configure()

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.body<String>())
    }

    @Test
    fun `snapshot endpoint supports cors preflight`() = testApplication {
        configure()

        val response = client.options("/snapshot") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.ContentType)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:3000", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(response.headers[HttpHeaders.AccessControlAllowMethods].orEmpty().contains("POST"))
    }

    @Test
    fun `snapshot rate limit applies only to render endpoint`() = testApplication {
        configure()
        val source = "<Snapshot type=\"png\"><Container width=\"1\" height=\"1\" color=\"#FFFFFFFF\"/></Snapshot>"

        repeat(6) {
            val response = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(source)
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        assertEquals(HttpStatusCode.TooManyRequests, client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(source)
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `snapshot endpoint renders png`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("<Snapshot type=\"png\"><Container width=20 height=20 color=\"#FFFF0000\"/></Snapshot>")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"].orEmpty().startsWith("image/png"))
        assertContentStartsWith(response.body(), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    }

    @Test
    fun `snapshot endpoint supports all output formats`() = testApplication {
        configure()

        mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "webp" to "image/webp",
        ).forEach { (format, contentType) ->
            val response = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("<Snapshot type=\"$format\"><Container width=1 height=1 color=\"#FFFFFFFF\"/></Snapshot>")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith(contentType))
            assertTrue(response.body<ByteArray>().isNotEmpty())
        }
    }

    @Test
    fun `snapshot endpoint returns bad request for invalid source`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("<Snapshot>")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `snapshot endpoint rejects oversized canvas`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("<Snapshot><Container width=\"4097\" height=\"1\" color=\"#FFFFFFFF\"/></Snapshot>")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.body<String>().contains("RENDER_ERROR"))
    }

    @Test
    fun `snapshot endpoint rejects empty source`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-Request-Id", "test-request-1")
            setBody("   \n\t")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("test-request-1", response.headers["X-Request-Id"])
        assertTrue(response.headers["Content-Type"].orEmpty().startsWith("application/json"))
        assertTrue(response.body<String>().contains("EMPTY_REQUEST"))
    }

    @Test
    fun `admin endpoints are disabled by default`() = testApplication {
        configure()

        assertEquals(HttpStatusCode.NotFound, client.get("/fonts").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/fonts.png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/cacheInfo").status)
    }

    @Test
    fun `admin endpoints require bearer token`() = testApplication {
        application {
            configureRoutingForTests("test-admin-token")
        }

        val unauthorized = client.get("/fonts")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertTrue(unauthorized.body<String>().contains("UNAUTHORIZED"))

        val authorized = client.get("/fonts") {
            bearerAuth("test-admin-token")
        }

        assertEquals(HttpStatusCode.OK, authorized.status)
        assertTrue(authorized.body<String>().isNotBlank())
    }

    @Test
    fun `snapshot endpoint rejects oversized declared body`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("x".repeat(1_048_577))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    @Test
    fun `snapshot endpoint renders and caches local network image`() = testApplication {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/image.png") { exchange ->
            requests++
            exchange.sendResponseHeaders(HttpStatusCode.OK.value, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()

        try {
            configure()
            application {
                configureImageCache(allowPrivateHostsOverride = true)
            }
            val url = "http://127.0.0.1:${server.address.port}/image.png"
            val source = "<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" url=\"$url\"/></Snapshot>"

            val first = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(source)
            }
            val second = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(source)
            }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(1, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `snapshot endpoint rejects non-http image urls`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("<Snapshot><Image width=\"1\" height=\"1\" url=\"file:///tmp/image.png\"/></Snapshot>")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.body<String>().contains("IMAGE_LOAD_ERROR"))
    }

    @Test
    fun `image address policy blocks private and reserved networks`() {
        val blocked = listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.0.2.1",
            "192.168.0.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "fc00::1",
            "fd12:3456:789a::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "64:ff9b::a9fe:a9fe",
            "2002:0a00:0001::1",
        )
        blocked.forEach { literal ->
            assertTrue(isBlockedImageAddress(InetAddress.getByName(literal)), literal)
        }

        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach { literal ->
            assertFalse(isBlockedImageAddress(InetAddress.getByName(literal)), literal)
        }
    }

    private fun assertContentStartsWith(actual: ByteArray, expectedPrefix: ByteArray) {
        assertTrue(actual.size >= expectedPrefix.size)
        assertContentEquals(expectedPrefix, actual.copyOf(expectedPrefix.size))
    }

}
