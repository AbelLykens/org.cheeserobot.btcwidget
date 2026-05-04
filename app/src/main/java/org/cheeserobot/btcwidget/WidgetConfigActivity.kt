package org.cheeserobot.btcwidget

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Shown automatically when the user adds the widget to the home screen.
 * Lets the user pick USD or EUR, persists the choice, asks for the
 * notification permission (so we can surface fetch errors), and triggers
 * the first widget render.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingCurrency: String? = null

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whatever the user chose, proceed: errors will still be logged
        // to logcat under the "CheeseBTC" tag.
        finishWithCurrency(pendingCurrency ?: WidgetPrefs.CURRENCY_USD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the user backs out, the widget should NOT be added.
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

        // Make sure the channel exists before any error notifications fire.
        Notifier.ensureChannel(this)

        findViewById<Button>(R.id.btn_usd).setOnClickListener {
            confirm(WidgetPrefs.CURRENCY_USD)
        }
        findViewById<Button>(R.id.btn_eur).setOnClickListener {
            confirm(WidgetPrefs.CURRENCY_EUR)
        }
    }

    private fun confirm(currency: String) {
        pendingCurrency = currency
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        finishWithCurrency(currency)
    }

    private fun finishWithCurrency(currency: String) {
        WidgetPrefs.saveCurrency(this, appWidgetId, currency)

        val mgr = AppWidgetManager.getInstance(this)
        BitcoinPriceWidgetProvider.updateWidget(this, mgr, appWidgetId)

        val resultValue = Intent().putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId
        )
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}
