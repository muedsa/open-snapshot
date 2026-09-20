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
    /** 图片缓存 TTL；0 表示永不过期。 */
    val cacheTtlMs: Long,
    val maxRetries: Int,
    val retryBackoffMs: Long,
)

internal data class RateLimitSettings(
    val anonymousRequests: Int,
    val anonymousWindowMs: Long,
    val credentialRequests: Int,
    val credentialWindowMs: Long,
    val adminRequests: Int,
    val adminWindowMs: Long,
)

/** `/metrics` 的访问模式；探针 `/health`、`/ready` 始终开放。 */
internal enum class MetricsAccess {
    OPEN,
    CREDENTIAL,
    ADMIN,
    ;

    companion object {
        fun parse(value: String?): MetricsAccess? = when (value?.lowercase()) {
            null, "open" -> OPEN
            "credential" -> CREDENTIAL
            "admin" -> ADMIN
            else -> null
        }
    }
}

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

/** 渲染结果缓存配置。 */
internal data class RenderCacheSettings(
    val enabled: Boolean,
    val maxEntries: Int,
    val maxBytes: Long,
    val ttlMs: Long,
)

/** 渲染队列背压配置。 */
internal data class RenderQueueSettings(
    val maxQueueSize: Int,
    val queueTimeoutMs: Long,
)

/** 解析错误高亮图配置。 */
internal data class TimingSettings(
    val enabled: Boolean,
)

internal data class ErrorImageSettings(
    val enabled: Boolean,
    val maxLines: Int,
    val maxColumns: Int,
    val contextLines: Int,
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
    val renderCache: RenderCacheSettings,
    val renderQueue: RenderQueueSettings,
    val errorImage: ErrorImageSettings,
    val timingHeaders: TimingSettings,
    val cors: CorsSettings,
    val admin: AdminSettings,
    /** 匿名调用方是否开放；由是否配置客户端凭据决定。 */
    val apiKey: String?,
    val credentials: List<ApiCredential>,
    val metricsAccess: MetricsAccess,
    val accessLog: AccessLogSettings,
    val metricsEnabled: Boolean,
    val fontFamilyNames: List<String>,
) {
    /** 是否配置了至少一个客户端凭据。 */
    val requiresCredential: Boolean get() = credentials.isNotEmpty()
}

internal object SnapshotConfigLoader {
    /** 开放接口 API Key 的最小长度，避免使用过短的弱密钥。 */
    const val MIN_API_KEY_LENGTH: Int = 16

    /** API Key 数量上限，同时约束指标标签基数。 */
    const val MAX_CREDENTIALS: Int = 32

    /** 凭据名称会出现在日志、指标与限流桶键里，因此限制字符集。 */
    val CREDENTIAL_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,32}")

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

        /** 允许 0 的整数，例如 `max-render-queue: 0` 表示不排队、直接拒绝。 */
        fun nonNegativeInt(path: String, default: Int): Int {
            val value = raw(path) ?: return default
            val parsed = value.toIntOrNull()
            if (parsed == null || parsed < 0) {
                problems += "$path must be a non-negative integer, but was '$value'"
                return default
            }
            return parsed
        }

        /** 允许 0 的长整数，例如 `cache-ttl-ms: 0` 表示永不过期。 */
        fun nonNegativeLong(path: String, default: Long): Long {
            val value = raw(path) ?: return default
            val parsed = value.toLongOrNull()
            if (parsed == null || parsed < 0) {
                problems += "$path must be a non-negative integer, but was '$value'"
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

        val apiKey = env("SNAPSHOT_API_KEY")?.takeIf(String::isNotBlank) ?: raw("snapshot.api-key")
        if (apiKey != null && apiKey.length < MIN_API_KEY_LENGTH) {
            problems += "snapshot.api-key (or SNAPSHOT_API_KEY) must be at least $MIN_API_KEY_LENGTH characters long"
        }

        val credentials = parseCredentials(config, env, apiKey, problems)

        // 管理接口可用「管理令牌」或「admin: true 的 API Key」任一方式访问。
        if (adminEnabled && adminToken == null && credentials.none { it.admin }) {
            problems += "snapshot.admin-token (or SNAPSHOT_ADMIN_TOKEN) or an API key with admin: true " +
                "is required when snapshot.admin-endpoints-enabled is true"
        }

        val metricsAccessValue = env("SNAPSHOT_METRICS_ACCESS")?.takeIf(String::isNotBlank)
            ?: raw("snapshot.metrics-access")
        val metricsAccess = MetricsAccess.parse(metricsAccessValue)
        if (metricsAccess == null) {
            problems += "snapshot.metrics-access must be one of 'open', 'credential', 'admin', but was '$metricsAccessValue'"
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
                cacheTtlMs = nonNegativeLong("snapshot.image.cache-ttl-ms", 600_000L),
                maxRetries = nonNegativeInt("snapshot.image.max-retries", 1),
                retryBackoffMs = nonNegativeLong("snapshot.image.retry-backoff-ms", 200L),
            ),
            rateLimit = RateLimitSettings(
                anonymousRequests = positiveInt("snapshot.rate-limit.requests", 6),
                anonymousWindowMs = positiveLong("snapshot.rate-limit.window-ms", 60_000L),
                credentialRequests = positiveInt("snapshot.rate-limit.credential-requests", 60),
                credentialWindowMs = positiveLong("snapshot.rate-limit.credential-window-ms", 60_000L),
                adminRequests = positiveInt("snapshot.rate-limit.admin-requests", 6),
                adminWindowMs = positiveLong("snapshot.rate-limit.admin-window-ms", 60_000L),
            ),
            renderCache = RenderCacheSettings(
                enabled = boolean("snapshot.render-cache.enabled", true),
                maxEntries = positiveInt("snapshot.render-cache.max-entries", 256),
                maxBytes = positiveLong("snapshot.render-cache.max-bytes", 64L * 1024 * 1024),
                ttlMs = positiveLong("snapshot.render-cache.ttl-ms", 60_000L),
            ),
            renderQueue = RenderQueueSettings(
                maxQueueSize = nonNegativeInt("snapshot.max-render-queue", RenderExecutor.DEFAULT_MAX_QUEUE_SIZE),
                queueTimeoutMs = positiveLong(
                    "snapshot.render-queue-timeout-ms",
                    RenderExecutor.DEFAULT_QUEUE_TIMEOUT_MS,
                ),
            ),
            timingHeaders = TimingSettings(
                enabled = boolean("snapshot.timing-headers.enabled", true),
            ),
            errorImage = ErrorImageSettings(
                enabled = boolean("snapshot.error-image.enabled", true),
                maxLines = positiveInt("snapshot.error-image.max-lines", 8),
                maxColumns = positiveInt("snapshot.error-image.max-columns", 80),
                contextLines = nonNegativeInt("snapshot.error-image.context-lines", 2),
            ),
            cors = CorsSettings(
                allowedHosts = config.tryGetStringList("snapshot.cors.allowed-hosts").orEmpty(),
                trustProxyHeaders = boolean("snapshot.trust-proxy-headers", false),
            ),
            admin = AdminSettings(enabled = adminEnabled, token = adminToken),
            apiKey = apiKey,
            credentials = credentials,
            metricsAccess = metricsAccess ?: MetricsAccess.OPEN,
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

    /**
     * 解析凭据：单个 `snapshot.api-key`、YAML 列表 `snapshot.api-keys`、环境变量
     * `SNAPSHOT_API_KEYS`（`名称:密钥` 逗号分隔）。
     */
    private fun parseCredentials(
        config: ApplicationConfig,
        env: (String) -> String?,
        singleApiKey: String?,
        problems: MutableList<String>,
    ): List<ApiCredential> {
        val credentials = mutableListOf<ApiCredential>()

        singleApiKey?.takeIf { it.length >= MIN_API_KEY_LENGTH }?.let {
            credentials += ApiCredential(name = "default", key = it)
        }

        env("SNAPSHOT_API_KEYS")?.takeIf(String::isNotBlank)?.let { rawKeys ->
            rawKeys.split(',').map(String::trim).filter(String::isNotEmpty).forEach { entry ->
                // 格式：名称:密钥[:admin]
                val parts = entry.split(':', limit = 3).map(String::trim)
                val name = parts.getOrNull(0).orEmpty()
                val key = parts.getOrNull(1).orEmpty()
                val flag = parts.getOrNull(2)
                when {
                    name.isEmpty() || key.isEmpty() ->
                        problems += "SNAPSHOT_API_KEYS entries must be 'name:key' or 'name:key:admin' pairs, but was '$entry'"

                    flag != null && !flag.equals("admin", ignoreCase = true) ->
                        problems += "SNAPSHOT_API_KEYS optional flag must be 'admin', but was '$flag'"

                    else -> credentials += ApiCredential(
                        name = name,
                        key = key,
                        admin = flag != null,
                    )
                }
            }
        }

        if (config.propertyOrNull("snapshot.api-keys") != null) {
            config.configList("snapshot.api-keys").forEachIndexed { index, entry ->
                val name = entry.propertyOrNull("name")?.getString()?.trim().orEmpty()
                val key = entry.propertyOrNull("key")?.getString()?.trim().orEmpty()
                val admin = entry.propertyOrNull("admin")?.getString()?.trim()?.toBooleanStrictOrNull() ?: false
                if (name.isEmpty() || key.isEmpty()) {
                    problems += "snapshot.api-keys[$index] requires both 'name' and 'key'"
                } else {
                    credentials += ApiCredential(name = name, key = key, admin = admin)
                }
            }
        }

        credentials.forEach { credential ->
            if (credential.key.length < MIN_API_KEY_LENGTH) {
                problems += "API key '${credential.name}' must be at least $MIN_API_KEY_LENGTH characters long"
            }
            if (!CREDENTIAL_NAME_PATTERN.matches(credential.name)) {
                problems += "API key name '${credential.name}' must match ${CREDENTIAL_NAME_PATTERN.pattern}"
            }
        }

        val duplicates = credentials.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            problems += "API key names must be unique, duplicated: ${duplicates.sorted().joinToString(", ")}"
        }

        if (credentials.size > MAX_CREDENTIALS) {
            problems += "at most $MAX_CREDENTIALS API keys are supported, but ${credentials.size} were configured"
        }

        return credentials
    }
}

private val SnapshotConfigKey = AttributeKey<SnapshotConfig>("SnapshotConfig")

/** 懒加载并缓存配置；配置非法时抛出 [SnapshotConfigurationException]，服务不会启动。 */
internal fun Application.snapshotConfig(): SnapshotConfig {
    attributes.getOrNull(SnapshotConfigKey)?.let { return it }
    return SnapshotConfigLoader.load(environment.config).also { attributes.put(SnapshotConfigKey, it) }
}
