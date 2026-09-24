package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.ImageResourceBudget
import com.muedsa.snapshot.open.LimitedDataUriImageDecoder
import com.muedsa.snapshot.open.configureImageCache
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Data URI 图片与文档结构的开放服务安全边界。 */
class ImageDataUriSecurityTest {

    @Test
    fun `data uri decoder releases native image after render scope`() {
        val decoder = LimitedDataUriImageDecoder(
            maxEncodedBytes = 1024,
            maxImageWidth = 16,
            maxImageHeight = 16,
            maxImagePixels = 256,
            budget = ImageResourceBudget(maxImageCount = 1, maxTotalPixels = 256),
        )
        val image = decoder.decode(PNG_DATA_URI)
        assertFalse(image.isClosed)

        decoder.close()

        assertTrue(image.isClosed)
    }

    @Test
    fun `image and emoji support valid data uri`() = testApplication {
        configure()
        val source = """
            <Snapshot type="png">
                <Column>
                    <Image width="1" height="1" dataUri="$PNG_DATA_URI"/>
                    <Text fontSize="12">A<Emoji width="1" height="1" dataUri="$PNG_DATA_URI"/>B</Text>
                </Column>
            </Snapshot>
        """.trimIndent()

        val response = render(source)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
    }

    @Test
    fun `latest parser layout tags render through the service`() = testApplication {
        configure()
        val documents = listOf(
            """
                <Snapshot><Container width="100" height="20"><Flex direction="HORIZONTAL">
                    <Container width="10" height="20" color="#FFFF0000"/>
                    <Spacer/>
                    <Container width="10" height="20" color="#FF0000FF"/>
                </Flex></Container></Snapshot>
            """.trimIndent(),
            """
                <Snapshot><IndexedStack index="1">
                    <Container width="10" height="10" color="#FFFF0000"/>
                    <Container width="20" height="20" color="#FF0000FF"/>
                </IndexedStack></Snapshot>
            """.trimIndent(),
        )

        documents.forEach { source ->
            val response = render(source)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    @Test
    fun `invalid base64 and mismatched media type are image errors`() = testApplication {
        configure()

        listOf(
            "data:image/png;base64,!invalid!",
            PNG_DATA_URI.replace("image/png", "image/jpeg"),
        ).forEach { dataUri ->
            val response = render(
                "<Snapshot><Image width=\"1\" height=\"1\" dataUri=\"$dataUri\"/></Snapshot>"
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains(ErrorCodes.IMAGE_LOAD_ERROR), response.bodyAsText())
        }
    }

    @Test
    fun `data uri encoded size is limited before decoding`() = testApplication {
        configure(overrides = { put("snapshot.image.max-single-image-size", "32") })

        val response = render(
            "<Snapshot><Image width=\"1\" height=\"1\" dataUri=\"$PNG_DATA_URI\"/></Snapshot>"
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.IMAGE_LOAD_ERROR), response.bodyAsText())
    }

    @Test
    fun `data uri images respect the total pixel budget`() = testApplication {
        configure(
            overrides = {
                put("snapshot.image.max-image-num-once", "2")
                put("snapshot.image.max-total-image-pixels", "1")
            }
        )
        val source = """
            <Snapshot><Row>
                <Image width="1" height="1" dataUri="$PNG_DATA_URI"/>
                <Image width="1" height="1" dataUri="$PNG_DATA_URI"/>
            </Row></Snapshot>
        """.trimIndent()

        val response = render(source)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.IMAGE_LOAD_ERROR), response.bodyAsText())
    }

    @Test
    fun `data uri and url images cannot bypass the shared count budget`() {
        val png = Base64.getDecoder().decode(PNG_DATA_URI.substringAfter(','))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/image.png") { exchange ->
            requests++
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        server.start()
        try {
            testApplication {
                configure(overrides = { put("snapshot.image.max-image-num-once", "1") })
                application { configureImageCache(allowPrivateHostsOverride = true) }
                val source = """
                    <Snapshot><Row>
                        <Image width="1" height="1" dataUri="$PNG_DATA_URI"/>
                        <Image width="1" height="1" url="http://127.0.0.1:${server.address.port}/image.png"/>
                    </Row></Snapshot>
                """.trimIndent()

                val response = render(source)

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertTrue(response.bodyAsText().contains(ErrorCodes.IMAGE_LOAD_ERROR), response.bodyAsText())
                assertEquals(0, requests, "第二张 URL 图片应在发起网络请求前被共享数量预算拒绝")
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `document element count and nesting depth are limited`() = testApplication {
        configure(
            overrides = {
                put("snapshot.max-document-elements", "4")
                put("snapshot.max-document-depth", "3")
            }
        )

        val tooMany = render(
            "<Snapshot><Row><Container width=\"1\" height=\"1\"/><Container width=\"1\" height=\"1\"/><Container width=\"1\" height=\"1\"/></Row></Snapshot>"
        )
        assertEquals(HttpStatusCode.BadRequest, tooMany.status)
        assertTrue(tooMany.bodyAsText().contains(ErrorCodes.RENDER_ERROR), tooMany.bodyAsText())

        val tooDeep = render(
            "<Snapshot><Container><Container><Container width=\"1\" height=\"1\"/></Container></Container></Snapshot>"
        )
        assertEquals(HttpStatusCode.BadRequest, tooDeep.status)
        assertTrue(tooDeep.bodyAsText().contains(ErrorCodes.RENDER_ERROR), tooDeep.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.render(source: String) =
        client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(source)
        }

    private companion object {
        const val PNG_DATA_URI =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
