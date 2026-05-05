package org.cheeserobot.btcwidget

import android.content.Context

/**
 * Lightweight helper around SharedPreferences for per-widget settings.
 *
 * Each placed widget has its own appWidgetId; we store every setting
 * keyed by that id so multiple widgets can be configured independently.
 *
 * Settings stored per widget:
 *   currency            — "USD" / "EUR" / "BTC"
 *   opacity             — 0..100 % background opacity (default 75)
 *   hideLogo            — bool, hide the bitcoin glyph
 *   hideUnitLabel       — bool, hide the "<amount> BTC" caption
 *   trackedAmount       — Double, default 1.0; widget displays
 *                         (trackedAmount × spot price)
 *   showDecimals        — bool, default false; when true the price is
 *                         rendered with 2 decimal places
 *   thousandsSeparator  — one of SEPARATOR_*; controls how groups of
 *                         three digits are visually separated
 *   changeIndicator     — "OFF" / "1D" / "1W"; toggles the red/green
 *                         percentage-change line at the bottom of the
 *                         widget
 *   lastSuccessAt       — epoch ms of last successful price fetch
 *   lastPriceText       — last successfully rendered price string,
 *                         shown again while offline / battery-saver so
 *                         the user still sees something useful
 *   lastRawPrice        — last successfully fetched RAW current price
 *                         (Double, no formatting). Used to compute the
 *                         change indicator without re-fetching.
 *   last1dAgoPrice      — last fetched 1-day-ago RAW price (or absent)
 *   last1wAgoPrice      — last fetched 1-week-ago RAW price (or absent)
 *   consecutiveFails    — number of consecutive fetch failures (used to
 *                         flip the widget into a "stale" visual state)
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

    const val CURRENCY_USD = "USD"
    const val CURRENCY_EUR = "EUR"

    /**
     * "For fun" currency: when selected, the widget skips the network
     * call entirely and always displays `₿ <trackedAmount>` (because
     * one Bitcoin is, by definition, exactly one Bitcoin).
     */
    const val CURRENCY_BTC = "BTC"

    // Thousands-separator options.
    const val SEPARATOR_AUTO = "AUTO"   // use the device locale's default
    const val SEPARATOR_COMMA = "COMMA" // 1,234,567
    const val SEPARATOR_DOT = "DOT"     // 1.234.567
    const val SEPARATOR_SPACE = "SPACE" // 1 234 567
    const val SEPARATOR_NONE = "NONE"   // 1234567

    // Change-indicator modes.
    const val CHANGE_OFF = "OFF"
    const val CHANGE_1D = "1D"
    const val CHANGE_1W = "1W"

    const val DEFAULT_OPACITY = 75
    const val DEFAULT_HIDE_LOGO = false
    const val DEFAULT_HIDE_UNIT_LABEL = false
    const val DEFAULT_TRACKED_AMOUNT = 1.0
    const val DEFAULT_SHOW_DECIMALS = false
    const val DEFAULT_SEPARATOR = SEPARATOR_AUTO
    const val DEFAULT_CHANGE_INDICATOR = CHANGE_OFF

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

    /** True if a currency has been saved for this widget id. */
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
        // Doubles don't have a SharedPreferences setter; bit-cast to Long.
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
        val normalised = when (mode.uppercase()) {
            CHANGE_1D -> CHANGE_1D
            CHANGE_1W -> CHANGE_1W
            else -> CHANGE_OFF
        }
        prefs(context).edit()
            .putString(KEY_CHANGE_INDICATOR_PREFIX + appWidgetId, normalised)
            .apply()
    }

    fun loadChangeIndicator(context: Context, appWidgetId: Int): String {
        return prefs(context).getString(
            KEY_CHANGE_INDICATOR_PREFIX + appWidgetId, DEFAULT_CHANGE_INDICATOR
        ) ?: DEFAULT_CHANGE_INDICATOR
    }

    // ---- Fetch state ------------------------------------------------------

    /**
     * Persist a successful fetch. Called on the network-success path
     * (and for BTC mode, where there's no network call).
     *
     * [rawPrice], [oneDayAgo] and [oneWeekAgo] are optional so callers
     * that don't know them (BTC mode, legacy paths) can omit them; we
     * record `null` placeholders, leaving any prior value in place.
     */
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
            // Clear any stale error message — we just had a success.
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

    /** The reason text from the most recent failed fetch, or null. */
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

    /** The last successfully rendered price (without the currency symbol). */
    fun loadLastPriceText(context: Context, appWidgetId: Int): String? {
        return prefs(context).getString(KEY_LAST_PRICE_TEXT_PREFIX + appWidgetId, null)
    }

    /** Last successfully fetched RAW current price, or null if never fetched. */
    fun loadLastRawPrice(context: Context, appWidgetId: Int): Double? =
        loadDouble(context, KEY_LAST_RAW_PRICE_PREFIX + appWidgetId)

    /** Last fetched 1-day-ago raw price, or null when not exposed by the feed. */
    fun loadLastOneDayAgo(context: Context, appWidgetId: Int): Double? =
        loadDouble(context, KEY_LAST_1D_PRICE_PREFIX + appWidgetId)

    /** Last fetched 1-week-ago raw price, or null when not exposed by the feed. */
    fun loadLastOneWeekAgo(context: Context, appWidgetId: Int): Double? =
        loadDouble(context, KEY_LAST_1W_PRICE_PREFIX + appWidgetId)

    private fun loadDouble(context: Context, key: String): Double? {
        if (!prefs(context).contains(key)) return null
        val bits = prefs(context).getLong(key, 0L)
        val v = java.lang.Double.longBitsToDouble(bits)
        return if (v.isFinite()) v else null
    }

    // ---- Cleanup ----------------------------------------------------------

    /** Wipe every per-widget key when a widget is removed. */
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
            .apply()
    }

    fun symbolFor(currency: String): String = when (currency.uppercase()) {
        CURRENCY_EUR -> "€"
        CURRENCY_BTC -> "₿" // ₿  U+20BF BITCOIN SIGN
        else -> "$"
    }
}
