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
 * Refresh-all + single-endpoint fetch:
 *   Every widget on the device shares one HTTP call to
 *   `/price/summary.json`, which carries the current USD/EUR price,
 *   the 24h and 7d historical arrays, and the latest-block snapshot
 *   in one payload. This used to be three separate endpoints; the
 *   consolidation cuts our hourly fetch budget by 3× and keeps every
 *   widget on the device in lock-step regardless of mode.
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
                // Scheduled (system 30-min tick / wake-up) refresh.
                // userInitiated=false keeps the widget silent on
                // transient failures so a freshly-woken phone with a
                // half-attached radio doesn't grey itself out before the
                // network has even had a chance to come back.
                refreshWidgets(context, appWidgetManager, appWidgetIds, userInitiated = false)
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
     * The user just resized the widget. The sparkline bitmap is rendered
     * at the widget's pixel size, so without this hook a resize would
     * leave a stretched/squashed chart on screen until the next
     * scheduled refresh (up to 30 minutes away). Repaint from cache —
     * no network needed, [buildViews] re-renders the sparkline at the
     * new size reported by the fresh widget options.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        paintFromCache(context, appWidgetManager, appWidgetId)
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
                refreshWidgets(context, mgr, ids, userInitiated = true)
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

        /**
         * Maximum age of the cached "latest upstream price" before the
         * chart stops appending it as the rightmost "now" point. One
         * hour matches the cadence the history arrays refresh at, so a
         * fresher-than-history point is appended and anything older is
         * left off (the line then simply ends at the last real sample).
         */
        private const val LATEST_POINT_MAX_AGE_MS = 60 * 60 * 1000L
        private const val ICON_ALPHA_NORMAL = 255
        private const val ICON_ALPHA_GREY = 90

        /**
         * Minimum gap between user-triggered network fetches. Tapping a
         * widget more often than this just repaints from cache.
         */
        private const val MIN_USER_REFRESH_INTERVAL_MS = 15_000L

        /**
         * Time-since-last-success threshold for switching the widget
         * into the greyed-out "stale" visual. Earlier versions used a
         * consecutive-failure counter, which meant a freshly-woken
         * phone whose radio hadn't reattached yet would visibly grey
         * out within two scheduled ticks even though the displayed
         * price was minutes old. By going time-based we keep the
         * "everything's fine" look across transient failures and only
         * surface staleness once the data on screen is genuinely old.
         *
         * 3 hours == roughly 6 missed 30-min update ticks. Anything
         * shorter and a phone that's been in a tunnel for a bit looks
         * broken; anything much longer and we'd be hiding a real,
         * persistent fetch problem from the user.
         */
        private const val STALE_AFTER_MS = 3 * 60 * 60 * 1000L

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
         * - Every other widget mode (USD, EUR, SATS, BLOCK) rides on a
         *   single `/price/summary.json` round trip — one fetch, all
         *   data: prices, history, and the latest-block snapshot.
         *
         * @param userInitiated  true for taps and "save settings"
         *   refreshes; false for the system-scheduled 30-min tick and
         *   wake-up `onUpdate`. Scheduled refreshes deliberately do NOT
         *   repaint the widget on transient failures — the user only
         *   ever sees a screen change when we actually have new data
         *   (or once the cached data has crossed [STALE_AFTER_MS]).
         *   That's what fixes the "wakes up greyed out" bug: a freshly
         *   woken phone with the radio still reattaching reports no
         *   network, but instead of greying the widget out we leave
         *   the launcher's last good frame on screen and try again
         *   next tick.
         */
        private fun refreshWidgets(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray,
            userInitiated: Boolean,
        ) {
            if (ids.isEmpty()) return

            // Three buckets:
            //   - BTC          → painted directly, no network at all.
            //   - summaryIds   → USD/EUR/SATS/BLOCK, served by the one
            //                    shared /price/summary.json round trip.
            //   - extendedIds  → every other catalog fiat (GBP, JPY, INR,
            //                    …), served per-currency by the /api/*
            //                    multi-currency endpoints.
            // networkIds is the union of the latter two — it drives the
            // shared offline gate, the loading flash, and the render loop.
            val networkIds = mutableListOf<Int>()
            val summaryIds = mutableListOf<Int>()
            val extendedIds = mutableListOf<Int>()
            for (id in ids) {
                val ccy = WidgetPrefs.loadCurrency(context, id)
                when {
                    ccy.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true) ->
                        renderBtcWidget(context, mgr, id)
                    CurrencyCatalog.isExtendedCurrency(ccy) -> {
                        extendedIds.add(id)
                        networkIds.add(id)
                    }
                    else -> {
                        summaryIds.add(id)
                        networkIds.add(id)
                    }
                }
            }
            if (networkIds.isEmpty()) return

            if (!hasNetwork(context)) {
                if (userInitiated) {
                    // The user explicitly asked, so they want to see
                    // *something* change — surface OFFLINE styling.
                    for (id in networkIds) {
                        WidgetPrefs.recordFailure(context, id, reason = "No network")
                        val cached = WidgetPrefs.loadLastPriceText(context, id) ?: "—"
                        mgr.updateAppWidget(
                            id, buildViews(context, cached, id, WidgetState.OFFLINE)
                        )
                    }
                } else {
                    // Scheduled refresh, no network — usually a wake-up
                    // race where the radio hasn't fully reattached yet.
                    // Don't repaint, don't bump the failure counter.
                    // Just let the staleness threshold decide whether
                    // the launcher's existing frame still looks fine.
                    for (id in networkIds) {
                        maybeRepaintIfStale(context, mgr, id)
                    }
                }
                return
            }

            // The "…" loading flash exists for tap-to-refresh feedback
            // — for a scheduled tick the user isn't watching, so this
            // would just briefly clobber the price for no reason. Only
            // paint it when the refresh is user-initiated.
            if (userInitiated) {
                for (id in networkIds) {
                    val loadingState = computeState(context, id, hasFreshPrice = false)
                    mgr.updateAppWidget(
                        id, buildViews(context, "…", id, loadingState)
                    )
                }
            }

            // Mark the attempt up-front so a second tap arriving while
            // we're still on the wire sees the rate limit and bails.
            WidgetPrefs.markNetworkAttempt(context)

            // Per-currency results consumed by the shared render loop
            // below. Keyed exactly as [renderResult] will look them up
            // (via upstreamCurrencyFor): USD/EUR/SATS/BLOCK collapse onto
            // their summary slot, extended currencies key by their own code.
            val results = HashMap<String, PriceResult>()

            val summaryCurrencies = summaryIds
                .map { upstreamCurrencyFor(WidgetPrefs.loadCurrency(context, it)) }
                .toSet()
            val extCurrencies = extendedIds
                .map { WidgetPrefs.loadCurrency(context, it).uppercase(Locale.ROOT) }
                .toSet()

            if (WidgetPrefs.loadCustomBackendUrl(context) == null) {
                // ---- Default backend: ONE request for EVERYTHING ----------
                // The official host's `?currency=` parameter accepts USD and
                // EUR alongside any extended code (up to
                // [ExtendedFetcher.MAX_CODES_PER_REQUEST] per request), and
                // every response shape carries latest_block — so a single
                // batched round trip covers USD/EUR/SATS/BLOCK widgets AND
                // every extended currency at once.
                results.putAll(
                    fetchAllViaBatch(context, summaryCurrencies, extCurrencies, userInitiated)
                )
            } else {
                // ---- Custom (pricemon-compatible) backend -----------------
                // Custom feeds only implement the BARE summary.json — no
                // `?currency=` parameter — so USD/EUR/SATS/BLOCK ride the
                // user's feed while extended codes still go to the official
                // host in one batched request. Two round trips, max.
                if (summaryIds.isNotEmpty()) {
                    val summary = fetchSummaryWithRetry(
                        WidgetPrefs.loadActiveBackendUrl(context), userInitiated
                    )
                    val summaryResults = buildPriceResults(summaryCurrencies, summary)
                    results.putAll(summaryResults)

                    if (summary is SummaryResult.Success) {
                        // The summary endpoint ships the same array shape the
                        // chart code already understands, so we drop hist_1d
                        // and hist_7d straight into the existing per-period cache.
                        val now = System.currentTimeMillis()
                        WidgetPrefs.saveHistoryJson(
                            context, WidgetPrefs.HISTORY_1D, summary.summary.hist1dJson, now
                        )
                        WidgetPrefs.saveHistoryJson(
                            context, WidgetPrefs.HISTORY_7D, summary.summary.hist7dJson, now
                        )
                        // Cache the latest-block snapshot so BLOCK-mode widgets
                        // can render even without a brand-new fetch on the next paint.
                        summary.summary.latestBlock?.let { block ->
                            WidgetPrefs.saveLatestBlock(
                                context,
                                height = block.height,
                                minerName = block.minerName,
                            )
                        }
                    }

                    // Cache the latest fetched USD/EUR prices globally so the
                    // chart renderer can append a "now" point on the right edge.
                    saveLatestUpstream(context, summaryResults)
                }

                if (extCurrencies.isNotEmpty()) {
                    results.putAll(
                        fetchExtendedCurrencies(context, extCurrencies, userInitiated)
                    )
                }
            }

            for (id in networkIds) {
                renderResult(context, mgr, id, results, userInitiated)
            }
        }

        /**
         * Default-backend fast path: ONE batched `?currency=` request
         * covers every widget on the device.
         *
         *   - USD/EUR are simply two more codes in the list (always both
         *     when any summary-mode widget exists, so the shared legacy
         *     history cache keeps carrying both currencies and SATS can
         *     invert off USD).
         *   - BLOCK rides the same response via its top-level
         *     latest_block (USD/EUR are already in the list as carriers).
         *   - Extended codes are appended as-is; >10 distinct codes just
         *     chunk into a second request inside [ExtendedFetcher.fetchBatch].
         *
         * USD/EUR results are projected back into every cache the legacy
         * pipeline reads — merged `{when_unix, price_usd, price_eur}`
         * history bodies, the latest-upstream snapshot, and the block
         * cache — so charts, change indicators, SATS inversion and BLOCK
         * widgets render exactly as they did off the bare endpoint.
         */
        private fun fetchAllViaBatch(
            context: Context,
            summaryCurrencies: Set<String>,
            extCodes: Set<String>,
            userInitiated: Boolean,
        ): Map<String, PriceResult> {
            val needSummary = summaryCurrencies.isNotEmpty()
            val needBlock = WidgetPrefs.CURRENCY_BLOCK in summaryCurrencies

            val codes = LinkedHashSet<String>()
            if (needSummary) {
                codes += WidgetPrefs.CURRENCY_USD
                codes += WidgetPrefs.CURRENCY_EUR
            }
            codes += extCodes
            if (codes.isEmpty()) return emptyMap()

            val batch = fetchBatchWithRetry(codes, userInitiated)
            val out = HashMap<String, PriceResult>()

            // Extended codes: persist + project per code.
            for (ccy in extCodes) {
                val result = batch.results[ccy]
                    ?: ExtendedResult.Error("No result for \"$ccy\"")
                out[ccy] = persistAndProjectExt(context, ccy, result)
            }

            if (needSummary) {
                val usd = batch.results[WidgetPrefs.CURRENCY_USD]
                val eur = batch.results[WidgetPrefs.CURRENCY_EUR]
                val usdOk = usd as? ExtendedResult.Success
                val eurOk = eur as? ExtendedResult.Success

                if (WidgetPrefs.CURRENCY_USD in summaryCurrencies) {
                    out[WidgetPrefs.CURRENCY_USD] = projectSummaryFromExt(usd)
                }
                if (WidgetPrefs.CURRENCY_EUR in summaryCurrencies) {
                    out[WidgetPrefs.CURRENCY_EUR] = projectSummaryFromExt(eur)
                }

                // Rebuild the legacy {when_unix, price_usd, price_eur}
                // cache bodies from the per-currency series so the
                // existing chart pipeline reads them unchanged. Only
                // overwrite when at least one currency brought data.
                val now = System.currentTimeMillis()
                for ((period, pick) in listOf(
                    WidgetPrefs.HISTORY_1D to { r: ExtendedResult.Success -> r.hist1dJson },
                    WidgetPrefs.HISTORY_7D to { r: ExtendedResult.Success -> r.hist7dJson },
                )) {
                    val usdSeries = usdOk?.let(pick)?.let { ExtendedFetcher.decodeSeries(it) }
                    val eurSeries = eurOk?.let(pick)?.let { ExtendedFetcher.decodeSeries(it) }
                    if (!usdSeries.isNullOrEmpty() || !eurSeries.isNullOrEmpty()) {
                        WidgetPrefs.saveHistoryJson(
                            context, period,
                            ExtendedFetcher.mergeSeriesToLegacyHist(usdSeries, eurSeries),
                            now,
                        )
                    }
                }

                if (usdOk != null || eurOk != null) {
                    WidgetPrefs.saveLatestUpstreamPrices(
                        context, usd = usdOk?.price, eur = eurOk?.price
                    )
                }

                batch.latestBlock?.let { block ->
                    WidgetPrefs.saveLatestBlock(
                        context, height = block.height, minerName = block.minerName
                    )
                }
                if (needBlock) {
                    val height = batch.latestBlock?.height
                    out[WidgetPrefs.CURRENCY_BLOCK] = if (height != null) {
                        PriceResult.Success(
                            price = height.toDouble(),
                            priceOneDayAgo = null,
                            priceOneWeekAgo = null,
                        )
                    } else {
                        // The whole batch failed (no entry parsed) or the
                        // response dropped the snapshot. Surface whatever
                        // error USD carried so diagnostics stay meaningful.
                        PriceResult.Error(
                            (usd as? ExtendedResult.Error)?.reason
                                ?: "No latest_block in response",
                            (usd as? ExtendedResult.Error)?.cause,
                        )
                    }
                }
            }
            return out
        }

        /** Project a USD/EUR batch entry into the summary [PriceResult] slot. */
        private fun projectSummaryFromExt(result: ExtendedResult?): PriceResult = when (result) {
            is ExtendedResult.Success -> PriceResult.Success(
                price = result.price,
                priceOneDayAgo = result.oneDayAgo,
                priceOneWeekAgo = result.oneWeekAgo,
            )
            is ExtendedResult.Error -> PriceResult.Error(result.reason, result.cause)
            null -> PriceResult.Error("No result in batch response")
        }

        /**
         * One [ExtendedFetcher.fetchBatch] attempt plus the standard retry
         * gating: a second attempt only for a user-initiated refresh, and
         * only for the codes whose first failure was transient (network /
         * HTTP 5xx). The retry's latest_block wins when it carried one.
         */
        private fun fetchBatchWithRetry(
            codes: Collection<String>,
            userInitiated: Boolean,
        ): BatchOutcome {
            var outcome = ExtendedFetcher.fetchBatch(codes)
            if (userInitiated) {
                val retryable = outcome.results.filterValues {
                    it is ExtendedResult.Error && it.isTransient
                }.keys
                if (retryable.isNotEmpty()) {
                    try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) {}
                    val second = ExtendedFetcher.fetchBatch(retryable)
                    outcome = BatchOutcome(
                        results = outcome.results + second.results,
                        latestBlock = second.latestBlock ?: outcome.latestBlock,
                    )
                }
            }
            return outcome
        }

        /**
         * Persist one extended currency's caches (latest price, upstream
         * sign, both history windows) and project the outcome into the
         * [PriceResult] shape the render loop understands.
         */
        private fun persistAndProjectExt(
            context: Context,
            ccy: String,
            result: ExtendedResult,
        ): PriceResult = when (result) {
            is ExtendedResult.Success -> {
                WidgetPrefs.saveExtLatestPrice(context, ccy, result.price)
                WidgetPrefs.saveExtSign(context, ccy, result.sign)
                result.hist1dJson?.let {
                    WidgetPrefs.saveExtHistoryJson(context, ccy, WidgetPrefs.HISTORY_1D, it)
                }
                result.hist7dJson?.let {
                    WidgetPrefs.saveExtHistoryJson(context, ccy, WidgetPrefs.HISTORY_7D, it)
                }
                PriceResult.Success(
                    price = result.price,
                    priceOneDayAgo = result.oneDayAgo,
                    priceOneWeekAgo = result.oneWeekAgo,
                )
            }
            is ExtendedResult.Error -> PriceResult.Error(result.reason, result.cause)
        }

        /**
         * Custom-backend path: fetch the extended currencies in [codes]
         * (one batched round trip to the official host) and persist +
         * project each one. Summary currencies are handled separately by
         * the caller via the user's own bare-summary feed.
         */
        private fun fetchExtendedCurrencies(
            context: Context,
            codes: Set<String>,
            userInitiated: Boolean,
        ): Map<String, PriceResult> {
            val batch = fetchBatchWithRetry(codes, userInitiated)
            val out = HashMap<String, PriceResult>(batch.results.size)
            for ((ccy, result) in batch.results) {
                out[ccy] = persistAndProjectExt(context, ccy, result)
            }
            return out
        }

        /**
         * If the cached data has aged past [STALE_AFTER_MS], repaint
         * the widget once so the user sees the grey "stale" treatment.
         * Otherwise leave the launcher's last frame in place — the
         * caller has decided we have nothing new worth showing.
         */
        private fun maybeRepaintIfStale(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val state = computeState(context, appWidgetId, hasFreshPrice = false)
            if (state == WidgetState.NORMAL) return
            val priceText = WidgetPrefs.loadLastPriceText(context, appWidgetId) ?: "—"
            mgr.updateAppWidget(
                appWidgetId, buildViews(context, priceText, appWidgetId, state)
            )
        }

        /**
         * Try once, and — only when it makes sense — wait [RETRY_DELAY_MS]
         * and try again. [url] is whatever the user has configured —
         * falls back to the bundled default when no override has been set.
         *
         * The retry is gated twice:
         *   - [userInitiated] must be true. A scheduled tick runs inside
         *     a goAsync() broadcast whose ~10s budget can't afford a
         *     sleep+second-fetch; a silent miss just waits for the next
         *     tick, which is exactly the failure mode the time-based
         *     stale threshold was designed around.
         *   - the first failure must be transient ([SummaryResult.Error.isTransient]):
         *     network errors and HTTP 5xx can plausibly clear in 2
         *     seconds, but a 404 or a malformed body will fail the same
         *     way twice — retrying those just delays the error frame.
         */
        private fun fetchSummaryWithRetry(url: String, userInitiated: Boolean): SummaryResult {
            val first = SummaryFetcher.fetchSummary(url)
            if (first is SummaryResult.Success) return first
            if (!userInitiated) return first
            if (!(first as SummaryResult.Error).isTransient) return first
            try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) {}
            return SummaryFetcher.fetchSummary(url)
        }

        /**
         * Project the unified summary back into the per-currency shape
         * the existing rendering pipeline expects. The block widgets are
         * special: they have no real "price" — we synthesise a
         * [PriceResult.Success] carrying the block height as the price
         * so the rest of the render path stays uniform.
         */
        private fun buildPriceResults(
            currencies: Collection<String>,
            result: SummaryResult,
        ): Map<String, PriceResult> {
            if (currencies.isEmpty()) return emptyMap()
            return when (result) {
                is SummaryResult.Error -> {
                    val err = PriceResult.Error(result.reason, result.cause)
                    currencies.associateWith { err }
                }
                is SummaryResult.Success -> {
                    val s = result.summary
                    currencies.associateWith { ccy ->
                        when (ccy.uppercase(Locale.ROOT)) {
                            WidgetPrefs.CURRENCY_BLOCK -> {
                                val height = s.latestBlock?.height
                                if (height == null) {
                                    PriceResult.Error("No latest_block in summary")
                                } else {
                                    PriceResult.Success(
                                        price = height.toDouble(),
                                        priceOneDayAgo = null,
                                        priceOneWeekAgo = null,
                                    )
                                }
                            }
                            else -> {
                                val current = s.currentPrice(ccy)
                                if (current == null) {
                                    PriceResult.Error("No \"$ccy\" in summary")
                                } else {
                                    PriceResult.Success(
                                        price = current,
                                        priceOneDayAgo = s.oneDayAgoPrice(ccy),
                                        priceOneWeekAgo = s.oneWeekAgoPrice(ccy),
                                    )
                                }
                            }
                        }
                    }
                }
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
         * For network-backed widgets, what currency key in the unified
         * summary do we actually need? SATS rides on the USD price slot
         * (the JSON doesn't ship sats directly — we invert at render
         * time); BLOCK uses its own slot since the price field isn't
         * meaningful for it; everything else maps to itself.
         */
        private fun upstreamCurrencyFor(currency: String): String = when {
            currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true) ->
                WidgetPrefs.CURRENCY_USD
            currency.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true) ->
                WidgetPrefs.CURRENCY_BLOCK
            // Upper-cased so the lookup always matches the result map,
            // which [fetchExtendedCurrencies] keys by upper-cased code.
            else -> currency.uppercase(Locale.ROOT)
        }

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

        /**
         * Paint a single network-backed widget from a shared result map.
         *
         * On [PriceResult.Error] we still record the failure for
         * diagnostics, but the *visual* repaint is gated on
         * [userInitiated]. A failed scheduled refresh just bumps the
         * counter and leaves the screen alone (the user won't notice
         * one missed tick); a failed user tap still surfaces the
         * cached price with stale/offline styling so the tap feels
         * acknowledged.
         */
        private fun renderResult(
            context: Context,
            mgr: AppWidgetManager,
            appWidgetId: Int,
            results: Map<String, PriceResult>,
            userInitiated: Boolean,
        ) {
            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val showDecimals = WidgetPrefs.loadShowDecimals(context, appWidgetId)
            val separator = WidgetPrefs.loadSeparator(context, appWidgetId)

            val isSats = currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
            val isBlock = currency.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true)
            val lookupKey = upstreamCurrencyFor(currency)
            val result = results[lookupKey]
                ?: PriceResult.Error("No result for \"$currency\"")

            when (result) {
                is PriceResult.Success -> {
                    // BLOCK mode: the "price" is actually the block
                    // height. Tracked-amount, decimals, and Moscow Time
                    // are all meaningless for an integer count, so we
                    // bypass them. The thousands separator setting is
                    // honoured because "948,347" reads better at a
                    // glance than "948347".
                    if (isBlock) {
                        val height = result.price
                        val text = PriceFormat.format(height, showDecimals = false, separator)
                        WidgetPrefs.recordSuccess(
                            context, appWidgetId, System.currentTimeMillis(),
                            priceText = text,
                            rawPrice = height,
                            oneDayAgo = null,
                            oneWeekAgo = null,
                        )
                        val state = computeState(context, appWidgetId, hasFreshPrice = true)
                        mgr.updateAppWidget(
                            appWidgetId, buildViews(context, text, appWidgetId, state)
                        )
                        return
                    }

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
                        PriceFormat.formatPrice(displayed, showDecimals, separator)
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
                    if (!userInitiated) {
                        // Scheduled tick: don't visibly clobber the
                        // existing frame. Only repaint if the cached
                        // data has aged past the stale threshold.
                        maybeRepaintIfStale(context, mgr, appWidgetId)
                        return
                    }
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
                // userInitiated=true: the user just saved settings and
                // is presumably looking at the screen — keep the
                // visible "…" → result transition.
                refreshWidgets(
                    context, appWidgetManager, intArrayOf(appWidgetId),
                    userInitiated = true,
                )
            }
        }

        /**
         * Decide what visual state the widget should be in for this
         * paint. NORMAL when we have (or recently had) good data,
         * STALE only once the cached price has aged past
         * [STALE_AFTER_MS], BATTERY_SAVER when the system is power-saving.
         *
         * Time-based rather than failure-count-based on purpose: a
         * couple of failed scheduled fetches right after wake (typical
         * when the radio is still reattaching) used to trip the old
         * `failCount >= 2` rule and grey out a widget whose displayed
         * price was still only minutes old.
         *
         * For a brand-new widget that has never had a successful
         * fetch we fall back to the older network/failure-aware logic
         * — that path needs a "we tried and it didn't work" hint
         * because there isn't any cached price for the user to read.
         */
        private fun computeState(
            context: Context,
            appWidgetId: Int,
            hasFreshPrice: Boolean
        ): WidgetState {
            if (isPowerSaveOn(context)) return WidgetState.BATTERY_SAVER
            if (hasFreshPrice) return WidgetState.NORMAL
            val lastSuccess = WidgetPrefs.loadLastSuccess(context, appWidgetId)
            if (lastSuccess <= 0L) {
                // No baseline yet — keep the legacy hints so a widget
                // added while offline still looks "off" instead of
                // pretending all is well with a "—" placeholder.
                if (!hasNetwork(context)) return WidgetState.OFFLINE
                val failCount = WidgetPrefs.loadFailCount(context, appWidgetId)
                if (failCount >= 2) return WidgetState.STALE
                return WidgetState.NORMAL
            }
            val age = System.currentTimeMillis() - lastSuccess
            if (age > STALE_AFTER_MS) return WidgetState.STALE
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
            val symbol = WidgetPrefs.symbolFor(context, currency)
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
            //
            // The base colour is the user's chosen swatch when they set
            // one, otherwise the theme default (R.color.widget_text). The
            // dim is a pure alpha multiply so the chosen hue is preserved
            // through stale/offline states — earlier versions hard-coded
            // R.color.widget_text here, so a failed refresh always looked
            // grey regardless of the user's pick.
            val textAlpha = if (state == WidgetState.NORMAL) 1.0f else 0.6f
            val savedColor = WidgetPrefs.loadPriceTextColor(context, appWidgetId)
            val baseTextColor = if (savedColor == WidgetPrefs.PRICE_TEXT_COLOR_DEFAULT)
                context.getColor(R.color.widget_text) else savedColor
            val dimmedTextColor = applyAlpha(baseTextColor, textAlpha)
            views.setTextColor(R.id.price_text, dimmedTextColor)

            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val isBlock = currency.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true)
            val hideUnit = WidgetPrefs.loadHideUnitLabel(context, appWidgetId)
            views.setViewVisibility(
                R.id.unit_label, if (hideUnit) View.GONE else View.VISIBLE
            )
            if (!hideUnit) {
                // BLOCK mode commandeers the unit-label slot for the
                // miner / pool name (e.g. "SpiderPool"). Falls back to
                // a localised "Unknown miner" if the upstream didn't
                // identify the block's miner — better than rendering
                // empty space at the top of the widget.
                val unitText = if (isBlock) {
                    WidgetPrefs.loadLatestBlockMiner(context)
                        ?: context.getString(R.string.block_miner_unknown)
                } else {
                    formatUnitLabel(trackedAmount, currency)
                }
                views.setTextViewText(R.id.unit_label, unitText)
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
            // BLOCK mode has no notion of a "change %" — the diagonal
            // sparkline already says block height keeps going up.
            // Suppress the indicator entirely instead of trying to
            // surface a meaningless number at the bottom.
            val ccy = WidgetPrefs.loadCurrency(context, appWidgetId)
            if (ccy.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true)) {
                views.setViewVisibility(R.id.change_indicator, View.GONE)
                return
            }

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

            // BLOCK mode: block height only ever climbs, so the chart
            // is a deliberate stylised diagonal going from bottom-left
            // to top-right. Same "the line is the joke" treatment as
            // BTC mode's flat line; bypasses every cache and series
            // code path.
            if (currency.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true)) {
                val (widthPx, heightPx) = widgetPixelSize(context, appWidgetId)
                val color = context.getColor(R.color.sparkline_up)
                val density = context.resources.displayMetrics.density
                val strokePx = (1.5f * density).coerceAtLeast(1.5f)
                return SparklineRenderer.renderDiagonal(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    color = color,
                    strokePx = strokePx,
                )
            }

            // Extended currencies keep their own per-currency 7-day series
            // cache ({t,v} points from /api/candles) rather than the USD/EUR
            // summary arrays — render the sparkline straight from that.
            if (CurrencyCatalog.isExtendedCurrency(currency)) {
                return buildExtendedSparkline(context, appWidgetId, currency)
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
            // every successful summary.json round trip; null only on the
            // very first launch or when no widget has fetched the
            // chart's base currency yet. Skipped entirely when the saved
            // snapshot is older than [LATEST_POINT_MAX_AGE_MS] — pinning
            // a stale price to the right edge would misstate the trend.
            val latestAt = WidgetPrefs.loadLatestUpstreamAt(context)
            val latestFresh =
                latestAt > 0 && System.currentTimeMillis() - latestAt <= LATEST_POINT_MAX_AGE_MS
            val latestBase = if (latestFresh)
                WidgetPrefs.loadLatestUpstreamPrice(context, baseCurrency)
            else null
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
         * Sparkline for an extended (non-USD/EUR) currency, drawn from its
         * per-currency 7-day series cache. The change-indicator window
         * selects how much of the series to show: CHANGE_1D trims to the
         * last 24h, CHANGE_1W shows the whole week. The latest fetched
         * price is appended as the rightmost "now" point when recent
         * enough — same convention as the summary path. Returns null (chart
         * hides) for derived currencies with no cached history, or when the
         * series is too short to plot.
         */
        private fun buildExtendedSparkline(
            context: Context,
            appWidgetId: Int,
            currency: String,
        ): Bitmap? {
            // Each window has its own cached series (24h vs 7d), exactly like
            // the USD/EUR summary path — pick the one the change-indicator
            // setting selects.
            val mode = WidgetPrefs.loadChangeIndicator(context, appWidgetId)
            val period = if (mode == WidgetPrefs.CHANGE_1W)
                WidgetPrefs.HISTORY_7D else WidgetPrefs.HISTORY_1D
            val cached = WidgetPrefs.loadExtHistoryJson(context, currency, period) ?: return null
            val points = ExtendedFetcher.decodeSeries(cached)
            if (points.size < 2) return null
            val raw = DoubleArray(points.size) { points[it].second }

            val latestAt = WidgetPrefs.loadExtLatestAt(context, currency)
            val latestFresh = latestAt > 0 &&
                System.currentTimeMillis() - latestAt <= LATEST_POINT_MAX_AGE_MS
            val latest = if (latestFresh)
                WidgetPrefs.loadExtLatestPrice(context, currency) else null
            val values = appendLatest(raw, latest)
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
            // Cap kept modest on purpose: RemoteViews bitmaps cross the
            // Binder, and a 800×400 ARGB frame is ~1.3 MB — close enough
            // to the transaction limit to be a crash risk on resize. At
            // 480×240 (~460 KB) the faint low-alpha line is visually
            // indistinguishable, fitXY scales it up for free.
            val widthPx = (widthDp * density).toInt().coerceIn(64, 480)
            val heightPx = (heightDp * density).toInt().coerceIn(32, 240)
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
            // VALIDATED filters out connections that exist but can't
            // reach the internet — most commonly a captive portal
            // (hotel/airport wifi before login). Without it those
            // count as "online", the fetch then fails or gets fed the
            // portal's HTML, and the user sees a spurious error state.
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
