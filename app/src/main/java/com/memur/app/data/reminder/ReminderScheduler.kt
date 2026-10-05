package com.memur.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import com.memur.app.data.ReminderEntity
import java.util.Calendar
import java.util.concurrent.TimeUnit

object ReminderScheduler {

    const val ACTION_FIRE = "com.memur.app.ACTION_FIRE_REMINDER"
    const val EXTRA_ID = "reminder_id"

    fun schedule(context: Context, reminder: ReminderEntity): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, reminder.id)
        val exactAllowed = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        return try {
            val t = if (reminder.triggerAt > System.currentTimeMillis()) reminder.triggerAt
            else computeNext(reminder, System.currentTimeMillis()) ?: return false
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            }
            exactAllowed
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
                false
            } catch (_: Exception) {
                false
            }
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, reminderId))
    }

    /** 稍后提醒：N 分钟后重新触发闹钟（仅一次性，不重算周期） */
    fun snooze(context: Context, reminderId: Long, minutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, reminderId)
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        try {
            val exactAllowed = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {}
    }

    private fun pendingIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // ---------- 触发时间计算 ----------

    private val dowMap = mapOf(
        Calendar.MONDAY to 1, Calendar.TUESDAY to 2, Calendar.WEDNESDAY to 3,
        Calendar.THURSDAY to 4, Calendar.FRIDAY to 5, Calendar.SATURDAY to 6, Calendar.SUNDAY to 7
    )
    val dowName = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")
    private val dowByCn = mapOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7, "天" to 7)

    /** 从中文字符串解析星期（一~日/天），失败返回 null */
    fun parseDayOfWeek(cn: String): Int? = dowByCn[cn.trim()]

    /** 计算下一次触发时间；once 且已过期返回 null */
    fun computeNext(r: ReminderEntity, from: Long): Long? = when (r.type) {
        "once" -> if (r.triggerAt > from) r.triggerAt else null
        "daily" -> nextAt(from) { c -> c.set(Calendar.HOUR_OF_DAY, r.hour); c.set(Calendar.MINUTE, r.minute); c.set(Calendar.SECOND, 0) }
        "weekly" -> {
            val target = r.dayOfWeek ?: 1
            nextAt(from) { c ->
                while (dowMap[c.get(Calendar.DAY_OF_WEEK)] != target) c.add(Calendar.DAY_OF_YEAR, 1)
                c.set(Calendar.HOUR_OF_DAY, r.hour); c.set(Calendar.MINUTE, r.minute); c.set(Calendar.SECOND, 0)
                if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 7)
            }
        }
        "monthly" -> nextAt(from) { c ->
            val dom = (r.dayOfMonth ?: 1).coerceIn(1, 31)
            c.set(Calendar.DAY_OF_MONTH, dom.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            c.set(Calendar.HOUR_OF_DAY, r.hour); c.set(Calendar.MINUTE, r.minute); c.set(Calendar.SECOND, 0)
            if (c.timeInMillis <= from) {
                c.add(Calendar.MONTH, 1)
                c.set(Calendar.DAY_OF_MONTH, dom.coerceAtMost(c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
        }
        else -> null
    }

    private inline fun nextAt(from: Long, adjust: (Calendar) -> Unit): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = from
        adjust(c)
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1).also { adjust(c) }
        return c.timeInMillis
    }

    fun describe(r: ReminderEntity): String = when (r.type) {
        "once" -> formatTime(r.triggerAt)
        "daily" -> "每天 %02d:%02d".format(r.hour, r.minute)
        "weekly" -> "每周${dowName[r.dayOfWeek ?: 1]} %02d:%02d".format(r.hour, r.minute)
        "monthly" -> "每月${r.dayOfMonth ?: 1}日 %02d:%02d".format(r.hour, r.minute)
        else -> ""
    }

    fun formatTime(t: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))

    /** 距离下次触发的可读描述 */
    fun relative(t: Long): String {
        val diff = t - System.currentTimeMillis()
        if (diff <= 0) return "已到期"
        val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
        return when {
            mins < 60 -> "${mins}分钟后"
            mins < 60 * 24 -> "${mins / 60}小时后"
            else -> "${mins / 60 / 24}天后"
        }
    }

    // ---------- 系统日历 ----------

    /**
     * 直接写入系统日历（ContentResolver），返回日历事件 ID 或 null。
     * 不跳出 App、不需要用户手动确认。
     * 日历提醒（HAS_ALARM=1）会由系统日历 App 在到点时触发通知。
     */
    fun addToCalendarPro(context: Context, r: ReminderEntity): Long? {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, r.triggerAt)
                put(CalendarContract.Events.DTEND, r.triggerAt + 30 * 60 * 1000)
                put(CalendarContract.Events.TITLE, "⏰ ${r.title}")
                put(CalendarContract.Events.DESCRIPTION, r.message)
                put(CalendarContract.Events.CALENDAR_ID, getDefaultCalendarId(context))
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1) // 让日历 App 自动加提醒
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.let { ContentUris.parseId(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** 获取系统默认日历 ID */
    private fun getDefaultCalendarId(context: Context): Long {
        return try {
            val uri = CalendarContract.Calendars.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            )
            val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= 500"
            val cursor = context.contentResolver.query(uri, projection, selection, null, null)
            cursor?.use {
                if (it.moveToFirst()) it.getLong(0) else 1L
            } ?: 1L
        } catch (_: Exception) {
            1L
        }
    }

    /** 删除日历事件 */
    fun deleteFromCalendar(context: Context, calendarEventId: Long) {
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, calendarEventId)
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {}
    }
}
