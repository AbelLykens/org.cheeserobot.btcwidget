package org.cheeserobot.btcwidget

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
}
