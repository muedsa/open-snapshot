package com.muedsa.snapshot.open

import io.ktor.http.HttpStatusCode

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
