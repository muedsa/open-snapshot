package com.muedsa.snapshot.open

import com.muedsa.snapshot.parser.attr.CommonAttrDefine
import com.muedsa.snapshot.parser.token.Token
import com.muedsa.snapshot.parser.token.Tokenizer
import io.ktor.http.ContentType
import java.io.StringReader

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
 * 使用 Parser 的分词和布尔解析判断 Image.noCache，不构建 Widget 或加载图片。
 * 裸属性、首尾空白、大小写和属性边界均与实际解析一致，正文和其他属性不参与判断。
 * 扫描失败时放弃缓存复用，由正常渲染流程保留首个错误、元素上下文及结构化诊断。
 */
internal fun bypassesRenderCache(source: String): Boolean {
    // 属性名区分大小写，分词器不解码属性名；通常请求不含此属性，避免额外完整分词。
    if (!source.contains(CommonAttrDefine.NO_CACHE.name)) return false
    return try {
        StringReader(source).use { reader ->
            val tokenizer = Tokenizer(reader)
            var token: Token
            do {
                token = tokenizer.read()
                if (token is Token.StartTag && token.tagName == "Image") {
                    for (attribute in token.attrList) {
                        if (attribute.name == CommonAttrDefine.NO_CACHE.name && CommonAttrDefine.NO_CACHE.parseValue(attribute)) {
                            return@use true
                        }
                    }
                }
                token.reset()
            } while (token !is Token.EOF)
            false
        }
    } catch (_: Exception) {
        true
    }
}
