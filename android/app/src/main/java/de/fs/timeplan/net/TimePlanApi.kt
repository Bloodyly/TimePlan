package de.fs.timeplan.net

import de.fs.timeplan.model.StatusResponse
import de.fs.timeplan.model.WeekBundle
import de.fs.timeplan.model.WorkersResponse

interface TimePlanApi {
    fun getStatus(): ApiResult<StatusResponse>
    fun getWorkers(): ApiResult<WorkersResponse>
    fun getWeek(weekId: String): ApiResult<WeekBundle>
}
