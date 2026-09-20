package com.muedsa.snapshot.open

import com.muedsa.snapshot.parser.SnapshotElement
import com.muedsa.snapshot.tools.LimitedImageInputStream
import com.muedsa.snapshot.tools.NetworkImageCache
import io.ktor.server.application.Application
import org.jetbrains.skia.Image
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局内存图片缓存（按访问顺序的 LRU）。
 *
 * 淘汰与清空只丢弃引用、不调用 `Image.close()`：缓存里的图片可能正被另一个并发渲染绘制，
 * 关闭它会让对方拿到已释放的本地对象。Skiko 的 `Managed` 在对象不可达后由 Reference Cleaner
 * 释放本地内存，因此丢弃引用即可安全回收。
 */
internal class MemoryImageCache(
    private val limit: Int,
    private val maxBytes: Long,
) : LinkedHashMap<String, Image>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>?): Boolean = size > limit

    fun putImage(url: String, image: Image): Boolean {
        if (image.imageInfo.computeMinByteSize().toLong() > maxBytes) return false
        super.put(url, image)
        while (size > limit || totalBytes() > maxBytes) {
            val eldestKey = keys.firstOrNull() ?: break
            if (eldestKey == url) break
            remove(eldestKey)
        }
        return containsKey(url)
    }

    fun totalBytes(): Long = values.sumOf { it.imageInfo.computeMinByteSize().toLong() }
}

/**
 * 同一图片 URL 的并发加载去重：多个渲染同时请求同一地址时只下载与解码一次，
 * 其余调用等待同一个结果，避免重复网络请求与重复解码。
 */
internal object SharedImageLoader {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Image>>()

    fun load(url: String, loader: () -> Image): Image {
        while (true) {
            inFlight[url]?.let { pending ->
                return try {
                    pending.join()
                } catch (error: CompletionException) {
                    throw error.cause ?: error
                }
            }
            val pending = CompletableFuture<Image>()
            if (inFlight.putIfAbsent(url, pending) != null) continue
            try {
                val image = loader()
                pending.complete(image)
                return image
            } catch (error: Throwable) {
                pending.completeExceptionally(error)
                throw error
            } finally {
                inFlight.remove(url, pending)
            }
        }
    }
}

internal class LimitedNetworkImageCache(
    private val memoryCache: MemoryImageCache,
    private val maxImageNum: Int,
    private val maxSingleImageSize: Int,
    private val maxImageWidth: Int,
    private val maxImageHeight: Int,
    private val maxImagePixels: Long,
    private val allowPrivateHosts: Boolean,
) : NetworkImageCache {
    override val name: String = "OpenSnapshotLimitedNetworkImageCache"
    private var requestCount = 0

    @Synchronized
    override fun getImage(url: String, noCache: Boolean): Image {
        if (!noCache) {
            val cached = synchronized(memoryCache) { memoryCache[url] }
            if (cached != null) {
                Metrics.imageCacheHits.inc()
                return cached
            }
            Metrics.imageCacheMisses.inc()
        }
        check(requestCount < maxImageNum) {
            "Exceeded maximum number [$maxImageNum] of image http requests"
        }
        requestCount++
        val image = SharedImageLoader.load(url) { loadImage(url) }
        if (!noCache) synchronized(memoryCache) { memoryCache.putImage(url, image) }
        return image
    }

    private fun loadImage(url: String): Image {
        val encoded = try {
            Metrics.imageDownloads.inc()
            download(url)
        } catch (error: Throwable) {
            Metrics.imageDownloadFailures.inc()
            throw error
        }
        val image = Image.makeFromEncoded(encoded)
        if (image.width > maxImageWidth || image.height > maxImageHeight) {
            image.close()
            throw IllegalArgumentException(
                "Image dimensions ${image.width}x${image.height} exceed maximum ${maxImageWidth}x$maxImageHeight"
            )
        }
        if (image.width.toLong() * image.height.toLong() > maxImagePixels) {
            image.close()
            throw IllegalArgumentException(
                "Image pixel count ${image.width.toLong() * image.height.toLong()} exceeds maximum $maxImagePixels"
            )
        }
        return image
    }

    override fun clearAll() = synchronized(memoryCache) { memoryCache.clear() }
    override fun clearImage(url: String) {
        synchronized(memoryCache) { memoryCache.remove(url) }
    }
    override fun count(): Int = synchronized(memoryCache) { memoryCache.size }
    override fun size(): Int = synchronized(memoryCache) {
        memoryCache.totalBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun download(url: String): ByteArray {
        val uri = URI(url)
        require(uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) {
            "Only http and https image URLs are allowed"
        }
        val host = requireNotNull(uri.host) { "Image URL must include a host" }
        if (!allowPrivateHosts) {
            require(!isPrivateHost(host)) {
                "Private or local image hosts are not allowed"
            }
        }
        val connection = uri.toURL().openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = false
                require(connection.responseCode in 200..299) {
                    "Image server returned HTTP ${connection.responseCode}"
                }
            }
            val contentLength = connection.contentLengthLong
            require(contentLength < 0 || contentLength <= maxSingleImageSize.toLong()) {
                "Image response size $contentLength exceeds maximum $maxSingleImageSize bytes"
            }
            connection.getInputStream().use {
                LimitedImageInputStream(it, maxSingleImageSize).readBytes()
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized == "metadata.google.internal" || normalized == "metadata" ||
            normalized == "instance-data") return true
        return InetAddress.getAllByName(normalized).any(::isBlockedImageAddress)
    }
}

internal fun isBlockedImageAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return true
    }

    val bytes = address.address.map(Byte::toInt).map { it and 0xff }
    return when (bytes.size) {
        4 -> isBlockedIpv4(bytes)
        16 -> isBlockedIpv6(bytes)
        else -> true
    }
}

private fun isBlockedIpv4(bytes: List<Int>): Boolean {
    val (a, b, c) = bytes
    return a == 0 ||
        a == 10 ||
        (a == 100 && b in 64..127) ||
        a == 127 ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 0 && c == 0) ||
        (a == 192 && b == 0 && c == 2) ||
        (a == 192 && b == 88 && c == 99) ||
        (a == 192 && b == 168) ||
        (a == 198 && b in 18..19) ||
        (a == 198 && b == 51 && c == 100) ||
        (a == 203 && b == 0 && c == 113) ||
        a >= 224
}

private fun isBlockedIpv6(bytes: List<Int>): Boolean {
    if ((bytes[0] and 0xfe) == 0xfc || bytes[0] == 0xff) return true
    if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return true

    val isIpv4Mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
    val isIpv4Compatible = bytes.take(12).all { it == 0 }
    if (isIpv4Mapped || isIpv4Compatible) {
        return isBlockedIpv4(bytes.takeLast(4))
    }

    val isNat64WellKnown = bytes.take(12) == listOf(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)
    if (isNat64WellKnown && isBlockedIpv4(bytes.takeLast(4))) return true

    val isSixToFour = bytes[0] == 0x20 && bytes[1] == 0x02
    if (isSixToFour) {
        return isBlockedIpv4(bytes.subList(2, 6))
    }

    return false
}

private var memoryImageCache: MemoryImageCache? = null

fun Application.configureImageCache(allowPrivateHostsOverride: Boolean? = null) {
    val config = environment.config.config("snapshot.image")
    val maxImageNum = config.propertyOrNull("max-image-num-once")?.getString()?.toIntOrNull() ?: 10
    val maxSingleImageSize = config.propertyOrNull("max-single-image-size")?.getString()?.toIntOrNull() ?: 5 * 1024 * 1024
    val memoryCacheLimit = config.propertyOrNull("memory-cache-num-limit")?.getString()?.toIntOrNull() ?: 100
    val maxCacheBytes = config.propertyOrNull("max-cache-bytes")?.getString()?.toLongOrNull() ?: 256L * 1024 * 1024
    val maxImageWidth = config.propertyOrNull("max-image-width")?.getString()?.toIntOrNull() ?: 4096
    val maxImageHeight = config.propertyOrNull("max-image-height")?.getString()?.toIntOrNull() ?: 4096
    val maxImagePixels = config.propertyOrNull("max-image-pixels")?.getString()?.toLongOrNull() ?: 16_777_216L
    val allowPrivateHosts = allowPrivateHostsOverride ?: (
        config.propertyOrNull("allow-private-hosts")?.getString()?.toBooleanStrictOrNull() ?: false
        )
    require(maxImageNum > 0) { "snapshot.image.max-image-num-once must be positive" }
    require(maxSingleImageSize > 0) { "snapshot.image.max-single-image-size must be positive" }
    require(memoryCacheLimit > 0) { "snapshot.image.memory-cache-num-limit must be positive" }
    require(maxCacheBytes > 0) { "snapshot.image.max-cache-bytes must be positive" }
    require(maxImageWidth > 0 && maxImageHeight > 0 && maxImagePixels > 0) {
        "snapshot.image dimensions and pixel limits must be positive"
    }

    val cache = MemoryImageCache(memoryCacheLimit, maxCacheBytes)
    memoryImageCache = cache
    SnapshotElement.NETWORK_IMAGE_CACHE_BUILDER = {
        LimitedNetworkImageCache(
            cache,
            maxImageNum,
            maxSingleImageSize,
            maxImageWidth,
            maxImageHeight,
            maxImagePixels,
            allowPrivateHosts,
        )
    }
}

fun clearImageCache() {
    memoryImageCache?.let { synchronized(it) { it.clear() } }
}

/** 网络图片缓存是否已经初始化，供就绪检查使用。 */
internal fun isImageCacheConfigured(): Boolean = memoryImageCache != null

fun imageCacheInfo(): Pair<Int, Int> {
    val cache = memoryImageCache ?: return 0 to 0
    return synchronized(cache) {
        cache.size to cache.totalBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
