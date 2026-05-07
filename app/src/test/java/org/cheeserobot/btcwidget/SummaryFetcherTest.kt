package org.cheeserobot.btcwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the consolidated `/price/summary.json` parser.
 * Mirrors the real upstream shape with a slimmed-down sample (one
 * historical point per window) so the assertions stay readable.
 */
class SummaryFetcherTest {

    private val sampleBody = """
        {
          "price": {
            "when": "2026-05-07T18:17:03.081Z",
            "price_usd": "80175.0850",
            "price_eur": "68246.5450",
            "source": "CoinDesk",
            "when_unix": "1778177823"
          },
          "hist_1d": [
            {"when_unix": 1778090400, "price_usd": 81413, "price_eur": 69308},
            {"when_unix": 1778176800, "price_usd": 80134, "price_eur": 68211}
          ],
          "hist_7d": [
            {"when_unix": 1777564800, "price_usd": 76377, "price_eur": 65109},
            {"when_unix": 1778169600, "price_usd": 79873, "price_eur": 67955}
          ],
          "latest_block": {
            "height": 948347,
            "hash": "00000000000000000001529725e3a624f35bc496a246855cda520966905fc178",
            "time": "2026-05-07T18:11:53+00:00",
            "nTx": 4125,
            "size": 1637748,
            "miner_name": "SpiderPool"
          }
        }
    """.trimIndent()

    @Test fun parsesPriceFromInnerObject() {
        val r = SummaryFetcher.parse(sampleBody) as SummaryResult.Success
        assertEquals(80175.085, r.summary.currentPrice("USD")!!, 0.0001)
        assertEquals(68246.545, r.summary.currentPrice("EUR")!!, 0.0001)
    }

    @Test fun extractsOneDayAgoFromHist1dHead() {
        val r = SummaryFetcher.parse(sampleBody) as SummaryResult.Success
        // hist_1d[0] is the oldest sample → the "1 day ago" reference.
        assertEquals(81413.0, r.summary.oneDayAgoPrice("USD")!!, 0.0001)
        assertEquals(69308.0, r.summary.oneDayAgoPrice("EUR")!!, 0.0001)
    }

    @Test fun extractsOneWeekAgoFromHist7dHead() {
        val r = SummaryFetcher.parse(sampleBody) as SummaryResult.Success
        assertEquals(76377.0, r.summary.oneWeekAgoPrice("USD")!!, 0.0001)
        assertEquals(65109.0, r.summary.oneWeekAgoPrice("EUR")!!, 0.0001)
    }

    @Test fun parsesLatestBlock() {
        val r = SummaryFetcher.parse(sampleBody) as SummaryResult.Success
        val block = r.summary.latestBlock
        assertNotNull("Expected latest_block to parse", block)
        assertEquals(948347L, block!!.height)
        assertEquals("SpiderPool", block.minerName)
        assertEquals("2026-05-07T18:11:53+00:00", block.time)
    }

    @Test fun missingBlockKeyDegradesGracefully() {
        // A summary without latest_block must not break the whole parse —
        // the price widgets need to keep working.
        val noBlock = """
            {
              "price": {"price_usd": "80000", "price_eur": "68000"},
              "hist_1d": [],
              "hist_7d": []
            }
        """.trimIndent()
        val r = SummaryFetcher.parse(noBlock) as SummaryResult.Success
        assertNull(r.summary.latestBlock)
        assertEquals(80000.0, r.summary.currentPrice("USD")!!, 0.0001)
    }

    @Test fun emptyHistoryArraysReturnNullPeriodAgo() {
        val noHist = """
            {
              "price": {"price_usd": "80000", "price_eur": "68000"},
              "hist_1d": [],
              "hist_7d": []
            }
        """.trimIndent()
        val r = SummaryFetcher.parse(noHist) as SummaryResult.Success
        assertNull(r.summary.oneDayAgoPrice("USD"))
        assertNull(r.summary.oneWeekAgoPrice("EUR"))
    }

    @Test fun missingPriceObjectIsHardError() {
        // Without the "price" object the widget literally has nothing
        // to render, so the parse fails outright.
        val noPrice = """{"hist_1d": [], "hist_7d": []}"""
        val r = SummaryFetcher.parse(noPrice)
        assertTrue("Expected Error, got $r", r is SummaryResult.Error)
    }

    @Test fun malformedJsonIsHardError() {
        val r = SummaryFetcher.parse("not json at all")
        assertTrue("Expected Error, got $r", r is SummaryResult.Error)
    }

    @Test fun blockHeightAcceptsStringForm() {
        // Defensive: the upstream historically stringifies its numerics
        // (price_usd is a string), so we accept the same for height.
        val stringHeight = """
            {
              "price": {"price_usd": "80000", "price_eur": "68000"},
              "hist_1d": [],
              "hist_7d": [],
              "latest_block": {"height": "948347", "miner_name": "Foo"}
            }
        """.trimIndent()
        val r = SummaryFetcher.parse(stringHeight) as SummaryResult.Success
        assertEquals(948347L, r.summary.latestBlock!!.height)
    }

    @Test fun missingMinerNameIsNull() {
        // Some blocks aren't attributed to a known pool. The widget
        // falls back to "Unknown miner" at render time; the parser
        // surfaces a null so the prefs layer can clear any stale name.
        val noMiner = """
            {
              "price": {"price_usd": "80000", "price_eur": "68000"},
              "hist_1d": [],
              "hist_7d": [],
              "latest_block": {"height": 948347}
            }
        """.trimIndent()
        val r = SummaryFetcher.parse(noMiner) as SummaryResult.Success
        assertNull(r.summary.latestBlock!!.minerName)
    }

    /**
     * The cache pipeline relies on hist_1d / hist_7d being re-serialised
     * in the same array shape [HistoryFetcher.parse] understands so the
     * chart code keeps working unchanged.
     */
    @Test fun historyJsonRoundTripsThroughHistoryFetcher() {
        val r = SummaryFetcher.parse(sampleBody) as SummaryResult.Success
        val parsed1d = HistoryFetcher.parse(r.summary.hist1dJson) as HistoryResult.Success
        assertEquals(2, parsed1d.points.size)
        assertEquals(1778090400L, parsed1d.points[0].whenUnix)
        assertEquals(81413.0, parsed1d.points[0].usd!!, 0.0001)

        val parsed7d = HistoryFetcher.parse(r.summary.hist7dJson) as HistoryResult.Success
        assertEquals(2, parsed7d.points.size)
    }
}
