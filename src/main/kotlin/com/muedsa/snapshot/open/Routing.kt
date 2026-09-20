package com.muedsa.snapshot.open

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.rateLimit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.security.MessageDigest
import java.util.UUID

private class RequestBodyTooLarge(val maxBytes: Long) : RuntimeException()

fun Application.configureRouting() {
    configureRoutingInternal()
}

internal fun Application.configureRoutingForTests(adminToken: String) {
    configureRateLimiting()
    configureRoutingInternal(adminEndpointsEnabledOverride = true, adminTokenOverride = adminToken)
}

private fun Application.configureRoutingInternal(
    adminEndpointsEnabledOverride: Boolean? = null,
    adminTokenOverride: String? = null,
) {
    val maxRequestSize = environment.config
        .propertyOrNull("snapshot.max-request-size")?.getString()?.toLongOrNull() ?: 1_048_576L
    require(maxRequestSize > 0) { "snapshot.max-request-size must be positive" }
    val maxConcurrentRenders = environment.config
        .propertyOrNull("snapshot.max-concurrent-renders")?.getString()?.toIntOrNull() ?: 4
    require(maxConcurrentRenders > 0) { "snapshot.max-concurrent-renders must be positive" }
    val renderLimits = RenderLimits(
        maxWidth = environment.config.propertyOrNull("snapshot.max-canvas-width")?.getString()?.toIntOrNull() ?: 4096,
        maxHeight = environment.config.propertyOrNull("snapshot.max-canvas-height")?.getString()?.toIntOrNull() ?: 4096,
        maxPixels = environment.config.propertyOrNull("snapshot.max-canvas-pixels")?.getString()?.toLongOrNull() ?: 16_777_216,
    )
    require(renderLimits.maxWidth > 0 && renderLimits.maxHeight > 0 && renderLimits.maxPixels > 0) {
        "snapshot canvas limits must be positive"
    }
    val maxRenderTimeoutMs = environment.config
        .propertyOrNull("snapshot.max-render-timeout-ms")?.getString()?.toLongOrNull() ?: 30_000L
    require(maxRenderTimeoutMs > 0) { "snapshot.max-render-timeout-ms must be positive" }
    val adminEndpointsEnabled = adminEndpointsEnabledOverride ?: (
        environment.config.propertyOrNull("snapshot.admin-endpoints-enabled")
            ?.getString()?.toBooleanStrictOrNull() ?: false
        )
    val adminToken = adminTokenOverride
        ?: System.getenv("SNAPSHOT_ADMIN_TOKEN")?.takeIf(String::isNotBlank)
        ?: environment.config.propertyOrNull("snapshot.admin-token")?.getString()?.takeIf(String::isNotBlank)
    require(!adminEndpointsEnabled || adminToken != null) {
        "SNAPSHOT_ADMIN_TOKEN or snapshot.admin-token is required when admin endpoints are enabled"
    }
    val renderSemaphore = Semaphore(maxConcurrentRenders)

    routing {
        get("/health") {
            call.respondText("OK")
        }

        get("/") {
            call.respondText("Open-Snapshot is running!")
        }

        rateLimit(SnapshotRateLimit) {
            post("/snapshot") {
                val requestId = call.request.headers["X-Request-Id"]?.takeIf { isValidRequestId(it) }
                    ?: UUID.randomUUID().toString()
                call.response.headers.append("X-Request-Id", requestId)
                val startedAt = System.nanoTime()
                try {
                    withTimeout(maxRenderTimeoutMs) {
                        val source = call.receiveLimitedText(maxRequestSize)
                        renderSemaphore.withPermit {
                            val result = SnapshotService.render(source, renderLimits)
                            call.respondBytes(result.bytes, result.contentType)
                        }
                    }
                } catch (error: RequestBodyTooLarge) {
                    respondApiError(call, ApiError(
                        code = "REQUEST_TOO_LARGE",
                        message = "Snapshot request body exceeds ${error.maxBytes} bytes",
                        requestId = requestId,
                        status = HttpStatusCode.PayloadTooLarge,
                    ))
                } catch (error: TimeoutCancellationException) {
                    call.application.environment.log.warn(
                        "Snapshot request timed out requestId=$requestId elapsedNanos=${System.nanoTime() - startedAt}"
                    )
                    respondApiError(call, ApiError(
                        code = "RENDER_TIMEOUT",
                        message = "Snapshot rendering exceeded ${maxRenderTimeoutMs} ms",
                        requestId = requestId,
                        status = HttpStatusCode.GatewayTimeout,
                    ))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val sourceMessage = error.message ?: error::class.simpleName ?: "Snapshot rendering failed"
                    val status = if (
                        error is com.muedsa.snapshot.parser.ParseException ||
                        error is IllegalArgumentException ||
                        error is IllegalStateException
                    ) {
                        HttpStatusCode.BadRequest
                    } else {
                        HttpStatusCode.InternalServerError
                    }
                    val code = when {
                        error is IllegalArgumentException && sourceMessage.contains("must not be empty") -> "EMPTY_REQUEST"
                        sourceMessage.contains("image URL", ignoreCase = true) -> "IMAGE_LOAD_ERROR"
                        error is com.muedsa.snapshot.parser.ParseException -> "PARSE_ERROR"
                        status == HttpStatusCode.BadRequest -> "RENDER_ERROR"
                        else -> "INTERNAL_ERROR"
                    }
                    call.application.environment.log.warn(
                        "Snapshot request failed requestId=$requestId elapsedNanos=${System.nanoTime() - startedAt}",
                        error,
                    )
                    respondApiError(call, ApiError(
                        code = code,
                        message = if (status == HttpStatusCode.InternalServerError) {
                            "Snapshot rendering failed"
                        } else {
                            sourceMessage
                        },
                        requestId = requestId,
                        status = status,
                    ))
                }
            }
        }

        if (adminEndpointsEnabled) {
            get("/fonts") {
                if (!call.authorizeAdmin(adminToken!!)) return@get
                call.respondText(FontService.listFonts())
            }

            get("/fonts.png") {
                if (!call.authorizeAdmin(adminToken!!)) return@get
                withTimeout(maxRenderTimeoutMs) {
                    renderSemaphore.withPermit {
                        call.respondBytes(FontService.drawFonts(), ContentType.Image.PNG)
                    }
                }
            }

            get("/cacheInfo") {
                if (!call.authorizeAdmin(adminToken!!)) return@get
                val (count, bytes) = imageCacheInfo()
                call.respondText("count=$count\nbytes=$bytes")
            }

            post("/cacheClear") {
                if (!call.authorizeAdmin(adminToken!!)) return@post
                clearImageCache()
                call.respondText("OK")
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.receiveLimitedText(maxBytes: Long): String {
    val packet = receiveChannel().readRemaining(maxBytes + 1)
    val bytes = packet.readByteArray()
    if (bytes.size.toLong() > maxBytes) throw RequestBodyTooLarge(maxBytes)
    return bytes.toString(Charsets.UTF_8)
}

private suspend fun respondApiError(call: io.ktor.server.application.ApplicationCall, error: ApiError) {
    call.respondText(error.toJson(), ContentType.Application.Json, error.status)
}

private suspend fun io.ktor.server.application.ApplicationCall.authorizeAdmin(expectedToken: String): Boolean {
    val authorization = request.headers[HttpHeaders.Authorization]
    val suppliedToken = authorization
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
    if (suppliedToken != null && MessageDigest.isEqual(
            expectedToken.toByteArray(Charsets.UTF_8),
            suppliedToken.toByteArray(Charsets.UTF_8),
        )
    ) {
        return true
    }

    val requestId = request.headers["X-Request-Id"]?.takeIf(::isValidRequestId)
        ?: UUID.randomUUID().toString()
    response.headers.append("X-Request-Id", requestId)
    response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    respondApiError(this, ApiError(
        code = "UNAUTHORIZED",
        message = "A valid admin bearer token is required",
        requestId = requestId,
        status = HttpStatusCode.Unauthorized,
    ))
    return false
}

private fun isValidRequestId(value: String): Boolean =
    value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
