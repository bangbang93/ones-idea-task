package com.bangbang93.onesideatask

import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk

/**
 * Application-independent unit tests for [OnesTaskRepositoryType].
 *
 * `getIcon()` as a RUNTIME call is still not covered: `IconLoader.getIcon` needs
 * the platform icon/ImageLoader infrastructure (it throws on missing infra in a
 * bare JVM — see task-3 learnings). What IS testable bare is the resource's
 * EXISTENCE, which is the runtime call's precondition: a missing/empty
 * /icons/ones.svg would make every getIcon() invocation fail inside the IDE.
 */
class OnesTaskRepositoryTypeSpec : FunSpec({

    test("repository type name is ONES") {
        OnesTaskRepositoryType().name shouldBe "ONES"
    }

    test("createRepository returns an OnesTaskRepository wired to this type") {
        val type = OnesTaskRepositoryType()
        val repository = type.createRepository()

        repository.shouldBeInstanceOf<OnesTaskRepository>()
        repository.repositoryType shouldBe type
    }

    test("created repository is prefilled with the default server URL") {
        OnesTaskRepositoryType().createRepository().url shouldBe DEFAULT_SERVER_URL
    }

    test("repositoryClass is OnesTaskRepository") {
        OnesTaskRepositoryType().repositoryClass shouldBe OnesTaskRepository::class.java
    }

    test("sort order is 500") {
        OnesTaskRepositoryType().sortOrder shouldBe 500
    }

    test("the icon resource exists on the classpath and is non-empty") {
        val icon = OnesTaskRepositoryType::class.java.getResourceAsStream("/icons/ones.svg")

        icon.shouldNotBeNull()
        icon.use { stream -> stream.readBytes().size shouldBeGreaterThan 0 }
    }

    test("createEditor returns an OnesTaskRepositoryEditor prefilled from the repository") {
        val type = OnesTaskRepositoryType()
        val repository = type.createRepository().apply { teamID = "team-1" }

        val editor = type.createEditor(repository, mockk<Project>(relaxed = true), mockk<Consumer<in OnesTaskRepository>>(relaxed = true))

        editor.shouldBeInstanceOf<OnesTaskRepositoryEditor>()
        editor.serverUrlField.text shouldBe DEFAULT_SERVER_URL
        editor.teamIdField.text shouldBe "team-1"
    }
})
