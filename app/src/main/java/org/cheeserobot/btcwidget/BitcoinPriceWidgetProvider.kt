package org.cheeserobot.btcwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class BitcoinPriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Use goAsync so the broadcast receiver process stays alive while the
        // network fetch runs.
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
            val mgr = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: mgr.getAppWidgetIds(
                    ComponentName(context, BitcoinPriceWidgetProvider::class.java)
                )
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (id in ids) refreshWidgetSync(context, mgr, id)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            WidgetPrefs.deleteCurrency(context, id)
            Notifier.cancelError(context, id)
        }
    }

    companion object {
        const val ACTION_REFRESH = "org.cheeserobot.btcwidget.ACTION_REFRESH"

        /**
         * Synchronously refresh a single widget: paint a "loading" frame,
         * fetch the price (blocking), then paint the result. Call from a
         * background dispatcher.
         */
        private fun refreshWidgetSync(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val symbol = WidgetPrefs.symbolFor(currency)

            // Loading state — visible while the fetch is in flight.
            appWidgetManager.updateAppWidget(
                appWidgetId,
                buildViews(context, symbol, "…", appWidgetId)
            )

            when (val result = PriceFetcher.fetchPrice(currency)) {
                is PriceResult.Success -> {
                    Notifier.cancelError(context, appWidgetId)
                    appWidgetManager.updateAppWidget(
                        appWidgetId,
                        buildViews(context, symbol, formatWhole(result.price), appWidgetId)
                    )
                }
                is PriceResult.Error -> {
                    Notifier.notifyError(context, appWidgetId, result.reason)
                    appWidgetManager.updateAppWidget(
                        appWidgetId,
                        buildViews(context, symbol, "!", appWidgetId)
                    )
                }
            }
        }

        /**
         * Public hook used by the configuration activity right after the user
         * picks USD or EUR. Triggers an async refresh.
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val currency = WidgetPrefs.loadCurrency(context, appWidgetId)
            val symbol = WidgetPrefs.symbolFor(currency)
            // Paint loading state immediately on the calling (UI) thread.
            appWidgetManager.updateAppWidget(
                appWidgetId,
                buildViews(context, symbol, "…", appWidgetId)
            )
            // Then fetch in the background.
            CoroutineScope(Dispatchers.IO).launch {
                refreshWidgetSync(context, appWidgetManager, appWidgetId)
            }
        }

        private fun buildViews(
            context: Context,
            symbol: String,
            priceText: String,
            appWidgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_bitcoin_price)
            // Single auto-sizing text — combines symbol + price so they
            // scale together as one large line.
            views.setTextViewText(R.id.price_text, "$symbol $priceText")

            // Tap the widget to force a refresh.
            val refreshIntent = Intent(context, BitcoinPriceWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, appWidgetId, refreshIntent, flags)
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }

        private fun formatWhole(price: Double): String {
            val rounded = price.toLong()
            val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
            nf.isGroupingUsed = true
            return nf.format(rounded)
        }
    }
}
