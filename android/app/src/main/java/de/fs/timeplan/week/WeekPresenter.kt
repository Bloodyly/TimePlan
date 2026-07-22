package de.fs.timeplan.week

import de.fs.timeplan.grid.WeekGridBuilder
import de.fs.timeplan.grid.WeekRow
import de.fs.timeplan.model.Entry
import de.fs.timeplan.model.Week
import de.fs.timeplan.model.Worker
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.TimePlanApi

sealed class WeekLoadResult {
    data class Success(
        val week: Week,
        val dates: List<String>,
        val rows: List<WeekRow>,
        val workers: List<Worker>,
        val entries: List<Entry>
    ) : WeekLoadResult()
    data class Failure(val message: String) : WeekLoadResult()
}

class WeekPresenter(private val api: TimePlanApi) {

    fun loadWeek(weekId: String): WeekLoadResult {
        val workersResult = api.getWorkers()
        val workers = when (workersResult) {
            is ApiResult.Success -> workersResult.data.workers
            is ApiResult.Error -> return WeekLoadResult.Failure(
                "Monteure konnten nicht geladen werden (${workersResult.code})")
            is ApiResult.NetworkFailure -> return WeekLoadResult.Failure(
                "Server nicht erreichbar: ${workersResult.message}")
        }

        val weekResult = api.getWeek(weekId)
        return when (weekResult) {
            is ApiResult.Success -> {
                val bundle = weekResult.data
                WeekLoadResult.Success(
                    week = bundle.week,
                    dates = bundle.dates,
                    rows = WeekGridBuilder.build(weekId, bundle.dates, workers, bundle.entries),
                    workers = workers,
                    entries = bundle.entries
                )
            }
            is ApiResult.Error -> WeekLoadResult.Failure(
                "Woche konnte nicht geladen werden (${weekResult.code})")
            is ApiResult.NetworkFailure -> WeekLoadResult.Failure(
                "Server nicht erreichbar: ${weekResult.message}")
        }
    }
}
