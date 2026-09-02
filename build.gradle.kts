plugins {
    id("org.jetbrains.intellij.platform") version "2.18.1"
    kotlin("jvm") version "2.4.10"
    // Build-tool plugin ONLY (task 10, measurement — no coverage gate): instruments the
    // `test` JVM and aggregates line-coverage reports. NOT a runtime/test dependency —
    // it never enters any `dependencies {}` block, so the plugin artifact is unchanged
    // (zip content list verified byte-identical in task-10 evidence).
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

group = "com.bangbang93"
version = "0.1.0"

repositories {
    mavenCentral()
    // TEST-SCOPE ONLY (F3 QA tooling, like Kover): hosts the Remote Robot client jars
    // (com.intellij.remoterobot) and the robot-server plugin zip. Never enters the
    // production artifact — verify evidence checks buildPlugin zip stays unchanged.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 2025.3+: IDEA Community (IC) is no longer published separately — unified intellijIdea() is the 2026.2 artifact
        intellijIdea("2026.2")
        // 2026.2: com.intellij.tasks is no longer a bundled plugin — the Tasks API lives in the
        // extracted platform module intellij.platform.tasks (productModuleV2, always loaded at runtime).
        // plugin.xml runtime dependency for todo 2: <depends>com.intellij.modules.tasks</depends> (pluginAlias)
        bundledModule("intellij.platform.tasks")
        // 2.18.1: instrumentationTools() removed — javaCompiler() is its equivalent (applied by default)
        javaCompiler()
        pluginVerifier()
    }

    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.mockk:mockk:1.14.11")

    // F3 QA harness ONLY (test scope): Remote Robot client + fixtures, mirrors the official
    // JetBrains UI-test tooling (github.com/JetBrains/intellij-ui-test-robot). These drive a
    // sandbox IDE through the in-process robot server; they never reach the production artifact.
    testImplementation("com.intellij.remoterobot:remote-robot:0.11.23")
    testImplementation("com.intellij.remoterobot:remote-fixtures:0.11.23")
    // F3 QA harness ONLY: standalone JUnit Platform console launcher (shaded jar). The QA spec
    // must run OUTSIDE Gradle while `runIdeForUiTests` is still running — Gradle serializes
    // builds on the same project directory, so a second `./gradlew f3RobotQa` would deadlock
    // on the project lock held by the never-finishing runIde task. The launcher lets us run
    // the spec via plain `java -cp <test runtime classpath>` (see writeQaRuntimeClasspath).
    testRuntimeOnly("org.junit.platform:junit-platform-console-standalone:1.13.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // F3 QA harness (com.bangbang93.onesideatask.qa.*) is driven ONLY by the dedicated
    // f3RobotQa task against a live runIdeForUiTests sandbox — never as part of `test`.
    filter {
        excludeTestsMatching("com.bangbang93.onesideatask.qa.*")
    }
}

// F3 real-QA launcher: `./gradlew runIdeForUiTests` boots the sandbox IDE with the
// robot-server plugin (Remote Robot, port 18322) + the "Task Management" plugin
// (Marketplace com.intellij.tasks — 2026.2 no longer bundles the Tasks UI; our plugin's
// Settings page / Open Task dialog / commit integration all live there). Dialog-suppressing
// flags are the ones proven in the prior F3 attempt.
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgs(
                    "-Drobot-server.port=18322",
                    "-Didea.trust.all.projects=true",
                    "-Dide.experimental.ui.onboarding=false",
                    "-Dexperimental.ui.onboarding.on.first.startup=false",
                    "-Dide.show.tips.on.startup.default.value=false",
                    "-Djb.consents.confirmation.enabled=false",
                    "-Djb.privacy.policy.text=<!--999.999-->",
                )
                // Open the scratch project directly (skip the Welcome screen).
                args("/tmp/opencode/f3-scratch")
            }
            plugins {
                robotServerPlugin("0.11.23")
                plugin("com.intellij.tasks", "262.8665.173")
            }
        }
    }
}

// F3 QA client runner: connects RemoteRobot to the already-running runIdeForUiTests IDE.
// NOT invoked via Gradle while the IDE is up — Gradle serializes builds on the same project
// dir, and `runIdeForUiTests` holds that lock until the IDE exits. Actual invocation:
//   ./gradlew testClasses writeQaRuntimeClasspath     # before starting the IDE
//   ./gradlew runIdeForUiTests &                      # IDE + robot server on 127.0.0.1:18322
//   java --add-opens java.base/java.lang=ALL-UNNAMED \
//        --add-opens java.desktop/java.awt=ALL-UNNAMED \
//        -cp "$(cat /tmp/opencode/f3-robot/qa-cp.txt)" \
//        org.junit.platform.console.ConsoleLauncher \
//        --select-class com.bangbang93.onesideatask.qa.F3RobotQaSpec --details=tree
// The f3RobotQa task below is kept as the Gradle-native equivalent for environments where
// the IDE is launched outside Gradle (e.g. manually replicated java command line).
val f3RobotQa by tasks.registering(Test::class) {
    group = "verification"
    description = "F3 QA harness: drives the live runIdeForUiTests sandbox via Remote Robot (IDE + mock ONES server must be running)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    // Remote-robot client deserializes server responses (incl. Throwable payloads) with Gson
    // reflection, which needs these opens on JDK 21+ (InaccessibleObjectException otherwise).
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
    )
    filter {
        includeTestsMatching("com.bangbang93.onesideatask.qa.*")
    }
    maxHeapSize = "2g"
}

// Dumps the test runtime classpath (incl. remote-robot client + console launcher) so the QA
// spec can be launched with plain `java` while `runIdeForUiTests` holds the Gradle project lock.
tasks.register("writeQaRuntimeClasspath") {
    val cp = sourceSets["test"].runtimeClasspath
    val outFile = rootProject.layout.projectDirectory.file(".omo/evidence/f3/qa-runtime-classpath.txt").asFile
    doLast {
        outFile.parentFile.mkdirs()
        outFile.writeText(cp.joinToString(":") { it.absolutePath })
        println("QA runtime classpath (${cp.files.size} entries) -> ${outFile.absolutePath}")
    }
}

// Measurement only — deliberately no kover verify rule / minimum threshold (task 10 decision).
kover {
    // JaCoCo engine instead of Kover's default (IntelliJ) agent: mockkObject()'s inline
    // retransformation (mock + unmock restore) trips the IntelliJ agent with
    // "class redefinition failed: attempted to change the schema (add/remove fields)".
    // JaCoCo's ClassFileTransformer re-instruments classes on RETRANSFORM events, so the
    // coverage fields survive MockK's transform/cancel cycle.
    useJacoco()
    reports {
        filters {
            // No excludes: every production class is hand-written plugin logic;
            // there is no generated or dead code to mask the numbers.
        }
    }
}
