package com.bangbang93.onesideatask

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.tasks.TaskRepositoryType
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.util.Consumer
import javax.swing.Icon

/**
 * Extension point implementation for `com.intellij.tasks.repositoryType`.
 * Registered in plugin.xml by task 2.
 */
class OnesTaskRepositoryType : TaskRepositoryType<OnesTaskRepository>() {

    override fun getName(): String = NAME

    override fun getIcon(): Icon = IconLoader.getIcon(ICON_PATH, javaClass)

    override fun createRepository(): OnesTaskRepository =
        OnesTaskRepository(this).apply { url = DEFAULT_SERVER_URL }

    override fun getRepositoryClass(): Class<OnesTaskRepository> = OnesTaskRepository::class.java

    override fun createEditor(
        repository: OnesTaskRepository,
        project: Project,
        changeListener: Consumer<in OnesTaskRepository>,
    ): TaskRepositoryEditor = OnesTaskRepositoryEditor(repository, project, changeListener)

    override fun getSortOrder(): Int = SORT_ORDER

    private companion object {
        const val NAME = "ONES"
        const val ICON_PATH = "/icons/ones.svg"
        const val SORT_ORDER = 500
    }
}

/** Prefill for the SaaS server URL; private deployments override it in the editor (task 5). */
internal const val DEFAULT_SERVER_URL = "https://ones.cn"
