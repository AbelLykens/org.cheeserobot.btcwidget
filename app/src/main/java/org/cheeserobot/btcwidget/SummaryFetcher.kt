package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches the unified `/price/summary.json` payload from cheeserobot.org.
 *
 * One HTTP round trip now covers everything the widget used to pull from
 * three different endpoints (`/price/latest.json`, `/price/price-hist-1d.json`,
 * `/price/price-hist-7d.json`) plus a latest-block snapshot. The upstream
 * shape:
 *
 *   {
 *     "price":   { price_usd, price_eur, when, when_unix, source },
 *     "hist_1d": [ { when_unix, price_usd, price_eur }, ... ],   // ~25 hourly points
 *     "hist_7d": [ { when_unix, price_usd, price_eur }, ... ],   // ~43 4-hourly points
 *     "latest_block": { height, hash, time, nTx, size, miner_name }
 *   }
 *
 * The fetcher returns the components in a shape that lets the rest of the
 * app keep working without restructuring its caches:
 *
 *   - [Summary.priceJson]   - the inner `price` object re-serialised so
 *                             [PriceFetcher.findCurrency] / .findHistorical
 *                             continue to work unchanged.
 *   - [Summary.hist1dJson]  - the inner `hist_1d` array re-serialised; this
 *                             is the same array shape the chart code already
 *                             knows how to parse via [HistoryFetcher.parse].
 *   - [Summary.hist7dJson]  - same, for the 7-day window.
 *   - [Summary.latestBlock] - parsed block snapshot for the new "Block"
 *                             widget mode.
 *
 * Failure semantics mirror [PriceFetcher]: a network/HTTP/JSON error
 * propagates as a single [Result.Error], leaving callers free to fall back
 * to cached state.
 *
 * MUST be called off the main thread.
 */
sealed class SummaryResult {
    data class Success(val summary: Summary) : SummaryResult()
    data class Error(val reason: String, val cause: Throwable? = null) : SummaryResult()
}

/**
 * Block snapshot pulled from `summary.latest_block`. Only the fields the
 * widget actually renders are kept; the upstream shape is wider but the
 * extra keys (hash, nTx, size) aren't needed on the home screen and are
 * left out of prefs to keep the cache small.
 */
data class LatestBlock(
    val height: Long,
    val minerName: String?,
    val time: String?,
)

data class Summary(
    val priceJson: String,
    val hist1dJson: String,
    val hist7dJson: String,
    val latestBlock: LatestBlock?,
) {
    /**
     * Convenience: extract the current price for [currency] (USD/EUR)
     * from the embedded price block. Returns null when the upstream
     * didn't carry that key. SATS is computed at the call site from the
     * USD price, same as before — the summary endpoint only ships the
     * fiat values.
     */
    fun currentPrice(currency: String): Double? {
        val obj = try { JSONObject(priceJson) } catch (_: Throwable) { return null }
        return PriceFetcher.findCurrency(obj, currency)
    }

    /**
     * First (oldest) price in `hist_1d` for [currency]. Becomes the
     * "1 day ago" reference for the change indicator. Returns null when
     * the array is empty or [currency] isn't carried.
     */
    fun oneDayAgoPrice(currency: String): Double? = firstHistPrice(hist1dJson, currency)

    /** First (oldest) price in `hist_7d` for [currency]. */
    fun oneWeekAgoPrice(currency: String): Double? = firstHistPrice(hist7dJson, currency)

    private fun firstHistPrice(arrJson: String, currency: String): Double? {
        val arr = try { JSONArray(arrJson) } catch (_: Throwable) { return null }
        if (arr.length() == 0) return null
        val first = arr.optJSONObject(0) ?: return null
        val key = when (currency.uppercase(Locale.ROOT)) {
            "EUR" -> "price_eur"
            else -> "price_usd"
        }
        return when (val v = first.opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}

/**
 * Outcome of a "is this URL a compatible price backend?" check, used by
 * the configuration screen before persisting a user-supplied override.
 *
 * [Valid] means we fetched, parsed, AND found every field the widget
 * actually reads (current USD+EUR price, both history arrays, the
 * latest-block snapshot). [Invalid] carries a short, user-facing
 * reason so the UI can surface "Missing price_usd" or similar rather
 * than the bare exception text.
 */
sealed class BackendValidationResult {
    object Valid : BackendValidationResult()
    data class Invalid(val reason: String) : BackendValidationResult()
}

object SummaryFetcher {

    private const val TAG = "CheeseBTC"
    /**
     * Bundled default endpoint. Mirrored in
     * [WidgetPrefs.DEFAULT_BACKEND_URL] for the config-screen reset
     * button — keep these two in sync if the upstream ever moves.
     */
    const val DEFAULT_URL = "https://price.cheeserobot.org/price/summary.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val SNIPPET_MAX = 160

    /**
     * Fetch the unified summary from [url] (or [DEFAULT_URL] when the
     * caller doesn't care). Per-call URL lets the widget honour a
     * user-supplied custom backend without threading the value through
     * every layer of the rendering pipeline.
     */
    fun fetchSummary(url: String = DEFAULT_URL): SummaryResult {
        val raw = try {
            fetchRaw(url)
        } catch (t: Throwable) {
            Log.w(TAG, "Summary network fetch failed", t)
            return SummaryResult.Error(
                "Network: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                t,
            )
        }
        if (raw is FetchOutcome.HttpError) {
            Log.w(TAG, "Summary HTTP ${raw.code}: ${raw.snippet}")
            return SummaryResult.Error("HTTP ${raw.code}: ${raw.snippet}")
        }
        val body = (raw as FetchOutcome.Body).text
        return parse(body)
    }

    /**
     * Validate that [url] serves a payload the widget can actually use.
     *
     * Stricter than [fetchSummary]: we don't just need the response to
     * parse, we need every field the rendering pipeline actually reads —
     * both fiat prices, both history arrays, and a latest_block snapshot.
     * That way the "Save" path can refuse a partially-compatible backend
     * outright instead of letting the widget visibly fall over later for
     * (say) BLOCK-mode users on a feed that only ships prices.
     *
     * MUST be called off the main thread.
     */
    fun validate(url: String): BackendValidationResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return BackendValidationResult.Invalid("URL is empty")
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return BackendValidationResult.Invalid("URL must start with http:// or https://")
        }
        val result = fetchSummary(trimmed)
        if (result is SummaryResult.Error) {
            return BackendValidationResult.Invalid(result.reason)
        }
        val s = (result as SummaryResult.Success).summary

        // Current prices. Both USD and EUR must be present — the widget
        // offers each as a top-level currency, so a feed missing either
        // would be subtly broken for half the users.
        if (s.currentPrice("USD") == null) {
            return BackendValidationResult.Invalid("Missing or non-numeric price_usd")
        }
        if (s.currentPrice("EUR") == null) {
            return BackendValidationResult.Invalid("Missing or non-numeric price_eur")
        }

        // History windows. The chart + change indicator both rely on these
        // arrays being populated, so we require at least one usable point
        // in each. We re-use the existing HistoryFetcher parser so the
        // rule matches what the rendering pipeline will read at runtime.
        val parsed1d = HistoryFetcher.parse(s.hist1dJson)
        if (parsed1d !is HistoryResult.Success || parsed1d.points.isEmpty()) {
            return BackendValidationResult.Invalid("hist_1d array missing or empty")
        }
        val parsed7d = HistoryFetcher.parse(s.hist7dJson)
        if (parsed7d !is HistoryResult.Success || parsed7d.points.isEmpty()) {
            return BackendValidationResult.Invalid("hist_7d array missing or empty")
        }

        // Latest block snapshot. Required for BLOCK-mode widgets — without
        // it the user would pick "Block height" in the picker and see
        // "No latest_block in summary" on screen instead of a number.
        if (s.latestBlock == null) {
            return BackendValidationResult.Invalid("Missing latest_block.height")
        }

        return BackendValidationResult.Valid
    }

    /**
     * Pure parser, factored out so unit tests can exercise the JSON
     * shape without a network stack. Public so [BitcoinPriceWidgetProvider]
     * can also re-parse a cached body if we ever decide to persist the
     * raw summary (we currently break it into its component caches).
     */
    fun parse(body: String): SummaryResult {
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            val snippet = body.take(SNIPPET_MAX).replace('\n', ' ')
            Log.w(TAG, "Summary JSON parse failed; body starts with: $snippet", t)
            return SummaryResult.Error("Bad JSON: $snippet", t)
        }
        // The upstream guarantees `price`, `hist_1d`, `hist_7d` and
        // `latest_block` as top-level keys. We treat any of them being
        // absent as a soft failure for that component (chart hides /
        // block widget reads "—") rather than a hard fetch error,
        // because we still want the price text to keep updating even if
        // the server temporarily drops one of the auxiliary keys.
        val priceObj = root.optJSONObject("price")
            ?: return SummaryResult.Error("Missing \"price\" object")
        val priceJson = priceObj.toString()

        val hist1dJson = (root.optJSONArray("hist_1d") ?: JSONArray()).toString()
        val hist7dJson = (root.optJSONArray("hist_7d") ?: JSONArray()).toString()

        val block = root.optJSONObject("latest_block")?.let { parseBlock(it) }

        return SummaryResult.Success(
            Summary(
                priceJson = priceJson,
                hist1dJson = hist1dJson,
                hist7dJson = hist7dJson,
                latestBlock = block,
            )
        )
    }

    private fun parseBlock(obj: JSONObject): LatestBlock? {
        val height = when (val v = obj.opt("height")) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: return null
            else -> return null
        }
        val miner = obj.optString("miner_name", "").takeIf { it.isNotBlank() }
        val time = obj.optString("time", "").takeIf { it.isNotBlank() }
        return LatestBlock(height = height, minerName = miner, time = time)
    }

    private sealed class FetchOutcome {
        data class Body(val text: String) : FetchOutcome()
        data class HttpError(val code: Int, val snippet: String) : FetchOutcome()
    }

    @Throws(Throwable::class)
    private fun fetchRaw(urlStr: String): FetchOutcome {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
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
}
