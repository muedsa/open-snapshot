package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.ImageLoadException
import com.muedsa.snapshot.open.LimitedNetworkImageCache
import com.muedsa.snapshot.open.MemoryImageCache
import com.muedsa.snapshot.open.configureImageCache
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 图片缓存 TTL、ETag 条件请求与下载重试。
 */
class ImageRevalidationTest {

    @Test
    fun `expired entry is revalidated with etag and reused on 304`() {
        ImageServer(png = PNG_1X1_BYTES, etag = "\"v1\"").use { server ->
            var now = 1_000L
            val cache = MemoryImageCache(limit = 4, maxBytes = 1 shl 20, ttlMs = 100, clock = { now })
            val perRequest = perRequestCache(cache)
            val url = server.url

            val first = perRequest.getImage(url)
            assertEquals(1, server.requests.get())

            now += 101
            val second = perRequest.getImage(url)

            assertEquals(2, server.requests.get(), "TTL 过期后应发起一次条件请求")
            assertEquals("\"v1\"", server.conditionalHeaders[1], "条件请求应带 If-None-Match")
            assertSame(first, second, "304 时应复用缓存中的图片")
        }
    }

    @Test
    fun `expired entry without etag is downloaded again`() {
        ImageServer(png = PNG_1X1_BYTES, etag = null).use { server ->
            var now = 1_000L
            val cache = MemoryImageCache(limit = 4, maxBytes = 1 shl 20, ttlMs = 100, clock = { now })
            val perRequest = perRequestCache(cache)

            val first = perRequest.getImage(server.url)
            now += 101
            val second = perRequest.getImage(server.url)

            assertEquals(2, server.requests.get())
            assertNull(server.conditionalHeaders[1], "没有 ETag 时不应发送条件请求头")
            assertNotSame(first, second, "无 ETag 时重新下载会得到新的图片对象")
        }
    }

    @Test
    fun `entry stays cached while ttl is not exceeded`() {
        ImageServer(png = PNG_1X1_BYTES, etag = "\"v1\"").use { server ->
            var now = 1_000L
            val cache = MemoryImageCache(limit = 4, maxBytes = 1 shl 20, ttlMs = 10_000, clock = { now })
            val perRequest = perRequestCache(cache)
            val url = server.url

            val first = perRequest.getImage(url)
            now += 5_000
            val second = perRequest.getImage(url)

            assertEquals(1, server.requests.get(), "TTL 内不应重新请求")
            assertSame(first, second)
        }
    }

    @Test
    fun `transient failures are retried`() {
        ImageServer(png = PNG_1X1_BYTES, etag = null, failFirstStatus = 500).use { server ->
            val cache = MemoryImageCache(limit = 4, maxBytes = 1 shl 20)
            val perRequest = perRequestCache(cache, maxRetries = 1, retryBackoffMs = 10)

            val image = perRequest.getImage(server.url)

            assertEquals(1, image.width)
            assertEquals(2, server.requests.get(), "首次 5xx 后应重试一次")
        }
    }

    @Test
    fun `client errors are not retried`() {
        ImageServer(png = PNG_1X1_BYTES, etag = null, alwaysStatus = 404).use { server ->
            val cache = MemoryImageCache(limit = 4, maxBytes = 1 shl 20)
            val perRequest = perRequestCache(cache, maxRetries = 2, retryBackoffMs = 10)

            assertFailsWith<ImageLoadException> { perRequest.getImage(server.url) }
            assertEquals(1, server.requests.get(), "4xx 不应重试")
        }
    }

    @Test
    fun `read timeouts are transient and retried`() {
        // 服务端接受连接后不返回任何内容，触发读取超时。
        ImageServer(png = PNG_1X1_BYTES, etag = null, responseDelayMs = 2_000).use { server ->
            val cache = MemoryImageCache(limit = 4, maxBytes = 1 shl 20)
            val perRequest = perRequestCache(cache, maxRetries = 1, retryBackoffMs = 10, readTimeoutMs = 200)

            val error = assertFailsWith<ImageLoadException> { perRequest.getImage(server.url) }

            assertTrue(error.transient, "读取超时应视为瞬时故障: ${error.message}")
            assertEquals(2, server.requests.get(), "瞬时故障应重试一次")
        }
    }

    @Test
    fun `read timeouts map to image load error`() = testApplication {
        ImageServer(png = PNG_1X1_BYTES, etag = null, responseDelayMs = 2_000).use { server ->
            configure(overrides = { put("snapshot.image.read-timeout-ms", "200") })
            application { configureImageCache(allowPrivateHostsOverride = true) }

            val response = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" url=\"${server.url}\"/></Snapshot>")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains(ErrorCodes.IMAGE_LOAD_ERROR), response.bodyAsText())
        }
    }

    @Test
    fun `evictions are counted`() {
        val cache = MemoryImageCache(limit = 1, maxBytes = 1 shl 20)

        cache.putImage("a", testImage())
        cache.putImage("b", testImage())

        assertEquals(1L, cache.evictionCount())
        assertEquals(1, cache.size)
    }

    @Test
    fun `image load failures map to image load error`() = testApplication {
        ImageServer(png = PNG_1X1_BYTES, etag = null, alwaysStatus = 500).use { server ->
            configure()
            application { configureImageCache(allowPrivateHostsOverride = true) }

            val response = client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody("<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" url=\"${server.url}\"/></Snapshot>")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains(ErrorCodes.IMAGE_LOAD_ERROR), response.bodyAsText())
        }
    }

    @Test
    fun `snapshot rendering still works through the cached image`() = testApplication {
        ImageServer(png = PNG_1X1_BYTES, etag = "\"v1\"").use { server ->
            configure()
            application { configureImageCache(allowPrivateHostsOverride = true) }

            val source = "<Snapshot type=\"png\"><Image width=\"1\" height=\"1\" url=\"${server.url}\"/></Snapshot>"
            repeat(2) {
                val response = client.post("/snapshot") {
                    header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                    setBody(source)
                }
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.body<ByteArray>().isNotEmpty())
            }

            assertEquals(1, server.requests.get(), "第二次渲染应命中图片缓存")
        }
    }

    private fun perRequestCache(
        sharedCache: MemoryImageCache,
        maxRetries: Int = 0,
        retryBackoffMs: Long = 0,
        readTimeoutMs: Int = 10_000,
    ) = LimitedNetworkImageCache(
        memoryCache = sharedCache,
        maxImageNum = 10,
        maxSingleImageSize = 5 * 1024 * 1024,
        maxImageWidth = 4096,
        maxImageHeight = 4096,
        maxImagePixels = 16_777_216L,
        allowPrivateHosts = true,
        readTimeoutMs = readTimeoutMs,
        maxRetries = maxRetries,
        retryBackoffMs = retryBackoffMs,
    )

    private fun testImage() = org.jetbrains.skia.Image.makeFromEncoded(PNG_1X1_BYTES)

    /** 支持 ETag / 304 与故障注入的本地图片服务。 */
    private class ImageServer(
        private val png: ByteArray,
        private val etag: String?,
        private val failFirstStatus: Int? = null,
        private val alwaysStatus: Int? = null,
        private val responseDelayMs: Long = 0,
    ) : AutoCloseable {
        val requests = AtomicInteger()
        val conditionalHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        private var pendingFailure: Int? = failFirstStatus
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val url: String get() = "http://127.0.0.1:${server.address.port}/image.png"

        init {
            server.createContext("/image.png") { exchange ->
                requests.incrementAndGet()
                val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
                conditionalHeaders.add(ifNoneMatch)

                val injected = alwaysStatus ?: pendingFailure?.also { pendingFailure = null }
                if (injected != null) {
                    exchange.sendResponseHeaders(injected, -1)
                    exchange.close()
                    return@createContext
                }

                if (etag != null && ifNoneMatch == etag) {
                    exchange.sendResponseHeaders(304, -1)
                    exchange.close()
                    return@createContext
                }

                if (responseDelayMs > 0) {
                    Thread.sleep(responseDelayMs)
                }
                if (etag != null) {
                    exchange.responseHeaders.add("ETag", etag)
                }
                exchange.sendResponseHeaders(200, png.size.toLong())
                exchange.responseBody.use { it.write(png) }
            }
            // 并发处理请求：否则慢响应会串行阻塞重试请求，统计不到第二次请求。
            server.executor = Executors.newFixedThreadPool(4)
            server.start()
        }

        override fun close() {
            server.stop(0)
        }
    }

    private companion object {
        val PNG_1X1_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
