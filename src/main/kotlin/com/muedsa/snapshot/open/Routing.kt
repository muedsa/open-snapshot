package com.muedsa.snapshot.open

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.ratelimit.rateLimit
import kotlinx.coroutines.withTimeout

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
        ),
        renderExecutor = renderExecutor,
    )

    routing {
        get("/health") {
            call.respondText("OK")
        }

        get("/") {
            call.respondText("Open-Snapshot is running!")
        }

        // 匿名按 IP、已认证按凭据分别计桶，两条限流器按身份互斥生效。
        rateLimit(SnapshotAnonymousRateLimit) {
            rateLimit(SnapshotCredentialRateLimit) {
                post("/snapshot") {
                    call.handleSnapshotRequest(snapshotContext)
                }
            }
        }

        if (adminEndpointsEnabled) {
            // 管理接口共用一个限流桶：字体预览图是重渲染，其余接口也便于统一防护。
            rateLimit(SnapshotAdminRateLimit) {
                get("/fonts") {
                    if (!call.requireAdmin()) return@get
                    call.respondText(FontService.listFonts())
                }

                get("/fonts.png") {
                    if (!call.requireAdmin()) return@get
                    withTimeout(snapshotContext.maxRenderTimeoutMs) {
                        val preview = renderExecutor.run { FontService.drawFonts() }
                        call.respondBytes(preview, ContentType.Image.PNG)
                    }
                }

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

internal fun isValidRequestId(value: String): Boolean =
    value.length in 1..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
