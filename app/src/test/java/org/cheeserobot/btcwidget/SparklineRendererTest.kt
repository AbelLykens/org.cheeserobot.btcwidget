package org.cheeserobot.btcwidget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure (non-Android) parts of [SparklineRenderer]. The
 * actual bitmap-rendering path uses Android's Canvas/Paint and lives
 * untested here because Robolectric isn't on the classpath; the
 * project already keeps unit tests pure-JVM.
 */
class SparklineRendererTest {

    @Test fun colorForRisingSeriesIsGreen() {
        val v = doubleArrayOf(100.0, 110.0, 120.0)
        assertEquals(SparklineRenderer.COLOR_UP, SparklineRenderer.colorFor(v))
    }

    @Test fun colorForFallingSeriesIsRed() {
        val v = doubleArrayOf(120.0, 110.0, 100.0)
        assertEquals(SparklineRenderer.COLOR_DOWN, SparklineRenderer.colorFor(v))
    }

    @Test fun colorForFlatSeriesIsGreen() {
        // Flat 7d → end >= start → counts as up. Arbitrary but stable.
        val v = doubleArrayOf(100.0, 100.0, 100.0)
        assertEquals(SparklineRenderer.COLOR_UP, SparklineRenderer.colorFor(v))
    }

    @Test fun colorForOnlyEndsMatter() {
        // Big intermediate dip but ends higher than start → green.
        val v = doubleArrayOf(100.0, 50.0, 80.0, 110.0)
        assertEquals(SparklineRenderer.COLOR_UP, SparklineRenderer.colorFor(v))
    }

    @Test fun colorForEmptyDefaultsToUp() {
        assertEquals(SparklineRenderer.COLOR_UP, SparklineRenderer.colorFor(doubleArrayOf()))
        assertEquals(SparklineRenderer.COLOR_UP, SparklineRenderer.colorFor(doubleArrayOf(100.0)))
    }
}
