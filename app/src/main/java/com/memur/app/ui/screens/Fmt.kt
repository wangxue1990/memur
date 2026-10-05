package com.memur.app.ui.screens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Fmt {
    private val ymd = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    private val md = SimpleDateFormat("M月d日", Locale.CHINA)

    /** 聊天流日期分隔：今天 / 昨天 / M月d日 */
    fun dayLabel(t: Long): String {
        val now = System.currentTimeMillis()
        return when (ymd.format(Date(t))) {
            ymd.format(Date(now)) -> "今天"
            ymd.format(Date(now - 86_400_000L)) -> "昨天"
            else -> md.format(Date(t))
        }
    }

    fun sameDay(a: Long, b: Long): Boolean = ymd.format(Date(a)) == ymd.format(Date(b))

    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val short = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val day = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)

    fun full(t: Long) = full.format(Date(t))
    fun short(t: Long) = short.format(Date(t))
    fun day(t: Long) = day.format(Date(t))

    fun duration(ms: Long): String {
        val s = TimeUnit.MILLISECONDS.toSeconds(ms)
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
