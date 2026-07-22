package de.fs.timeplan.net

import de.fs.timeplan.config.ServerConfig
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.WorkersResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
private data class CreateEntryRequest(val cell_id: String, val type: String, val content: JsonElement)

@Serializable
private data class UpdateEntryRequest(val content: JsonElement, val base_revision: Int)

@Serializable
private data class EntryEnvelope(val entry: Entry)

class TimePlanApiClient(
    private val config: ServerConfig,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TimePlanApi {

    private fun request(path: String): Request =
        Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${config.token}")
            .get()
            .build()

    private inline fun <reified T> execute(path: String): ApiResult<T> {
        return try {
            client.newCall(request(path)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Success(json.decodeFromString<T>(body))
                } else {
                    ApiResult.Error(response.code, body)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkFailure(e.message ?: "network error")
        } catch (e: Exception) {
            ApiResult.NetworkFailure(e.message ?: "unerwarteter Fehler")
        }
    }

    private fun executeWithBody(path: String, method: String, body: String): ApiResult<Entry> {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val httpRequest = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${config.token}")
            .method(method, body.toRequestBody(mediaType))
            .build()
        return try {
            client.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    ApiResult.Success(json.decodeFromString(EntryEnvelope.serializer(), responseBody).entry)
                } else {
                    ApiResult.Error(response.code, responseBody)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkFailure(e.message ?: "network error")
        } catch (e: Exception) {
            ApiResult.NetworkFailure(e.message ?: "unerwarteter Fehler")
        }
    }

    private fun executeDelete(path: String): ApiResult<Unit> {
        val httpRequest = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .header("Authorization", "Bearer ${config.token}")
            .delete()
            .build()
        return try {
            client.newCall(httpRequest).execute().use { response ->
                if (response.isSuccessful) {
                    ApiResult.Success(Unit)
                } else {
                    val responseBody = response.body?.string().orEmpty()
                    ApiResult.Error(response.code, responseBody)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkFailure(e.message ?: "network error")
        } catch (e: Exception) {
            ApiResult.NetworkFailure(e.message ?: "unerwarteter Fehler")
        }
    }

    override fun getStatus(): ApiResult<StatusResponse> = execute("/api/v1/status")

    override fun getWorkers(): ApiResult<WorkersResponse> = execute("/api/v1/workers")

    override fun getWeek(weekId: String): ApiResult<WeekBundle> = execute("/api/v1/weeks/$weekId")

    fun createEntry(cellId: String, type: String, content: JsonElement): ApiResult<Entry> {
        val body = json.encodeToString(CreateEntryRequest.serializer(), CreateEntryRequest(cellId, type, content))
        return executeWithBody("/api/v1/entries", "POST", body)
    }

    fun updateEntry(entryId: String, content: JsonElement, baseRevision: Int): ApiResult<Entry> {
        val body = json.encodeToString(UpdateEntryRequest.serializer(), UpdateEntryRequest(content, baseRevision))
        return executeWithBody("/api/v1/entries/$entryId", "PUT", body)
    }

    fun deleteEntry(entryId: String): ApiResult<Unit> = executeDelete("/api/v1/entries/$entryId")
}
