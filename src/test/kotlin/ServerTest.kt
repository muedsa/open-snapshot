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
import java.util.Base64
import com.muedsa.snapshot.open.configureImageCache

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
    fun `fonts endpoint returns font names`() = testApplication {
        configure()

        val response = client.get("/fonts")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.body<String>().isNotBlank())
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

    private fun assertContentStartsWith(actual: ByteArray, expectedPrefix: ByteArray) {
        assertTrue(actual.size >= expectedPrefix.size)
        assertContentEquals(expectedPrefix, actual.copyOf(expectedPrefix.size))
    }

}
