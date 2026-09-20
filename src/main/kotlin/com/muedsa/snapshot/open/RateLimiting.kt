package com.muedsa.snapshot.open

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.RateLimitProviderConfig
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.header
import io.ktor.util.AttributeKey
import kotlin.time.Duration.Companion.milliseconds

internal val SnapshotAnonymousRateLimit = RateLimitName("snapshot-anonymous")
internal val SnapshotCredentialRateLimit = RateLimitName("snapshot-credential")
internal val SnapshotAdminRateLimit = RateLimitName("snapshot-admin")

internal const val RATE_LIMIT_SCOPE_ANONYMOUS = "anonymous"
internal const val RATE_LIMIT_SCOPE_CREDENTIAL = "credential"
internal const val RATE_LIMIT_SCOPE_ADMIN = "admin"

/** 无法归因到具体层级的限流（理论上不应出现）。 */
internal const val RATE_LIMIT_SCOPE_UNKNOWN = "unknown"

/** 不适用于当前身份的限流器共享同一个键，权重为 0，不消耗配额。 */
private const val SKIPPED_RATE_LIMIT_KEY = "skipped"

private val RateLimitScopeKey = AttributeKey<String>("SnapshotRateLimitScope")

/** 触发限流时记录命中的层级，用于指标归因。 */
internal fun ApplicationCall.rateLimitedScope(): String? = attributes.getOrNull(RateLimitScopeKey)

/**
 * 分层限流：
 *
 * - 匿名调用方按来源 IP 计桶（`snapshot.rate-limit.requests`）；
 * - 已认证调用方按凭据计桶（`snapshot.rate-limit.credential-requests`），不受匿名桶约束；
 * - 管理接口（如字体预览图）单独计桶（`snapshot.rate-limit.admin-requests`）。
 *
 * 通过 `requestWeight` 返回 0 跳过不适用当前身份的限流器，使各层互不影响。
 */
fun Application.configureRateLimiting() {
    val settings = snapshotConfig().rateLimit

    install(RateLimit) {
        register(SnapshotAnonymousRateLimit) {
            rateLimiter(settings.anonymousRequests, settings.anonymousWindowMs.milliseconds)
            applyTo(
                scope = RATE_LIMIT_SCOPE_ANONYMOUS,
                applies = { call -> !call.identity().isAuthenticated },
                bucketKey = { call -> call.rateLimitIdentity(RATE_LIMIT_SCOPE_ANONYMOUS) },
            )
        }
        register(SnapshotCredentialRateLimit) {
            rateLimiter(settings.credentialRequests, settings.credentialWindowMs.milliseconds)
            applyTo(
                scope = RATE_LIMIT_SCOPE_CREDENTIAL,
                applies = { call -> call.identity().isAuthenticated },
                bucketKey = { call -> call.rateLimitIdentity(RATE_LIMIT_SCOPE_CREDENTIAL) },
            )
        }
        register(SnapshotAdminRateLimit) {
            rateLimiter(settings.adminRequests, settings.adminWindowMs.milliseconds)
            applyTo(
                scope = RATE_LIMIT_SCOPE_ADMIN,
                applies = { call -> call.identity().isAdmin },
                bucketKey = { call -> call.adminRateLimitIdentity() },
            )
        }
    }
}

private fun RateLimitProviderConfig.applyTo(
    scope: String,
    applies: (ApplicationCall) -> Boolean,
    bucketKey: (ApplicationCall) -> String,
) {
    requestKey { call -> if (applies(call)) bucketKey(call) else SKIPPED_RATE_LIMIT_KEY }
    requestWeight { call, _ -> if (applies(call)) 1 else 0 }
    modifyResponse { call, state ->
        if (!applies(call)) return@modifyResponse
        when (state) {
            is RateLimiter.State.Available -> {
                call.response.headers.appendIfAbsent("X-RateLimit-Limit", state.limit.toString())
                call.response.headers.appendIfAbsent("X-RateLimit-Remaining", state.remainingTokens.toString())
                call.response.headers.appendIfAbsent("X-RateLimit-Reset", (state.refillAtTimeMillis / 1000).toString())
            }

            is RateLimiter.State.Exhausted -> {
                call.attributes.put(RateLimitScopeKey, scope)
                if (!call.response.headers.contains(HttpHeaders.RetryAfter)) {
                    val retryAfterSeconds = ((state.toWait.inWholeMilliseconds + 999) / 1000).coerceAtLeast(1L)
                    call.response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
                }
            }
        }
    }
}
