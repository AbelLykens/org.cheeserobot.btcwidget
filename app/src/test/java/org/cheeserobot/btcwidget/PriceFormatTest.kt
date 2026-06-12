package org.cheeserobot.btcwidget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for magnitude-adaptive price formatting. The COMMA separator is
 * used throughout because it pins the decimal separator to '.' (and the
 * grouping separator to ','), making assertions independent of the test
 * JVM's default locale.
 */
class PriceFormatTest {

    private val comma = WidgetPrefs.SEPARATOR_COMMA
    private val none = WidgetPrefs.SEPARATOR_NONE

    @Test fun adaptiveDecimalsTrackMagnitude() {
        assertEquals(0, PriceFormat.adaptiveDecimals(12345.0))
        assertEquals(0, PriceFormat.adaptiveDecimals(1234.5))
        assertEquals(1, PriceFormat.adaptiveDecimals(123.4))
        assertEquals(2, PriceFormat.adaptiveDecimals(15.0314))   // XAU
        assertEquals(3, PriceFormat.adaptiveDecimals(1.5))
        assertEquals(3, PriceFormat.adaptiveDecimals(0.05))
        assertEquals(0, PriceFormat.adaptiveDecimals(0.0))
        assertEquals(0, PriceFormat.adaptiveDecimals(1_000_000.0))
        // Magnitude is taken on the absolute value.
        assertEquals(2, PriceFormat.adaptiveDecimals(-15.03))
    }

    @Test fun largePricesStayWhole() {
        assertEquals("12,345", PriceFormat.formatPrice(12345.0, false, comma))
        assertEquals("90,486", PriceFormat.formatPrice(90486.3555, false, comma))
    }

    @Test fun smallPricesGainDecimals() {
        // XAU: "15" would be useless; "15.03" preserves the move.
        assertEquals("15.03", PriceFormat.formatPrice(15.0314, false, comma))
        assertEquals("123.4", PriceFormat.formatPrice(123.4, false, comma))
        assertEquals("1.500", PriceFormat.formatPrice(1.5, false, comma))
    }

    @Test fun showDecimalsKeepsAtLeastTwoButAddsMoreWhenSmall() {
        // Large + toggle on → classic 2dp behaviour preserved.
        assertEquals("63,400.00", PriceFormat.formatPrice(63400.0, true, comma))
        // Small + toggle on → adaptive already gives >= 2.
        assertEquals("15.03", PriceFormat.formatPrice(15.0314, true, comma))
    }

    @Test fun roundsHalfUp() {
        assertEquals("15.04", PriceFormat.formatPrice(15.0359, false, comma))
        assertEquals("12,346", PriceFormat.formatPrice(12345.6, false, comma))
    }

    @Test fun plainFormatUnchangedForBtcAndBlock() {
        // BTC amount / block height must NOT pick up adaptive decimals.
        assertEquals("1", PriceFormat.format(1.0, false, none))
        assertEquals("0.50", PriceFormat.format(0.5, true, none))
        assertEquals("953,401", PriceFormat.format(953401.0, false, comma))
    }
}
