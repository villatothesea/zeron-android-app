package sh.zeron.android.ui

/**
 * Elapsed-time label for the transcript Working row and the status pill
 * (iOS `StatusPill.elapsed`, extended past a day). Pure Kotlin so the unit
 * tests can run on the JVM.
 */
object ElapsedFormat {
    fun format(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            s < 86400 -> "${s / 3600}h ${s / 60 % 60}m"
            else -> "${s / 86400}d ${s / 3600 % 24}h"
        }
    }
}
