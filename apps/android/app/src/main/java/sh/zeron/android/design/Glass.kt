package sh.zeron.android.design

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Root of the Compose hierarchy. Kept so capsules can find the window later.
 * Blurring that hierarchy by recording it into a RenderNode crashes the
 * emulator GPU (SIGSEGV on RenderThread), so capsules don't do that.
 */
class GlassFrameLayout(context: Context) : FrameLayout(context)

val LocalGlassFrame = staticCompositionLocalOf<GlassFrameLayout?> { null }

/**
 * Frosted capsule. The fill is the measured iOS glass tone (`#1E1E1E` on the
 * demo backdrop) plus a hairline. A live [android.graphics.RenderEffect] of the
 * Compose tree is not used: capturing those draws crashes the software GPU.
 * Wallpaper pixels are blurred separately with Compose's RenderEffect blur
 * before they show through this fill.
 */
@Composable
fun Modifier.glassSurface(colors: ZeronColors, radius: Dp): Modifier {
    val shape = RoundedCornerShape(radius)
    val fill = if (colors.dark) Color(0xFF1E1E1E).copy(alpha = 0.92f) else Color.White.copy(alpha = 0.78f)
    val line = if (colors.dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)
    return this
        .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.14f))
        .clip(shape)
        .drawWithContent {
            drawRect(fill)
            drawContent()
        }
        .border(0.6.dp, line, shape)
}
