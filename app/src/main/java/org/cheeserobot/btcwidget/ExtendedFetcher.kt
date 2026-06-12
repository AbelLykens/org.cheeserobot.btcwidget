package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Network + parsing layer for the per-currency price feed that backs every
 * fiat the widget offers beyond USD / EUR. Those two keep riding the bare
 * `/price/summary.json` (which carries both at once and stays compatible
 * with custom pricemon backends); every other catalog currency is served by
 * the **parameterised** form:
 *
 *   GET /price/summary.json?currency={code}
 *
 * which returns the *same* envelope as the bare endpoint — current price,
 * a 24-hour history array, a 7-day history array, and the latest-block
 * snapshot — only keyed generically (`"price"`) instead of `price_usd` /
 * `price_eur`, with the requested code echoed back in `price.currency`:
 *
 *   {
 *     "price":   {"currency":"AUD","price":"90486.35","when_unix":"…", …},
 *     "hist_1d": [{"when_unix":…, "price":89814}, …],   // ~25 hourly points
 *     "hist_7d": [{"when_unix":…, "price":85277}, …],   // up to 14 days, 4-hourly
 *     "latest_block": { … }
 *   }
 *
 * Because the server applies its FX rate across the whole BTC-USD history,
 * **every** currency comes back with both history windows — there's no
 * "derived, price-only" case to special-case any more.
 *
 * The catalog of available codes still comes from `/api/currencies/`
 * ([fetchCatalogBody]); only the price/history feed moved here.
 *
 * MUST be called off the main thread.
 */
sealed class ExtendedResult {
    /**
     * @param price       current BTC price in the requested currency.
     * @param oneDayAgo   oldest point in hist_1d (the 24h-ago reference), or null.
     * @param oneWeekAgo  oldest point in hist_7d (the 7d-ago reference), or null.
     * @param hist1dJson  compact `[{"t":unix,"v":price}, …]` 24h series, or null.
     * @param hist7dJson  compact `[{"t":unix,"v":price}, …]` 7d series, or null.
     * @param sign        upstream display symbol for the currency (e.g. "£",
     *                    "kr", "CHF"), or null when the feed didn't carry one.
     *                    Authoritative over the bundled symbol table.
     */
    data class Success(
        val price: Double,
        val oneDayAgo: Double?,
        val oneWeekAgo: Double?,
        val hist1dJson: String?,
        val hist7dJson: String?,
        val sign: String? = null,
    ) : ExtendedResult()

    data class Error(val reason: String, val cause: Throwable? = null) : ExtendedResult() {
        /** Mirrors [SummaryResult.Error.isTransient]: network + HTTP 5xx retryable. */
        val isTransient: Boolean
            get() = reason.startsWith("Network:") || reason.startsWith("HTTP 5")
    }
}

/**
 * Outcome of one [ExtendedFetcher.fetchBatch] round trip: the per-code
 * results plus the `latest_block` snapshot that rides along in every
 * `summary.json` response shape. Letting the batch carry the block means
 * a home screen with BLOCK-mode widgets doesn't need a separate bare
 * summary fetch.
 */
data class BatchOutcome(
    val results: Map<String, ExtendedResult>,
    val latestBlock: LatestBlock?,
)

object ExtendedFetcher {

    private const val TAG = "CheeseBTC"
    private const val SNIPPET_MAX = 160
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    /**
     * Base host for the per-currency feed and the `/api/currencies/`
     * catalog. The custom-backend override in the config screen targets
     * pricemon-compatible bare-`summary.json` feeds, which don't implement
     * the `?currency=` parameter or the catalog — so the extended path
     * always talks to the official host regardless of that override.
     */
    const val API_BASE = "https://price.cheeserobot.org"

    fun currenciesUrl(): String = "$API_BASE/api/currencies/"

    /**
     * Server-side cap on how many comma-separated codes one
     * `?currency=` request may carry. The 11th code makes the whole
     * request fail (verified against the live endpoint), so callers
     * with more codes must chunk — [fetchBatch] does this automatically.
     */
    const val MAX_CODES_PER_REQUEST = 10

    private fun summaryUrl(codes: Collection<String>): String =
        "$API_BASE/price/summary.json?currency=${enc(codes.joinToString(","))}"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Fetch the per-currency summary for [code] and project it into the
     * shape the provider consumes. A single round trip carries the current
     * price plus both history windows.
     */
    fun fetch(code: String): ExtendedResult {
        val raw = try {
            fetchRaw(summaryUrl(listOf(code)))
        } catch (t: Throwable) {
            Log.w(TAG, "Per-currency summary fetch failed for $code", t)
            return ExtendedResult.Error(
                "Network: ${t.javaClass.simpleName}: ${t.message ?: "no message"}", t
            )
        }
        if (raw is FetchOutcome.HttpError) {
            return ExtendedResult.Error("HTTP ${raw.code}: ${raw.snippet}")
        }
        return parse((raw as FetchOutcome.Body).text)
    }

    /**
     * Fetch every code in [codes] using as few round trips as possible:
     * the `?currency=` parameter accepts up to [MAX_CODES_PER_REQUEST]
     * comma-separated codes per request (the server rejects an 11th), so
     * the deduplicated list is chunked and each chunk costs one fetch.
     * For the typical home screen — a handful of currencies — that means
     * ONE request where the old code paid one per currency.
     *
     * The result map is keyed by upper-cased code; every requested code
     * is present. Codes the server doesn't recognise are silently absent
     * from its response and map to an [ExtendedResult.Error]. A failed
     * chunk maps all of its codes to the same error so the caller's
     * retry gating can see whether the failure was transient. The
     * `latest_block` snapshot is lifted from whichever chunk carried it.
     */
    fun fetchBatch(codes: Collection<String>): BatchOutcome {
        val unique = codes.map { it.uppercase() }.distinct()
        if (unique.isEmpty()) return BatchOutcome(emptyMap(), null)
        val out = HashMap<String, ExtendedResult>(unique.size)
        var block: LatestBlock? = null
        for (chunk in unique.chunked(MAX_CODES_PER_REQUEST)) {
            val raw = try {
                fetchRaw(summaryUrl(chunk))
            } catch (t: Throwable) {
                Log.w(TAG, "Batch summary fetch failed for $chunk", t)
                val err = ExtendedResult.Error(
                    "Network: ${t.javaClass.simpleName}: ${t.message ?: "no message"}", t
                )
                chunk.forEach { out[it] = err }
                continue
            }
            if (raw is FetchOutcome.HttpError) {
                val err = ExtendedResult.Error("HTTP ${raw.code}: ${raw.snippet}")
                chunk.forEach { out[it] = err }
                continue
            }
            val env = parseMultiEnvelope((raw as FetchOutcome.Body).text, chunk)
            out.putAll(env.results)
            if (env.latestBlock != null) block = env.latestBlock
        }
        return BatchOutcome(out, block)
    }

    /**
     * [parseMulti] plus the top-level `latest_block` snapshot, which both
     * response shapes carry. Factored separately so the per-code parser
     * stays a pure map for the existing unit tests.
     */
    fun parseMultiEnvelope(body: String, requested: Collection<String>): BatchOutcome {
        val results = parseMulti(body, requested)
        val block = try {
            JSONObject(body).optJSONObject("latest_block")
                ?.let { SummaryFetcher.parseBlock(it) }
        } catch (_: Throwable) {
            null
        }
        return BatchOutcome(results, block)
    }

    /**
     * Re-assemble the LEGACY bare-summary history shape —
     * `[{when_unix, price_usd, price_eur}, …]` — from the per-currency
     * `[{t,v}]` series the multi-code endpoint returns. The merged body
     * drops straight into the existing global history cache, so the
     * USD/EUR/SATS chart + change-indicator pipeline keeps working
     * unchanged when the whole refresh rides one batched request.
     * Rows are unioned on timestamp; a currency missing at a timestamp
     * simply omits its key (the chart code already skips such rows).
     */
    fun mergeSeriesToLegacyHist(
        usd: List<Pair<Long, Double>>?,
        eur: List<Pair<Long, Double>>?,
    ): String {
        val byTime = java.util.TreeMap<Long, Array<Double?>>()
        usd?.forEach { (t, v) -> byTime.getOrPut(t) { arrayOfNulls(2) }[0] = v }
        eur?.forEach { (t, v) -> byTime.getOrPut(t) { arrayOfNulls(2) }[1] = v }
        val arr = JSONArray()
        for ((t, pair) in byTime) {
            val row = JSONObject().put("when_unix", t)
            pair[0]?.let { row.put("price_usd", it) }
            pair[1]?.let { row.put("price_eur", it) }
            arr.put(row)
        }
        return arr.toString()
    }

    // ---- Pure parsers (unit-tested without a network stack) --------------

    /**
     * Parse a `/price/summary.json?currency=…` body. The `price.price`
     * field is the current value; `hist_1d` / `hist_7d` are arrays of
     * `{when_unix, price}`. Returns an [ExtendedResult.Error] when the
     * current price is missing/non-numeric, otherwise a [Success] with both
     * history windows (each null when its array is absent/empty).
     */
    fun parse(body: String): ExtendedResult {
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            val snippet = body.take(SNIPPET_MAX).replace('\n', ' ')
            return ExtendedResult.Error("Bad JSON: $snippet", t)
        }
        val priceObj = root.optJSONObject("price")
            ?: return ExtendedResult.Error("Missing \"price\" object")
        return entryToResult(
            priceObj,
            hist1d = root.optJSONArray("hist_1d"),
            hist7d = root.optJSONArray("hist_7d"),
        )
    }

    /**
     * Parse a MULTI-code `?currency=A,B,…` body, where `price` is an
     * ARRAY of per-currency entries each carrying its own embedded
     * `hist_1d` / `hist_7d` (verified against the live endpoint). A
     * single-code body (where `price` is still the legacy OBJECT with
     * top-level history arrays) is handled too, so callers don't need
     * to special-case chunk size 1.
     *
     * Every code in [requested] is present in the returned map: codes
     * the server didn't echo back (unknown/unsupported) map to an
     * [ExtendedResult.Error]. Keys are upper-cased.
     */
    fun parseMulti(body: String, requested: Collection<String>): Map<String, ExtendedResult> {
        val want = requested.map { it.uppercase() }
        fun allErr(reason: String, cause: Throwable? = null) =
            want.associateWith { ExtendedResult.Error(reason, cause) }

        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            val snippet = body.take(SNIPPET_MAX).replace('\n', ' ')
            return allErr("Bad JSON: $snippet", t)
        }

        val out = HashMap<String, ExtendedResult>(want.size)
        when (val p = root.opt("price")) {
            is JSONObject -> {
                // Legacy single-code shape: history arrays sit at the top
                // level next to the price object.
                val code = p.optString("currency", "").uppercase()
                    .ifEmpty { want.firstOrNull() ?: "" }
                out[code] = entryToResult(
                    p,
                    hist1d = root.optJSONArray("hist_1d"),
                    hist7d = root.optJSONArray("hist_7d"),
                )
            }
            is JSONArray -> {
                for (i in 0 until p.length()) {
                    val entry = p.optJSONObject(i) ?: continue
                    val code = entry.optString("currency", "").uppercase()
                    if (code.isEmpty()) continue
                    out[code] = entryToResult(
                        entry,
                        hist1d = entry.optJSONArray("hist_1d"),
                        hist7d = entry.optJSONArray("hist_7d"),
                    )
                }
            }
            else -> return allErr("Missing \"price\" object")
        }

        // Codes the server silently dropped (unknown/unsupported) still
        // get an entry so every widget renders a deterministic outcome.
        for (code in want) {
            out.getOrPut(code) {
                ExtendedResult.Error("Currency \"$code\" not in response")
            }
        }
        return out
    }

    /**
     * Project one per-currency price entry (+ its history arrays, which
     * sit either next to it or embedded in it depending on the response
     * shape) into an [ExtendedResult].
     */
    private fun entryToResult(
        priceObj: JSONObject,
        hist1d: JSONArray?,
        hist7d: JSONArray?,
    ): ExtendedResult {
        val price = coerce(priceObj.opt("price"))
            ?: return ExtendedResult.Error("Missing or non-numeric price")

        val points1d = parseGenericHist(hist1d)
        val points7d = parseGenericHist(hist7d)

        return ExtendedResult.Success(
            price = price,
            oneDayAgo = points1d.firstOrNull()?.second,
            oneWeekAgo = points7d.firstOrNull()?.second,
            hist1dJson = if (points1d.size >= 2) encodeSeries(points1d) else null,
            hist7dJson = if (points7d.size >= 2) encodeSeries(points7d) else null,
            sign = priceObj.optString("sign", "").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Parse a generic `[{when_unix, price}, …]` history array into a
     * chronologically-ordered list of (unix-seconds, value) pairs. Rows
     * lacking a usable timestamp or price are skipped.
     */
    fun parseGenericHist(arr: JSONArray?): List<Pair<Long, Double>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<Long, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val t = coerce(obj.opt("when_unix"))?.toLong() ?: continue
            val v = coerce(obj.opt("price")) ?: continue
            if (v.isFinite()) out.add(t to v)
        }
        return out.sortedBy { it.first }
    }

    /** Encode a (time, value) series as compact `[{"t":..,"v":..}, …]` JSON. */
    fun encodeSeries(points: List<Pair<Long, Double>>): String {
        val arr = JSONArray()
        for ((t, v) in points) {
            arr.put(JSONObject().put("t", t).put("v", v))
        }
        return arr.toString()
    }

    /**
     * Decode the compact series JSON produced by [encodeSeries] back into
     * (time, value) pairs. Returns an empty list on any parse trouble.
     */
    fun decodeSeries(json: String): List<Pair<Long, Double>> {
        val arr = try { JSONArray(json) } catch (_: Throwable) { return emptyList() }
        val out = ArrayList<Pair<Long, Double>>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val t = coerce(obj.opt("t"))?.toLong() ?: continue
            val v = coerce(obj.opt("v")) ?: continue
            if (v.isFinite()) out.add(t to v)
        }
        return out
    }

    private fun coerce(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    // ---- HTTP ------------------------------------------------------------

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
                FetchOutcome.Body(conn.inputStream.bufferedReader().use { it.readText() })
            } else {
                val errBody = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) { "" }
                FetchOutcome.HttpError(code, errBody.take(SNIPPET_MAX).replace('\n', ' '))
            }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Fetch the raw `/api/currencies/` body so the caller can both parse it
     * (via [CurrencyCatalog.parse]) and cache the original JSON verbatim.
     * Returns null on any network/HTTP failure.
     */
    fun fetchCatalogBody(): String? {
        val raw = try {
            fetchRaw(currenciesUrl())
        } catch (t: Throwable) {
            Log.w(TAG, "Catalog fetch failed", t)
            return null
        }
        return (raw as? FetchOutcome.Body)?.text
    }
}
