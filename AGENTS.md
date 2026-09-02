# AGENTS.md

IntelliJ IDEA plugin **ONES Task** (`com.bangbang93.onesideatask`): integrates ONES issue tracking into IDEA's "Tasks & Contexts" as a task repository. **Phase 1 is read-only** — no writes to ONES work items. Target IDE: **IDEA 2026.2+** (`since-build=262`).

Source comments and user-facing messages are in **Chinese** — match that style when adding text.

## Build & commands

- **JDK 21** required. Gradle + `org.jetbrains.intellij.platform` 2.18.1 + Kotlin 2.4.10.
- `./gradlew test` — all unit tests (Kotest `FunSpec` + MockK + JUnit Platform). **Excludes the `qa.*` package** by default.
- `./gradlew buildPlugin` — produces `build/distributions/ones-idea-task-0.1.0.zip` (this is the distributable; verifyPlugin Compatible is part of it).
- Coverage (Kover, measurement only, **no gate/threshold**): `./gradlew koverXmlReport` / `koverHtmlReport`.
- `./gradlew runIde` — plain sandbox. ⚠️ **Has NO Tasks UI** (see the 2026.2 quirk below) — not useful for exercising this plugin's UI.

## The 2026.2 Tasks-UI quirk (do not rediscover)

IDEA 2026.2 **no longer bundles the Tasks UI** (Settings→Tools→Tasks, Open Task dialog, commit integration). It lives in the Marketplace plugin `com.intellij.tasks`. The plugin's own Settings page / Open Task dialog / commit format all render there. Consequences:

- The plugin declares `<depends>com.intellij.modules.tasks</depends>` and the build pulls the API via `bundledModule("intellij.platform.tasks")`.
- To exercise the real UI in a sandbox you MUST use `./gradlew runIdeForUiTests` — it adds `plugin("com.intellij.tasks", "262.8665.173")` + robot-server on port 18322, and opens `/tmp/opencode/f3-scratch`.
- For manual QA, that task's sandbox is the one to use (the user did the F3 acceptance in it).

## F3 QA harness (Remote Robot) — how it actually runs

The harness `src/test/kotlin/.../qa/F3RobotQaSpec.kt` drives a **live** sandbox IDE via Remote Robot. It is NOT run through `test` (excluded) and NOT through Gradle while the IDE is up — `runIdeForUiTests` never exits, so it holds the Gradle project lock (a second `./gradlew` would deadlock). Documented flow (see build.gradle.kts):

```
./gradlew testClasses writeQaRuntimeClasspath        # before starting the IDE
./gradlew runIdeForUiTests &                         # IDE + robot server on 127.0.0.1:18322
java --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.desktop/java.awt=ALL-UNNAMED \
     -cp "$(cat .omo/evidence/f3/qa-runtime-classpath.txt)" \
     org.junit.platform.console.ConsoleLauncher \
     --select-class com.bangbang93.onesideatask.qa.F3RobotQaSpec --details=tree
```

The `--add-opens` flags are required (Gson reflection in the robot client on JDK 21+). On a locked Wayland screen the robot drives the real Swing components via `doClick()`/`setText()`/EDT JS (see the spec's header comment) — it cannot use X input injection.

## Architecture (src/main/kotlin/com/bangbang93/onesideatask/)

- `OnesTaskRepositoryType` — the `com.intellij.tasks.repositoryType` extension point impl (registered in plugin.xml). `getName() = "ONES"`.
- `OnesTaskRepository` — the `TaskRepository` impl: persistence, `getIssues`/`findTask`, `testConnection`.
- `OnesTaskRepositoryEditor` — settings panel (URL / teamID / API key + 测试连接 button).
- `client/OnesClient` — stateless HTTP client for ONES Open API v2 (via platform `HttpRequests`, so IDE proxy settings apply automatically).
- `model/OnesIssue` — Gson models + `Task` adapter + `parseIssues`/`parseIssue` entry points.

## Non-obvious implementation facts (hard-won, respect them)

- **Persistence contract**: `@Tag("ONES")` on `OnesTaskRepository` MUST equal `OnesTaskRepositoryType.getName()` — `TaskManagerImpl` serializes repositories with `XmlSerializer` and looks children up by type name. Wrong tag = config silently lost on restart.
- **API key handling (security)**: `apiKey` is `@Transient` — never serialized, never in `toString()`, never in exception messages/logs. It lives in the IDE `PasswordSafe` via the `OnesCredentialsStore` object, keyed by service name `ONES Task API key <url>/<teamID>`. `equals`/`hashCode` deliberately do NOT read PasswordSafe (restart-resilient). Editor persists it **on focus-lost, not per keystroke**; empty field = keep stored key (never echoed back).
- **Editor contract**: the host configurable **never calls `editor.apply()`** (2026.2, verified). The editor writes back to the repository via document listeners + `changeListener` — this is why the input fields are wired that way. Replicate `BaseRepositoryEditor`'s re-entry guard (`applying` flag).
- **`getIssues` pagination**: fetches a single page clamped to 50–100 (`maxOf(limit,50).coerceAtMost(100)`), applies `withClosed` (drops `done` category) and query filtering locally, then `drop(offset)`. No page-2 fetch in phase 1.
- **Error mapping in `OnesClient`** (single shared GET pipeline): 401→"API key 无效或已过期", 403→permission error, 404→`FileNotFoundException`, HTTP 200 + `{"result":"FAIL"}` envelope→throws the API's `errorMsg`. Unconfigured repository → `getIssues` returns empty / `findTask` returns null (not an error — the framework probes eagerly).
- **`HttpRequests`**: MUST call `.throwStatusCodeException(false)` then read `responseCode` manually — 2026.2 platform throws `HttpStatusException` on non-2xx GET by default; disabling it is the supported way to map statuses yourself (verified via javap).
- **ONES timestamps** are **epoch microseconds** (16-digit, e.g. 1788315974446000) — divide by 1000 for `java.util.Date`. `number` is modeled as `String` (safe for non-numeric values; it feeds `{id}` in commit format via `getPresentableId`).
- **Web deep link**: `getIssueUrl` = `{serverUrl}/{project/#/team/{teamID}/task/{id}}` — this is the REAL calibrated ONES route (the initial `/project/issue/{id}` guess was a dead link). Returns null if teamID/id missing.
- `status.category` values: `to_do`/`in_progress`/`done` (lowercase) → `TaskState.OPEN/IN_PROGRESS/RESOLVED`; `done` → `isClosed` true. Normalized (lowercase, `-`/space→`_`) before matching.

## Testing conventions

- Unit specs are **Application-independent**: avoid the IntelliJ Application; never touch real PasswordSafe — set `repository.apiKey` in-memory directly (bypasses the lazy store lookup).
- `client/OnesClientSpec` uses a **JDK `HttpServer` mock on an ephemeral port** (port 0) capturing the `Authorization` header + request path/query — this pins the Bearer-auth model without a real server.
- `OnesTaskRepositoryEndToEndSpec` runs the real repository + real client + real parsing against a local path-routing `HttpServer` with calibrated ONES fixture envelopes.
- **mockkObject pattern**: `beforeTest { mockkObject(X) }`, `afterTest { unmockkObject(X) }`; unstubbed members hit the real implementation, so every test stubs what it exercises.
- Kover uses **JaCoCo engine** (`useJacoco()`) — the default IntelliJ agent fails with "class redefinition failed" when `mockkObject`'s inline retransformation runs. Do not switch back.
- Install/user docs: `docs/install.md` (Chinese; covers configuration, usage, key cleanup).

## Git

- Remote: `git@github.com:bangbang93/ones-idea-task.git`, branch `master`. Push with SSH (gh's HTTPS URL prompts for a username and fails).
- `.omo/` (orchestration/plan/evidence metadata) is **untracked and intentionally not committed**.
- Source/KDoc contains detailed "task-N evidence" notes referencing past work — read them before changing behavior; they document why the code is the way it is.
