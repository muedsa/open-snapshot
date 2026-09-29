package com.muedsa.snapshot

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicDocsTest {
    @Test
    fun `public document routes are absent by default`() = testApplication {
        configure()

        assertEquals(HttpStatusCode.NotFound, client.get("/openapi.yaml").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/ai-guide.md").status)
        assertFalse(client.get("/").body<String>().contains("/openapi.yaml"))
    }

    @Test
    fun `enabled routes serve bundled documents without an api key`() = testApplication {
        configure(overrides = {
            put("snapshot.public-docs-enabled", "true")
            put("snapshot.api-key", "test-api-key-0123456789")
        })

        val openapi = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, openapi.status)
        assertTrue(openapi.headers[HttpHeaders.ContentType].orEmpty().startsWith("application/yaml"))
        assertEquals(
            File("docs/openapi.yaml").readText().replace("\r\n", "\n"),
            openapi.body<String>().replace("\r\n", "\n"),
        )

        val guide = client.get("/ai-guide.md")
        assertEquals(HttpStatusCode.OK, guide.status)
        assertTrue(guide.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/markdown"))
        assertEquals(
            File("docs/ai-guide.md").readText().replace("\r\n", "\n"),
            guide.body<String>().replace("\r\n", "\n"),
        )
        val root = client.get("/").body<String>()
        assertTrue(root.contains("/openapi.yaml"))
        assertTrue(root.contains("/ai-guide.md"))
    }

    @Test
    fun `guide example renders as png`() = testApplication {
        configure()
        val guide = File("docs/ai-guide.md").readText()
        val source = Regex("```xml\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
            .find(guide)?.groupValues?.get(1)
            ?: error("AI 指南缺少可渲染的 XML 示例")

        val response = client.post("/snapshot") {
            headers.append(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(source)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
    }
}
