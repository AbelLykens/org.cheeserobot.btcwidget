package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Parser for the BTC price-history arrays that ride inside the unified
 * `/price/summary.json` payload ([SummaryFetcher.Summary.hist1dJson] and
 * `.hist7dJson`). The dedicated `/price/price-hist-{1d,7d}.json` endpoints
 * — and the network code that hit them — were retired in v2.8 once the
 * summary endpoint started carrying both windows. What's left here is the
 * pure parser the rendering pipeline calls each time it builds a sparkline
 * from the cached JSON body.
 *
 * Upstream shape (JSON array, oldest -> newest):
 *   [
 *     {"when_unix": 1777449600, "price_usd": 77567, "price_eur": 66270},
 *     {"when_unix": 1777464000, "price_usd": 76733, "price_eur": 65617},
 *     ...
 *   ]
 */
sealed class HistoryResult {
    data class Success(
        val points: List<HistoryPoint>,
        /** Raw body kept around so the caller can re-parse from cache later. */
        val rawBody: String,
    ) : HistoryResult()
    data class Error(val reason: String, val cause: Throwable? = null) : HistoryResult()
}

/** One sample from the price-history series. Either price may be null if the
 *  upstream skipped that field for the row, but the typical feed has
 *  both populated. */
data class HistoryPoint(
    val whenUnix: Long,
    val usd: Double?,
    val eur: Double?,
)

object HistoryFetcher {

    private const val TAG = "CheeseBTC"
    private const val SNIPPET_MAX = 160

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
