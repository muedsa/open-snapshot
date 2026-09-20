package com.muedsa.snapshot

import com.muedsa.snapshot.open.LimitedNetworkImageCache
import com.muedsa.snapshot.open.MemoryImageCache
import com.muedsa.snapshot.open.RenderExecutor
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderAndCacheTest {

    @Test
    fun `render executor runs work on dedicated render threads`() = runBlocking {
        RenderExecutor(2).use { executor ->
            val threadName = executor.run { Thread.currentThread().name }

            assertTrue(threadName.startsWith("snapshot-render-"), threadName)
        }
    }

    @Test
    fun `render executor allows as many concurrent renders as its pool size`() = runBlocking {
        RenderExecutor(2).use { executor ->
            val bothInside = CountDownLatch(2)
            val release = CountDownLatch(1)

            val renders = (1..2).map {
                async(Dispatchers.Default) {
                    executor.run {
                        bothInside.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }

            assertTrue(bothInside.await(5, TimeUnit.SECONDS), "两个渲染槽位应可同时使用")
            release.countDown()
            renders.forEach { it.await() }
        }
    }

    @Test
    fun `render executor holds back renders beyond its pool size`() = runBlocking {
        RenderExecutor(1).use { executor ->
            val firstInside = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)

            val first = async(Dispatchers.Default) {
                executor.run {
                    firstInside.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(firstInside.await(5, TimeUnit.SECONDS))

            val second = async(Dispatchers.Default) { executor.run { secondStarted.countDown() } }
            assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS), "槽位被占用时不应有第二个渲染进入")

            releaseFirst.countDown()
            first.await()
            second.await()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cache eviction keeps previously returned images usable`() {
        val cache = MemoryImageCache(limit = 1, maxBytes = 64L * 1024 * 1024)
        val first = testImage()
        val second = testImage()

        assertTrue(cache.putImage("first", first))
        assertTrue(cache.putImage("second", second))

        assertEquals(setOf("second"), cache.keys)
        assertFalse(first.isClosed, "淘汰不应关闭可能仍被并发渲染使用的图片")
        assertEquals(1, first.imageInfo.width)
        assertFalse(second.isClosed)
    }

    @Test
    fun `cache clear keeps previously returned images usable`() {
        val cache = MemoryImageCache(limit = 10, maxBytes = 64L * 1024 * 1024)
        val image = testImage()
        cache.putImage("only", image)

        cache.clear()

        assertEquals(0, cache.size)
        assertFalse(image.isClosed)
        assertEquals(1, image.imageInfo.height)
    }

    @Test
    fun `concurrent loads of the same image download once`() {
        val png = Base64.getDecoder().decode(PNG_1X1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image.png") { exchange ->
            requests.incrementAndGet()
            Thread.sleep(300)
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()

        val pool = Executors.newFixedThreadPool(4)
        try {
            val url = "http://127.0.0.1:${server.address.port}/image.png"
            val sharedCache = MemoryImageCache(limit = 10, maxBytes = 64L * 1024 * 1024)
            val caches = List(4) { perRequestCache(sharedCache) }
            val startGate = CountDownLatch(1)

            val downloads = caches.mapIndexed { index, cache ->
                pool.submit(Callable {
                    startGate.await()
                    cache.getImage(url)
                })
            }
            startGate.countDown()
            val images = downloads.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, requests.get(), "同一地址的并发请求应只下载一次")
            assertEquals(1, images.distinct().size, "并发调用应共享同一个解码结果")
        } finally {
            pool.shutdownNow()
            server.stop(0)
        }
    }

    private fun perRequestCache(sharedCache: MemoryImageCache) = LimitedNetworkImageCache(
        memoryCache = sharedCache,
        maxImageNum = 10,
        maxSingleImageSize = 5 * 1024 * 1024,
        maxImageWidth = 4096,
        maxImageHeight = 4096,
        maxImagePixels = 16_777_216L,
        allowPrivateHosts = true,
    )

    private fun testImage(): Image = Image.makeFromEncoded(Base64.getDecoder().decode(PNG_1X1))

    private companion object {
        const val PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
