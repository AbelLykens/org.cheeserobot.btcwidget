package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches the 7-day BTC price history from cheeserobot.org used to draw
 * the optional faint sparkline behind the price text.
 *
 * Upstream shape (JSON array, oldest -> newest, ~4-hour spacing):
 *   [
 *     {"when_unix": 1777449600, "price_usd": 77567, "price_eur": 66270},
 *     {"when_unix": 1777464000, "price_usd": 76733, "price_eur": 65617},
 *     ...
 *   ]
 *
 * This is a *non-essential* feature. A failure here never breaks the
 * widget; the caller falls back to the static background.
 *
 * The endpoint is updated server-side at most every couple of hours, so
 * callers should also throttle requests on their end. See
 * [BitcoinPriceWidgetProvider]'s hourly cap.
 */
sealed class HistoryResult {
    data class Success(
        val points: List<HistoryPoint>,
        /** Raw body kept around so the caller can re-parse from cache later. */
        val rawBody: String,
    ) : HistoryResult()
    data class Error(val reason: String, val cause: Throwable? = null) : HistoryResult()
}

/** One sample from the 7-day series. Either price may be null if the
 *  upstream skipped that field for the row, but the typical feed has
 *  both populated. */
data class HistoryPoint(
    val whenUnix: Long,
    val usd: Double?,
    val eur: Double?,
)

object HistoryFetcher {

    private const val TAG = "CheeseBTC"
    private const val URL_STR = "https://cheeserobot.org/price/price-hist-7d.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val SNIPPET_MAX = 160

    /** Fetch and parse the 7-day series. MUST be called off the main thread. */
    fun fetchHistory(): HistoryResult {
        val raw = try {
            fetchRaw()
        } catch (t: Throwable) {
            Log.w(TAG, "History network fetch failed", t)
            return HistoryResult.Error(
                "Network: ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                t,
            )
        }

        if (raw is FetchOutcome.HttpError) {
            Log.w(TAG, "History HTTP ${raw.code}: ${raw.snippet}")
            return HistoryResult.Error("HTTP ${raw.code}: ${raw.snippet}")
        }
        val body = (raw as FetchOutcome.Body).text
        return parse(body)
    }

    /** Pure parser, factored out so unit tests don't need a network stack. */
    internal fun parse(body: String): HistoryResult {
        val arr = try {
            JSONArray(body)
        } catch (t: Throwable) {
            val snippet = body.take(SNIPPET_MAX).replace('\n', ' ')
            Log.w(TAG, "History JSON parse failed; body starts with: $snippet", t)
            return HistoryResult.Error("Bad JSON: $snippet", t)
        }
        val out = ArrayList<HistoryPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val ts = readLong(obj, "when_unix") ?: continue
            val usd = readDouble(obj, "price_usd")
            val eur = readDouble(obj, "price_eur")
            // Skip rows that don't carry at least one price; nothing to plot.
            if (usd == null && eur == null) continue
            out.add(HistoryPoint(ts, usd, eur))
        }
        return if (out.isEmpty()) HistoryResult.Error("Empty series")
        else HistoryResult.Success(out, body)
    }

    /**
     * Project a series down to a plain `DoubleArray` of values for the
     * given currency, in chronological order. SATS rides on USD and is
     * inverted by the caller, so this only handles "USD"/"EUR".
     *
     * Rows missing the currency are dropped rather than zero-filled so
     * the line doesn't dive to zero on partial data.
     */
    fun seriesFor(points: List<HistoryPoint>, currency: String): DoubleArray {
        val ccy = currency.uppercase(Locale.ROOT)
        val list = ArrayList<Double>(points.size)
        for (p in points) {
            val v = when (ccy) {
                "EUR" -> p.eur
                else -> p.usd
            } ?: continue
            if (v.isFinite()) list.add(v)
        }
        return list.toDoubleArray()
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

    private fun readLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    private fun readDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.opt(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}
