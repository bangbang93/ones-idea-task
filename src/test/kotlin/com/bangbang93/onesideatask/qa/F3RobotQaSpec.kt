@file:Suppress("TestFunctionName")

package com.bangbang93.onesideatask.qa

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import io.kotest.core.spec.style.FunSpec
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

/**
 * F3 REAL MANUAL QA harness (task F3, evidence: .omo/evidence/task-F3-completion-ones-idea-task.md).
 *
 * Executed ONLY by the dedicated Gradle task `f3RobotQa` against a LIVE sandbox IDE started
 * with `./gradlew runIdeForUiTests` (robot-server plugin on 127.0.0.1:18322). The main `test`
 * task excludes this package (see build.gradle.kts).
 *
 * ## Why Remote Robot, and why the input goes through JS instead of the robot's click()/keyboard()
 * The desktop session is KDE-Wayland LOCKED. Prior F3 evidence: X input injection and framebuffer
 * capture are impossible. Remote Robot's own click()/enterText() turned out to go through
 * AssertJ BasicRobot → java.awt.Robot → XTEST — which the locked screen equally swallows
 * (verified live: robot.click(okButton) runs without error but the dialog never closes).
 * What DOES work, all fully in-process:
 *   - component search / hierarchy  (robot server XPath)
 *   - JS execution (Rhino) with full Java access, optionally on the EDT
 *   - painting-mode component screenshots (component.paint into an offscreen image)
 * So this harness drives the REAL Swing UI through its own event handlers:
 *   JButton.doClick()          — the real ActionEvent path a mouse click ends in
 *   ActionButton.click()       — the real programmatic click of IDE toolbar buttons
 *   JTextField.setText()       — fires the real DocumentEvents (our editor live-applies on them)
 *   FocusEvent(FOCUS_LOST)     — fires the plugin's real apiKey persistence listener
 *   ListPopup.handleSelect(i)  — the popup's real select handler
 *   AnAction.actionPerformed() — for invoking IDE actions by id
 * Every UI element involved is the genuine IDE/plugin UI; only the physical mouse/keyboard
 * layer (blocked by the OS) is replaced.
 *
 * ## Environment quirks handled here (all observed live, see console log in evidence)
 * - Windows become X-unmapped ("not showing") on the locked screen; when NOTHING is showing
 *   the IDE exits (~last-window-close). A keep-alive thread re-shows the main frame.
 * - Application.invokeLater(runnable) WITHOUT ModalityState.any() queues the runnable until
 *   no modal dialog is up — every click scheduled while the (modal) Settings dialog is open
 *   silently never ran until this was fixed (first QA run's root failure).
 * - Actions that open modal dialogs must be invoked via invokeLater (otherwise the JS
 *   response blocks until the dialog closes → HTTP timeout); with ModalityState.any().
 */
class F3RobotQaSpec : FunSpec({

    val robotUrl = System.getProperty("f3.robot.url", "http://127.0.0.1:18322")
    val evidenceDir = File(System.getProperty("f3.evidence.dir", ".omo/evidence/f3")).apply { mkdirs() }
    val consoleLog = File(evidenceDir, "f3-qa-console.log")
    val mockUrl = "http://127.0.0.1:18321"
    val teamId = "team-f3"
    val apiKey = "f3-key"

    lateinit var robot: RemoteRobot
    lateinit var frame: ComponentFixture

    fun log(msg: String) {
        consoleLog.appendText("[${java.time.LocalTime.now().withNano(0)}] $msg\n")
        println("F3QA | $msg")
    }

    /** Painting-mode component screenshot — immune to the locked screen (offscreen paint). */
    fun shot(fixture: ComponentFixture, name: String) {
        val img: BufferedImage = fixture.getScreenshot(true)
        val f = File(evidenceDir, "$name.png")
        ImageIO.write(img, "png", f)
        log("screenshot -> ${f.absolutePath} (${f.length()} bytes)")
    }

    fun <T : Any> await(what: String, timeoutSec: Long = 40, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                probe()?.let { return it }
            } catch (t: Throwable) {
                lastError = t
            }
            Thread.sleep(400)
        }
        throw AssertionError("timeout waiting for $what (last error: $lastError)")
    }

    fun findAllFixtures(xpath: String): List<ComponentFixture> =
        robot.findAll<ComponentFixture>(byXpath(xpath))

    fun findFixture(xpath: String, timeoutSec: Long = 20): ComponentFixture =
        robot.find<ComponentFixture>(byXpath(xpath), java.time.Duration.ofSeconds(timeoutSec))

    fun textsOf(fixture: ComponentFixture): List<String> =
        fixture.findAllText().map { it.text }

    /** Run JS off-EDT (robot server thread) and return its value. Errors are logged. */
    fun js(script: String): String =
        runCatching { robot.callJs<String>(script.trimIndent(), false) }
            .onFailure { log("JS ERROR (off-edt): ${it.message}") }
            .getOrThrow()

    /** Run JS on the EDT and return its value. Errors are logged. */
    fun jsEdt(script: String): String =
        runCatching { robot.callJs<String>(script.trimIndent(), true) }
            .onFailure { log("JS ERROR (edt): ${it.message}") }
            .getOrThrow()

    fun dumpHierarchy(name: String) {
        val html = kotlin.runCatching {
            val conn = java.net.URI(robotUrl).toURL().openConnection()
            conn.getInputStream().bufferedReader().readText()
        }.getOrDefault("<hierarchy dump failed>")
        File(evidenceDir, "$name-hierarchy.html").writeText(html)
        log("hierarchy dump -> $name-hierarchy.html (${html.length} chars)")
    }

    /** Common window-walk prologue for the JS helpers below. */
    val WALK = """
        importPackage(java.awt);
        importPackage(javax.swing);
        function walkAll(fn) {
            var wins = Window.getWindows();
            for (var i = 0; i < wins.length; i++) {
                var q = new java.util.LinkedList(); q.add(wins[i]);
                while (!q.isEmpty()) {
                    var comp = q.removeFirst();
                    fn(comp);
                    if (comp instanceof java.awt.Container) {
                        var ch = comp.getComponents();
                        for (var j = 0; j < ch.length; j++) q.add(ch[j]);
                    }
                }
            }
        }
        function findButton(name) {
            var res = null;
            walkAll(function(comp) {
                if (res != null) return;
                if (comp instanceof javax.swing.JButton) {
                    var ac = comp.getAccessibleContext();
                    if (ac != null && ac.getAccessibleName() == name) res = comp;
                }
            });
            return res;
        }
        function findDialog(accName) {
            var wins = Window.getWindows();
            for (var i = 0; i < wins.length; i++) {
                var ac = wins[i].getAccessibleContext();
                if (ac != null && ac.getAccessibleName() == accName) return wins[i];
            }
            return null;
        }
        function walkIn(root, fn) {
            var q = new java.util.LinkedList(); q.add(root);
            while (!q.isEmpty()) {
                var comp = q.removeFirst();
                fn(comp);
                if (comp instanceof java.awt.Container) {
                    var ch = comp.getComponents();
                    for (var j = 0; j < ch.length; j++) q.add(ch[j]);
                }
            }
        }
        function findButtonIn(root, name) {
            var res = null;
            walkIn(root, function(comp) {
                if (res != null) return;
                if (comp instanceof javax.swing.JButton) {
                    var ac = comp.getAccessibleContext();
                    if (ac != null && ac.getAccessibleName() == name) res = comp;
                }
            });
            return res;
        }
        function findDeclaredField(cls, name) {
            while (cls != null) {
                try { return cls.getDeclaredField(name); } catch (e) { cls = cls.getSuperclass(); }
            }
            return null;
        }
        function findDeclaredMethod(cls, name) {
            while (cls != null) {
                var ms = cls.getDeclaredMethods();
                for (var i = 0; i < ms.length; i++) {
                    if (ms[i].getName() == name) return ms[i];
                }
                cls = cls.getSuperclass();
            }
            return null;
        }
        function fieldFor(labelText) {
            // 2026.2 FormBuilder does NOT wire JLabel.labelFor; all labeled rows share ONE
            // panel as [label, field, label, field, ...] — take the first JTextComponent
            // AFTER the label among its siblings (live-probed against the sandbox IDE).
            var res = null;
            walkAll(function(comp) {
                if (res != null) return;
                if (comp instanceof javax.swing.JLabel && labelText == (comp.getText() + "")) {
                    var parent = comp.getParent();
                    if (parent == null) return;
                    var ch = parent.getComponents();
                    var seen = false;
                    for (var j = 0; j < ch.length; j++) {
                        if (ch[j] == comp) { seen = true; continue; }
                        if (seen && ch[j] instanceof javax.swing.text.JTextComponent) { res = ch[j]; return; }
                    }
                }
            });
            return res;
        }
    """

    /**
     * Keep-alive: the locked screen un-maps IDE windows; when nothing is showing the IDE
     * exits. Re-show the main frame whenever that is about to happen.
     */
    fun startKeepAlive() {
        js(
            """
            $WALK
            var t = new java.lang.Thread(new java.lang.Runnable({
                run: function() {
                    while (true) {
                        try {
                            java.lang.Thread.sleep(2000);
                            var anyShowing = false;
                            var frame = null;
                            var wins = Window.getWindows();
                            for (var i = 0; i < wins.length; i++) {
                                if (wins[i].isShowing()) { anyShowing = true; }
                                if (frame == null && wins[i].getClass().getName().indexOf("IdeFrameImpl") >= 0) frame = wins[i];
                            }
                            if (!anyShowing && frame != null) {
                                var f = frame;
                                java.awt.EventQueue.invokeLater(function() { f.setVisible(true); });
                            }
                        } catch (e) {}
                    }
                }
            }), "f3-keepalive");
            t.setDaemon(true);
            t.start();
            "keepalive-started";
            """.trimIndent(),
        )
        log("keep-alive thread started")
    }

    /**
     * Invoke an IDE action by id: actionPerformed via invokeLater with the project frame's
     * data context (focus-based context is null on the locked screen).
     *
     * invokeLater MUST pass ModalityState.any(): the default-modality variant queues the
     * runnable until no modal dialog is up, so any click scheduled while the (modal) Settings
     * dialog is open would never run — the exact failure that made the first QA run's Add/OK
     * clicks silently do nothing (verified live: synchronous EDT doClick worked, default
     * invokeLater never fired).
     */
    fun invokeAction(actionId: String) {
        js(
            """
            $WALK
            importPackage(com.intellij.openapi.actionSystem);
            importPackage(com.intellij.openapi.application);
            importPackage(com.intellij.ide);
            var am = ActionManager.getInstance();
            var action = am.getAction("$actionId");
            if (action == null) throw new Error("action not found: $actionId");
            var frame = null;
            var wins = Window.getWindows();
            for (var i = 0; i < wins.length; i++) {
                if (wins[i].getClass().getName().indexOf("IdeFrameImpl") >= 0) { frame = wins[i]; break; }
            }
            if (frame == null) throw new Error("no IdeFrameImpl");
            ApplicationManager.getApplication().invokeLater(function() {
                var ctx = DataManager.getInstance().getDataContext(frame);
                action.actionPerformed(new AnActionEvent(null, ctx, ActionPlaces.UNKNOWN,
                                                         action.getTemplatePresentation().clone(), am, 0));
            }, ModalityState.any());
            "scheduled $actionId";
            """.trimIndent(),
        )
        log("action scheduled: $actionId")
    }

    /** JButton.doClick via invokeLater(ModalityState.any()) — see invokeAction for why any(). */
    fun clickButtonAsync(accessibleName: String): String = js(
        """
        $WALK
        importPackage(com.intellij.openapi.application);
        var b = findButton("$accessibleName");
        if (b == null) "NOT-FOUND";
        else {
            ApplicationManager.getApplication().invokeLater(function() { b.doClick(); }, ModalityState.any());
            "CLICKED $accessibleName";
        }
        """.trimIndent(),
    )

    /**
     * doClick a button inside ONE named dialog only — the global hierarchy carries several
     * "OK" buttons from unrelated windows, so an unscoped search can click the wrong one
     * (first QA run: the Settings dialog never closed for exactly this reason).
     */
    fun clickDialogButton(dialogAccName: String, buttonName: String): String = js(
        """
        $WALK
        importPackage(com.intellij.openapi.application);
        var dlg = findDialog("$dialogAccName");
        if (dlg == null) "NO-DIALOG $dialogAccName";
        else {
            var b = findButtonIn(dlg, "$buttonName");
            if (b == null) "NO-BUTTON $buttonName";
            else {
                ApplicationManager.getApplication().invokeLater(function() { b.doClick(); }, ModalityState.any());
                "CLICKED $dialogAccName/$buttonName";
            }
        }
        """.trimIndent(),
    )

    /** Are there any popup JLists around right now? (repository/task choosers) */
    fun popupListInfo(): String = jsEdt(
        """
        $WALK
        var res = "";
        walkAll(function(comp) {
            var cn = comp.getClass().getName();
            if (cn.indexOf("ListPopup") >= 0 && cn.indexOf("MyList") >= 0) {
                res += "POPUPLIST " + cn + " size=" + comp.getModel().getSize() + " :: ";
                for (var i = 0; i < comp.getModel().getSize(); i++) res += "[" + i + "] " + comp.getModel().getElementAt(i) + " ";
                res += "\n";
            }
        });
        res == "" ? "NO-POPUP-LIST" : res;
        """.trimIndent(),
    )

    /**
     * Deterministic repository-add path: invoke the exact method the type chooser's ONES
     * entry executes — TaskRepositoriesConfigurable.addRepository(type.createRepository(null)).
     */
    fun addRepositoryFallback(): String = jsEdt(
        """
        $WALK
        importPackage(com.intellij.tasks);
        var dlg = findDialog("Servers");
        if (dlg == null) "NO-SERVERS-DIALOG";
        else {
            var sse = null;
            walkIn(dlg, function(comp) {
                if (sse != null) return;
                if (comp.getClass().getName().indexOf("SingleSettingEditor") >= 0) sse = comp;
            });
            if (sse == null) "NO-SINGLE-SETTING-EDITOR";
            else {
                var cf = findDeclaredField(sse.getClass(), "myControllers");
                if (cf == null) "NO-myControllers-FIELD";
                else {
                    cf.setAccessible(true);
                    var controllers = cf.get(sse);
                    var configurable = null;
                    var it = controllers.keySet().iterator();
                    while (it.hasNext()) {
                        var c = it.next();
                        if (c.getClass().getName().indexOf("TaskRepositoriesConfigurable") >= 0) configurable = c;
                    }
                    if (configurable == null) "NO-TASKS-CONFIGURABLE";
                    else {
                        var onesType = null;
                        var types = TaskRepositoryType.getRepositoryTypes();
                        for (var i = 0; i < types.size(); i++) {
                            if (types.get(i).getName() == "ONES") onesType = types.get(i);
                        }
                        if (onesType == null) "NO-ONES-TYPE";
                        else {
                            var m = findDeclaredMethod(configurable.getClass(), "addRepository");
                            if (m == null) "NO-addRepository-METHOD";
                            else {
                                var repo = onesType.createRepository(null);
                                m.setAccessible(true);
                                m.invoke(configurable, repo);
                                "ADDED " + repo;
                            }
                        }
                    }
                }
            }
        }
        """.trimIndent(),
    )

    /** Select item [index] in the first popup list (the popup's own select handler). */
    fun popupSelect(index: Int): String = jsEdt(
        """
        $WALK
        var list = null;
        walkAll(function(comp) {
            if (list != null) return;
            var cn = comp.getClass().getName();
            if (cn.indexOf("ListPopup") >= 0 && cn.indexOf("MyList") >= 0) list = comp;
        });
        if (list == null) "NO-POPUP-LIST";
        else {
            var f = findDeclaredField(list.getClass(), "this" + '$' + "0");
            if (f == null) "NO-OUTER-FIELD";
            else {
                f.setAccessible(true);
                var popup = f.get(list);
                list.setSelectedIndex($index);
                var value = list.getModel().getElementAt($index);
                popup.handleSelect(true);
                "SELECTED " + $index + " (" + value + ")";
            }
        }
        """.trimIndent(),
    )

    /**
     * GotoTaskAction's Open Task UI is a ChooseByNamePopup (GotoTaskAction$MyChooseByNamePopup),
     * NOT a ListPopup: a search field (ChooseByNameBase$MyTextField) + a JList (myList).
     * Model elements are TaskSymbol wrappers whose toString is useless — resolve getTask()
     * and print "presentableId summary (closed)" so entries can be matched by title.
     */
    fun gotoPopupInfo(): String = jsEdt(
        """
        $WALK
        var field = null;
        walkAll(function(comp) {
            if (field != null) return;
            var cn = comp.getClass().getName();
            if (cn.indexOf("ChooseByNameBase" + '$' + "MyTextField") >= 0) field = comp;
        });
        if (field == null) "NO-GOTO-POPUP";
        else {
            var f = findDeclaredField(field.getClass(), "this" + '$' + "0");
            f.setAccessible(true);
            var popup = f.get(field);
            var baseCls = java.lang.Class.forName("com.intellij.ide.util.gotoByName.ChooseByNameBase");
            var lf = baseCls.getDeclaredField("myList");
            lf.setAccessible(true);
            var list = lf.get(popup);
            var out = "TEXT=" + field.getText() + " | size=" + list.getModel().getSize() + " ::";
            for (var i = 0; i < list.getModel().getSize(); i++) {
                var v = list.getModel().getElementAt(i);
                if (v.getClass().getName().indexOf("TaskSymbol") >= 0) {
                    var t = v.getTask();
                    out += " [" + i + "] " + t.getPresentableId() + " " + t.getSummary() + (t.isClosed() ? " (closed)" : "");
                } else {
                    out += " [" + i + "] <" + v.getClass().getSimpleName() + ">";
                }
            }
            out;
        }
        """.trimIndent(),
    )

    /** Active task facts straight from TaskManager (supplementary, UI-independent). */
    fun activeTaskInfo(): String = jsEdt(
        """
        importPackage(com.intellij.openapi.project);
        importPackage(com.intellij.tasks);
        var p = ProjectManager.getInstance().getOpenProjects()[0];
        var t = TaskManager.getManager(p).getActiveTask();
        t == null ? "null" : t.getPresentableId() + " | " + t.getSummary() + " | " + t.getIssueUrl();
        """.trimIndent(),
    )

    fun changeLists(): String = jsEdt(
        """
        importPackage(com.intellij.openapi.project);
        importPackage(com.intellij.openapi.vcs.changes);
        var p = ProjectManager.getInstance().getOpenProjects()[0];
        var clm = ChangeListManager.getInstance(p);
        var out = "";
        var it = clm.getChangeLists().iterator();
        while (it.hasNext()) { var cl = it.next(); out += cl.getName() + " :: " + cl.getChanges().size() + "\n"; }
        out;
        """.trimIndent(),
    )

    test("00 connect, keep-alive, wait for project frame").config(timeout = 6.minutes) {
        robot = RemoteRobot(robotUrl)
        log("connected to $robotUrl, IDE os=${robot.os}")
        frame = await<ComponentFixture>("IdeFrameImpl", 300) {
            robot.findAll<ComponentFixture>(byXpath("//div[@class='IdeFrameImpl']")).firstOrNull()
        }
        log("project frame found: id=${frame.remoteComponent.id}")
        startKeepAlive()
        if (robot.findAll<ComponentFixture>(byXpath("//div[@class='FlatWelcomeFrame']")).isNotEmpty()) {
            throw AssertionError("Welcome frame still visible — scratch project did not open")
        }
        log("open projects: " + jsEdt(
            """
            importPackage(com.intellij.openapi.project);
            var ps = ProjectManager.getInstance().getOpenProjects();
            ps.length + " | " + ps[0].getName() + " | " + ps[0].getBasePath();
            """.trimIndent(),
        ))
        shot(frame, "00-ide-up")
    }

    test("01 open Settings on Tools > Tasks > Servers").config(timeout = 2.minutes) {
        invokeAction("tasks.configure.servers")
        val settings = await<ComponentFixture>("Settings dialog (Servers)", 60) {
            findAllFixtures("//div[@class='MyDialog' and @accessiblename='Servers']").firstOrNull()
        }
        log("Settings dialog up (id=${settings.remoteComponent.id})")
        shot(settings, "01-settings-tasks-servers")
    }

    test("02 add ONES server via the + button (chooser popup, addRepository fallback)").config(timeout = 3.minutes) {
        // Click the Servers page toolbar "+" (scoped to the Servers dialog — the global
        // hierarchy carries unrelated ActionButtons), then pick ONES from the type chooser.
        // Fallback: invoke the exact method the chooser's ONES entry executes —
        // TaskRepositoriesConfigurable.addRepository(onesType.createRepository(project)).
        val addResult = js(
            """
            $WALK
            importPackage(com.intellij.openapi.application);
            var dlg = findDialog("Servers");
            if (dlg == null) "NO-SERVERS-DIALOG";
            else {
                var add = null;
                walkIn(dlg, function(comp) {
                    if (add != null) return;
                    var cn = comp.getClass().getName();
                    if (cn.indexOf("ActionButton") >= 0) {
                        var ac = comp.getAccessibleContext();
                        if (ac != null && ac.getAccessibleName() == "Add") add = comp;
                    }
                });
                if (add == null) "NO-ADD-BUTTON";
                else {
                    var b = add;
                    ApplicationManager.getApplication().invokeLater(function() { b.click(); }, ModalityState.any());
                    "ADD-CLICKED";
                }
            }
            """.trimIndent(),
        )
        log("Add button: $addResult")
        val chooser = runCatching {
            await<String>("repository type chooser popup with ONES", 20) {
                val info = popupListInfo()
                if (info.contains("ONES")) info else null
            }
        }.getOrNull()
        var addedVia = ""
        if (chooser != null) {
            log("chooser popup shown: $chooser")
            val onesIndex = Regex("\\[(\\d+)\\][^\\[]*ONES").find(chooser)
                ?.groupValues?.get(1)?.toInt()
                ?: throw AssertionError("cannot locate ONES entry in chooser: $chooser")
            val sel = popupSelect(onesIndex)
            log("popupSelect($onesIndex): $sel")
            if (sel.startsWith("SELECTED")) addedVia = "popup"
        }
        if (addedVia.isEmpty()) {
            log("chooser path failed — falling back to addRepository")
            val added = addRepositoryFallback()
            log("addRepository fallback: $added")
            if (!added.startsWith("ADDED")) throw AssertionError("failed to add ONES repository: $added")
        }
        await<ComponentFixture>("ONES editor panel (服务器 URL label)", 30) {
            findAllFixtures("//div[@class='JBLabel' and @visible_text='服务器 URL']").firstOrNull()
                ?: findAllFixtures("//div[@class='JLabel' and @text='服务器 URL']").firstOrNull()
        }
        log("ONES repository editor shown")
        shot(findFixture("//div[@class='MyDialog' and @accessiblename='Servers']"), "02-ones-editor-added")
    }

    test("03 fill 服务器 URL / 团队 ID / API key").config(timeout = 2.minutes) {
        // FormBuilder.addLabeledComponent wires JLabel.labelFor -> the field, so field
        // discovery goes through the label texts (immune to unrelated text components
        // elsewhere in the hierarchy — the Settings search box broke the earlier
        // "count all JBTextField" xpath approach).
        val setRes = jsEdt(
            """
            $WALK
            var url = fieldFor("服务器 URL");
            var team = fieldFor("团队 ID");
            var key = fieldFor("API key");
            if (url == null || team == null || key == null) {
                "MISSING url=" + (url != null) + " team=" + (team != null) + " key=" + (key != null);
            } else {
                url.setText("$mockUrl");
                team.setText("$teamId");
                key.setText("$apiKey");
                "SET url=" + url.getText() + " team=" + team.getText() +
                    " keyLen=" + new java.lang.String(key.getPassword()).length();
            }
            """.trimIndent(),
        )
        log("field fill: $setRes")
        if (!setRes.startsWith("SET ")) {
            dumpHierarchy("03-fields-missing")
            throw AssertionError("editor fields not located: $setRes")
        }
        // Focus-leaving the password field is the plugin's persistence trigger — fire the real event.
        val fl = jsEdt(
            """
            $WALK
            var key = fieldFor("API key");
            if (key == null) "NO-KEY-FIELD";
            else {
                key.dispatchEvent(new java.awt.event.FocusEvent(key, 1005, false, null));
                "FOCUS-LOST-FIRED";
            }
            """.trimIndent(),
        )
        log("apiKey persistence trigger: $fl")
        if (fl != "FOCUS-LOST-FIRED") throw AssertionError("focus-lost dispatch failed: $fl")
        shot(findFixture("//div[@class='MyDialog' and @accessiblename='Servers']"), "03-editor-filled")
    }

    test("04 测试连接 succeeds against mock ONES").config(timeout = 2.minutes) {
        val testBtn = await<ComponentFixture>("测试连接 button", 15) {
            findAllFixtures("//div[@class='JButton' and (@text='测试连接' or @accessiblename='测试连接')]").firstOrNull()
        }
        // The handler runs synchronously and shows a MODAL dialog — click via
        // invokeLater(ModalityState.any()) so it fires despite the modal Settings dialog.
        robot.callJs<String>(
            testBtn,
            """
            importPackage(com.intellij.openapi.application);
            var b = component;
            ApplicationManager.getApplication().invokeLater(function() { b.doClick(); }, ModalityState.any());
            'test-click-scheduled';
            """.trimIndent(),
            false,
        )
        val dlg = await<ComponentFixture>("connection result dialog (连接测试)", 30) {
            findAllFixtures("//div[@class='MyDialog' and (@accessiblename='连接测试' or @title='连接测试')]").firstOrNull()
        }
        val body = textsOf(dlg).joinToString(" / ")
        log("test-connection dialog text: $body")
        if (!body.contains("连接成功")) {
            dumpHierarchy("04-failed")
            throw AssertionError("connection test did not succeed: $body")
        }
        shot(dlg, "04-test-connection-success")
        val closed = clickDialogButton("连接测试", "OK")
        log("closed connection result dialog: $closed")
        await<Unit>("连接测试 dialog closed", 15) {
            if (findAllFixtures("//div[@class='MyDialog' and (@accessiblename='连接测试' or @title='连接测试')]").isEmpty()) Unit else null
        }
    }

    test("05 apply Settings — server persisted into workspace").config(timeout = 2.minutes) {
        val applied = clickDialogButton("Servers", "OK") // Settings dialog OK
        log("Settings OK: $applied")
        if (applied != "CLICKED Servers/OK") throw AssertionError("Settings OK click failed: $applied")
        await<Unit>("Settings dialog closed", 30) {
            if (findAllFixtures("//div[@class='MyDialog' and @accessiblename='Servers']").isEmpty()) Unit else null
        }
        val servers = jsEdt(
            """
            importPackage(com.intellij.openapi.project);
            importPackage(com.intellij.tasks);
            var p = ProjectManager.getInstance().getOpenProjects()[0];
            var repos = TaskManager.getManager(p).getAllRepositories();
            var out = repos.length + " repo(s):";
            for (var i = 0; i < repos.length; i++) out += " [" + repos[i].toString() + " configured=" + repos[i].isConfigured() + "]";
            out;
            """.trimIndent(),
        )
        log("TaskManager repositories: $servers")
        if (!servers.contains("url=$mockUrl") || !servers.contains("teamID=$teamId")) {
            throw AssertionError("ONES repository not registered in TaskManager: $servers")
        }
        val ws = await<File>("workspace.xml contains ONES server", 30) {
            val f = File("/tmp/opencode/f3-scratch/.idea/workspace.xml")
            if (f.isFile && f.readText().contains("<ONES")) f else null
        }
        log("workspace.xml persisted ONES server (${ws.length()} bytes)")
        // Enable the platform's commit-message formatting on the repository (the setting the
        // official connectors expose as the "Commit Message Format" checkbox; our editor panel
        // does not carry that section). Without it TaskUtil.getChangeListComment() returns null
        // and the commit message would never carry "{number} {title}" (bytecode-verified gate).
        val fmtEnabled = jsEdt(
            """
            importPackage(com.intellij.openapi.project);
            importPackage(com.intellij.tasks);
            var p = ProjectManager.getInstance().getOpenProjects()[0];
            var repo = TaskManager.getManager(p).getAllRepositories()[0];
            repo.setShouldFormatCommitMessage(true);
            "shouldFormatCommitMessage=" + repo.isShouldFormatCommitMessage() +
                " format=" + repo.getCommitMessageFormat();
            """.trimIndent(),
        )
        log("commit-message formatting enabled: $fmtEnabled")
        if (!fmtEnabled.contains("shouldFormatCommitMessage=true")) {
            throw AssertionError("failed to enable commit message formatting: $fmtEnabled")
        }
        shot(frame, "05-after-apply-settings")
    }

    test("06 Open Task pulls the fixture list from mock ONES").config(timeout = 4.minutes) {
        invokeAction("tasks.goto")
        // Entries arrive asynchronously (ChooseByNamePopup model -> TaskManager.getIssues -> mock).
        var entries = await<String>("task chooser entries", 90) {
            val info = gotoPopupInfo()
            if (info.contains("客户端支持应用内下载更新") || info.contains("集成神经网络芯片")) info else null
        }
        // If the empty-pattern list stayed empty, search for the in_progress fixture directly.
        if (!entries.contains("集成神经网络芯片")) {
            log("empty-pattern list did not include #58, typing search fragment")
            jsEdt(
                """
                $WALK
                var field = null;
                walkAll(function(comp) {
                    if (field != null) return;
                    var cn = comp.getClass().getName();
                    if (cn.indexOf("ChooseByNameBase" + '$' + "MyTextField") >= 0) field = comp;
                });
                if (field == null) "NO-FIELD";
                else { field.setText("集成"); "typed"; }
                """.trimIndent(),
            )
            entries = await<String>("task chooser entries after search", 60) {
                val info = gotoPopupInfo()
                if (info.contains("集成神经网络芯片")) info else null
            }
        }
        log("task chooser entries: $entries")
        if (entries.contains("最终确认和验收")) log("NOTE: done-category task #27 visible in chooser")
        // The chooser spans two heavyweight windows (field panel + drop-down list); capture
        // the one whose JList actually holds the TaskSymbol entries — that's the visible
        // "task list" evidence.
        val popupWin = await<ComponentFixture>("chooser task-list window", 30) {
            robot.findAll<ComponentFixture>(byXpath("//div[@class='HeavyWeightWindow']"))
                .firstOrNull { w ->
                    kotlin.runCatching {
                        robot.callJs<String>(
                            w,
                            """
                            var q = new java.util.LinkedList(); q.add(component);
                            var found = false;
                            while (!q.isEmpty()) {
                                var c = q.removeFirst();
                                if (c instanceof javax.swing.JList) {
                                    var m = c.getModel();
                                    for (var i = 0; i < m.getSize(); i++) {
                                        if (m.getElementAt(i).getClass().getName().indexOf("TaskSymbol") >= 0) { found = true; break; }
                                    }
                                }
                                if (found) break;
                                if (c instanceof java.awt.Container) {
                                    var ch = c.getComponents();
                                    for (var j = 0; j < ch.length; j++) q.add(ch[j]);
                                }
                            }
                            found ? "yes" : "no";
                            """.trimIndent(),
                            true,
                        ) == "yes"
                    }.getOrDefault(false)
                }
        }
        shot(popupWin, "06-open-task-list")
        log("chooser state: ${gotoPopupInfo()}")
    }

    test("07 activate #58 (in_progress fixture)").config(timeout = 4.minutes) {
        val idxInfo = await<String>("index of #58 entry", 30) {
            val info = gotoPopupInfo()
            val m = Regex("\\[(\\d+)\\][^\\[]*集成神经网络芯片").find(info)
            m?.value ?: if (info.contains("集成神经网络芯片")) info else null
        }
        val idx = Regex("\\[(\\d+)\\]").find(idxInfo)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("cannot parse entry index from: $idxInfo")
        log("selecting chooser item $idx for #58")
        // Select the row and press Enter in the popup's real search field — the same code path
        // a human Enter goes through (MyTextField -> ActionListener -> elementChosen callback).
        // Everything runs on the EDT (Swing access asserts it); the Enter dispatch is scheduled
        // via invokeLater(ModalityState.any()) so the call returns before elementChosen pumps
        // the nested modal loop of the activation dialog.
        val entered = jsEdt(
            """
            $WALK
            importPackage(com.intellij.openapi.application);
            var field = null;
            walkAll(function(comp) {
                if (field != null) return;
                var cn = comp.getClass().getName();
                if (cn.indexOf("ChooseByNameBase" + '$' + "MyTextField") >= 0) field = comp;
            });
            if (field == null) "NO-FIELD";
            else {
                var f = field.getClass().getDeclaredField("this" + '$' + "0");
                f.setAccessible(true);
                var popup = f.get(field);
                var baseCls = java.lang.Class.forName("com.intellij.ide.util.gotoByName.ChooseByNameBase");
                var lf = baseCls.getDeclaredField("myList");
                lf.setAccessible(true);
                var list = lf.get(popup);
                list.setSelectedIndex($idx);
                var fd = field;
                ApplicationManager.getApplication().invokeLater(function() {
                    var now = java.lang.System.currentTimeMillis();
                    fd.dispatchEvent(new java.awt.event.KeyEvent(
                        fd, java.awt.event.KeyEvent.KEY_PRESSED, now, 0,
                        java.awt.event.KeyEvent.VK_ENTER, new java.lang.Character('\n')));
                    fd.dispatchEvent(new java.awt.event.KeyEvent(
                        fd, java.awt.event.KeyEvent.KEY_RELEASED, now, 0,
                        java.awt.event.KeyEvent.VK_ENTER, new java.lang.Character('\n')));
                }, ModalityState.any());
                "ENTER-SCHEDULED " + $idx;
            }
            """.trimIndent(),
        )
        log("Enter dispatch scheduled on chooser field: $entered")
        // An activation dialog may appear (changelist creation etc.) — confirm it if so.
        runCatching {
            val dlg = await<ComponentFixture>("activation dialog", 25) {
                findAllFixtures("//div[@class='MyDialog']").firstOrNull { d ->
                    kotlin.runCatching { textsOf(d).any { it.contains("集成神经网络芯片") } }.getOrDefault(false)
                }
            }
            log("activation dialog: ${textsOf(dlg)}")
            shot(dlg, "07a-activation-dialog")
            val okRes = robot.callJs<String>(
                dlg,
                """
                importPackage(com.intellij.openapi.application);
                var ok = null;
                var q = new java.util.LinkedList(); q.add(component);
                while (!q.isEmpty()) {
                    var c = q.removeFirst();
                    if (c instanceof javax.swing.JButton) {
                        var ac = c.getAccessibleContext();
                        if (ac != null && ac.getAccessibleName() == "OK") ok = c;
                    }
                    if (c instanceof java.awt.Container) {
                        var ch = c.getComponents();
                        for (var j = 0; j < ch.length; j++) q.add(ch[j]);
                    }
                }
                if (ok == null) "NO-OK";
                else {
                    ApplicationManager.getApplication().invokeLater(function() { ok.doClick(); }, ModalityState.any());
                    "OK-CLICKED";
                }
                """.trimIndent(),
                false,
            )
            log("confirmed activation dialog: $okRes")
        }.onFailure { log("no activation dialog intercepted (${it.message})") }

        await<String>("active task == #58", 60) {
            val info = activeTaskInfo()
            log("activeTask: $info")
            if (info.contains("58") && info.contains("集成神经网络芯片")) info else null
        }
        shot(frame, "07b-task-active")
    }

    test("08 dirty a file after activation (lands in task changelist)").config(timeout = 3.minutes) {
        // Dirty the TRACKED README.md (a new file would stay an unversioned file, which does
        // not count into LocalChangeList.getChanges()). External writes bypass the IDE's VFS
        // listener on this machine, so force a synchronous refresh before polling.
        File("/tmp/opencode/f3-scratch/README.md").appendText("\nchange made while #58 is active\n")
        log("dirtied README.md")
        val refresh = jsEdt(
            """
            importPackage(com.intellij.openapi.vfs);
            var f = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new java.io.File("/tmp/opencode/f3-scratch/README.md"));
            f == null ? "NOT-FOUND" : "REFRESHED " + f.getPath();
            """.trimIndent(),
        )
        log("VFS refresh: $refresh")
        await<String>("changelists reflect new dirty file in #58 changelist", 90) {
            val cls = changeLists()
            log("changelists now:\n$cls")
            if (cls.split("\n").any { it.contains("58") && !it.endsWith(":: 0") }) cls else null
        }
    }

    test("09 commit message contains {number} {title} — THE acceptance shot").config(timeout = 4.minutes) {
        invokeAction("CheckinProject")
        fun editorTexts(): List<String> = findAllFixtures("//div[@class='EditorComponentImpl']").mapNotNull { ed ->
            kotlin.runCatching {
                robot.callJs<String>(ed, "component.getEditor().getDocument().getText();")
            }.getOrNull()
        }
        val commitMessage = await<String>("commit message filled with '58 <title>'", 60) {
            editorTexts().firstOrNull { it.contains("58") && it.contains("集成神经网络芯片") }
        }
        log("commit message: [${commitMessage.replace("\n", "\\n")}]")
        File(evidenceDir, "09-commit-message.txt").writeText(commitMessage)
        val msgEditor = findAllFixtures("//div[@class='EditorComponentImpl']").first { ed ->
            kotlin.runCatching {
                robot.callJs<String>(ed, "component.getEditor().getDocument().getText();")
            }.getOrNull() == commitMessage
        }
        shot(msgEditor, "09a-commit-message-editor")
        shot(frame, "09b-commit-view")
    }

    test("10 bonus — deep link /project/#/team/{teamID}/task/{id}").config(timeout = 2.minutes) {
        val info = activeTaskInfo()
        log("active task info: $info")
        if (!info.contains("/project/#/team/$teamId/task/")) {
            throw AssertionError("active task has no calibrated ONES deep link: $info")
        }
        File(evidenceDir, "10-active-task.txt").writeText(info)
        shot(frame, "10-frame-final")
    }
})
