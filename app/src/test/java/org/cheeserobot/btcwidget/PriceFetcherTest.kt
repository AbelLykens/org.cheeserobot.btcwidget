package org.cheeserobot.btcwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for the JSON traversal logic. No Android framework
 * needed — `org.json:json` on the test classpath provides the real
 * implementation that's stubbed out at compile-time on the device.
 */
class PriceFetcherTest {

    @Test fun flatLowercase() {
        val json = JSONObject("""{"usd": 65000, "eur": 60000.5}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
        assertEquals(60000.5, PriceFetcher.findCurrency(json, "eur")!!, 0.0)
    }

    @Test fun flatUppercase() {
        val json = JSONObject("""{"USD": 65000, "EUR": 60000}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    @Test fun nestedPrices() {
        val json = JSONObject("""{"prices": {"usd": 65000, "eur": 60000}}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    @Test fun coingeckoStyle() {
        val json = JSONObject("""{"bitcoin": {"usd": 65000, "eur": 60000}}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "USD")!!, 0.0)
    }

    @Test fun stringValueIsCoerced() {
        val json = JSONObject("""{"usd": "65000.50"}""")
        assertEquals(65000.5, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    @Test fun missingCurrencyReturnsNull() {
        val json = JSONObject("""{"gbp": 50000}""")
        assertNull(PriceFetcher.findCurrency(json, "usd"))
    }

    // The actual cheeserobot.org/price/latest.json shape — keys use
    // `price_<ccy>` and values are strings, not numbers.
    @Test fun cheeserobotShape() {
        val json = JSONObject(
            """{"when": "2026-05-04T10:58:02.777Z", "price_usd": "78875.2250", "price_eur": "67379.1550", "source": "CoinDesk", "when_unix": "1777892282"}"""
        )
        assertEquals(78875.225, PriceFetcher.findCurrency(json, "USD")!!, 0.0001)
        assertEquals(67379.155, PriceFetcher.findCurrency(json, "EUR")!!, 0.0001)
    }

    // Newer shape with 1d / 1w historical prices alongside today's.
    @Test fun cheeserobotShapeWithHistory() {
        val json = JSONObject(
            """{
                "when": "2026-05-05T17:49:03.042Z",
                "price_usd": "81324.9950",
                "price_eur": "69498.0850",
                "source": "CoinDesk",
                "when_unix": "1778003343",
                "price_1d_ago_usd": "80325.0050",
                "price_1w_ago_usd": "76177.9950",
                "price_1d_ago_eur": "68717.1350",
                "price_1w_ago_eur": "65033.5250"
            }""".trimIndent()
        )
        // Current price should NOT pick up an "_ago" key.
        assertEquals(81324.995, PriceFetcher.findCurrency(json, "USD")!!, 0.0001)
        assertEquals(69498.085, PriceFetcher.findCurrency(json, "EUR")!!, 0.0001)

        assertEquals(80325.005, PriceFetcher.findHistorical(json, "USD", "1d")!!, 0.0001)
        assertEquals(76177.995, PriceFetcher.findHistorical(json, "USD", "1w")!!, 0.0001)
        assertEquals(68717.135, PriceFetcher.findHistorical(json, "EUR", "1d")!!, 0.0001)
        assertEquals(65033.525, PriceFetcher.findHistorical(json, "EUR", "1w")!!, 0.0001)
    }

    // If the key order is reversed so ago-keys come *before* current
    // keys, the "ago" exclusion still keeps us from grabbing a stale
    // value as today's price.
    @Test fun agoKeyDoesNotShadowCurrent() {
        val json = JSONObject(
            """{"price_1d_ago_usd": "80000.00", "price_usd": "81000.00"}"""
        )
        val current = PriceFetcher.findCurrency(json, "usd")!!
        assertEquals(81000.0, current, 0.0001)
        assertNotEquals(80000.0, current, 0.0001)
    }

    @Test fun historicalReturnsNullWhenAbsent() {
        val json = JSONObject("""{"price_usd": "81000.00"}""")
        assertNull(PriceFetcher.findHistorical(json, "usd", "1d"))
        assertNull(PriceFetcher.findHistorical(json, "usd", "1w"))
    }

    @Test fun suffixedKey() {
        val json = JSONObject("""{"usd_price": 65000}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    @Test fun camelCaseKey() {
        val json = JSONObject("""{"priceUsd": 65000, "priceEur": 60000}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    @Test fun screamingSnakeKey() {
        val json = JSONObject("""{"PRICE_USD": 65000}""")
        assertEquals(65000.0, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    // Important: don't match a currency that's only a substring of one token.
    @Test fun substringDoesNotMatch() {
        // "audusd" is one token containing "usd" as a substring — must NOT match.
        val json = JSONObject("""{"audusd": 0.65}""")
        assertNull(PriceFetcher.findCurrency(json, "usd"))
    }

    // But the same value behind a delimiter IS a separate token.
    @Test fun delimitedSubstringDoesMatch() {
        val json = JSONObject("""{"aud_usd": 0.65}""")
        assertEquals(0.65, PriceFetcher.findCurrency(json, "usd")!!, 0.0)
    }

    @Test fun tokeniseHistoricalKey() {
        val tokens = PriceFetcher.tokens("price_1d_ago_usd")
        assertEquals(setOf("price", "1d", "ago", "usd"), tokens)
    }

    @Test fun tokeniseScreamingSnake() {
        val tokens = PriceFetcher.tokens("PRICE_USD")
        assertEquals(setOf("price", "usd"), tokens)
    }
}
