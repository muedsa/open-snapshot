package com.muedsa.snapshot.open

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.milliseconds

internal val SnapshotRateLimit = RateLimitName("snapshot")

fun Application.configureRateLimiting() {
    val requests = environment.config.propertyOrNull("snapshot.rate-limit.requests")
        ?.getString()?.toIntOrNull() ?: 6
    val windowMs = environment.config.propertyOrNull("snapshot.rate-limit.window-ms")
        ?.getString()?.toLongOrNull() ?: 60_000L
    require(requests > 0) { "snapshot.rate-limit.requests must be positive" }
    require(windowMs > 0) { "snapshot.rate-limit.window-ms must be positive" }

    install(RateLimit) {
        register(SnapshotRateLimit) {
            rateLimiter(limit = requests, refillPeriod = windowMs.milliseconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}
