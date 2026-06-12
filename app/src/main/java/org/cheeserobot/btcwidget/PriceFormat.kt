package org.cheeserobot.btcwidget

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10

/**
 * Locale-aware price formatter that respects the user's per-widget
 * choices for decimals and the thousands separator.
 *
 *   showDecimals = false  →  "78,875"
 *   showDecimals = true   →  "78,875.23"
 *
 * The `separator` string is one of [WidgetPrefs.SEPARATOR_AUTO],
 * [WidgetPrefs.SEPARATOR_COMMA], [WidgetPrefs.SEPARATOR_DOT],
 * [WidgetPrefs.SEPARATOR_SPACE] or [WidgetPrefs.SEPARATOR_NONE]. AUTO
 * delegates to the device locale's default grouping character.
 *
 * The decimal separator is intentionally always `.`. Mixing a custom
 * thousands separator with a locale-dependent decimal separator gets
 * confusing fast (e.g. user picks "." as thousand-sep, gets "1.234,56"
 * on a Dutch device); a single fixed decimal point is unambiguous.
 */
object PriceFormat {

    fun format(price: Double, showDecimals: Boolean, separator: String): String {
        return formatWithDecimals(price, if (showDecimals) 2 else 0, separator)
    }

    /**
     * Format a fiat *price* with a decimal count chosen from its
     * magnitude, so a single fixed "0 or 2 decimals" rule doesn't either
     * clutter large numbers or strip all meaning from small ones.
     *
     * Because the per-currency feed quotes BTC directly in the chosen
     * currency, that magnitude now spans a huge range — a few units of
     * gold (XAU ≈ 15) up to billions of rupiah — and a flat "whole
     * numbers only" display would render gold as a meaningless "15".
     *
     * The target is ~4 significant figures: decimals = 4 − (integer
     * digits), clamped to [0, [MAX_ADAPTIVE_DECIMALS]]:
     *
     *    12345    → 0 decimals → "12,345"
     *    1234.5   → 0          → "1,235"
     *    123.4    → 1          → "123.4"
     *    15.0314  → 2          → "15.03"     (XAU)
     *    1.5      → 3          → "1.500"
     *    0.05     → 3          → "0.050"
     *
     * When the user has explicitly turned "show decimals" on we keep at
     * least 2 (so big round prices still read "63,400.00") but still add
     * more for small values. BTC-amount and block-height displays do NOT
     * use this — they call [format] directly so "1 BTC" stays "1".
     */
    fun formatPrice(price: Double, showDecimals: Boolean, separator: String): String {
        val adaptive = adaptiveDecimals(price)
        val decimals = if (showDecimals) maxOf(2, adaptive) else adaptive
        return formatWithDecimals(price, decimals, separator)
    }

    /** Decimal places for [value], targeting ~4 significant figures. */
    internal fun adaptiveDecimals(value: Double): Int {
        val a = abs(value)
        if (!a.isFinite() || a == 0.0) return 0
        // Integer digits: floor(log10)+1 for a >= 1, else a single
        // leading "0" before the point.
        val intDigits = if (a >= 1.0) floor(log10(a)).toInt() + 1 else 1
        return (TARGET_SIG_FIGS - intDigits).coerceIn(0, MAX_ADAPTIVE_DECIMALS)
    }

    private fun formatWithDecimals(price: Double, decimals: Int, separator: String): String {
        // Pick the grouping character.
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        val groupChar: Char? = when (separator) {
            WidgetPrefs.SEPARATOR_COMMA -> ','
            WidgetPrefs.SEPARATOR_DOT -> '.'
            WidgetPrefs.SEPARATOR_SPACE -> ' '
            WidgetPrefs.SEPARATOR_NONE -> null
            else /* AUTO */ -> symbols.groupingSeparator
        }

        val pattern = if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0"
        val df = DecimalFormat(pattern)
        // Pin to HALF_UP. DecimalFormat's default is HALF_EVEN (banker's
        // rounding), which disagrees with Math.round() on exact .5
        // boundaries. We use Math.round in formatMoscowTime so the two
        // formatters can disagree on the last digit otherwise — the
        // user reported this when 1223.7-ish rendered as "1 224"
        // numerically but "12:23" as Moscow Time.
        df.roundingMode = RoundingMode.HALF_UP
        if (groupChar == null) {
            df.isGroupingUsed = false
        } else {
            df.isGroupingUsed = true
            df.decimalFormatSymbols = symbols.apply {
                groupingSeparator = groupChar
                decimalSeparator = '.'
            }
        }
        return df.format(price)
    }

    /** Significant-figure target for [adaptiveDecimals]. */
    private const val TARGET_SIG_FIGS = 4

    /** Hard cap on adaptive decimals so tiny values don't explode the width. */
    private const val MAX_ADAPTIVE_DECIMALS = 4

    /**
     * Easter-egg "Moscow Time" formatter. The sats-per-USD figure
     * normally lands in the 800–1,500 range — a coincidence that
     * resembles a HH:MM clock when the four digits are split with a
     * colon before the last two. We round to an integer, then place
     * a single ':' two digits from the end:
     *
     *    1234   ->  "12:34"
     *    1000   ->  "10:00"
     *    830    ->  "8:30"     (3-digit values still split cleanly)
     *    12345  ->  "123:45"   (won't happen at realistic BTC prices)
     *
     * Negative or non-finite inputs degrade to "0:00" so the widget
     * never renders junk. The decision to *invoke* this formatter
     * (rather than the regular numeric one) is taken at the call site
     * — see [BitcoinPriceWidgetProvider] and [WidgetConfigActivity].
     */
    fun formatMoscowTime(price: Double): String {
        if (!price.isFinite() || price < 0) return "0:00"
        // Math.round rounds half AWAY FROM ZERO for positive values
        // (i.e. HALF_UP). Matches the DecimalFormat path above so the
        // numeric rendering and the Moscow-Time rendering of the same
        // underlying double always agree on every digit. Earlier we
        // used `toLong()` which truncates; that produced "12:23" for
        // values like 1223.7 while the numeric path showed "1 224".
        val n = Math.round(price)
        val s = n.toString()
        return when {
            s.length <= 2 -> "0:" + s.padStart(2, '0')
            else -> s.substring(0, s.length - 2) + ":" + s.substring(s.length - 2)
        }
    }

    /**
     * Should the easter-egg Moscow-Time formatter be used for this
     * widget? Returns true only when ALL of these hold:
     *   - currency is SATS (sats per USD),
     *   - the user has chosen *some* thousands separator (anything
     *     except SEPARATOR_NONE — AUTO/COMMA/DOT/SPACE all qualify),
     *   - the user has flipped the hidden Moscow-Time toggle on.
     *
     * The first two are the "unlock" conditions that surface the
     * toggle in the config UI in the first place; we re-check them
     * here so a change to currency or separator after the toggle was
     * saved silently disables the easter egg until conditions are
     * re-met.
     */
    fun isMoscowTimeActive(
        currency: String,
        separator: String,
        moscowTimeEnabled: Boolean,
    ): Boolean {
        if (!moscowTimeEnabled) return false
        if (!currency.equals(WidgetPrefs.CURRENCY_SATS, ignoreCase = true)) return false
        if (separator == WidgetPrefs.SEPARATOR_NONE) return false
        return true
    }
}
