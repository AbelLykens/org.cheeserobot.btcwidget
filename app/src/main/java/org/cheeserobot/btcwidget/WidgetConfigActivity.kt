package org.cheeserobot.btcwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

/**
 * Shown:
 *   • automatically the first time a widget is added to the home screen, and
 *   • again whenever the user taps the pencil icon while moving the widget
 *     (Android 12+ — enabled via `widgetFeatures="reconfigurable"`).
 *
 * Two-tier UI:
 *   • Simple — currency picker only, on the assumption that most people
 *     just want USD or EUR and don't care about formatting.
 *   • Advanced — collapsed by default, expanded with a single tap.
 *     Houses tracked amount, number formatting, opacity, display
 *     toggles, and the new red/green change indicator. Auto-expands on
 *     the reconfigure path when any advanced setting is non-default, so
 *     the user immediately sees what's already customised.
 *
 * Fetch errors are surfaced inside the launcher activity, so this
 * screen no longer asks for the POST_NOTIFICATIONS permission.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isReconfigure: Boolean = false

    // Currency (simple section)
    private lateinit var rgCurrency: RadioGroup
    private lateinit var rbUsd: RadioButton
    private lateinit var rbEur: RadioButton
    private lateinit var rbBtc: RadioButton

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

        // If the user backs out, the widget should NOT be added. (For a
        // reconfigure flow, RESULT_CANCELED leaves the widget unchanged,
        // which is also the correct fallback.)
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
        // reconfigure rather than a fresh add. That way we work on every
        // Android version even though only 12+ exposes the pencil icon.
        isReconfigure = WidgetPrefs.hasCurrency(this, appWidgetId)

        bindViews()
        wireSeparatorAdapter()
        loadInitialState()
        wireSeekBar()
        wireAdvancedToggle()
        showVersionFooter()

        findViewById<Button>(R.id.btn_save).setOnClickListener { persistAndFinish() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    private fun bindViews() {
        rgCurrency = findViewById(R.id.rg_currency)
        rbUsd = findViewById(R.id.rb_usd)
        rbEur = findViewById(R.id.rb_eur)
        rbBtc = findViewById(R.id.rb_btc)

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
    }

    private fun wireSeparatorAdapter() {
        // Plain framework Spinner with a stock array adapter — no
        // appcompat/material dependency required.
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.config_separator_options,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSeparator.adapter = adapter
    }

    private fun loadInitialState() {
        // Subtitle reflects mode so the user knows whether they're editing
        // existing settings or configuring something fresh.
        findViewById<TextView>(R.id.subtitle_text).setText(
            if (isReconfigure) R.string.config_reconfigure_subtitle
            else R.string.config_subtitle
        )

        val currency = WidgetPrefs.loadCurrency(this, appWidgetId)
        when (currency) {
            WidgetPrefs.CURRENCY_EUR -> rbEur.isChecked = true
            WidgetPrefs.CURRENCY_BTC -> rbBtc.isChecked = true
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

        // Auto-expand advanced when any advanced field is non-default,
        // so users tapping the pencil icon see their existing tweaks
        // without an extra click.
        if (hasNonDefaultAdvanced()) setAdvancedExpanded(true)
    }

    /**
     * True when at least one advanced setting deviates from its default,
     * which makes "Advanced" worth opening on entry for reconfigure.
     */
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

    /**
     * Display the app version at the bottom of the screen — handy for
     * users reporting issues and for confirming an update has installed.
     */
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
        else -> WidgetPrefs.CURRENCY_USD
    }

    private fun parsedTrackedAmount(): Double {
        val raw = etTrackedAmount.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return WidgetPrefs.DEFAULT_TRACKED_AMOUNT
        // Accept both "." and "," as the decimal separator — different
        // soft keyboards emit different characters.
        val normalised = raw.replace(',', '.')
        val parsed = normalised.toDoubleOrNull() ?: return WidgetPrefs.DEFAULT_TRACKED_AMOUNT
        return if (parsed.isFinite() && parsed >= 0) parsed
        else WidgetPrefs.DEFAULT_TRACKED_AMOUNT
    }

    private fun formatAmountForEdit(amount: Double): String {
        // Render whole numbers without a decimal so the user sees "1"
        // not "1.0", but preserve fractional precision otherwise.
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            // Up to 8 significant decimal places with trailing zeros
            // stripped (BTC has 8 satoshis of precision).
            String.format(java.util.Locale.US, "%.8f", amount)
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
