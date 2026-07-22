package de.fs.timeplan.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ModelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes worker from api contract shape`() {
        val worker = json.decodeFromString<Worker>(
            """{"id":"w-1","number":"144","name":"Albrecht","category":"monteur","position":1,"active":true,"revision":3}"""
        )
        assertEquals("144 Albrecht", worker.displayName)
        assertEquals(true, worker.isMonteur)
        assertEquals(false, worker.isAzubi)
    }

    @Test
    fun `decodes text entry and extracts text`() {
        val entry = json.decodeFromString<Entry>(
            """{"id":"e-127","cell_id":"2026-W31_w-1_2026-07-30","type":"text",
               "author_type":"web","author_id":"verwaltung-1",
               "content":{"text":"Material bei Lager 2 abholen"},
               "conflict_of":null,"created_at":"2026-07-30T07:30:00",
               "updated_at":"2026-07-30T07:30:00","revision":1}"""
        )
        assertEquals("Material bei Lager 2 abholen", entry.textOrNull())
    }

    @Test
    fun `drawing entry has no text`() {
        val entry = json.decodeFromString<Entry>(
            """{"id":"e-113","cell_id":"2026-W31_w-1_2026-07-30","type":"drawing",
               "author_type":"tablet","author_id":"tablet-01",
               "content":{"canvas_width":1200,"canvas_height":500,"strokes":[]},
               "conflict_of":null,"created_at":"now","updated_at":"now","revision":1}"""
        )
        assertNull(entry.textOrNull())
    }

    @Test
    fun `decodes week bundle`() {
        val bundle = json.decodeFromString<WeekBundle>(
            """{"week":{"id":"2026-W31","status":"OPEN","revision":42},
               "dates":["2026-07-27","2026-07-28","2026-07-29","2026-07-30","2026-07-31","2026-08-01","2026-08-02"],
               "entries":[]}"""
        )
        assertEquals("2026-W31", bundle.week.id)
        assertEquals(7, bundle.dates.size)
    }
}
