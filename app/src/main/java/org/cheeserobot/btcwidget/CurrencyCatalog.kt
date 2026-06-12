package org.cheeserobot.btcwidget

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The set of fiat currencies the widget can display, discovered from the
 * backend's `/api/currencies/` endpoint and cached locally so the config
 * screen's picker can be populated instantly (and offline).
 *
 * Upstream shape (`GET /api/currencies/`):
 *
 *   {
 *     "anchor": "USD",
 *     "history_max_days": 14,
 *     "count": 126,
 *     "currencies": [
 *       {"code":"USD","name":"US Dollar","pricing":"trade","current":true,
 *        "history":true,"rate_age_sec":null},
 *       {"code":"AED","name":"","pricing":"derived","current":true,
 *        "history":false,"rate_age_sec":232},
 *       ...
 *     ]
 *   }
 *
 * Two facts matter to the rest of the app:
 *   - **code**  — the 3-letter ISO code we pass to `/api/current/` and
 *                 `/api/candles/`.
 *   - **history** — whether OHLC candle history exists. Only the seven
 *                 "trade" currencies (USD, EUR, GBP, JPY, CHF, AUD, CAD)
 *                 carry it; everything else is "derived" and shows the
 *                 current price only (no sparkline, no change indicator).
 *
 * The backend only ships display names for the trade currencies (the
 * derived ones come through with `name:""`). To keep the picker readable
 * we fall back to a bundled ISO-4217 name table ([NAMES]) and only use the
 * upstream name when it's non-blank.
 */
data class CurrencyInfo(
    val code: String,
    val name: String,
    val hasHistory: Boolean,
) {
    /** "USD — US Dollar", or just "USD" when no friendly name is known. */
    fun label(): String = if (name.isBlank()) code else "$code — $name"
}

object CurrencyCatalog {

    private const val TAG = "CheeseBTC"
    private const val SNIPPET_MAX = 160

    /**
     * Codes that the widget handles through its own dedicated render path
     * rather than the generic extended-currency fetch:
     *   - USD / EUR ride the unified `/price/summary.json` payload (one
     *     fetch already carries both, plus history and the block snapshot).
     *   - BTC / SATS / BLOCK are "special" display modes, not real fiats.
     * Everything else in the catalog goes through [ExtendedFetcher].
     */
    private val SUMMARY_CODES = setOf(
        WidgetPrefs.CURRENCY_USD,
        WidgetPrefs.CURRENCY_EUR,
    )

    /** True for a code the legacy summary.json pipeline serves directly. */
    fun isSummaryCurrency(code: String): Boolean =
        SUMMARY_CODES.contains(code.uppercase(Locale.ROOT))

    /**
     * True for a real catalog fiat that is NOT handled by the summary
     * pipeline — i.e. one the provider must service via [ExtendedFetcher].
     * The special display modes (BTC/SATS/BLOCK) return false.
     */
    fun isExtendedCurrency(code: String): Boolean {
        val c = code.uppercase(Locale.ROOT)
        if (isSummaryCurrency(c)) return false
        return when (c) {
            WidgetPrefs.CURRENCY_BTC,
            WidgetPrefs.CURRENCY_SATS,
            WidgetPrefs.CURRENCY_BLOCK -> false
            else -> true
        }
    }

    /** Whether [code] is one of the seven currencies that carry candle history. */
    fun hasHistory(code: String): Boolean {
        val c = code.uppercase(Locale.ROOT)
        return HISTORY_CODES.contains(c) || (infoFor(c)?.hasHistory == true)
    }

    /** Look up a single currency in the (cached or bundled) catalog. */
    fun infoFor(code: String): CurrencyInfo? {
        val c = code.uppercase(Locale.ROOT)
        return defaultCatalog().firstOrNull { it.code == c }
    }

    /**
     * Parse the `/api/currencies/` body into the list the picker uses.
     * Falls back to the upstream `name` only when present; otherwise the
     * bundled ISO name fills in. Sorted with the history-capable currencies
     * first (USD, EUR, then the rest alphabetically) so the most useful
     * picks sit at the top of the list.
     *
     * Returns null on a parse failure so the caller can fall back to the
     * bundled [defaultCatalog].
     */
    fun parse(body: String): List<CurrencyInfo>? {
        val root = try {
            JSONObject(body)
        } catch (t: Throwable) {
            val snippet = body.take(SNIPPET_MAX).replace('\n', ' ')
            Log.w(TAG, "Currencies JSON parse failed; body starts with: $snippet", t)
            return null
        }
        val arr = root.optJSONArray("currencies") ?: return null
        val out = ArrayList<CurrencyInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val code = obj.optString("code", "").trim().uppercase(Locale.ROOT)
            if (code.isEmpty()) continue
            // Only surface currencies that actually have a current price —
            // a catalog entry with current=false would render "—" forever.
            if (obj.has("current") && !obj.optBoolean("current", true)) continue
            val upstreamName = obj.optString("name", "").trim()
            val name = if (upstreamName.isNotEmpty()) upstreamName else (NAMES[code] ?: "")
            val history = obj.optBoolean("history", false)
            out.add(CurrencyInfo(code, name, history))
        }
        if (out.isEmpty()) return null
        return sortForPicker(out)
    }

    /**
     * Order for the picker: USD and EUR pinned to the very top (the
     * historical default offering), then the remaining history-capable
     * currencies, then everyone else — each group alphabetical by code.
     */
    fun sortForPicker(list: List<CurrencyInfo>): List<CurrencyInfo> {
        fun rank(c: CurrencyInfo): Int = when (c.code) {
            WidgetPrefs.CURRENCY_USD -> 0
            WidgetPrefs.CURRENCY_EUR -> 1
            else -> if (c.hasHistory) 2 else 3
        }
        return list.sortedWith(compareBy({ rank(it) }, { it.code }))
    }

    /**
     * The catalog used when no network fetch has succeeded yet. Built from
     * the bundled [NAMES] table so the picker is fully populated on first
     * launch and offline. The seven [HISTORY_CODES] are flagged as
     * history-capable to match the live backend.
     */
    fun defaultCatalog(): List<CurrencyInfo> {
        val out = ArrayList<CurrencyInfo>(NAMES.size)
        for ((code, name) in NAMES) {
            out.add(CurrencyInfo(code, name, HISTORY_CODES.contains(code)))
        }
        return sortForPicker(out)
    }

    /** Currencies the backend exposes OHLC candle history for. */
    val HISTORY_CODES = setOf("USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD")

    /**
     * Currency-symbol prefixes shown in front of the price (e.g. "£ 47,304").
     * Only unambiguous, widely-recognised glyphs are listed; codes that
     * share a glyph with many others (the various dollars/pesos) are left
     * out so the 3-letter code is shown instead, which reads less
     * ambiguously on a home-screen widget.
     */
    private val SYMBOLS = mapOf(
        "USD" to "$",
        "EUR" to "€",
        "GBP" to "£",
        "JPY" to "¥",
        "CNY" to "¥",
        "INR" to "₹",
        "KRW" to "₩",
        "RUB" to "₽",
        "TRY" to "₺",
        "ILS" to "₪",
        "THB" to "฿",
        "VND" to "₫",
        "UAH" to "₴",
        "NGN" to "₦",
        "PHP" to "₱",
        "PKR" to "₨",
        "LKR" to "₨",
        "NPR" to "₨",
        "BDT" to "৳",
        "KZT" to "₸",
        "GEL" to "₾",
        "PYG" to "₲",
        "LBP" to "ل.ل",
        "KHR" to "៛",
        "AZN" to "₼",
    )

    /**
     * Prefix to render before the price for [code]. Returns a recognised
     * currency glyph when one exists, otherwise the upper-cased code itself
     * (e.g. "CZK 1,500,000") — never empty, so an extended-currency widget
     * always carries a visible currency marker.
     */
    fun symbolFor(code: String): String {
        val c = code.uppercase(Locale.ROOT)
        return SYMBOLS[c] ?: c
    }

    /**
     * Bundled ISO-4217 (and a few backend-specific) display names. Keys are
     * the upper-case codes the backend returns from `/api/currencies/`.
     * Used both to build [defaultCatalog] and to fill in names the backend
     * leaves blank for derived currencies.
     */
    val NAMES: Map<String, String> = linkedMapOf(
        "AED" to "UAE Dirham",
        "ALL" to "Albanian Lek",
        "ANG" to "Netherlands Antillean Guilder",
        "AOA" to "Angolan Kwanza",
        "ARS" to "Argentine Peso",
        "AUD" to "Australian Dollar",
        "AWG" to "Aruban Florin",
        "AZN" to "Azerbaijani Manat",
        "BAM" to "Bosnia-Herzegovina Convertible Mark",
        "BBD" to "Barbadian Dollar",
        "BDT" to "Bangladeshi Taka",
        "BHD" to "Bahraini Dinar",
        "BIF" to "Burundian Franc",
        "BMD" to "Bermudan Dollar",
        "BOB" to "Bolivian Boliviano",
        "BRL" to "Brazilian Real",
        "BSD" to "Bahamian Dollar",
        "BTN" to "Bhutanese Ngultrum",
        "BWP" to "Botswanan Pula",
        "BYN" to "Belarusian Ruble",
        "BZD" to "Belize Dollar",
        "CAD" to "Canadian Dollar",
        "CDF" to "Congolese Franc",
        "CHF" to "Swiss Franc",
        "CLP" to "Chilean Peso",
        "CNY" to "Chinese Yuan",
        "COP" to "Colombian Peso",
        "CRC" to "Costa Rican Colón",
        "CUP" to "Cuban Peso",
        "CVE" to "Cape Verdean Escudo",
        "CZK" to "Czech Koruna",
        "DJF" to "Djiboutian Franc",
        "DKK" to "Danish Krone",
        "DOP" to "Dominican Peso",
        "DZD" to "Algerian Dinar",
        "EGP" to "Egyptian Pound",
        "ERN" to "Eritrean Nakfa",
        "ETB" to "Ethiopian Birr",
        "EUR" to "Euro",
        "FKP" to "Falkland Islands Pound",
        "GBP" to "British Pound",
        "GEL" to "Georgian Lari",
        "GGP" to "Guernsey Pound",
        "GHS" to "Ghanaian Cedi",
        "GIP" to "Gibraltar Pound",
        "GMD" to "Gambian Dalasi",
        "GNF" to "Guinean Franc",
        "GTQ" to "Guatemalan Quetzal",
        "HKD" to "Hong Kong Dollar",
        "HNL" to "Honduran Lempira",
        "HUF" to "Hungarian Forint",
        "IDR" to "Indonesian Rupiah",
        "ILS" to "Israeli New Shekel",
        "IMP" to "Isle of Man Pound",
        "INR" to "Indian Rupee",
        "IRR" to "Iranian Rial",
        "IRT" to "Iranian Toman",
        "ISK" to "Icelandic Króna",
        "JEP" to "Jersey Pound",
        "JMD" to "Jamaican Dollar",
        "JOD" to "Jordanian Dinar",
        "JPY" to "Japanese Yen",
        "KES" to "Kenyan Shilling",
        "KGS" to "Kyrgystani Som",
        "KMF" to "Comorian Franc",
        "KRW" to "South Korean Won",
        "KYD" to "Cayman Islands Dollar",
        "KZT" to "Kazakhstani Tenge",
        "LBP" to "Lebanese Pound",
        "LKR" to "Sri Lankan Rupee",
        "LSL" to "Lesotho Loti",
        "MAD" to "Moroccan Dirham",
        "MGA" to "Malagasy Ariary",
        "MLC" to "Cuban MLC",
        "MOP" to "Macanese Pataca",
        "MRU" to "Mauritanian Ouguiya",
        "MWK" to "Malawian Kwacha",
        "MXN" to "Mexican Peso",
        "MYR" to "Malaysian Ringgit",
        "NAD" to "Namibian Dollar",
        "NGN" to "Nigerian Naira",
        "NIO" to "Nicaraguan Córdoba",
        "NOK" to "Norwegian Krone",
        "NPR" to "Nepalese Rupee",
        "NZD" to "New Zealand Dollar",
        "OMR" to "Omani Rial",
        "PAB" to "Panamanian Balboa",
        "PEN" to "Peruvian Sol",
        "PHP" to "Philippine Peso",
        "PKR" to "Pakistani Rupee",
        "PLN" to "Polish Złoty",
        "PYG" to "Paraguayan Guaraní",
        "QAR" to "Qatari Rial",
        "RON" to "Romanian Leu",
        "RSD" to "Serbian Dinar",
        "RUB" to "Russian Ruble",
        "RWF" to "Rwandan Franc",
        "SAR" to "Saudi Riyal",
        "SEK" to "Swedish Krona",
        "SGD" to "Singapore Dollar",
        "SHP" to "Saint Helena Pound",
        "SYP" to "Syrian Pound",
        "SZL" to "Swazi Lilangeni",
        "THB" to "Thai Baht",
        "TMT" to "Turkmenistani Manat",
        "TND" to "Tunisian Dinar",
        "TRY" to "Turkish Lira",
        "TTD" to "Trinidad & Tobago Dollar",
        "TWD" to "New Taiwan Dollar",
        "TZS" to "Tanzanian Shilling",
        "UAH" to "Ukrainian Hryvnia",
        "UGX" to "Ugandan Shilling",
        "USD" to "US Dollar",
        "UYU" to "Uruguayan Peso",
        "UZS" to "Uzbekistani Som",
        "VES" to "Venezuelan Bolívar",
        "VND" to "Vietnamese Dong",
        "XAF" to "Central African CFA Franc",
        "XAG" to "Silver (ounce)",
        "XAU" to "Gold (ounce)",
        "XCD" to "East Caribbean Dollar",
        "XCG" to "Caribbean Guilder",
        "XOF" to "West African CFA Franc",
        "XPT" to "Platinum (ounce)",
        "ZAR" to "South African Rand",
        "ZMW" to "Zambian Kwacha",
    )
}
