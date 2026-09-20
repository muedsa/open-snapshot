package com.muedsa.snapshot

import com.muedsa.snapshot.open.ErrorCodes
import io.ktor.server.config.yaml.YamlConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `docs/openapi.yaml` 与实现的一致性检查：新增接口或错误码却忘了更新文档时，这里会失败。
 *
 * 根路径 `/` 只是启动横幅，不属于对外 API，因此不要求在文档中登记。
 */
class OpenApiSpecTest {

    private val specFile = File("docs/openapi.yaml")
    private val specText = specFile.readText()

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
