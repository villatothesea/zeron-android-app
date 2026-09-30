package sh.zeron.android.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.zeron.android.design.GlassFrameLayout
import sh.zeron.android.design.LocalZeronColors
import sh.zeron.android.design.ZeronDark
import sh.zeron.android.design.ZeronMaterialTheme
import sh.zeron.android.ui.ScheduleSendDialog

/** The Schedule send time picker (long-press Send). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
open class ScheduleDialogScreenshotTest {
    protected open val subdir: String = ""

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun gate() {
        Screenshots.assumeEnabled()
        GlassFrameLayout.backdrop = null
        // The usual case (USE_EXACT_ALARM on 13+): no "exact alarms are off" hint.
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun schedulePicker() {
        val colors = ZeronDark
        compose.setContent {
            CompositionLocalProvider(LocalZeronColors provides colors) {
                ZeronMaterialTheme(colors) {
                    Box(Modifier.fillMaxSize().background(colors.background))
                    ScheduleSendDialog(colors, onDismiss = {}) { }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage(Screenshots.path(subdir + "10-schedule-picker.png"))
    }
}
