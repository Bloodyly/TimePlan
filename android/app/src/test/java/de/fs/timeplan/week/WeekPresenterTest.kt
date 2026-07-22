package de.fs.timeplan.week

import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.Week
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.Worker
import de.fs.timeplan.model.WorkersResponse
import de.fs.timeplan.net.ApiResult
import de.fs.timeplan.net.TimePlanApi
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import de.fs.timeplan.net.DemoApi

private class FakeApi(
    private val workersResult: ApiResult<WorkersResponse>,
    private val weekResult: ApiResult<WeekBundle>
) : TimePlanApi {
    override fun getStatus(): ApiResult<StatusResponse> = ApiResult.Success(StatusResponse("ok", 1))
    override fun getWorkers(): ApiResult<WorkersResponse> = workersResult
    override fun getWeek(weekId: String): ApiResult<WeekBundle> = weekResult
}

class WeekPresenterTest {

    private val monteur = Worker("w-1", "144", "Albrecht", "monteur", 1, true, 1)
    private val dates = listOf("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30", "2026-07-31", "2026-08-01", "2026-08-02")

    @Test
    fun `success combines workers and week into rows`() {
        val api = FakeApi(
            workersResult = ApiResult.Success(WorkersResponse(listOf(monteur))),
            weekResult = ApiResult.Success(WeekBundle(Week("2026-W31", "OPEN", 5), dates, emptyList()))
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Success)
        assertEquals("2026-W31", result.week.id)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(monteur), result.workers)
        assertEquals(emptyList<de.fs.timeplan.model.Entry>(), result.entries)
    }

    @Test
    fun `workers error yields failure`() {
        val api = FakeApi(
            workersResult = ApiResult.Error(401, "unauthorized"),
            weekResult = ApiResult.Success(WeekBundle(Week("2026-W31", "OPEN", 5), dates, emptyList()))
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Failure)
        assertTrue(result.message.contains("401"))
    }

    @Test
    fun `network failure on week yields failure`() {
        val api = FakeApi(
            workersResult = ApiResult.Success(WorkersResponse(listOf(monteur))),
            weekResult = ApiResult.NetworkFailure("timeout")
        )
        val result = WeekPresenter(api).loadWeek("2026-W31")
        require(result is WeekLoadResult.Failure)
        assertTrue(result.message.contains("nicht erreichbar"))
    }

    @Test
    fun `works end-to-end with DemoApi`() {
        DemoApi.reset()
        val result = WeekPresenter(DemoApi).loadWeek("2027-W30")
        require(result is WeekLoadResult.Success)
        assertEquals(21, result.rows.size)
    }
}
