package com.muedsa.snapshot.open

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.config.tryGetStringList
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.FontMgr
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val REQUEST_ID_HEADER = "X-Request-Id"

private const val SMOKE_SNAPSHOT =
    "<Snapshot type=\"png\"><Container width=\"1\" height=\"1\" color=\"#FFFFFFFF\"/></Snapshot>"

private val PROMETHEUS_CONTENT_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")

private val ServiceStateKey = AttributeKey<ServiceState>("SnapshotServiceState")
private val RequestIdKey = AttributeKey<String>("SnapshotRequestId")
private val RequestStartNanosKey = AttributeKey<Long>("SnapshotRequestStartNanos")

/** 进程级运行状态：进入排水阶段后不再接受新的渲染请求。 */
internal class ServiceState {
    val draining = AtomicBoolean(false)
}

internal fun Application.serviceState(): ServiceState {
    attributes.getOrNull(ServiceStateKey)?.let { return it }
    return ServiceState().also { attributes.put(ServiceStateKey, it) }
}

/** 返回可信的请求 ID：仅接受格式合法的调用方传入值，否则生成新的 UUID。 */
internal fun ApplicationCall.snapshotRequestId(): String {
    attributes.getOrNull(RequestIdKey)?.let { return it }
    val requestId = request.headers[REQUEST_ID_HEADER]?.takeIf(::isValidRequestId)
        ?: UUID.randomUUID().toString()
    attributes.put(RequestIdKey, requestId)
    return requestId
}

/** 确保响应只带一个请求 ID；可观测性插件未安装时（如路由级测试）由调用方补齐。 */
internal fun ApplicationCall.ensureRequestIdHeader(): String {
    val requestId = snapshotRequestId()
    if (response.headers[REQUEST_ID_HEADER] == null) {
        response.headers.append(REQUEST_ID_HEADER, requestId)
    }
    return requestId
}

internal data class ReadinessReport(
    val ready: Boolean,
    val reason: String? = null,
)

/**
 * 就绪探针：确认渲染链路真的可用，而不只是进程还活着。
 *
 * 首次检查会执行一次 1x1 的冒烟渲染（解析 + 布局 + Skia 光栅化 + 编码），
 * 通过后缓存结果，后续探测只做轻量判断。
 */
internal object ReadinessProbe {
    @Volatile
    private var renderEnvironmentVerified = false

    fun check(imageCacheConfigured: Boolean): ReadinessReport {
        if (!renderEnvironmentVerified) {
            renderEnvironmentVerified = runCatching {
                SnapshotService.render(SMOKE_SNAPSHOT, RenderLimits(maxWidth = 1, maxHeight = 1, maxPixels = 1))
            }.isSuccess
        }
        return when {
            !renderEnvironmentVerified -> ReadinessReport(false, "Snapshot render environment is not available")
            !fontEnvironmentAvailable() -> ReadinessReport(false, "Font environment is not available")
            !imageCacheConfigured -> ReadinessReport(false, "Network image cache is not configured")
            else -> ReadinessReport(true)
        }
    }

    private fun fontEnvironmentAvailable(): Boolean =
        runCatching { FontMgr.default.familiesCount > 0 }.getOrDefault(false)
}

/**
 * 单行结构化访问日志。只包含数字与元数据，不包含 DSL 与图片地址。
 */
internal fun buildAccessLogLine(
    requestId: String,
    method: String,
    path: String,
    status: Int,
    durationNanos: Long,
    bytes: Long,
): String = buildString {
    append("event=snapshot.access")
    append(" requestId=").append(sanitizeLogValue(requestId, 64))
    append(" method=").append(sanitizeLogValue(method, 16))
    append(" path=").append(sanitizeLogValue(path, 128))
    append(" status=").append(status)
    append(" durationMs=").append(if (durationNanos >= 0) durationNanos / 1_000_000L else -1L)
    append(" bytes=").append(bytes)
}

/** 防止日志注入：控制字符与空格统一替换，并限制长度。 */
internal fun sanitizeLogValue(value: String, maxLength: Int): String = buildString(minOf(value.length, maxLength)) {
    value.take(maxLength).forEach { char ->
        append(if (char.code < 0x20 || char.code == 0x7f || char == ' ') '_' else char)
    }
}

/** 渲染并发额度：仅在真正执行渲染时计入 in-flight 指标。 */
internal suspend fun <T> withRenderSlot(semaphore: Semaphore, block: suspend () -> T): T =
    semaphore.withPermit {
        Metrics.renderInFlight.inc()
        try {
            block()
        } finally {
            Metrics.renderInFlight.dec()
        }
    }

/**
 * 生命周期：接入 Ktor 的停机事件，让服务在退出前先进入排水状态。
 *
 * `ktor.deployment.shutdownGracePeriod` 决定已接收请求的收尾时间，
 * `ktor.deployment.shutdownTimeout` 是强制结束的上限。
 */
fun Application.configureLifecycle() {
    val state = serviceState()

    monitor.subscribe(ApplicationStarted) { application ->
        val report = if (state.draining.get()) {
            ReadinessReport(false, "Service is shutting down")
        } else {
            ReadinessProbe.check(isImageCacheConfigured())
        }
        Metrics.ready.set(if (report.ready) 1L else 0L)
        Metrics.draining.set(if (state.draining.get()) 1L else 0L)
        if (report.ready) {
            application.environment.log.info("event=snapshot.lifecycle phase=started ready=true")
        } else {
            application.environment.log.warn(
                "event=snapshot.lifecycle phase=started ready=false reason=${sanitizeLogValue(report.reason.orEmpty(), 128)}"
            )
        }
    }

    monitor.subscribe(ApplicationStopPreparing) { environment ->
        state.draining.set(true)
        Metrics.draining.set(1L)
        Metrics.ready.set(0L)
        environment.log.info(
            "event=snapshot.lifecycle phase=stop-preparing message=\"draining, new render requests are rejected with 503\""
        )
    }

    monitor.subscribe(ApplicationStopping) { application ->
        application.environment.log.info(
            "event=snapshot.lifecycle phase=stopping inFlightRenders=${Metrics.renderInFlight.value()}"
        )
    }

    monitor.subscribe(ApplicationStopped) { application ->
        application.environment.log.info("event=snapshot.lifecycle phase=stopped")
    }
}

/**
 * 可观测性：请求 ID、结构化访问日志、`/ready` 与 `/metrics`。
 *
 * 探针路径默认不写访问日志，避免健康检查刷屏。
 */
fun Application.configureObservability() {
    val config = environment.config
    val accessLogEnabled = config.propertyOrNull("snapshot.access-log-enabled")
        ?.getString()?.toBooleanStrictOrNull() ?: true
    val metricsEnabled = config.propertyOrNull("snapshot.metrics-enabled")
        ?.getString()?.toBooleanStrictOrNull() ?: true
    val accessLogSkipPaths = config.tryGetStringList("snapshot.access-log-skip-paths")?.toSet()
        ?: setOf("/health", "/ready", "/metrics")
    val logger = environment.log

    install(createApplicationPlugin("SnapshotObservability") {
        on(CallSetup) { call ->
            call.attributes.put(RequestStartNanosKey, System.nanoTime())
            call.response.headers.append(REQUEST_ID_HEADER, call.snapshotRequestId())
        }

        on(ResponseSent) { call ->
            val path = call.request.path()
            val status = call.response.status()?.value ?: 0
            val startedAtNanos = call.attributes.getOrNull(RequestStartNanosKey)
            val durationNanos = startedAtNanos?.let { System.nanoTime() - it } ?: -1L
            Metrics.observeHttp(call.request.httpMethod.value, path, status, durationNanos)
            if (status == HttpStatusCode.TooManyRequests.value && metricPathLabel(path) == "/snapshot") {
                Metrics.renderFailed("RATE_LIMITED")
            }
            if (accessLogEnabled && path !in accessLogSkipPaths) {
                logger.info(
                    buildAccessLogLine(
                        requestId = call.snapshotRequestId(),
                        method = call.request.httpMethod.value,
                        path = path,
                        status = status,
                        durationNanos = durationNanos,
                        bytes = call.response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L,
                    )
                )
            }
        }
    })

    routing {
        get("/ready") {
            val draining = call.application.serviceState().draining.get()
            val report = if (draining) {
                ReadinessReport(false, "Service is shutting down")
            } else {
                ReadinessProbe.check(isImageCacheConfigured())
            }
            Metrics.ready.set(if (report.ready) 1L else 0L)
            Metrics.draining.set(if (draining) 1L else 0L)
            if (report.ready) {
                call.respondText("READY")
            } else {
                respondApiError(
                    call,
                    ApiError(
                        code = "NOT_READY",
                        message = report.reason ?: "Service is not ready",
                        requestId = call.snapshotRequestId(),
                        status = HttpStatusCode.ServiceUnavailable,
                    ),
                )
            }
        }

        if (metricsEnabled) {
            get("/metrics") {
                call.respondText(Metrics.scrape(), PROMETHEUS_CONTENT_TYPE)
            }
        }
    }
}
