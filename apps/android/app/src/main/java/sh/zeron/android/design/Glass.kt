package sh.zeron.android.design

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Full-screen host that records the frame without glass capsules, blurs that
 * recording with [RenderEffect] (API 31+), then draws the real frame. Capsules
 * sample the blurred node so the frost is the content behind them.
 */
class GlassFrameLayout(context: Context) : FrameLayout(context) {
    private val contentNode = if (Build.VERSION.SDK_INT >= 29) RenderNode("zeron-content") else null
    private val blurNode = if (Build.VERSION.SDK_INT >= 29) RenderNode("zeron-blur") else null

    /** True while the offscreen pass is recording. Glass draws nothing on that pass. */
    var capturePass: Boolean = false
        private set

    var hasBlur: Boolean = false
        private set

    fun blurredNode(): RenderNode? = if (hasBlur) blurNode else null

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val content = contentNode
        val blur = blurNode
        if (
            Build.VERSION.SDK_INT < 31 ||
            content == null ||
            blur == null ||
            !canvas.isHardwareAccelerated ||
            width <= 0 ||
            height <= 0
        ) {
            capturePass = false
            hasBlur = false
            super.dispatchDraw(canvas)
            return
        }
        var contentOpen = false
        var blurOpen = false
        try {
            capturePass = true
            content.setPosition(0, 0, width, height)
            val recorded = content.beginRecording()
            contentOpen = true
            super.dispatchDraw(recorded)
            content.endRecording()
            contentOpen = false
            capturePass = false
            val radius = resources.displayMetrics.density * 18f
            blur.setPosition(0, 0, width, height)
            blur.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            val blurred = blur.beginRecording()
            blurOpen = true
            blurred.drawRenderNode(content)
            blur.endRecording()
            blurOpen = false
            hasBlur = true
            super.dispatchDraw(canvas)
        } catch (_: Throwable) {
            capturePass = false
            hasBlur = false
            if (contentOpen) runCatching { content.endRecording() }
            if (blurOpen) runCatching { blur.endRecording() }
            super.dispatchDraw(canvas)
        }
    }
}

val LocalGlassFrame = staticCompositionLocalOf<GlassFrameLayout?> { null }

/**
 * Frosted capsule. On API 31+ the fill is a [RenderEffect] blur of whatever
 * was drawn behind the capsule, plus a light tint and a hairline. Older
 * releases get a translucent fill.
 */
@Composable
fun Modifier.glassSurface(colors: ZeronColors, radius: Dp): Modifier {
    val frame = LocalGlassFrame.current
    val shape = RoundedCornerShape(radius)
    val line = if (colors.dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)
    val origin = remember { mutableStateOf(Offset.Zero) }
    val fallback = if (colors.dark) Color(0xFF1E1E1E).copy(alpha = 0.94f) else Color.White.copy(alpha = 0.82f)
    val tint = if (colors.dark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.45f)
    return this
        .onGloballyPositioned { origin.value = it.positionInRoot() }
        .drawWithContent {
            if (frame?.capturePass == true) return@drawWithContent
            drawContent()
        }
        .shadow(8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.18f))
        .clip(shape)
        .drawWithContent {
            val node = frame?.blurredNode()
            if (Build.VERSION.SDK_INT >= 31 && node != null) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.translate(-origin.value.x, -origin.value.y)
                    native.drawRenderNode(node)
                    native.restore()
                }
                drawRect(tint)
            } else {
                drawRect(fallback)
            }
            drawContent()
        }
        .border(0.6.dp, line, shape)
}
