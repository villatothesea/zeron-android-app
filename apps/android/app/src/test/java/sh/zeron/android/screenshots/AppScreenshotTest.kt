package sh.zeron.android.screenshots

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.MainActivity
import sh.zeron.android.core.Machine
import sh.zeron.android.core.MachineStore
import sh.zeron.android.core.ZeronModel
import uniffi.zeron_core.AgentUsage
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.UsageWindow

/**
 * Real app screens on the JVM: MainActivity under Robolectric running the
 * host build of the Rust core with the Demo workspace (DemoFixture.STANDARD),
 * plus two fixture SSH machines and fixture plan usage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class AppScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun gate() {
        Screenshots.assumeEnabled()
        Screenshots.assumeHostCore()
    }

    private val studio = Machine(id = "fixture-studio", name = "Studio PC", host = "192.168.1.20", user = "dev")
    private val buildBox = Machine(id = "fixture-build", name = "Build box", host = "build.local", port = 2222, user = "ci", auth = Machine.AUTH_PASSWORD)

    @Test
    fun appScreens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // A phone-like device name (the demo lists this device) and a
        // keystore, so the phone's SSH key renders.
        org.robolectric.shadows.ShadowBuild.setModel("Pixel 8")
        FakeAndroidKeyStore.install()
        // No GitHub update probe (it would add an "update available" row).
        app.getSharedPreferences("zeron-update", 0).edit().putLong("lastCheck", System.currentTimeMillis()).commit()
        MachineStore(app).apply {
            save(studio, null)
            save(buildBox, null)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: ZeronModel
        scenario.onActivity { model = ViewModelProvider(it)[ZeronModel::class.java] }

        settleUntil("demo workspace") { model.phase == ZeronModel.Phase.Ready && model.workspace != null }
        model.agentUsageSource = { _, _ -> fixtureUsage() }
        settle()

        model.applyListMode(ZeronModel.ListMode.Project)
        settle()
        capture("01a-home-by-project.png")

        model.applyListMode(ZeronModel.ListMode.Activity)
        settle()
        capture("01b-home-by-activity.png")
        model.applyListMode(ZeronModel.ListMode.Project)
        settle()

        val chat = pickChat(model)
        model.openSession(chat.id)
        settle(2000)
        // Start a demo turn so the status pill shows the working timer.
        val handle = model.client!!.openSession(chat.id)
        handle.send(SendRequest(text = "Add a test for the fade timing.", attachments = emptyList(), worktree = null, busy = BusyPolicy.QUEUE))
        settleUntil("a running turn", 20_000) { handle.composer().live.turnRunning }
        val since = System.currentTimeMillis()
        settleUntil("the timer to tick", 20_000) { System.currentTimeMillis() - since > 4_000 || !handle.composer().live.turnRunning }
        capture("02-chat-usage-rings.png")

        // Tapping the rings opens the Usage sheet.
        openUsageSheet()
        settle(1500)
        capture("03-usage-sheet.png")
        pressBack(scenario)
        settle()
        model.back()
        settle()

        model.tab = ZeronModel.Tab.Settings
        settle()
        capture("04-settings.png")

        model.showMachines = true
        settle()
        capture("05-machines.png")

        model.editMachine = model.machines.first { it.id == studio.id }
        settle()
        capture("06-machine-editor.png")
        model.editMachine = null
        model.showMachines = false
        model.tab = ZeronModel.Tab.Sessions
        settle()

        model.showNewSession = true
        settle(2000)
        capture("07-new-session.png")
        model.showNewSession = false
        settle()
        scenario.close()
    }

    private fun openUsageSheet() {
        // The plan ring's percent: 62% (Claude Code) or 88% (Codex) fixture.
        val ring = androidx.compose.ui.test.hasText("62%").or(androidx.compose.ui.test.hasText("88%"))
        compose.onAllNodes(ring, useUnmergedTree = true).onFirst().performClick()
    }

    private fun pressBack(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    /** A demo chat whose harness reports plan usage (Claude Code first), not mid-turn. */
    private fun pickChat(model: ZeronModel): SessionRow {
        val ws = model.workspace!!
        val rows = ws.front.pinned + ws.front.sections.flatMap { it.sessions } + ws.front.recent
        return rows.firstOrNull { it.harness == "claude-code" }
            ?: rows.firstOrNull { it.harness == "codex" }
            ?: rows.first()
    }

    private fun fixtureUsage(): List<AgentUsage> {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        return listOf(
            AgentUsage(
                harness = "claude-code",
                email = "dev@example.com",
                planLabel = "Max",
                active = true,
                windows = listOf(
                    UsageWindow("5-hour", 0.62f, now + 2 * hour + 14 * 60_000L),
                    UsageWindow("Weekly", 0.35f, now + 3 * 24 * hour),
                ),
                fetchedAtMs = now,
                error = null,
            ),
            AgentUsage(
                harness = "codex",
                email = "dev@example.com",
                planLabel = "Pro",
                active = true,
                windows = listOf(
                    UsageWindow("5-hour", 0.88f, now + 47 * 60_000L),
                    UsageWindow("Weekly", 0.41f, now + 5 * 24 * hour),
                ),
                fetchedAtMs = now,
                error = null,
            ),
        )
    }

    private fun capture(name: String) {
        compose.onRoot().captureRoboImage(Screenshots.path(name))
    }

    /** Let the core's background work post back and Compose draw, for ~[ms]. */
    private fun settle(ms: Long = 800) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun settleUntil(what: String, timeoutMs: Long = 60_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            settle(200)
        }
    }
}
