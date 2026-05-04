package org.cheeserobot.btcwidget

import android.content.Context

/**
 * Lightweight helper around SharedPreferences for per-widget settings.
 *
 * Each placed widget has its own appWidgetId; we store its currency choice
 * keyed by that id so multiple widgets can show different currencies.
 */
object WidgetPrefs {

    private const val PREFS_NAME = "widget_prefs"
    private const val KEY_CURRENCY_PREFIX = "currency_"

    const val CURRENCY_USD = "USD"
    const val CURRENCY_EUR = "EUR"

    fun saveCurrency(context: Context, appWidgetId: Int, currency: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENCY_PREFIX + appWidgetId, currency)
            .apply()
    }

    fun loadCurrency(context: Context, appWidgetId: Int): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENCY_PREFIX + appWidgetId, CURRENCY_USD)
            ?: CURRENCY_USD
    }

    fun deleteCurrency(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CURRENCY_PREFIX + appWidgetId)
            .apply()
    }

    fun symbolFor(currency: String): String = when (currency.uppercase()) {
        CURRENCY_EUR -> "€" // €
        else -> "$"
    }
}
