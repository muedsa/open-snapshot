package com.muedsa.snapshot.open

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 对外错误码的唯一来源，OpenAPI 文档中的 `code` 枚举与之一致。 */
internal object ErrorCodes {
    const val EMPTY_REQUEST = "EMPTY_REQUEST"
    const val PARSE_ERROR = "PARSE_ERROR"
    const val RENDER_ERROR = "RENDER_ERROR"
    const val IMAGE_LOAD_ERROR = "IMAGE_LOAD_ERROR"
    const val REQUEST_TOO_LARGE = "REQUEST_TOO_LARGE"
    const val RENDER_TIMEOUT = "RENDER_TIMEOUT"
    const val FONT_NOT_FOUND = "FONT_NOT_FOUND"
    const val INVALID_QUERY = "INVALID_QUERY"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val QUEUE_FULL = "QUEUE_FULL"
    const val QUEUE_TIMEOUT = "QUEUE_TIMEOUT"
    const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    const val NOT_READY = "NOT_READY"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    val ALL: Set<String> = setOf(
        EMPTY_REQUEST,
        PARSE_ERROR,
        RENDER_ERROR,
        IMAGE_LOAD_ERROR,
        REQUEST_TOO_LARGE,
        RENDER_TIMEOUT,
        FONT_NOT_FOUND,
        INVALID_QUERY,
        RATE_LIMITED,
        QUEUE_FULL,
        QUEUE_TIMEOUT,
        SERVICE_UNAVAILABLE,
        NOT_READY,
        UNAUTHORIZED,
        FORBIDDEN,
        INTERNAL_ERROR,
    )
}

data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val status: HttpStatusCode,
    val diagnostic: ApiAttributeDiagnostic? = null,
)

/** 对外 DTO 不包含原始属性值或异常链。未知位置不填，不用 0 冒充有效位置。 */
data class ApiErrorPosition(val offset: Int? = null, val line: Int? = null, val column: Int? = null)

data class ApiAttributeDiagnostic(
    val attributeName: String,
    val valueState: String,
    val elementName: String? = null,
    val position: ApiErrorPosition? = null,
    val format: String? = null,
    val allowedValues: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val truncated: Boolean = false,
)

fun ApiError.toJson(): String = buildJsonObject {
    put("code", code)
    put("message", message)
    put("requestId", requestId)
    diagnostic?.let { diagnostic ->
        put("diagnostic", buildJsonObject {
            put("attributeName", diagnostic.attributeName)
            put("valueState", diagnostic.valueState)
            diagnostic.elementName?.let { put("elementName", it) }
            diagnostic.position?.let { position ->
                put("position", buildJsonObject {
                    position.offset?.let { put("offset", it) }
                    position.line?.let { put("line", it) }
                    position.column?.let { put("column", it) }
                })
            }
            diagnostic.format?.let { put("format", it) }
            put("allowedValues", JsonArray(diagnostic.allowedValues.map(::JsonPrimitive)))
            put("examples", JsonArray(diagnostic.examples.map(::JsonPrimitive)))
            if (diagnostic.truncated) put("truncated", true)
        })
    }
}.toString()
