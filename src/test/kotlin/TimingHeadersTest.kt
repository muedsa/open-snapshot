package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.TimingEntry
import com.muedsa.snapshot.open.buildServerTimingHeader
import com.muedsa.snapshot.open.configureImageCache
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 响应耗时元数据：`Server-Timing`、`X-Snapshot-Cache`、`X-Snapshot-Image-Count`。
 */
class TimingHeadersTest {

    private val pngSource = "<Snapshot type=\"png\"><Container width=\"2\" height=\"2\" color=\"#FFFF0000\"/></Snapshot>"
    private val invalidSource = "<Snapshot><Container width=\"1\"height=\"1\"/></Snapshot>"

    @Test
    fun `server timing header formats durations and descriptions`() {
        assertEquals(
            "queue;dur=0.3, render;dur=12.4, cache;desc=hit, total;dur=14.2",
            buildServerTimingHeader(
                listOf(
                    TimingEntry("queue", durationMs = 0.34),
                    TimingEntry("render", durationMs = 12.36),
                    TimingEntry("cache", description = "hit"),
                    TimingEntry("total", durationMs = 14.16),
                ),
            ),
        )
        assertEquals("", buildServerTimingHeader(listOf(TimingEntry("render"))))
        assertEquals("", buildServerTimingHeader(emptyList()))
    }

    @Test
    fun `successful render exposes timings`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val timing = response.headers["Server-Timing"].orEmpty()
        assertTrue(timing.contains("render;dur="), timing)
        assertTrue(timing.contains("total;dur="), timing)
        assertFalse(timing.contains("image;dur="), timing)
        assertTrue(timing.length < 200, timing)
        assertEquals("miss", response.headers["X-Snapshot-Cache"])
        assertNull(response.headers["X-Snapshot-Image-Count"])
    }

    @Test
    fun `cache hit omits the render phase`() = testApplication {
        configure()

        repeat(2) {
            client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(pngSource)
            }
        }
        val cached = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }

        assertEquals("hit", cached.headers["X-Snapshot-Cache"])
        val timing = cached.headers["Server-Timing"].orEmpty()
        assertTrue(timing.contains("cache;desc=hit"), timing)
        assertTrue(timing.contains("total;dur="), timing)
        assertFalse(timing.contains("render;dur="), timing)
    }

    @Test
    fun `remote image fetch is reported`() = testApplication {
        val png = Base64.getDecoder().decode(PNG_1X1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image.png") { exchange ->
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()

        try {
            configure()
            application { configureImageCache(allowPrivateHostsOverride = true) }
            val url = "http://127.0.0.1:${server.address.port}/image.png"

            val response = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" url=\"$url\"/></Snapshot>")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val timing = response.headers["Server-Timing"].orEmpty()
            assertTrue(timing.contains("image;dur="), timing)
            assertEquals("1", response.headers["X-Snapshot-Image-Count"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `parse error keeps the total timing`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(invalidSource)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.PARSE_ERROR))
        val timing = response.headers["Server-Timing"].orEmpty()
        assertTrue(timing.contains("total;dur="), timing)
        assertFalse(timing.contains("render;dur="), timing)
    }

    @Test
    fun `queued request reports its wait`() = testApplication {
        val png = Base64.getDecoder().decode(PNG_1X1)
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow.png") { exchange ->
            downloadStarted.countDown()
            releaseDownload.await(15, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            configure(
                overrides = {
                    put("snapshot.max-concurrent-renders", "1")
                    put("snapshot.max-render-queue", "4")
                    put("snapshot.render-cache.enabled", "false")
                },
            )
            application { configureImageCache(allowPrivateHostsOverride = true) }
            val url = "http://127.0.0.1:${server.address.port}/slow.png"

            val occupying = scope.async {
                client.post("/snapshot") {
                    header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                    setBody("<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" url=\"$url\"/></Snapshot>")
                }
            }
            assertTrue(downloadStarted.await(15, TimeUnit.SECONDS), "首个渲染应已占用渲染槽位")

            val queued = scope.async {
                client.post("/snapshot") {
                    header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                    setBody(pngSource)
                }
            }
            Thread.sleep(150)
            releaseDownload.countDown()

            assertEquals(HttpStatusCode.OK, occupying.await().status)
            val queuedResponse = queued.await()
            assertEquals(HttpStatusCode.OK, queuedResponse.status)
            assertTrue(queuedResponse.headers["Server-Timing"].orEmpty().contains("queue;dur="), "排队请求应带 queue;dur")
        } finally {
            releaseDownload.countDown()
            scope.cancel()
            server.stop(0)
        }
    }

    @Test
    fun `timing headers can be disabled`() = testApplication {
        configure(overrides = { put("snapshot.timing-headers.enabled", "false") })

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers["Server-Timing"])
        assertNull(response.headers["X-Snapshot-Cache"])
    }

    @Test
    fun `cors exposes timing headers to browsers`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }

        val exposed = response.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty()
        assertNotNull(response.headers["Server-Timing"])
        assertTrue(exposed.contains("Server-Timing"), exposed)
        assertTrue(exposed.contains("X-Snapshot-Cache"), exposed)
    }

    @Test
    fun `preflight still advertises the timing headers`() = testApplication {
        configure()

        val preflight = client.options("/snapshot") {
            header(HttpHeaders.Origin, "http://localhost:3000")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.ContentType)
        }

        assertEquals(HttpStatusCode.OK, preflight.status)
    }

    private companion object {
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
