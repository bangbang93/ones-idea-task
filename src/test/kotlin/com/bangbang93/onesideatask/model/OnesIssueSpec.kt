package com.bangbang93.onesideatask.model

import com.intellij.tasks.TaskState
import com.intellij.tasks.TaskType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Date

/**
 * Behavior tests for the ONES JSON → [com.intellij.tasks.Task] mapping layer.
 *
 * The fixtures follow the OFFICIAL response schema of ONES Open API v2
 * "Get a list of issues" / "Get a issue details" (docs.ones.com), with field
 * VALUES calibrated against a real ONES instance on 2026-09-02 (via the ones
 * MCP connection): `createTime`/`serverUpdateStamp` are 16-digit epoch
 * MICROSECONDS, `status.category` is lowercase `to_do`/`in_progress`/`done`,
 * and the detail envelope puts the issue object directly under `data`
 * (no `list` wrapper).
 *
 * Not covered here: `getIcon()` — loading /icons/ones.svg needs icon
 * infrastructure that does not exist in a bare-JVM test (same reasoning as
 * [com.bangbang93.onesideatask.OnesTaskRepositoryTypeSpec]).
 *
 * Documented lenient-parse decision (see OnesIssue.kt): malformed or empty
 * JSON makes [parseIssues] return an empty list and [parseIssue] return null.
 */
class OnesIssueSpec : FunSpec({

    test("parseIssues maps count and all core fields from the official list shape") {
        val issues = parseIssues(LIST_FIXTURE, SERVER, TEAM)

        issues.size shouldBe 3
        val first = issues[0]
        first.id shouldBe "LEUB2J2u4bWYT7k7"
        first.summary shouldBe "客户端支持应用内下载更新"
        first.number shouldBe "1"
        first.data.assignee?.name shouldBe "马晓迪"
        first.data.status?.name shouldBe "待规划"
        first.data.status?.category shouldBe "to_do"
        first.data.project?.name shouldBe "ONES-TEST"
        first.data.createTime shouldBe 1788315974446000L
        first.project shouldBe "ONES-TEST"
        first.isIssue shouldBe true
        first.description shouldBe null
        first.comments.size shouldBe 0
        first.updated shouldBe null
    }

    test("issueUrl joins trimmed server URL, teamID and issue id in the calibrated ONES web shape") {
        val plain = parseIssues(LIST_FIXTURE, "https://ones.example.com", TEAM)[0]
        val slashed = parseIssues(LIST_FIXTURE, "https://ones.example.com/", TEAM)[0]

        plain.issueUrl shouldBe "https://ones.example.com/project/#/team/team-uuid-1/task/LEUB2J2u4bWYT7k7"
        slashed.issueUrl shouldBe "https://ones.example.com/project/#/team/team-uuid-1/task/LEUB2J2u4bWYT7k7"
    }

    test("issueUrl is null when teamID is null or blank — a team-less deep link would be dead") {
        parseIssues(LIST_FIXTURE, SERVER, null)[0].issueUrl shouldBe null
        parseIssues(LIST_FIXTURE, SERVER, "")[0].issueUrl shouldBe null
        parseIssues(LIST_FIXTURE, SERVER, "   ")[0].issueUrl shouldBe null
        parseIssue(DETAIL_FIXTURE, SERVER, null).shouldNotBeNull().issueUrl shouldBe null
    }

    test("to_do category maps to OPEN and is not closed") {
        val issue = parseIssues(LIST_FIXTURE, SERVER, TEAM)[0]

        issue.state shouldBe TaskState.OPEN
        issue.isClosed shouldBe false
    }

    test("in_progress category maps to IN_PROGRESS and is not closed") {
        val issue = parseIssues(LIST_FIXTURE, SERVER, TEAM)[1]

        issue.state shouldBe TaskState.IN_PROGRESS
        issue.isClosed shouldBe false
    }

    test("done category maps to RESOLVED and is closed") {
        val issue = parseIssues(LIST_FIXTURE, SERVER, TEAM)[2]

        issue.state shouldBe TaskState.RESOLVED
        issue.isClosed shouldBe true
    }

    test("a status without category falls back to OPEN without throwing") {
        val issue = parseIssues(listJson("""{"id":"i1","title":"t","number":1,"status":{"id":"s1","name":"待规划"}}"""), SERVER, TEAM)[0]

        issue.state shouldBe TaskState.OPEN
        issue.isClosed shouldBe false
    }

    test("an unknown status category falls back to OPEN without throwing") {
        val issue = parseIssues(listJson("""{"id":"i1","title":"t","number":1,"status":{"id":"s1","name":"挂起","category":"paused"}}"""), SERVER, TEAM)[0]

        issue.state shouldBe TaskState.OPEN
        issue.isClosed shouldBe false
    }

    test("missing assignee, status and project parse to nulls with sensible Task defaults") {
        val issue = parseIssues(listJson("""{"id":"i1","title":"t","number":3}"""), SERVER, TEAM)[0]

        issue.data.assignee shouldBe null
        issue.data.status shouldBe null
        issue.data.project shouldBe null
        issue.project shouldBe null
        issue.state shouldBe TaskState.OPEN
        issue.isClosed shouldBe false
    }

    test("an empty item object yields empty id and summary instead of throwing") {
        val issue = parseIssues(listJson("{}"), SERVER, TEAM)[0]

        issue.id shouldBe ""
        issue.summary shouldBe ""
        issue.number shouldBe ""
        issue.presentableId shouldBe ""
    }

    test("an empty or missing list returns an empty result") {
        parseIssues("""{"result":"SUCCESS","data":{"list":[]}}""", SERVER, TEAM) shouldBe emptyList()
        parseIssues("""{"result":"SUCCESS","data":{}}""", SERVER, TEAM) shouldBe emptyList()
        parseIssues("""{"result":"SUCCESS"}""", SERVER, TEAM) shouldBe emptyList()
    }

    test("malformed or empty JSON returns an empty list (documented lenient decision)") {
        parseIssues("not json at all", SERVER, TEAM) shouldBe emptyList()
        parseIssues("", SERVER, TEAM) shouldBe emptyList()
    }

    test("parseIssue maps the single-item detail envelope") {
        val issue = parseIssue(DETAIL_FIXTURE, SERVER, TEAM)

        issue.shouldNotBeNull()
        issue.id shouldBe "LEUB2J2u4bWYT7k7"
        issue.summary shouldBe "客户端支持应用内下载更新"
        issue.number shouldBe "1"
        issue.data.assignee?.name shouldBe "马晓迪"
        issue.state shouldBe TaskState.OPEN
    }

    test("parseIssue returns null for malformed or data-less JSON") {
        parseIssue("garbage", SERVER, TEAM) shouldBe null
        parseIssue("""{"result":"SUCCESS"}""", SERVER, TEAM) shouldBe null
        parseIssue("""{"data":null}""", SERVER, TEAM) shouldBe null
    }

    test("created and updated dates convert epoch microseconds to milliseconds") {
        val listIssue = parseIssues(LIST_FIXTURE, SERVER, TEAM)[0]
        val detailIssue = parseIssue(DETAIL_FIXTURE, SERVER, TEAM).shouldNotBeNull()

        listIssue.created shouldBe Date(1788315974446)
        listIssue.updated shouldBe null
        detailIssue.created shouldBe Date(1788315974446)
        detailIssue.updated shouldBe Date(1788315974446)
    }

    test("issueType maps 需求 to FEATURE, 缺陷 to BUG, and unknown or missing types to OTHER") {
        parseIssues(LIST_FIXTURE, SERVER, TEAM)[0].type shouldBe TaskType.FEATURE
        parseIssues(LIST_FIXTURE, SERVER, TEAM)[2].type shouldBe TaskType.OTHER

        val bug = parseIssues(listJson("""{"id":"b1","title":"t","issueType":{"id":"it","name":"缺陷"}}"""), SERVER, TEAM)[0]
        bug.type shouldBe TaskType.BUG

        val untyped = parseIssues(listJson("""{"id":"u1","title":"t"}"""), SERVER, TEAM)[0]
        untyped.type shouldBe TaskType.OTHER
    }

    test("number accepts a JSON string form, and presentableId/number fall back to the id") {
        val textual = parseIssues(listJson("""{"id":"s1","title":"t","number":"A-12"}"""), SERVER, TEAM)[0]
        textual.number shouldBe "A-12"
        textual.presentableId shouldBe "A-12"

        val missing = parseIssues(listJson("""{"id":"fallback-id","title":"t"}"""), SERVER, TEAM)[0]
        missing.number shouldBe "fallback-id"
        missing.presentableId shouldBe "fallback-id"
    }

    test("number parses identically from JSON integer 42 and string \"42\" (Gson nextString coercion)") {
        val issues = parseIssues(
            """{"result":"SUCCESS","data":{"list":[
                {"id":"a","title":"t","number":42},
                {"id":"b","title":"t","number":"42"}
            ]}}""",
            SERVER, TEAM,
        )

        issues[0].number shouldBe "42"
        issues[1].number shouldBe "42"
        issues.map { it.presentableId } shouldBe listOf("42", "42")
    }

    test("parseIssues drops null list elements instead of throwing (lenient-parse contract)") {
        val issues = parseIssues(
            """{"result":"SUCCESS","data":{"list":[{"id":"i1","title":"t","number":1}, null]}}""",
            SERVER, TEAM,
        )

        issues.size shouldBe 1
        issues[0].id shouldBe "i1"
    }
})

private fun listJson(item: String): String = """{"result":"SUCCESS","data":{"list":[$item]}}"""

private const val SERVER = "https://ones.example.com"

private const val TEAM = "team-uuid-1"

/** Official "Get a list of issues" envelope; values calibrated from a live ONES instance. */
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
