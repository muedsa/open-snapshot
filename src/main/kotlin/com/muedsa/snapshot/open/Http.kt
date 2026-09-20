package com.muedsa.snapshot.open

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*

fun Application.configureHttp() {
    val cors = snapshotConfig().cors
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowHeader(API_KEY_HEADER)
        exposeHeader("X-Request-Id")
        exposeHeader(HttpHeaders.RetryAfter)
        exposeHeader("X-RateLimit-Limit")
        exposeHeader("X-RateLimit-Remaining")
        exposeHeader("X-RateLimit-Reset")
        allowNonSimpleContentTypes = true
        cors.allowedHosts.forEach(::allowHost)
    }
    if (cors.trustProxyHeaders) {
        install(XForwardedHeaders)
    }
}
