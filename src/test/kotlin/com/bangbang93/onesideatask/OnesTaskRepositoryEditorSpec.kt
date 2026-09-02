package com.bangbang93.onesideatask

import com.bangbang93.onesideatask.client.OnesClient
import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.throwable.shouldHaveMessage
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.awt.Container
import java.awt.event.FocusEvent
import javax.swing.JButton

/**
 * Application-independent unit tests for [OnesTaskRepositoryEditor].
 *
 * ## What is covered and why
 * The editor's business logic (`apply()` write-back, the keep-existing-key rule,
 * `performTestConnection()` propagation, change-listener notification) is driven
 * through the Swing fields directly — constructing `JBTextField`/`JPasswordField`
 * and firing document events requires no IntelliJ Application.
 *
 * `createComponent()` IS covered: FormBuilder/JLabel/JBTextField all construct
 * headlessly (verified in task 10 — the earlier skip assumption did not hold),
 * so the test asserts the panel wires in the exact internal field instances
 * plus exactly one button.
 *
 * `OnesCredentialsStore` is object-mocked in every test (with both members stubbed),
 * so the real PasswordSafe is never touched.
 */
class OnesTaskRepositoryEditorSpec : FunSpec({

    val project: Project = mockk(relaxed = true)
    val changeListener: Consumer<in OnesTaskRepository> = mockk(relaxed = true)

    beforeTest {
        mockkObject(OnesClient, OnesCredentialsStore)
        // Defensive: even an accidental credential-store call must not reach the real PasswordSafe.
        every { OnesCredentialsStore.save(any(), any(), any()) } just Runs
        every { OnesCredentialsStore.load(any(), any()) } returns null
    }
    afterTest { (_, _) ->
        unmockkObject(OnesClient, OnesCredentialsStore)
    }

    fun newEditor(repository: OnesTaskRepository): OnesTaskRepositoryEditor =
        OnesTaskRepositoryEditor(repository, project, changeListener)

    test("fields are prefilled from the repository; the stored key is never echoed") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            apiKey = "secret-key"
        }

        val editor = newEditor(repository)

        editor.serverUrlField.text shouldBe "https://ones.cn"
        editor.teamIdField.text shouldBe "team-1"
        // The password field is always empty: rendering the stored key would leak it.
        editor.apiKeyField.password.isEmpty() shouldBe true
    }

    test("apply writes trimmed url and teamID back to the repository") {
        val repository = OnesTaskRepository()
        val editor = newEditor(repository)

        editor.serverUrlField.text = "  https://ones.cn  "
        editor.teamIdField.text = "  team-1  "
        editor.apply()

        repository.url shouldBe "https://ones.cn"
        repository.teamID shouldBe "team-1"
    }

    test("typing an API key updates the in-memory repository but never touches the credentials store") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            apiKey = "old-key"
        }
        val editor = newEditor(repository)

        // Every character fires a document event → live apply; the store must NOT
        // be hammered with partial keys (issues.md polish item, fixed in todo 9).
        editor.apiKeyField.text = "n"
        editor.apiKeyField.text = "ne"
        editor.apiKeyField.text = "new-key"

        repository.apiKey shouldBe "new-key"
        verify(exactly = 0) { OnesCredentialsStore.save(any(), any(), any()) }
    }

    test("focus leaving a non-blank API key field persists it via the credentials store exactly once") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
        }
        val editor = newEditor(repository)

        editor.apiKeyField.text = "new-key"
        editor.apiKeyPersistListener.focusLost(FocusEvent(editor.apiKeyField, FocusEvent.FOCUS_LOST))

        verify(exactly = 1) { OnesCredentialsStore.save("https://ones.cn", "team-1", "new-key") }
    }

    test("leaving the API key blank keeps the existing key and never touches the store") {
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            apiKey = "existing-key"
        }
        val editor = newEditor(repository)

        editor.teamIdField.text = "team-2" // edit something else; key field stays blank
        editor.apply()
        editor.apiKeyPersistListener.focusLost(FocusEvent(editor.apiKeyField, FocusEvent.FOCUS_LOST))

        repository.teamID shouldBe "team-2"
        repository.apiKey shouldBe "existing-key"
        verify(exactly = 0) { OnesCredentialsStore.save(any(), any(), any()) }
        verify(exactly = 0) { OnesCredentialsStore.load(any(), any()) }
    }

    test("field edits notify the change listener (framework contract)") {
        val repository = OnesTaskRepository()
        val editor = newEditor(repository)

        editor.serverUrlField.text = "https://ones.cn"

        verify(atLeast = 1) { changeListener.accept(repository) }
    }

    test("performTestConnection fails with the not-configured message on a bare repository") {
        val editor = newEditor(OnesTaskRepository())

        val result = editor.performTestConnection()

        result.isSuccess shouldBe false
        val error = result.exceptionOrNull().shouldNotBeNull()
        error.shouldBeInstanceOf<IllegalStateException>()
        error shouldHaveMessage "请先配置服务器地址、团队 ID 与 API key"
    }

    test("performTestConnection propagates client errors such as an invalid API key") {
        every { OnesClient.getTeams("https://ones.cn", "key-1") } throws
            IllegalStateException("API key 无效或已过期")
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            apiKey = "key-1"
        }
        val editor = newEditor(repository)

        val result = editor.performTestConnection()

        result.isSuccess shouldBe false
        val error = result.exceptionOrNull().shouldNotBeNull()
        error.shouldBeInstanceOf<IllegalStateException>()
        error shouldHaveMessage "API key 无效或已过期"
    }

    test("performTestConnection succeeds when the client returns a response") {
        every { OnesClient.getTeams("https://ones.cn", "key-1") } returns "{}"
        val repository = OnesTaskRepository().apply {
            url = "https://ones.cn"
            teamID = "team-1"
            apiKey = "key-1"
        }
        val editor = newEditor(repository)

        val result = editor.performTestConnection()

        result.isSuccess shouldBe true
    }

    test("createComponent builds the form panel headlessly with the internal fields wired in") {
        val editor = newEditor(OnesTaskRepository())

        val panel = editor.createComponent()

        panel.shouldNotBeNull()
        panel.isAncestorOf(editor.serverUrlField) shouldBe true
        panel.isAncestorOf(editor.teamIdField) shouldBe true
        panel.isAncestorOf(editor.apiKeyField) shouldBe true
        buttonsIn(panel).size shouldBe 1
    }

    test("preferred focused component is the server URL field (framework focus contract)") {
        val editor = newEditor(OnesTaskRepository())

        editor.preferredFocusedComponent shouldBe editor.serverUrlField
    }

    test("a change listener that edits fields during its callback does not recurse infinitely") {
        lateinit var editor: OnesTaskRepositoryEditor
        var callbacks = 0
        val recursiveListener = object : Consumer<OnesTaskRepository> {
            override fun consume(repository: OnesTaskRepository) {
                callbacks++
                editor.serverUrlField.text = "https://edited-$callbacks.example.com"
            }
        }
        editor = OnesTaskRepositoryEditor(OnesTaskRepository(), project, recursiveListener)

        // Insert into the empty field = exactly one document event → one apply →
        // one callback; the guard then swallows the callback's own setText.
        editor.teamIdField.text = "team-1"

        // without the applying guard the callback's setText would re-enter apply unboundedly (StackOverflowError)
        callbacks shouldBe 1
    }
})

private fun buttonsIn(container: Container): List<JButton> =
    container.components.flatMap { child ->
        when (child) {
            is JButton -> listOf(child)
            is Container -> buttonsIn(child)
            else -> emptyList()
        }
    }
