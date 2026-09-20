package com.muedsa.snapshot.open

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.util.Locale

/** `Server-Timing` 的一段：耗时段或描述段。 */
internal data class TimingEntry(
    val name: String,
    val durationMs: Double? = null,
    val description: String? = null,
)

internal const val SERVER_TIMING_HEADER = "Server-Timing"
internal const val CACHE_HEADER = "X-Snapshot-Cache"
internal const val IMAGE_COUNT_HEADER = "X-Snapshot-Image-Count"

internal const val CACHE_HIT = "hit"
internal const val CACHE_MISS = "miss"

/** 各阶段名称，顺序即输出顺序。 */
internal const val TIMING_QUEUE = "queue"
internal const val TIMING_RENDER = "render"
internal const val TIMING_IMAGE = "image"
internal const val TIMING_CACHE = "cache"
internal const val TIMING_TOTAL = "total"

/**
 * 序列化 `Server-Timing`：只有耗时段与描述段，均缺失的条目会被跳过；全空时返回空串。
 */
internal fun buildServerTimingHeader(entries: List<TimingEntry>): String =
    entries.mapNotNull { entry ->
        val parts = buildList {
            add(entry.name)
            entry.description?.let { add("desc=$it") }
            entry.durationMs?.let { add("dur=" + String.format(Locale.ROOT, "%.1f", it)) }
        }
        if (parts.size == 1) null else parts.joinToString(";")
    }.joinToString(", ")

/**
 * 追加耗时响应头。关闭开关、或没有任何指标可写时不追加任何头。
 */
internal fun ApplicationCall.appendTimingHeaders(
    enabled: Boolean,
    timings: RenderTimings?,
    totalNanos: Long,
    cache: String?,
) {
    if (!enabled) return
    val entries = buildList {
        timings?.queueNanos?.takeIf { it >= 0 }?.let {
            add(TimingEntry(TIMING_QUEUE, durationMs = it / NANOS_PER_MILLI))
        }
        timings?.renderNanos?.takeIf { it >= 0 }?.let {
            add(TimingEntry(TIMING_RENDER, durationMs = it / NANOS_PER_MILLI))
        }
        timings?.imageFetchNanos?.takeIf { it > 0 }?.let {
            add(TimingEntry(TIMING_IMAGE, durationMs = it / NANOS_PER_MILLI))
        }
        if (cache == CACHE_HIT) {
            add(TimingEntry(TIMING_CACHE, description = CACHE_HIT))
        }
        add(TimingEntry(TIMING_TOTAL, durationMs = totalNanos / NANOS_PER_MILLI))
    }

    val header = buildServerTimingHeader(entries)
    if (header.isNotEmpty()) {
        response.headers.append(SERVER_TIMING_HEADER, header)
    }
    cache?.let { response.headers.append(CACHE_HEADER, it) }
    val downloads = timings?.imageDownloads ?: 0
    if (downloads > 0) {
        response.headers.append(IMAGE_COUNT_HEADER, downloads.toString())
    }
}

private const val NANOS_PER_MILLI = 1_000_000.0

/** `/snapshot` 上供调用方读取的耗时信息也便于在浏览器 DevTools 中展示。 */
internal val TIMING_EXPOSED_HEADERS = listOf(SERVER_TIMING_HEADER, CACHE_HEADER, IMAGE_COUNT_HEADER, HttpHeaders.RetryAfter)
