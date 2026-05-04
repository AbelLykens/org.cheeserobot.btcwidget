package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches the latest BTC price JSON from cheeserobot.org and extracts the
 * value for the requested currency.
 *
 * Returns a [PriceResult] so callers can distinguish *why* a fetch failed:
 * network, HTTP status, JSON shape, or missing currency key.
 *
 * The response format isn't strictly documented, so we search recursively
 * for the requested currency key (case-insensitive). This keeps us robust
 * against shapes like:
 *   {"usd": 65000, "eur": 60000}
 *   {"USD": 65000, "EUR": 60000}
 *   {"prices": {"usd": 65000, "eur": 60000}}
 *   {"bitcoin": {"usd": 65000, "eur": 60000}}
 */
sealed class PriceResult {
    data class Success(val price: Double) : PriceResult()
    data class Error(val reason: String, val cause: Throwable? = null) : PriceResult()
}

object PriceFetcher {

    private const val TAG = "CheeseBTC"
    private const val URL_STR = "https://cheeserobot.org/price/latest.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val SNIPPET_MAX = 160

    /**
     * Fetches and returns the price. MUST be called off the main thread.
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

        val price = findCurrency(json, currency.lowercase(Locale.ROOT))
        return if (price != null) {
            PriceResult.Success(price)
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
     * Recursively walks the JSON tree looking for a key whose tokens contain
     * [currency] (case-insensitive). A "token" is a word formed by splitting
     * the key on non-letter characters AND on lower→upper case transitions.
     *
     * Examples that all match `currency = "usd"`:
     *   "usd", "USD", "price_usd", "usd_price", "priceUsd", "USD_PRICE",
     *   "prices.usd", "btc-usd"
     *
     * "usdoll" or "audusd" still match (the token "usd" is present once we
     * split the camelCase, and "audusd" is a single token "audusd" which
     * does NOT match — only equality after splitting counts).
     *
     * Exposed as [internal] so it can be exercised from unit tests without
     * making a network call.
     */
    internal fun findCurrency(node: Any?, currency: String): Double? {
        val needle = currency.lowercase(Locale.ROOT)
        when (node) {
            is JSONObject -> {
                // First pass: shallow match at this level.
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (keyMatchesCurrency(k, needle)) {
                        coerceNumber(node.opt(k))?.let { return it }
                    }
                }
                // Second pass: recurse into nested objects/arrays.
                val keys2 = node.keys()
                while (keys2.hasNext()) {
                    val k = keys2.next()
                    findCurrency(node.opt(k), currency)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findCurrency(node.opt(i), currency)?.let { return it }
                }
            }
        }
        return null
    }

    /** True if any token in [key] equals [needle] (already lowercased). */
    private fun keyMatchesCurrency(key: String, needle: String): Boolean {
        if (key.equals(needle, ignoreCase = true)) return true
        // Split on non-letter chars first, then on lower→upper transitions
        // so things like "priceUsd" → ["price","Usd"].
        return key.split(Regex("[^A-Za-z]"))
            .flatMap { it.split(Regex("(?<=[a-z])(?=[A-Z])")) }
            .any { it.lowercase(Locale.ROOT) == needle }
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
