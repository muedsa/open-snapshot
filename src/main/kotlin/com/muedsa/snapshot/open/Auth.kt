package com.muedsa.snapshot.open

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.security.MessageDigest

/** 开放接口的 API Key 请求头，与 `Authorization: Bearer` 二选一。 */
internal const val API_KEY_HEADER = "X-API-Key"

/**
 * 可选鉴权：仅在配置了 `snapshot.api-key` 或 `SNAPSHOT_API_KEY` 后启用。
 *
 * 兼容两种携带方式：`X-API-Key: <key>` 与 `Authorization: Bearer <key>`，
 * 比较使用定长比较，避免通过响应时间推断密钥。
 */
internal suspend fun ApplicationCall.authorizeApiKey(expectedKey: String): Boolean {
    val supplied = request.header(API_KEY_HEADER)?.takeIf(String::isNotBlank)
        ?: request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.takeIf(String::isNotBlank)

    if (supplied != null && MessageDigest.isEqual(
            expectedKey.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    ) {
        return true
    }

    Metrics.renderFailed(ErrorCodes.UNAUTHORIZED)
    response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    respondApiError(
        this,
        ApiError(
            code = ErrorCodes.UNAUTHORIZED,
            message = "A valid API key is required",
            requestId = ensureRequestIdHeader(),
            status = HttpStatusCode.Unauthorized,
        ),
    )
    return false
}
