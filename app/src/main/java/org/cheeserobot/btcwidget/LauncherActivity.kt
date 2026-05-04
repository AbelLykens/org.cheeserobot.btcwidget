package org.cheeserobot.btcwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Plain launcher screen so the app icon does something useful when tapped.
 * Walks the user through adding the widget, lets them sanity-check that
 * the price feed is reachable, and gives a one-tap refresh for any
 * widgets they've already placed.
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        val previewView = findViewById<TextView>(R.id.preview_text)

        findViewById<Button>(R.id.btn_open_home).setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            refreshAllWidgets()
        }

        // Run a quick connectivity check so the user can verify the price
        // feed before placing the widget.
        previewView.text = getString(R.string.preview_loading)
        CoroutineScope(Dispatchers.IO).launch {
            val usd = PriceFetcher.fetchPrice(WidgetPrefs.CURRENCY_USD)
            val eur = PriceFetcher.fetchPrice(WidgetPrefs.CURRENCY_EUR)
            withContext(Dispatchers.Main) {
                previewView.text = formatPreview(usd, eur)
            }
        }
    }

    private fun refreshAllWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(
            ComponentName(this, BitcoinPriceWidgetProvider::class.java)
        )
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_widgets, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, BitcoinPriceWidgetProvider::class.java).apply {
            action = BitcoinPriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        sendBroadcast(intent)
        Toast.makeText(
            this,
            getString(R.string.toast_refresh_sent, ids.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun formatPreview(usd: PriceResult, eur: PriceResult): String {
        fun line(label: String, r: PriceResult): String = when (r) {
            is PriceResult.Success -> "$label  ${formatWhole(r.price)}"
            is PriceResult.Error -> "$label  — (${r.reason.take(60)})"
        }
        return line("$", usd) + "\n" + line("€", eur)
    }

    private fun formatWhole(price: Double): String {
        val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
        nf.isGroupingUsed = true
        return nf.format(price.toLong())
    }
}
