package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches the latest BTC price JSON from cheeserobot.org and extracts the
 * value for the requested currency, plus optional historical values
 * (1 day ago and 1 week ago).
 *
 * The current upstream shape is:
 *   {
 *     "when": "...", "when_unix": "...", "source": "...",
 *     "price_usd": "81324.99", "price_eur": "69498.08",
 *     "price_1d_ago_usd": "80325.00", "price_1d_ago_eur": "68717.13",
 *     "price_1w_ago_usd": "76177.99", "price_1w_ago_eur": "65033.52"
 *   }
 *
 * Returns a [PriceResult] so callers can distinguish *why* a fetch failed:
 * network, HTTP status, JSON shape, or missing currency key.
 *
 * The response format isn't strictly documented, so we search recursively
 * for the requested currency key (case-insensitive) and tokenise keys to
 * keep us robust against variants like:
 *   {"usd": 65000, "eur": 60000}
 *   {"USD": 65000, "EUR": 60000}
 *   {"prices": {"usd": 65000, "eur": 60000}}
 *   {"bitcoin": {"usd": 65000, "eur": 60000}}
 *   {"price_usd": "65000", "price_1d_ago_usd": "64000", ...}
 */
sealed class PriceResult {
    /**
     * Successful fetch. [price] is always populated; [priceOneDayAgo] and
     * [priceOneWeekAgo] are populated only if the upstream JSON exposes
     * them (older feeds that just have today's price still parse fine,
     * with the historical fields left null).
     */
    data class Success(
        val price: Double,
        val priceOneDayAgo: Double? = null,
        val priceOneWeekAgo: Double? = null,
    ) : PriceResult()
    data class Error(val reason: String, val cause: Throwable? = null) : PriceResult()
}

object PriceFetcher {

    private const val TAG = "CheeseBTC"
    private const val URL_STR = "https://cheeserobot.org/price/latest.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val SNIPPET_MAX = 160

    /** Period tokens we recognise inside a historical key. */
    internal const val PERIOD_1D = "1d"
    internal const val PERIOD_1W = "1w"

    /**
     * Fetches and returns the price (and historical prices when present).
     * MUST be called off the main thread.
     */
    fun fetchPrice(currency: String): PriceResult {
        val raw = try {
            fetchRaw()
        } catch (t: Throwable) {
            Log.w(TAG, "Network fetch failed", t)
            return PriceResult.Error(
                "Network: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                t
            )
        }

        if (raw is FetchOutcome.HttpError) {
            Log.w(TAG, "HTTP ${raw.code}: ${raw.snippet}")
            return PriceResult.Error("HTTP ${raw.code}: ${raw.snippet}")
        }
        val body = (raw as FetchOutcome.Body).text

        val json = try {
            JSONObject(body)
        } catch (t: Throwable) {
            val snippet = body.take(SNIPPET_MAX).replace('\n', ' ')
            Log.w(TAG, "JSON parse failed; body starts with: $snippet", t)
            return PriceResult.Error("Bad JSON: $snippet", t)
        }

        val needle = currency.lowercase(Locale.ROOT)
        val price = findCurrency(json, needle)
        return if (price != null) {
            // Historical values are best-effort — older feeds may not
            // expose them. A null result here just means "no indicator".
            val oneDay = findHistorical(json, needle, PERIOD_1D)
            val oneWeek = findHistorical(json, needle, PERIOD_1W)
            PriceResult.Success(price, oneDay, oneWeek)
        } else {
            val keys = topKeys(json)
            Log.w(TAG, "Currency $currency not found. Top-level keys: $keys")
            PriceResult.Error("No \"$currency\" in JSON. Keys: $keys")
        }
    }

    private sealed class FetchOutcome {
        data class Body(val text: String) : FetchOutcome()
        data class HttpError(val code: Int, val snippet: String) : FetchOutcome()
    }

    @Throws(Throwable::class)
    private fun fetchRaw(): FetchOutcome {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "CheeseWidget-Android/1.0")
            }
            val code = conn.responseCode
            return if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                FetchOutcome.Body(body)
            } else {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) {
                    ""
                }
                FetchOutcome.HttpError(code, errBody.take(SNIPPET_MAX).replace('\n', ' '))
            }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Find the *current* price for [currency]. Excludes keys whose tokens
     * include "ago" — those carry historical values like
     * "price_1d_ago_usd" and would otherwise shadow today's price.
     *
     * Exposed as `internal` so it can be exercised from unit tests
     * without making a network call.
     */
    internal fun findCurrency(node: Any?, currency: String): Double? {
        val needle = currency.lowercase(Locale.ROOT)
        return findByPredicate(node) { tokens ->
            needle in tokens && "ago" !in tokens
        }
    }

    /**
     * Find a historical price for [currency] [period] ago. [period] is
     * one of [PERIOD_1D] ("1d") or [PERIOD_1W] ("1w"). Looks for a key
     * whose tokens contain the currency, the period token, and the
     * literal token "ago".
     *
     * Returns null when the upstream JSON doesn't expose a matching key.
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
     * non-alphanumeric character and on lower→upper case transitions:
     *
     *   "price_1d_ago_usd" → {"price","1d","ago","usd"}
     *   "priceUsd"         → {"price","usd"}
     *   "USD"              → {"usd"}
     *   "PRICE_USD"        → {"price","usd"}
     *   "audusd"           → {"audusd"}    (no false positive for "usd")
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

    private fun topKeys(json: JSONObject): String {
        val out = mutableListOf<String>()
        val it = json.keys()
        while (it.hasNext() && out.size < 10) out.add(it.next())
        return out.joinToString(", ")
    }

    private fun coerceNumber(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
