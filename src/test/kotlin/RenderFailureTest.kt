package com.muedsa.snapshot

import com.muedsa.snapshot.open.*
import com.muedsa.snapshot.parser.AttributeDiagnostic
import com.muedsa.snapshot.parser.ParseException
import com.muedsa.snapshot.parser.TrackPos
import com.muedsa.snapshot.parser.attr.AttributeValueInfo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.*
import kotlin.test.*

class RenderFailureTest {
    @Test
    fun `classification ignores input text and follows typed image causes`() {
        val source = "<Snapshot><ClipPath viewBox=\"${"x".repeat(50)}image URL\"/></Snapshot>"
        val error = assertFailsWith<ParseException> { SnapshotService.render(source) }
        assertEquals(ErrorCodes.PARSE_ERROR, renderFailure(error, source, "id").code)
        assertEquals(ErrorCodes.RENDER_ERROR, renderFailure(IllegalArgumentException("must not be empty image URL"), "", "id").code)
        assertEquals(ErrorCodes.EMPTY_REQUEST, renderFailure(EmptySnapshotRequest(), "", "id").code)
        val wrapped = ParseException(TrackPos(), "not an image message", ImageLoadException("invalid image"))
        assertEquals(ErrorCodes.IMAGE_LOAD_ERROR, renderFailure(wrapped, "", "id").code)
    }

    @Test
    fun `large attribute input is absent from response and log but hints remain`() {
        val value = "PRIVATE_INPUT_" + "x".repeat(8192)
        val source = "<Snapshot><SizedBox width=\"$value\"/></Snapshot>"
        val error = assertFailsWith<ParseException> { SnapshotService.render(source) }
        assertTrue(error.message.orEmpty().contains(value))
        val failure = renderFailure(error, source, "test-id")
        assertTrue(failure.message.length <= MAX_RENDER_ERROR_MESSAGE)
        assertFalse(failure.toJson().contains("PRIVATE_INPUT_"))
        assertFalse(failure.toJson().contains("rawValue"))
        assertContains(failure.message, "浮点数")
        val log = renderFailureLog(error, failure, 100)
        assertContains(log, "code=PARSE_ERROR")
        assertContains(log, "attribute=width")
        assertFalse(log.contains("PRIVATE_INPUT_"))
        assertFalse(log.contains('\n'))
    }

    @Test
    fun `unknown positions and optional metadata are omitted`() {
        val error = ParseException(TrackPos(), "private", null, AttributeDiagnostic("custom", null, false, null))
        val json = Json.parseToJsonElement(renderFailure(error, "", "id").toJson()).jsonObject
        val diagnostic = json.getValue("diagnostic").jsonObject
        assertEquals("missing", diagnostic.getValue("valueState").jsonPrimitive.content)
        assertFalse(diagnostic.containsKey("position"))
        assertFalse(diagnostic.containsKey("elementName"))
        assertFalse(diagnostic.containsKey("format"))
        assertEquals(emptyList(), diagnostic.getValue("allowedValues").jsonArray)
        assertFalse(json.getValue("message").jsonPrimitive.content.contains("position 0"))
        assertNull(renderFailure(ParseException(TrackPos(), "structure"), "", "id").diagnostic)
    }

    @Test
    fun `large custom metadata is bounded without inventing truncated candidate values`() {
        val values = (1..70).map { "CHOICE_$it" }
        val info = AttributeValueInfo("f".repeat(2000), listOf("x".repeat(161), "ok", "two", "three", "four"), values)
        val error = ParseException(TrackPos(0, 1, 1), "private", null, AttributeDiagnostic("custom", "secret", true, info))
        val failure = renderFailure(error, "", "id")
        val diagnostic = assertNotNull(failure.diagnostic)
        assertTrue(diagnostic.truncated)
        assertEquals(values.take(64), diagnostic.allowedValues)
        assertEquals(listOf("ok", "two", "three"), diagnostic.examples)
        assertEquals(512, diagnostic.format?.length)
        assertTrue(failure.message.length <= MAX_RENDER_ERROR_MESSAGE)
        assertContains(failure.message, "at position 0")
        val card = errorCardMessage(failure)
        assertTrue(card.length <= MAX_ERROR_CARD_MESSAGE)
        assertContains(card, "格式：")
        assertContains(card, "可用值：")
        assertContains(card, "示例：")
    }

    @Test
    fun `json serializer preserves chinese newlines quotes and controls`() {
        val message = "中文\n\"quoted\"\\\t\u0001"
        val failure = ApiError("PARSE_ERROR", message, "id", HttpStatusCode.BadRequest,
            ApiAttributeDiagnostic("custom", "present", format = message, examples = listOf(message)))
        val json = Json.parseToJsonElement(failure.toJson()).jsonObject
        assertEquals(message, json.getValue("message").jsonPrimitive.content)
        assertEquals(message, json.getValue("diagnostic").jsonObject.getValue("examples").jsonArray.single().jsonPrimitive.content)
        val ordinary = Json.parseToJsonElement(failure.copy(diagnostic = null).toJson()).jsonObject
        assertEquals(setOf("code", "message", "requestId"), ordinary.keys)
    }

    @Test
    fun `source masking handles quoted unquoted multiline and incomplete sensitive values`() {
        for (source in listOf(
            "<Image url=\"https://user:SECRET@host/path?token=SECRET\"/>",
            "<Image dataUri='data:image/png;base64,SECRET\nSECRET'/>",
            "<Image dataUri=SECRET/>",
            "<Image url=\"SECRET\nSECRET",
            "<Text token='SECRET' password=SECRET apiKey=SECRET authorization=SECRET/>",
            "input https://host/SECRET and data:image/png;base64,SECRET",
        )) {
            val masked = redactErrorSource(source)
            assertEquals(source.length, masked.length)
            assertEquals(source.indices.filter { source[it] == '\n' }, masked.indices.filter { masked[it] == '\n' })
            assertFalse(masked.contains("SECRET"), masked)
        }
    }

    @Test
    fun `fallback messages and excerpts hide sources before truncating`() {
        val source = "<Image url=\"https://host/${"x".repeat(80)}SECRET\"/>"
        val offset = source.indexOf("SECRET")
        val failure = renderFailure(ParseException(TrackPos(offset, 1, offset + 1), source), source, "id")
        assertFalse(failure.message.contains("SECRET"))
        assertContains(failure.message, "at position $offset")
        assertTrue(failure.message.length <= MAX_RENDER_ERROR_MESSAGE)
    }

    @Test
    fun `long scheme-like input does not repeatedly rescan every separator`() {
        val prefix = "a-".repeat(65_536)
        val source = "$prefix https://host/SECRET"
        val masked = redactErrorSource(source)
        assertTrue(masked.startsWith(prefix))
        assertEquals(source.length, masked.length)
        assertFalse(masked.contains("SECRET"))
    }

    @Test
    fun `internal errors hide exception messages and cyclic causes terminate`() {
        val first = RuntimeException("SECRET\nforged log")
        val second = RuntimeException("SECRET cause", first)
        first.initCause(second)
        val failure = renderFailure(first, "SECRET source", "id")
        assertEquals(ErrorCodes.INTERNAL_ERROR, failure.code)
        assertEquals(HttpStatusCode.InternalServerError, failure.status)
        assertEquals("Snapshot rendering failed", failure.message)
        assertNull(failure.diagnostic)
        val log = renderFailureLog(first, failure, 0)
        assertFalse(log.contains("SECRET"))
        assertFalse(log.contains('\n'))
        assertContains(log, "stack=")
    }

    @Test
    fun `text budget does not split supplementary unicode characters`() {
        val clipped = ("a".repeat(8) + "😀" + "tail").boundedText(10)
        assertEquals("a".repeat(8) + "…", clipped)
        assertEquals("line\n\t  ", "line\n\t\u0001\u202e".boundedText(20))
        val card = boundedErrorCardText(("x\n").repeat(1024))
        assertEquals(8, card.lines().size)
        assertTrue(boundedErrorCardText("x".repeat(1024)).length <= 160)
    }
}
