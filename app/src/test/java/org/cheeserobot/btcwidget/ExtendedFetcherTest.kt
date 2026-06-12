package org.cheeserobot.btcwidget

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the per-currency `summary.json?currency=…` parser.
 * No network or Android framework — `org.json` on the test classpath
 * provides the real JSON implementation.
 */
class ExtendedFetcherTest {

    // Structurally-faithful slice of a real /price/summary.json?currency=AUD
    // body: generic `price` key, generic hist arrays, a block snapshot.
    private val audBody = """
        {
          "price": {"currency":"AUD","price":"90486.3555","source":"yadio-anchored","when_unix":"1781285496"},
          "hist_1d": [
            {"when_unix":1781280000,"price":90545},
            {"when_unix":1781197200,"price":89814},
            {"when_unix":1781283600,"price":90623}
          ],
          "hist_7d": [
            {"when_unix":1780675200,"price":85277},
            {"when_unix":1781280000,"price":90572}
          ],
          "latest_block": {"height":953398,"miner_name":"F2Pool"}
        }
    """.trimIndent()

    @Test fun parsesCurrentPriceFromGenericKey() {
        val r = ExtendedFetcher.parse(audBody) as ExtendedResult.Success
        assertEquals(90486.3555, r.price, 0.0001)
    }

    @Test fun derivesOldestPointAsReference() {
        val r = ExtendedFetcher.parse(audBody) as ExtendedResult.Success
        // hist_1d / hist_7d are sorted ascending, so the oldest point is the
        // N-ago reference regardless of upstream array order.
        assertEquals(89814.0, r.oneDayAgo!!, 0.0)   // when_unix 1781197200
        assertEquals(85277.0, r.oneWeekAgo!!, 0.0)  // when_unix 1780675200
    }

    @Test fun emitsBothHistoryWindows() {
        val r = ExtendedFetcher.parse(audBody) as ExtendedResult.Success
        assertNotNull(r.hist1dJson)
        assertNotNull(r.hist7dJson)
        // 1d series carries all three points, oldest-first.
        val pts = ExtendedFetcher.decodeSeries(r.hist1dJson!!)
        assertEquals(3, pts.size)
        assertEquals(1781197200L, pts.first().first)
        assertEquals(89814.0, pts.first().second, 0.0)
    }

    @Test fun priceOnlyWhenHistoryAbsent() {
        val body = """{"price":{"currency":"INR","price":"6384056.27"}}"""
        val r = ExtendedFetcher.parse(body) as ExtendedResult.Success
        assertEquals(6384056.27, r.price, 0.001)
        assertNull(r.oneDayAgo)
        assertNull(r.oneWeekAgo)
        assertNull(r.hist1dJson)
        assertNull(r.hist7dJson)
    }

    @Test fun errorWhenPriceMissingOrBad() {
        assertTrue(ExtendedFetcher.parse("""{"hist_1d":[]}""") is ExtendedResult.Error)
        assertTrue(ExtendedFetcher.parse("""{"price":{"currency":"X"}}""") is ExtendedResult.Error)
        assertTrue(ExtendedFetcher.parse("garbage") is ExtendedResult.Error)
    }

    @Test fun parseGenericHistSortsAndCoerces() {
        val arr = JSONArray(
            """[{"when_unix":200,"price":"2.5"},{"when_unix":100,"price":1.5}]"""
        )
        val pts = ExtendedFetcher.parseGenericHist(arr)
        assertEquals(100L, pts[0].first)
        assertEquals(1.5, pts[0].second, 0.0)
        assertEquals(2.5, pts[1].second, 0.0)
    }

    @Test fun seriesRoundTrips() {
        val original = listOf(100L to 1.5, 200L to 2.5, 300L to 3.5)
        val json = ExtendedFetcher.encodeSeries(original)
        assertEquals(original, ExtendedFetcher.decodeSeries(json))
    }

    @Test fun decodeSeriesEmptyOnGarbage() {
        assertTrue(ExtendedFetcher.decodeSeries("nope").isEmpty())
    }

    // ---- Multi-currency (`?currency=A,B,…`) shape -------------------------

    // Structurally-faithful slice of a real ?currency=AUD,GBP body:
    // `price` is an ARRAY whose entries embed their own hist arrays and
    // carry a per-currency `sign`; latest_block stays top-level.
    private val multiBody = """
        {
          "price": [
            {"currency":"AUD","sign":"${'$'}","price":"90596.2952","when_unix":"1781287274",
             "hist_1d":[{"when_unix":1781197200,"price":90150},{"when_unix":1781283600,"price":90579}],
             "hist_7d":[{"when_unix":1780675200,"price":85044},{"when_unix":1781280000,"price":90563}]},
            {"currency":"GBP","sign":"£","price":"47752.9942","when_unix":"1781287274",
             "hist_1d":[{"when_unix":1781197200,"price":47518},{"when_unix":1781283600,"price":47744}],
             "hist_7d":[{"when_unix":1780675200,"price":44827},{"when_unix":1781280000,"price":47736}]}
          ],
          "latest_block": {"height":953400,"miner_name":"Foundry USA"}
        }
    """.trimIndent()

    @Test fun multiParsesEveryRequestedCurrency() {
        val out = ExtendedFetcher.parseMulti(multiBody, listOf("AUD", "GBP"))
        val aud = out["AUD"] as ExtendedResult.Success
        val gbp = out["GBP"] as ExtendedResult.Success
        assertEquals(90596.2952, aud.price, 0.0001)
        assertEquals(47752.9942, gbp.price, 0.0001)
        // Per-entry embedded histories land in each currency's own series.
        assertEquals(90150.0, aud.oneDayAgo!!, 0.0)
        assertEquals(44827.0, gbp.oneWeekAgo!!, 0.0)
        assertEquals(2, ExtendedFetcher.decodeSeries(aud.hist1dJson!!).size)
    }

    @Test fun multiCarriesUpstreamSign() {
        val out = ExtendedFetcher.parseMulti(multiBody, listOf("AUD", "GBP"))
        assertEquals("$", (out["AUD"] as ExtendedResult.Success).sign)
        assertEquals("£", (out["GBP"] as ExtendedResult.Success).sign)
    }

    @Test fun multiMapsDroppedCodesToError() {
        // The server silently omits unknown codes from the array; every
        // requested code must still get a deterministic result.
        val out = ExtendedFetcher.parseMulti(multiBody, listOf("AUD", "GBP", "XXX"))
        assertTrue(out["XXX"] is ExtendedResult.Error)
        assertEquals(3, out.size)
    }

    @Test fun multiHandlesLegacySingleObjectShape() {
        // A 1-code request returns the legacy object shape; parseMulti
        // must cope so batch callers don't special-case chunk size 1.
        val out = ExtendedFetcher.parseMulti(audBody, listOf("AUD"))
        val aud = out["AUD"] as ExtendedResult.Success
        assertEquals(90486.3555, aud.price, 0.0001)
        assertEquals(89814.0, aud.oneDayAgo!!, 0.0)
    }

    @Test fun multiAllErrorOnGarbage() {
        val out = ExtendedFetcher.parseMulti("garbage", listOf("AUD", "GBP"))
        assertTrue(out.values.all { it is ExtendedResult.Error })
        assertEquals(2, out.size)
    }

    @Test fun singleShapeCarriesSignWhenPresent() {
        val body = """{"price":{"currency":"CHF","sign":"CHF","price":"50869.91"}}"""
        val r = ExtendedFetcher.parse(body) as ExtendedResult.Success
        assertEquals("CHF", r.sign)
    }

    @Test fun envelopeLiftsLatestBlockFromBothShapes() {
        // Multi (array) shape…
        val multi = ExtendedFetcher.parseMultiEnvelope(multiBody, listOf("AUD", "GBP"))
        assertEquals(953400L, multi.latestBlock!!.height)
        assertEquals("Foundry USA", multi.latestBlock!!.minerName)
        // …and the legacy single-object shape.
        val single = ExtendedFetcher.parseMultiEnvelope(audBody, listOf("AUD"))
        assertEquals(953398L, single.latestBlock!!.height)
        // Garbage → no block, every code errored.
        assertNull(ExtendedFetcher.parseMultiEnvelope("garbage", listOf("AUD")).latestBlock)
    }

    // ---- Legacy-shape history merge ---------------------------------------

    @Test fun mergeRebuildsLegacyRows() {
        val usd = listOf(100L to 63526.0, 200L to 63419.0)
        val eur = listOf(100L to 55000.0, 200L to 54904.0)
        val merged = ExtendedFetcher.mergeSeriesToLegacyHist(usd, eur)
        // The merged body must parse through the SAME pipeline the
        // charts use for the bare endpoint's arrays.
        val parsed = HistoryFetcher.parse(merged) as HistoryResult.Success
        assertEquals(2, parsed.points.size)
        assertEquals(100L, parsed.points[0].whenUnix)
        assertEquals(63526.0, parsed.points[0].usd!!, 0.0)
        assertEquals(55000.0, parsed.points[0].eur!!, 0.0)
    }

    @Test fun mergeToleratesMissingCurrencyOrTimestamps() {
        // EUR absent entirely; USD-only rows must still chart.
        val usdOnly = ExtendedFetcher.mergeSeriesToLegacyHist(
            listOf(100L to 1.0, 200L to 2.0), null
        )
        val p1 = HistoryFetcher.parse(usdOnly) as HistoryResult.Success
        assertEquals(2, p1.points.size)
        assertNull(p1.points[0].eur)

        // Disjoint timestamp grids union into per-row partial entries.
        val disjoint = ExtendedFetcher.mergeSeriesToLegacyHist(
            listOf(100L to 1.0), listOf(200L to 2.0)
        )
        val p2 = HistoryFetcher.parse(disjoint) as HistoryResult.Success
        assertEquals(2, p2.points.size)
        assertNull(p2.points[0].eur)
        assertNull(p2.points[1].usd)
    }
}
