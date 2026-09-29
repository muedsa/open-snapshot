package com.muedsa.snapshot.open

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.rateLimit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

private const val DEFAULT_FONT_PREVIEW_FAMILIES = 10
private const val MAX_FONT_PREVIEW_FAMILIES = 20

fun Application.configureRouting() {
    configureRoutingInternal()
}

internal fun Application.configureRoutingForTests(
    adminToken: String?,
    credentials: List<ApiCredential> = emptyList(),
) {
    installCredentialStore(CredentialStore(credentials, adminToken))
    configureRateLimiting()
    configureRoutingInternal(
        adminEndpointsEnabledOverride = true,
        adminTokenOverride = adminToken ?: "test-admin-token-placeholder",
    )
}

private fun Application.configureRoutingInternal(
    adminEndpointsEnabledOverride: Boolean? = null,
    adminTokenOverride: String? = null,
) {
    val config = snapshotConfig()
    val publicDocs = if (config.publicDocsEnabled) PublicDocs.load() else null
    val adminEndpointsEnabled = adminEndpointsEnabledOverride ?: config.admin.enabled
    val adminToken = adminTokenOverride ?: config.admin.token
    require(!adminEndpointsEnabled || adminToken != null || config.credentials.any { it.admin }) {
        "snapshot.admin-token (or SNAPSHOT_ADMIN_TOKEN) or an API key with admin: true " +
            "is required when admin endpoints are enabled"
    }
    val renderExecutor = RenderExecutor(
        maxConcurrentRenders = config.maxConcurrentRenders,
        maxQueueSize = config.renderQueue.maxQueueSize,
        queueTimeoutMs = config.renderQueue.queueTimeoutMs,
    )
    monitor.subscribe(ApplicationStopped) { renderExecutor.close() }
    renderResultCache = if (config.renderCache.enabled) {
        RenderResultCache(
            maxEntries = config.renderCache.maxEntries,
            maxBytes = config.renderCache.maxBytes,
            ttlMs = config.renderCache.ttlMs,
        )
    } else {
        null
    }
    val snapshotContext = SnapshotRouteContext(
        maxRequestSize = config.maxRequestSize,
        maxRenderTimeoutMs = config.maxRenderTimeoutMs,
        renderLimits = RenderLimits(
            maxWidth = config.canvas.maxWidth,
            maxHeight = config.canvas.maxHeight,
            maxPixels = config.canvas.maxPixels,
            maxDocumentElements = config.maxDocumentElements,
            maxDocumentDepth = config.maxDocumentDepth,
            image = RenderImageLimits(
                maxCount = config.image.maxImageNumOnce,
                maxEncodedBytes = config.image.maxSingleImageSize,
                maxWidth = config.image.maxImageWidth,
                maxHeight = config.image.maxImageHeight,
                maxPixels = config.image.maxImagePixels,
                maxTotalPixels = config.image.maxTotalImagePixels,
            ),
        ),
        renderExecutor = renderExecutor,
        errorImage = config.errorImage,
        timingHeadersEnabled = config.timingHeaders.enabled,
    )

    routing {
        get("/health") {
            call.respondText("OK")
        }

        get("/") {
            call.respondText(
                if (publicDocs == null) "Open-Snapshot is running!"
                else "Open-Snapshot is running!\nOpenAPI: /openapi.yaml\nAI guide: /ai-guide.md",
            )
        }

        if (publicDocs != null) {
            get("/openapi.yaml") {
                call.respondText(publicDocs.openapi, ContentType.parse("application/yaml; charset=utf-8"))
            }
            get("/ai-guide.md") {
                call.respondText(publicDocs.aiGuide, ContentType.parse("text/markdown; charset=utf-8"))
            }
        }

        // 渲染与字体接口共用配额；匿名按 IP、已认证按凭据分别计桶。
        rateLimit(SnapshotAnonymousRateLimit) {
            rateLimit(SnapshotCredentialRateLimit) {
                post("/snapshot") {
                    call.handleSnapshotRequest(snapshotContext)
                }

                get("/fonts") {
                    if (!call.requireRenderCredential(countRenderFailure = false)) return@get
                    call.respondText(FontService.listFonts())
                }

                get("/fonts.png") {
                    if (!call.requireRenderCredential(countRenderFailure = false)) return@get
                    val requestedFamilies = call.request.queryParameters.getAll("family")
                        .orEmpty()
                        .flatMap { it.split(',') }
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                    val offset = call.nonNegativeQuery("offset") ?: return@get
                    val requestedLimit = call.nonNegativeQuery("limit") ?: return@get
                    if (requestedLimit > MAX_FONT_PREVIEW_FAMILIES) {
                        respondApiError(call, ApiError(
                            code = ErrorCodes.INVALID_QUERY,
                            message = "Query parameter 'limit' must be at most $MAX_FONT_PREVIEW_FAMILIES",
                            requestId = call.ensureRequestIdHeader(),
                            status = HttpStatusCode.BadRequest,
                        ))
                        return@get
                    }
                    val limit = requestedLimit.takeIf { it > 0 } ?: DEFAULT_FONT_PREVIEW_FAMILIES

                    val selection = selectFontFamilies(
                        available = FontService.familyNames(),
                        requested = requestedFamilies,
                        offset = offset,
                        limit = limit,
                    )
                    if (selection.unknown.isNotEmpty()) {
                        respondApiError(call, ApiError(
                            code = ErrorCodes.FONT_NOT_FOUND,
                            message = "Unknown font families: ${selection.unknown.joinToString(", ")}",
                            requestId = call.ensureRequestIdHeader(),
                            status = HttpStatusCode.BadRequest,
                        ))
                        return@get
                    }

                    try {
                        withTimeout(snapshotContext.maxRenderTimeoutMs) {
                            val cacheKey = fontPreviewCacheKey(selection.selected, offset, limit)
                            renderResultCache?.get(cacheKey)?.let { cached ->
                                call.respondBytes(cached.bytes, cached.contentType)
                                return@withTimeout
                            }
                            val preview = renderExecutor.run { FontService.drawFonts(selection.selected) }
                            val result = SnapshotResult(preview, ContentType.Image.PNG)
                            renderResultCache?.put(cacheKey, result)
                            call.respondBytes(result.bytes, result.contentType)
                        }
                    } catch (error: TimeoutCancellationException) {
                        respondApiError(call, ApiError(
                            code = ErrorCodes.RENDER_TIMEOUT,
                            message = "Font preview exceeded ${snapshotContext.maxRenderTimeoutMs} ms",
                            requestId = call.ensureRequestIdHeader(),
                            status = HttpStatusCode.GatewayTimeout,
                        ))
                    } catch (error: RenderQueueFullException) {
                        call.response.headers.append(HttpHeaders.RetryAfter, "1")
                        respondApiError(call, ApiError(
                            code = ErrorCodes.QUEUE_FULL,
                            message = "Render queue is full (${error.maxQueueSize} waiting), retry later",
                            requestId = call.ensureRequestIdHeader(),
                            status = HttpStatusCode.ServiceUnavailable,
                        ))
                    } catch (error: RenderQueueTimeoutException) {
                        call.response.headers.append(HttpHeaders.RetryAfter, "1")
                        respondApiError(call, ApiError(
                            code = ErrorCodes.QUEUE_TIMEOUT,
                            message = "Waited longer than ${error.queueTimeoutMs} ms for a render slot",
                            requestId = call.ensureRequestIdHeader(),
                            status = HttpStatusCode.ServiceUnavailable,
                        ))
                    } catch (error: CancellationException) {
                        throw error
                    }
                }
            }
        }

        if (adminEndpointsEnabled) {
            // 缓存管理接口共用一个管理限流桶。
            rateLimit(SnapshotAdminRateLimit) {
                get("/cacheInfo") {
                    if (!call.requireAdmin()) return@get
                    val (count, bytes) = imageCacheInfo()
                    val (renderEntries, renderBytes) = renderCacheStats()
                    call.respondText(
                        "count=$count\nbytes=$bytes\nrenderEntries=$renderEntries\nrenderBytes=$renderBytes"
                    )
                }

                post("/cacheClear") {
                    if (!call.requireAdmin()) return@post
                    clearImageCache()
                    clearRenderCache()
                    call.respondText("OK")
                }
            }
        }
    }
}

internal suspend fun respondApiError(call: io.ktor.server.application.ApplicationCall, error: ApiError) {
    call.respondText(error.toJson(), ContentType.Application.Json, error.status)
}

/**
 * 读取非负整数查询参数；缺省为 0，非法值直接返回 `400 INVALID_QUERY` 并返回 null。
 */
private suspend fun io.ktor.server.application.ApplicationCall.nonNegativeQuery(name: String): Int? {
    val raw = request.queryParameters[name] ?: return 0
    val parsed = raw.trim().toIntOrNull()
    if (parsed == null || parsed < 0) {
        respondApiError(this, ApiError(
            code = ErrorCodes.INVALID_QUERY,
            message = "Query parameter '$name' must be a non-negative integer, but was '$raw'",
            requestId = ensureRequestIdHeader(),
            status = HttpStatusCode.BadRequest,
        ))
        return null
    }
    return parsed
}

/** 字体预览缓存的键：与渲染结果缓存共用存储，但用前缀与 DSL 键区分。 */
internal fun fontPreviewCacheKey(families: List<String>, offset: Int, limit: Int): String =
    "fonts-preview\u0000$offset\u0000$limit\u0000${families.joinToString(",")}"

internal fun isValidRequestId(value: String): Boolean =
    value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
