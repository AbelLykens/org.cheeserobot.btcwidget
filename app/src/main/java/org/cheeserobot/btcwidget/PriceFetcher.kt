package org.cheeserobot.btcwidget

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Per-currency rendering result used throughout the widget pipeline.
 *
 * The historical fetch path used to live in this file (`PriceFetcher.fetchPrice`,
 * `fetchPrices`, `fetchRaw`, etc.) but the v3 consolidation onto the unified
 * `/price/summary.json` endpoint moved every network call into [SummaryFetcher].
 * What remains here is the [PriceResult] shape that the renderer consumes and
 * the small set of JSON-key heuristics that lets [SummaryFetcher.Summary]
 * pull a currency value out of arbitrarily-shaped backend payloads.
 *
 * The key search is intentionally lenient — keys can be cased any way,
 * delimited any way, or wrapped in nested objects:
 *   {"usd": 65000, "eur": 60000}
 *   {"USD": 65000, "EUR": 60000}
 *   {"prices": {"usd": 65000, "eur": 60000}}
 *   {"bitcoin": {"usd": 65000, "eur": 60000}}
 *   {"price_usd": "65000", "price_1d_ago_usd": "64000", ...}
 */
sealed class PriceResult {
    data class Success(
        val price: Double,
        val priceOneDayAgo: Double? = null,
        val priceOneWeekAgo: Double? = null,
    ) : PriceResult()
    data class Error(val reason: String, val cause: Throwable? = null) : PriceResult()
}

object PriceFetcher {

    /**
     * Find the *current* price for [currency]. Excludes keys whose
     * tokens include "ago" so that "price_1d_ago_usd" can never shadow
     * today's "price_usd".
     */
    internal fun findCurrency(node: Any?, currency: String): Double? {
        val needle = currency.lowercase(Locale.ROOT)
        return findByPredicate(node) { tokens ->
            needle in tokens && "ago" !in tokens
        }
    }

    /**
     * Find a historical price for [currency] [period] ago. [period] is
     * a short token like "1d" or "1w". Looks for a key whose tokens
     * contain the currency, the period token, and "ago". Returns null
     * when the upstream JSON doesn't expose a matching key.
     */
    internal fun findHistorical(node: Any?, currency: String, period: String): Double? {
        val needle = currency.lowercase(Locale.ROOT)
        val periodLower = period.lowercase(Locale.ROOT)
        return findByPredicate(node) { tokens ->
            needle in tokens && periodLower in tokens && "ago" in tokens
        }
    }

    /**
     * Walk the JSON tree, returning the first numeric value whose key
     * passes [pred] applied to the key's token set. Does a shallow scan
     * at each level first so a top-level match isn't shadowed by a
     * deeper one.
     */
    private fun findByPredicate(node: Any?, pred: (Set<String>) -> Boolean): Double? {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (pred(tokens(k))) coerceNumber(node.opt(k))?.let { return it }
                }
                val keys2 = node.keys()
                while (keys2.hasNext()) {
                    val k = keys2.next()
                    findByPredicate(node.opt(k), pred)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findByPredicate(node.opt(i), pred)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * Tokenise a key into a lower-cased set of tokens. Splits on any
     * non-alphanumeric character and on lower->upper case transitions:
     *
     *   "price_1d_ago_usd" -> {"price","1d","ago","usd"}
     *   "priceUsd"         -> {"price","usd"}
     *   "USD"              -> {"usd"}
     *   "PRICE_USD"        -> {"price","usd"}
     *   "audusd"           -> {"audusd"}    (no false positive for "usd")
     */
    internal fun tokens(key: String): Set<String> {
        val out = mutableSetOf<String>()
        for (raw in key.split(Regex("[^A-Za-z0-9]"))) {
            if (raw.isEmpty()) continue
            for (sub in raw.split(Regex("(?<=[a-z])(?=[A-Z])"))) {
                if (sub.isNotEmpty()) out.add(sub.lowercase(Locale.ROOT))
            }
        }
        return out
    }

    private fun coerceNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
