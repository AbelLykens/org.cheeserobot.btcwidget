package org.cheeserobot.btcwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Plain launcher screen — the closest thing this app has to a "main"
 * screen. It now serves three jobs:
 *
 *   1. Walk the user through adding the widget (how-to + open-home
 *      shortcut).
 *   2. Show a live preview so the user can sanity-check that the price
 *      feed is reachable.
 *   3. **Surface fetch errors** for any widgets they've already placed.
 *      We removed the system-notification path entirely, so this is
 *      where errors live now: each placed widget gets a status line
 *      showing either the last successful price + when it was fetched,
 *      or the most recent error reason.
 *
 * Subclasses [Activity] (not AppCompatActivity) so we don't have to
 * depend on the appcompat library.
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        val previewView = findViewById<TextView>(R.id.preview_text)

        findViewById<Button>(R.id.btn_add_widget).setOnClickListener {
            requestPinWidget()
        }

        findViewById<Button>(R.id.btn_open_home).setOnClickListener {
            goToHomeScreen()
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            refreshAllWidgets()
        }

        // Run a quick connectivity check so the user can verify the price
        // feed before placing the widget.
        previewView.text = getString(R.string.preview_loading)
        CoroutineScope(Dispatchers.IO).launch {
            val usd = PriceFetcher.fetchPrice(WidgetPrefs.CURRENCY_USD)
            val eur = PriceFetcher.fetchPrice(WidgetPrefs.CURRENCY_EUR)
            withContext(Dispatchers.Main) {
                previewView.text = formatPreview(usd, eur)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the per-widget status panel whenever we come back to
        // this screen — gives the user the latest info each time.
        renderWidgetStatuses()
    }

    private fun renderWidgetStatuses() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(
            ComponentName(this, BitcoinPriceWidgetProvider::class.java)
        )
        val container = findViewById<LinearLayout>(R.id.status_container)
        val header = findViewById<TextView>(R.id.status_header)

        container.removeAllViews()

        if (ids.isEmpty()) {
            header.visibility = View.GONE
            container.visibility = View.GONE
            return
        }
        header.visibility = View.VISIBLE
        container.visibility = View.VISIBLE

        // Stable display order — Android assigns ids in the order
        // widgets were placed, so sorting just makes the list match
        // intuition rather than insertion timing.
        val sorted = ids.sortedArray()
        val now = System.currentTimeMillis()

        sorted.forEachIndexed { index, id ->
            val line = TextView(this).apply {
                textSize = 13f
                setPadding(0, 4, 0, 4)
                text = describeWidget(this@LauncherActivity, id, displayIndex = index + 1, now)
            }
            container.addView(
                line,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            )
        }
    }

    private fun describeWidget(
        ctx: android.content.Context,
        appWidgetId: Int,
        displayIndex: Int,
        nowEpochMs: Long,
    ): String {
        val currency = WidgetPrefs.loadCurrency(ctx, appWidgetId)
        val symbol = WidgetPrefs.symbolFor(currency)
        val cachedPrice = WidgetPrefs.loadLastPriceText(ctx, appWidgetId)
        val lastSuccess = WidgetPrefs.loadLastSuccess(ctx, appWidgetId)
        val lastError = WidgetPrefs.loadLastError(ctx, appWidgetId)
        val lastErrorAt = WidgetPrefs.loadLastErrorAt(ctx, appWidgetId)

        // SATS has no Unicode glyph, so symbolFor returns "". Display
        // it with a "sats" suffix instead of the empty prefix.
        fun decorate(price: String): String =
            if (symbol.isEmpty()) "$price sats" else "$symbol $price"

        // The "BTC" easter-egg currency is special: the displayed value
        // doesn't change and there's no fetch failure to report.
        if (currency.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true)) {
            val text = if (cachedPrice != null) "$symbol $cachedPrice" else "$symbol 1"
            return getString(
                R.string.launcher_widget_status_btc_mode, displayIndex, text
            )
        }

        // Most recent error wins display priority — the user wants to
        // see WHY their widget is greyed.
        return when {
            lastError != null && lastErrorAt > lastSuccess -> {
                getString(
                    R.string.launcher_widget_status_error,
                    displayIndex,
                    currency,
                    truncate(lastError, 80),
                    relativeTime(nowEpochMs - lastErrorAt)
                )
            }
            cachedPrice != null && lastSuccess > 0 -> {
                getString(
                    R.string.launcher_widget_status_ok,
                    displayIndex,
                    currency,
                    decorate(cachedPrice),
                    relativeTime(nowEpochMs - lastSuccess)
                )
            }
            else -> {
                getString(
                    R.string.launcher_widget_status_never_fetched,
                    displayIndex,
                    currency
                )
            }
        }
    }

    private fun relativeTime(diffMs: Long): String {
        if (diffMs < 0) return getString(R.string.time_just_now)
        val seconds = diffMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> getString(R.string.time_just_now)
            minutes < 60 -> getString(R.string.time_minutes_ago, minutes.toInt())
            hours < 24 -> getString(R.string.time_hours_ago, hours.toInt())
            else -> getString(R.string.time_days_ago, days.toInt())
        }
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    /**
     * Asks the user's launcher to pin a fresh BTC Price widget on the
     * home screen. Modern launchers (Pixel, Samsung One UI, etc.) show
     * a confirmation dialog with a preview; older or third-party
     * launchers may not support this API at all, in which case we
     * fall back to dropping the user on the home screen with a hint.
     *
     * Because the widget declares a configure activity, the system
     * automatically launches WidgetConfigActivity once the user
     * accepts the pin — no extra plumbing needed here.
     */
    private fun requestPinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        // isRequestPinAppWidgetSupported was added in API 26 — same as
        // our minSdk, so no version guard required.
        if (!mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.toast_pin_unsupported, Toast.LENGTH_LONG).show()
            goToHomeScreen()
            return
        }

        val provider = ComponentName(this, BitcoinPriceWidgetProvider::class.java)
        val accepted = try {
            mgr.requestPinAppWidget(provider, /* extras = */ null, /* successCallback = */ null)
        } catch (_: IllegalStateException) {
            // Some OEM launchers throw this when in an unusual state
            // (e.g. setup wizard mid-flow). Treat as unsupported.
            false
        }
        if (!accepted) {
            Toast.makeText(this, R.string.toast_pin_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun goToHomeScreen() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }

    private fun refreshAllWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(
            ComponentName(this, BitcoinPriceWidgetProvider::class.java)
        )
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_widgets, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, BitcoinPriceWidgetProvider::class.java).apply {
            action = BitcoinPriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        sendBroadcast(intent)
        Toast.makeText(
            this,
            getString(R.string.toast_refresh_sent, ids.size),
            Toast.LENGTH_SHORT
        ).show()
        // Re-render statuses ~1s after triggering refresh so the panel
        // catches up with the new last-success / last-error values.
        findViewById<View>(R.id.status_container).postDelayed(
            { renderWidgetStatuses() }, 1500
        )
    }

    private fun formatPreview(usd: PriceResult, eur: PriceResult): String {
        fun line(label: String, r: PriceResult): String = when (r) {
            is PriceResult.Success -> "$label  ${formatWhole(r.price)}"
            is PriceResult.Error -> "$label  — (${r.reason.take(60)})"
        }
        return line("$", usd) + "\n" + line("€", eur)
    }

    private fun formatWhole(price: Double): String {
        val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
        nf.isGroupingUsed = true
        return nf.format(price.toLong())
    }
}
