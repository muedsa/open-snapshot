package com.muedsa.snapshot.open

import io.ktor.http.ContentType

/**
 * 渲染结果缓存：相同 DSL 的重复请求直接返回上一次的输出，跳过解析、布局与光栅化。
 *
 * - 键为 DSL 文本的 SHA-256，天然与输出格式、字体配置等输入绑定；
 * - 同时受条目数与总字节数约束，按 LRU 淘汰；
 * - 带 TTL：DSL 中引用的网络图片可能变化，过期后重新渲染；
 * - [invalidate] 用于管理接口或配置变更后整体失效。
 */
internal class RenderResultCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val ttlMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal class Entry(
        val bytes: ByteArray,
        val contentType: ContentType,
        var expiresAtMillis: Long,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var totalBytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    /** 命中且未过期时返回缓存的输出。 */
    fun get(key: String): SnapshotResult? {
        val entry = synchronized(this) {
            val candidate = entries[key] ?: run {
                misses++
                return null
            }
            if (candidate.expiresAtMillis <= clock()) {
                entries.remove(key)
                totalBytes -= candidate.bytes.size.toLong()
                misses++
                return null
            }
            hits++
            candidate
        }
        return SnapshotResult(entry.bytes, entry.contentType)
    }

    /** 写入缓存；超出上限时按 LRU 淘汰。图片类响应可能较大，因此同时看字节预算。 */
    fun put(key: String, result: SnapshotResult) {
        val size = result.bytes.size.toLong()
        if (size > maxBytes) return
        synchronized(this) {
            entries.remove(key)?.let { totalBytes -= it.bytes.size.toLong() }
            entries[key] = Entry(result.bytes, result.contentType, clock() + ttlMs)
            totalBytes += size
            while (entries.size > maxEntries || totalBytes > maxBytes) {
                val eldestKey = entries.keys.firstOrNull() ?: break
                if (eldestKey == key && entries.size == 1) break
                val removed = entries.remove(eldestKey) ?: break
                totalBytes -= removed.bytes.size.toLong()
                evictions++
            }
        }
    }

    /** 清空并让所有历史键失效（供管理接口使用）。 */
    fun invalidate() {
        synchronized(this) {
            entries.clear()
            totalBytes = 0
        }
    }

    fun stats(): Stats = synchronized(this) {
        Stats(
            entries = entries.size,
            bytes = totalBytes,
            hits = hits,
            misses = misses,
            evictions = evictions,
        )
    }

    internal data class Stats(
        val entries: Int,
        val bytes: Long,
        val hits: Long,
        val misses: Long,
        val evictions: Long,
    )
}

/** 渲染结果缓存的全局实例，供路由与指标读取。 */
internal var renderResultCache: RenderResultCache? = null

/** 供指标输出使用的缓存统计。 */
internal fun renderCacheStats(): Pair<Int, Long> {
    val stats = renderResultCache?.stats() ?: return 0 to 0L
    return stats.entries to stats.bytes
}

internal fun renderCacheHits(): Long = renderResultCache?.stats()?.hits ?: 0L

internal fun renderCacheMisses(): Long = renderResultCache?.stats()?.misses ?: 0L

internal fun renderCacheEvictions(): Long = renderResultCache?.stats()?.evictions ?: 0L

/** 清空渲染结果缓存；未启用时是空操作。 */
internal fun clearRenderCache() {
    renderResultCache?.invalidate()
}

internal fun isRenderCacheEnabled(): Boolean = renderResultCache != null

/**
 * 渲染结果缓存键：DSL 文本的 SHA-256。
 *
 * 同一份 DSL 隐含了输出格式与全部布局参数，因此不需要额外的键成分；
 * 字体等运行期配置变化通过 [clearRenderCache] 失效。
 */
internal fun renderCacheKey(source: String): String = sha256Hex(source)

/**
 * `noCache="true"` 表示调用方要求每次重新拉取图片，这类请求跳过结果缓存。
 */
private val NO_CACHE_ATTRIBUTE = Regex("""noCache\s*=\s*["']?true["']?""", RegexOption.IGNORE_CASE)

internal fun bypassesRenderCache(source: String): Boolean = NO_CACHE_ATTRIBUTE.containsMatchIn(source)
