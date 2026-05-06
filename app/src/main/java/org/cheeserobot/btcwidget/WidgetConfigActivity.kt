package org.cheeserobot.btcwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

/**
 * Shown:
 *   - automatically the first time a widget is added to the home screen, and
 *   - again whenever the user taps the pencil icon while moving the widget
 *     (Android 12+ - enabled via `widgetFeatures="reconfigurable"`).
 *
 * Two-tier UI:
 *   - Simple: currency picker only, on the assumption that most people just
 *     want USD or EUR and don't care about formatting.
 *   - Advanced: collapsed by default. Houses tracked amount, number
 *     formatting, opacity, display toggles, and the red/green change
 *     indicator. Auto-expands on reconfigure when ANY advanced setting is
 *     non-default.
 *
 * A live preview at the top sits on a checkered grey background so the
 * user can see how their opacity choice will read against home-screen
 * wallpaper. Every setting change updates the preview in place.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isReconfigure: Boolean = false

    // Currency (simple section)
    private lateinit var rgCurrency: RadioGroup
    private lateinit var rbUsd: RadioButton
    private lateinit var rbEur: RadioButton
    private lateinit var rbBtc: RadioButton
    private lateinit var rbSats: RadioButton

    // Advanced toggle + container
    private lateinit var advancedToggle: TextView
    private lateinit var advancedSection: LinearLayout

    // Advanced section views
    private lateinit var etTrackedAmount: EditText
    private lateinit var cbShowDecimals: CheckBox
    private lateinit var spSeparator: Spinner
    private lateinit var sbOpacity: SeekBar
    private lateinit var opacityValue: TextView
    // All four toggles are user-affirmative ("Show X"). The underlying
    // SharedPreferences keys are still KEY_HIDE_* — we invert at every
    // load and save so that storing a checked CheckBox writes hide=false.
    private lateinit var cbShowLogo: CheckBox
    private lateinit var cbShowCurrencyIcon: CheckBox
    private lateinit var cbShowUnitLabel: CheckBox
    private lateinit var cbShowChart: CheckBox
    private lateinit var rgChange: RadioGroup
    private lateinit var rbChangeOff: RadioButton
    private lateinit var rbChange1d: RadioButton
    private lateinit var rbChange1w: RadioButton

    /**
     * Easter-egg toggle. Bound to a CheckBox that's invisible by
     * default and only revealed when [shouldShowMoscowTimeToggle]
     * returns true (currency == SATS && thousands separator in use).
     */
    private lateinit var cbMoscowTime: CheckBox

    // Preview views (looked up inside preview_container)
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewBackground: ImageView
    private lateinit var previewSparkline: ImageView
    private lateinit var previewIcon: ImageView
    private lateinit var previewUnit: TextView
    private lateinit var previewPrice: TextView
    private lateinit var previewChange: TextView

    /** Mirrors the order of `R.array.config_separator_options`. */
    private val separatorKeys = listOf(
        WidgetPrefs.SEPARATOR_AUTO,
        WidgetPrefs.SEPARATOR_COMMA,
        WidgetPrefs.SEPARATOR_DOT,
        WidgetPrefs.SEPARATOR_SPACE,
        WidgetPrefs.SEPARATOR_NONE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // We treat "prefs already exist" as the signal that this is a
        // reconfigure rather than a fresh add.
        isReconfigure = WidgetPrefs.hasCurrency(this, appWidgetId)

        bindViews()
        wireSeparatorAdapter()
        installCheckerBackground()
        loadInitialState()
        wireSeekBar()
        wireAdvancedToggle()
        wirePreviewListeners()
        showVersionFooter()
        updatePreview()

        findViewById<Button>(R.id.btn_save).setOnClickListener { persistAndFinish() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    private fun bindViews() {
        rgCurrency = findViewById(R.id.rg_currency)
        rbUsd = findViewById(R.id.rb_usd)
        rbEur = findViewById(R.id.rb_eur)
        rbBtc = findViewById(R.id.rb_btc)
        rbSats = findViewById(R.id.rb_sats)

        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedSection = findViewById(R.id.advanced_section)

        etTrackedAmount = findViewById(R.id.et_tracked_amount)
        cbShowDecimals = findViewById(R.id.cb_show_decimals)
        spSeparator = findViewById(R.id.sp_separator)
        sbOpacity = findViewById(R.id.sb_opacity)
        opacityValue = findViewById(R.id.opacity_value)
        cbShowLogo = findViewById(R.id.cb_show_logo)
        cbShowCurrencyIcon = findViewById(R.id.cb_show_currency_icon)
        cbShowUnitLabel = findViewById(R.id.cb_show_unit_label)
        cbShowChart = findViewById(R.id.cb_show_chart)
        rgChange = findViewById(R.id.rg_change)
        rbChangeOff = findViewById(R.id.rb_change_off)
        rbChange1d = findViewById(R.id.rb_change_1d)
        rbChange1w = findViewById(R.id.rb_change_1w)
        cbMoscowTime = findViewById(R.id.cb_moscow_time)

        previewContainer = findViewById(R.id.preview_container)
        previewBackground = previewContainer.findViewById(R.id.background_view)
        previewSparkline = previewContainer.findViewById(R.id.sparkline_view)
        previewIcon = previewContainer.findViewById(R.id.btc_icon)
        previewUnit = previewContainer.findViewById(R.id.unit_label)
        previewPrice = previewContainer.findViewById(R.id.price_text)
        previewChange = previewContainer.findViewById(R.id.change_indicator)
    }

    private fun wireSeparatorAdapter() {
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.config_separator_options,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSeparator.adapter = adapter
    }

    /**
     * Generate a small checkered tile and use it as the preview's
     * background so the opacity slider has a visible effect even when
     * the screen sits on a flat colour.
     */
    private fun installCheckerBackground() {
        previewContainer.background = makeCheckerDrawable()
    }

    private fun makeCheckerDrawable(): Drawable {
        val density = resources.displayMetrics.density
        val tile = (8 * density).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(tile * 2, tile * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val light = if (isDark) 0xFF3A3A3A.toInt() else 0xFFE8E8E8.toInt()
        val dark = if (isDark) 0xFF2C2C2C.toInt() else 0xFFCFCFCF.toInt()
        val paint = Paint().apply { color = light }
        canvas.drawRect(0f, 0f, (tile * 2).toFloat(), (tile * 2).toFloat(), paint)
        paint.color = dark
        canvas.drawRect(0f, 0f, tile.toFloat(), tile.toFloat(), paint)
        canvas.drawRect(
            tile.toFloat(), tile.toFloat(),
            (tile * 2).toFloat(), (tile * 2).toFloat(), paint
        )
        val bd = BitmapDrawable(resources, bmp)
        bd.tileModeX = Shader.TileMode.REPEAT
        bd.tileModeY = Shader.TileMode.REPEAT
        return bd
    }

    private fun loadInitialState() {
        findViewById<TextView>(R.id.subtitle_text).setText(
            if (isReconfigure) R.string.config_reconfigure_subtitle
            else R.string.config_subtitle
        )

        val currency = WidgetPrefs.loadCurrency(this, appWidgetId)
        when (currency) {
            WidgetPrefs.CURRENCY_EUR -> rbEur.isChecked = true
            WidgetPrefs.CURRENCY_BTC -> rbBtc.isChecked = true
            WidgetPrefs.CURRENCY_SATS -> rbSats.isChecked = true
            else -> rbUsd.isChecked = true
        }

        val tracked = WidgetPrefs.loadTrackedAmount(this, appWidgetId)
        etTrackedAmount.setText(formatAmountForEdit(tracked))

        cbShowDecimals.isChecked = WidgetPrefs.loadShowDecimals(this, appWidgetId)

        val sep = WidgetPrefs.loadSeparator(this, appWidgetId)
        val sepIndex = separatorKeys.indexOf(sep).coerceAtLeast(0)
        spSeparator.setSelection(sepIndex)

        val opacity = WidgetPrefs.loadOpacity(this, appWidgetId)
        sbOpacity.progress = opacity
        opacityValue.text = getString(R.string.config_opacity_value, opacity)

        // Invert at load: a saved hide=true → CheckBox unchecked.
        // Defaults line up: DEFAULT_HIDE_* = false → CheckBox starts checked,
        // matching the out-of-the-box "everything visible" appearance.
        cbShowLogo.isChecked = !WidgetPrefs.loadHideLogo(this, appWidgetId)
        cbShowCurrencyIcon.isChecked = !WidgetPrefs.loadHideCurrencyIcon(this, appWidgetId)
        cbShowUnitLabel.isChecked = !WidgetPrefs.loadHideUnitLabel(this, appWidgetId)
        cbShowChart.isChecked = WidgetPrefs.loadShowChart(this, appWidgetId)

        when (WidgetPrefs.loadChangeIndicator(this, appWidgetId)) {
            WidgetPrefs.CHANGE_1D -> rbChange1d.isChecked = true
            WidgetPrefs.CHANGE_1W -> rbChange1w.isChecked = true
            else -> rbChangeOff.isChecked = true
        }

        // Restore the easter-egg toggle and let the visibility helper
        // decide whether to actually show it on screen. If the user
        // saved with it on but later flipped currency away from SATS
        // (or picked separator=None), the CheckBox stays hidden and
        // the price formatter naturally falls back to numeric.
        cbMoscowTime.isChecked = WidgetPrefs.loadMoscowTime(this, appWidgetId)
        applyMoscowTimeVisibility()

        // Auto-expand advanced when any advanced field is non-default.
        // Only relevant for reconfigure; fresh widgets have no prefs and
        // every setting reads as default.
        if (isReconfigure && hasNonDefaultAdvanced()) {
            setAdvancedExpanded(true)
        }
    }

    private fun hasNonDefaultAdvanced(): Boolean {
        if (WidgetPrefs.loadTrackedAmount(this, appWidgetId) != WidgetPrefs.DEFAULT_TRACKED_AMOUNT) return true
        if (WidgetPrefs.loadShowDecimals(this, appWidgetId) != WidgetPrefs.DEFAULT_SHOW_DECIMALS) return true
        if (WidgetPrefs.loadSeparator(this, appWidgetId) != WidgetPrefs.DEFAULT_SEPARATOR) return true
        if (WidgetPrefs.loadOpacity(this, appWidgetId) != WidgetPrefs.DEFAULT_OPACITY) return true
        if (WidgetPrefs.loadHideLogo(this, appWidgetId) != WidgetPrefs.DEFAULT_HIDE_LOGO) return true
        if (WidgetPrefs.loadHideCurrencyIcon(this, appWidgetId) != WidgetPrefs.DEFAULT_HIDE_CURRENCY_ICON) return true
        if (WidgetPrefs.loadHideUnitLabel(this, appWidgetId) != WidgetPrefs.DEFAULT_HIDE_UNIT_LABEL) return true
        if (WidgetPrefs.loadChangeIndicator(this, appWidgetId) != WidgetPrefs.DEFAULT_CHANGE_INDICATOR) return true
        if (WidgetPrefs.loadShowChart(this, appWidgetId) != WidgetPrefs.DEFAULT_SHOW_CHART) return true
        if (WidgetPrefs.loadMoscowTime(this, appWidgetId) != WidgetPrefs.DEFAULT_MOSCOW_TIME) return true
        return false
    }

    private fun wireSeekBar() {
        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                opacityValue.text = getString(R.string.config_opacity_value, p)
                updatePreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun wireAdvancedToggle() {
        advancedToggle.setOnClickListener {
            setAdvancedExpanded(advancedSection.visibility != View.VISIBLE)
        }
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedSection.visibility = if (expanded) View.VISIBLE else View.GONE
        advancedToggle.setText(
            if (expanded) R.string.config_advanced_hide
            else R.string.config_advanced_show
        )
    }

    private fun wirePreviewListeners() {
        rgCurrency.setOnCheckedChangeListener { _, _ ->
            applyMoscowTimeVisibility()
            updatePreview()
        }
        rgChange.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbShowDecimals.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbShowLogo.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbShowCurrencyIcon.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbShowUnitLabel.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbShowChart.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbMoscowTime.setOnCheckedChangeListener { _, _ -> updatePreview() }
        spSeparator.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                applyMoscowTimeVisibility()
                updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        etTrackedAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        })
    }

    /**
     * The Moscow-Time CheckBox is intentionally hidden until the user
     * has stumbled into the right combination — currency=SATS AND a
     * thousands separator in use. When the unlock conditions stop
     * holding we hide it again; we deliberately *don't* uncheck it,
     * so flipping currency back to SATS restores the prior state.
     */
    private fun applyMoscowTimeVisibility() {
        val unlocked = shouldShowMoscowTimeToggle()
        cbMoscowTime.visibility = if (unlocked) View.VISIBLE else View.GONE
    }

    private fun shouldShowMoscowTimeToggle(): Boolean {
        if (selectedCurrency() != WidgetPrefs.CURRENCY_SATS) return false
        if (selectedSeparator() == WidgetPrefs.SEPARATOR_NONE) return false
        return true
    }

    /**
     * Render the preview from the current UI state (not from saved
     * prefs). Uses sample current/historical prices so the change
     * indicator can show plausible values before any real fetch.
     */
    private fun updatePreview() {
        val currency = selectedCurrency()
        val tracked = parsedTrackedAmount()
        val showDecimals = cbShowDecimals.isChecked
        val separator = selectedSeparator()
        val hideLogo = !cbShowLogo.isChecked
        val hideUnit = !cbShowUnitLabel.isChecked
        val changeMode = selectedChangeIndicator()
        val opacity = sbOpacity.progress.coerceIn(0, 100)

        val (samplePrice, sampleOneDay, sampleOneWeek) = sampleData(currency)
        val displayed = samplePrice * tracked
        val moscowTime = cbMoscowTime.isChecked
        val priceText = if (PriceFormat.isMoscowTimeActive(currency, separator, moscowTime))
            PriceFormat.formatMoscowTime(displayed)
        else
            PriceFormat.format(displayed, showDecimals, separator)
        val symbol = WidgetPrefs.symbolFor(currency)
        val hideCurrencyIcon = !cbShowCurrencyIcon.isChecked
        // SATS uses the icon slot for the glyph (text prefix is empty).
        // For USD/EUR/BTC the prefix is dropped when the user has
        // hide-currency-icon turned on.
        previewPrice.text = when {
            symbol.isEmpty() -> priceText
            hideCurrencyIcon -> priceText
            else -> "$symbol $priceText"
        }

        // Swap the preview icon between the Bitcoin logo and the sat
        // symbol so the user sees the same glyph the live widget will
        // paint.
        val isSats = currency == WidgetPrefs.CURRENCY_SATS
        previewIcon.setImageResource(
            if (isSats) R.drawable.ic_sat_symbol else R.drawable.ic_bitcoin
        )
        // Two flags can hide the icon slot: hideLogo (legacy) and, in
        // SATS mode, hideCurrencyIcon (because the sat-symbol IS the
        // currency marker for SATS).
        val iconHidden = hideLogo || (isSats && hideCurrencyIcon)
        previewIcon.visibility = if (iconHidden) View.GONE else View.VISIBLE

        if (hideUnit) {
            previewUnit.visibility = View.GONE
        } else {
            previewUnit.visibility = View.VISIBLE
            previewUnit.text = formatUnitLabel(tracked, currency)
        }

        previewBackground.imageAlpha = (opacity * 255 / 100)

        renderPreviewSparkline(currency, sampleOneWeek, samplePrice)

        val historical: Double? = when (changeMode) {
            WidgetPrefs.CHANGE_1D -> sampleOneDay
            WidgetPrefs.CHANGE_1W -> sampleOneWeek
            else -> null
        }
        if (changeMode == WidgetPrefs.CHANGE_OFF || historical == null) {
            previewChange.visibility = View.GONE
        } else {
            // Scale by trackedAmount so the preview matches what the
            // widget will actually render.
            val scaledHistorical = historical * tracked
            val pct = if (scaledHistorical == 0.0) 0.0
            else ((displayed - scaledHistorical) / scaledHistorical) * 100.0
            val sign = if (pct >= 0) "+" else ""
            val periodLabel = if (changeMode == WidgetPrefs.CHANGE_1W) "7d" else "24h"
            previewChange.text = String.format(
                Locale.US, "%s%.1f%% %s", sign, pct, periodLabel
            )
            // SATS mode shows sats-per-USD; that number rises when BTC
            // falls. Mirror the live widget's renderChangeIndicator and
            // flip the colour so "green = BTC up" stays consistent for
            // the user across currencies, even though the literal pct
            // sign reflects the displayed value's own direction.
            val btcWentUp = if (currency == WidgetPrefs.CURRENCY_SATS) pct < 0 else pct >= 0
            val colorRes = if (btcWentUp) R.color.change_up else R.color.change_down
            previewChange.setTextColor(getColor(colorRes))
            previewChange.visibility = View.VISIBLE
        }
    }

    /**
     * Paint the preview's sparkline ImageView. Hidden when the user
     * has the chart toggle off or when no plausible series is available.
     *
     * Real cached history (if the user already has a placed widget that
     * fetched it) is preferred so the preview matches what the live
     * widget will paint. With no cache we synthesise a plausible
     * 7-day curve from the sample week-ago and current price so the
     * preview still shows *something* — empty preview is the bug
     * report's symptom.
     */
    private fun renderPreviewSparkline(
        currency: String,
        weekAgo: Double?,
        currentSample: Double,
    ) {
        if (!cbShowChart.isChecked) {
            previewSparkline.visibility = View.GONE
            return
        }

        val series = cachedSeriesFor(currency) ?: synthesiseSeries(weekAgo, currentSample)
        if (series == null || series.size < 2) {
            previewSparkline.visibility = View.GONE
            return
        }

        // The preview FrameLayout has a fixed 100dp height and matches
        // its parent's width. On the very first render (called from
        // onCreate before layout has run) width==0 — defer to a post()
        // so we don't draw a degenerate bitmap that gets immediately
        // discarded by the post-layout re-render.
        if (previewContainer.width == 0 || previewContainer.height == 0) {
            previewSparkline.visibility = View.GONE
            previewContainer.post { updatePreview() }
            return
        }
        val density = resources.displayMetrics.density
        val widthPx = previewContainer.width
        val heightPx = previewContainer.height

        val colorRes = if (SparklineRenderer.isUp(series))
            R.color.sparkline_up else R.color.sparkline_down
        val color = getColor(colorRes)
        val strokePx = (1.5f * density).coerceAtLeast(1.5f)

        val bmp = SparklineRenderer.render(
            values = series,
            widthPx = widthPx,
            heightPx = heightPx,
            color = color,
            strokePx = strokePx,
        )
        if (bmp == null) {
            previewSparkline.visibility = View.GONE
            return
        }
        previewSparkline.setImageBitmap(bmp)
        previewSparkline.visibility = View.VISIBLE
    }

    /**
     * Pull a real series out of the global history cache (the same one
     * the live widget reads). Returns null when no widget has fetched
     * yet, the cache is corrupt, or the parsed series is too short.
     * SATS-mode rides on the USD series and inverts each point, mirroring
     * [BitcoinPriceWidgetProvider.buildSparklineBitmap].
     */
    private fun cachedSeriesFor(currency: String): DoubleArray? {
        val cached = WidgetPrefs.loadHistoryJson(this) ?: return null
        val parsed = HistoryFetcher.parse(cached) as? HistoryResult.Success ?: return null
        val isSats = currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
        val baseCurrency = if (isSats) WidgetPrefs.CURRENCY_USD else currency
        val raw = HistoryFetcher.seriesFor(parsed.points, baseCurrency)
        if (raw.size < 2) return null
        return if (isSats) {
            DoubleArray(raw.size) { i ->
                val v = raw[i]
                if (v == 0.0 || !v.isFinite()) 0.0 else 100_000_000.0 / v
            }
        } else raw
    }

    /**
     * Build a synthetic 7-day curve when we have no cached history. We
     * trace a gently wavy path between the week-ago sample and the
     * current sample so the line shape reads as "a real chart" rather
     * than a straight diagonal. BTC mode (no historical) returns null —
     * the chart simply hides for that mode.
     */
    private fun synthesiseSeries(weekAgo: Double?, current: Double): DoubleArray? {
        if (weekAgo == null || !weekAgo.isFinite() || !current.isFinite()) return null
        val n = 24
        val out = DoubleArray(n)
        val span = current - weekAgo
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            // Linear interpolation plus a small sinusoidal wobble (~3 %
            // of span) so the curve has visible character even when the
            // endpoints are close together.
            val base = weekAgo + span * t
            val wobble = span * 0.03 * Math.sin(t * Math.PI * 3.0)
            out[i] = base + wobble
        }
        return out
    }

    /**
     * Plausible sample values for each currency, used solely to drive
     * the live preview before any real network fetch has populated
     * cached values.
     */
    private fun sampleData(currency: String): Triple<Double, Double?, Double?> {
        return when (currency) {
            WidgetPrefs.CURRENCY_EUR -> Triple(69498.08, 68717.13, 65033.52)
            WidgetPrefs.CURRENCY_BTC -> Triple(1.0, null, null)
            WidgetPrefs.CURRENCY_SATS -> {
                // Mirror the live computation: 100,000,000 / usd_price.
                val usd = 81324.99
                val usd1d = 80325.00
                val usd1w = 76177.99
                Triple(100_000_000.0 / usd, 100_000_000.0 / usd1d, 100_000_000.0 / usd1w)
            }
            else -> Triple(81324.99, 80325.00, 76177.99)
        }
    }

    private fun formatUnitLabel(amount: Double, currency: String = WidgetPrefs.CURRENCY_USD): String {
        val rendered = if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            val s = String.format(Locale.US, "%.8f", amount)
                .trimEnd('0').trimEnd('.')
            if (s.isEmpty()) "0" else s
        }
        // SATS-mode is "sats per N USD" rather than the price of N BTC.
        val isSats = currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)
        val unit = if (isSats) "USD" else "BTC"
        val prefix = if (isSats) "per " else ""
        return "$prefix$rendered $unit"
    }

    private fun showVersionFooter() {
        val footer = findViewById<TextView>(R.id.version_footer) ?: return
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
        footer.text = if (versionName.isEmpty()) ""
        else getString(R.string.config_version_label, versionName)
    }

    private fun selectedCurrency(): String = when {
        rbEur.isChecked -> WidgetPrefs.CURRENCY_EUR
        rbBtc.isChecked -> WidgetPrefs.CURRENCY_BTC
        rbSats.isChecked -> WidgetPrefs.CURRENCY_SATS
        else -> WidgetPrefs.CURRENCY_USD
    }

    private fun parsedTrackedAmount(): Double {
        val raw = etTrackedAmount.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return WidgetPrefs.DEFAULT_TRACKED_AMOUNT
        val normalised = raw.replace(',', '.')
        val parsed = normalised.toDoubleOrNull() ?: return WidgetPrefs.DEFAULT_TRACKED_AMOUNT
        return if (parsed.isFinite() && parsed >= 0) parsed
        else WidgetPrefs.DEFAULT_TRACKED_AMOUNT
    }

    private fun formatAmountForEdit(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.8f", amount)
                .trimEnd('0').trimEnd('.')
        }
    }

    private fun selectedSeparator(): String {
        val pos = spSeparator.selectedItemPosition
        return separatorKeys.getOrElse(pos) { WidgetPrefs.SEPARATOR_AUTO }
    }

    private fun selectedChangeIndicator(): String = when {
        rbChange1d.isChecked -> WidgetPrefs.CHANGE_1D
        rbChange1w.isChecked -> WidgetPrefs.CHANGE_1W
        else -> WidgetPrefs.CHANGE_OFF
    }

    private fun persistAndFinish() {
        WidgetPrefs.saveCurrency(this, appWidgetId, selectedCurrency())
        WidgetPrefs.saveTrackedAmount(this, appWidgetId, parsedTrackedAmount())
        WidgetPrefs.saveShowDecimals(this, appWidgetId, cbShowDecimals.isChecked)
        WidgetPrefs.saveSeparator(this, appWidgetId, selectedSeparator())
        WidgetPrefs.saveOpacity(this, appWidgetId, sbOpacity.progress)
        // Invert at save: a checked "Show X" → hide=false on disk.
        WidgetPrefs.saveHideLogo(this, appWidgetId, !cbShowLogo.isChecked)
        WidgetPrefs.saveHideCurrencyIcon(this, appWidgetId, !cbShowCurrencyIcon.isChecked)
        WidgetPrefs.saveHideUnitLabel(this, appWidgetId, !cbShowUnitLabel.isChecked)
        WidgetPrefs.saveChangeIndicator(this, appWidgetId, selectedChangeIndicator())
        WidgetPrefs.saveShowChart(this, appWidgetId, cbShowChart.isChecked)
        // Always persist what the user set, even when the toggle is
        // currently hidden — the formatter consults isMoscowTimeActive
        // at render time, so a stored "true" with currency!=SATS does
        // nothing visible until the user re-meets the unlock condition.
        WidgetPrefs.saveMoscowTime(this, appWidgetId, cbMoscowTime.isChecked)

        val mgr = AppWidgetManager.getInstance(this)
        BitcoinPriceWidgetProvider.updateWidget(this, mgr, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}
