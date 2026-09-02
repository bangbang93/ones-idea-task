package com.bangbang93.onesideatask

import com.bangbang93.onesideatask.client.OnesClient
import com.bangbang93.onesideatask.model.parseIssue
import com.bangbang93.onesideatask.model.parseIssues
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveMessage
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.FileNotFoundException

/**
 * Task-8 integration seams: [OnesTaskRepository.getIssues] / [OnesTaskRepository.findTask]
 * wired to [OnesClient] through the `mockkObject` pattern proven in
 * [OnesTaskRepositorySpec] (stubbed in beforeTest, released in afterTest; unstubbed
 * members would hit the real implementation, so every test stubs what it exercises).
 *
 * Fixtures echo the official ONES Open API v2 envelopes calibrated against a live
 * instance in the model spec (task 7): a 3-item list page (one `to_do`, one
 * `in_progress`, one `done`) and a detail envelope with the issue directly under
 * `data`. The repository is configured purely in-memory (`apiKey = "test-key"`),
 * which bypasses PasswordSafe — the lazy store lookup never fires.
 *
 * Documented error semantics under test:
 *  - unconfigured repository → emptyArray()/null and the client is NEVER called
 *    (the tasks framework probes repositories eagerly; "not configured" is not an
 *    error);
 *  - client errors (401 ISE / FAIL envelope) PROPAGATE so the tasks UI error
 *    banner can show them;
 *  - 404 ([FileNotFoundException]) in findTask → null: the task is absent, that is
 *    not a failure.
 */
class OnesTaskRepositoryIntegrationSpec : FunSpec({

    beforeTest {
        mockkObject(OnesClient)
    }
    afterTest { (_, _) ->
        unmockkObject(OnesClient)
    }

    test("getIssues maps the fetched page into tasks in order") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns LIST_FIXTURE
        val repository = configuredRepository()

        val tasks = repository.getIssues(null, 0, UI_PAGE_SIZE, withClosed = true)

        tasks.size shouldBe 3
        tasks.map { it.summary } shouldBe listOf(
            "客户端支持应用内下载更新",
            "集成神经网络芯片，进行智能识别和预测分析数据",
            "最终确认和验收",
        )
    }

    test("getIssues asks the client for a bounded page: at least MIN_FETCH, at most MAX_FETCH") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns EMPTY_PAGE
        val repository = configuredRepository()

        repository.getIssues(null, 0, 10, withClosed = true)
        repository.getIssues(null, 0, 500, withClosed = true)

        verify(exactly = 1) { OnesClient.getIssues(SERVER, TEAM, KEY, 50) }
        verify(exactly = 1) { OnesClient.getIssues(SERVER, TEAM, KEY, 100) }
    }

    test("getIssues keeps only issues whose summary contains the query") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns LIST_FIXTURE
        val repository = configuredRepository()

        val tasks = repository.getIssues("客户端", 0, UI_PAGE_SIZE, withClosed = true)

        tasks.size shouldBe 1
        tasks[0].summary shouldBe "客户端支持应用内下载更新"
    }

    test("getIssues matches the query case-insensitively") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns
            listJson("""{"id":"c1","title":"Refactor OAuth Login Flow","number":9,"status":{"id":"s1","name":"进行中","category":"in_progress"}}""")
        val repository = configuredRepository()

        val tasks = repository.getIssues("oauth", 0, UI_PAGE_SIZE, withClosed = true)

        tasks.size shouldBe 1
        tasks[0].summary shouldBe "Refactor OAuth Login Flow"
    }

    test("getIssues returns an empty array when no summary matches the query") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns LIST_FIXTURE
        val repository = configuredRepository()

        val tasks = repository.getIssues("zzz无此关键词", 0, UI_PAGE_SIZE, withClosed = true)

        tasks shouldBe emptyArray()
    }

    test("getIssues treats a blank query as no query") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns LIST_FIXTURE
        val repository = configuredRepository()

        val tasks = repository.getIssues("   ", 0, UI_PAGE_SIZE, withClosed = true)

        tasks.size shouldBe 3
    }

    test("getIssues drops closed issues unless withClosed is true") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns LIST_FIXTURE
        val repository = configuredRepository()

        val openOnly = repository.getIssues(null, 0, UI_PAGE_SIZE, withClosed = false)
        val all = repository.getIssues(null, 0, UI_PAGE_SIZE, withClosed = true)

        openOnly.size shouldBe 2
        openOnly.none { it.isClosed } shouldBe true
        all.size shouldBe 3
    }

    test("getIssues applies offset as a local drop on the fetched page") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } returns LIST_FIXTURE
        val repository = configuredRepository()

        val tasks = repository.getIssues(null, 1, UI_PAGE_SIZE, withClosed = true)

        tasks.map { it.summary } shouldBe listOf(
            "集成神经网络芯片，进行智能识别和预测分析数据",
            "最终确认和验收",
        )
    }

    test("getIssues returns an empty array without calling the client when not configured") {
        val repository = OnesTaskRepository()

        repository.getIssues(null, 0, UI_PAGE_SIZE, withClosed = true) shouldBe emptyArray()

        verify(exactly = 0) { OnesClient.getIssues(any(), any(), any(), any()) }
    }

    test("getIssues propagates client errors so the task list error banner can show them") {
        every { OnesClient.getIssues(SERVER, TEAM, KEY, any()) } throws
            IllegalStateException("API key 无效或已过期")
        val repository = configuredRepository()

        val error = shouldThrowExactly<IllegalStateException> {
            repository.getIssues(null, 0, UI_PAGE_SIZE, withClosed = true)
        }
        error shouldHaveMessage "API key 无效或已过期"
    }

    test("findTask maps the detail envelope into the matching issue") {
        every { OnesClient.getIssue(SERVER, TEAM, KEY, ISSUE_ID) } returns DETAIL_FIXTURE
        val repository = configuredRepository()

        val task = repository.findTask(ISSUE_ID)

        task.shouldNotBeNull()
        task.id shouldBe ISSUE_ID
        task.summary shouldBe "客户端支持应用内下载更新"
        task.number shouldBe "1"
    }

    test("findTask returns null on 404 — the task is absent, not an error") {
        every { OnesClient.getIssue(SERVER, TEAM, KEY, "missing") } throws
            FileNotFoundException("工作项不存在: missing")
        val repository = configuredRepository()

        repository.findTask("missing") shouldBe null
    }

    test("findTask returns null without calling the client when not configured") {
        val repository = OnesTaskRepository()

        repository.findTask(ISSUE_ID) shouldBe null

        verify(exactly = 0) { OnesClient.getIssue(any(), any(), any(), any()) }
    }

    test("findTask propagates authentication failures") {
        every { OnesClient.getIssue(SERVER, TEAM, KEY, ISSUE_ID) } throws
            IllegalStateException("API key 无效或已过期")
        val repository = configuredRepository()

        val error = shouldThrowExactly<IllegalStateException> { repository.findTask(ISSUE_ID) }
        error shouldHaveMessage "API key 无效或已过期"
    }

    test("commit loop: {id} formats to the ONES number and extractId reads it back") {
        val issue = parseIssue(DETAIL_FIXTURE, SERVER, TEAM).shouldNotBeNull()
        val repository = configuredRepository()

        issue.presentableId shouldBe "1"
        repository.commitMessageFormat shouldBe "{id} {summary}"

        // The platform substitution the changelist commit flow performs
        // (TaskRepository.getTaskComment): {id} → presentableId, {summary} → summary.
        val message = repository.commitMessageFormat
            .replace("{id}", issue.presentableId)
            .replace("{summary}", issue.summary)

        message shouldBe "1 客户端支持应用内下载更新"
        repository.extractId(message) shouldBe "1"
    }

    test("commit loop: multi-digit numbers from the list page round-trip through extractId") {
        val issues = parseIssues(LIST_FIXTURE, SERVER, TEAM)
        val repository = configuredRepository()

        issues[1].presentableId shouldBe "58"
        repository.extractId("58 集成神经网络芯片，进行智能识别和预测分析数据") shouldBe "58"
    }
})

/** In-memory configuration only — apiKey set directly bypasses PasswordSafe. */
private fun configuredRepository() = OnesTaskRepository().apply {
    url = SERVER
    teamID = TEAM
    apiKey = KEY
}

private fun listJson(item: String): String = """{"result":"SUCCESS","data":{"list":[$item]}}"""

private const val SERVER = "https://ones.cn"
private const val TEAM = "team-uuid-1"
private const val KEY = "test-key"
private const val ISSUE_ID = "LEUB2J2u4bWYT7k7"

/** Whatever page size the tasks UI asks for; stubs accept any limit. */
private const val UI_PAGE_SIZE = 50

private const val EMPTY_PAGE = """{"result":"SUCCESS","data":{"list":[]}}"""

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
