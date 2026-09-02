package com.bangbang93.onesideatask

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.tasks.config.TaskRepositoryEditor
import com.intellij.ui.components.JBTextField
import com.intellij.util.Consumer
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

/**
 * ONES 任务仓库配置面板：服务器 URL / 团队 ID / API key + 测试连接。
 *
 * ## 框架契约（2026.2 实测，见 task-5 证据）
 * 2026.2 捆绑的 [TaskRepositoryEditor] 抽象面只有 `createComponent()`（javap）；
 * 宿主 `TaskRepositoriesConfigurable.apply()` 只克隆 repository 对象、**从不调用
 * editor.apply()**。因此编辑器必须随每次输入把值写回 repository 并通过
 * [changeListener] 通知宿主刷新列表——与官方 `BaseRepositoryEditor` 的
 * document-listener → `doApply()` 模式一致（该基类在非捆绑的 Task Management
 * 插件里，本类无法继承，只能复刻其契约）。
 *
 * ## API key 的"留空即保留"语义与持久化时机
 * 密码框永不回显已保存的 key。[apply] 仅在输入非空时更新内存中的
 * [OnesTaskRepository.apiKey]（逐键生效，保证同屏的"测试连接"能用新 key）；
 * **持久化到 [OnesCredentialsStore] 的时机是焦点离开密码框**（[apiKeyPersistListener]），
 * 而不是每次按键——逐键保存会把 PasswordSafe 反复砸进半截 key（issues.md 登记的
 * 打磨项，todo 9 修复）。留空 = 继续使用已保存的 key（内存值与 PasswordSafe 均不动）。
 *
 * `internal` 的三个输入框是单测接缝：测试直接设值/触 document 事件，
 * 不构造 Swing 顶层窗口（createComponent 不做单测，原因见 spec 文档）。
 */
class OnesTaskRepositoryEditor(
    private val repository: OnesTaskRepository,
    private val project: Project,
    private val changeListener: Consumer<in OnesTaskRepository>,
) : TaskRepositoryEditor() {

    internal val serverUrlField = JBTextField()
    internal val teamIdField = JBTextField()
    internal val apiKeyField = JPasswordField()
    private val testConnectionButton = JButton(BUTTON_TEST)

    /** 复刻 BaseRepositoryEditor 的重入保护：changeListener 回调不得再触发 apply。 */
    private var applying = false

    /**
     * API key 的持久化接缝：焦点离开密码框且值非空时，一次性写入 PasswordSafe
     * （时机决策见类文档）。`internal` 供单测直接调 [focusLost][FocusAdapter.focusLost]
     * ——无需真实焦点系统，且用的就是注册在字段上的同一个监听器对象。
     */
    internal val apiKeyPersistListener = object : FocusAdapter() {
        override fun focusLost(event: FocusEvent) {
            val key = String(apiKeyField.password).trim()
            if (key.isEmpty()) return
            OnesCredentialsStore.save(serverUrlField.text.trim(), teamIdField.text.trim(), key)
        }
    }

    init {
        // Prefill BEFORE listeners go in, so programmatic setText does not fire apply().
        serverUrlField.text = repository.url ?: ""
        teamIdField.text = repository.teamID ?: ""
        // apiKeyField 刻意留空：已保存的 key 不回显（见类文档）。
        listenForEdits(serverUrlField)
        listenForEdits(teamIdField)
        listenForEdits(apiKeyField)
        apiKeyField.addFocusListener(apiKeyPersistListener)
        testConnectionButton.addActionListener {
            performTestConnection().fold(
                onSuccess = { Messages.showInfoMessage(project, MSG_CONNECTION_SUCCESS, TITLE_TEST_RESULT) },
                onFailure = { error ->
                    Messages.showErrorDialog(project, error.message ?: "", TITLE_TEST_RESULT)
                },
            )
        }
    }

    override fun createComponent(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel(LABEL_SERVER_URL), serverUrlField)
            .addLabeledComponent(JLabel(LABEL_TEAM_ID), teamIdField)
            .addLabeledComponent(JLabel(LABEL_API_KEY), apiKeyField)
            .addTooltip(HINT_KEEP_EXISTING_KEY)
            .addComponent(testConnectionButton)
            .panel

    override fun getPreferredFocusedComponent(): JComponent = serverUrlField

    /**
     * 把面板值写回 repository：url/teamID 恒写（trim）；API key 仅在非空时写
     * **内存**（留空即保留已存 key）。持久化到 PasswordSafe 不在这里——见
     * [apiKeyPersistListener] 的焦点丢失时机。写完按框架契约通知
     * [changeListener]。宿主从不调用本方法——靠 document listener 驱动。
     */
    fun apply() {
        val serverUrl = serverUrlField.text.trim()
        repository.url = serverUrl
        repository.teamID = teamIdField.text.trim()
        val newKey = String(apiKeyField.password).trim()
        if (newKey.isNotEmpty()) {
            repository.apiKey = newKey
        }
        changeListener.accept(repository)
    }

    /**
     * 测试连接的纯逻辑接缝：调用 [OnesTaskRepository.testConnection] 并把结果
     * 装进 [Result]。对话框只在按钮 action handler 里弹（UI 层），这里不碰
     * Swing 对话框 API——单测可无 UI 地验证异常传播。
     */
    fun performTestConnection(): Result<Unit> = runCatching { repository.testConnection() }

    private fun listenForEdits(field: JTextComponent) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = doApply()
            override fun removeUpdate(event: DocumentEvent) = doApply()
            override fun changedUpdate(event: DocumentEvent) = doApply()
        })
    }

    private fun doApply() {
        if (applying) return
        applying = true
        try {
            apply()
        } finally {
            applying = false
        }
    }

    private companion object {
        const val LABEL_SERVER_URL = "服务器 URL"
        const val LABEL_TEAM_ID = "团队 ID"
        const val LABEL_API_KEY = "API key"
        const val BUTTON_TEST = "测试连接"
        const val HINT_KEEP_EXISTING_KEY = "已保存的 API key 不会回显；留空表示继续使用已保存的 key"
        const val MSG_CONNECTION_SUCCESS = "连接成功"
        const val TITLE_TEST_RESULT = "连接测试"
    }
}
