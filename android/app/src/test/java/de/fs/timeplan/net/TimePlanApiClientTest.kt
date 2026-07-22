package de.fs.timeplan.net

import de.fs.timeplan.config.ServerConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TimePlanApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TimePlanApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = ServerConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            deviceId = "tablet-01",
            token = "testtoken"
        )
        client = TimePlanApiClient(config)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getStatus parses success response`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok","revision":5}"""))
        val result = client.getStatus()
        require(result is ApiResult.Success)
        assertEquals("ok", result.data.status)
        assertEquals(5, result.data.revision)
    }

    @Test
    fun `getWorkers returns Error on 401`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid device token"}"""))
        val result = client.getWorkers()
        require(result is ApiResult.Error)
        assertEquals(401, result.code)
    }

    @Test
    fun `getWeek returns NetworkFailure when server unreachable`() {
        server.shutdown()
        val result = client.getWeek("2026-W31")
        assertTrue(result is ApiResult.NetworkFailure)
    }

    @Test
    fun `request sends bearer token header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"workers":[]}"""))
        client.getWorkers()
        val recorded = server.takeRequest()
        assertEquals("Bearer testtoken", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getStatus returns NetworkFailure instead of throwing for invalid baseUrl`() {
        val invalidConfig = ServerConfig(
            baseUrl = "keine-gueltige-url",
            deviceId = "tablet-01",
            token = "testtoken"
        )
        val invalidClient = TimePlanApiClient(invalidConfig)

        val result = invalidClient.getStatus()

        assertTrue(result is ApiResult.NetworkFailure)
    }

    @Test
    fun `createEntry posts cell_id type content and parses the returned entry`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"entry":{"id":"e-1","cell_id":"2026-W31_w-1_2026-07-27","type":"drawing",
                "author_type":"tablet","author_id":"tablet-01",
                "content":{"canvas_width":100,"canvas_height":50,"strokes":[]},
                "conflict_of":null,"created_at":"t","updated_at":"t","revision":1}}"""
        ))
        val content = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"canvas_width":100,"canvas_height":50,"strokes":[]}""")
        val result = client.createEntry("2026-W31_w-1_2026-07-27", "drawing", content)
        require(result is ApiResult.Success)
        assertEquals("e-1", result.data.id)
        assertEquals(1, result.data.revision)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("Bearer testtoken", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("\"cell_id\":\"2026-W31_w-1_2026-07-27\""))
    }

    @Test
    fun `updateEntry puts content and base_revision and parses the returned entry`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"entry":{"id":"e-1","cell_id":"c","type":"drawing",
                "author_type":"tablet","author_id":"tablet-01",
                "content":{"canvas_width":1,"canvas_height":1,"strokes":[]},
                "conflict_of":null,"created_at":"t","updated_at":"t","revision":2}}"""
        ))
        val content = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"canvas_width":1,"canvas_height":1,"strokes":[]}""")
        val result = client.updateEntry("e-1", content, 1)
        require(result is ApiResult.Success)
        assertEquals(2, result.data.revision)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("\"base_revision\":1"))
    }

    @Test
    fun `createEntry returns Error on 422 validation failure`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"invalid"}"""))
        val result = client.createEntry("bad", "drawing", kotlinx.serialization.json.Json.parseToJsonElement("{}"))
        require(result is ApiResult.Error)
        assertEquals(422, result.code)
    }

    @Test
    fun `updateEntry returns Error on 409 conflict`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":{"error":"revision_conflict"}}"""))
        val result = client.updateEntry("e-1", kotlinx.serialization.json.Json.parseToJsonElement("{}"), 1)
        require(result is ApiResult.Error)
        assertEquals(409, result.code)
    }

    @Test
    fun `createEntry returns NetworkFailure when server unreachable`() {
        server.shutdown()
        val result = client.createEntry("c", "drawing", kotlinx.serialization.json.Json.parseToJsonElement("{}"))
        assertTrue(result is ApiResult.NetworkFailure)
    }

    @Test
    fun `deleteEntry sends DELETE with bearer token and returns Success on 204`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.deleteEntry("e-1")
        require(result is ApiResult.Success)
        assertEquals(Unit, result.data)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/entries/e-1", recorded.path)
        assertEquals("Bearer testtoken", recorded.getHeader("Authorization"))
    }

    @Test
    fun `deleteEntry returns Error on 404`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"not found"}"""))
        val result = client.deleteEntry("missing")
        require(result is ApiResult.Error)
        assertEquals(404, result.code)
    }
}
