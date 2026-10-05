package com.memur.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.memur.app.MemurApp
import kotlinx.coroutines.runBlocking

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1)
        if (id < 0) return
        val app = context.applicationContext as MemurApp
        val container = app.container

        runBlocking {
            val reminder = container.repo.reminderDao.byId(id) ?: return@runBlocking
            if (!reminder.enabled) return@runBlocking

            val note = reminder.noteId?.let { container.repo.noteDao.byId(it) }
            Notifier.ensureChannels(context)
            if (Notifier.canNotify(context)) {
                Notifier.showReminder(
                    context,
                    reminder.id,
                    reminder.title,
                    reminder.message,
                    note
                )
            }

            // 启动全屏响铃 Activity（锁屏也能亮屏弹出）
            val ringIntent = Intent(context, FullScreenReminderActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(FullScreenReminderActivity.EXTRA_ID, reminder.id)
                putExtra(FullScreenReminderActivity.EXTRA_TITLE, reminder.title)
                putExtra(FullScreenReminderActivity.EXTRA_MESSAGE, reminder.message)
            }
            try {
                context.startActivity(ringIntent)
            } catch (_: Exception) {
                // 某些场景（如后台限制）可能无法启动 Activity，通知已发作为兜底
            }

            // 周期性提醒：算下次时间续排
            if (reminder.isRepeat) {
                val next = ReminderScheduler.computeNext(reminder, System.currentTimeMillis())
                if (next != null) {
                    val updated = reminder.copy(triggerAt = next)
                    container.repo.reminderDao.update(updated)
                    ReminderScheduler.schedule(context, updated)
                } else {
                    container.repo.reminderDao.setEnabled(reminder.id, false)
                }
            } else {
                container.repo.reminderDao.setEnabled(reminder.id, false)
            }
        }
    }
}
