package org.cheeserobot.btcwidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Posts a system notification when the widget can't load a price, so the
 * user (and you, the developer) can see *why*.
 *
 * Notifications also get logged to logcat under the "CheeseBTC" tag — you
 * can stream them on your machine with:
 *
 *     adb logcat -s CheeseBTC:V
 */
object Notifier {

    private const val TAG = "CheeseBTC"
    private const val CHANNEL_ID = "btc_widget_errors"
    private const val CHANNEL_NAME = "BTC Widget errors"
    private const val CHANNEL_DESC = "Shown when the widget fails to load a price."

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = CHANNEL_DESC }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    fun notifyError(context: Context, appWidgetId: Int, message: String) {
        Log.w(TAG, "Widget $appWidgetId error: $message")
        ensureChannel(context)

        // Tap notification → fire ACTION_REFRESH for this widget.
        val retryIntent = Intent(context, BitcoinPriceWidgetProvider::class.java).apply {
            action = BitcoinPriceWidgetProvider.ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, appWidgetId, retryIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bitcoin)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // POST_NOTIFICATIONS is a runtime permission on Android 13+. If the
        // user hasn't granted it we can't show — but we've already logged
        // to logcat, so debugging via adb still works.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted; not showing notification")
                return
            }
        }

        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIF_ID_BASE + appWidgetId, notification)
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification post threw SecurityException", se)
        }
    }

    fun cancelError(context: Context, appWidgetId: Int) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID_BASE + appWidgetId)
    }

    private const val NOTIF_ID_BASE = 1000
}
