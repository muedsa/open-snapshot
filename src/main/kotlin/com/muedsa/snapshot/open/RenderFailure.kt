package com.muedsa.snapshot.open

import com.muedsa.snapshot.parser.ParseException
import io.ktor.http.HttpStatusCode
import java.util.Collections
import java.util.IdentityHashMap

internal const val MAX_RENDER_ERROR_MESSAGE = 1024
internal const val MAX_ERROR_CARD_MESSAGE = 768

/** 用异常类型区分空请求，避免消息内容参与错误码判定。 */
internal class EmptySnapshotRequest : IllegalArgumentException("Snapshot request body must not be empty")

/** 错误响应、错误图和日志共用分类；任何错误码都不依赖可包含用户输入的 message。 */
internal fun renderFailure(error: Throwable, source: String, requestId: String): ApiError {
    val chain = error.causeChain()
    val parseError = chain.filterIsInstance<ParseException>().firstOrNull()
    val imageError = chain.filterIsInstance<ImageLoadException>().firstOrNull()
    val code = when {
        imageError != null -> ErrorCodes.IMAGE_LOAD_ERROR
        chain.any { it is EmptySnapshotRequest } -> ErrorCodes.EMPTY_REQUEST
        parseError != null -> ErrorCodes.PARSE_ERROR
        error is IllegalArgumentException || error is IllegalStateException -> ErrorCodes.RENDER_ERROR
        else -> ErrorCodes.INTERNAL_ERROR
    }
    val diagnostic = parseError?.publicDiagnostic()
    val message = when {
        code == ErrorCodes.INTERNAL_ERROR -> "Snapshot rendering failed"
        // ImageLoadException 由服务构造，消息不含 URL、原始 Data URI 或底层 cause.message。
        imageError != null -> safeErrorText(imageError.message ?: "Image loading failed")
        diagnostic != null -> diagnostic.describe()
        else -> safeErrorText(error.message ?: "Snapshot rendering failed")
    }
    val location = parseError?.pos?.pos?.takeIf { it >= 0 }?.let { " at position $it" }.orEmpty()
    // 属性错误不再重复输出原值；结构错误仍保留经过隐藏处理的上下文。
    val excerpt = if (parseError != null && diagnostic == null && parseError.pos.pos >= 0 && source.isNotEmpty()) {
        val position = parseError.pos.pos.coerceAtMost(source.length)
        " near: " + safeErrorText(
            redactErrorSource(source).substring((position - 30).coerceAtLeast(0), (position + 30).coerceAtMost(source.length)),
        )
    } else ""
    val suffix = location + excerpt
    return ApiError(
        code = code,
        message = message.boundedText(MAX_RENDER_ERROR_MESSAGE - suffix.length) + suffix,
        requestId = requestId,
        status = if (code == ErrorCodes.INTERNAL_ERROR) HttpStatusCode.InternalServerError else HttpStatusCode.BadRequest,
        diagnostic = diagnostic,
    )
}

/** 防止异常 cause 环或过长异常链影响错误处理。 */
internal fun Throwable.causeChain(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val result = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && result.size < 32 && seen.add(current)) {
        result += current
        current = current.cause
    }
    return result
}

private fun ParseException.publicDiagnostic(): ApiAttributeDiagnostic? {
    val diagnostic = attributeDiagnostic ?: return null
    val rawValue = diagnostic.rawValue
    val info = diagnostic.valueInfo
    val name = diagnostic.attributeName.boundedText(128)
    val element = diagnostic.elementName?.boundedText(128)
    val format = info?.format?.boundedText(512)
    // 不把截断后的候选当成合法值输出；超长条目直接省略，并显式标记信息不完整。
    val allowed = info?.allowedValues.orEmpty().filter { it.length <= 128 }.take(64)
    val examples = info?.examples.orEmpty().filter { it.length <= 160 }.take(3)
    val position = ApiErrorPosition(
        offset = pos.pos.takeIf { it >= 0 },
        line = pos.line.takeIf { it >= 1 },
        column = pos.column.takeIf { it >= 1 },
    ).takeIf { it.offset != null || it.line != null || it.column != null }
    return ApiAttributeDiagnostic(
        attributeName = name,
        elementName = element,
        valueState = when {
            !diagnostic.attributePresent -> "missing"
            rawValue == null -> "unassigned"
            rawValue.isEmpty() -> "empty"
            else -> "present"
        },
        position = position,
        format = format,
        allowedValues = allowed,
        examples = examples,
        truncated = name != diagnostic.attributeName || element != diagnostic.elementName ||
            format != info?.format || allowed != info?.allowedValues.orEmpty() || examples != info?.examples.orEmpty(),
    )
}

private fun ApiAttributeDiagnostic.summary(): String {
    val reason = when (valueState) {
        "missing" -> "缺少必填属性"
        "unassigned" -> "属性未赋值"
        "empty" -> "属性值为空"
        else -> "属性值无效"
    }
    return "$reason [$attributeName]" + (elementName?.let { "，元素 <$it>" } ?: "")
}

private fun ApiAttributeDiagnostic.describe(): String = buildList {
    add(summary())
    format?.let { add("格式：$it") }
    if (allowedValues.isNotEmpty()) add("可用值：${allowedValues.joinToString()}")
    if (examples.isNotEmpty()) add("示例：${examples.joinToString(" / ")}")
    if (truncated) add("部分提示已省略")
}.joinToString("\n")

/** 为每部分保留独立额度，长错误摘要不能挤掉格式、候选和示例。 */
internal fun errorCardMessage(failure: ApiError): String {
    val diagnostic = failure.diagnostic ?: return failure.message.boundedText(MAX_ERROR_CARD_MESSAGE)
    return buildList {
        add(diagnostic.summary().boundedText(160))
        diagnostic.format?.let { add("格式：${it.boundedText(200)}") }
        if (diagnostic.allowedValues.isNotEmpty()) {
            add("可用值：${diagnostic.allowedValues.joinToString().boundedText(160)}")
        }
        if (diagnostic.examples.isNotEmpty()) {
            add("示例：${diagnostic.examples.joinToString(" / ").boundedText(160)}")
        }
        if (diagnostic.truncated) add("部分提示已省略")
    }.joinToString("\n").boundedText(MAX_ERROR_CARD_MESSAGE)
}

/** 字符总量之外限制显式换行，避免含大量换行的异常撑高错误卡片。 */
internal fun boundedErrorCardText(message: String): String = message.boundedText(MAX_ERROR_CARD_MESSAGE)
    .lineSequence().take(8).joinToString("\n") { it.boundedText(160) }

/** 只记录可控元数据；包括内部错误也不把可能含输入的 throwable/cause.message 交给日志框架。 */
internal fun renderFailureLog(error: Throwable, failure: ApiError, elapsedNanos: Long): String = buildString {
    append("Snapshot request failed requestId=").append(sanitizeLogValue(failure.requestId, 64))
    append(" code=").append(failure.code)
    append(" elapsedNanos=").append(elapsedNanos)
    append(" errorType=").append(sanitizeLogValue(error.javaClass.name, 128))
    failure.diagnostic?.let {
        append(" attribute=").append(sanitizeLogValue(it.attributeName, 128))
        append(" valueState=").append(it.valueState)
    }
    error.causeChain().filterIsInstance<ParseException>().firstOrNull()?.pos?.let {
        append(" position=").append(it.pos)
        append(" line=").append(it.line)
        append(" column=").append(it.column)
    }
    if (failure.code == ErrorCodes.INTERNAL_ERROR) {
        append(" stack=").append(sanitizeLogValue(error.stackTrace.take(8).joinToString(";"), 1024))
    }
}

/** 隐藏完整 URL/Data URI 及常见凭据属性值，保留 UTF-16 长度与换行，供 caret 定位。 */
internal fun redactErrorSource(source: String): String {
    val result = source.toCharArray()
    fun mask(range: IntRange) {
        for (index in range) if (result[index] != '\r' && result[index] != '\n') result[index] = '•'
    }
    SENSITIVE_ATTRIBUTE.findAll(source).forEach { match ->
        match.groups.drop(1).firstOrNull { it != null }?.let { mask(it.range) }
    }
    URI_IN_TEXT.findAll(source).forEach { mask(it.range) }
    return result.concatToString()
}

private val SENSITIVE_ATTRIBUTE = Regex(
    """(?i)(?<![\w:-])(?:url|dataUri|password|token|apiKey|authorization)\s*=\s*(?:"([^"]*)(?:"|$)|'([^']*)(?:'|$)|([^\s>]+))""",
)
// 仅从 scheme 字符串起点尝试，避免 a-a-a- 等长输入在每个连字符后重试造成平方级扫描。
private val URI_IN_TEXT = Regex("""(?i)(?<![a-z0-9+.-])(?:[a-z][a-z0-9+.-]*://|data:)[^\s"'<>]+""")

private fun safeErrorText(value: String): String = redactErrorSource(value).boundedText(MAX_RENDER_ERROR_MESSAGE)

/** 限长并清理显示控制符；不在 UTF-16 代理对中间截断。 */
internal fun String.boundedText(maxLength: Int): String {
    require(maxLength > 0)
    var end = if (length > maxLength) maxLength - 1 else length
    if (end > 0 && end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return buildString {
        for (index in 0 until end) {
            val char = this@boundedText[index]
            append(if ((char.isISOControl() && char != '\n' && char != '\t') || Character.getType(char) == Character.FORMAT.toInt()) ' ' else char)
        }
        if (end < this@boundedText.length) append('…')
    }
}
