package com.muedsa.snapshot

import com.muedsa.snapshot.open.buildAccessLogLine
import com.muedsa.snapshot.open.metricPathLabel
import com.muedsa.snapshot.open.sanitizeLogValue
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ObservabilityTest {

    private val pngSource =
        "<Snapshot type=\"png\"><Container width=\"2\" height=\"2\" color=\"#FFFF0000\"/></Snapshot>"

    @Test
    fun `ready endpoint reports ready`() = testApplication {
        configure()

        val response = client.get("/ready")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("READY", response.body<String>())
        assertNotNull(response.headers["X-Request-Id"])
    }

    @Test
    fun `draining service reports not ready and rejects renders`() = testApplication {
        configure()
        var startedApplication: Application? = null
        application { startedApplication = this }

        assertEquals(HttpStatusCode.OK, client.get("/ready").status)

        // 引擎开始停机时会抛出该事件，服务应进入排水状态。
        val runningApplication = assertNotNull(startedApplication)
        runningApplication.monitor.raise(ApplicationStopPreparing, runningApplication.environment)

        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertTrue(ready.body<String>().contains("NOT_READY"))

        val render = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, render.status)
        assertTrue(render.body<String>().contains("SERVICE_UNAVAILABLE"))
        assertEquals("5", render.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `metrics endpoint exposes prometheus text`() = testApplication {
        configure()

        val render = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody(pngSource)
        }
        assertEquals(HttpStatusCode.OK, render.status)

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/plain"))

        val text = response.body<String>()
        assertTrue(text.contains("# TYPE snapshot_renders_total counter"))
        assertTrue(text.contains("# TYPE snapshot_render_duration_seconds histogram"))
        assertTrue(text.contains("snapshot_render_duration_seconds_bucket{le=\"+Inf\"}"))
        assertTrue((metricValue(text, "snapshot_renders_total", "outcome=\"success\"") ?: 0.0) >= 1.0)
        assertTrue((metricValue(text, "snapshot_render_output_bytes_total") ?: 0.0) > 0.0)
        assertTrue(
            (metricValue(text, "snapshot_http_requests_total", "path=\"/snapshot\",method=\"POST\",status=\"200\"")
                ?: 0.0) >= 1.0
        )
        assertTrue(text.contains("snapshot_image_cache_entries"))
        assertTrue(text.contains("snapshot_ready"))
    }

    @Test
    fun `metrics endpoint can be disabled by configuration`() = testApplication {
        configure(overrides = { put("snapshot.metrics-enabled", "false") })

        assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
    }

    @Test
    fun `rate limited renders are counted in metrics`() = testApplication {
        configure()

        repeat(7) {
            client.post("/snapshot") {
                header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                setBody(pngSource)
            }
        }

        val text = client.get("/metrics").body<String>()
        assertTrue((metricValue(text, "snapshot_renders_total", "outcome=\"rate_limited\"") ?: 0.0) >= 1.0)
    }

    @Test
    fun `request id is validated and echoed`() = testApplication {
        configure()

        val valid = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-Request-Id", "client-request-1")
            setBody(pngSource)
        }
        assertEquals(listOf("client-request-1"), valid.headers.getAll("X-Request-Id"))

        val invalid = client.post("/snapshot") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            header("X-Request-Id", "bad id with spaces")
            setBody(pngSource)
        }
        val generated = invalid.headers["X-Request-Id"]
        assertNotNull(generated)
        assertNotEquals("bad id with spaces", generated)
    }

    @Test
    fun `access log line keeps values on a single line`() {
        val line = buildAccessLogLine(
            requestId = "req-1",
            method = "POST",
            path = "/snapshot\ninjected=1",
            status = 200,
            durationNanos = 1_500_000,
            bytes = 128,
        )

        assertFalse(line.contains('\n'))
        assertTrue(line.contains("requestId=req-1"))
        assertTrue(line.contains("path=/snapshot_injected=1"))
        assertTrue(line.contains("status=200"))
        assertTrue(line.contains("durationMs=1"))
        assertEquals("a_b", sanitizeLogValue("a b", 16))
        assertEquals("ab", sanitizeLogValue("abcdef", 2))
    }

    @Test
    fun `metric path labels stay bounded`() {
        assertEquals("/snapshot", metricPathLabel("/snapshot"))
        assertEquals("/ready", metricPathLabel("/ready"))
        assertEquals("/other", metricPathLabel("/arbitrary/path"))
    }

    private fun metricValue(text: String, name: String, labels: String? = null): Double? {
        val prefix = if (labels == null) name else "$name{$labels}"
        return text.lineSequence()
            .firstOrNull { it.startsWith("$prefix ") }
            ?.substringAfterLast(' ')
            ?.toDoubleOrNull()
    }
}
