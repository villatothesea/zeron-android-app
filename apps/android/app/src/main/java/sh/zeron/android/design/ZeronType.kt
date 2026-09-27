package sh.zeron.android.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import sh.zeron.android.R

/** Geist, the same family the Rust core measures. UI labels use these faces. */
object ZeronType {
    val Sans = FontFamily(
        Font(R.font.geist, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold),
        Font(R.font.geist_italic, FontWeight.Normal, FontStyle.Italic),
    )
    val Mono = FontFamily(
        Font(R.font.geist_mono, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
    )
}
