package org.cheeserobot.btcwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /**
     * Most recently launched preview-fetch coroutine. Cancelled before
     * starting a new one so a slow request that's still in flight when
     * the user taps "Refresh existing widgets" can't clobber the fresher
     * result. Without this guard the preview would briefly show "No
     * network" and then flip back to a stale success when the older
     * request finally returned.
     */
    private var previewJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        findViewById<Button>(R.id.btn_add_widget).setOnClickListener {
            requestPinWidget()
        }

        findViewById<Button>(R.id.btn_open_home).setOnClickListener {
            goToHomeScreen()
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            refreshAllWidgets()
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the per-widget status panel whenever we come back to
        // this screen — gives the user the latest info each time.
        renderWidgetStatuses()
        // Re-run the preview fetch on every resume too. onCreate used to
        // do this once, but that meant a stale "success" frame stuck
        // around forever if the user toggled airplane mode after first
        // open. Re-fetching on resume keeps the preview honest with the
        // current network state, and feels cheap because the activity is
        // already in the foreground when this runs.
        loadPreview()
    }

    override fun onPause() {
        super.onPause()
        // Drop the in-flight fetch so it can't wake up after the user
        // has moved on (e.g. paused the activity to flip airplane mode).
        previewJob?.cancel()
        previewJob = null
    }

    /**
     * Fetch USD + EUR prices and paint the preview box. Short-circuits
     * with a clear "No network" frame when the device reports no usable
     * connection — that mirrors what the home-screen widget does, so the
     * two surfaces stay in sync about why nothing's loading.
     */
    private fun loadPreview() {
        val previewView = findViewById<TextView>(R.id.preview_text)
        previewJob?.cancel()

        if (!BitcoinPriceWidgetProvider.hasNetwork(this)) {
            previewView.text = buildErrorPreview(
                getString(R.string.preview_error_no_network),
                getString(R.string.preview_error_no_network_detail),
            )
            return
        }

        previewView.text = getString(R.string.preview_loading)
        previewJob = CoroutineScope(Dispatchers.IO).launch {
            // One round trip pulls everything: the unified summary feed
            // carries both fiat prices (and the latest-block info we
            // don't need on this screen). We project it back into the
            // per-currency PriceResult shape the existing formatter
            // already understands.
            val summary = SummaryFetcher.fetchSummary()
            val usd = priceResultFor(summary, WidgetPrefs.CURRENCY_USD)
            val eur = priceResultFor(summary, WidgetPrefs.CURRENCY_EUR)
            withContext(Dispatchers.Main) {
                previewView.text = formatPreview(usd, eur)
            }
        }
    }

    /** Turn a [SummaryResult] into a per-currency [PriceResult]. */
    private fun priceResultFor(result: SummaryResult, currency: String): PriceResult {
        return when (result) {
            is SummaryResult.Error ->
                PriceResult.Error(result.reason, result.cause)
            is SummaryResult.Success -> {
                val price = result.summary.currentPrice(currency)
                if (price == null) PriceResult.Error("No \"$currency\" in summary")
                else PriceResult.Success(price)
            }
        }
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
            container.addView(buildWidgetRow(id, displayIndex = index + 1, now))
        }
    }

    /**
     * Build a single row in the "Your widgets" list: status text on the
     * left, a pencil edit button on the right that re-opens
     * [WidgetConfigActivity] for that widget id. Previously this was a
     * plain TextView, but folks with the widget already pinned had no
     * obvious path back into settings without dragging the widget around
     * to expose Android 12+'s reconfigure pencil — this surfaces that
     * affordance directly in the app.
     */
    private fun buildWidgetRow(
        appWidgetId: Int,
        displayIndex: Int,
        nowEpochMs: Long,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val statusView = TextView(this).apply {
            textSize = 13f
            text = describeWidget(this@LauncherActivity, appWidgetId, displayIndex, nowEpochMs)
            // Weight-1 inside a 0-width slot lets the text expand to fill
            // whatever's left after the fixed-size pencil claims its space.
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        row.addView(statusView)

        val editBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_edit_pencil)
            // Borderless ripple so it visually reads as a tappable
            // glyph rather than a heavy raised button next to body text.
            val tv = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true
            )
            setBackgroundResource(tv.resourceId)
            contentDescription =
                getString(R.string.launcher_edit_widget_desc, displayIndex)
            // 40dp square — Material's recommended minimum touch target
            // is 48dp, but the row is already padded by the surrounding
            // 24dp activity padding so 40dp is comfortable here.
            val sizePx = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = (8 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { openWidgetConfig(appWidgetId) }
        }
        row.addView(editBtn)

        return row
    }

    /**
     * Launch [WidgetConfigActivity] directly for an existing widget. We
     * deliberately *don't* go through the system reconfigure flow —
     * that path requires the widget to be currently dragging on the
     * home screen, which is exactly the friction this button removes.
     * The config activity already treats "prefs already exist" as the
     * reconfigure signal, so launching it with EXTRA_APPWIDGET_ID is
     * enough to make it behave like the system pencil would.
     */
    private fun openWidgetConfig(appWidgetId: Int) {
        try {
            val intent = Intent(this, WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_edit_widget_failed, Toast.LENGTH_SHORT).show()
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

        // BLOCK mode: there's no currency / price text in the
        // traditional sense — the status line shows the cached block
        // height (with miner if known) and how long ago we fetched it.
        if (currency.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true)) {
            val cached = cachedPrice ?: WidgetPrefs.loadLatestBlockHeight(ctx)?.toString()
            if (cached != null && lastSuccess > 0) {
                val miner = WidgetPrefs.loadLatestBlockMiner(ctx)
                val display = if (miner != null) "block $cached ($miner)" else "block $cached"
                return getString(
                    R.string.launcher_widget_status_block,
                    displayIndex,
                    display,
                    relativeTime(nowEpochMs - lastSuccess),
                )
            }
            // Falls through to the generic never-fetched / error paths
            // below if no cached value yet.
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
        // Re-fetch the preview alongside the widget refresh so a "no
        // network" / "server error" surfaces at the top of the screen
        // too, instead of leaving the previously-cached success frame
        // sitting there contradicting the per-widget status lines.
        loadPreview()
        // Re-render statuses ~1s after triggering refresh so the panel
        // catches up with the new last-success / last-error values.
        findViewById<View>(R.id.status_container).postDelayed(
            { renderWidgetStatuses() }, 1500
        )
    }

    /**
     * Format the preview box. Three cases:
     *
     *   1. Both fetches succeeded → two-line numeric layout, identical
     *      to the original design.
     *   2. Both fetches failed → a single, bold error headline ("No
     *      network", "Server error", …) with a smaller explanation
     *      underneath. We collapse to one message because both currencies
     *      ride on the same HTTP call, so a generic failure hits both
     *      identically and showing it twice is just noise.
     *   3. Mixed (one success, one fail) → the rare case where the JSON
     *      came back but only one currency was parseable. Keep the
     *      per-line layout so the user can still see the half that worked.
     */
    private fun formatPreview(usd: PriceResult, eur: PriceResult): CharSequence {
        if (usd is PriceResult.Success && eur is PriceResult.Success) {
            return "$  ${formatWhole(usd.price)}\n€  ${formatWhole(eur.price)}"
        }
        if (usd is PriceResult.Error && eur is PriceResult.Error) {
            val (headline, detail) = describeError(usd.reason)
            return buildErrorPreview(headline, detail)
        }
        // Mixed: build per-line. Failed half gets a short reason in the
        // same spot the price would have occupied.
        fun line(label: String, r: PriceResult): String = when (r) {
            is PriceResult.Success -> "$label  ${formatWhole(r.price)}"
            is PriceResult.Error -> "$label  — ${describeError(r.reason).first}"
        }
        return line("$", usd) + "\n" + line("€", eur)
    }

    /**
     * Build a two-line "headline + explanation" CharSequence sized so
     * the headline is the visual anchor (1.1× body) and the detail line
     * is smaller (0.75×), regardless of the surrounding TextView's size.
     */
    private fun buildErrorPreview(headline: String, detail: String): CharSequence {
        val sb = SpannableStringBuilder()
        val headStart = sb.length
        sb.append(headline)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD),
            headStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.1f),
            headStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val detailStart = sb.length
        sb.append(detail)
        sb.setSpan(RelativeSizeSpan(0.75f),
            detailStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * Map a [PriceResult.Error] reason string back into a friendly
     * (headline, detail) pair. The reason format comes from
     * [PriceFetcher] — currently:
     *
     *   - "Network: <ExceptionClass>: <message>"   → no network
     *   - "HTTP <code>: <body-snippet>"            → server error
     *   - everything else (bad JSON, missing key)  → generic fail
     *
     * If [PriceFetcher] starts producing new reason shapes, the worst
     * case is the generic branch — we'll surface the raw reason in the
     * detail line, which is still better than the old behaviour of
     * silently displaying stale prices.
     */
    private fun describeError(reason: String): Pair<String, String> {
        val networkMarkers = listOf(
            "Network:",
            "UnknownHost",
            "ConnectException",
            "SocketTimeout",
            "NoRouteToHost",
            "SSLHandshake",
        )
        if (networkMarkers.any { it in reason }) {
            return getString(R.string.preview_error_no_network) to
                getString(R.string.preview_error_no_network_detail)
        }
        if (reason.startsWith("HTTP ")) {
            return getString(R.string.preview_error_server) to
                getString(R.string.preview_error_server_detail)
        }
        return getString(R.string.preview_error_fetch_failed) to
            getString(R.string.preview_error_fetch_failed_detail, truncate(reason, 100))
    }

    private fun formatWhole(price: Double): String {
        val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
        nf.isGroupingUsed = true
        return nf.format(price.toLong())
    }
}
