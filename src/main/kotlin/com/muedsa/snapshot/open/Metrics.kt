package com.muedsa.snapshot.open

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder

/**
 * 进程内指标注册表，输出 Prometheus 文本格式（version=0.0.4）。
 *
 * 设计约束：
 * - 只用固定集合的标签，未知路径收敛到 `/other`，避免指标基数被外部输入放大；
 * - 只记录数字与结果分类，不记录 DSL、图片地址等请求内容；
 * - 计数为进程级累计值，重启后归零。
 */
private const val MAX_LABEL_SERIES = 64

private const val NANOS_PER_SECOND = 1_000_000_000.0

private val RENDER_DURATION_BUCKETS = doubleArrayOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0)

private val QUEUE_WAIT_BUCKETS = doubleArrayOf(0.001, 0.01, 0.05, 0.1, 0.5, 1.0, 2.5, 5.0)

private val KNOWN_METRIC_PATHS = setOf(
    "/",
    "/snapshot",
    "/health",
    "/ready",
    "/metrics",
    "/fonts",
    "/fonts.png",
    "/cacheInfo",
    "/cacheClear",
)

/** 把任意请求路径收敛为有限标签值。 */
internal fun metricPathLabel(path: String): String =
    if (path in KNOWN_METRIC_PATHS) path else "/other"

/** 把接口错误码映射为渲染统计的结果分类。 */
internal fun renderOutcome(code: String): String = when (code) {
    ErrorCodes.EMPTY_REQUEST -> "empty_request"
    ErrorCodes.PARSE_ERROR -> "parse_error"
    ErrorCodes.IMAGE_LOAD_ERROR -> "image_error"
    ErrorCodes.RENDER_ERROR -> "render_error"
    ErrorCodes.REQUEST_TOO_LARGE -> "too_large"
    ErrorCodes.RENDER_TIMEOUT -> "timeout"
    ErrorCodes.RATE_LIMITED -> "rate_limited"
    ErrorCodes.QUEUE_FULL -> "queue_full"
    ErrorCodes.QUEUE_TIMEOUT -> "queue_timeout"
    ErrorCodes.SERVICE_UNAVAILABLE -> "unavailable"
    ErrorCodes.NOT_READY -> "not_ready"
    ErrorCodes.UNAUTHORIZED -> "unauthorized"
    ErrorCodes.FORBIDDEN -> "forbidden"
    else -> "internal_error"
}

internal class Counter {
    private val value = LongAdder()

    fun inc(delta: Long = 1L) {
        if (delta != 0L) value.add(delta)
    }

    fun value(): Long = value.sum()
}

internal class Gauge(initialValue: Long = 0L) {
    private val value = AtomicLong(initialValue)

    fun set(newValue: Long) {
        value.set(newValue)
    }

    fun inc(delta: Long = 1L) {
        value.addAndGet(delta)
    }

    fun dec(delta: Long = 1L) {
        value.addAndGet(-delta)
    }

    fun value(): Long = value.get()
}

internal class LabeledCounter(
    private val name: String,
    private val help: String,
    private val labelNames: List<String>,
) {
    private val series = ConcurrentHashMap<List<String>, LongAdder>()

    fun inc(labelValues: List<String>, delta: Long = 1L) {
        require(labelValues.size == labelNames.size) {
            "$name requires ${labelNames.size} label values"
        }
        val existing = series[labelValues]
        if (existing != null) {
            existing.add(delta)
            return
        }
        if (series.size >= MAX_LABEL_SERIES) return
        series.computeIfAbsent(labelValues) { LongAdder() }.add(delta)
    }

    fun render(): String = buildString {
        appendLine("# HELP $name $help")
        appendLine("# TYPE $name counter")
        series.entries.sortedBy { it.key.joinToString("\u0000") }.forEach { (labelValues, value) ->
            append(name).append(formatLabels(labelNames, labelValues))
                .append(' ').append(value.sum()).append('\n')
        }
    }
}

internal class LabeledTiming(
    private val name: String,
    private val help: String,
    private val labelNames: List<String>,
) {
    private class Sample {
        val count = LongAdder()
        val totalSeconds = DoubleAdder()
    }

    private val series = ConcurrentHashMap<List<String>, Sample>()

    fun record(labelValues: List<String>, seconds: Double) {
        require(labelValues.size == labelNames.size) {
            "$name requires ${labelNames.size} label values"
        }
        val existing = series[labelValues]
        val sample = if (existing != null) {
            existing
        } else {
            if (series.size >= MAX_LABEL_SERIES) return
            series.computeIfAbsent(labelValues) { Sample() }
        }
        sample.count.increment()
        sample.totalSeconds.add(seconds)
    }

    fun render(): String = buildString {
        appendLine("# HELP $name $help")
        appendLine("# TYPE $name summary")
        series.entries.sortedBy { it.key.joinToString("\u0000") }.forEach { (labelValues, sample) ->
            append(name).append("_sum").append(formatLabels(labelNames, labelValues))
                .append(' ').append(formatDouble(sample.totalSeconds.sum())).append('\n')
            append(name).append("_count").append(formatLabels(labelNames, labelValues))
                .append(' ').append(sample.count.sum()).append('\n')
        }
    }
}

internal class Histogram(
    private val name: String,
    private val help: String,
    private val buckets: DoubleArray,
) {
    private val bucketCounts = Array(buckets.size) { LongAdder() }
    private val totalSeconds = DoubleAdder()
    private val count = LongAdder()

    fun observe(seconds: Double) {
        val index = buckets.indexOfFirst { seconds <= it }
        if (index >= 0) bucketCounts[index].increment()
        count.increment()
        totalSeconds.add(seconds)
    }

    fun render(): String = buildString {
        appendLine("# HELP $name $help")
        appendLine("# TYPE $name histogram")
        var cumulative = 0L
        buckets.forEachIndexed { index, bound ->
            cumulative += bucketCounts[index].sum()
            append(name).append("_bucket{le=\"").append(formatDouble(bound)).append("\"} ")
                .append(cumulative).append('\n')
        }
        append(name).append("_bucket{le=\"+Inf\"} ").append(count.sum()).append('\n')
        append(name).append("_sum ").append(formatDouble(totalSeconds.sum())).append('\n')
        append(name).append("_count ").append(count.sum()).append('\n')
    }
}

internal object Metrics {
    private val startedAtNanos = System.nanoTime()

    val ready = Gauge()
    val draining = Gauge()
    val renderInFlight = Gauge()
    val renderPending = Gauge()
    val renderOutputBytes = Counter()

    val renders = LabeledCounter(
        name = "snapshot_renders_total",
        help = "Snapshot render requests by outcome.",
        labelNames = listOf("outcome"),
    )

    val httpRequests = LabeledCounter(
        name = "snapshot_http_requests_total",
        help = "HTTP requests by route, method and status code.",
        labelNames = listOf("path", "method", "status"),
    )

    val httpDuration = LabeledTiming(
        name = "snapshot_http_request_duration_seconds",
        help = "HTTP request latency in seconds by route.",
        labelNames = listOf("path"),
    )

    val renderDuration = Histogram(
        name = "snapshot_render_duration_seconds",
        help = "Snapshot render latency in seconds.",
        buckets = RENDER_DURATION_BUCKETS,
    )

    val renderQueueWait = Histogram(
        name = "snapshot_render_queue_wait_seconds",
        help = "Time spent waiting for a free render slot, in seconds.",
        buckets = QUEUE_WAIT_BUCKETS,
    )

    val renderQueueRejected = LabeledCounter(
        name = "snapshot_render_queue_rejected_total",
        help = "Requests rejected by render queue backpressure, by reason.",
        labelNames = listOf("reason"),
    )

    val imageCacheHits = Counter()
    val imageCacheMisses = Counter()
    val imageDownloads = Counter()
    val imageDownloadFailures = Counter()
    val imageRetries = Counter()

    val imageRevalidations = LabeledCounter(
        name = "snapshot_image_revalidations_total",
        help = "Conditional image requests by result: not_modified or updated.",
        labelNames = listOf("result"),
    )

    fun imageRevalidated(result: String) {
        imageRevalidations.inc(listOf(result))
    }

    val rateLimited = LabeledCounter(
        name = "snapshot_rate_limited_total",
        help = "Requests rejected by rate limiting, by limit scope.",
        labelNames = listOf("scope"),
    )

    fun rateLimitExceeded(scope: String) {
        rateLimited.inc(listOf(scope))
    }

    fun renderQueueRejected(reason: String) {
        renderQueueRejected.inc(listOf(reason))
    }

    fun observeRenderQueueWait(durationNanos: Long) {
        if (durationNanos >= 0) {
            renderQueueWait.observe(durationNanos / NANOS_PER_SECOND)
        }
    }

    fun renderSucceeded(durationNanos: Long, outputBytes: Int) {
        renders.inc(listOf("success"))
        renderDuration.observe(durationNanos / NANOS_PER_SECOND)
        renderOutputBytes.inc(outputBytes.toLong())
    }

    fun renderFailed(code: String) {
        renders.inc(listOf(renderOutcome(code)))
    }

    fun observeHttp(method: String, path: String, status: Int, durationNanos: Long) {
        val pathLabel = metricPathLabel(path)
        httpRequests.inc(listOf(pathLabel, method.uppercase(Locale.ROOT), status.toString()))
        if (durationNanos >= 0) {
            httpDuration.record(listOf(pathLabel), durationNanos / NANOS_PER_SECOND)
        }
    }

    fun scrape(): String = buildString {
        append(renderGauge("snapshot_ready", "1 when the service is ready to serve render requests.", ready.value()))
        append(renderGauge("snapshot_draining", "1 when the service is draining before shutdown.", draining.value()))
        append(
            renderGauge(
                "snapshot_uptime_seconds",
                "Process uptime in seconds.",
                formatDouble((System.nanoTime() - startedAtNanos) / NANOS_PER_SECOND),
            )
        )
        append(renderGauge("snapshot_render_in_flight", "Snapshot renders currently executing.", renderInFlight.value()))
        append(
            renderGauge(
                "snapshot_render_pending",
                "Requests waiting for a free render slot.",
                renderPending.value(),
            )
        )
        append(
            renderGauge(
                "snapshot_render_output_bytes_total",
                "Total bytes of rendered snapshot output.",
                renderOutputBytes.value(),
                type = "counter",
            )
        )
        append(renders.render())
        append(renderDuration.render())
        append(renderQueueWait.render())
        append(renderQueueRejected.render())
        append(
            renderGauge(
                "snapshot_render_cache_hits_total",
                "Render requests served from the render result cache.",
                renderCacheHits(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_render_cache_misses_total",
                "Render requests not found in the render result cache.",
                renderCacheMisses(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_render_cache_evictions_total",
                "Render cache entries evicted by the size limits.",
                renderCacheEvictions(),
                type = "counter",
            )
        )
        val (renderCacheEntries, renderCacheBytes) = renderCacheStats()
        append(
            renderGauge(
                "snapshot_render_cache_entries",
                "Rendered results held in the render cache.",
                renderCacheEntries.toLong(),
            )
        )
        append(
            renderGauge(
                "snapshot_render_cache_bytes",
                "Estimated bytes held in the render cache.",
                renderCacheBytes,
            )
        )
        append(rateLimited.render())
        append(httpRequests.render())
        append(httpDuration.render())
        append(
            renderGauge(
                "snapshot_image_cache_hits_total",
                "Network image requests served from the memory cache.",
                imageCacheHits.value(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_image_cache_misses_total",
                "Network image requests not found in the memory cache.",
                imageCacheMisses.value(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_image_downloads_total",
                "Network image download attempts.",
                imageDownloads.value(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_image_download_failures_total",
                "Failed network image downloads.",
                imageDownloadFailures.value(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_image_download_retries_total",
                "Image downloads retried after a transient failure.",
                imageRetries.value(),
                type = "counter",
            )
        )
        append(imageRevalidations.render())
        val (cacheEntries, cacheBytes) = imageCacheInfo()
        append(renderGauge("snapshot_image_cache_entries", "Images held in the memory cache.", cacheEntries.toLong()))
        append(renderGauge("snapshot_image_cache_bytes", "Estimated bytes held in the memory cache.", cacheBytes.toLong()))
        append(
            renderGauge(
                "snapshot_image_cache_evictions_total",
                "Images evicted from the memory cache by the size limits.",
                imageCacheEvictions(),
                type = "counter",
            )
        )
        append(
            renderGauge(
                "snapshot_image_cache_expirations_total",
                "Cache lookups that found an expired image entry.",
                imageCacheExpirations(),
                type = "counter",
            )
        )
    }

    private fun renderGauge(name: String, help: String, value: Long, type: String = "gauge"): String =
        renderGauge(name, help, value.toString(), type)

    private fun renderGauge(name: String, help: String, value: String, type: String = "gauge"): String = buildString {
        appendLine("# HELP $name $help")
        appendLine("# TYPE $name $type")
        append(name).append(' ').append(value).append('\n')
    }
}

private fun formatLabels(labelNames: List<String>, labelValues: List<String>): String =
    if (labelNames.isEmpty()) {
        ""
    } else {
        labelNames.zip(labelValues).joinToString(prefix = "{", postfix = "}", separator = ",") { (name, value) ->
            "$name=\"${escapeLabelValue(value)}\""
        }
    }

private fun escapeLabelValue(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
}

private fun formatDouble(value: Double): String =
    if (value.isNaN() || value.isInfinite()) {
        "0"
    } else {
        String.format(Locale.ROOT, "%.3f", value)
    }
