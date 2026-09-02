package com.bangbang93.onesideatask

import com.bangbang93.onesideatask.client.OnesClient
import com.intellij.util.xmlb.XmlSerializer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.throwable.shouldHaveMessage
import io.kotest.assertions.throwables.shouldThrowExactly
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import com.intellij.openapi.util.JDOMUtil

/**
 * Application-independent unit tests for [OnesTaskRepository].
 *
 * The serialization round-trip uses `com.intellij.util.xmlb.XmlSerializer` — the
 * serializer core that `TaskManagerImpl.getState()/loadRepositories()` persist
 * repositories with (via its configurationStore facade, which delegates here;
 * the facade itself is a @JvmName file class, not Kotlin-callable — see task-4
 * evidence). `@Tag("ONES")` on the class produces the `<ONES>` element that
 * loadRepositories() looks up by repository type name.
 *
 * `OnesCredentialsStore`/PasswordSafe is NEVER touched here: every test that needs
 * a key sets the in-memory [OnesTaskRepository.apiKey] directly, so the lazy
 * store lookup is bypassed.
 */
class OnesTaskRepositorySpec : FunSpec({

    beforeTest {
        mockkObject(OnesClient)
    }
    afterTest { (_, _) ->
        unmockkObject(OnesClient)
    }

    test("isConfigured is false while any field is blank") {
        OnesTaskRepository().isConfigured shouldBe false

        OnesTaskRepository().apply { url = "https://ones.cn" }.isConfigured shouldBe false
    }

    test("isConfigured is true with url, teamID and apiKey set") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-uuid-1"
            apiKey = "key-1"
        }

        repository.isConfigured shouldBe true
    }

    test("extractId recognizes #<digits> anywhere in the summary") {
        OnesTaskRepository().extractId("fix #123") shouldBe "123"
        OnesTaskRepository().extractId("#456") shouldBe "456"
    }

    test("extractId recognizes leading digits without hash") {
        OnesTaskRepository().extractId("789 summary") shouldBe "789"
    }

    test("extractId returns null when no id is present") {
        val repository = OnesTaskRepository()

        repository.extractId("no digits") shouldBe null
        repository.extractId("#abc") shouldBe null
    }

    test("extractId returns null for an empty summary") {
        OnesTaskRepository().extractId("") shouldBe null
    }

    test("extractId picks the FIRST hash id when a summary mentions several") {
        OnesTaskRepository().extractId("fix #1 then #2") shouldBe "1"
    }

    test("extractId keeps the matched digits as a string: leading zeros and huge numbers survive") {
        // the id is a display string, never parsed to a number
        OnesTaskRepository().extractId("James Bond #007") shouldBe "007"
        OnesTaskRepository().extractId("#99999999999999999999999999") shouldBe "99999999999999999999999999"
    }

    test("equals/hashCode: same config is equal; any tracked field difference breaks equality") {
        fun configured() = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            apiKey = "key-1"
        }

        val a = configured()
        val b = configured()
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()

        b.teamID = "team-2"
        a shouldNotBe b

        b.teamID = "team-1"
        b.apiKey = "key-2"
        a shouldNotBe b

        b.apiKey = "key-1"
        b.commitMessageFormat = "{number} {summary}"
        a shouldNotBe b
    }

    test("clone copies url, teamID and apiKey; mutating the original does not affect the clone") {
        val original = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-uuid-1"
            apiKey = "key-1"
        }

        val copy = original.clone() as OnesTaskRepository

        copy.url shouldBe "https://ones.cn"
        copy.teamID shouldBe "team-uuid-1"
        copy.apiKey shouldBe "key-1"
        copy.commitMessageFormat shouldBe original.commitMessageFormat

        original.url = "https://other.example.com"
        original.teamID = "team-uuid-2"
        original.apiKey = "key-2"
        copy.url shouldBe "https://ones.cn"
        copy.teamID shouldBe "team-uuid-1"
        copy.apiKey shouldBe "key-1"
    }

    test("commit message format defaults to {id} {summary}") {
        OnesTaskRepository().commitMessageFormat shouldBe "{id} {summary}"
    }

    test("serialization round-trip persists teamID, never the API key") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-uuid-1"
            apiKey = "secret-key"
        }

        // Same call TaskManagerImpl.getState() makes: serialize the repository array.
        val servers = XmlSerializer.serialize(arrayOf(repository))
        val xml = JDOMUtil.write(servers)

        // loadRepositories() looks up children by repository type name — the
        // element must exist for the repository to survive an IDE restart.
        servers.getChildren("ONES").size shouldBe 1
        xml shouldContain "team-uuid-1"
        xml shouldNotContain "secret-key"

        // Same call TaskManagerImpl.loadRepositories() makes per <ONES> element.
        val restored = XmlSerializer.deserialize(servers.getChildren("ONES")[0], OnesTaskRepository::class.java)
        restored.url shouldBe "https://ones.cn"
        restored.teamID shouldBe "team-uuid-1"
        restored.apiKey shouldBe null
        restored.commitMessageFormat shouldBe "{id} {summary}"
    }

    test("serialization round-trip keeps a null teamID blank") {
        val repository = OnesTaskRepository().apply { url = "https://ones.cn" }

        val servers = XmlSerializer.serialize(arrayOf(repository))
        val restored = XmlSerializer.deserialize(servers.getChildren("ONES")[0], OnesTaskRepository::class.java)

        // TagBinding maps the empty <teamID/> element to "" (not null) — blank is
        // equivalent for isConfigured()/equals() purposes.
        restored.teamID shouldBe ""
        restored.url shouldBe "https://ones.cn"
    }

    test("testConnection throws IllegalStateException with actionable message when not configured") {
        val repository = OnesTaskRepository()

        val error = shouldThrowExactly<IllegalStateException> { repository.testConnection() }
        error shouldHaveMessage "请先配置服务器地址、团队 ID 与 API key"
    }

    test("testConnection succeeds when configured and ONES answers") {
        every { OnesClient.getTeams("https://ones.cn", "key-1") } returns """{"result":"SUCCESS","data":[]}"""
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-uuid-1"
            apiKey = "key-1"
        }

        repository.testConnection() // must not throw
    }

    test("testConnection propagates client errors such as an invalid API key") {
        every { OnesClient.getTeams("https://ones.cn", "key-1") } throws
            IllegalStateException("API key 无效或已过期")
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-uuid-1"
            apiKey = "key-1"
        }

        val error = shouldThrowExactly<IllegalStateException> { repository.testConnection() }
        error shouldHaveMessage "API key 无效或已过期"
    }

    test("preferred task states are kept in memory only (read-only phase 1)") {
        val repository = OnesTaskRepository()
        val state = com.intellij.tasks.CustomTaskState("OPEN", "Open")

        repository.preferredOpenTaskState shouldBe null
        repository.setPreferredOpenTaskState(state)
        repository.preferredOpenTaskState shouldBe state

        repository.setPreferredCloseTaskState(state)
        repository.preferredCloseTaskState shouldBe state
    }

    test("toString never contains the API key") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-uuid-1"
            apiKey = "secret-key"
        }

        repository.toString() shouldContain "team-uuid-1"
        repository.toString() shouldNotContain "secret-key"
    }
})
