package com.memur.app.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.memur.app.MainActivity
import com.memur.app.R
import com.memur.app.data.NoteEntity

object Notifier {

    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_REMINDERS = "reminders"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RECORDING, context.getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "录音进行中的常驻通知"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "碎碎念提醒"
                    enableVibration(true)
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** 提醒触发通知；点击跳转原始笔记（如有） */
    fun showReminder(context: Context, reminderId: Long, title: String, text: String, note: NoteEntity?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val deepLink = if (note != null) "memur://note/${note.id}" else "memur://reminders"
        val contentIntent = PendingIntent.getActivity(
            context,
            reminderId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(deepLink)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle("⏰ $title")
            .setContentText(if (note != null) "${text}\n—— 来自你的碎碎念" else text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (note != null) "${text}\n—— 来自你的碎碎念：\n${note.cleanedText.take(200)}" else text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(reminderId.toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}
