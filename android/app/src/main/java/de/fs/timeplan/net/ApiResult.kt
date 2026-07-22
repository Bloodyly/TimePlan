package de.fs.timeplan.net

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    data class NetworkFailure(val message: String) : ApiResult<Nothing>()
}
