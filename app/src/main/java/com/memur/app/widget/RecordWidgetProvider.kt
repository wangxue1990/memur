package com.memur.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.memur.app.MainActivity
import com.memur.app.R
import com.memur.app.data.RecState
import com.memur.app.data.RecordingService

/**
 * 桌面/锁屏小组件：一键开始或停止录音。
 * 点击直接发服务指令（若系统限制后台启动前台服务，则回退拉起主界面自动开始录音）。
 */
class RecordWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.memur.app.widget.TOGGLE"
        const val EXTRA_FROM_WIDGET = "from_widget"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_record).apply {
            setOnClickPendingIntent(R.id.widget_root, togglePendingIntent(context))
        }
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            if (RecordingService.state.value is RecState.Recording) {
                RecordingService.stop(context)
            } else {
                try {
                    RecordingService.start(context)
                } catch (_: Exception) {
                    // 受限时拉起主界面并自动开始
                    val i = Intent(context, MainActivity::class.java).apply {
                        putExtra(EXTRA_FROM_WIDGET, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(i)
                }
            }
            // 刷新小组件外观
            val mgr = AppWidgetManager.getInstance(context)
            onUpdate(context, mgr, mgr.getAppWidgetIds(ComponentName(context, RecordWidgetProvider::class.java)))
        }
    }

    private fun togglePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RecordWidgetProvider::class.java).apply { action = ACTION_WIDGET_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
