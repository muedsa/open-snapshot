package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.buildErrorExcerpt
import com.muedsa.snapshot.open.drawParseErrorCard
import com.muedsa.snapshot.open.errorImageRenderer
import com.muedsa.snapshot.parser.TrackPos
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 解析错误摘录（纯函数）与 `?errorImage=png` 的 HTTP 行为。
 */
class ParseErrorImageTest {

    private val invalidSource = "<Snapshot><Container width=\"1\"height=\"1\"/></Snapshot>"
    private val validSource = "<Snapshot type=\"png\"><Container width=\"2\" height=\"2\" color=\"#FF00FF00\"/></Snapshot>"

    // ---------- 摘录纯函数 ----------

    @Test
    fun `excerpt shows context lines around the error line`() {
        val source = "line1\nline2\nline3\nline4\nline5\n"

        val excerpt = buildErrorExcerpt(source, pos(12, 3, 1), maxLines = 8, maxColumns = 40, contextLines = 1)

        assertEquals(listOf(2, 3, 4), excerpt.lines.map { it.number })
        assertEquals(1, excerpt.caretLineIndex)
        assertTrue(excerpt.lines[1].isErrorLine)
        assertEquals("line3", excerpt.lines[1].text)
        assertEquals("line 3, column 1", excerpt.location)
    }

    @Test
    fun `caret column is zero based and follows the reported column`() {
        val excerpt = buildErrorExcerpt("abc\ndefg\n", pos(6, 2, 3), maxLines = 8, maxColumns = 40, contextLines = 1)

        assertEquals(2, excerpt.caretColumn)
        assertEquals("defg", excerpt.lines.single { it.isErrorLine }.text)
    }

    @Test
    fun `tabs are expanded and the caret accounts for the expansion`() {
        val excerpt = buildErrorExcerpt("\tX\n", pos(1, 1, 2), maxLines = 8, maxColumns = 40, contextLines = 0)

        assertEquals("    X", excerpt.lines.single().text)
        assertEquals(4, excerpt.caretColumn)
    }

    @Test
    fun `long error lines keep a window around the caret`() {
        val long = "a".repeat(100) + "X" + "b".repeat(100)

        val excerpt = buildErrorExcerpt(long, pos(100, 1, 101), maxLines = 4, maxColumns = 21, contextLines = 0)

        val line = excerpt.lines.single()
        assertTrue(line.truncatedHead)
        assertTrue(line.truncatedTail)
        assertEquals(21, line.text.length)
        assertEquals(10, excerpt.caretColumn)
        assertEquals("X", line.text.substring(excerpt.caretColumn, excerpt.caretColumn + 1))
    }

    @Test
    fun `other long lines are truncated at the tail only`() {
        val source = "a".repeat(50) + "\nX\n"

        val excerpt = buildErrorExcerpt(source, pos(51, 2, 1), maxLines = 4, maxColumns = 10, contextLines = 1)

        val context = excerpt.lines.first()
        assertFalse(context.truncatedHead)
        assertTrue(context.truncatedTail)
        assertEquals(10, context.text.length)
    }

    @Test
    fun `crlf line endings are normalized`() {
        val excerpt = buildErrorExcerpt("a\r\nb\r\n", pos(3, 2, 1), maxLines = 4, maxColumns = 10, contextLines = 0)

        assertEquals("b", excerpt.lines.single().text)
    }

    @Test
    fun `line window is capped by maxLines and keeps the error line`() {
        val source = (1..20).joinToString("\n") { "line$it" }

        val excerpt = buildErrorExcerpt(source, pos(0, 10, 1), maxLines = 3, maxColumns = 40, contextLines = 5)

        assertTrue(excerpt.lines.any { it.isErrorLine && it.number == 10 })
        assertTrue(excerpt.lines.size <= 3, excerpt.lines.map { it.number }.toString())
    }

    @Test
    fun `line is derived from the position when line is unset`() {
        val excerpt = buildErrorExcerpt(
            "abc\ndef\n",
            TrackPos(5, TrackPos.UNSET, TrackPos.UNSET),
            maxLines = 4,
            maxColumns = 10,
            contextLines = 1,
        )

        assertEquals(1, excerpt.caretLineIndex)
        assertEquals(1, excerpt.caretColumn)
        assertEquals("position 5", excerpt.location)
    }

    @Test
    fun `unset position degrades to unknown location`() {
        val excerpt = buildErrorExcerpt(
            "abc\ndef\n",
            TrackPos(TrackPos.UNSET, TrackPos.UNSET, TrackPos.UNSET),
            maxLines = 4,
            maxColumns = 10,
            contextLines = 1,
        )

        assertEquals(-1, excerpt.caretLineIndex)
        assertEquals(-1, excerpt.caretColumn)
        assertEquals("unknown position", excerpt.location)
    }

    // ---------- HTTP 行为 ----------

    @Test
    fun `error image is returned when requested`() = testApplication {
        configure()

        val response = client.post("/snapshot?errorImage=png") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(invalidSource)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
        assertEquals(ErrorCodes.PARSE_ERROR, response.headers["X-Snapshot-Error-Code"])
        assertTrue(response.headers["X-Snapshot-Error-Location"].orEmpty().startsWith("line "))
        assertTrue(response.headers["X-Snapshot-Error-Position"].orEmpty().isNotEmpty())
        assertPng(response.body())

        val metrics = client.get("/metrics").bodyAsText()
        assertTrue(metrics.contains("""snapshot_error_images_total{result="served"} 1"""), metrics)
    }

    @Test
    fun `json error is unchanged without the parameter`() = testApplication {
        configure()

        val response = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(invalidSource)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertTrue(response.bodyAsText().contains(ErrorCodes.PARSE_ERROR))
        assertNull(response.headers["X-Snapshot-Error-Code"])
    }

    @Test
    fun `valid dsl ignores the error image parameter`() = testApplication {
        configure()

        val response = client.post("/snapshot?errorImage=png") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(validSource)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertPng(response.body())
        assertNull(response.headers["X-Snapshot-Error-Code"])
    }

    @Test
    fun `unsupported error image format falls back to json`() = testApplication {
        configure()

        val response = client.post("/snapshot?errorImage=svg") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(invalidSource)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
        assertTrue(response.bodyAsText().contains(ErrorCodes.PARSE_ERROR))

        val metrics = client.get("/metrics").bodyAsText()
        assertTrue(metrics.contains("""snapshot_error_images_total{result="unsupported"} 1"""), metrics)
    }

    @Test
    fun `error image can be disabled`() = testApplication {
        configure(overrides = { put("snapshot.error-image.enabled", "false") })

        val response = client.post("/snapshot?errorImage=png") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(invalidSource)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `error image render failure falls back to the json error`() = testApplication {
        configure()
        errorImageRenderer = { _, _, _ -> throw IllegalStateException("renderer unavailable") }

        try {
            val response = client.post("/snapshot?errorImage=png") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(invalidSource)
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/json"))
            assertTrue(response.bodyAsText().contains(ErrorCodes.PARSE_ERROR))

            val metrics = client.get("/metrics").bodyAsText()
            assertTrue(metrics.contains("""snapshot_error_images_total{result="fallback"} 1"""), metrics)
        } finally {
            errorImageRenderer = ::drawParseErrorCard
        }
    }

    private fun assertPng(bytes: ByteArray) {
        assertTrue(bytes.size > 8, "响应体过小: ${bytes.size}")
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), bytes.copyOf(4))
    }

    private fun pos(pos: Int, line: Int, column: Int) = TrackPos(pos, line, column)


}
