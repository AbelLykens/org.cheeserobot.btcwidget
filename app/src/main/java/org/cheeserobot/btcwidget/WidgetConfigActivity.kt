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
import android.graphics.drawable.GradientDrawable
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

    /**
     * Guards [finish] against re-entering the persist path more than
     * once per activity lifecycle. Without this, an exotic finish()
     * call sequence could write the prefs twice and trigger two widget
     * updates back-to-back.
     */
    private var hasPersisted: Boolean = false

    // Currency (simple section)
    private lateinit var rgCurrency: RadioGroup
    private lateinit var rbUsd: RadioButton
    private lateinit var rbEur: RadioButton
    private lateinit var rbBtc: RadioButton
    private lateinit var rbSats: RadioButton
    private lateinit var rbBlock: RadioButton

    // Advanced toggle + container
    private lateinit var advancedToggle: TextView
    private lateinit var advancedSection: LinearLayout

    // Advanced section views
    private lateinit var etTrackedAmount: EditText
    private lateinit var cbShowDecimals: CheckBox
    private lateinit var spSeparator: Spinner
    private lateinit var sbOpacity: SeekBar
    private lateinit var opacityValue: TextView
    // Container groups, used to hide whole option blocks (label + control
    // + hint) when the chosen currency makes them irrelevant — e.g. the
    // tracked-amount editor is meaningless for BLOCK mode.
    private lateinit var groupTrackedAmount: View
    private lateinit var groupNumberFormat: View
    private lateinit var groupPriceChange: View
    // All four toggles are user-affirmative ("Show X"). The underlying
    // SharedPreferences keys are still KEY_HIDE_* — we invert at every
    // load and save so that storing a checked CheckBox writes hide=false.
    private lateinit var cbShowLogo: CheckBox
    private lateinit var cbShowCurrencyIcon: CheckBox
    private lateinit var cbShowUnitLabel: CheckBox
    private lateinit var cbShowChart: CheckBox
    private lateinit var rgChange: RadioGroup
    private lateinit var rbChange1d: RadioButton
    private lateinit var rbChange1w: RadioButton

    /**
     * Easter-egg toggle. Bound to a CheckBox that's invisible by
     * default and only revealed when [shouldShowMoscowTimeToggle]
     * returns true (currency == SATS && thousands separator in use).
     */
    private lateinit var cbMoscowTime: CheckBox

    // ---- Price text colour picker ----------------------------------------
    //
    // The swatches are plain Views (the "default" one is a FrameLayout
    // wrapping a TextView with the letter "A"). Each gets its background
    // drawable assigned programmatically — a filled circle in the swatch's
    // colour, with a thicker stroke when it's the currently selected one.
    // selectedPriceColor holds the chosen ARGB int; PRICE_TEXT_COLOR_DEFAULT
    // (== 0) means "no override, follow theme".
    private lateinit var colorSwatchDefault: View
    private lateinit var colorSwatchDefaultLabel: TextView
    private lateinit var colorSwatchBlack: View
    private lateinit var colorSwatchWhite: View
    private lateinit var colorSwatchOrange: View
    private lateinit var colorSwatchRed: View
    private lateinit var colorSwatchGreen: View
    private lateinit var colorSwatchBlue: View
    private var selectedPriceColor: Int = WidgetPrefs.PRICE_TEXT_COLOR_DEFAULT

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

    /**
     * Colours offered in the price-text colour picker. The first entry is
     * the "default" sentinel (== 0): when selected the widget falls back
     * to the theme colour at render time. The remaining swatches are
     * literal ARGB ints and override the theme regardless of light/dark
     * mode. Order here drives both the on-screen swatch order and the
     * fill colour for each swatch.
     */
    private val priceColorOptions = listOf(
        WidgetPrefs.PRICE_TEXT_COLOR_DEFAULT,
        0xFF000000.toInt(), // black
        0xFFFFFFFF.toInt(), // white
        0xFFF7931A.toInt(), // bitcoin orange
        0xFFD32F2F.toInt(), // red
        0xFF388E3C.toInt(), // green
        0xFF1976D2.toInt(), // blue
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

        // The single "Done" button is now just an explicit close — the
        // actual persistence happens inside the overridden finish(), so
        // the system back button and the Done button take exactly the
        // same path. Keeping the button gives users a visible confirm
        // affordance even though it's no longer strictly required.
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
    }

    private fun bindViews() {
        rgCurrency = findViewById(R.id.rg_currency)
        rbUsd = findViewById(R.id.rb_usd)
        rbEur = findViewById(R.id.rb_eur)
        rbBtc = findViewById(R.id.rb_btc)
        rbSats = findViewById(R.id.rb_sats)
        rbBlock = findViewById(R.id.rb_block)

        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedSection = findViewById(R.id.advanced_section)

        etTrackedAmount = findViewById(R.id.et_tracked_amount)
        cbShowDecimals = findViewById(R.id.cb_show_decimals)
        spSeparator = findViewById(R.id.sp_separator)
        sbOpacity = findViewById(R.id.sb_opacity)
        opacityValue = findViewById(R.id.opacity_value)
        groupTrackedAmount = findViewById(R.id.group_tracked_amount)
        groupNumberFormat = findViewById(R.id.group_number_format)
        groupPriceChange = findViewById(R.id.group_price_change)
        cbShowLogo = findViewById(R.id.cb_show_logo)
        cbShowCurrencyIcon = findViewById(R.id.cb_show_currency_icon)
        cbShowUnitLabel = findViewById(R.id.cb_show_unit_label)
        cbShowChart = findViewById(R.id.cb_show_chart)
        rgChange = findViewById(R.id.rg_change)
        rbChange1d = findViewById(R.id.rb_change_1d)
        rbChange1w = findViewById(R.id.rb_change_1w)
        cbMoscowTime = findViewById(R.id.cb_moscow_time)

        colorSwatchDefault = findViewById(R.id.color_swatch_default)
        colorSwatchDefaultLabel = findViewById(R.id.color_swatch_default_label)
        colorSwatchBlack = findViewById(R.id.color_swatch_black)
        colorSwatchWhite = findViewById(R.id.color_swatch_white)
        colorSwatchOrange = findViewById(R.id.color_swatch_orange)
        colorSwatchRed = findViewById(R.id.color_swatch_red)
        colorSwatchGreen = findViewById(R.id.color_swatch_green)
        colorSwatchBlue = findViewById(R.id.color_swatch_blue)

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
        // The header used to carry a "Choose your currency" title and a
        // "Pick how you want…" / "Update your widget settings" subtitle
        // that switched copy on reconfigure. Both TextViews were dropped
        // when the header was slimmed down to just the logo + preview,
        // so there's no copy to set here any more — the live preview
        // itself is the user's orientation cue.

        val currency = WidgetPrefs.loadCurrency(this, appWidgetId)
        when (currency) {
            WidgetPrefs.CURRENCY_EUR -> rbEur.isChecked = true
            WidgetPrefs.CURRENCY_BTC -> rbBtc.isChecked = true
            WidgetPrefs.CURRENCY_SATS -> rbSats.isChecked = true
            WidgetPrefs.CURRENCY_BLOCK -> rbBlock.isChecked = true
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

        // loadChangeIndicator now never returns CHANGE_OFF — legacy
        // values are migrated to CHANGE_1D inside WidgetPrefs.
        when (WidgetPrefs.loadChangeIndicator(this, appWidgetId)) {
            WidgetPrefs.CHANGE_1W -> rbChange1w.isChecked = true
            else -> rbChange1d.isChecked = true
        }

        // Restore the easter-egg toggle and let the visibility helper
        // decide whether to actually show it on screen. If the user
        // saved with it on but later flipped currency away from SATS
        // (or picked separator=None), the CheckBox stays hidden and
        // the price formatter naturally falls back to numeric.
        cbMoscowTime.isChecked = WidgetPrefs.loadMoscowTime(this, appWidgetId)
        applyMoscowTimeVisibility()

        // Restore the saved colour swatch selection (or default sentinel)
        // and paint the row so the right swatch carries the active ring.
        selectedPriceColor = WidgetPrefs.loadPriceTextColor(this, appWidgetId)
        renderColorSwatches()

        // Initial pass over the per-mode visibility rules — hides the
        // option groups that don't apply to whatever currency was just
        // restored from prefs (e.g. tracked-amount editor in BLOCK mode).
        applyCurrencyVisibility()

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
        if (WidgetPrefs.loadPriceTextColor(this, appWidgetId) != WidgetPrefs.PRICE_TEXT_COLOR_DEFAULT) return true
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
            applyCurrencyVisibility()
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
        wireColorSwatchListeners()
    }

    /**
     * Wire each swatch View to set [selectedPriceColor] and re-render
     * both the swatch row (so the selection ring follows the click) and
     * the preview (so the price text colour updates immediately).
     */
    private fun wireColorSwatchListeners() {
        val swatches = colorSwatches()
        for ((index, view) in swatches.withIndex()) {
            val color = priceColorOptions[index]
            view.setOnClickListener {
                selectedPriceColor = color
                renderColorSwatches()
                updatePreview()
            }
        }
    }

    /**
     * Returns the swatch Views in the same order as [priceColorOptions]
     * so callers can zip the two lists by index without juggling ids.
     */
    private fun colorSwatches(): List<View> = listOf(
        colorSwatchDefault,
        colorSwatchBlack,
        colorSwatchWhite,
        colorSwatchOrange,
        colorSwatchRed,
        colorSwatchGreen,
        colorSwatchBlue,
    )

    /**
     * Paint each swatch with its colour (or the theme text colour, for
     * the "default" swatch) and overlay a thicker stroke on whichever
     * one matches [selectedPriceColor]. Called on initial bind, on every
     * swatch tap, and after configuration changes that flip the theme
     * (so the default swatch follows light/dark mode).
     */
    private fun renderColorSwatches() {
        val density = resources.displayMetrics.density
        val selectedStrokePx = (3 * density).toInt().coerceAtLeast(2)
        val unselectedStrokePx = (1 * density).toInt().coerceAtLeast(1)
        val selectedStrokeColor = getColor(R.color.bitcoin_orange)
        val unselectedStrokeColor = 0x66888888.toInt()
        val themeTextColor = getColor(R.color.widget_text)

        val swatches = colorSwatches()
        for ((index, view) in swatches.withIndex()) {
            val color = priceColorOptions[index]
            val fill = if (color == WidgetPrefs.PRICE_TEXT_COLOR_DEFAULT)
                themeTextColor else color
            val isSelected = color == selectedPriceColor
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
                setStroke(
                    if (isSelected) selectedStrokePx else unselectedStrokePx,
                    if (isSelected) selectedStrokeColor else unselectedStrokeColor,
                )
            }
            view.background = drawable
        }
        // The "default" swatch carries an "A" label whose own colour
        // needs to contrast with the swatch fill (the theme text colour),
        // i.e. it should match the widget background — that way the
        // letter reads as inverted text on the chip.
        colorSwatchDefaultLabel.setTextColor(getColor(R.color.widget_background_solid))
    }

    /**
     * Hide controls that don't apply to the currently selected currency.
     *
     * BLOCK mode is the most aggressive cull — block height isn't a
     * scaled price, so tracked amount, decimals, separator, currency
     * icon, and the price-change indicator all get hidden. The unit
     * caption toggle stays visible but its label flips to "Show miner
     * name" since BLOCK reuses the slot for the pool / miner.
     *
     * BTC mode hides only the price-change indicator: 1 BTC has always
     * been worth 1 BTC, so a 24h % move is meaningless.
     *
     * Every other mode (USD/EUR/SATS) shows everything. Called on
     * initial bind and on every currency-radio change.
     */
    private fun applyCurrencyVisibility() {
        val currency = selectedCurrency()
        val isBlock = currency == WidgetPrefs.CURRENCY_BLOCK
        val isBtc = currency == WidgetPrefs.CURRENCY_BTC

        groupTrackedAmount.visibility = if (isBlock) View.GONE else View.VISIBLE
        groupNumberFormat.visibility = if (isBlock) View.GONE else View.VISIBLE
        cbShowDecimals.visibility = if (isBlock) View.GONE else View.VISIBLE
        cbShowCurrencyIcon.visibility = if (isBlock) View.GONE else View.VISIBLE

        // Relabel the unit-caption toggle so BLOCK users read it as
        // "Show miner name". The underlying preference key is the same
        // (KEY_HIDE_UNIT_PREFIX) — only the visible string changes.
        cbShowUnitLabel.setText(
            if (isBlock) R.string.config_show_miner_name
            else R.string.config_show_unit_label
        )

        // Hide the change-indicator block for currencies where it has
        // no meaningful reading. For BTC the historical is always 1; for
        // BLOCK the diagonal sparkline already says "only goes up".
        groupPriceChange.visibility =
            if (isBlock || isBtc) View.GONE else View.VISIBLE
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
        val isBlock = currency == WidgetPrefs.CURRENCY_BLOCK
        val tracked = parsedTrackedAmount()
        val showDecimals = cbShowDecimals.isChecked
        val separator = selectedSeparator()
        val hideLogo = !cbShowLogo.isChecked
        val hideUnit = !cbShowUnitLabel.isChecked
        val changeMode = selectedChangeIndicator()
        val opacity = sbOpacity.progress.coerceIn(0, 100)

        val (samplePrice, sampleOneDay, sampleOneWeek) = sampleData(currency)
        // BLOCK mode shows the literal block height; "tracked amount"
        // doesn't apply to a block count, so we don't scale by it.
        val displayed = if (isBlock) samplePrice else samplePrice * tracked
        val moscowTime = cbMoscowTime.isChecked
        val priceText = if (isBlock) {
            // Block height is an integer count, never decimals or
            // Moscow-Time-formatted. Honour the thousands separator so
            // 948,347 reads more easily than 948347.
            PriceFormat.format(displayed, showDecimals = false, separator)
        } else if (PriceFormat.isMoscowTimeActive(currency, separator, moscowTime)) {
            PriceFormat.formatMoscowTime(displayed)
        } else {
            PriceFormat.format(displayed, showDecimals, separator)
        }
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

        // Mirror the live widget's colour pick so the preview always
        // shows the user what they'll get. PRICE_TEXT_COLOR_DEFAULT
        // means "no override" — fall back to the theme colour.
        val priceColor = if (selectedPriceColor == WidgetPrefs.PRICE_TEXT_COLOR_DEFAULT)
            getColor(R.color.widget_text) else selectedPriceColor
        previewPrice.setTextColor(priceColor)
        // Unit label keeps the existing 0.7 alpha defined in the layout.
        previewUnit.setTextColor(priceColor)

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

        if (isBlock) {
            // BLOCK mode commandeers the unit-label slot for the
            // miner / pool name. The "Show 1 BTC caption" toggle still
            // controls visibility — turning it off hides the miner too,
            // which is the most natural carry-over of the existing flag
            // for users who want a minimal block widget.
            if (hideUnit) {
                previewUnit.visibility = View.GONE
            } else {
                previewUnit.visibility = View.VISIBLE
                previewUnit.text = "SpiderPool"
            }
        } else if (hideUnit) {
            previewUnit.visibility = View.GONE
        } else {
            previewUnit.visibility = View.VISIBLE
            previewUnit.text = formatUnitLabel(tracked, currency)
        }

        previewBackground.imageAlpha = (opacity * 255 / 100)

        // The chart now spans the same window as the price-change
        // selection, so the preview picks its endpoint accordingly.
        val chartHistorical = when (changeMode) {
            WidgetPrefs.CHANGE_1W -> sampleOneWeek
            else -> sampleOneDay
        }
        renderPreviewSparkline(currency, chartHistorical, samplePrice, changeMode)

        // BLOCK mode has no concept of "change %" — block height only
        // ever increases, the diagonal line behind the number already
        // says so. Hide the change indicator entirely for that mode.
        val historical: Double? = when {
            isBlock -> null
            changeMode == WidgetPrefs.CHANGE_1W -> sampleOneWeek
            else -> sampleOneDay
        }
        if (historical == null) {
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
     * curve from the sample period-ago and current price so the
     * preview still shows *something* — empty preview is the bug
     * report's symptom.
     *
     * The selected [changeMode] picks the window: CHANGE_1D reads the
     * 24-hour cache, CHANGE_1W the 7-day cache. The current sample is
     * always appended as the rightmost data point so the line ends at
     * "now" — same convention as the live widget.
     */
    private fun renderPreviewSparkline(
        currency: String,
        periodAgo: Double?,
        currentSample: Double,
        changeMode: String,
    ) {
        if (!cbShowChart.isChecked) {
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
        val strokePx = (1.5f * density).coerceAtLeast(1.5f)

        // BTC mode: always paint a flat green line in the preview, same
        // visual joke the live widget paints. Bypasses every series /
        // cache / up-or-down code path.
        if (currency.equals(WidgetPrefs.CURRENCY_BTC, ignoreCase = true)) {
            val color = getColor(R.color.sparkline_up)
            val bmp = SparklineRenderer.renderFlat(
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
            return
        }

        // BLOCK mode: paint a diagonal line going up. Block height only
        // ever increases, so a simple bottom-left -> top-right stroke
        // tells the joke without needing a real series.
        if (currency.equals(WidgetPrefs.CURRENCY_BLOCK, ignoreCase = true)) {
            val color = getColor(R.color.sparkline_up)
            val bmp = SparklineRenderer.renderDiagonal(
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
            return
        }

        val rawSeries = cachedSeriesFor(currency, changeMode)
            ?: synthesiseSeries(periodAgo, currentSample)
        if (rawSeries == null || rawSeries.size < 2) {
            previewSparkline.visibility = View.GONE
            return
        }
        // Always paint with the latest sample on the right edge.
        val series = appendLatest(rawSeries, currentSample)

        val colorRes = if (SparklineRenderer.isUp(series))
            R.color.sparkline_up else R.color.sparkline_down
        val color = getColor(colorRes)

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
     * Pull a real series out of the global history cache for [period]
     * (CHANGE_1D or CHANGE_1W). Returns null when no widget has fetched
     * yet, the cache is corrupt, or the parsed series is too short.
     * SATS-mode rides on the USD series and inverts each point, mirroring
     * [BitcoinPriceWidgetProvider.buildSparklineBitmap].
     */
    private fun cachedSeriesFor(currency: String, period: String): DoubleArray? {
        val cached = WidgetPrefs.loadHistoryJson(this, period) ?: return null
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
     * Append [latest] as a new rightmost point to [series]. If the
     * existing tail is already equal to [latest] (no real movement
     * since the last cached sample) we skip the append so the line
     * doesn't end with a degenerate horizontal segment.
     */
    private fun appendLatest(series: DoubleArray, latest: Double): DoubleArray {
        if (!latest.isFinite()) return series
        if (series.isNotEmpty() && series.last() == latest) return series
        val out = DoubleArray(series.size + 1)
        System.arraycopy(series, 0, out, 0, series.size)
        out[series.size] = latest
        return out
    }

    /**
     * Build a synthetic curve when we have no cached history. We trace
     * a gently wavy path between the period-ago sample and the current
     * sample so the line shape reads as "a real chart" rather than a
     * straight diagonal. BTC mode (no historical) returns null — the
     * chart simply hides for that mode.
     */
    private fun synthesiseSeries(periodAgo: Double?, current: Double): DoubleArray? {
        val weekAgo = periodAgo
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
     * Sample (currentPrice, oneDayAgo, oneWeekAgo) for the live preview.
     *
     * Prefers REAL cached data so the preview matches what the home
     * screen widget will actually paint:
     *   - current price comes from `loadLatestUpstreamPrice` (saved on
     *     every successful summary fetch), or null and we synthesise
     *     from the chart cache's tail / fall back to a hardcoded value;
     *   - 24h-ago / 7d-ago references are pulled from the FIRST entry
     *     of the cached `hist_1d` / `hist_7d` arrays.
     *
     * Falling back to hardcoded numbers only when no widget has fetched
     * yet keeps the very first launch usable, but as soon as one
     * placed widget has hit the wire the preview lines up with reality.
     * This used to be a static triple, which is why the change
     * indicator could read "+1.1% 24h" while the chart line behind it
     * dipped — the two read from different sources.
     */
    private fun sampleData(currency: String): Triple<Double, Double?, Double?> {
        return when (currency) {
            WidgetPrefs.CURRENCY_BTC -> Triple(1.0, null, null)
            WidgetPrefs.CURRENCY_BLOCK -> {
                val cached = WidgetPrefs.loadLatestBlockHeight(this)?.toDouble()
                Triple(cached ?: 948_347.0, null, null)
            }
            WidgetPrefs.CURRENCY_SATS -> {
                // SATS rides on USD: invert every component value.
                // Fallbacks below are taken from the example summary
                // payload so the implied direction (24h down, 7d up)
                // matches a realistic recent snapshot — it used to be
                // a flat +1% line that argued with the chart shape.
                val usd = liveOrFallbackPrice(WidgetPrefs.CURRENCY_USD, fallback = 80175.08)
                val usd1d = liveOrFallbackHistorical(
                    WidgetPrefs.CURRENCY_USD, WidgetPrefs.HISTORY_1D, fallback = 81413.00
                )
                val usd1w = liveOrFallbackHistorical(
                    WidgetPrefs.CURRENCY_USD, WidgetPrefs.HISTORY_7D, fallback = 76377.00
                )
                Triple(
                    100_000_000.0 / usd,
                    usd1d?.let { 100_000_000.0 / it },
                    usd1w?.let { 100_000_000.0 / it },
                )
            }
            WidgetPrefs.CURRENCY_EUR -> Triple(
                liveOrFallbackPrice(WidgetPrefs.CURRENCY_EUR, fallback = 68246.54),
                liveOrFallbackHistorical(
                    WidgetPrefs.CURRENCY_EUR, WidgetPrefs.HISTORY_1D, fallback = 69308.00
                ),
                liveOrFallbackHistorical(
                    WidgetPrefs.CURRENCY_EUR, WidgetPrefs.HISTORY_7D, fallback = 65109.00
                ),
            )
            else -> Triple(
                liveOrFallbackPrice(WidgetPrefs.CURRENCY_USD, fallback = 80175.08),
                liveOrFallbackHistorical(
                    WidgetPrefs.CURRENCY_USD, WidgetPrefs.HISTORY_1D, fallback = 81413.00
                ),
                liveOrFallbackHistorical(
                    WidgetPrefs.CURRENCY_USD, WidgetPrefs.HISTORY_7D, fallback = 76377.00
                ),
            )
        }
    }

    /**
     * Latest fetched upstream price for [currency], or [fallback] when
     * no widget has fetched yet (or the saved value is non-finite).
     */
    private fun liveOrFallbackPrice(currency: String, fallback: Double): Double {
        val v = WidgetPrefs.loadLatestUpstreamPrice(this, currency) ?: return fallback
        return if (v.isFinite() && v > 0) v else fallback
    }

    /**
     * First (oldest) value in the cached history series for [period],
     * which is the natural "1 day ago" / "1 week ago" reference. Falls
     * back to [fallback] when nothing is cached yet — same shape the
     * live widget uses for the change-indicator math.
     */
    private fun liveOrFallbackHistorical(
        currency: String,
        period: String,
        fallback: Double,
    ): Double? {
        val cached = WidgetPrefs.loadHistoryJson(this, period) ?: return fallback
        val parsed = HistoryFetcher.parse(cached) as? HistoryResult.Success
            ?: return fallback
        val series = HistoryFetcher.seriesFor(parsed.points, currency)
        if (series.isEmpty()) return fallback
        val head = series.first()
        return if (head.isFinite() && head > 0) head else fallback
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
        rbBlock.isChecked -> WidgetPrefs.CURRENCY_BLOCK
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
        rbChange1w.isChecked -> WidgetPrefs.CHANGE_1W
        else -> WidgetPrefs.CHANGE_1D
    }

    /**
     * Persist every UI value to SharedPreferences, set RESULT_OK so
     * the appWidgetId Android handed us is committed (required on
     * fresh-add, no-op on reconfigure), and trigger a widget refresh
     * so the new settings hit the home screen immediately.
     *
     * Called from [finish] — guarded by [hasPersisted] so even an
     * abnormal close path can't double-write.
     */
    private fun persistAndPushUpdate() {
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
        WidgetPrefs.savePriceTextColor(this, appWidgetId, selectedPriceColor)

        val mgr = AppWidgetManager.getInstance(this)
        BitcoinPriceWidgetProvider.updateWidget(this, mgr, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
    }

    /**
     * Auto-save hook: every exit path through this Activity (the Done
     * button, the system back button, the swipe-back gesture, an
     * explicit finish() from anywhere else) routes through here, so the
     * user no longer has to tap an explicit Save button to keep their
     * changes.
     *
     * The early-return on [appWidgetId] == INVALID_APPWIDGET_ID covers
     * the bail-out branch in [onCreate] — when launched without a valid
     * id we finish immediately without touching prefs.
     */
    override fun finish() {
        if (!hasPersisted && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            hasPersisted = true
            persistAndPushUpdate()
        }
        super.finish()
    }
}
