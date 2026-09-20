package com.muedsa.snapshot.open

import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.tryGetStringList
import io.ktor.util.AttributeKey

/** 配置非法时抛出，消息中一次性列出全部问题。 */
internal class SnapshotConfigurationException(message: String) : IllegalStateException(message)

internal data class CanvasLimits(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxPixels: Long,
)

internal data class ImageLimits(
    val maxImageNumOnce: Int,
    val maxSingleImageSize: Int,
    val memoryCacheNumLimit: Int,
    val maxCacheBytes: Long,
    val maxImageWidth: Int,
    val maxImageHeight: Int,
    val maxImagePixels: Long,
    val allowPrivateHosts: Boolean,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)

internal data class RateLimitSettings(
    val requests: Int,
    val windowMs: Long,
)

internal data class CorsSettings(
    val allowedHosts: List<String>,
    val trustProxyHeaders: Boolean,
)

internal data class AdminSettings(
    val enabled: Boolean,
    val token: String?,
)

internal data class AccessLogSettings(
    val enabled: Boolean,
    val skipPaths: Set<String>,
)

/**
 * 服务运行配置的唯一来源。
 *
 * 所有模块都通过 [Application.snapshotConfig] 读取，避免同一项配置在多个文件里各自解析、
 * 各自设默认值。解析失败时在启动阶段抛出 [SnapshotConfigurationException]，
 * 并且一次列出全部问题，而不是修一个报一个。
 */
internal class SnapshotConfig(
    val maxRequestSize: Long,
    val maxConcurrentRenders: Int,
    val maxRenderTimeoutMs: Long,
    val canvas: CanvasLimits,
    val image: ImageLimits,
    val rateLimit: RateLimitSettings,
    val cors: CorsSettings,
    val admin: AdminSettings,
    val apiKey: String?,
    val accessLog: AccessLogSettings,
    val metricsEnabled: Boolean,
    val fontFamilyNames: List<String>,
)

internal object SnapshotConfigLoader {
    /** 开放接口 API Key 的最小长度，避免使用过短的弱密钥。 */
    const val MIN_API_KEY_LENGTH: Int = 16

    private val DEFAULT_ACCESS_LOG_SKIP_PATHS = setOf("/health", "/ready", "/metrics")

    fun load(config: ApplicationConfig, env: (String) -> String? = System::getenv): SnapshotConfig {
        val problems = mutableListOf<String>()

        fun raw(path: String): String? = config.propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotEmpty() }

        fun positiveInt(path: String, default: Int): Int {
            val value = raw(path) ?: return default
            val parsed = value.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                problems += "$path must be a positive integer, but was '$value'"
                return default
            }
            return parsed
        }

        fun positiveLong(path: String, default: Long): Long {
            val value = raw(path) ?: return default
            val parsed = value.toLongOrNull()
            if (parsed == null || parsed <= 0) {
                problems += "$path must be a positive integer, but was '$value'"
                return default
            }
            return parsed
        }

        fun boolean(path: String, default: Boolean): Boolean {
            val value = raw(path) ?: return default
            val parsed = value.toBooleanStrictOrNull()
            if (parsed == null) {
                problems += "$path must be 'true' or 'false', but was '$value'"
                return default
            }
            return parsed
        }

        val adminEnabled = boolean("snapshot.admin-endpoints-enabled", false)
        val adminToken = env("SNAPSHOT_ADMIN_TOKEN")?.takeIf(String::isNotBlank)
            ?: raw("snapshot.admin-token")
        if (adminEnabled && adminToken == null) {
            problems += "snapshot.admin-token (or SNAPSHOT_ADMIN_TOKEN) is required when snapshot.admin-endpoints-enabled is true"
        }

        val apiKey = env("SNAPSHOT_API_KEY")?.takeIf(String::isNotBlank) ?: raw("snapshot.api-key")
        if (apiKey != null && apiKey.length < MIN_API_KEY_LENGTH) {
            problems += "snapshot.api-key (or SNAPSHOT_API_KEY) must be at least $MIN_API_KEY_LENGTH characters long"
        }

        val config = SnapshotConfig(
            maxRequestSize = positiveLong("snapshot.max-request-size", 1_048_576L),
            maxConcurrentRenders = positiveInt("snapshot.max-concurrent-renders", 4),
            maxRenderTimeoutMs = positiveLong("snapshot.max-render-timeout-ms", 30_000L),
            canvas = CanvasLimits(
                maxWidth = positiveInt("snapshot.max-canvas-width", 4096),
                maxHeight = positiveInt("snapshot.max-canvas-height", 4096),
                maxPixels = positiveLong("snapshot.max-canvas-pixels", 16_777_216L),
            ),
            image = ImageLimits(
                maxImageNumOnce = positiveInt("snapshot.image.max-image-num-once", 10),
                maxSingleImageSize = positiveInt("snapshot.image.max-single-image-size", 5 * 1024 * 1024),
                memoryCacheNumLimit = positiveInt("snapshot.image.memory-cache-num-limit", 100),
                maxCacheBytes = positiveLong("snapshot.image.max-cache-bytes", 256L * 1024 * 1024),
                maxImageWidth = positiveInt("snapshot.image.max-image-width", 4096),
                maxImageHeight = positiveInt("snapshot.image.max-image-height", 4096),
                maxImagePixels = positiveLong("snapshot.image.max-image-pixels", 16_777_216L),
                allowPrivateHosts = boolean("snapshot.image.allow-private-hosts", false),
                connectTimeoutMs = positiveInt("snapshot.image.connect-timeout-ms", 10_000),
                readTimeoutMs = positiveInt("snapshot.image.read-timeout-ms", 10_000),
            ),
            rateLimit = RateLimitSettings(
                requests = positiveInt("snapshot.rate-limit.requests", 6),
                windowMs = positiveLong("snapshot.rate-limit.window-ms", 60_000L),
            ),
            cors = CorsSettings(
                allowedHosts = config.tryGetStringList("snapshot.cors.allowed-hosts").orEmpty(),
                trustProxyHeaders = boolean("snapshot.trust-proxy-headers", false),
            ),
            admin = AdminSettings(enabled = adminEnabled, token = adminToken),
            apiKey = apiKey,
            accessLog = AccessLogSettings(
                enabled = boolean("snapshot.access-log-enabled", true),
                skipPaths = config.tryGetStringList("snapshot.access-log-skip-paths")?.toSet()
                    ?: DEFAULT_ACCESS_LOG_SKIP_PATHS,
            ),
            metricsEnabled = boolean("snapshot.metrics-enabled", true),
            fontFamilyNames = config.tryGetStringList("snapshot.font-family-names").orEmpty(),
        )

        if (problems.isNotEmpty()) {
            throw SnapshotConfigurationException(
                buildString {
                    append("Invalid snapshot configuration:\n")
                    problems.forEach { append("  - ").append(it).append('\n') }
                }.trimEnd()
            )
        }
        return config
    }
}

private val SnapshotConfigKey = AttributeKey<SnapshotConfig>("SnapshotConfig")

/** 懒加载并缓存配置；配置非法时抛出 [SnapshotConfigurationException]，服务不会启动。 */
internal fun Application.snapshotConfig(): SnapshotConfig {
    attributes.getOrNull(SnapshotConfigKey)?.let { return it }
    return SnapshotConfigLoader.load(environment.config).also { attributes.put(SnapshotConfigKey, it) }
}
