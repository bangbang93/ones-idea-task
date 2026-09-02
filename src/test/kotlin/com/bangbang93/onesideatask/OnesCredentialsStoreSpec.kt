package com.bangbang93.onesideatask

import com.bangbang93.onesideatask.client.OnesClient
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify

/**
 * Tests for [OnesCredentialsStore] — the only place touching PasswordSafe — with a
 * mocked PasswordSafe instead of a mocked store, so the REAL attribute assembly
 * (serviceName = "ONES Task API key <url>/<teamID>") is what's under test.
 *
 * Feasibility (javap, intellij.platform.credentialStore.jar): `PasswordSafe.instance`
 * is a computed companion property (no backing field) — `mockkObject(PasswordSafe)`
 * intercepts the companion, `every { PasswordSafe.instance } returns fake` swaps the
 * implementation, and callers inside the production store go through the companion
 * getter, so interception holds end-to-end.
 *
 * The last test exercises the repository's lazy credential fallback (IDE restart:
 * in-memory apiKey is null; effectiveApiKey must transparently load from the store)
 * over the REAL store + REAL repository + mocked client.
 */
class OnesCredentialsStoreSpec : FunSpec({

    val passwordSafe = mockk<PasswordSafe>(relaxed = true)

    beforeTest {
        mockkObject(OnesClient, PasswordSafe)
        every { PasswordSafe.instance } returns passwordSafe
    }
    afterTest { (_, _) ->
        unmockkObject(OnesClient, PasswordSafe)
    }

    test("save writes the key under the per-repository serviceName") {
        OnesCredentialsStore.save("https://ones.cn", "team-1", "key-1")

        val attributes = slot<CredentialAttributes>()
        val credentials = slot<Credentials>()
        verify(exactly = 1) { passwordSafe.set(capture(attributes), capture(credentials)) }
        attributes.captured.serviceName shouldBe "ONES Task API key https://ones.cn/team-1"
        credentials.captured.getPasswordAsString() shouldBe "key-1"
    }

    test("load reads the password stored under the per-repository serviceName") {
        every { passwordSafe.getPassword(any()) } returns "stored-key"

        OnesCredentialsStore.load("https://ones.cn", "team-1") shouldBe "stored-key"

        val attributes = slot<CredentialAttributes>()
        verify(exactly = 1) { passwordSafe.getPassword(capture(attributes)) }
        attributes.captured.serviceName shouldBe "ONES Task API key https://ones.cn/team-1"
    }

    test("clear nulls out the credentials under the per-repository serviceName") {
        OnesCredentialsStore.clear("https://ones.cn", "team-1")

        val attributes = slot<CredentialAttributes>()
        verify(exactly = 1) { passwordSafe.set(capture(attributes), null) }
        attributes.captured.serviceName shouldBe "ONES Task API key https://ones.cn/team-1"
    }

    test("repository falls back to the stored credential after an IDE restart (lazy effectiveApiKey)") {
        every { passwordSafe.getPassword(any()) } returns "stored-key"
        every { OnesClient.getIssues("https://ones.cn", "team-1", "stored-key", any()) } returns
            """{"result":"SUCCESS","data":{"list":[{"id":"i1","title":"t","number":1}]}}"""
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            // apiKey deliberately left null: the in-memory key is gone after a restart
        }

        repository.isConfigured shouldBe true
        val tasks = repository.getIssues(null, 0, 50, withClosed = true)

        tasks.size shouldBe 1
        tasks[0].id shouldBe "i1"
    }
})
