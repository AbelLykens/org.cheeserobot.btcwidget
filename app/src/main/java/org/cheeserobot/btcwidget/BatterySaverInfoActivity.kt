package org.cheeserobot.btcwidget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings

/**
 * Translucent activity launched when the user taps the widget while
 * battery saver is on.
 *
 * Two reasons this is an Activity rather than a Toast or Notification:
 *
 *  • Toasts from a BroadcastReceiver context are suppressed by Android
 *    when the user has disabled notifications for the app (logcat:
 *    "Suppressing toast from package … by user request"). Activities
 *    are not subject to that suppression.
 *
 *  • An Activity gives us a foreground process — required to register
 *    a dynamic receiver for [PowerManager.ACTION_POWER_SAVE_MODE_CHANGED].
 *    That broadcast is flagged FLAG_RECEIVER_REGISTERED_ONLY, so a
 *    manifest receiver would never see it, but a runtime-registered
 *    one does. While the dialog is open, toggling battery saver off
 *    from the system settings dialog flips the widget icon back to
 *    colour right away.
 *
 * Whenever the activity is destroyed (any path: dialog dismissed, user
 * pressed back, user opened settings and came back) we send an
 * ACTION_REFRESH broadcast so every placed widget repaints itself
 * against the *current* battery-saver state.
 */
class BatterySaverInfoActivity : Activity() {

    private var receiverRegistered = false

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
            // Battery saver flipped while the dialog was on screen.
            // Refresh all widgets and dismiss; the user has either just
            // turned saver off (so widgets should fetch a fresh price)
            // or just turned it on (so widgets should grey out).
            broadcastWidgetRefresh()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 34+ requires explicit export flag for context-
            // registered receivers. ACTION_POWER_SAVE_MODE_CHANGED is a
            // protected system broadcast so the flag is technically
            // ignored, but we set RECEIVER_NOT_EXPORTED for hygiene.
            registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(powerSaveReceiver, filter)
        }
        receiverRegistered = true

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_saver_dialog_title)
            .setMessage(R.string.battery_saver_dialog_message)
            .setPositiveButton(R.string.battery_saver_dialog_open_settings) { _, _ ->
                // Deliberately don't dismiss: we want the activity to
                // stay alive in the background while the user is in the
                // settings screen so our dynamic
                // ACTION_POWER_SAVE_MODE_CHANGED receiver can still
                // fire. If the user toggles saver off in settings the
                // receiver finishes us; if they come back without
                // changing anything the dialog reappears so they can
                // dismiss it themselves.
                openBatterySaverSettings()
            }
            .setNegativeButton(R.string.battery_saver_dialog_dismiss) { d, _ ->
                d.dismiss()
            }
            .setOnDismissListener { finish() }
            .setOnCancelListener(DialogInterface.OnCancelListener { finish() })
            .show()
    }

    override fun onResume() {
        super.onResume()
        // The user might have toggled battery saver off in the system
        // settings screen and pressed back to return here, in which case
        // ACTION_POWER_SAVE_MODE_CHANGED already fired (and we already
        // finished). But the broadcast can race with onResume on some
        // OEM ROMs, so double-check here as a safety net.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isPowerSaveMode) {
            broadcastWidgetRefresh()
            finish()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(powerSaveReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered — defensive only.
            }
            receiverRegistered = false
        }
        // Always send a refresh on the way out: covers the "user tapped
        // OK without changing anything" case (so the icon stays grey)
        // and the "user disabled saver from settings" case (so the icon
        // goes back to colour and a fresh price is fetched).
        broadcastWidgetRefresh()
        super.onDestroy()
    }

    private fun openBatterySaverSettings() {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Some OEM ROMs don't expose the battery saver settings
            // screen — fall back to general settings rather than
            // crashing.
            startActivity(
                Intent(Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun broadcastWidgetRefresh() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(
            ComponentName(this, BitcoinPriceWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        val refresh = Intent(this, BitcoinPriceWidgetProvider::class.java).apply {
            action = BitcoinPriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        sendBroadcast(refresh)
    }
}
