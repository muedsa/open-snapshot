package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import com.muedsa.snapshot.open.SnapshotService
import com.muedsa.snapshot.open.renderFailure
import com.muedsa.snapshot.open.toJson
import com.muedsa.snapshot.parser.ParseException
import io.ktor.server.config.yaml.YamlConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `docs/openapi.yaml` 与实现的一致性检查：新增接口或错误码却忘了更新文档时，这里会失败。
 *
 * 根路径 `/` 只是启动横幅，不属于对外 API，因此不要求在文档中登记。
 */
class OpenApiSpecTest {

    private val specFile = File("docs/openapi.yaml")

    /** 统一换行符，避免 Windows 检出（CRLF）影响按行匹配的断言。 */
    private val specText = specFile.readText().replace("\r\n", "\n")

    @Test
    fun `document is valid yaml and loadable as ktor config`() {
        assertTrue(specFile.isFile, "缺少 docs/openapi.yaml")
        assertNotNull(YamlConfig("docs/openapi.yaml"), "docs/openapi.yaml 不是合法的 YAML")
    }

    @Test
    fun `documented paths match the public endpoints`() {
        val documented = Regex("""^ {2}(/[^\s:]*):\s*$""", RegexOption.MULTILINE)
            .findAll(specText)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "/openapi.yaml",
                "/ai-guide.md",
                "/snapshot",
                "/health",
                "/ready",
                "/metrics",
                "/fonts",
                "/fonts.png",
                "/cacheInfo",
                "/cacheClear",
            ),
            documented,
        )
    }

    @Test
    fun `documented error codes match the error code contract`() {
        val enumSection = Regex("""^\s*enum:\s*$\n((?:\s*-\s+[A-Z_]+\s*$\n?)+)""", RegexOption.MULTILINE)
            .find(specText)
            ?.groupValues
            ?.get(1)
        assertNotNull(enumSection, "docs/openapi.yaml 中没有找到 code 的 enum 列表")

        val documented = Regex("""-\s+([A-Z_]+)""")
            .findAll(enumSection)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(ErrorCodes.ALL, documented)
    }

    @Test
    fun `attribute diagnostic contract matches serialized parser errors`() {
        val spec = assertNotNull(YamlConfig("docs/openapi.yaml"))
        val schema = spec.config("components.schemas.AttributeDiagnostic")
        val source = "<Snapshot><Row mainAxisAlignment=\"MIDDLE\"/></Snapshot>"
        val error = assertFailsWith<ParseException> { SnapshotService.render(source) }
        val json = Json.parseToJsonElement(renderFailure(error, source, "id").toJson()).jsonObject
        val diagnostic = json.getValue("diagnostic").jsonObject
        assertEquals(setOf("code", "message", "requestId"), spec.property("components.schemas.ApiError.required").getList().toSet())
        assertTrue(diagnostic.keys.containsAll(schema.property("required").getList()))
        assertTrue(diagnostic.getValue("valueState").jsonPrimitive.content in schema.property("properties.valueState.enum").getList())
        assertTrue(diagnostic.getValue("format").jsonPrimitive.content.length <= schema.property("properties.format.maxLength").getString().toInt())
        assertTrue(diagnostic.getValue("allowedValues").jsonArray.size <= schema.property("properties.allowedValues.maxItems").getString().toInt())
        assertEquals("#/components/schemas/AttributeDiagnostic", spec.property("components.schemas.ApiError.properties.diagnostic.\$ref").getString())
    }

    @Test
    fun `render endpoint documents its responses`() {
        val snapshotSection = Regex("""^ {2}/snapshot:\s*$\n(.*?)(?=^ {2}/[^\s:]*:\s*$)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(specText)
            ?.groupValues
            ?.get(1)
        assertNotNull(snapshotSection, "docs/openapi.yaml 中没有找到 /snapshot 定义")

        val responses = Regex("""^\s*"(\d{3})":\s*$""", RegexOption.MULTILINE)
            .findAll(snapshotSection)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(setOf("200", "400", "401", "413", "429", "500", "503", "504"), responses)
    }
}
