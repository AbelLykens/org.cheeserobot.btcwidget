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
    private lateinit var cbHideLogo: CheckBox
    private lateinit var cbHideUnitLabel: CheckBox
    private lateinit var rgChange: RadioGroup
    private lateinit var rbChangeOff: RadioButton
    private lateinit var rbChange1d: RadioButton
    private lateinit var rbChange1w: RadioButton

    // Preview views (looked up inside preview_container)
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewBackground: ImageView
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
        cbHideLogo = findViewById(R.id.cb_hide_logo)
        cbHideUnitLabel = findViewById(R.id.cb_hide_unit_label)
        rgChange = findViewById(R.id.rg_change)
        rbChangeOff = findViewById(R.id.rb_change_off)
        rbChange1d = findViewById(R.id.rb_change_1d)
        rbChange1w = findViewById(R.id.rb_change_1w)

        previewContainer = findViewById(R.id.preview_container)
        previewBackground = previewContainer.findViewById(R.id.background_view)
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

        cbHideLogo.isChecked = WidgetPrefs.loadHideLogo(this, appWidgetId)
        cbHideUnitLabel.isChecked = WidgetPrefs.loadHideUnitLabel(this, appWidgetId)

        when (WidgetPrefs.loadChangeIndicator(this, appWidgetId)) {
            WidgetPrefs.CHANGE_1D -> rbChange1d.isChecked = true
            WidgetPrefs.CHANGE_1W -> rbChange1w.isChecked = true
            else -> rbChangeOff.isChecked = true
        }

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
        if (WidgetPrefs.loadHideUnitLabel(this, appWidgetId) != WidgetPrefs.DEFAULT_HIDE_UNIT_LABEL) return true
        if (WidgetPrefs.loadChangeIndicator(this, appWidgetId) != WidgetPrefs.DEFAULT_CHANGE_INDICATOR) return true
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
        rgCurrency.setOnCheckedChangeListener { _, _ -> updatePreview() }
        rgChange.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbShowDecimals.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbHideLogo.setOnCheckedChangeListener { _, _ -> updatePreview() }
        cbHideUnitLabel.setOnCheckedChangeListener { _, _ -> updatePreview() }
        spSeparator.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
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
     * Render the preview from the current UI state (not from saved
     * prefs). Uses sample current/historical prices so the change
     * indicator can show plausible values before any real fetch.
     */
    private fun updatePreview() {
        val currency = selectedCurrency()
        val tracked = parsedTrackedAmount()
        val showDecimals = cbShowDecimals.isChecked
        val separator = selectedSeparator()
        val hideLogo = cbHideLogo.isChecked
        val hideUnit = cbHideUnitLabel.isChecked
        val changeMode = selectedChangeIndicator()
        val opacity = sbOpacity.progress.coerceIn(0, 100)

        val (samplePrice, sampleOneDay, sampleOneWeek) = sampleData(currency)
        val displayed = samplePrice * tracked
        val priceText = PriceFormat.format(displayed, showDecimals, separator)
        val symbol = WidgetPrefs.symbolFor(currency)
        // SATS uses the icon slot for the glyph, so the text prefix is empty.
        previewPrice.text = if (symbol.isEmpty()) priceText else "$symbol $priceText"

        // Swap the preview icon between the Bitcoin logo and the sat
        // symbol so the user sees the same glyph the live widget will
        // paint.
        val isSats = currency == WidgetPrefs.CURRENCY_SATS
        previewIcon.setImageResource(
            if (isSats) R.drawable.ic_sat_symbol else R.drawable.ic_bitcoin
        )
        previewIcon.visibility = if (hideLogo) View.GONE else View.VISIBLE

        if (hideUnit) {
            previewUnit.visibility = View.GONE
        } else {
            previewUnit.visibility = View.VISIBLE
            previewUnit.text = formatUnitLabel(tracked, currency)
        }

        previewBackground.imageAlpha = (opacity * 255 / 100)

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
            val colorRes = if (pct >= 0) R.color.change_up else R.color.change_down
            previewChange.setTextColor(getColor(colorRes))
            previewChange.visibility = View.VISIBLE
        }
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
        WidgetPrefs.saveHideLogo(this, appWidgetId, cbHideLogo.isChecked)
        WidgetPrefs.saveHideUnitLabel(this, appWidgetId, cbHideUnitLabel.isChecked)
        WidgetPrefs.saveChangeIndicator(this, appWidgetId, selectedChangeIndicator())

        val mgr = AppWidgetManager.getInstance(this)
        BitcoinPriceWidgetProvider.updateWidget(this, mgr, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}
