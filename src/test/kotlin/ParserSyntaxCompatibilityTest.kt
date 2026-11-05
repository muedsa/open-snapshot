package com.muedsa.snapshot

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import kotlin.test.*

/** 验证服务实际使用的 Parser JAR，而不依赖始终相同的 SNAPSHOT 版本字符串。 */
class ParserSyntaxCompatibilityTest {
    @Test
    fun `new compound syntax renders through the public endpoint`() = testApplication {
        configure()
        val source = """
            <Snapshot type=" PNG " debug>
                <Container width="160" height="100" padding="4 8 6" margin="2 3"
                    alignment="center" transformAlignment="0,0"
                    transform="translate(1,0) rotate(0deg) scale(1)" borderRadius="12 / 6"
                    border="1 red" boxShadow="0 2 red 4" gradientType="linear"
                    gradientColors="red,blue" gradientStops="0 1">
                    <Text fontFamily=" DejaVu Serif , Noto Serif SC " strutFontFamily=" DejaVu Serif , Noto Serif SC "
                        fontStyle="bold-italic" decoration="underline line-through" textShadow="0 2 red 4"
                        strutEnabled softWrap subpixel=" TrUe ">Snapshot</Text>
                </Container>
            </Snapshot>
        """.trimIndent()
        val response = client.post("/snapshot") { setBody(source) }
        assertEquals(HttpStatusCode.OK, response.status, response.headers[HttpHeaders.ContentType])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
        Image.makeFromEncoded(response.body<ByteArray>()).use {
            assertTrue(it.width > 0 && it.height > 0)
        }
    }

    @Test
    fun `new padding order and legacy tuple order produce their documented canvas sizes`() = testApplication {
        configure()
        for ((padding, size) in listOf("1 2 3 4" to (16 to 14), "(1,2,3,4)" to (14 to 16), "2 3 4 1" to (14 to 16))) {
            val response = client.post("/snapshot") {
                setBody("""<Snapshot><Padding padding="$padding"><SizedBox width="10" height="10"/></Padding></Snapshot>""")
            }
            assertEquals(HttpStatusCode.OK, response.status, padding)
            Image.makeFromEncoded(response.body<ByteArray>()).use {
                assertEquals(size.first, it.width, padding)
                assertEquals(size.second, it.height, padding)
            }
        }
    }

    @Test
    fun `normalized output formats retain the correct response types`() = testApplication {
        configure()
        for ((format, contentType) in listOf(" PNG " to "image/png", " JPG " to "image/jpeg", " WeBp " to "image/webp")) {
            val response = client.post("/snapshot") {
                setBody("""<Snapshot type="$format"><SizedBox width="2" height="2"/></Snapshot>""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith(contentType))
        }
    }

    @Test
    fun `invalid booleans and font lists retain structured attribute diagnostics`() = testApplication {
        configure(overrides = { put("snapshot.rate-limit.requests", "100") })
        val invalid = listOf("", " ", "tru", "yes", "1", "0").map { value ->
            Triple("""<Snapshot debug="$value"><SizedBox width="1" height="1"/></Snapshot>""", "debug", value)
        } + listOf(
            Triple("""<Snapshot><Text fontFamily="Arial,">text</Text></Snapshot>""", "fontFamily", "Arial,"),
            Triple("""<Snapshot><Text strutFontFamily="Arial, ,Noto Sans">text</Text></Snapshot>""", "strutFontFamily", "Arial, ,Noto Sans"),
            Triple("""<Snapshot><Image url="https://host.invalid/a.png" noCache="yes"/></Snapshot>""", "noCache", "yes"),
            Triple("""<Snapshot><Image url="https://host.invalid/a.png" noCache="&#116;rue"/></Snapshot>""", "noCache", "&#116;rue"),
        )
        for ((source, attribute, value) in invalid) {
            val response = client.post("/snapshot") { setBody(source) }
            assertEquals(HttpStatusCode.BadRequest, response.status, source)
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("PARSE_ERROR", error.getValue("code").jsonPrimitive.content)
            val diagnostic = error.getValue("diagnostic").jsonObject
            assertEquals(attribute, diagnostic.getValue("attributeName").jsonPrimitive.content)
            assertEquals(if (value.isEmpty()) "empty" else "present", diagnostic.getValue("valueState").jsonPrimitive.content)
            assertFalse(diagnostic.containsKey("rawValue"))
            if (attribute == "debug" || attribute == "noCache") {
                assertEquals(listOf("true", "false"), diagnostic.getValue("allowedValues").jsonArray.map { it.jsonPrimitive.content })
            }
        }
    }
}
