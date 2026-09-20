package com.muedsa.snapshot.open

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*

fun Application.configureHttp() {
    val allowedHosts = environment.config
        .propertyOrNull("snapshot.cors.allowed-hosts")?.getList().orEmpty()
    val trustProxyHeaders = environment.config
        .propertyOrNull("snapshot.trust-proxy-headers")?.getString()?.toBooleanStrictOrNull() ?: false
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        exposeHeader("X-Request-Id")
        exposeHeader(HttpHeaders.RetryAfter)
        allowNonSimpleContentTypes = true
        allowedHosts.forEach(::allowHost)
    }
    if (trustProxyHeaders) {
        install(XForwardedHeaders)
    }
}
