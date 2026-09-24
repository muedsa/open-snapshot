package com.muedsa.snapshot.open

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

/** 开放接口的 API Key 请求头，与 `Authorization: Bearer` 二选一。 */
internal const val API_KEY_HEADER = "X-API-Key"

/**
 * 开放接口鉴权：未配置任何客户端凭据时保持开放；配置后默认要求有效凭据。
 * `snapshot.anonymous-access-enabled=true` 时，未携带凭据的请求可以匿名访问，
 * 但显式携带错误凭据仍返回 401，避免客户端在凭据失效时无声降级为匿名身份。
 *
 * 凭据通过 [CallIdentity] 解析，支持多个 API Key 与平滑轮换（新旧 Key 可同时在列）。
 */
internal suspend fun ApplicationCall.requireRenderCredential(): Boolean {
    if (!application.credentialStore().requiresCredential) return true
    if (identity().isAuthenticated) return true
    if (application.snapshotConfig().anonymousAccessEnabled && suppliedCredential() == null) return true

    Metrics.renderFailed(ErrorCodes.UNAUTHORIZED)
    respondUnauthorized("A valid API key is required")
    return false
}

/**
 * 管理接口鉴权：接受管理令牌，或带 `admin: true` 的 API Key。
 *
 * 已认证但不具备管理权限返回 `403 FORBIDDEN`；未认证返回 `401 UNAUTHORIZED`。
 */
internal suspend fun ApplicationCall.requireAdmin(): Boolean {
    val current = identity()
    if (current.isAdmin) return true

    if (current.isAuthenticated) {
        Metrics.renderFailed(ErrorCodes.FORBIDDEN)
        respondApiError(
            this,
            ApiError(
                code = ErrorCodes.FORBIDDEN,
                message = "An admin credential is required",
                requestId = ensureRequestIdHeader(),
                status = HttpStatusCode.Forbidden,
            ),
        )
        return false
    }

    Metrics.renderFailed(ErrorCodes.UNAUTHORIZED)
    respondUnauthorized("A valid admin bearer token is required")
    return false
}

/** `/metrics` 访问控制；`/health`、`/ready` 探针不使用该检查。 */
internal suspend fun ApplicationCall.authorizeMetrics(): Boolean =
    when (application.snapshotConfig().metricsAccess) {
        MetricsAccess.OPEN -> true

        MetricsAccess.CREDENTIAL -> {
            if (identity().isAuthenticated) {
                true
            } else {
                Metrics.renderFailed(ErrorCodes.UNAUTHORIZED)
                respondUnauthorized("A valid API key is required to read metrics")
                false
            }
        }

        MetricsAccess.ADMIN -> requireAdmin()
    }

private suspend fun ApplicationCall.respondUnauthorized(message: String) {
    response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    respondApiError(
        this,
        ApiError(
            code = ErrorCodes.UNAUTHORIZED,
            message = message,
            requestId = ensureRequestIdHeader(),
            status = HttpStatusCode.Unauthorized,
        ),
    )
}
