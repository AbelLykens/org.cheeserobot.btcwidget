package org.cheeserobot.btcwidget

import android.content.Context

/**
 * Lightweight helper around SharedPreferences for per-widget settings.
 *
 * Each placed widget has its own appWidgetId; we store every setting
 * keyed by that id so multiple widgets can be configured independently.
 *
 * Per-widget settings:
 *   currency            - "USD" / "EUR" / "BTC" / "SATS"
 *   opacity             - 0..100 % background opacity (default 75)
 *   hideLogo            - bool, hide the bitcoin glyph
 *   hideUnitLabel       - bool, hide the "<amount> BTC" caption
 *   trackedAmount       - Double, default 1.0; widget displays
 *                         (trackedAmount x spot price)
 *   showDecimals        - bool, default false; when true the price is
 *                         rendered with 2 decimal places
 *   thousandsSeparator  - one of SEPARATOR_*; controls how groups of
 *                         three digits are visually separated
 *   changeIndicator     - "1D" / "1W"; selects the time window for the
 *                         red/green percentage-change line at the bottom
 *                         AND the (optional) chart background. Stored
 *                         values of "OFF" from earlier versions are
 *                         silently migrated to "1D" on read.
 *   lastSuccessAt       - epoch ms of last successful price fetch
 *   lastPriceText       - last successfully rendered price string
 *   lastRawPrice        - last successfully fetched RAW current price
 *                         (Double, no formatting). Used to compute the
 *                         change indicator without re-fetching.
 *   last1dAgoPrice      - last fetched 1-day-ago RAW price (or absent)
 *   last1wAgoPrice      - last fetched 1-week-ago RAW price (or absent)
 *   consecutiveFails    - count of consecutive fetch failures (drives
 *                         the "stale" visual state after >=2)
 *
 * Global (not per-widget):
 *   lastNetworkAt       - epoch ms of the most recent network attempt
 *                         across ALL widgets, used to rate-limit
 *                         user-triggered refreshes.
 *   lastHistory{1D,7D}At - epoch ms of the most recent successful
 *                         price-history fetch for that window. Each
 *                         endpoint is rate-limited to one fetch per
 *                         hour (the upstream JSON updates coarsely).
 *   history{1D,7D}Json   - cached body of the last successful history
 *                         fetch for that window. Re-parsed on each
 *                         render so we don't refetch every 30-min tick.
 *   latestUpstream{Usd,Eur}
 *                       - last successfully fetched RAW upstream price
 *                         (Double) for that currency, alongside its
 *                         timestamp. Used to append a "now" point on
 *                         the right edge of the chart so it always ends
 *                         at the most recent price the app knows about.
 *
 * Per-widget (sparkline):
 *   showChart           - bool, default true; toggles the optional
 *                         faint 7-day line behind the price text.
 */
object WidgetPrefs {

    private const val PREFS_NAME = "widget_prefs"
    private const val KEY_CURRENCY_PREFIX = "currency_"
    private const val KEY_OPACITY_PREFIX = "opacity_"
    private const val KEY_HIDE_LOGO_PREFIX = "hide_logo_"
    private const val KEY_HIDE_UNIT_PREFIX = "hide_unit_"
    private const val KEY_TRACKED_AMOUNT_PREFIX = "tracked_amount_"
    private const val KEY_SHOW_DECIMALS_PREFIX = "show_decimals_"
    private const val KEY_SEPARATOR_PREFIX = "separator_"
    private const val KEY_CHANGE_INDICATOR_PREFIX = "change_indicator_"
    private const val KEY_LAST_SUCCESS_PREFIX = "last_success_"
    private const val KEY_LAST_PRICE_TEXT_PREFIX = "last_price_text_"
    private const val KEY_LAST_RAW_PRICE_PREFIX = "last_raw_price_"
    private const val KEY_LAST_1D_PRICE_PREFIX = "last_1d_price_"
    private const val KEY_LAST_1W_PRICE_PREFIX = "last_1w_price_"
    private const val KEY_FAIL_COUNT_PREFIX = "fail_count_"
    private const val KEY_LAST_ERROR_PREFIX = "last_error_"
    private const val KEY_LAST_ERROR_AT_PREFIX = "last_error_at_"
    private const val KEY_LAST_NETWORK_AT_GLOBAL = "last_network_at_global"
    // The 7-day cache keys were the only history caches before v2.8;
    // their on-disk names are kept verbatim so an upgrade doesn't drop
    // the existing cached body.
    private const val KEY_LAST_HISTORY_7D_AT_GLOBAL = "last_history_at_global"
    private const val KEY_HISTORY_7D_JSON_GLOBAL = "history_json_global"
    // 24-hour history cache (v2.8+). Independent endpoint, independent
    // hourly refresh window — see HistoryFetcher and the provider's
    // maybeRefreshHistory.
    private const val KEY_LAST_HISTORY_1D_AT_GLOBAL = "last_history_1d_at_global"
    private const val KEY_HISTORY_1D_JSON_GLOBAL = "history_1d_json_global"
    // Latest fetched upstream USD / EUR price (raw, no tracked-amount
    // multiplication) plus the timestamp of that fetch. Stored globally
    // so any widget — and the chart renderer — can append a "now" point
    // without re-fetching. Saved every successful PriceFetcher round trip.
    private const val KEY_LATEST_UPSTREAM_USD_GLOBAL = "latest_upstream_usd_global"
    private const val KEY_LATEST_UPSTREAM_EUR_GLOBAL = "latest_upstream_eur_global"
    private const val KEY_LATEST_UPSTREAM_AT_GLOBAL = "latest_upstream_at_global"
    private const val KEY_SHOW_CHART_PREFIX = "show_chart_"
    private const val KEY_MOSCOW_TIME_PREFIX = "moscow_time_"
    private const val KEY_HIDE_CURRENCY_ICON_PREFIX = "hide_currency_icon_"

    const val CURRENCY_USD = "USD"
    const val CURRENCY_EUR = "EUR"

    /**
     * "For fun" currency: when selected the widget skips the network
     * call entirely and always displays "Bitcoin x trackedAmount".
     */
    const val CURRENCY_BTC = "BTC"

    /**
     * Sats per 1 USD. Derived from the USD price (100,000,000 / usd),
     * so the upstream JSON only needs the existing `price_usd` slot.
     * The widget shows the sat-symbol glyph (a packaged PNG) instead of
     * a text symbol like "$" or "€".
     */
    const val CURRENCY_SATS = "SATS"

    // Thousands-separator options.
    const val SEPARATOR_AUTO = "AUTO"
    const val SEPARATOR_COMMA = "COMMA"
    const val SEPARATOR_DOT = "DOT"
    const val SEPARATOR_SPACE = "SPACE"
    const val SEPARATOR_NONE = "NONE"

    // Price-change modes. CHANGE_OFF is no longer offered in the UI
    // (the bottom indicator is now always shown), but the constant
    // stays so older saved prefs can still be recognised and migrated.
    const val CHANGE_OFF = "OFF"
    const val CHANGE_1D = "1D"
    const val CHANGE_1W = "1W"

    const val DEFAULT_OPACITY = 75
    const val DEFAULT_HIDE_LOGO = false
    const val DEFAULT_HIDE_UNIT_LABEL = false
    const val DEFAULT_TRACKED_AMOUNT = 1.0
    const val DEFAULT_SHOW_DECIMALS = false
    const val DEFAULT_SEPARATOR = SEPARATOR_AUTO

    /**
     * Default price-change window. 24h is the more common reading and
     * matches the JSON the app is least likely to have to wait an hour
     * for — most users opening the widget in the morning want to know
     * "did it move overnight?" rather than the weekly trend.
     */
    const val DEFAULT_CHANGE_INDICATOR = CHANGE_1D
    const val DEFAULT_SHOW_CHART = true

    /**
     * Easter egg: when true, and currency is SATS and a thousands
     * separator is in use, the price (4-digit sats-per-USD value) is
     * rendered as a colon-separated "Moscow Time" — e.g. 1234 → "12:34"
     * — instead of as a numeric price. The toggle is hidden in the
     * config UI unless those two unlocking conditions are met.
     */
    const val DEFAULT_MOSCOW_TIME = false

    /**
     * When true the widget suppresses the currency marker that would
     * otherwise read in front of the price:
     *   - USD/EUR/BTC: drop the "$" / "€" / "₿" prefix from the price
     *     text so just the number renders.
     *   - SATS: hide the sat-symbol PNG (the icon slot doubles as the
     *     currency marker in SATS mode, so this flag controls it for
     *     parity with the text-prefix modes).
     *
     * Independent of [DEFAULT_HIDE_LOGO]: that flag governs the bitcoin
     * glyph specifically (irrelevant for SATS, where the slot already
     * shows the sat-symbol). Both can be set; their effects compose.
     */
    const val DEFAULT_HIDE_CURRENCY_ICON = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Currency ---------------------------------------------------------

    fun saveCurrency(context: Context, appWidgetId: Int, currency: String) {
        prefs(context).edit().putString(KEY_CURRENCY_PREFIX + appWidgetId, currency).apply()
    }

    fun loadCurrency(context: Context, appWidgetId: Int): String {
        return prefs(context).getString(KEY_CURRENCY_PREFIX + appWidgetId, CURRENCY_USD)
            ?: CURRENCY_USD
    }

    fun hasCurrency(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).contains(KEY_CURRENCY_PREFIX + appWidgetId)
    }

    // ---- Opacity ----------------------------------------------------------

    fun saveOpacity(context: Context, appWidgetId: Int, opacity: Int) {
        val clamped = opacity.coerceIn(0, 100)
        prefs(context).edit().putInt(KEY_OPACITY_PREFIX + appWidgetId, clamped).apply()
    }

    fun loadOpacity(context: Context, appWidgetId: Int): Int {
        return prefs(context).getInt(KEY_OPACITY_PREFIX + appWidgetId, DEFAULT_OPACITY)
    }

    // ---- Hide-logo --------------------------------------------------------

    fun saveHideLogo(context: Context, appWidgetId: Int, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_LOGO_PREFIX + appWidgetId, hide).apply()
    }

    fun loadHideLogo(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_LOGO_PREFIX + appWidgetId, DEFAULT_HIDE_LOGO)
    }

    // ---- Hide-unit-label --------------------------------------------------

    fun saveHideUnitLabel(context: Context, appWidgetId: Int, hide: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_UNIT_PREFIX + appWidgetId, hide).apply()
    }

    fun loadHideUnitLabel(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).getBoolean(
            KEY_HIDE_UNIT_PREFIX + appWidgetId, DEFAULT_HIDE_UNIT_LABEL
        )
    }

    // ---- Tracked amount ---------------------------------------------------

    fun saveTrackedAmount(context: Context, appWidgetId: Int, amount: Double) {
        val safe = if (amount.isFinite() && amount >= 0) amount else DEFAULT_TRACKED_AMOUNT
        prefs(context).edit()
            .putLong(KEY_TRACKED_AMOUNT_PREFIX + appWidgetId, java.lang.Double.doubleToRawLongBits(safe))
            .apply()
    }

    fun loadTrackedAmount(context: Context, appWidgetId: Int): Double {
        val key = KEY_TRACKED_AMOUNT_PREFIX + appWidgetId
        if (!prefs(context).contains(key)) return DEFAULT_TRACKED_AMOUNT
        val bits = prefs(context).getLong(key, java.lang.Double.doubleToRawLongBits(DEFAULT_TRACKED_AMOUNT))
        val value = java.lang.Double.longBitsToDouble(bits)
        return if (value.isFinite() && value >= 0) value else DEFAULT_TRACKED_AMOUNT
    }

    // ---- Show-decimals ----------------------------------------------------

    fun saveShowDecimals(context: Context, appWidgetId: Int, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DECIMALS_PREFIX + appWidgetId, show).apply()
    }

    fun loadShowDecimals(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).getBoolean(
            KEY_SHOW_DECIMALS_PREFIX + appWidgetId, DEFAULT_SHOW_DECIMALS
        )
    }

    // ---- Thousands separator ----------------------------------------------

    fun saveSeparator(context: Context, appWidgetId: Int, separator: String) {
        prefs(context).edit().putString(KEY_SEPARATOR_PREFIX + appWidgetId, separator).apply()
    }

    fun loadSeparator(context: Context, appWidgetId: Int): String {
        return prefs(context).getString(KEY_SEPARATOR_PREFIX + appWidgetId, DEFAULT_SEPARATOR)
            ?: DEFAULT_SEPARATOR
    }

    // ---- Change indicator -------------------------------------------------

    fun saveChangeIndicator(context: Context, appWidgetId: Int, mode: String) {
        // CHANGE_OFF is no longer a user-facing option — the bottom
        // indicator is always shown. We coerce any unrecognised input
        // (including legacy CHANGE_OFF passed by older callers) to the
        // 24h default so on-disk values stay within the live set.
        val normalised = when (mode.uppercase()) {
            CHANGE_1W -> CHANGE_1W
            else -> CHANGE_1D
        }
        prefs(context).edit()
            .putString(KEY_CHANGE_INDICATOR_PREFIX + appWidgetId, normalised)
            .apply()
    }

    /**
     * Returns one of [CHANGE_1D] or [CHANGE_1W]. Any legacy [CHANGE_OFF]
     * value still on disk is silently migrated to [DEFAULT_CHANGE_INDICATOR]
     * so callers never have to handle an "off" branch.
     */
    fun loadChangeIndicator(context: Context, appWidgetId: Int): String {
        val raw = prefs(context).getString(
            KEY_CHANGE_INDICATOR_PREFIX + appWidgetId, DEFAULT_CHANGE_INDICATOR
        ) ?: DEFAULT_CHANGE_INDICATOR
        return when (raw.uppercase()) {
            CHANGE_1W -> CHANGE_1W
            else -> CHANGE_1D
        }
    }

    // ---- Show-chart (optional 7-day sparkline) ---------------------------

    fun saveShowChart(context: Context, appWidgetId: Int, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CHART_PREFIX + appWidgetId, show).apply()
    }

    fun loadShowChart(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).getBoolean(
            KEY_SHOW_CHART_PREFIX + appWidgetId, DEFAULT_SHOW_CHART
        )
    }

    // ---- Moscow Time (easter egg) ----------------------------------------

    fun saveMoscowTime(context: Context, appWidgetId: Int, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOSCOW_TIME_PREFIX + appWidgetId, on).apply()
    }

    fun loadMoscowTime(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).getBoolean(
            KEY_MOSCOW_TIME_PREFIX + appWidgetId, DEFAULT_MOSCOW_TIME
        )
    }

    // ---- Hide currency icon ----------------------------------------------

    fun saveHideCurrencyIcon(context: Context, appWidgetId: Int, hide: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_CURRENCY_ICON_PREFIX + appWidgetId, hide)
            .apply()
    }

    fun loadHideCurrencyIcon(context: Context, appWidgetId: Int): Boolean {
        return prefs(context).getBoolean(
            KEY_HIDE_CURRENCY_ICON_PREFIX + appWidgetId, DEFAULT_HIDE_CURRENCY_ICON
        )
    }

    // ---- Fetch state ------------------------------------------------------

    fun recordSuccess(
        context: Context,
        appWidgetId: Int,
        epochMs: Long,
        priceText: String? = null,
        rawPrice: Double? = null,
        oneDayAgo: Double? = null,
        oneWeekAgo: Double? = null,
    ) {
        prefs(context).edit().apply {
            putLong(KEY_LAST_SUCCESS_PREFIX + appWidgetId, epochMs)
            putInt(KEY_FAIL_COUNT_PREFIX + appWidgetId, 0)
            remove(KEY_LAST_ERROR_PREFIX + appWidgetId)
            remove(KEY_LAST_ERROR_AT_PREFIX + appWidgetId)
            if (priceText != null) {
                putString(KEY_LAST_PRICE_TEXT_PREFIX + appWidgetId, priceText)
            }
            if (rawPrice != null && rawPrice.isFinite()) {
                putLong(
                    KEY_LAST_RAW_PRICE_PREFIX + appWidgetId,
                    java.lang.Double.doubleToRawLongBits(rawPrice)
                )
            }
            // Historical prices: when the upstream JSON omits them we
            // explicitly REMOVE the key so a stale value can't bleed
            // into a freshly-fetched render.
            if (oneDayAgo != null && oneDayAgo.isFinite()) {
                putLong(
                    KEY_LAST_1D_PRICE_PREFIX + appWidgetId,
                    java.lang.Double.doubleToRawLongBits(oneDayAgo)
                )
            } else {
                remove(KEY_LAST_1D_PRICE_PREFIX + appWidgetId)
            }
            if (oneWeekAgo != null && oneWeekAgo.isFinite()) {
                putLong(
                    KEY_LAST_1W_PRICE_PREFIX + appWidgetId,
                    java.lang.Double.doubleToRawLongBits(oneWeekAgo)
                )
            } else {
                remove(KEY_LAST_1W_PRICE_PREFIX + appWidgetId)
            }
        }.apply()
    }

    fun recordFailure(
        context: Context,
        appWidgetId: Int,
        reason: String? = null,
        epochMs: Long = System.currentTimeMillis()
    ) {
        val current = loadFailCount(context, appWidgetId)
        prefs(context).edit().apply {
            putInt(KEY_FAIL_COUNT_PREFIX + appWidgetId, current + 1)
            if (reason != null) {
                putString(KEY_LAST_ERROR_PREFIX + appWidgetId, reason)
                putLong(KEY_LAST_ERROR_AT_PREFIX + appWidgetId, epochMs)
            }
        }.apply()
    }

    fun loadLastError(context: Context, appWidgetId: Int): String? {
        return prefs(context).getString(KEY_LAST_ERROR_PREFIX + appWidgetId, null)
    }

    fun loadLastErrorAt(context: Context, appWidgetId: Int): Long {
        return prefs(context).getLong(KEY_LAST_ERROR_AT_PREFIX + appWidgetId, 0L)
    }

    fun loadFailCount(context: Context, appWidgetId: Int): Int {
        return prefs(context).getInt(KEY_FAIL_COUNT_PREFIX + appWidgetId, 0)
    }

    fun loadLastSuccess(context: Context, appWidgetId: Int): Long {
        return prefs(context).getLong(KEY_LAST_SUCCESS_PREFIX + appWidgetId, 0L)
    }

    fun loadLastPriceText(context: Context, appWidgetId: Int): String? {
        return prefs(context).getString(KEY_LAST_PRICE_TEXT_PREFIX + appWidgetId, null)
    }

    fun loadLastRawPrice(context: Context, appWidgetId: Int): Double? =
        loadDouble(context, KEY_LAST_RAW_PRICE_PREFIX + appWidgetId)

    fun loadLastOneDayAgo(context: Context, appWidgetId: Int): Double? =
        loadDouble(context, KEY_LAST_1D_PRICE_PREFIX + appWidgetId)

    fun loadLastOneWeekAgo(context: Context, appWidgetId: Int): Double? =
        loadDouble(context, KEY_LAST_1W_PRICE_PREFIX + appWidgetId)

    // ---- Global rate-limit ------------------------------------------------

    /**
     * Epoch ms of the last network attempt across ALL widgets. Zero
     * means "never attempted". Stored globally because the price feed
     * is a single shared resource; one fetch covers every widget.
     */
    fun loadLastNetworkAt(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_NETWORK_AT_GLOBAL, 0L)
    }

    fun markNetworkAttempt(
        context: Context,
        epochMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit().putLong(KEY_LAST_NETWORK_AT_GLOBAL, epochMs).apply()
    }

    // ---- Price-history caches (global, hourly refresh) -------------------

    /**
     * Period selectors used by the cache helpers. Match the [CHANGE_1D]
     * / [CHANGE_1W] string set so callers can pass the user's chosen
     * change-indicator mode straight through.
     */
    const val HISTORY_1D = CHANGE_1D
    const val HISTORY_7D = CHANGE_1W

    private fun atKeyFor(period: String): String = when (period.uppercase()) {
        HISTORY_1D -> KEY_LAST_HISTORY_1D_AT_GLOBAL
        else -> KEY_LAST_HISTORY_7D_AT_GLOBAL
    }

    private fun bodyKeyFor(period: String): String = when (period.uppercase()) {
        HISTORY_1D -> KEY_HISTORY_1D_JSON_GLOBAL
        else -> KEY_HISTORY_7D_JSON_GLOBAL
    }

    /** Epoch ms of the last successful history fetch for [period], or 0 if never. */
    fun loadLastHistoryAt(context: Context, period: String): Long {
        return prefs(context).getLong(atKeyFor(period), 0L)
    }

    /**
     * Store the raw JSON body alongside the timestamp so a later render
     * can re-parse without another network round trip. Bodies are small
     * (~2 KB for 24-43 points) so SharedPreferences is fine.
     */
    fun saveHistoryJson(
        context: Context,
        period: String,
        body: String,
        epochMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putString(bodyKeyFor(period), body)
            .putLong(atKeyFor(period), epochMs)
            .apply()
    }

    fun loadHistoryJson(context: Context, period: String): String? {
        return prefs(context).getString(bodyKeyFor(period), null)
    }

    // ---- Latest fetched upstream prices (global) -------------------------

    /**
     * Save the raw upstream USD/EUR price snapshot from a successful
     * fetch. Either currency may be null if the feed didn't carry it
     * (the cheeserobot.org feed always carries both today, but the
     * format isn't strictly contracted). Stored alongside [epochMs] so
     * the chart renderer can decide whether the cached "now" point is
     * recent enough to bother appending.
     */
    fun saveLatestUpstreamPrices(
        context: Context,
        usd: Double?,
        eur: Double?,
        epochMs: Long = System.currentTimeMillis(),
    ) {
        val ed = prefs(context).edit()
        if (usd != null && usd.isFinite()) {
            ed.putLong(
                KEY_LATEST_UPSTREAM_USD_GLOBAL,
                java.lang.Double.doubleToRawLongBits(usd),
            )
        }
        if (eur != null && eur.isFinite()) {
            ed.putLong(
                KEY_LATEST_UPSTREAM_EUR_GLOBAL,
                java.lang.Double.doubleToRawLongBits(eur),
            )
        }
        ed.putLong(KEY_LATEST_UPSTREAM_AT_GLOBAL, epochMs)
        ed.apply()
    }

    /**
     * Latest fetched upstream price for [currency]. Only USD and EUR
     * are stored; SATS rides on USD and is computed at the call site.
     */
    fun loadLatestUpstreamPrice(context: Context, currency: String): Double? {
        val key = when (currency.uppercase()) {
            CURRENCY_EUR -> KEY_LATEST_UPSTREAM_EUR_GLOBAL
            CURRENCY_USD -> KEY_LATEST_UPSTREAM_USD_GLOBAL
            else -> return null
        }
        return loadDouble(context, key)
    }

    /** Epoch ms of the most recent saveLatestUpstreamPrices call, or 0. */
    fun loadLatestUpstreamAt(context: Context): Long {
        return prefs(context).getLong(KEY_LATEST_UPSTREAM_AT_GLOBAL, 0L)
    }

    private fun loadDouble(context: Context, key: String): Double? {
        if (!prefs(context).contains(key)) return null
        val bits = prefs(context).getLong(key, 0L)
        val v = java.lang.Double.longBitsToDouble(bits)
        return if (v.isFinite()) v else null
    }

    // ---- Cleanup ----------------------------------------------------------

    fun deleteAll(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove(KEY_CURRENCY_PREFIX + appWidgetId)
            .remove(KEY_OPACITY_PREFIX + appWidgetId)
            .remove(KEY_HIDE_LOGO_PREFIX + appWidgetId)
            .remove(KEY_HIDE_UNIT_PREFIX + appWidgetId)
            .remove(KEY_TRACKED_AMOUNT_PREFIX + appWidgetId)
            .remove(KEY_SHOW_DECIMALS_PREFIX + appWidgetId)
            .remove(KEY_SEPARATOR_PREFIX + appWidgetId)
            .remove(KEY_CHANGE_INDICATOR_PREFIX + appWidgetId)
            .remove(KEY_LAST_SUCCESS_PREFIX + appWidgetId)
            .remove(KEY_LAST_PRICE_TEXT_PREFIX + appWidgetId)
            .remove(KEY_LAST_RAW_PRICE_PREFIX + appWidgetId)
            .remove(KEY_LAST_1D_PRICE_PREFIX + appWidgetId)
            .remove(KEY_LAST_1W_PRICE_PREFIX + appWidgetId)
            .remove(KEY_FAIL_COUNT_PREFIX + appWidgetId)
            .remove(KEY_LAST_ERROR_PREFIX + appWidgetId)
            .remove(KEY_LAST_ERROR_AT_PREFIX + appWidgetId)
            .remove(KEY_SHOW_CHART_PREFIX + appWidgetId)
            .remove(KEY_MOSCOW_TIME_PREFIX + appWidgetId)
            .remove(KEY_HIDE_CURRENCY_ICON_PREFIX + appWidgetId)
            .apply()
    }

    /**
     * Text prefix shown before the price (e.g. "$ 81,324"). SATS has no
     * widely-supported Unicode glyph yet — for that mode we render the
     * sat-symbol PNG in the icon slot and return an empty prefix here.
     */
    fun symbolFor(currency: String): String = when (currency.uppercase()) {
        CURRENCY_EUR -> "€"
        CURRENCY_BTC -> "₿"
        CURRENCY_SATS -> ""
        else -> "$"
    }
}
