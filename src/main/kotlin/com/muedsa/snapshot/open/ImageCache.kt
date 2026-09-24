package com.muedsa.snapshot.open

import com.muedsa.snapshot.parser.SnapshotElement
import com.muedsa.snapshot.parser.image.DataUriImageDecoder
import com.muedsa.snapshot.tools.LimitedImageInputStream
import com.muedsa.snapshot.tools.NetworkImageCache
import io.ktor.server.application.Application
import org.jetbrains.skia.Image
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/** 图片加载失败（协议、地址、HTTP 状态、大小、超时、解码等）。 */
internal class ImageLoadException(
    message: String,
    cause: Throwable? = null,
    /** 瞬时故障（5xx、超时、IO）可以重试；4xx 等确定性失败不重试。 */
    val transient: Boolean = false,
) : RuntimeException(message, cause)

/**
 * 单次渲染共享的图片资源预算。
 *
 * URL 图片（包括缓存命中）与 Data URI 图片都必须占用同一份数量和解码像素预算，
 * 避免混合两种来源绕过限制，也避免高压缩比图片把本地内存放大到不可控规模。
 */
internal class ImageResourceBudget(
    private val maxImageCount: Int,
    private val maxTotalPixels: Long,
) {
    private var imageCount = 0
    private var totalPixels = 0L

    @Synchronized
    fun claimImageSource() {
        if (imageCount >= maxImageCount) {
            throw ImageLoadException("Exceeded maximum number [$maxImageCount] of images per render")
        }
        imageCount++
    }

    @Synchronized
    fun claimDecodedImage(image: Image) {
        val pixels = image.width.toLong() * image.height.toLong()
        if (pixels > maxTotalPixels - totalPixels) {
            throw ImageLoadException("Total decoded image pixels exceed maximum $maxTotalPixels per render")
        }
        totalPixels += pixels
    }
}

/**
 * 面向开放服务的 Data URI 解码器：限制格式、编码字节、尺寸、单图像素和每次渲染总预算。
 */
internal class LimitedDataUriImageDecoder(
    private val maxEncodedBytes: Int,
    private val maxImageWidth: Int,
    private val maxImageHeight: Int,
    private val maxImagePixels: Long,
    internal val budget: ImageResourceBudget,
) : DataUriImageDecoder, AutoCloseable {
    private val decodedImages = mutableListOf<Image>()

    override fun decode(dataUri: String): Image {
        budget.claimImageSource()

        val separator = dataUri.indexOf(',')
        val mediaType = if (separator > 0) dataUri.substring(0, separator).lowercase(Locale.ROOT) else ""
        val format = SUPPORTED_HEADERS[mediaType]
            ?: throw ImageLoadException("Expected a PNG, JPEG or WebP Base64 image Data URI")
        val encoded = dataUri.substring(separator + 1)
        if (encoded.isEmpty()) {
            throw ImageLoadException("Image Data URI has no Base64 payload")
        }

        // Base64 最多把 3 字节展开为 4 字符；先限制字符串长度，避免 decode() 预分配过大数组。
        val maxEncodedLength = ((maxEncodedBytes.toLong() + 2L) / 3L) * 4L
        if (encoded.length.toLong() > maxEncodedLength) {
            throw ImageLoadException("Data URI image exceeds maximum $maxEncodedBytes encoded bytes")
        }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw ImageLoadException("Image Data URI contains invalid Base64 data", error)
        }
        if (bytes.size > maxEncodedBytes) {
            throw ImageLoadException("Data URI image exceeds maximum $maxEncodedBytes encoded bytes")
        }
        if (!format.matches(bytes)) {
            throw ImageLoadException("Image Data URI media type does not match its encoded image format")
        }

        val image = try {
            Image.makeFromEncoded(bytes)
        } catch (error: Exception) {
            throw ImageLoadException("Data URI contains an invalid or unsupported image", error)
        }
        try {
            validateDecodedImage(image, maxImageWidth, maxImageHeight, maxImagePixels)
            budget.claimDecodedImage(image)
            decodedImages += image
            return image
        } catch (error: Throwable) {
            image.close()
            throw error
        }
    }

    /** Data URI 图片不进入全局缓存，渲染结束后立即释放对应的 Skia 本地内存。 */
    override fun close() {
        decodedImages.forEach { image ->
            if (!image.isClosed) image.close()
        }
        decodedImages.clear()
    }

    private enum class ImageFormat {
        PNG {
            override fun matches(bytes: ByteArray): Boolean =
                bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        },
        JPEG {
            override fun matches(bytes: ByteArray): Boolean =
                bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        },
        WEBP {
            override fun matches(bytes: ByteArray): Boolean =
                bytes.size >= 12 &&
                    bytes.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
                    bytes.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
        },
        ;

        abstract fun matches(bytes: ByteArray): Boolean
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val RIFF_SIGNATURE = "RIFF".encodeToByteArray()
        val WEBP_SIGNATURE = "WEBP".encodeToByteArray()
        val SUPPORTED_HEADERS = mapOf(
            "data:image/png;base64" to ImageFormat.PNG,
            "data:image/jpeg;base64" to ImageFormat.JPEG,
            "data:image/webp;base64" to ImageFormat.WEBP,
        )
    }
}

private fun validateDecodedImage(
    image: Image,
    maxImageWidth: Int,
    maxImageHeight: Int,
    maxImagePixels: Long,
) {
    if (image.width > maxImageWidth || image.height > maxImageHeight) {
        throw ImageLoadException(
            "Image dimensions ${image.width}x${image.height} exceed maximum ${maxImageWidth}x$maxImageHeight"
        )
    }
    val pixels = image.width.toLong() * image.height.toLong()
    if (pixels > maxImagePixels) {
        throw ImageLoadException("Image pixel count $pixels exceeds maximum $maxImagePixels")
    }
}

/** 条件请求的两种结果，用于指标标签。 */
internal const val REVALIDATION_NOT_MODIFIED = "not_modified"
internal const val REVALIDATION_UPDATED = "updated"

/**
 * 全局内存图片缓存（按访问顺序的 LRU，带 TTL 与 ETag）。
 *
 * 淘汰与清空只丢弃引用、不调用 `Image.close()`：缓存里的图片可能正被另一个并发渲染绘制，
 * 关闭它会让对方拿到已释放的本地对象。Skiko 的 `Managed` 在对象不可达后由 Reference Cleaner
 * 释放本地内存，因此丢弃引用即可安全回收。
 *
 * `ttlMs` 为 0 表示永不过期；否则过期条目会保留并使用 `ETag` 做条件请求复用。
 */
internal class MemoryImageCache(
    private val limit: Int,
    private val maxBytes: Long,
    private val ttlMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal class Entry(
        val image: Image,
        val etag: String?,
        val sizeBytes: Long,
        var expiresAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes = 0L
    private var evictions = 0L
    private var expirations = 0L

    val keys: Set<String> get() = synchronized(this) { entries.keys.toSet() }

    val size: Int get() = synchronized(this) { entries.size }

    /** 命中未过期的条目；过期条目计入过期计数并返回 null。 */
    fun get(url: String): Entry? = synchronized(this) {
        val entry = entries[url] ?: return null
        if (isExpired(entry)) {
            expirations++
            return null
        }
        entry
    }

    /** 取可能已过期的条目，用于条件请求复用。 */
    fun getStale(url: String): Entry? = synchronized(this) { entries[url] }

    /** 304 之后刷新过期时间；条目已被淘汰时返回 false。 */
    fun refresh(url: String): Boolean = synchronized(this) {
        val entry = entries[url] ?: return false
        if (ttlMs > 0) entry.expiresAtMillis = clock() + ttlMs
        true
    }

    fun putImage(url: String, image: Image, etag: String? = null): Boolean {
        val sizeBytes = image.imageInfo.computeMinByteSize().toLong()
        if (sizeBytes > maxBytes) return false
        synchronized(this) {
            entries.remove(url)?.let { totalBytes -= it.sizeBytes }
            entries[url] = Entry(
                image = image,
                etag = etag,
                sizeBytes = sizeBytes,
                expiresAtMillis = if (ttlMs > 0) clock() + ttlMs else Long.MAX_VALUE,
            )
            totalBytes += sizeBytes
            while (entries.size > limit || totalBytes > maxBytes) {
                val eldestKey = entries.keys.firstOrNull() ?: break
                if (eldestKey == url && entries.size == 1) break
                val removed = entries.remove(eldestKey) ?: break
                totalBytes -= removed.sizeBytes
                evictions++
            }
            return entries.containsKey(url)
        }
    }

    fun remove(url: String): Entry? = synchronized(this) {
        entries.remove(url)?.also { totalBytes -= it.sizeBytes }
    }

    fun clear() = synchronized(this) {
        entries.clear()
        totalBytes = 0
    }

    fun totalBytes(): Long = synchronized(this) { totalBytes }

    fun evictionCount(): Long = synchronized(this) { evictions }

    fun expirationCount(): Long = synchronized(this) { expirations }

    private fun isExpired(entry: Entry): Boolean = ttlMs > 0 && entry.expiresAtMillis <= clock()
}

/** 一次图片获取的结果：解码后的图片、响应 ETag，以及是否复用了缓存中的旧图片。 */
internal class LoadedImage(
    val image: Image,
    val etag: String?,
    val reused: Boolean,
)

/** 下载结果：拿到新字节，或服务端返回 304。 */
internal sealed interface DownloadResult {
    data class Downloaded(val bytes: ByteArray, val etag: String?) : DownloadResult

    data object NotModified : DownloadResult
}

/**
 * 同一图片 URL 的并发加载去重：多个渲染同时请求同一地址时只下载与解码一次，
 * 其余调用等待同一个结果，避免重复网络请求与重复解码。
 */
internal object SharedImageLoader {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<LoadedImage>>()

    fun load(url: String, loader: () -> LoadedImage): LoadedImage {
        while (true) {
            inFlight[url]?.let { pending ->
                return try {
                    pending.join()
                } catch (error: CompletionException) {
                    throw error.cause ?: error
                }
            }
            val pending = CompletableFuture<LoadedImage>()
            if (inFlight.putIfAbsent(url, pending) != null) continue
            try {
                val loaded = loader()
                pending.complete(loaded)
                return loaded
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
    private val imageBudget: ImageResourceBudget = ImageResourceBudget(maxImageNum, maxImagePixels),
    private val allowPrivateHosts: Boolean,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxRetries: Int = 0,
    private val retryBackoffMs: Long = 0,
) : NetworkImageCache {
    override val name: String = "OpenSnapshotLimitedNetworkImageCache"

    @Synchronized
    override fun getImage(url: String, noCache: Boolean): Image {
        // 缓存命中同样代表文档中的一个图片节点，必须计入数量和总解码像素预算。
        imageBudget.claimImageSource()
        val stale = if (!noCache) {
            val fresh = memoryCache.get(url)
            if (fresh != null) {
                Metrics.imageCacheHits.inc()
                imageBudget.claimDecodedImage(fresh.image)
                return fresh.image
            }
            Metrics.imageCacheMisses.inc()
            // 过期条目保留 ETag，用于条件请求；没有条目时返回 null。
            memoryCache.getStale(url)
        } else {
            null
        }

        // 记录本请求在图片获取上的墙钟时间：包含等待同一 URL 的并发下载结果。
        val fetchStartedAt = System.nanoTime()
        var downloaded = false
        try {
            val loaded = SharedImageLoader.load(url) { loadImage(url, stale) }
            downloaded = !loaded.reused
            imageBudget.claimDecodedImage(loaded.image)
            if (!noCache) {
                if (loaded.reused) {
                    // 304：刷新过期时间；条目若已被淘汰则重新写回。
                    if (!memoryCache.refresh(url)) {
                        memoryCache.putImage(url, loaded.image, loaded.etag)
                    }
                } else {
                    memoryCache.putImage(url, loaded.image, loaded.etag)
                }
            }
            return loaded.image
        } finally {
            CurrentRenderStats.get()?.addImageFetch(System.nanoTime() - fetchStartedAt, downloaded)
        }
    }

    private fun loadImage(url: String, stale: MemoryImageCache.Entry?): LoadedImage {
        val download = try {
            downloadWithRetry(url, stale?.etag)
        } catch (error: Throwable) {
            Metrics.imageDownloadFailures.inc()
            throw error
        }
        if (download is DownloadResult.NotModified) {
            val entry = stale
            if (entry != null) {
                Metrics.imageRevalidated(REVALIDATION_NOT_MODIFIED)
                return LoadedImage(entry.image, entry.etag, reused = true)
            }
            // 理论上不可达（服务端只在带 ETag 时返回 304），退化为重新下载。
        }

        val downloaded = download as DownloadResult.Downloaded
        val image = try {
            Image.makeFromEncoded(downloaded.bytes)
        } catch (error: Exception) {
            throw ImageLoadException("Downloaded data is not a valid supported image", error)
        }
        try {
            validateDecodedImage(image, maxImageWidth, maxImageHeight, maxImagePixels)
        } catch (error: Throwable) {
            image.close()
            throw error
        }
        if (stale != null) {
            Metrics.imageRevalidated(REVALIDATION_UPDATED)
        }
        return LoadedImage(image, downloaded.etag, reused = false)
    }

    override fun clearAll() = memoryCache.clear()
    override fun clearImage(url: String) {
        memoryCache.remove(url)
    }
    override fun count(): Int = memoryCache.size
    override fun size(): Int = memoryCache.totalBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** 下载并在瞬时故障时按退避重试；4xx 等确定性失败直接抛出。 */
    private fun downloadWithRetry(url: String, etag: String?): DownloadResult {
        var attempt = 0
        while (true) {
            try {
                Metrics.imageDownloads.inc()
                return download(url, etag)
            } catch (error: ImageLoadException) {
                if (!error.transient || attempt >= maxRetries) throw error
                attempt++
                Metrics.imageRetries.inc()
                if (retryBackoffMs > 0) {
                    try {
                        Thread.sleep(retryBackoffMs * attempt)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw error
                    }
                }
            }
        }
    }

    private fun download(url: String, etag: String?): DownloadResult {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            throw ImageLoadException("Image URL is not a valid URI: $url", error)
        }
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
            throw ImageLoadException("Only http and https image URLs are allowed")
        }
        val host = uri.host?.takeIf(String::isNotBlank)
            ?: throw ImageLoadException("Image URL must include a host: $url")
        if (!allowPrivateHosts && isPrivateHost(host)) {
            throw ImageLoadException("Private or local image hosts are not allowed: $host")
        }

        val connection = try {
            uri.toURL().openConnection().apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }
        } catch (error: Exception) {
            throw ImageLoadException("Failed to open image URL $url: ${error.message}", error, transient = true)
        }

        return try {
            try {
                downloadOnce(connection, url, etag)
            } catch (error: ImageLoadException) {
                throw error
            } catch (error: Exception) {
                // 连接建立后仍可能超时或中断（例如读取响应状态），统一视为瞬时故障以便重试。
                throw ImageLoadException("Failed to load image from $url: ${error.message}", error, transient = true)
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    private fun downloadOnce(connection: java.net.URLConnection, url: String, etag: String?): DownloadResult {
        var responseEtag: String? = null
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = false
            if (etag != null) {
                connection.setRequestProperty("If-None-Match", etag)
            }
            val status = connection.responseCode
            if (status == HTTP_NOT_MODIFIED) {
                return DownloadResult.NotModified
            }
            if (status !in 200..299) {
                throw ImageLoadException(
                    message = "Image server returned HTTP $status",
                    transient = status >= 500 || status == 408 || status == 429,
                )
            }
            responseEtag = connection.getHeaderField("ETag")
        }
        val contentLength = connection.contentLengthLong
        if (contentLength >= 0 && contentLength > maxSingleImageSize.toLong()) {
            throw ImageLoadException(
                "Image response size $contentLength exceeds maximum $maxSingleImageSize bytes"
            )
        }
        val bytes = try {
            connection.getInputStream().use {
                LimitedImageInputStream(it, maxSingleImageSize).readBytes()
            }
        } catch (error: Exception) {
            throw ImageLoadException("Failed to read image from $url: ${error.message}", error, transient = true)
        }
        return DownloadResult.Downloaded(bytes, responseEtag)
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized == "metadata.google.internal" || normalized == "metadata" ||
            normalized == "instance-data") return true
        return runCatching { InetAddress.getAllByName(normalized).any(::isBlockedImageAddress) }
            .getOrElse { true }
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
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
    val limits = snapshotConfig().image
    val allowPrivateHosts = allowPrivateHostsOverride ?: limits.allowPrivateHosts
    val cache = MemoryImageCache(
        limit = limits.memoryCacheNumLimit,
        maxBytes = limits.maxCacheBytes,
        ttlMs = limits.cacheTtlMs,
    )
    memoryImageCache = cache
    SnapshotElement.NETWORK_IMAGE_CACHE_BUILDER = {
        LimitedNetworkImageCache(
            memoryCache = cache,
            maxImageNum = limits.maxImageNumOnce,
            maxSingleImageSize = limits.maxSingleImageSize,
            maxImageWidth = limits.maxImageWidth,
            maxImageHeight = limits.maxImageHeight,
            maxImagePixels = limits.maxImagePixels,
            imageBudget = (it.dataUriImageDecoder as? LimitedDataUriImageDecoder)?.budget
                ?: ImageResourceBudget(limits.maxImageNumOnce, limits.maxTotalImagePixels),
            allowPrivateHosts = allowPrivateHosts,
            connectTimeoutMs = limits.connectTimeoutMs,
            readTimeoutMs = limits.readTimeoutMs,
            maxRetries = limits.maxRetries,
            retryBackoffMs = limits.retryBackoffMs,
        )
    }
}

fun clearImageCache() {
    memoryImageCache?.clear()
}

/** 网络图片缓存是否已经初始化，供就绪检查使用。 */
internal fun isImageCacheConfigured(): Boolean = memoryImageCache != null

/** 图片缓存因容量上限淘汰的条目数。 */
internal fun imageCacheEvictions(): Long = memoryImageCache?.evictionCount() ?: 0L

/** 图片缓存因 TTL 过期而重新加载的次数。 */
internal fun imageCacheExpirations(): Long = memoryImageCache?.expirationCount() ?: 0L

fun imageCacheInfo(): Pair<Int, Int> {
    val cache = memoryImageCache ?: return 0 to 0
    return synchronized(cache) {
        cache.size to cache.totalBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
