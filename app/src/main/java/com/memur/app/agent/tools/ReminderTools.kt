package com.memur.app.agent.tools

import android.content.Context
import com.memur.app.agent.Tool
import com.memur.app.agent.ToolArgs
import com.memur.app.agent.parameters
import com.memur.app.data.ReminderEntity
import com.memur.app.data.Repository
import com.memur.app.data.SettingsStore
import com.memur.app.data.reminder.ReminderScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 提醒工具：创建、查询、删除、暂停/恢复 */

class CreateReminderTool(
    private val appContext: Context,
    private val repo: Repository,
    private val settings: SettingsStore
) : Tool {
    override val name = "create_reminder"
    override val description =
        "创建提醒。type=once 需给 datetime（yyyy-MM-dd HH:mm）；daily/weekly/monthly 需给 time（HH:mm），weekly 另需 day_of_week（中文一~日），monthly 另需 day_of_month（1-31）。mode 可选：alarm（App 内闹钟响铃，适合 ≤24h 的短期提醒，默认）或 calendar（写入系统日历日程，适合 >24h 的长期事件）。不传 mode 时按时间跨度自动判断。category 可选：提醒分组类别，根据内容自动判断（如生活、工作、健康、理财、社交、学习等），不传则默认通用。"
    override val activity = "创建提醒"
    override val parameters = parameters {
        string("text", "提醒内容（简短一句话）", required = true)
        string("type", "重复类型", required = true, enum = listOf("once", "daily", "weekly", "monthly"))
        string("datetime", "once：具体时间，格式 yyyy-MM-dd HH:mm")
        string("time", "daily/weekly/monthly：触发时间，格式 HH:mm")
        string("day_of_week", "weekly：星期几（中文：一/二/三/四/五/六/日）")
        integer("day_of_month", "monthly：每月几号（1-31）")
        integer("note_id", "关联的来源笔记编号（可选，便于溯源）")
        string("mode", "提醒方式：alarm（闹钟响铃）或 calendar（系统日历），不传则自动判断")
        string("category", "提醒分组类别（如生活、工作、健康、理财、社交、学习），不传则默认通用")
    }

    override suspend fun execute(args: ToolArgs): String {
        val text = args.string("text")
        val entity = buildEntity(args, text)
            ?: return "提醒参数不完整或时间无效，请核对 datetime / time / day_of_week / day_of_month 与 type 是否匹配"
        val id = repo.reminderDao.insert(entity)
        val saved = entity.copy(id = id)
        if (saved.mode == "calendar") {
            // 长期日程：写入系统日历，由日历 App 在到点时提醒
            ReminderScheduler.addToCalendarPro(appContext, saved)
        } else {
            // 短期闹钟：走 AlarmManager 精确闹钟
            ReminderScheduler.schedule(appContext, saved)
        }
        val modeLabel = if (saved.mode == "calendar") "日历日程" else "闹钟响铃"
        return "已创建提醒 #$id「$text」，${ReminderScheduler.describe(saved)}（${ReminderScheduler.relative(saved.triggerAt)}触发，$modeLabel，分类：${saved.category}）"
    }

    private fun buildEntity(args: ToolArgs, text: String): ReminderEntity? {
        val noteId = args.int("note_id", 0).toLong().takeIf { it > 0 }
        val now = Calendar.getInstance()
        val explicitMode = args.string("mode").takeIf { it == "alarm" || it == "calendar" }
        val category = args.string("category").takeIf { it.isNotBlank() } ?: autoCategory(text)

        return when (args.string("type")) {
            "once" -> {
                val t = parseDateTime(args.string("datetime")) ?: return null
                if (t <= System.currentTimeMillis() - 60_000) return null
                // 自动判断 mode：≤24h 走闹钟，>24h 走日历
                val autoMode = if (t - System.currentTimeMillis() > 24 * 60 * 60 * 1000L) "calendar" else "alarm"
                ReminderEntity(
                    noteId = noteId, title = text, message = text,
                    type = "once", triggerAt = t,
                    hour = now.get(Calendar.HOUR_OF_DAY), minute = now.get(Calendar.MINUTE),
                    mode = explicitMode ?: autoMode, category = category
                )
            }
            "daily" -> {
                val (h, m) = parseHm(args.string("time")) ?: return null
                ReminderEntity(noteId = noteId, title = text, message = text, type = "daily", triggerAt = 0, hour = h, minute = m, mode = explicitMode ?: "alarm", category = category)
            }
            "weekly" -> {
                val (h, m) = parseHm(args.string("time")) ?: return null
                val dow = ReminderScheduler.parseDayOfWeek(args.string("day_of_week")) ?: return null
                ReminderEntity(noteId = noteId, title = text, message = text, type = "weekly", triggerAt = 0, hour = h, minute = m, dayOfWeek = dow, mode = explicitMode ?: "alarm", category = category)
            }
            "monthly" -> {
                val (h, m) = parseHm(args.string("time")) ?: return null
                val dom = args.int("day_of_month", 0)
                if (dom !in 1..31) return null
                ReminderEntity(noteId = noteId, title = text, message = text, type = "monthly", triggerAt = 0, hour = h, minute = m, dayOfMonth = dom, mode = explicitMode ?: "calendar", category = category)
            }
            else -> null
        }?.let { probe ->
            if (probe.type == "once") probe
            else probe.copy(triggerAt = ReminderScheduler.computeNext(probe, System.currentTimeMillis()) ?: return null)
        }
    }

    /**
     * 根据提醒内容关键词自动判断分组类别。
     * 匹配优先级：健康 > 理财 > 工作 > 社交 > 学习 > 生活 > 通用
     */
    private fun autoCategory(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("吃药", "运动", "锻炼", "喝水", "体检", "睡觉", "休息", "跑步", "散步", "健身").any { t.contains(it) } -> "健康"
            listOf("还款", "缴费", "工资", "理财", "报销", "账单", "信用卡", "保险", "缴费", "水电", "房租", "续保").any { t.contains(it) } -> "理财"
            listOf("开会", "汇报", "项目", "任务", "截止", "提交", "周报", "文档", "评审", "面试", "上班", "出差").any { t.contains(it) } -> "工作"
            listOf("聚会", "生日", "电话", "联系", "回访", "约", "聚餐", "拜访", "朋友").any { t.contains(it) } -> "社交"
            listOf("学习", "课程", "作业", "考试", "复习", "看书", "上课", "预习", "打卡").any { t.contains(it) } -> "学习"
            listOf("买菜", "取快递", "快递", "购物", "洗衣", "做饭", "打扫", "垃圾", "猫粮", "狗粮", "花", "浇花").any { t.contains(it) } -> "生活"
            else -> "通用"
        }
    }
}

class ListRemindersTool(private val repo: Repository) : Tool {
    override val name = "list_reminders"
    override val description = "列出全部提醒（编号、内容、重复规则、启用状态、下次触发时间）。"
    override val activity = "查看提醒"
    override val parameters = parameters { }

    override suspend fun execute(args: ToolArgs): String {
        val list = repo.reminderDao.all()
        if (list.isEmpty()) return "还没有任何提醒"
        return list.groupBy { it.category }.entries.joinToString("\n\n") { (cat, items) ->
            buildString {
                appendLine("【$cat】")
                items.forEach { r ->
                    val modeLabel = if (r.mode == "calendar") "日历" else "闹钟"
                    appendLine("  [#${r.id}] ${ReminderScheduler.describe(r)} ${r.title}" +
                        "（${if (r.enabled) "启用" else "暂停"}，${modeLabel}，${ReminderScheduler.relative(r.triggerAt)}）")
                }
            }
        }
    }
}

class DeleteReminderTool(private val appContext: Context, private val repo: Repository) : Tool {
    override val name = "delete_reminder"
    override val description = "删除一个提醒（不可恢复，删除前务必用 ask_user 向用户确认）。"
    override val activity = "删除提醒"
    override val parameters = parameters {
        integer("reminder_id", "提醒编号", required = true)
    }

    override suspend fun execute(args: ToolArgs): String {
        val id = args.int("reminder_id", -1).toLong()
        val r = repo.reminderDao.byId(id) ?: return "提醒不存在"
        ReminderScheduler.cancel(appContext, id)
        repo.reminderDao.delete(r)
        return "已删除提醒 #$id「${r.title}」"
    }
}

class SetReminderEnabledTool(private val appContext: Context, private val repo: Repository) : Tool {
    override val name = "set_reminder_enabled"
    override val description = "暂停或恢复一个提醒。"
    override val activity = "调整提醒"
    override val parameters = parameters {
        integer("reminder_id", "提醒编号", required = true)
        boolean("enabled", "true 恢复 / false 暂停", required = true)
    }

    override suspend fun execute(args: ToolArgs): String {
        val id = args.int("reminder_id", -1).toLong()
        val r = repo.reminderDao.byId(id) ?: return "提醒不存在"
        val enabled = args.bool("enabled")
        if (enabled) {
            val next = ReminderScheduler.computeNext(r, System.currentTimeMillis()) ?: return "该提醒已过期，无法恢复"
            val updated = r.copy(triggerAt = next, enabled = true)
            repo.reminderDao.update(updated)
            ReminderScheduler.schedule(appContext, updated)
        } else {
            repo.reminderDao.setEnabled(id, false)
            ReminderScheduler.cancel(appContext, id)
        }
        return if (enabled) "已恢复提醒 #$id" else "已暂停提醒 #$id"
    }
}

private val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

private fun parseDateTime(s: String): Long? = try {
    dateTimeFmt.parse(s.trim())?.time
} catch (_: Exception) {
    null
}

private fun parseHm(s: String): Pair<Int, Int>? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (h !in 0..23 || min !in 0..59) return null
    return h to min
}
