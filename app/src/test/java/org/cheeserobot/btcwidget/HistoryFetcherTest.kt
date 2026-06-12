package org.cheeserobot.btcwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the price-history parser. The historical
 * network path that used to back this parser was retired in v2.8; the
 * arrays now arrive embedded in the unified summary payload and only
 * the pure [HistoryFetcher.parse] entry point survives.
 */
class HistoryFetcherTest {

    /** Two-row sample mirroring the real upstream shape. */
    private val sampleBody = """
        [
          {"when_unix": 1777449600, "price_usd": 77567, "price_eur": 66270},
          {"when_unix": 1777464000, "price_usd": 76733, "price_eur": 65617}
        ]
    """.trimIndent()

    @Test fun parsesBasicArray() {
        val r = HistoryFetcher.parse(sampleBody) as HistoryResult.Success
        assertEquals(2, r.points.size)
        assertEquals(1777449600L, r.points[0].whenUnix)
        assertEquals(77567.0, r.points[0].usd!!, 0.0001)
        assertEquals(66270.0, r.points[0].eur!!, 0.0001)
        assertEquals(76733.0, r.points[1].usd!!, 0.0001)
    }

    @Test fun rawBodyIsRoundTrippable() {
        val r = HistoryFetcher.parse(sampleBody) as HistoryResult.Success
        // Re-parsing the cached body must yield the same points so the
        // provider's render-from-cache path is reliable.
        val r2 = HistoryFetcher.parse(r.rawBody) as HistoryResult.Success
        assertEquals(r.points, r2.points)
    }

    @Test fun seriesForUsd() {
        val r = HistoryFetcher.parse(sampleBody) as HistoryResult.Success
        val usd = HistoryFetcher.seriesFor(r.points, "USD")
        assertEquals(2, usd.size)
        assertEquals(77567.0, usd[0], 0.0001)
        assertEquals(76733.0, usd[1], 0.0001)
    }

    @Test fun seriesForEur() {
        val r = HistoryFetcher.parse(sampleBody) as HistoryResult.Success
        val eur = HistoryFetcher.seriesFor(r.points, "EUR")
        assertEquals(2, eur.size)
        assertEquals(66270.0, eur[0], 0.0001)
    }

    @Test fun stringNumbersAreCoerced() {
        val body = """[
          {"when_unix": "1777449600", "price_usd": "77567.50", "price_eur": "66270.10"}
        ]""".trimIndent()
        val r = HistoryFetcher.parse(body) as HistoryResult.Success
        assertEquals(77567.5, r.points[0].usd!!, 0.0001)
        assertEquals(66270.1, r.points[0].eur!!, 0.0001)
    }

    @Test fun rowMissingBothPricesIsSkipped() {
        val body = """[
          {"when_unix": 1777449600, "price_usd": 77567, "price_eur": 66270},
          {"when_unix": 1777464000},
          {"when_unix": 1777478400, "price_usd": 75646, "price_eur": 64773}
        ]""".trimIndent()
        val r = HistoryFetcher.parse(body) as HistoryResult.Success
        assertEquals(2, r.points.size)
        assertEquals(1777449600L, r.points[0].whenUnix)
        assertEquals(1777478400L, r.points[1].whenUnix)
    }

    @Test fun rowMissingOnlyOneCurrencyIsKept() {
        val body = """[
          {"when_unix": 1777449600, "price_usd": 77567},
          {"when_unix": 1777464000, "price_usd": 76733, "price_eur": 65617}
        ]""".trimIndent()
        val r = HistoryFetcher.parse(body) as HistoryResult.Success
        assertEquals(2, r.points.size)
        // seriesFor("EUR") drops the row with no EUR rather than zero-filling.
        val eur = HistoryFetcher.seriesFor(r.points, "EUR")
        assertEquals(1, eur.size)
        assertEquals(65617.0, eur[0], 0.0001)
    }

    @Test fun emptyArrayIsError() {
        val r = HistoryFetcher.parse("[]")
        assertTrue("Expected Error, got $r", r is HistoryResult.Error)
    }

    @Test fun malformedJsonIsError() {
        val r = HistoryFetcher.parse("not json")
        assertTrue("Expected Error, got $r", r is HistoryResult.Error)
    }

    @Test fun seriesForUnknownCurrencyDefaultsToUsd() {
        // Implementation: unknown currency falls through to USD branch.
        // Documented for the SATS-on-USD ride; "GBP" should still come
        // back as USD numbers rather than empty.
        val r = HistoryFetcher.parse(sampleBody) as HistoryResult.Success
        val gbp = HistoryFetcher.seriesFor(r.points, "GBP")
        assertEquals(2, gbp.size)
        assertEquals(77567.0, gbp[0], 0.0001)
    }
}
