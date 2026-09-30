package sh.zeron.android.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.zeron.android.MainActivity
import sh.zeron.android.core.ZeronModel
import sh.zeron.android.screenshots.FakeAndroidKeyStore
import sh.zeron.android.screenshots.Screenshots
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.SendRequest

/**
 * Session transcript controls: a code block's copy button copies the code
 * and shows its check for ~2s, then reverts; the message navigator appears
 * once there are two of your messages, opens on tap, and a pick glides the
 * transcript to that message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TranscriptControlsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun gate() = Screenshots.assumeHostCore()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: ZeronModel

    private fun launch(chat: String) {
        FakeAndroidKeyStore.install()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { model = ViewModelProvider(it)[ZeronModel::class.java] }
        val app = model.getApplication<Application>()
        app.getSharedPreferences("zeron-update", 0).edit().putLong("lastCheck", System.currentTimeMillis()).putBoolean("autoUpdate", false).commit()
        settleUntil("demo workspace") { model.phase == ZeronModel.Phase.Ready && model.workspace != null }
        model.openSession(chat)
        settleUntil("the transcript") { transcript()?.codeRowKeys() != null && (transcript()?.height ?: 0) > 0 }
        settle(1000)
    }

    private fun transcript(): TranscriptListView? {
        var found: TranscriptListView? = null
        scenario.onActivity { found = find(it.window.decorView) }
        return found
    }

    private fun find(v: View): TranscriptListView? {
        if (v is TranscriptListView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun copyButtonShowsACheckThenReverts() {
        launch("chat-zh")
        val view = transcript()!!
        val key = view.codeRowKeys().first()
        view.scrollToRow(key)
        glide(view)
        val center = view.copyButtonCenters().first()
        tap(view, center.x, center.y)
        settle(300)
        assertNotNull("the tapped block shows the check", view.copiedCode)
        val clip = model.getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java).primaryClip
        assertTrue("code on the clipboard", (clip?.getItemAt(0)?.text ?: "").isNotEmpty())
        settle(2400)
        assertNull("the check reverts after ~2s", view.copiedCode)
        scenario.close()
    }

    @Test
    fun navigatorListsYourMessagesAndJumps() {
        launch("chat-zh")
        // One message of yours: no navigator.
        assertEquals(0, compose.onAllNodesWithTag("msg-nav").fetchSemanticsNodes().size)
        val handle = model.client!!.openSession("chat-zh")
        for (text in listOf("把第二个代码块改成异步版本", "再补一个单元测试")) {
            handle.send(SendRequest(text = text, attachments = emptyList(), worktree = null, busy = BusyPolicy.QUEUE))
            settle(1500)
        }
        settleUntil("the navigator") { compose.onAllNodesWithTag("msg-nav").fetchSemanticsNodes().isNotEmpty() }
        settleUntil("three of your messages") { (transcript()?.userMarkCount ?: 0) >= 3 }
        compose.onNodeWithTag("msg-nav").performClick()
        settle()
        val items = compose.onAllNodesWithTag("msg-nav-item").fetchSemanticsNodes().size
        assertTrue("one preview per message, got $items", items >= 3)
        // The first message: the transcript glides up, away from the bottom.
        compose.onAllNodesWithTag("msg-nav-item")[0].performClick()
        settle(300)
        glide(transcript()!!)
        assertEquals(0, compose.onAllNodesWithTag("msg-nav-card").fetchSemanticsNodes().size)
        val view = transcript()!!
        assertEquals("reading your first message", 0, view.activeUserMark)
        assertTrue("away from the bottom", view.distanceFromBottomPx() > 0f)
        scenario.close()
    }

    /** Robolectric doesn't draw the AndroidView, so run its glide (computeScroll) by hand. */
    private fun glide(view: TranscriptListView) {
        repeat(30) {
            scenario.onActivity { view.computeScroll() }
            settle(40)
        }
    }

    private fun settle(ms: Long = 600) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun settleUntil(what: String, timeoutMs: Long = 30_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            settle(200)
        }
    }
}
