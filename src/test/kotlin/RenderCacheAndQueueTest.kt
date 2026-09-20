package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.RenderExecutor
import com.muedsa.snapshot.open.RenderQueueFullException
import com.muedsa.snapshot.open.RenderQueueTimeoutException
import com.muedsa.snapshot.open.RenderResultCache
import com.muedsa.snapshot.open.SnapshotResult
import com.muedsa.snapshot.open.bypassesRenderCache
import com.muedsa.snapshot.open.configureImageCache
import com.muedsa.snapshot.open.renderCacheKey
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RenderCacheAndQueueTest {

    private val pngSource =
        "<Snapshot type=\"png\"><Container width=\"2\" height=\"2\" color=\"#FFFF0000\"/></Snapshot>"

    // ---------- 渲染结果缓存 ----------

    @Test
    fun `cache returns the stored result until it expires`() {
        var now = 1_000L
        val cache = RenderResultCache(maxEntries = 4, maxBytes = 1024, ttlMs = 500, clock = { now })
        val result = SnapshotResult(ByteArray(16), ContentType.Image.PNG)

        cache.put("key", result)
        assertNotNull(cache.get("key"))
        assertEquals(1L, cache.stats().hits)

        now += 501
        assertNull(cache.get("key"), "过期后应重新渲染")
        assertEquals(0, cache.stats().entries)
    }

    @Test
    fun `cache evicts by entry limit and reports misses`() {
        val cache = RenderResultCache(maxEntries = 2, maxBytes = 1024, ttlMs = 60_000)
        repeat(3) { index ->
            cache.put("key-$index", SnapshotResult(ByteArray(8), ContentType.Image.PNG))
        }

        assertEquals(2, cache.stats().entries)
        assertEquals(1L, cache.stats().evictions)
        assertNull(cache.get("key-0"))
        assertEquals(1L, cache.stats().misses)
        assertNotNull(cache.get("key-1"))
        assertNotNull(cache.get("key-2"))
    }

    @Test
    fun `cache skips results larger than the byte budget`() {
        val cache = RenderResultCache(maxEntries = 4, maxBytes = 100, ttlMs = 60_000)

        cache.put("big", SnapshotResult(ByteArray(200), ContentType.Image.PNG))

        assertEquals(0, cache.stats().entries)
        assertNull(cache.get("big"))
    }

    @Test
    fun `cache invalidate clears entries`() {
        val cache = RenderResultCache(maxEntries = 4, maxBytes = 1024, ttlMs = 60_000)
        cache.put("key", SnapshotResult(ByteArray(8), ContentType.Image.PNG))

        cache.invalidate()

        assertEquals(0, cache.stats().entries)
        assertNull(cache.get("key"))
    }

    @Test
    fun `noCache attribute bypasses the render cache`() {
        assertFalse(bypassesRenderCache(pngSource))
        assertTrue(bypassesRenderCache("""<Snapshot><Image url="https://example.com/a.png" noCache="true"/></Snapshot>"""))
        assertTrue(bypassesRenderCache("""<Snapshot><Image url="https://example.com/a.png" noCache='true'/></Snapshot>"""))
        // 裸写属性回落到默认值 false，因此不需要绕过缓存。
        assertFalse(bypassesRenderCache("""<Snapshot><Image url="https://example.com/a.png" noCache/></Snapshot>"""))
    }

    @Test
    fun `render cache key is stable and input bound`() {
        assertEquals(renderCacheKey(pngSource), renderCacheKey(pngSource))
        assertFalse(renderCacheKey(pngSource) == renderCacheKey("$pngSource "))
    }

    @Test
    fun `repeated requests are served from the render cache`() = testApplication {
        configure()

        val first = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, second.status)
        assertContentEquals(first.body<ByteArray>(), second.body<ByteArray>())

        val metrics = client.get("/metrics").bodyAsText()
        assertTrue(metrics.contains("snapshot_render_cache_entries 1"), metrics)
        assertTrue(metrics.contains("snapshot_render_cache_hits_total 1"), metrics)
        assertTrue(metrics.contains("snapshot_render_cache_misses_total 1"), metrics)
    }

    @Test
    fun `render cache can be disabled by configuration`() = testApplication {
        configure(overrides = { put("snapshot.render-cache.enabled", "false") })

        repeat(2) {
            client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(pngSource)
            }
        }

        val metrics = client.get("/metrics").bodyAsText()
        assertTrue(metrics.contains("snapshot_render_cache_entries 0"), metrics)
        assertTrue(metrics.contains("snapshot_render_cache_hits_total 0"), metrics)
    }

    // ---------- 渲染队列背压 ----------

    @Test
    fun `queue rejects new work when the waiting limit is reached`() = runBlocking {
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = RenderExecutor(maxConcurrentRenders = 1, maxQueueSize = 0, queueTimeoutMs = 5_000)
        try {
            val running = async(Dispatchers.Default) {
                executor.run {
                    occupied.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(occupied.await(5, TimeUnit.SECONDS))

            val error = assertFailsWith<RenderQueueFullException> { executor.run { 1 } }
            assertEquals(0, error.maxQueueSize)

            release.countDown()
            assertTrue(running.await())
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `queue wait times out instead of waiting forever`() = runBlocking {
        val occupied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = RenderExecutor(maxConcurrentRenders = 1, maxQueueSize = 8, queueTimeoutMs = 200)
        try {
            val running = async(Dispatchers.Default) {
                executor.run {
                    occupied.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(occupied.await(5, TimeUnit.SECONDS))

            val error = assertFailsWith<RenderQueueTimeoutException> { executor.run { 1 } }
            assertEquals(200L, error.queueTimeoutMs)

            release.countDown()
            assertTrue(running.await())
        } finally {
            release.countDown()
            executor.close()
        }
    }

    @Test
    fun `saturated queue answers queue full`() = testApplication {
        val png = Base64.getDecoder().decode(PNG_1X1)
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val imageServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        imageServer.createContext("/slow.png") { exchange ->
            downloadStarted.countDown()
            releaseDownload.await(15, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        imageServer.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            configure(
                overrides = {
                    put("snapshot.max-concurrent-renders", "1")
                    put("snapshot.max-render-queue", "0")
                    put("snapshot.render-cache.enabled", "false")
                },
            )
            application { configureImageCache(allowPrivateHostsOverride = true) }
            val slowSource = "<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" " +
                "url=\"http://127.0.0.1:${imageServer.address.port}/slow.png\"/></Snapshot>"

            val occupying = scope.async {
                client.post("/snapshot") {
                    header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                    setBody(slowSource)
                }
            }
            if (!downloadStarted.await(15, TimeUnit.SECONDS)) {
                val response = occupying.await()
                fail("首个渲染未触发图片下载: status=${response.status} body=${response.bodyAsText()}")
            }

            val rejected = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(pngSource)
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, rejected.status)
            assertTrue(rejected.bodyAsText().contains(ErrorCodes.QUEUE_FULL))

            releaseDownload.countDown()
            assertEquals(HttpStatusCode.OK, occupying.await().status)
        } finally {
            releaseDownload.countDown()
            scope.cancel()
            imageServer.stop(0)
        }
    }

    @Test
    fun `queue rejection error codes are part of the contract`() {
        assertTrue(ErrorCodes.QUEUE_FULL in ErrorCodes.ALL)
        assertTrue(ErrorCodes.QUEUE_TIMEOUT in ErrorCodes.ALL)
    }

    private companion object {
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
