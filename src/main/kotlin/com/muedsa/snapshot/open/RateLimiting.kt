package com.muedsa.snapshot.open

import io.github.flaxoos.ktor.server.plugins.ratelimiter.*
import io.github.flaxoos.ktor.server.plugins.ratelimiter.implementations.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureRateLimiting() {
    routing {
        route("/"){
            install(RateLimiting) {
                rateLimiter {
                    type = TokenBucket::class
                    capacity = 3
                    rate = 10.seconds
                }
            }
        }
    }
}
