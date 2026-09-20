package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.FontService
import com.muedsa.snapshot.open.configureRoutingForTests
import com.muedsa.snapshot.open.selectFontFamilies
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/fonts.png` 鐨勫瓧浣撹繃婊ゃ€佸垎椤典笌缁撴灉缂撳瓨銆? */
class FontPreviewTest {

    @Test
    fun `selection returns every family by default`() {
        val available = listOf("Inter", "Noto Serif SC", "DejaVu Serif")

        val selection = selectFontFamilies(available, requested = emptyList(), offset = 0, limit = 0)

        assertEquals(available, selection.selected)
        assertEquals(emptyList(), selection.unknown)
    }

    @Test
    fun `selection filters by requested families`() {
        val available = listOf("Inter", "Noto Serif SC", "DejaVu Serif")

        val selection = selectFontFamilies(available, requested = listOf("DejaVu Serif", "Inter"), offset = 0, limit = 0)

        assertEquals(listOf("Inter", "DejaVu Serif"), selection.selected)
        assertEquals(emptyList(), selection.unknown)
    }

    @Test
    fun `selection matches families case insensitively`() {
        val available = listOf("Inter", "Noto Serif SC")

        val selection = selectFontFamilies(available, requested = listOf("inter"), offset = 0, limit = 0)

        assertEquals(listOf("Inter"), selection.selected)
    }

    @Test
    fun `selection reports unknown families`() {
        val available = listOf("Inter", "Noto Serif SC")

        val selection = selectFontFamilies(available, requested = listOf("Inter", "Nope Font"), offset = 0, limit = 0)

        assertEquals(listOf("Nope Font"), selection.unknown)
    }

    @Test
    fun `selection paginates`() {
        val available = listOf("A", "B", "C", "D", "E")

        assertEquals(listOf("A", "B"), selectFontFamilies(available, emptyList(), offset = 0, limit = 2).selected)
        assertEquals(listOf("C", "D"), selectFontFamilies(available, emptyList(), offset = 2, limit = 2).selected)
        assertEquals(listOf("E"), selectFontFamilies(available, emptyList(), offset = 4, limit = 2).selected)
        assertEquals(emptyList(), selectFontFamilies(available, emptyList(), offset = 9, limit = 2).selected)
    }

    @Test
    fun `preview endpoint renders the filtered family`() = testApplication {
        val family = firstAvailableFamily() ?: return@testApplication
        application { configureRoutingForTests(adminToken = ADMIN_TOKEN) }

        val response = client.get("/fonts.png?family=${family.replace(" ", "%20")}") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
    }

    @Test
    fun `preview endpoint reports unknown families`() = testApplication {
        application { configureRoutingForTests(adminToken = ADMIN_TOKEN) }

        val response = client.get("/fonts.png?family=NoSuchFamilyXyz") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.FONT_NOT_FOUND), response.bodyAsText())
    }

    @Test
    fun `preview endpoint rejects invalid pagination`() = testApplication {
        application { configureRoutingForTests(adminToken = ADMIN_TOKEN) }

        listOf("/fonts.png?limit=-1", "/fonts.png?offset=abc", "/fonts.png?limit=abc").forEach { path ->
            val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertTrue(response.bodyAsText().contains(ErrorCodes.INVALID_QUERY), response.bodyAsText())
        }
    }

    @Test
    fun `preview endpoint caches identical queries`() = testApplication {
        val family = firstAvailableFamily() ?: return@testApplication
        configure(
            overrides = {
                put("snapshot.admin-endpoints-enabled", "true")
                put("snapshot.admin-token", ADMIN_TOKEN)
            },
        )

        val path = "/fonts.png?family=${family.replace(" ", "%20")}&limit=1"
        val first = client.get(path) { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }
        val second = client.get(path) { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertContentEquals(first.bytes(), second.bytes())

        val metrics = client.get("/metrics").bodyAsText()
        assertTrue(metrics.contains("snapshot_render_cache_hits_total 1"), metrics)
    }

    private fun firstAvailableFamily(): String? = FontService.familyNames().firstOrNull()

    private suspend fun HttpResponse.bytes(): ByteArray = body()

    private companion object {
        const val ADMIN_TOKEN = "font-preview-admin-token"
    }
}
