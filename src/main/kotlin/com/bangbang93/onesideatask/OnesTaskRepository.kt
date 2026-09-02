package com.bangbang93.onesideatask

import com.bangbang93.onesideatask.client.OnesClient
import com.bangbang93.onesideatask.model.parseIssue
import com.bangbang93.onesideatask.model.parseIssues
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.tasks.CustomTaskState
import com.intellij.tasks.Task
import com.intellij.tasks.TaskRepository
import com.intellij.tasks.TaskRepositoryType
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.Transient
import java.io.FileNotFoundException
import java.util.Objects

/**
 * ONES 任务仓库（一期只读）。
 *
 * ## 持久化契约（2026.2 实测，见 task-4 证据）
 * TaskManagerImpl.getState() 用 XmlSerializer 序列化 repository 数组，
 * loadRepositories() 再按 `type.getName()` 查找子元素回读 —— 因此本类必须
 * `@Tag("ONES")`（与 [OnesTaskRepositoryType.getName] 一致），否则配置在 IDE
 * 重启后丢失。`url`/`shared` 走基类 @Attribute；`teamID` 是本类的 @Tag 元素；
 * **apiKey 永不序列化**（@get:Transient，与基类排除 repositoryType 同一机制）。
 * 反序列化经无参构造（@JvmOverloads 生成）重建，框架随后回填 repositoryType。
 *
 * ## 状态修改（一期只读）
 * setPreferredOpenTaskState/setPreferredCloseTaskState 仅保存到内存——实测
 * （task-4 证据）XmlSerializer 会把这两个属性序列化为空 `<option/>`，且
 * TaskManagerImpl.loadRepositories 反序列化时会回调 setter（传 null），
 * 抛异常会直接炸掉仓库加载，故按只读内存语义实现。它们只是用户的 UI 偏好，
 * 不产生任何 ONES 写操作：真正的状态修改入口 setTaskState 保持基类行为
 * （抛 UnsupportedOperationException），插件也从不调用 ONES 工作流接口。
 */
@Tag("ONES")
class OnesTaskRepository @JvmOverloads constructor(
    type: TaskRepositoryType<*> = OnesTaskRepositoryType(),
) : TaskRepository(type) {

    /** ONES 团队 UUID（`/openapi/v2/account/teams` 返回的 id）。 */
    @get:Tag("teamID")
    var teamID: String? = null

    /**
     * 内存中的 API key（编辑器写入；重启后经 [OnesCredentialsStore] 懒加载）。
     * 绝不序列化、绝不进日志 / toString / 异常消息。
     */
    @get:Transient
    var apiKey: String? = null

    /** 基类字段（url/shared/提交格式）+ 本类字段的逐项拷贝。 */
    private constructor(copyFrom: OnesTaskRepository) : this(copyFrom.repositoryType) {
        url = copyFrom.url
        isShared = copyFrom.isShared
        isShouldFormatCommitMessage = copyFrom.isShouldFormatCommitMessage
        commitMessageFormat = copyFrom.commitMessageFormat
        teamID = copyFrom.teamID
        apiKey = copyFrom.apiKey
    }

    /** `apiKey ?: PasswordSafe`——仅在 url 与 teamID 都已配置时才触碰密钥库。 */
    private val effectiveApiKey: String?
        get() {
            if (apiKey != null) return apiKey
            val serverUrl = url
            val team = teamID
            return if (serverUrl.isNullOrBlank() || team.isNullOrBlank()) {
                null
            } else {
                OnesCredentialsStore.load(serverUrl, team)
            }
        }

    /** 基类只检查 url；这里加上 teamID 与 key 才算配置完整。 */
    override fun isConfigured(): Boolean =
        !url.isNullOrBlank() && !teamID.isNullOrBlank() && !effectiveApiKey.isNullOrBlank()

    @Suppress("OVERRIDE_DEPRECATION") // 基类 testConnection 已 @Deprecated（框架改走 createCancellableConnection），编辑器直连测试仍需要它
    override fun testConnection() {
        val serverUrl = url
        val key = effectiveApiKey
        if (serverUrl.isNullOrBlank() || teamID.isNullOrBlank() || key.isNullOrBlank()) {
            throw IllegalStateException(MSG_NOT_CONFIGURED)
        }
        OnesClient.getTeams(serverUrl, key)
    }

    override fun extractId(taskName: String): String? =
        HASH_ID_PATTERN.find(taskName)?.groupValues?.get(1)
            ?: LEADING_ID_PATTERN.find(taskName)?.groupValues?.get(1)

    override fun clone(): TaskRepository = OnesTaskRepository(this)

    /**
     * 配置等价：url + shared + teamID + apiKey（仅内存值）+ 提交信息格式。
     * 刻意不读取 PasswordSafe（equals 可能被频繁调用）；重启后双方 apiKey 均为
     * null，等价性不受影响。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OnesTaskRepository) return false
        return url == other.url &&
            isShared == other.isShared &&
            teamID == other.teamID &&
            apiKey == other.apiKey &&
            commitMessageFormat == other.commitMessageFormat
    }

    override fun hashCode(): Int =
        Objects.hash(url, isShared, teamID, apiKey, commitMessageFormat)

    /** 绝不包含 apiKey。 */
    override fun toString(): String = "OnesTaskRepository(url=$url, teamID=$teamID)"

    override fun setPreferredOpenTaskState(state: CustomTaskState?) {
        preferredOpenTaskState = state
    }

    override fun getPreferredOpenTaskState(): CustomTaskState? = preferredOpenTaskState

    override fun setPreferredCloseTaskState(state: CustomTaskState?) {
        preferredCloseTaskState = state
    }

    override fun getPreferredCloseTaskState(): CustomTaskState? = preferredCloseTaskState

    /**
     * 按 id 精确加载单个工作项：`GET /openapi/v2/project/issues/{id}` → 详情信封解析。
     *
     * ## 错误语义
     *  - 未配置（url/teamID/API key 任一缺失）→ null——框架会 eager 探测仓库，
     *    "未配置"不是错误；
     *  - 404（client 抛 [FileNotFoundException]）→ null：工作项不存在是"没有"，
     *    不是失败；
     *  - 其余 client 错误（401/403/FAIL 信封等）**原样上抛**，由调用方展示；
     *  - 详情信封缺 data / JSON 畸形 → null（[parseIssue] 的宽松解析决策）。
     */
    override fun findTask(id: String): Task? {
        val serverUrl = url
        val team = teamID
        val key = effectiveApiKey
        if (serverUrl.isNullOrBlank() || team.isNullOrBlank() || key.isNullOrBlank()) {
            return null
        }
        return try {
            parseIssue(OnesClient.getIssue(serverUrl, team, key, id), serverUrl, team)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /**
     * 拉取团队工作项第一页并映射为 [Task]（Open Task 选择器的数据源）。
     *
     * ## 拉取与分页策略
     * ONES 列表接口本身是游标分页，但 tasks UI 的选择器只需要第一页即可用：
     * 一期固定拉取单页，页大小 fetchLimit = `maxOf(limit, [MIN_FETCH]).coerceAtMost(MAX_FETCH)`
     * ——既保证选择器有足够候选（UI 传入的 limit 偏小），也封顶单次请求量。
     * query 过滤（summary 包含、忽略大小写）、withClosed 过滤（丢弃 done 类目）
     * 与 offset（无限滚动的页内位移，本地 drop）都在这一页上完成。**翻页拉取
     * 第二页及以后超出一期范围。**
     *
     * ## 错误语义
     * 未配置 → 空数组（同 [findTask] 的"未配置不是错误"）；client 的 HTTP/业务
     * 错误（401/403/FAIL 信封）**原样上抛**——任务列表的错误横幅负责把失败展示
     * 给用户，吞掉它们只会让列表静默变空。
     */
    override fun getIssues(query: String?, offset: Int, limit: Int, withClosed: Boolean): Array<Task> {
        val serverUrl = url
        val team = teamID
        val key = effectiveApiKey
        if (serverUrl.isNullOrBlank() || team.isNullOrBlank() || key.isNullOrBlank()) {
            return emptyArray()
        }
        val searchText = query?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        return parseIssues(OnesClient.getIssues(serverUrl, team, key, maxOf(limit, MIN_FETCH).coerceAtMost(MAX_FETCH)), serverUrl, team)
            .filter { withClosed || !it.isClosed }
            .filter { searchText == null || it.summary.lowercase().contains(searchText) }
            .drop(offset)
            .toTypedArray<Task>()
    }

    private companion object {
        val HASH_ID_PATTERN = Regex("""#(\d+)""")
        val LEADING_ID_PATTERN = Regex("""^(\d+)""")
        const val MSG_NOT_CONFIGURED = "请先配置服务器地址、团队 ID 与 API key"

        /** 单页拉取下限：任务选择器至少要看到这么多候选（即使 UI 传入更小的 limit）。 */
        const val MIN_FETCH = 50

        /** 单页拉取上限：封顶单次列表请求量，避免一次拉取过多。 */
        const val MAX_FETCH = 100
    }

    /** 仅内存中的 UI 偏好；见类文档"状态修改"一节。 */
    private var preferredOpenTaskState: CustomTaskState? = null

    /** 仅内存中的 UI 偏好；见类文档"状态修改"一节。 */
    private var preferredCloseTaskState: CustomTaskState? = null
}

/**
 * ONES API key 的平台安全存储封装——插件中唯一触碰 PasswordSafe 的位置，
 * 单元测试因此可以完全绕开 IntelliJ Application。
 */
object OnesCredentialsStore {

    fun load(serverUrl: String, teamID: String): String? =
        PasswordSafe.instance.getPassword(attributes(serverUrl, teamID))

    fun save(serverUrl: String, teamID: String, apiKey: String) {
        PasswordSafe.instance.set(attributes(serverUrl, teamID), Credentials(null, apiKey))
    }

    fun clear(serverUrl: String, teamID: String) {
        PasswordSafe.instance.set(attributes(serverUrl, teamID), null)
    }

    private fun attributes(serverUrl: String, teamID: String): CredentialAttributes =
        CredentialAttributes(serviceName(serverUrl, teamID), null)

    private fun serviceName(serverUrl: String, teamID: String): String =
        "$SERVICE_NAME_PREFIX $serverUrl/$teamID"

    private const val SERVICE_NAME_PREFIX = "ONES Task API key"
}
