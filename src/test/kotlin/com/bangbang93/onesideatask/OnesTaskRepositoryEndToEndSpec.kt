package com.bangbang93.onesideatask

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end tests for the read stack WITHOUT any mocking: a real repository,
 * the real [com.bangbang93.onesideatask.client.OnesClient] (platform HttpRequests),
 * real JSON parsing and real filtering — all against a local JDK [HttpServer]
 * on an ephemeral port serving the calibrated ONES fixtures (pattern proven in
 * `OnesClientSpec`; this server additionally ROUTES by request path, because
 * the repository exercises both the list and the detail endpoint).
 *
 * This pins the whole todo 6-9 chain over a real socket: HTTP request shape
 * (Bearer auth, teamID/limit query), envelope parsing, Task mapping, the
 * done/query filters, findTask's 404→null semantics and the calibrated web
 * deep link — the last mile mockkObject-based specs cannot prove.
 *
 * The repository is configured purely in-memory (`apiKey` set directly), so
 * PasswordSafe is never touched.
 */
class OnesTaskRepositoryEndToEndSpec : FunSpec({

    val server = OnesApiServer()

    beforeTest { server.start() }
    afterTest { (_, _) -> server.stop() }

    fun configuredRepository() = OnesTaskRepository().apply {
        url = server.url
        teamID = TEAM
        apiKey = KEY
    }

    test("getIssues walks the real HTTP stack: Bearer auth, list query, 3 mapped tasks") {
        val repository = configuredRepository()

        val tasks = repository.getIssues(null, 0, 50, withClosed = true)

        tasks.size shouldBe 3
        tasks.map { it.summary } shouldBe listOf(
            "客户端支持应用内下载更新",
            "集成神经网络芯片，进行智能识别和预测分析数据",
            "最终确认和验收",
        )
        server.capturedAuthorization.get() shouldBe "Bearer $KEY"
        server.capturedQuery.get() shouldContain "teamID=$TEAM"
        server.capturedQuery.get() shouldContain "limit=50"
    }

    test("done filtering works over the real socket") {
        val repository = configuredRepository()

        val openOnly = repository.getIssues(null, 0, 50, withClosed = false)

        openOnly.size shouldBe 2
        openOnly.none { it.isClosed } shouldBe true
    }

    test("query filtering works over the real socket") {
        val repository = configuredRepository()

        val tasks = repository.getIssues("客户端", 0, 50, withClosed = true)

        tasks.size shouldBe 1
        tasks[0].summary shouldBe "客户端支持应用内下载更新"
    }

    test("findTask walks the real HTTP stack and yields the task with the calibrated deep link") {
        val repository = configuredRepository()

        val task = repository.findTask(ISSUE_ID)

        task.shouldNotBeNull()
        task.id shouldBe ISSUE_ID
        task.summary shouldBe "客户端支持应用内下载更新"
        task.number shouldBe "1"
        task.issueUrl shouldBe "${server.url}/project/#/team/$TEAM/task/$ISSUE_ID"
        server.capturedAuthorization.get() shouldBe "Bearer $KEY"
    }

    test("findTask maps a real 404 response to null") {
        val repository = configuredRepository()

        repository.findTask("no-such-issue") shouldBe null
    }
})

private const val TEAM = "team-uuid-1"
private const val KEY = "test-key"
private const val ISSUE_ID = "LEUB2J2u4bWYT7k7"

/**
 * Local ONES Open API mock with path routing: the list endpoint serves the
 * calibrated 3-item page, the known detail endpoint serves the detail
 * envelope, everything else answers 404. Captures the Authorization header
 * and query of the last request. Test-only helper — production code goes
 * through the platform HttpRequests (proxy support).
 */
private class OnesApiServer {
    private var server: HttpServer? = null

    val capturedAuthorization = AtomicReference<String?>()
    val capturedQuery = AtomicReference<String?>()

    fun start() {
        val httpServer = HttpServer.create(InetSocketAddress(LOOPBACK, 0), 0)
        httpServer.createContext("/") { exchange -> handle(exchange) }
        httpServer.start()
        server = httpServer
    }

    val url: String
        get() = "http://$LOOPBACK:${checkNotNull(server).address.port}"

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        capturedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
        capturedQuery.set(exchange.requestURI.rawQuery)
        val body = bodyFor(exchange.requestURI.path)
        if (body == null) {
            exchange.sendResponseHeaders(NOT_FOUND, NO_BODY)
        } else {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun bodyFor(path: String): String? = when (path) {
        PATH_ISSUES -> LIST_FIXTURE
        "$PATH_ISSUES/$ISSUE_ID" -> DETAIL_FIXTURE
        else -> null
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val NO_BODY = -1L
        const val OK = 200
        const val NOT_FOUND = 404
        const val PATH_ISSUES = "/openapi/v2/project/issues"
    }
}

/**
 * Official "Get a list of issues" envelope; values calibrated from a live ONES
 * instance (task-7 model spec): 1 to_do + 1 in_progress + 1 done.
 */
private val LIST_FIXTURE = """
    {
      "result": "SUCCESS",
      "data": {
        "list": [
          {
            "id": "LEUB2J2u4bWYT7k7",
            "title": "客户端支持应用内下载更新",
            "number": 1,
            "assignee": {"id": "LEUB2J2u", "name": "马晓迪"},
            "status": {"id": "UjFEXKf1", "name": "待规划", "category": "to_do"},
            "project": {"id": "LEUB2J2uVMwkMWRy", "name": "ONES-TEST"},
            "issueType": {"id": "7SpCDQE9", "name": "需求"},
            "createTime": 1788315974446000
          },
          {
            "id": "LEUB2J2uLr9csMUr",
            "title": "集成神经网络芯片，进行智能识别和预测分析数据",
            "number": 58,
            "assignee": {"id": "LEUB2J2u", "name": "马晓迪"},
            "status": {"id": "1445xB2f", "name": "方案设计中", "category": "in_progress"},
            "project": {"id": "LEUB2J2uVMwkMWRy", "name": "ONES-TEST"},
            "issueType": {"id": "7SpCDQE9", "name": "需求"},
            "createTime": 1788315974389000
          },
          {
            "id": "LEUB2J2uTfwj1Cdu",
            "title": "最终确认和验收",
            "number": 27,
            "assignee": {"id": "LEUB2J2u", "name": "马晓迪"},
            "status": {"id": "M63zTh9p", "name": "完成", "category": "done"},
            "project": {"id": "LEUB2J2uVMwkMWRy", "name": "ONES-TEST"},
            "issueType": {"id": "7SpCDQE9", "name": "任务"},
            "createTime": 1788315974446000
          }
        ]
      }
    }
""".trimIndent()

/** Official "Get a issue details" envelope: the issue object sits directly under `data`. */
private val DETAIL_FIXTURE = """
    {
      "result": "SUCCESS",
      "data": {
        "id": "LEUB2J2u4bWYT7k7",
        "title": "客户端支持应用内下载更新",
        "number": 1,
        "assignee": {"id": "LEUB2J2u", "name": "马晓迪"},
        "status": {"id": "UjFEXKf1", "name": "待规划", "category": "to_do"},
        "project": {"id": "LEUB2J2uVMwkMWRy", "name": "ONES-TEST"},
        "issueType": {"id": "7SpCDQE9", "name": "需求"},
        "createTime": 1788315974446000,
        "serverUpdateStamp": 1788315974446000
      }
    }
""".trimIndent()
