package org.cheeserobot.btcwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Widget provider - orchestrates rendering and the network fetch.
 *
 * Lifecycle hooks:
 *   - onUpdate   - system tells us to refresh one or more widgets.
 *   - onReceive  - also handles ACTION_REFRESH (user tap on widget,
 *                  or a refresh broadcast from another component such
 *                  as BatterySaverInfoActivity).
 *   - onDeleted  - clean up per-widget prefs.
 *
 * Refresh-all + batched fetch:
 *   The cheeserobot.org feed returns USD and EUR (plus all historical
 *   variants) in a single JSON. So a tap on ANY widget triggers a
 *   refresh of every widget on the device, sharing one HTTP round-trip
 *   across all of them. This keeps multi-widget setups in sync and
 *   avoids redundant network calls.
 *
 * 15-second rate limit:
 *   User-triggered refreshes (taps, "Refresh existing widgets" button)
 *   skip the network if there was a network attempt within the last
 *   [MIN_USER_REFRESH_INTERVAL_MS] ms. A rate-limited tap still
 *   repaints from cache so it feels responsive. The system-scheduled
 *   update (every 30 min) and the settings-save path bypass this check.
 *
 * When battery saver is ON, the widget's tap target is rewired to
 * launch [BatterySaverInfoActivity] instead of broadcasting
 * ACTION_REFRESH - Toasts from a BroadcastReceiver context are
 * suppressed by Android when the user has notifications disabled, so
 * an Activity is the only reliable way to surface "the widget can't
 * update right now".
 */
class BitcoinPriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshWidgets(context, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            handleRefreshAction(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            WidgetPrefs.deleteAll(context, id)
        }
    }

    /**
     * Tap-to-refresh entry point. Always operates on every placed
     * widget (single shared backend, single shared fetch) and applies
     * a 15-second rate-limit so a user tapping rapidly doesn't spam
     * the network.
     */
    private fun handleRefreshAction(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(
            ComponentName(context, BitcoinPriceWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return

        if (isPowerSaveOn(context)) {
            for (id in ids) paintFromCache(context, mgr, id)
            return
        }

        val now = System.currentTimeMillis()
        val lastAttempt = WidgetPrefs.loadLastNetworkAt(context)
        if (lastAttempt > 0 && (now - lastAttempt) < MIN_USER_REFRESH_INTERVAL_MS) {
            for (id in ids) paintFromCache(context, mgr, id)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshWidgets(context, mgr, ids)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Paint a widget from saved state without making any network call.
     * Used when battery saver kicks in or a refresh tap is rate-limited.
     */
    private fun paintFromCache(
        context: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int
    ) {
        val state = computeState(context, appWidgetId, hasFreshPrice = false)
        val priceText = WidgetPrefs.loadLastPriceText(context, appWidgetId) ?: "—"
        mgr.updateAppWidget(appWidgetId, buildViews(context, priceText, appWidgetId, state))
    }

    companion object {
        const val ACTION_REFRESH = "org.cheeserobot.btcwidget.ACTION_REFRESH"

        private const val RETRY_DELAY_MS = 2_000L
        private const val ICON_ALPHA_NORMAL = 255
        private const val ICON_ALPHA_GREY = 90

        /**
         * Minimum gap between user-triggered network fetches. Tapping a
         * widget more often than this just repaints from cache.
         */
        private const val MIN_USER_REFRESH_INTERVAL_MS = 15_000L

        private enum class WidgetState {
            NORMAL,
            BATTERY_SAVER,
            OFFLINE,
            STALE,
        }

        /**
         * Refresh a batch of widgets with a shared network fetch.
         *
         * - BTC-mode widgets are painted directly (no network).
         * - Network widgets get a "loading" frame, then a single fetch
         *   covers every distinct currency, then each widget renders
         *   from its currency's slot in the result map.
         */
        private fun refreshWidgets(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray,
        ) {
            if (ids.isEmpty()) return

            val networkIds = mutableListOf<Int>()
            for (id in ids) {
                val ccy = WidgetPrefs.loadCurrency(context, id)
                if (ccy.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true)) {
                    renderBtcWidget(context, mgr, id)
                } else {
                    networkIds.add(id)
                }
            }
            if (networkIds.isEmpty()) return

            if (!hasNetwork(context)) {
                for (id in networkIds) {
                    WidgetPrefs.recordFailure(context, id, reason = "No network")
                    val cached = WidgetPrefs.loadLastPriceText(context, id) ?: "—"
                    mgr.updateAppWidget(
                        id, buildViews(context, cached, id, WidgetState.OFFLINE)
                    )
                }
                return
            }

            for (id in networkIds) {
                val loadingState = computeState(context, id, hasFreshPrice = false)
                mgr.updateAppWidget(
                    id, buildViews(context, "…", id, loadingState)
                )
            }

            // SATS widgets ride on the USD fetch — the upstream JSON
            // doesn't carry sats directly, we just invert the USD price.
            val currencies = networkIds
                .map { upstreamCurrencyFor(WidgetPrefs.loadCurrency(context, it)) }
                .toSet()

            // Mark the attempt up-front so a second tap arriving while
            // we're still on the wire sees the rate limit and bails.
            WidgetPrefs.markNetworkAttempt(context)

            val results = fetchPricesWithRetry(currencies)

            // Best-effort: refresh the (optional) 24h / 7d price history
            // at most once per hour each, only for the windows actually
            // needed by some placed chart-enabled widget. Runs on the
            // same IO thread we're already on. A failure here never
            // affects the price render.
            maybeRefreshHistory(context, networkIds.toIntArray())

            // Cache the latest fetched USD/EUR prices globally so the
            // chart renderer can append a "now" point on the right
            // edge — that's the "always add the latest price we have
            // to the data points" rule.
            saveLatestUpstream(context, results)

            for (id in networkIds) {
                renderResult(context, mgr, id, results)
            }
        }

        /**
         * Lift the raw upstream USD/EUR price out of the per-currency
         * fetch results and persist them globally. Either may be absent
         * (no widget asked for that currency this round, or the fetch
         * failed); we only save the ones we actually have so a
         * partial-failure round trip can't blank an existing good value.
         */
        private fun saveLatestUpstream(
            context: Context,
            results: Map<String, PriceResult>,
        ) {
            val usd = (results[WidgetPrefs.CURRENCY_USD] as? PriceResult.Success)?.price
            val eur = (results[WidgetPrefs.CURRENCY_EUR] as? PriceResult.Success)?.price
            if (usd == null && eur == null) return
            WidgetPrefs.saveLatestUpstreamPrices(context, usd, eur)
        }

        /**
         * Minimum gap between successful price-history fetches per
         * window. The upstream endpoints change slowly (server-side
         * updates are coarse-grained too), so an hour is plenty.
         * Applies to both the 24h and 7d JSON files independently.
         */
        private const val HISTORY_REFRESH_INTERVAL_MS = 60L * 60L * 1000L

        /**
         * Refresh whichever price-history caches are stale (>1h old)
         * and actually needed by some placed widget. We compute the
         * union of windows currently selected across all non-BTC
         * widgets that have the chart turned on — i.e. only fetch the
         * 24h JSON if at least one widget is set to 24h-with-chart,
         * and likewise for 7d. Failures are silent; the next tick can
         * retry. Bytes saved: charts that are off entirely no longer
         * pull either endpoint.
         */
        private fun maybeRefreshHistory(context: Context, ids: IntArray) {
            if (!hasNetwork(context)) return
            val now = System.currentTimeMillis()
            val needed = neededHistoryPeriods(context, ids)
            for (period in needed) {
                val last = WidgetPrefs.loadLastHistoryAt(context, period)
                if (last > 0 && (now - last) < HISTORY_REFRESH_INTERVAL_MS) continue
                when (val r = HistoryFetcher.fetchHistory(period)) {
                    is HistoryResult.Success -> {
                        WidgetPrefs.saveHistoryJson(context, period, r.rawBody, now)
                    }
                    is HistoryResult.Error -> {
                        // Non-fatal: leave existing cache alone (or empty),
                        // the next tick can retry.
                    }
                }
            }
        }

        /**
         * Set of history windows ([WidgetPrefs.HISTORY_1D] /
         * [WidgetPrefs.HISTORY_7D]) needed across all placed widgets.
         * Only widgets that have the chart enabled and aren't in BTC-mode
         * contribute — BTC has no historical to draw, and chart-off
         * widgets never read the cache.
         */
        private fun neededHistoryPeriods(
            context: Context,
            ids: IntArray,
        ): Set<String> {
            val out = mutableSetOf<String>()
            for (id in ids) {
                val ccy = WidgetPrefs.loadCurrency(context, id)
                if (ccy.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true)) continue
                if (!WidgetPrefs.loadShowChart(context, id)) continue
                val mode = WidgetPrefs.loadChangeIndicator(context, id)
                out.add(
                    if (mode == WidgetPrefs.CHANGE_1W) WidgetPrefs.HISTORY_7D
                    else WidgetPrefs.HISTORY_1D
                )
            }
            return out
        }

        /**
         * For network-backed widgets, what currency key in the upstream
         * JSON do we actually need? SATS rides on the USD price so its
         * fetch slot is "USD"; everything else maps to itself.
         */
        private fun upstreamCurrencyFor(currency: String): String =
            if (currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true))
                WidgetPrefs.CURRENCY_USD else currency

        /**
         * Number of satoshis in one bitcoin. SATS-mode displays
         * `SATS_PER_BTC / usd_price` — i.e. how many sats you get per USD.
         */
        private const val SATS_PER_BTC = 100_000_000.0

        /** Convert a USD-quoted BTC price to sats-per-USD. */
        private fun toSats(usdPrice: Double?): Double? {
            if (usdPrice == null || !usdPrice.isFinite() || usdPrice == 0.0) return null
            return SATS_PER_BTC / usdPrice
        }

        /** Paint a single network-backed widget from a shared result map. */
        private fun renderResult(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
            results: Map<String, PriceResult>,
        ) {
            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val showDecimals = WidgetPrefs.loadShowDecimals(context, appWidgetId)
            val separator = WidgetPrefs.loadSeparator(context, appWidgetId)

            val isSats = currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
            val lookupKey = upstreamCurrencyFor(currency)
            val result = results[lookupKey]
                ?: PriceResult.Error("No result for \"$currency\"")

            when (result) {
                is PriceResult.Success -> {
                    // SATS inverts: BTC->USD price becomes USD->sats.
                    // For sats we also recompute the historical legs from
                    // the USD historicals so the change indicator stays
                    // meaningful. Note: when USD goes UP, sats/USD goes
                    // DOWN — so a green "+x%" line for SATS means USD
                    // bought more sats than yesterday, i.e. BTC dropped.
                    val effectivePrice = if (isSats) toSats(result.price) ?: 0.0
                    else result.price
                    val effective1d = if (isSats) toSats(result.priceOneDayAgo)
                    else result.priceOneDayAgo
                    val effective1w = if (isSats) toSats(result.priceOneWeekAgo)
                    else result.priceOneWeekAgo

                    val displayed = effectivePrice * trackedAmount
                    val moscowTime = WidgetPrefs.loadMoscowTime(context, appWidgetId)
                    val text = if (PriceFormat.isMoscowTimeActive(currency, separator, moscowTime))
                        PriceFormat.formatMoscowTime(displayed)
                    else
                        PriceFormat.format(displayed, showDecimals, separator)
                    WidgetPrefs.recordSuccess(
                        context, appWidgetId, System.currentTimeMillis(),
                        priceText = text,
                        rawPrice = displayed,
                        oneDayAgo = effective1d?.times(trackedAmount),
                        oneWeekAgo = effective1w?.times(trackedAmount),
                    )
                    val state = computeState(context, appWidgetId, hasFreshPrice = true)
                    mgr.updateAppWidget(
                        appWidgetId, buildViews(context, text, appWidgetId, state)
                    )
                }
                is PriceResult.Error -> {
                    WidgetPrefs.recordFailure(
                        context, appWidgetId, reason = result.reason
                    )
                    val cached = WidgetPrefs.loadLastPriceText(context, appWidgetId)
                    val text = cached ?: "—"
                    val state = errorState(context)
                    mgr.updateAppWidget(
                        appWidgetId, buildViews(context, text, appWidgetId, state)
                    )
                }
            }
        }

        /**
         * Render a BTC-mode widget. One Bitcoin equals one Bitcoin
         * (xtrackedAmount), so there's no fetch and no historical data;
         * the change indicator naturally hides itself.
         */
        private fun renderBtcWidget(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val showDecimals = WidgetPrefs.loadShowDecimals(context, appWidgetId)
            val separator = WidgetPrefs.loadSeparator(context, appWidgetId)
            val text = PriceFormat.format(trackedAmount, showDecimals, separator)
            WidgetPrefs.recordSuccess(
                context, appWidgetId, System.currentTimeMillis(),
                priceText = text, rawPrice = trackedAmount,
                oneDayAgo = null, oneWeekAgo = null,
            )
            val state = computeState(context, appWidgetId, hasFreshPrice = true)
            mgr.updateAppWidget(
                appWidgetId, buildViews(context, text, appWidgetId, state)
            )
        }

        private fun errorState(context: Context): WidgetState {
            if (isPowerSaveOn(context)) return WidgetState.BATTERY_SAVER
            if (!hasNetwork(context)) return WidgetState.OFFLINE
            return WidgetState.STALE
        }

        /**
         * Try once, and if EVERY currency failed wait [RETRY_DELAY_MS]
         * and try again. Partial successes don't trigger a retry.
         */
        private fun fetchPricesWithRetry(
            currencies: Collection<String>,
        ): Map<String, PriceResult> {
            val first = PriceFetcher.fetchPrices(currencies)
            if (first.values.any { it is PriceResult.Success }) return first
            try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) {}
            return PriceFetcher.fetchPrices(currencies)
        }

        /**
         * Public hook used by the configuration activity right after the
         * user saves their settings. Bypasses the user-tap rate limit
         * because the user just deliberately changed something.
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val state = computeState(context, appWidgetId, hasFreshPrice = false)
            appWidgetManager.updateAppWidget(
                appWidgetId, buildViews(context, "…", appWidgetId, state)
            )
            CoroutineScope(Dispatchers.IO).launch {
                delay(50)
                refreshWidgets(context, appWidgetManager, intArrayOf(appWidgetId))
            }
        }

        private fun computeState(
            context: Context,
            appWidgetId: Int,
            hasFreshPrice: Boolean
        ): WidgetState {
            if (isPowerSaveOn(context)) return WidgetState.BATTERY_SAVER
            if (!hasNetwork(context)) return WidgetState.OFFLINE
            val failCount = WidgetPrefs.loadFailCount(context, appWidgetId)
            if (!hasFreshPrice && failCount >= 2) return WidgetState.STALE
            return WidgetState.NORMAL
        }

        private fun buildViews(
            context: Context,
            priceText: String,
            appWidgetId: Int,
            state: WidgetState
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_bitcoin_price)

            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val symbol = WidgetPrefs.symbolFor(currency)
            val hideCurrencyIcon = WidgetPrefs.loadHideCurrencyIcon(context, appWidgetId)
            // SATS uses the icon slot for the glyph, so its text prefix
            // is already empty. For USD/EUR/BTC the prefix is dropped
            // when the user has hide-currency-icon turned on, otherwise
            // we render "<symbol> <price>" with a single spacer.
            val priceWithSymbol = when {
                symbol.isEmpty() -> priceText
                hideCurrencyIcon -> priceText
                else -> "$symbol $priceText"
            }
            views.setTextViewText(R.id.price_text, priceWithSymbol)

            // When the last update didn't bring fresh data (battery saver,
            // offline, stale) the price + unit text get a subtle dim so
            // the whole widget reads as "not live" — not just the icon.
            // Kept lighter than the icon's alpha (90/255) so the price
            // stays comfortably legible at a glance.
            val textAlpha = if (state == WidgetState.NORMAL) 1.0f else 0.6f
            val baseTextColor = context.getColor(R.color.widget_text)
            val dimmedTextColor = applyAlpha(baseTextColor, textAlpha)
            views.setTextColor(R.id.price_text, dimmedTextColor)

            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val hideUnit = WidgetPrefs.loadHideUnitLabel(context, appWidgetId)
            views.setViewVisibility(
                R.id.unit_label, if (hideUnit) View.GONE else View.VISIBLE
            )
            if (!hideUnit) {
                views.setTextViewText(R.id.unit_label, formatUnitLabel(trackedAmount, currency))
                views.setTextColor(R.id.unit_label, dimmedTextColor)
            }

            // Two flags can suppress the icon slot:
            //   - hideLogo: the legacy "hide bitcoin logo" toggle
            //   - hideCurrencyIcon (SATS only): the new "hide currency
            //     icon" toggle, which for SATS means the sat-symbol
            //     because that PNG IS the currency marker in SATS mode.
            // For non-SATS modes the currency marker is the text
            // prefix on price_text (handled above), so this flag has
            // no effect on the icon slot.
            val isSats = currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
            val hideLogo = WidgetPrefs.loadHideLogo(context, appWidgetId)
            val iconHidden = hideLogo || (isSats && hideCurrencyIcon)
            views.setViewVisibility(
                R.id.btc_icon, if (iconHidden) View.GONE else View.VISIBLE
            )

            // Pick the icon family based on currency: SATS gets the
            // sat-symbol PNG; everything else gets the existing Bitcoin
            // logo. Each family has a "live" and a "grey" (stale/offline
            // /battery-saver) variant.
            val iconRes = when (state) {
                WidgetState.NORMAL ->
                    if (isSats) R.drawable.ic_sat_symbol else R.drawable.ic_bitcoin
                WidgetState.BATTERY_SAVER, WidgetState.STALE, WidgetState.OFFLINE ->
                    if (isSats) R.drawable.ic_sat_symbol_grey
                    else R.drawable.ic_bitcoin_grey
            }
            views.setImageViewResource(R.id.btc_icon, iconRes)
            val iconAlpha = if (state == WidgetState.NORMAL) ICON_ALPHA_NORMAL else ICON_ALPHA_GREY
            views.setInt(R.id.btc_icon, "setImageAlpha", iconAlpha)

            // Background panel sits on background_view and respects the
            // user's opacity slider (0 % = fully transparent panel).
            // The sparkline lives on its OWN ImageView (sparkline_view)
            // so the slider can dim the panel without dimming the chart
            // — at 0 % opacity the line still needs to read against the
            // wallpaper. We always set the panel's source explicitly
            // so the previous frame's bitmap can't bleed through.
            views.setImageViewResource(R.id.background_view, R.drawable.widget_background)
            val opacityPct = WidgetPrefs.loadOpacity(context, appWidgetId)
            val opacity255 = (opacityPct.coerceIn(0, 100) * 255 / 100)
            views.setInt(R.id.background_view, "setImageAlpha", opacity255)

            // Sparkline only paints in the NORMAL state — a stale /
            // offline / battery-saver widget already greys its icon, and
            // a stale chart would just lie about the trend. A null
            // result (chart disabled, no cached data, degenerate series)
            // hides the view entirely.
            val bmp = if (state == WidgetState.NORMAL)
                buildSparklineBitmap(context, appWidgetId, currency)
            else null
            if (bmp != null) {
                views.setImageViewBitmap(R.id.sparkline_view, bmp)
                views.setInt(R.id.sparkline_view, "setImageAlpha", 255)
                views.setViewVisibility(R.id.sparkline_view, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.sparkline_view, View.GONE)
            }

            renderChangeIndicator(context, views, appWidgetId, state)

            val pi = if (state == WidgetState.BATTERY_SAVER) {
                buildBatterySaverPendingIntent(context, appWidgetId)
            } else {
                buildRefreshPendingIntent(context, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }

        /**
         * Set text + colour + visibility on the change indicator line.
         * Hidden only when there's no current price yet or the upstream
         * feed didn't ship the requested historical — the user can no
         * longer disable the indicator entirely (the bottom row always
         * reflects the chosen 24h/7d window when data is available).
         */
        private fun renderChangeIndicator(
            context: Context,
            views: RemoteViews,
            appWidgetId: Int,
            state: WidgetState,
        ) {
            val mode = WidgetPrefs.loadChangeIndicator(context, appWidgetId)
            val current = WidgetPrefs.loadLastRawPrice(context, appWidgetId)
            val historical = when (mode) {
                WidgetPrefs.CHANGE_1W -> WidgetPrefs.loadLastOneWeekAgo(context, appWidgetId)
                else -> WidgetPrefs.loadLastOneDayAgo(context, appWidgetId)
            }

            if (current == null || !current.isFinite() ||
                historical == null || !historical.isFinite() || historical == 0.0
            ) {
                views.setViewVisibility(R.id.change_indicator, View.GONE)
                return
            }

            val pct = ((current - historical) / historical) * 100.0
            val sign = if (pct >= 0) "+" else ""
            val periodLabel = if (mode == WidgetPrefs.CHANGE_1W) "7d" else "24h"
            val text = String.format(Locale.US, "%s%.1f%% %s", sign, pct, periodLabel)

            // For SATS (sats-per-USD) the displayed quantity moves
            // OPPOSITE to BTC: a rising sats-per-USD figure means each
            // dollar buys more sats, i.e. BTC just got cheaper. From a
            // BTC holder's point of view that's a loss, so we flip the
            // colour mapping — green for negative, red for positive —
            // to keep "green = good for Bitcoin" intuitive across all
            // currencies. The numeric sign in the text stays correct
            // for the literal value being shown.
            val isSats = WidgetPrefs.loadCurrency(context, appWidgetId)
                .equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
            val btcWentUp = if (isSats) pct < 0 else pct >= 0
            val colorRes = if (btcWentUp) R.color.change_up else R.color.change_down
            val color = context.getColor(colorRes)

            val alpha = if (state == WidgetState.NORMAL) 1.0f else 0.6f
            views.setTextViewText(R.id.change_indicator, text)
            views.setTextColor(R.id.change_indicator, applyAlpha(color, alpha))
            views.setViewVisibility(R.id.change_indicator, View.VISIBLE)
        }

        private fun applyAlpha(color: Int, factor: Float): Int {
            val a = ((color ushr 24) and 0xFF)
            val newA = (a * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            return (newA shl 24) or (color and 0x00FFFFFF)
        }

        private fun formatUnitLabel(amount: Double, currency: String = WidgetPrefs.CURRENCY_USD): String {
            val rendered = if (amount == amount.toLong().toDouble()) {
                amount.toLong().toString()
            } else {
                val s = String.format(Locale.US, "%.8f", amount)
                    .trimEnd('0').trimEnd('.')
                if (s.isEmpty()) "0" else s
            }
            // SATS-mode shows "sats per N USD" rather than the price of
            // N BTC, so the caption swaps unit too.
            val unit = if (currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true))
                "USD" else "BTC"
            val prefix = if (currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true))
                "per " else ""
            return "$prefix$rendered $unit"
        }

        private fun buildRefreshPendingIntent(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            // ACTION_REFRESH always triggers a refresh-all in
            // handleRefreshAction; we no longer pass EXTRA_APPWIDGET_IDS
            // because the receiver ignores them. Per-widget data on the
            // intent stays only so distinct PendingIntent instances
            // don't collide via FLAG_UPDATE_CURRENT.
            val intent = Intent(context, BitcoinPriceWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                data = android.net.Uri.parse("cheesebtc://refresh/$appWidgetId")
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, appWidgetId, intent, flags)
        }

        private fun buildBatterySaverPendingIntent(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            val intent = Intent(context, BatterySaverInfoActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                data = android.net.Uri.parse("cheesebtc://power-save/$appWidgetId")
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, appWidgetId, intent, flags)
        }

        /**
         * Build the optional sparkline bitmap for [appWidgetId], or null
         * when:
         *   - the user has the chart toggle off,
         *   - we don't yet have any cached history JSON for the chosen
         *     window (24h or 7d),
         *   - the cached body is corrupt or empty,
         *   - the resulting series is too short / flat to render.
         *
         * The chart window follows the per-widget price-change setting:
         * CHANGE_1D pulls the 1-day endpoint, CHANGE_1W the 7-day one.
         * The most recent fetched upstream price is always appended as
         * the rightmost data point so the line ends at "now" — even
         * when the cache itself is slightly stale (up to 1h old).
         *
         * The bitmap is just the line on a transparent canvas — the
         * rounded panel lives on a separate ImageView so the user's
         * opacity slider can dim the panel without touching the chart.
         * Colours come from theme-qualified resources so the line
         * stays legible on dark wallpaper too.
         */
        private fun buildSparklineBitmap(
            context: Context,
            appWidgetId: Int,
            currency: String,
        ): Bitmap? {
            if (!WidgetPrefs.loadShowChart(context, appWidgetId)) return null

            // BTC mode: no fetch, no historical, no period selector —
            // 1 BTC has always been worth 1 BTC. Paint a flat green
            // line so the chart toggle still reads as "on" and the
            // visual joke completes. Bypasses every cache and the
            // up/down colour logic on purpose.
            if (currency.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true)) {
                val (widthPx, heightPx) = widgetPixelSize(context, appWidgetId)
                val color = context.getColor(R.color.sparkline_up)
                val density = context.resources.displayMetrics.density
                val strokePx = (1.5f * density).coerceAtLeast(1.5f)
                return SparklineRenderer.renderFlat(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    color = color,
                    strokePx = strokePx,
                )
            }

            val mode = WidgetPrefs.loadChangeIndicator(context, appWidgetId)
            val period = if (mode == WidgetPrefs.CHANGE_1W)
                WidgetPrefs.HISTORY_7D else WidgetPrefs.HISTORY_1D
            val cached = WidgetPrefs.loadHistoryJson(context, period) ?: return null
            val parsed = HistoryFetcher.parse(cached)
            if (parsed !is HistoryResult.Success) return null

            // SATS rides on the USD series and inverts each point.
            val isSats = currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
            val baseCurrency = if (isSats) WidgetPrefs.CURRENCY_USD else currency
            val raw = HistoryFetcher.seriesFor(parsed.points, baseCurrency)
            if (raw.size < 2) return null

            // Append the latest fetched upstream price (USD/EUR) as the
            // rightmost data point. Stored globally by the provider on
            // every successful PriceFetcher round trip; null only on the
            // very first launch or when no widget has fetched the
            // chart's base currency yet.
            val latestBase = WidgetPrefs.loadLatestUpstreamPrice(context, baseCurrency)
            val rawPlus = appendLatest(raw, latestBase)

            val values = if (isSats) {
                DoubleArray(rawPlus.size) { i ->
                    val v = rawPlus[i]
                    if (v == 0.0 || !v.isFinite()) 0.0 else 100_000_000.0 / v
                }
            } else rawPlus
            if (values.size < 2) return null

            val (widthPx, heightPx) = widgetPixelSize(context, appWidgetId)
            val colorRes = if (SparklineRenderer.isUp(values))
                R.color.sparkline_up else R.color.sparkline_down
            val color = context.getColor(colorRes)
            val density = context.resources.displayMetrics.density
            val strokePx = (1.5f * density).coerceAtLeast(1.5f)

            return SparklineRenderer.render(
                values = values,
                widthPx = widthPx,
                heightPx = heightPx,
                color = color,
                strokePx = strokePx,
            )
        }

        /**
         * Append [latest] as a new rightmost point to [series]. Returns
         * the original array unchanged when [latest] is null, non-finite,
         * or already equal to the existing tail (no movement = no point
         * in adding a degenerate horizontal segment).
         */
        private fun appendLatest(series: DoubleArray, latest: Double?): DoubleArray {
            if (latest == null || !latest.isFinite()) return series
            if (series.isNotEmpty() && series.last() == latest) return series
            val out = DoubleArray(series.size + 1)
            System.arraycopy(series, 0, out, 0, series.size)
            out[series.size] = latest
            return out
        }

        /**
         * Compute a sensible target bitmap size for the sparkline based
         * on the widget's current cell footprint. Falls back to a 2x1
         * default when the launcher hasn't reported sizes yet (typically
         * the very first paint).
         */
        private fun widgetPixelSize(
            context: Context,
            appWidgetId: Int,
        ): Pair<Int, Int> {
            val opts = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            val density = context.resources.displayMetrics.density
            val maxWdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
            val maxHdp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val widthDp = if (maxWdp > 0) maxWdp else 160
            val heightDp = if (maxHdp > 0) maxHdp else 80
            val widthPx = (widthDp * density).toInt().coerceIn(64, 800)
            val heightPx = (heightDp * density).toInt().coerceIn(32, 400)
            return widthPx to heightPx
        }

        private fun isPowerSaveOn(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isPowerSaveMode == true
        }

        /**
         * Visible to the rest of the app (e.g. [LauncherActivity]) so the
         * "main" screen can short-circuit its preview fetch and render a
         * "No network" message immediately, identical in spirit to what
         * the widget itself does when the connectivity check fails.
         */
        internal fun hasNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
