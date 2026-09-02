@file:Suppress("PrivatePropertyName")

package com.bangbang93.onesideatask.model

import com.google.gson.Gson
import com.intellij.openapi.util.IconLoader
import com.intellij.tasks.Comment
import com.intellij.tasks.Task
import com.intellij.tasks.TaskState
import com.intellij.tasks.TaskType
import java.util.Date
import javax.swing.Icon

/**
 * ONES 工作项模型：Gson 数据类 + [Task] 适配 + JSON 解析入口。
 *
 * 数据形状以 docs.ones.com Open API v2 "Get a list of issues" / "Get a issue
 * details" 的官方 Schema 为准，字段值经真实 ONES 实例校准（2026-09-02）：
 *  - 列表信封：`{"result":"SUCCESS","data":{"list":[...]}}`
 *  - 详情信封：`{"result":"SUCCESS","data":{...工作项字段直接挂在 data 下，无 list 包装}}`
 *  - `number` 官方 Schema 为 integer；按 String 建模（Gson 对 JSON number 调
 *    nextString() 得到字面文本），兼容实例返回非数字字符串的显示安全需求。
 *  - `createTime`/`serverUpdateStamp` 为 **epoch 微秒**（16 位；实测
 *    1788315974446000 ≈ 2026-09-02，旧版 API 文档亦标注 create_time 单位为微秒），
 *    转 [Date] 时除以 1000 折算毫秒。
 *  - `status.category` 实测取值：`to_do` / `in_progress` / `done`（小写下划线，
 *    与 ONES task_stats API 的三分类一致）；归一化（小写、`-`/空格→`_`）后匹配，
 *    兼容大写变体，未知值按未完成处理。
 *
 * ## status.category → Task 状态映射
 * | ONES category（归一化后） | [getState]            | [isClosed] |
 * |---------------------------|-----------------------|------------|
 * | `to_do`、null、未知值      | [TaskState.OPEN]      | false      |
 * | `in_progress`             | [TaskState.IN_PROGRESS] | false    |
 * | `done`                    | [TaskState.RESOLVED]  | true       |
 *
 * ## Task 成员映射决策（2026.2 Task 接口，javap 实测 IU-262.8665.258）
 *  - [getId]：工作项 UUID。Task.equals/hashCode 为 final 且按 id 比较，必须用稳定 UUID。
 *  - [getPresentableId]/[getNumber]：`number`，缺失时回落 id。平台把提交信息格式里的
 *    `{id}` 替换为 getPresentableId()（TaskRepository.getTaskComment 源码），计划要求
 *    提交信息带数字编号，故 presentableId 必须是 number 而非 UUID。
 *  - [getProject]：project.name（基类默认按 `PROJ-123` 形态截取 id 前缀，对 UUID 恒为 null）。
 *  - [getType]：issueType.name 关键词映射（缺陷/bug→BUG，需求/feature/story→FEATURE，
 *    其余→OTHER）。ONES 工作项类型是实例自定义自由文本（实测：需求/客户/发布），关键词映射为尽力而为。
 *  - [getCreated]/[getUpdated]：createTime / serverUpdateStamp（µs→ms）。列表接口不返回
 *    serverUpdateStamp，列表解析出的任务 [getUpdated] 为 null（接口 @Nullable 允许）。
 *  - [getDescription]：null（列表接口不返回描述，一期不建模详情富文本）。
 *  - [getComments]：空数组（评论接口不在一期范围）。
 *  - [getIcon]：插件自带 /icons/ones.svg（与 OnesTaskRepositoryType 同源）。
 *  - [getIssueUrl]：`{serverUrl 去尾斜杠}/project/#/team/{teamID}/task/{id}`。
 *    这是 2026-09-02 经真实 ONES 实例校准的网页真实路径（计划阶段的占位
 *    `/project/issue/{id}` 会产生死链，已废弃）。ONES 网页路由必须携带 teamID
 *    上下文；teamID 为 null/blank 时无法构造有效深链，返回 null（[Task.getIssueUrl]
 *    契约允许 null，平台将不提供跳转入口，好过一条打不开的链接）。
 *  - [isIssue]：true（来自缺陷跟踪器的工作项）。
 *  - getRepository()/getCustomIcon()/getCustomProperties()/getPropertiesToShowInPreview()：
 *    沿用基类默认（null/空集合），预览面板增强不在一期范围。
 *
 * ## 解析失败策略（已定决策）
 *  - [parseIssues]：JSON 畸形/空/缺 list → 返回空列表（与 OnesClient 对非 JSON 响应体的
 *    宽松兜底一致；HTTP 与业务错误已在 client 层抛出）。
 *  - [parseIssue]：同样情形 → 返回 null。
 *  - 不解析 fieldValues 自定义字段（计划明确排除，一期不展示）。
 */

/** ONES 用户引用：`assignee{id, name}`（creator/watchers 同形，一期只建模 assignee）。 */
data class OnesUser(val id: String?, val name: String?)

/** ONES 工作项状态：`status{id, name, category}`，category 见文件级映射表。 */
data class OnesStatus(val id: String?, val name: String?, val category: String?)

/** ONES 项目引用：`project{id, name}`。 */
data class OnesProject(val id: String?, val name: String?)

/** ONES 工作项类型引用：`issueType{id, name}`，name 用于 [TaskType] 关键词映射。 */
data class OnesIssueType(val id: String?, val name: String?)

/**
 * 列表项/详情共有的工作项核心字段（官方 Schema 子集：只建模能喂给 [Task] 成员的字段，
 * dueDate/priority/sprint/subIssues/attachments/工时等一律不收）。全部可空：Gson 走
 * Unsafe 实例化 + 反射设字段，缺失/null 字段不抛异常。
 */
data class OnesIssueData(
    val id: String?,
    val title: String?,
    val number: String?,
    val assignee: OnesUser?,
    val status: OnesStatus?,
    val project: OnesProject?,
    val issueType: OnesIssueType?,
    val createTime: Long?,
    val serverUpdateStamp: Long?,
)

/** ONES 工作项的 IntelliJ [Task] 适配；成员映射决策见文件级 KDoc。 */
class OnesIssue(
    val data: OnesIssueData,
    private val serverUrl: String,
    private val teamID: String?,
) : Task() {

    override fun getId(): String = data.id.orEmpty()

    override fun getSummary(): String = data.title.orEmpty()

    override fun getDescription(): String? = null

    override fun getComments(): Array<Comment> = Comment.EMPTY_ARRAY

    override fun getIcon(): Icon = IconLoader.getIcon(ICON_PATH, javaClass)

    override fun getType(): TaskType = mapType(data.issueType?.name)

    override fun getUpdated(): Date? = data.serverUpdateStamp?.let(::microsToDate)

    override fun getCreated(): Date? = data.createTime?.let(::microsToDate)

    override fun isClosed(): Boolean = normalizedCategory() == CATEGORY_DONE

    override fun isIssue(): Boolean = true

    /**
     * 校准过的 ONES 网页深链（见文件级 KDoc）：teamID 缺失（null/blank）或
     * 工作项 id 缺失时返回 null——平台对 null 不渲染跳转，好过死链。
     */
    override fun getIssueUrl(): String? {
        val team = teamID?.trim()
        val issueId = data.id
        if (team.isNullOrEmpty() || issueId.isNullOrEmpty()) return null
        return serverUrl.trimEnd('/') + ISSUE_URL_TEAM_FRAGMENT + team + ISSUE_URL_TASK_SEGMENT + issueId
    }

    override fun getState(): TaskState = when (normalizedCategory()) {
        CATEGORY_IN_PROGRESS -> TaskState.IN_PROGRESS
        CATEGORY_DONE -> TaskState.RESOLVED
        else -> TaskState.OPEN
    }

    override fun getNumber(): String = data.number ?: getId()

    override fun getProject(): String? = data.project?.name

    override fun getPresentableId(): String = data.number ?: getId()

    private fun normalizedCategory(): String? =
        data.status?.category?.trim()?.lowercase()
            ?.replace('-', '_')?.replace(' ', '_')

    private fun mapType(typeName: String?): TaskType {
        val name = typeName?.lowercase() ?: return TaskType.OTHER
        return when {
            KEYWORD_BUG_ANY in name || KEYWORD_BUG_EN in name -> TaskType.BUG
            KEYWORD_FEATURE in name || KEYWORD_FEATURE_EN in name || KEYWORD_STORY_EN in name -> TaskType.FEATURE
            else -> TaskType.OTHER
        }
    }

    private companion object {
        const val ICON_PATH = "/icons/ones.svg"
        const val ISSUE_URL_TEAM_FRAGMENT = "/project/#/team/"
        const val ISSUE_URL_TASK_SEGMENT = "/task/"
        const val MICROS_PER_MILLI = 1_000L
        const val CATEGORY_DONE = "done"
        const val CATEGORY_IN_PROGRESS = "in_progress"
        const val KEYWORD_BUG_ANY = "缺陷"
        const val KEYWORD_BUG_EN = "bug"
        const val KEYWORD_FEATURE = "需求"
        const val KEYWORD_FEATURE_EN = "feature"
        const val KEYWORD_STORY_EN = "story"

        /** ONES 时间戳（epoch 微秒）→ java.util.Date（毫秒）。 */
        fun microsToDate(micros: Long): Date = Date(micros / MICROS_PER_MILLI)
    }
}

/**
 * 解析 "Get a list of issues" 响应：`{"result":..,"data":{"list":[..]}}`。
 * 空/缺失 list、list 内 null 元素或畸形 JSON → 空列表/剔除 null（见文件级
 * "解析失败策略"——解析层绝不向调用方抛异常）。
 * [teamID] 仅用于给每个条目构造网页深链（[OnesIssue.getIssueUrl]）。
 */
fun parseIssues(json: String, serverUrl: String, teamID: String?): List<OnesIssue> {
    val response = runCatching {
        GSON.fromJson(json, OnesIssueListResponse::class.java)
    }.getOrNull() ?: return emptyList()
    return response.data?.list.orEmpty().filterNotNull().map { OnesIssue(it, serverUrl, teamID) }
}

/**
 * 解析 "Get a issue details" 响应：`{"result":..,"data":{...}}`（工作项直接挂在
 * data 下，无 list 包装）。data 缺失或 JSON 畸形 → null。
 * [teamID] 仅用于构造网页深链（[OnesIssue.getIssueUrl]）。
 */
fun parseIssue(json: String, serverUrl: String, teamID: String?): OnesIssue? {
    val response = runCatching {
        GSON.fromJson(json, OnesIssueDetailResponse::class.java)
    }.getOrNull() ?: return null
    val data = response.data ?: return null
    return OnesIssue(data, serverUrl, teamID)
}

private val GSON: Gson = Gson()

private data class OnesIssueListResponse(val data: OnesIssueListData?)

private data class OnesIssueListData(val list: List<OnesIssueData>?)

private data class OnesIssueDetailResponse(val data: OnesIssueData?)
