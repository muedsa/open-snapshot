package com.muedsa.snapshot.open

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlin.time.Duration.Companion.milliseconds

internal val SnapshotRateLimit = RateLimitName("snapshot")

fun Application.configureRateLimiting() {
    val settings = snapshotConfig().rateLimit

    install(RateLimit) {
        register(SnapshotRateLimit) {
            rateLimiter(limit = settings.requests, refillPeriod = settings.windowMs.milliseconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }
}
