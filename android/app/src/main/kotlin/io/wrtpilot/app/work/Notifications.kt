package io.wrtpilot.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.wrtpilot.app.MainActivity
import io.wrtpilot.app.R

/** Notification channels and builders (new devices, data quota). */
object Notifications {
    const val CHANNEL_NEW_DEVICE = "new_devices"
    const val CHANNEL_QUOTA = "data_quota"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_DEVICE,
                context.getString(R.string.channel_new_devices),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_new_devices_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUOTA,
                context.getString(R.string.channel_quota),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_quota_desc) }
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openDevice(context: Context, routerId: Long, mac: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTER_ID, routerId)
            if (mac != null) putExtra(MainActivity.EXTRA_MAC, mac)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notifyNewDevice(context: Context, routerId: Long, routerName: String, eventId: Long, mac: String, label: String) {
        if (!canNotify(context)) return
        val id = notificationId(routerId, eventId)
        val text = context.getString(R.string.notif_new_device_text, label, routerName)
        val n = NotificationCompat.Builder(context, CHANNEL_NEW_DEVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_new_device_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openDevice(context, routerId, mac, id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup("new_devices_$routerId")
            .build()
        post(context, id, n)
    }

    fun notifyQuota(context: Context, routerId: Long, eventId: Long, mac: String, label: String, used: String, limit: String, blocked: Boolean) {
        if (!canNotify(context)) return
        val id = notificationId(routerId, eventId)
        val text = context.getString(
            if (blocked) R.string.notif_quota_text_blocked else R.string.notif_quota_text,
            label, used, limit,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_QUOTA)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_quota_title, label))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openDevice(context, routerId, mac, id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        post(context, id, n)
    }

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // permission revoked between the check and the call
        }
    }

    private fun notificationId(routerId: Long, eventId: Long): Int = ((routerId shl 20) xor eventId).toInt()
}
