package org.cheeserobot.btcwidget

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

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
        // Pick the grouping character.
        val symbols = DecimalFormatSymbols(Locale.getDefault())
        val groupChar: Char? = when (separator) {
            WidgetPrefs.SEPARATOR_COMMA -> ','
            WidgetPrefs.SEPARATOR_DOT -> '.'
            WidgetPrefs.SEPARATOR_SPACE -> ' '
            WidgetPrefs.SEPARATOR_NONE -> null
            else /* AUTO */ -> symbols.groupingSeparator
        }

        val pattern = if (showDecimals) "#,##0.00" else "#,##0"
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
