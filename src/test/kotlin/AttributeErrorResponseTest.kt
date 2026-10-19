package com.muedsa.snapshot

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.muedsa.snapshot.open.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.test.*

class AttributeErrorResponseTest {
    @Test
    fun `enum error exposes actionable metadata and source positions`() = testApplication {
        configure()
        val source = "<Snapshot>\n  <Row mainAxisAlignment=\"MIDDLE\"/>\n</Snapshot>"
        val response = client.post("/snapshot") { setBody(source) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("PARSE_ERROR", json.text("code"))
        assertEquals(response.headers["X-Request-Id"], json.text("requestId"))
        val diagnostic = json.getValue("diagnostic").jsonObject
        assertEquals("mainAxisAlignment", diagnostic.text("attributeName"))
        assertEquals("Row", diagnostic.text("elementName"))
        assertEquals("present", diagnostic.text("valueState"))
        assertEquals(listOf("START", "END", "CENTER", "SPACE_BETWEEN", "SPACE_AROUND", "SPACE_EVENLY"),
            diagnostic.getValue("allowedValues").jsonArray.map { it.jsonPrimitive.content })
        val position = diagnostic.getValue("position").jsonObject
        assertEquals(source.indexOf("MIDDLE"), position.getValue("offset").jsonPrimitive.int)
        assertEquals(2, position.getValue("line").jsonPrimitive.int)
        assertEquals(source.indexOf("MIDDLE") - source.indexOf('\n'), position.getValue("column").jsonPrimitive.int)
        assertContains(json.text("message"), "格式：")
        assertFalse(response.bodyAsText().contains("MIDDLE"))
    }

    @Test
    fun `missing valueless and empty attributes remain distinguishable`() = testApplication {
        configure()
        for ((attribute, state) in listOf("" to "missing", "aspectRatio" to "unassigned", "aspectRatio=\"\"" to "empty")) {
            val source = "<Snapshot><AspectRatio $attribute/></Snapshot>"
            val response = client.post("/snapshot") { setBody(source) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val diagnostic = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("diagnostic").jsonObject
            assertEquals(state, diagnostic.text("valueState"))
            val expectedOffset = if (state == "missing") source.indexOf("<AspectRatio") else source.indexOf("aspectRatio")
            assertEquals(expectedOffset, diagnostic.getValue("position").jsonObject.getValue("offset").jsonPrimitive.int)
        }
    }

    @Test
    fun `root inline and restricted attributes preserve their own context`() = testApplication {
        configure()
        val cases = listOf(
            Triple("<Snapshot type=\"gif\"/>", "Snapshot", listOf("png", "jpg", "webp")),
            Triple("<Snapshot><Text><Emoji url=\"https://example.com/a.png\" fit=\"BIG\"/></Text></Snapshot>", "Emoji", listOf("SCALE_DOWN")),
            Triple("<Snapshot><ClipPath fit=\"COVER\"/></Snapshot>", "ClipPath", listOf("FILL", "CONTAIN")),
        )
        for ((source, element, expectedChoices) in cases) {
            val response = client.post("/snapshot") { setBody(source) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("PARSE_ERROR", json.text("code"))
            val diagnostic = json.getValue("diagnostic").jsonObject
            assertEquals(element, diagnostic.text("elementName"))
            val choices = diagnostic.getValue("allowedValues").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(choices.containsAll(expectedChoices))
            if (element == "ClipPath") assertEquals(expectedChoices, choices)
        }
    }

    @Test
    fun `image wording in attribute values still allows png errors`() = testApplication {
        configure()
        val response = client.post("/snapshot?errorImage=png") {
            setBody("<Snapshot><ClipPath viewBox=\"${"x".repeat(50)}image URL\"/></Snapshot>")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("PARSE_ERROR", response.headers["X-Snapshot-Error-Code"])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
        val bytes = response.body<ByteArray>()
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), bytes.take(4).toByteArray())
        // 留下本地可查看的渲染产物，检查中文提示及换行。
        File("build/attribute-error-preview.png").writeBytes(bytes)
    }

    @Test
    fun `image causes keep their code and private values out of responses and logs`() = testApplication {
        configure()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            for (attribute in listOf(
                "dataUri=\"data:image/png;base64,PRIVATE_PAYLOAD-invalid\"",
                "url=\"https://USER:PASSWORD@host.invalid/INVALID SPACE?token=PRIVATE_TOKEN\"",
            )) {
                val response = client.post("/snapshot?errorImage=png") { setBody("<Snapshot><Image $attribute/></Snapshot>") }
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
                val body = response.bodyAsText()
                assertEquals("IMAGE_LOAD_ERROR", Json.parseToJsonElement(body).jsonObject.text("code"))
                assertFalse(body.contains("PRIVATE_"))
                assertFalse(body.contains("PASSWORD"))
            }
            val failures = appender.list.filter { it.formattedMessage.startsWith("Snapshot request failed") }
            assertEquals(2, failures.size)
            failures.forEach {
                assertNull(it.throwableProxy)
                assertFalse(it.formattedMessage.contains("PRIVATE_"))
                assertFalse(it.formattedMessage.contains("PASSWORD"))
            }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `png receives masked source and independent format choice example sections`() = testApplication {
        configure()
        var captured = false
        errorImageRenderer = { excerpt, message, _ ->
            captured = true
            assertFalse(excerpt.lines.any { "PRIVATE_" in it.text || "PASSWORD" in it.text })
            assertContains(message, "格式：")
            assertContains(message, "可用值：")
            assertContains(message, "示例：")
            assertTrue(message.length <= MAX_ERROR_CARD_MESSAGE)
            throw IllegalStateException("PRIVATE_RENDERER_FAILURE")
        }
        try {
            val source = "<Snapshot><Text><Emoji url='https://USER:PASSWORD@host/a?token=PRIVATE_TOKEN' fit='BIG'/></Text></Snapshot>"
            val response = client.post("/snapshot?errorImage=png") { setBody(source) }
            assertTrue(captured)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("PARSE_ERROR", json.text("code"))
            assertEquals("fit", json.getValue("diagnostic").jsonObject.text("attributeName"))
            assertFalse(response.bodyAsText().contains("PRIVATE_"))
            assertFalse(response.bodyAsText().contains("PASSWORD"))
        } finally {
            errorImageRenderer = ::drawParseErrorCard
        }
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
