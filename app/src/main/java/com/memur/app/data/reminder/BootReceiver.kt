package com.memur.app.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.memur.app.MemurApp
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as MemurApp
        runBlocking {
            for (r in app.container.repo.reminderDao.allEnabled()) {
                val next = ReminderScheduler.computeNext(r, System.currentTimeMillis())
                if (next != null) {
                    val updated = if (next != r.triggerAt) r.copy(triggerAt = next) else r
                    app.container.repo.reminderDao.update(updated)
                    ReminderScheduler.schedule(context, updated)
                } else {
                    app.container.repo.reminderDao.setEnabled(r.id, false)
                }
            }
        }
    }
}
