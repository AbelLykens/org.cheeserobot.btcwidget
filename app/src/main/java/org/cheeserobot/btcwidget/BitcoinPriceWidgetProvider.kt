package org.cheeserobot.btcwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Widget provider — orchestrates rendering and the network fetch.
 *
 * Lifecycle hooks we override:
 *   • onUpdate   — system tells us to refresh one or more widgets.
 *   • onReceive  — also handles ACTION_REFRESH (user tap on widget when
 *                  battery saver is OFF, or a refresh broadcast from
 *                  another component such as BatterySaverInfoActivity
 *                  after the user disables battery saver).
 *   • onDeleted  — clean up per-widget prefs.
 *
 * When battery saver is ON, the widget's tap target is rewired to
 * launch [BatterySaverInfoActivity] instead of broadcasting
 * ACTION_REFRESH — Toasts from a BroadcastReceiver context are
 * suppressed by Android when the user has notifications disabled, so
 * an Activity is the only reliable way to surface "the widget can't
 * update right now".
 *
 * Note: we cannot listen for ACTION_POWER_SAVE_MODE_CHANGED from a
 * manifest receiver — Android sends that broadcast with
 * FLAG_RECEIVER_REGISTERED_ONLY. Live detection happens inside
 * BatterySaverInfoActivity (via a dynamically registered receiver) so
 * that turning battery saver off from the system dialog flips the
 * widget back to colour immediately. Other transitions are picked up
 * on the next scheduled refresh.
 */
class BitcoinPriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (id in appWidgetIds) {
                    refreshWidgetSync(context, appWidgetManager, id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            handleRefreshAction(context, intent)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            WidgetPrefs.deleteAll(context, id)
        }
    }

    private fun handleRefreshAction(context: Context, intent: Intent) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: mgr.getAppWidgetIds(
                ComponentName(context, BitcoinPriceWidgetProvider::class.java)
            )

        if (isPowerSaveOn(context)) {
            // Don't burn battery when the user explicitly asked the OS
            // to conserve it. Repaint everything in greyed-out state.
            for (id in ids) paintFromCache(context, mgr, id)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (id in ids) refreshWidgetSync(context, mgr, id)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Paint the widget from saved state without making any network call.
     * Used when battery saver kicks in, so the icon's tint and click
     * target flip promptly.
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
         * Visual states the widget can be in. Drives icon tint/alpha and
         * which click action is wired to the root view.
         */
        private enum class WidgetState {
            NORMAL,           // fresh price, normal colour
            BATTERY_SAVER,    // power-save mode active; greyed
            OFFLINE,          // no network connectivity; greyed
            STALE,            // ≥2 consecutive fetch failures; greyed
        }

        /**
         * Synchronously refresh a single widget: paint a "loading" frame,
         * fetch the price (with one retry), then paint the result.
         * Call from a background dispatcher.
         */
        private fun refreshWidgetSync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val showDecimals = WidgetPrefs.loadShowDecimals(context, appWidgetId)
            val separator = WidgetPrefs.loadSeparator(context, appWidgetId)

            // "BTC" mode is a fun easter-egg — one Bitcoin always equals
            // one Bitcoin (×trackedAmount). Skip the network entirely.
            if (currency.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true)) {
                val text = PriceFormat.format(trackedAmount, showDecimals, separator)
                WidgetPrefs.recordSuccess(
                    context, appWidgetId, System.currentTimeMillis(), priceText = text
                )
                val state = computeState(context, appWidgetId, hasFreshPrice = true)
                appWidgetManager.updateAppWidget(
                    appWidgetId, buildViews(context, text, appWidgetId, state)
                )
                return
            }

            // No network → don't waste a retry on a guaranteed failure.
            // Show last-known price (if any) in greyed-out OFFLINE state.
            if (!hasNetwork(context)) {
                WidgetPrefs.recordFailure(context, appWidgetId, reason = "No network")
                val cached = WidgetPrefs.loadLastPriceText(context, appWidgetId) ?: "—"
                appWidgetManager.updateAppWidget(
                    appWidgetId,
                    buildViews(context, cached, appWidgetId, WidgetState.OFFLINE)
                )
                return
            }

            // Loading frame — preserve previous visual state otherwise
            // the icon flickers from grey→orange→grey on every tap.
            val loadingState = computeState(context, appWidgetId, hasFreshPrice = false)
            appWidgetManager.updateAppWidget(
                appWidgetId, buildViews(context, "…", appWidgetId, loadingState)
            )

            val result = fetchWithRetry(currency)
            when (result) {
                is PriceResult.Success -> {
                    val displayed = result.price * trackedAmount
                    val text = PriceFormat.format(displayed, showDecimals, separator)
                    WidgetPrefs.recordSuccess(
                        context, appWidgetId, System.currentTimeMillis(), priceText = text
                    )
                    val state = computeState(context, appWidgetId, hasFreshPrice = true)
                    appWidgetManager.updateAppWidget(
                        appWidgetId, buildViews(context, text, appWidgetId, state)
                    )
                }
                is PriceResult.Error -> {
                    WidgetPrefs.recordFailure(
                        context, appWidgetId, reason = result.reason
                    )
                    // On *any* fetch failure with a cached price, show
                    // the last-known value with a greyed icon. We don't
                    // wait for two consecutive failures here — the user
                    // explicitly asked for the cached-price-with-grey-
                    // icon behaviour.
                    val cached = WidgetPrefs.loadLastPriceText(context, appWidgetId)
                    val text = cached ?: "—"
                    val state = errorState(context)
                    appWidgetManager.updateAppWidget(
                        appWidgetId, buildViews(context, text, appWidgetId, state)
                    )
                }
            }
        }

        /**
         * Greyed visual state to render after a fetch failure. If
         * battery saver / no-network is the *root* cause, attribute it
         * to that specifically so the click target on the widget makes
         * sense (battery saver opens the dialog; offline doesn't).
         */
        private fun errorState(context: Context): WidgetState {
            if (isPowerSaveOn(context)) return WidgetState.BATTERY_SAVER
            if (!hasNetwork(context)) return WidgetState.OFFLINE
            return WidgetState.STALE
        }

        /**
         * Try once, and if that fails wait [RETRY_DELAY_MS] and try again.
         * Cheap enough (single extra HTTP call) and protects against
         * transient flakiness on flaky cell connections.
         */
        private fun fetchWithRetry(currency: String): PriceResult {
            val first = PriceFetcher.fetchPrice(currency)
            if (first is PriceResult.Success) return first
            try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) {}
            val second = PriceFetcher.fetchPrice(currency)
            return second
        }

        /**
         * Public hook used by the configuration activity right after the
         * user saves their settings. Triggers an async refresh so the
         * widget reflects the new settings immediately.
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val state = computeState(context, appWidgetId, hasFreshPrice = false)
            // Paint loading state immediately on the calling (UI) thread.
            appWidgetManager.updateAppWidget(
                appWidgetId, buildViews(context, "…", appWidgetId, state)
            )
            // Then fetch in the background.
            CoroutineScope(Dispatchers.IO).launch {
                delay(50)
                refreshWidgetSync(context, appWidgetManager, appWidgetId)
            }
        }

        private fun computeState(
            context: Context,
            appWidgetId: Int,
            hasFreshPrice: Boolean
        ): WidgetState {
            if (isPowerSaveOn(context)) return WidgetState.BATTERY_SAVER
            if (!hasNetwork(context)) return WidgetState.OFFLINE
            // After 2+ consecutive failures we visually mark the widget
            // as stale so the user notices their data is old.
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

            // Compose the symbol + price string. Symbol comes from the
            // currency choice; the priceText is already locale/decimal-
            // formatted by the caller.
            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val symbol = WidgetPrefs.symbolFor(currency)
            views.setTextViewText(R.id.price_text, "$symbol $priceText")

            // Unit label: "<amount> BTC". Hidden if the user opted out
            // of the caption.
            val trackedAmount = WidgetPrefs.loadTrackedAmount(context, appWidgetId)
            val hideUnit = WidgetPrefs.loadHideUnitLabel(context, appWidgetId)
            views.setViewVisibility(
                R.id.unit_label, if (hideUnit) View.GONE else View.VISIBLE
            )
            if (!hideUnit) {
                views.setTextViewText(R.id.unit_label, formatUnitLabel(trackedAmount))
            }

            // Show / hide the bitcoin logo per user setting.
            val hideLogo = WidgetPrefs.loadHideLogo(context, appWidgetId)
            views.setViewVisibility(
                R.id.btc_icon, if (hideLogo) View.GONE else View.VISIBLE
            )

            // Icon tint by state.
            val iconRes = when (state) {
                WidgetState.NORMAL -> R.drawable.ic_bitcoin
                WidgetState.BATTERY_SAVER, WidgetState.STALE, WidgetState.OFFLINE ->
                    R.drawable.ic_bitcoin_grey
            }
            views.setImageViewResource(R.id.btc_icon, iconRes)
            val iconAlpha = if (state == WidgetState.NORMAL) ICON_ALPHA_NORMAL else ICON_ALPHA_GREY
            views.setInt(R.id.btc_icon, "setImageAlpha", iconAlpha)

            // Background opacity — applied to the dedicated background
            // ImageView. 0..100% maps to 0..255 image alpha.
            val opacityPct = WidgetPrefs.loadOpacity(context, appWidgetId)
            val opacity255 = (opacityPct.coerceIn(0, 100) * 255 / 100)
            views.setInt(R.id.background_view, "setImageAlpha", opacity255)

            // Click target depends on state. When battery saver is ON we
            // launch a tiny activity that explains the situation.
            val pi = if (state == WidgetState.BATTERY_SAVER) {
                buildBatterySaverPendingIntent(context, appWidgetId)
            } else {
                buildRefreshPendingIntent(context, appWidgetId)
            }
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }

        private fun formatUnitLabel(amount: Double): String {
            // "1 BTC", "0.5 BTC", "2 BTC". Strip trailing zeros so we
            // don't say "1.00 BTC" when 1 was meant.
            val rendered = if (amount == amount.toLong().toDouble()) {
                amount.toLong().toString()
            } else {
                // Up to 8 decimal places (BTC has 8 satoshis precision),
                // trailing zeros stripped.
                val s = String.format(java.util.Locale.US, "%.8f", amount)
                    .trimEnd('0').trimEnd('.')
                if (s.isEmpty()) "0" else s
            }
            return "$rendered BTC"
        }

        private fun buildRefreshPendingIntent(
            context: Context,
            appWidgetId: Int
        ): PendingIntent {
            val intent = Intent(context, BitcoinPriceWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
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

        private fun isPowerSaveOn(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isPowerSaveMode == true
        }

        /**
         * True iff the device has a usable network connection. Cheap
         * pre-check to avoid burning a futile HTTP request when the
         * device is in airplane mode or out of signal.
         */
        private fun hasNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
