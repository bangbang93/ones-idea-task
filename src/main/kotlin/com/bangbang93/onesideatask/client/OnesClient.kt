package com.bangbang93.onesideatask.client

import com.google.gson.Gson
import com.intellij.util.io.HttpRequests
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Stateless HTTP client for the ONES Open API v2 (docs.ones.com/developer/openapi/).
 *
 * ## 认证模型
 * ONES v2 不用 HTTP Basic，也不兼容旧私有部署的 Ones-Auth-Token 头；它用
 * **个人 API key**（在 ONES 网页端个人设置中创建，可随时吊销）。每个请求都必须
 * 携带 HTTP 头 `Authorization: Bearer <apiKey>`。key 只出现在该请求头里——绝不
 * 拼进 URL，也绝不写入任何异常消息或日志。
 *
 * ## 错误映射（集中在一个共享 GET 管道里）
 * - HTTP 401 → key 无效或过期（[IllegalStateException]）
 * - HTTP 403 → scope/业务权限不足（[IllegalStateException]）
 * - HTTP 404 → 资源不存在（[FileNotFoundException]）
 * - HTTP 200 + 响应体 `{"result":"FAIL",...}` 信封 → 抛出 API 自己的 `errorMsg`
 * - 其它非 2xx → [IOException]（带状态码）
 *
 * 所有方法返回原始 JSON 字符串；JSON→模型解析属于任务 7，不在这里做。
 * HTTP 访问走平台自带的 [HttpRequests]，因此自动使用 IDE 的代理设置。
 */
object OnesClient {

    /** Lists work items of a team: `GET /openapi/v2/project/issues?teamID=&limit=`. */
    fun getIssues(serverUrl: String, teamID: String, apiKey: String, limit: Int): String =
        get(
            url = baseUrl(serverUrl) + PATH_ISSUES + "?teamID=" + encode(teamID) + "&limit=" + limit,
            apiKey = apiKey,
            notFoundMessage = MSG_ISSUES_NOT_FOUND,
        )

    /** Loads a single work item: `GET /openapi/v2/project/issues/{issueId}?teamID=`. */
    fun getIssue(serverUrl: String, teamID: String, apiKey: String, issueId: String): String =
        get(
            url = baseUrl(serverUrl) + PATH_ISSUES + "/" + encode(issueId) + "?teamID=" + encode(teamID),
            apiKey = apiKey,
            notFoundMessage = MSG_ISSUE_NOT_FOUND_PREFIX + issueId,
        )

    /** Lists teams visible to the key's owner: `GET /openapi/v2/account/teams`. */
    fun getTeams(serverUrl: String, apiKey: String): String =
        get(
            url = baseUrl(serverUrl) + PATH_TEAMS,
            apiKey = apiKey,
            notFoundMessage = MSG_TEAMS_NOT_FOUND,
        )

    /**
     * Shared GET pipeline: Bearer header → explicit status check → body read →
     * FAIL-envelope check.
     *
     * 2026.2 platform behavior (verified with javap against the shipped jars):
     * `Request.getConnection()` itself calls `getResponseCode()` and throws
     * `HttpStatusException` on non-2xx for GET requests while the builder's
     * `throwStatusCodeException` flag is true (the default). Disabling that
     * flag is the supported way to take over status handling — the connection
     * is then returned as-is and the mapping below sees the raw code.
     */
    private fun get(url: String, apiKey: String, notFoundMessage: String): String {
        val body = HttpRequests.request(url)
            .tuner { it.setRequestProperty(HEADER_AUTHORIZATION, "Bearer $apiKey") }
            .throwStatusCodeException(false)
            .connect { request ->
                val statusCode = (request.connection as HttpURLConnection).responseCode
                when {
                    statusCode in 200..299 -> request.readString()
                    statusCode == HTTP_UNAUTHORIZED -> throw IllegalStateException(MSG_INVALID_API_KEY)
                    statusCode == HTTP_FORBIDDEN -> throw IllegalStateException(MSG_FORBIDDEN)
                    statusCode == HTTP_NOT_FOUND -> throw FileNotFoundException(notFoundMessage)
                    else -> throw IOException(genericFailureMessage(statusCode, request.readError()))
                }
            }
        return rejectFailureEnvelope(body)
    }

    /**
     * ONES reports business failures as HTTP 200 with a `{"result":"FAIL"}`
     * envelope. Non-JSON bodies (proxies, HTML error pages) pass through
     * untouched — parsing them is the model layer's job.
     */
    private fun rejectFailureEnvelope(body: String): String {
        val envelope = runCatching { GSON.fromJson(body, OnesErrorEnvelope::class.java) }.getOrNull()
        if (envelope?.result == RESULT_FAIL) {
            throw IllegalStateException(envelope.errorMsg ?: MSG_REQUEST_FAILED)
        }
        return body
    }

    private fun genericFailureMessage(statusCode: Int, errorBody: String?): String {
        val detail = errorBody?.trim()?.take(MAX_ERROR_DETAIL_LENGTH).orEmpty()
        return if (detail.isEmpty()) {
            "ONES API 请求失败 (HTTP $statusCode)"
        } else {
            "ONES API 请求失败 (HTTP $statusCode): $detail"
        }
    }

    private fun baseUrl(serverUrl: String): String = serverUrl.trimEnd('/')

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    /** Minimal shape of the ONES error envelope; issue models live in task 7. */
    private data class OnesErrorEnvelope(val result: String?, val errorMsg: String?)

    private const val HEADER_AUTHORIZATION = "Authorization"
    private const val PATH_ISSUES = "/openapi/v2/project/issues"
    private const val PATH_TEAMS = "/openapi/v2/account/teams"
    private const val RESULT_FAIL = "FAIL"
    private const val MSG_INVALID_API_KEY = "API key 无效或已过期"
    private const val MSG_FORBIDDEN = "权限不足：请检查 scope 与业务权限"
    private const val MSG_ISSUE_NOT_FOUND_PREFIX = "工作项不存在: "
    private const val MSG_ISSUES_NOT_FOUND = "工作项列表不存在，请检查服务器地址"
    private const val MSG_TEAMS_NOT_FOUND = "团队列表不存在，请检查服务器地址"
    private const val MSG_REQUEST_FAILED = "ONES API 请求失败"
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val MAX_ERROR_DETAIL_LENGTH = 200
    private val GSON = Gson()
}
