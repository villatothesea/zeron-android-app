package sh.zeron.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The navigator's rules, mirrored from the desktop MessageRail (crates/ui/src/rail.rs). */
class MessageNavTest {
    @Test
    fun hiddenUnderTwoMessages() {
        assertFalse(MessageNav.visible(0))
        assertFalse(MessageNav.visible(1))
        assertTrue(MessageNav.visible(2))
    }

    @Test
    fun bucketsAreTheIdentityUnderTheCap() {
        assertEquals((0 until 5).map { it..it }, MessageNav.buckets(5))
        assertTrue(MessageNav.buckets(0).isEmpty())
    }

    @Test
    fun bucketsPartitionEvenlyOverTheCap() {
        val b = MessageNav.buckets(100)
        assertEquals(MessageNav.MAX_TICKS, b.size)
        assertEquals(0, b.first().first)
        assertEquals(99, b.last().last)
        b.zipWithNext().forEach { (x, y) -> assertEquals(x.last + 1, y.first) }
        b.forEach { assertTrue(it.count() in 8..9) }
    }

    @Test
    fun activeIsTheLastMessageAtOrAboveTheReadingLine() {
        val ys = listOf(0f, 400f, 900f)
        assertEquals(0, MessageNav.activeIndex(ys, -50f))
        assertEquals(0, MessageNav.activeIndex(ys, 399f))
        assertEquals(1, MessageNav.activeIndex(ys, 400f))
        assertEquals(2, MessageNav.activeIndex(ys, 5_000f))
        assertEquals(-1, MessageNav.activeIndex(emptyList(), 0f))
    }

    @Test
    fun representativeTracksActiveExceptInTheLastBucket() {
        val b = MessageNav.buckets(100)
        // Reading inside a middle bucket shows the message being read.
        assertEquals(45, MessageNav.representative(b, 5, 45))
        // Reading elsewhere shows the bucket's newest.
        assertEquals(b[5].last, MessageNav.representative(b, 5, 0))
        // The last bucket always shows the newest message: the card's last
        // row is your last message even while you read an older one in it.
        assertEquals(99, MessageNav.representative(b, b.lastIndex, 95))
    }

    @Test
    fun previewsAreOneLineAndCapped() {
        assertEquals("fix the build and ship", MessageNav.preview("  fix the\n\nbuild   and ship "))
        val long = "字".repeat(200)
        val p = MessageNav.preview(long)
        assertEquals(MessageNav.PREVIEW_CHARS, p.length)
        assertTrue(p.endsWith("…"))
    }
}
