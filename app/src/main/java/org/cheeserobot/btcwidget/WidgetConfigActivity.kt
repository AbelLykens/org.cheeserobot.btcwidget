package org.cheeserobot.btcwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
 * Lets the user pick:
 *   • currency (USD / EUR / BTC)
 *   • tracked amount of Bitcoin (default 1.0)
 *   • whether to show decimals on the displayed price
 *   • the thousands separator
 *   • background opacity (0–100%)
 *   • whether the bitcoin logo is shown
 *   • whether the "<amount> BTC" caption is shown
 *
 * Fetch errors are surfaced inside the launcher activity, so this
 * screen no longer asks for the POST_NOTIFICATIONS permission.
 */
class WidgetConfigActivity : Activity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isReconfigure: Boolean = false

    private lateinit var rgCurrency: RadioGroup
    private lateinit var rbUsd: RadioButton
    private lateinit var rbEur: RadioButton
    private lateinit var rbBtc: RadioButton
    private lateinit var etTrackedAmount: EditText
    private lateinit var cbShowDecimals: CheckBox
    private lateinit var spSeparator: Spinner
    private lateinit var sbOpacity: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var cbHideLogo: CheckBox
    private lateinit var cbHideUnitLabel: CheckBox

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

        findViewById<Button>(R.id.btn_save).setOnClickListener { persistAndFinish() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    private fun bindViews() {
        rgCurrency = findViewById(R.id.rg_currency)
        rbUsd = findViewById(R.id.rb_usd)
        rbEur = findViewById(R.id.rb_eur)
        rbBtc = findViewById(R.id.rb_btc)
        etTrackedAmount = findViewById(R.id.et_tracked_amount)
        cbShowDecimals = findViewById(R.id.cb_show_decimals)
        spSeparator = findViewById(R.id.sp_separator)
        sbOpacity = findViewById(R.id.sb_opacity)
        opacityValue = findViewById(R.id.opacity_value)
        cbHideLogo = findViewById(R.id.cb_hide_logo)
        cbHideUnitLabel = findViewById(R.id.cb_hide_unit_label)
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

    private fun persistAndFinish() {
        WidgetPrefs.saveCurrency(this, appWidgetId, selectedCurrency())
        WidgetPrefs.saveTrackedAmount(this, appWidgetId, parsedTrackedAmount())
        WidgetPrefs.saveShowDecimals(this, appWidgetId, cbShowDecimals.isChecked)
        WidgetPrefs.saveSeparator(this, appWidgetId, selectedSeparator())
        WidgetPrefs.saveOpacity(this, appWidgetId, sbOpacity.progress)
        WidgetPrefs.saveHideLogo(this, appWidgetId, cbHideLogo.isChecked)
        WidgetPrefs.saveHideUnitLabel(this, appWidgetId, cbHideUnitLabel.isChecked)

        val mgr = AppWidgetManager.getInstance(this)
        BitcoinPriceWidgetProvider.updateWidget(this, mgr, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}
