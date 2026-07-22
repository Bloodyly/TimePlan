package de.fs.timeplan.net

import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.Week
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.WorkersResponse
import de.fs.timeplan.model.textOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val DEMO_MONTEURE = listOf(
    "101" to "Wagner", "102" to "Krüger", "103" to "Zimmermann", "104" to "Neumann",
    "105" to "Fuchs", "106" to "Braun", "107" to "Krause", "108" to "Meyer",
    "109" to "Lehmann", "110" to "Schwarz"
)

private val DEMO_AZUBIS = listOf(
    "501" to "Winkler", "502" to "Herrmann", "503" to "König", "504" to "Vogt",
    "505" to "Stein", "506" to "Busch", "507" to "Kramer", "508" to "Voss",
    "509" to "Engel", "510" to "Pauli"
)

object DemoApi : TimePlanApi {

    private val seedWorkers: List<Worker> = buildList {
        DEMO_MONTEURE.forEachIndexed { index, (number, name) ->
            add(Worker("demo-m-$number", number, name, "monteur", index + 1, true, 1))
        }
        DEMO_AZUBIS.forEachIndexed { index, (number, name) ->
            add(Worker("demo-a-$number", number, name, "azubi", index + 1, true, 1))
        }
    }

    private val entries = mutableMapOf<String, Entry>()
    private var nextEntryId = 1

    override fun getStatus(): ApiResult<StatusResponse> = ApiResult.Success(StatusResponse("ok", 0))

    override fun getWorkers(): ApiResult<WorkersResponse> = ApiResult.Success(WorkersResponse(seedWorkers))

    override fun getWeek(weekId: String): ApiResult<WeekBundle> {
        val dates = WeekId.weekDates(weekId).map { it.toString() }
        val weekEntries = entries.values.filter { it.cell_id.startsWith("${weekId}_") }
        return ApiResult.Success(WeekBundle(Week(weekId, "OPEN", 0), dates, weekEntries))
    }

    fun textFor(cellId: String): String? = entries[cellId]?.textOrNull()

    fun putEntry(cellId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            entries.remove(cellId)
            return
        }
        val previous = entries[cellId]
        entries[cellId] = Entry(
            id = previous?.id ?: "demo-entry-${nextEntryId++}",
            cell_id = cellId,
            type = "text",
            author_type = "tablet",
            author_id = "demo",
            content = buildJsonObject { put("text", trimmed) },
            conflict_of = null,
            created_at = previous?.created_at ?: "demo",
            updated_at = "demo",
            revision = (previous?.revision ?: 0) + 1
        )
    }

    fun reset() {
        entries.clear()
        nextEntryId = 1
    }
}
