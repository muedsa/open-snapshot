package com.muedsa.snapshot.open

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray

/** 渲染请求体超过上限时抛出，转换为 413。 */
internal class RequestBodyTooLarge(val maxBytes: Long) : RuntimeException()

/** 单次 `/snapshot` 请求执行渲染所需的运行期上下文。 */
internal class SnapshotRouteContext(
    val maxRequestSize: Long,
    val maxRenderTimeoutMs: Long,
    val renderLimits: RenderLimits,
    val renderExecutor: RenderExecutor,
)

/**
 * `/snapshot` 的处理逻辑：鉴权 → 排水检查 → 限流已由路由层完成 → 流式读取请求体 → 渲染 → 输出图片。
 *
 * 所有失败都会转成统一的 JSON 错误响应，并把结果计入指标。
 */
internal suspend fun ApplicationCall.handleSnapshotRequest(context: SnapshotRouteContext) {
    val requestId = ensureRequestIdHeader()
    val startedAt = System.nanoTime()

    if (!requireRenderCredential()) return

    if (application.serviceState().draining.get()) {
        Metrics.renderFailed(ErrorCodes.SERVICE_UNAVAILABLE)
        response.headers.append(HttpHeaders.RetryAfter, "5")
        respondApiError(
            this,
            ApiError(
                code = ErrorCodes.SERVICE_UNAVAILABLE,
                message = "Service is shutting down",
                requestId = requestId,
                status = HttpStatusCode.ServiceUnavailable,
            ),
        )
        return
    }

    var source: String? = null
    try {
        withTimeout(context.maxRenderTimeoutMs) {
            val body = receiveLimitedText(context.maxRequestSize)
            source = body
            val result = context.renderExecutor.run {
                val renderStartedAt = System.nanoTime()
                val rendered = SnapshotService.render(body, context.renderLimits)
                Metrics.renderSucceeded(System.nanoTime() - renderStartedAt, rendered.bytes.size)
                rendered
            }
            respondBytes(result.bytes, result.contentType)
        }
    } catch (error: RequestBodyTooLarge) {
        Metrics.renderFailed(ErrorCodes.REQUEST_TOO_LARGE)
        respondApiError(
            this,
            ApiError(
                code = ErrorCodes.REQUEST_TOO_LARGE,
                message = "Snapshot request body exceeds ${error.maxBytes} bytes",
                requestId = requestId,
                status = HttpStatusCode.PayloadTooLarge,
            ),
        )
    } catch (error: TimeoutCancellationException) {
        Metrics.renderFailed(ErrorCodes.RENDER_TIMEOUT)
        application.environment.log.warn(
            "Snapshot request timed out requestId=$requestId elapsedNanos=${System.nanoTime() - startedAt}"
        )
        respondApiError(
            this,
            ApiError(
                code = ErrorCodes.RENDER_TIMEOUT,
                message = "Snapshot rendering exceeded ${context.maxRenderTimeoutMs} ms",
                requestId = requestId,
                status = HttpStatusCode.GatewayTimeout,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        respondRenderFailure(error, requestId, startedAt, source)
    }
}

private suspend fun ApplicationCall.respondRenderFailure(
    error: Exception,
    requestId: String,
    startedAt: Long,
    source: String?,
) {
    val sourceMessage = when {
        // 解析失败时补充出错位置与附近源码，便于定位 DSL 问题。
        error is com.muedsa.snapshot.parser.ParseException -> SnapshotService.formatError(error, source.orEmpty())
        else -> error.message ?: error::class.simpleName ?: "Snapshot rendering failed"
    }
    val clientError = error is com.muedsa.snapshot.parser.ParseException ||
        error is IllegalArgumentException ||
        error is IllegalStateException
    val status = if (clientError) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
    val code = when {
        error is IllegalArgumentException && sourceMessage.contains("must not be empty") -> ErrorCodes.EMPTY_REQUEST
        sourceMessage.contains("image URL", ignoreCase = true) -> ErrorCodes.IMAGE_LOAD_ERROR
        error is com.muedsa.snapshot.parser.ParseException -> ErrorCodes.PARSE_ERROR
        clientError -> ErrorCodes.RENDER_ERROR
        else -> ErrorCodes.INTERNAL_ERROR
    }

    Metrics.renderFailed(code)
    application.environment.log.warn(
        "Snapshot request failed requestId=$requestId elapsedNanos=${System.nanoTime() - startedAt}",
        error,
    )
    respondApiError(
        this,
        ApiError(
            code = code,
            message = if (status == HttpStatusCode.InternalServerError) "Snapshot rendering failed" else sourceMessage,
            requestId = requestId,
            status = status,
        ),
    )
}

/** 流式读取请求体，超过上限立即失败，不依赖 Content-Length。 */
internal suspend fun ApplicationCall.receiveLimitedText(maxBytes: Long): String {
    val packet = receiveChannel().readRemaining(maxBytes + 1)
    val bytes = packet.readByteArray()
    if (bytes.size.toLong() > maxBytes) throw RequestBodyTooLarge(maxBytes)
    return bytes.toString(Charsets.UTF_8)
}
