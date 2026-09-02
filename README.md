# ONES Task

把 ONES 项目管理平台的工作项接入 IntelliJ IDEA 的 **Tasks & Contexts** 的插件。

在 IDE 里列出/搜索 ONES 工作项、打开任务自动关联 changelist、提交信息自动带任务编号、
一键跳转 ONES 网页。**一期只读**（不在 IDE 里创建/修改工作项，写操作属二期规划）。

- 插件 ID：`com.bangbang93.onesideatask`
- 适用 IDE：IntelliJ IDEA **2026.2+**（`since-build=262`）
- 语言：Kotlin / JDK 21 / IntelliJ Platform SDK
- 文档：安装与使用详见 [docs/install.md](docs/install.md)

## 功能

- **配置**：`Settings → Tools → Tasks → Servers` 添加 ONES 仓库（服务器 URL / 团队 ID / API key），
  内置「测试连接」校验。
- **列出/搜索**：`Tools → Tasks & Contexts → Open Task`（`Alt+Shift+T`）拉取团队工作项，
  按标题过滤。
- **打开任务**：选中即创建对应 changelist 并切换上下文，之后改动归属该任务。
- **提交信息**：提交面板自动预填 `{编号} {标题}`（默认格式，可改）。
- **跳转 ONES**：打开的任务一键跳转对应工作项网页。

## 安装

1. 构建产物：`build/distributions/ones-idea-task-0.1.0.zip`
2. `Settings → Plugins → ⚙ → Install Plugin from Disk...` 选中 zip → 重启 IDE
3. 按 [docs/install.md](docs/install.md) 完成三步配置（API key / 团队 ID / 服务器）

> 本插件**不发布**到 JetBrains Marketplace，仅内网分发。详细安装/配置/卸载与密钥清理
> 见 [docs/install.md](docs/install.md)。

## 开发

```bash
./gradlew test                # 全部单元测试（Kotest + MockK，排除 qa.* 包）
./gradlew buildPlugin         # 打包 → build/distributions/ones-idea-task-0.1.0.zip
./gradlew runIdeForUiTests    # 起沙盒 IDE（带 Task Management 插件，可手动 QA 配置/列表/提交流程）
./gradlew verifyPlugin        # 二进制兼容性校验
./gradlew koverHtmlReport     # 覆盖率报告（JaCoCo 引擎）
```

架构与关键实现约定见 [AGENTS.md](AGENTS.md)（持久化契约、API key 安全模型、2026.2 平台怪癖等）。

## 已知限制（一期）

| 限制 | 说明 |
| --- | --- |
| 只读 | 不能在 IDE 里创建工作项、修改状态/字段（写操作属二期） |
| 仅新版 Open API | 只对接个人 API key + Bearer 认证的 Open API v2；不支持旧私有部署的 `Ones-Auth-Token`/`Ones-User-Id` 头认证 |
| 列表只取第一页 | Open Task 列表拉取单页（50–100 条），更早的用 ONES 网页查看 |
| 目标 IDE 2026.2+ | 不支持更老版本 |

## 许可证

内部项目，未发布到公共渠道。
