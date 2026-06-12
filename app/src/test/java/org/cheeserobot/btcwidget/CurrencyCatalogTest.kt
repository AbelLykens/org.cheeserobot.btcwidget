package org.cheeserobot.btcwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the currency catalog: parsing the `/api/currencies/`
 * payload, the bundled fallback, routing predicates, and symbols.
 */
class CurrencyCatalogTest {

    // Trimmed but structurally-faithful slice of the real /api/currencies/ body.
    private val body = """
        {
          "anchor": "USD",
          "history_max_days": 14,
          "count": 4,
          "currencies": [
            {"code":"AED","name":"","pricing":"derived","current":true,"history":false,"rate_age_sec":232},
            {"code":"USD","name":"US Dollar","pricing":"trade","current":true,"history":true,"rate_age_sec":null},
            {"code":"GBP","name":"British Pound","pricing":"trade","current":true,"history":true,"rate_age_sec":null},
            {"code":"ZZZ","name":"Ghost","pricing":"derived","current":false,"history":false,"rate_age_sec":1}
          ]
        }
    """.trimIndent()

    @Test fun parsesAndOrdersWithUsdFirst() {
        val list = CurrencyCatalog.parse(body)
        assertNotNull(list)
        list!!
        // current=false entries are dropped → 3 remain.
        assertEquals(3, list.size)
        // USD pinned first; history-capable GBP before derived AED.
        assertEquals("USD", list[0].code)
        assertEquals("GBP", list[1].code)
        assertEquals("AED", list[2].code)
    }

    @Test fun fillsBlankNameFromBundledTable() {
        val list = CurrencyCatalog.parse(body)!!
        val aed = list.first { it.code == "AED" }
        // Upstream name was "" → bundled ISO name fills in.
        assertEquals("UAE Dirham", aed.name)
        assertFalse(aed.hasHistory)
    }

    @Test fun keepsUpstreamNameWhenPresent() {
        val list = CurrencyCatalog.parse(body)!!
        assertEquals("US Dollar", list.first { it.code == "USD" }.name)
        assertTrue(list.first { it.code == "USD" }.hasHistory)
    }

    @Test fun parseReturnsNullOnGarbage() {
        assertNull(CurrencyCatalog.parse("not json"))
        assertNull(CurrencyCatalog.parse("""{"nope": 1}"""))
    }

    @Test fun bundledCatalogIsComplete() {
        val all = CurrencyCatalog.defaultCatalog()
        // 126 codes are bundled; sanity-check the count and ordering.
        assertEquals(126, all.size)
        assertEquals("USD", all[0].code)
        assertEquals("EUR", all[1].code)
        // The seven trade currencies must be flagged history-capable.
        val history = all.filter { it.hasHistory }.map { it.code }.toSet()
        assertEquals(setOf("USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD"), history)
    }

    @Test fun routingPredicates() {
        assertTrue(CurrencyCatalog.isSummaryCurrency("USD"))
        assertTrue(CurrencyCatalog.isSummaryCurrency("eur"))
        assertFalse(CurrencyCatalog.isSummaryCurrency("GBP"))

        assertTrue(CurrencyCatalog.isExtendedCurrency("GBP"))
        assertTrue(CurrencyCatalog.isExtendedCurrency("inr"))
        assertFalse(CurrencyCatalog.isExtendedCurrency("USD"))
        assertFalse(CurrencyCatalog.isExtendedCurrency("EUR"))
        assertFalse(CurrencyCatalog.isExtendedCurrency("BTC"))
        assertFalse(CurrencyCatalog.isExtendedCurrency("SATS"))
        assertFalse(CurrencyCatalog.isExtendedCurrency("BLOCK"))
    }

    @Test fun historyFlags() {
        assertTrue(CurrencyCatalog.hasHistory("GBP"))
        assertTrue(CurrencyCatalog.hasHistory("jpy"))
        assertFalse(CurrencyCatalog.hasHistory("INR"))
        assertFalse(CurrencyCatalog.hasHistory("AED"))
    }

    @Test fun symbolsKnownAndFallback() {
        assertEquals("£", CurrencyCatalog.symbolFor("GBP"))
        assertEquals("¥", CurrencyCatalog.symbolFor("JPY"))
        assertEquals("₹", CurrencyCatalog.symbolFor("INR"))
        // No unambiguous glyph → the code itself is the prefix.
        assertEquals("CZK", CurrencyCatalog.symbolFor("CZK"))
        assertEquals("AED", CurrencyCatalog.symbolFor("aed"))
    }
}
