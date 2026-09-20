package com.muedsa.snapshot.open

import io.ktor.http.ContentType
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
    val errorImage: ErrorImageSettings,
)

/** `?errorImage=png` 时返回的响应头，便于调用方程序化读取错误信息。 */
internal const val ERROR_IMAGE_CODE_HEADER = "X-Snapshot-Error-Code"
internal const val ERROR_IMAGE_POSITION_HEADER = "X-Snapshot-Error-Position"
internal const val ERROR_IMAGE_LOCATION_HEADER = "X-Snapshot-Error-Location"

internal const val ERROR_IMAGE_FORMAT_PNG = "png"
internal const val ERROR_IMAGE_SERVED = "served"
internal const val ERROR_IMAGE_FALLBACK = "fallback"
internal const val ERROR_IMAGE_UNSUPPORTED = "unsupported"

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

            val cacheKey = if (isRenderCacheEnabled() && !bypassesRenderCache(body)) {
                renderCacheKey(body)
            } else {
                null
            }
            if (cacheKey != null) {
                renderResultCache?.get(cacheKey)?.let { cached ->
                    respondBytes(cached.bytes, cached.contentType)
                    return@withTimeout
                }
            }

            val result = context.renderExecutor.run {
                val renderStartedAt = System.nanoTime()
                val rendered = SnapshotService.render(body, context.renderLimits)
                Metrics.renderSucceeded(System.nanoTime() - renderStartedAt, rendered.bytes.size)
                rendered
            }
            if (cacheKey != null) {
                renderResultCache?.put(cacheKey, result)
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
    } catch (error: RenderQueueFullException) {
        Metrics.renderFailed(ErrorCodes.QUEUE_FULL)
        response.headers.append(HttpHeaders.RetryAfter, "1")
        respondApiError(
            this,
            ApiError(
                code = ErrorCodes.QUEUE_FULL,
                message = "Render queue is full (${error.maxQueueSize} waiting), retry later",
                requestId = requestId,
                status = HttpStatusCode.ServiceUnavailable,
            ),
        )
    } catch (error: RenderQueueTimeoutException) {
        Metrics.renderFailed(ErrorCodes.QUEUE_TIMEOUT)
        response.headers.append(HttpHeaders.RetryAfter, "1")
        respondApiError(
            this,
            ApiError(
                code = ErrorCodes.QUEUE_TIMEOUT,
                message = "Waited longer than ${error.queueTimeoutMs} ms for a render slot",
                requestId = requestId,
                status = HttpStatusCode.ServiceUnavailable,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        respondRenderFailure(error, requestId, startedAt, source, context)
    }
}

private suspend fun ApplicationCall.respondRenderFailure(
    error: Exception,
    requestId: String,
    startedAt: Long,
    source: String?,
    context: SnapshotRouteContext,
) {
    val sourceMessage = when {
        // 解析失败时补充出错位置与附近源码，便于定位 DSL 问题。
        error is com.muedsa.snapshot.parser.ParseException -> SnapshotService.formatError(error, source.orEmpty())
        else -> error.message ?: error::class.simpleName ?: "Snapshot rendering failed"
    }
    val clientError = error.containsImageLoadFailure() ||
        error is com.muedsa.snapshot.parser.ParseException ||
        error is IllegalArgumentException ||
        error is IllegalStateException
    val status = if (clientError) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
    val code = when {
        // 解析器会把构建 widget 时的异常包成 ParseException，因此要沿因果链判断图片加载失败。
        error.containsImageLoadFailure() -> ErrorCodes.IMAGE_LOAD_ERROR
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

    if (requestedErrorImage()) {
        val parseError = error.findParseException()
        if (code == ErrorCodes.PARSE_ERROR && parseError != null && context.errorImage.enabled) {
            val rendered = renderErrorImage(parseError, source.orEmpty(), requestId, code, context)
            if (rendered != null) {
                Metrics.errorImageServed(ERROR_IMAGE_SERVED)
                respondBytes(rendered, ContentType.Image.PNG, HttpStatusCode.BadRequest)
                return
            }
            Metrics.errorImageServed(ERROR_IMAGE_FALLBACK)
        } else {
            Metrics.errorImageServed(ERROR_IMAGE_UNSUPPORTED)
        }
    }

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

/** 请求是否显式要求错误图（`?errorImage=png`）。 */
private fun ApplicationCall.requestedErrorImage(): Boolean =
    request.queryParameters["errorImage"]?.trim()?.equals(ERROR_IMAGE_FORMAT_PNG, ignoreCase = true) == true

/**
 * 渲染解析错误卡片。失败时返回 null（调用方回退 JSON），
 * 并且**不会**让错误图渲染的失败掩盖原始解析错误。
 */
private suspend fun ApplicationCall.renderErrorImage(
    parseError: com.muedsa.snapshot.parser.ParseException,
    source: String,
    requestId: String,
    code: String,
    context: SnapshotRouteContext,
): ByteArray? {
    val settings = context.errorImage
    val excerpt = buildErrorExcerpt(
        source = source,
        pos = parseError.pos,
        maxLines = settings.maxLines,
        maxColumns = settings.maxColumns,
        contextLines = settings.contextLines,
    )
    val message = parseError.message ?: "Invalid snapshot document"
    val bytes = try {
        withTimeout(context.maxRenderTimeoutMs) {
            context.renderExecutor.run { errorImageRenderer(excerpt, message, requestId) }
        }
    } catch (error: TimeoutCancellationException) {
        application.environment.log.warn("Parse error image timed out requestId=$requestId")
        return null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        application.environment.log.warn("Failed to render parse error image requestId=$requestId", error)
        return null
    }

    response.headers.append(ERROR_IMAGE_CODE_HEADER, code)
    parseError.pos.pos.takeIf { it >= 0 }?.let { response.headers.append(ERROR_IMAGE_POSITION_HEADER, it.toString()) }
    response.headers.append(ERROR_IMAGE_LOCATION_HEADER, excerpt.location)
    return bytes
}

private fun Throwable.findParseException(): com.muedsa.snapshot.parser.ParseException? =
    generateSequence(this) { it.cause }.filterIsInstance<com.muedsa.snapshot.parser.ParseException>().firstOrNull()

/** 流式读取请求体，超过上限立即失败，不依赖 Content-Length。 */
internal suspend fun ApplicationCall.receiveLimitedText(maxBytes: Long): String {
    val packet = receiveChannel().readRemaining(maxBytes + 1)
    val bytes = packet.readByteArray()
    if (bytes.size.toLong() > maxBytes) throw RequestBodyTooLarge(maxBytes)
    return bytes.toString(Charsets.UTF_8)
}

/** 因果链上是否存在图片加载失败：解析器会把构建 widget 时的异常包装成 ParseException。 */
private fun Throwable.containsImageLoadFailure(): Boolean =
    generateSequence(this) { it.cause }.any { it is ImageLoadException }
