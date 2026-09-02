# ONES Task 插件 — 安装与使用说明（内网分发）

把 ONES 项目管理平台的工作项接入 IntelliJ IDEA 的 "Tasks & Contexts"：在 IDE 里
列出/搜索 ONES 工作项、打开任务自动关联 changelist、提交信息自动带任务编号、一键
跳转 ONES 网页。一期**只读**。

- 插件 ID：`com.bangbang93.onesideatask`（名称 "ONES Task"，版本 0.1.0）
- 适用 IDE：IntelliJ IDEA **2026.2 及以上**（`since-build=262`；未设 `until-build`，
  支持未来版本）
- 安装包：`build/distributions/ones-idea-task-0.1.0.zip`
- 依赖：仅平台模块（`com.intellij.modules.platform` + `com.intellij.modules.tasks`），
  无第三方运行时依赖

---

## 1. 安装

### 方式一：从磁盘安装 zip（默认）

1. 拿到内网分发的 `ones-idea-task-0.1.0.zip`（**不要解压**）。
2. IDE 中打开 `Settings/Preferences → Plugins → ⚙（齿轮）→ Install Plugin from Disk...`。
3. 选中 zip → OK → 按提示重启 IDE。

### 方式二：内网自定义插件仓库（可选）

若团队搭建了自定义插件仓库（custom plugin repository），可在
`Settings → Plugins → ⚙ → Manage Plugin Repositories...` 添加仓库 URL，之后在
Plugins 市场页搜索 "ONES Task" 安装并跟随更新。

> 本插件**不发布**到 JetBrains Marketplace，仅内网分发。

---

## 2. 配置（三步）

### 第一步：获取 ONES 个人 API key

1. 登录 ONES 网页端 → 右上角头像 → **个人设置** → **API key**（个人开放 API 密钥）。
2. 创建并复制 key（只显示一次，请立即保存）。
3. 前提：需 ONES 管理员为你**开通 Open API 权限**，且 key 具备
   `read:project:issue` scope（读取工作项）；本插件的"测试连接"还需能访问
   `GET /openapi/v2/account/teams`（读取团队列表）。

### 第二步：查团队 ID

团队 ID 是 UUID，可用 API key 直接查询：

```bash
curl -H "Authorization: Bearer <你的API key>" https://ones.cn/openapi/v2/account/teams
```

返回 JSON 中每项的 `id` 字段即团队 ID（`name` 用于辨认是哪个团队）。
私有部署把域名换成你的服务器地址即可。

### 第三步：在 IDE 中配置

1. `Settings → Tools → Tasks → Servers` → 点 `+` → 选择 **ONES**。
2. 三个字段：
   - **服务器 URL**：如 `https://ones.cn`（新建时会预填 SaaS 地址，私有部署自行修改）
   - **团队 ID**：第二步查到的 UUID
   - **API key**：粘贴个人 key
3. 点 **测试连接** —— 成功会弹"连接成功"对话框；失败会显示具体原因
   （401 = key 无效或已过期；403 = scope/业务权限不足）。
4. OK 保存。

**API key 的行为细节：**

- key 保存在 IDE 的 PasswordSafe（即操作系统钥匙串：macOS Keychain / Windows
  Credential Manager / Linux Secret Service），**不写入任何配置文件、不上传、不落盘明文**。
- 密码框**永不回显**已保存的 key：重新打开配置页时留空即表示继续使用已保存的 key；
  只有输入了新值才会覆盖。
- key 在**焦点离开密码框时**保存（不是每敲一个键就存）。

---

## 3. 日常使用

- **列出/搜索工作项**：`Tools → Tasks & Contexts → Open Task...`（或快捷键
  `Alt+Shift+T`），弹出本团队工作项列表；输入关键字按标题过滤。
- **打开任务**：选中后回车，IDE 自动创建对应的 changelist 并切换上下文——
  之后在该上下文里的改动都归属这个任务。
- **提交信息**：提交信息格式默认为 `{number} {标题}`（如 `123 修复登录超时`），
  在提交面板会自动预填；可在 `Settings → Tools → Tasks → Servers → 编辑仓库`
  里调整"Commit Message Format"。
- **跳转 ONES**：打开的任务面板里点任务链接，直接打开该工作项的 ONES 网页
  （`{服务器}/project/#/team/{团队ID}/task/{工作项ID}`）。
- **切换/关闭任务**：`Tools → Tasks & Contexts` 菜单下切换或清除当前任务上下文。

---

## 4. 已知限制（一期）

| 限制 | 说明 |
| --- | --- |
| 只读 | 不能在 IDE 里创建工作项、修改状态/字段（写操作属二期规划） |
| 仅新版 Open API | 只对接个人 API key + Bearer 认证的 Open API v2；**不支持**旧私有部署的 `Ones-Auth-Token`/`Ones-User-Id` 头认证 |
| 列表只取第一页 | Open Task 列表拉取单页（50–100 条）；更早的工作项请用 ONES 网页查看 |
| 目标 IDE 2026.2+ | `since-build=262`，不支持更老版本 |
| 时间线 | 列表任务不显示"更新时间"（列表接口不返回该字段），打开详情后可见 |

---

## 5. 卸载与密钥清理

- **卸载插件**：`Settings → Plugins → ONES Task → 卸载`（Uninstall），重启 IDE。
- **删除服务器配置**：`Settings → Tools → Tasks → Servers → 选中 ONES → 删除（-）`。
  注意：删除配置**不会**自动清理已存的 API key（凭据按"服务器 + 团队"维度独立保存，
  以便重配时免重输）。
- **彻底清理密钥**：在系统钥匙串管理器中删除以
  **`ONES Task API key`** 为前缀的条目
  （完整 service name 形如 `ONES Task API key https://ones.cn/<团队ID>`）：
  - macOS：Keychain Access → 搜索 "ONES Task API key"
  - Windows：凭据管理器 → Windows 凭据
  - Linux：Secret Service / gnome-keyring（seahorse）
- 也可以先吊销 ONES 端的 API key（个人设置 → API key → 删除），使残留凭据立即失效。
