package com.muedsa.snapshot.open

import io.ktor.http.HttpStatusCode

/** 对外错误码的唯一来源，OpenAPI 文档中的 `code` 枚举与之一致。 */
internal object ErrorCodes {
    const val EMPTY_REQUEST = "EMPTY_REQUEST"
    const val PARSE_ERROR = "PARSE_ERROR"
    const val RENDER_ERROR = "RENDER_ERROR"
    const val IMAGE_LOAD_ERROR = "IMAGE_LOAD_ERROR"
    const val REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
    const val RENDER_TIMEOUT = "RENDER_TIMEOUT"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    const val NOT_READY = "NOT_READY"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    val ALL: Set<String> = setOf(
        EMPTY_REQUEST,
        PARSE_ERROR,
        RENDER_ERROR,
        IMAGE_LOAD_ERROR,
        REQUEST_TOO_LARGE,
        RENDER_TIMEOUT,
        RATE_LIMITED,
        SERVICE_UNAVAILABLE,
        NOT_READY,
        UNAUTHORIZED,
        INTERNAL_ERROR,
    )
}

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val status: HttpStatusCode,
)

fun ApiError.toJson(): String = """
    {"code":"${jsonEscape(code)}","message":"${jsonEscape(message)}","requestId":"${jsonEscape(requestId)}"}
""".trimIndent()

private fun jsonEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}
