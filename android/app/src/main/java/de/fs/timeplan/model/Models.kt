package de.fs.timeplan.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Worker(
    val id: String,
    val number: String,
    val name: String,
    val category: String,
    val position: Int,
    val active: Boolean,
    val revision: Int
) {
    val displayName: String get() = "$number $name"
    val isMonteur: Boolean get() = category == "monteur"
    val isAzubi: Boolean get() = category == "azubi"
}

@Serializable
data class Week(
    val id: String,
    val status: String,
    val revision: Int
)

@Serializable
data class Entry(
    val id: String,
    val cell_id: String,
    val type: String,
    val author_type: String,
    val author_id: String,
    val content: JsonElement,
    val conflict_of: String? = null,
    val created_at: String,
    val updated_at: String,
    val revision: Int
)

fun Entry.textOrNull(): String? {
    if (type != "text") return null
    val obj = content as? JsonObject ?: return null
    val value = obj["text"] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

@Serializable
data class WorkersResponse(val workers: List<Worker>)

@Serializable
data class WeekBundle(
    val week: Week,
    val dates: List<String>,
    val entries: List<Entry>
)

@Serializable
data class StatusResponse(val status: String, val revision: Int)
