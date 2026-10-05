package com.memur.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memur.app.MemurApp
import com.memur.app.data.ReminderEntity
import com.memur.app.data.reminder.ReminderScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 提醒页：支持按类别 / 按日期 / 不分组 三种查看方式 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(container: MemurApp.Container, nav: NavHostController) {
    val reminders by container.repo.remindersFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ReminderEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<ReminderEntity?>(null) }
    var viewMode by remember { mutableStateOf("category") } // category / date / flat

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提醒") },
                actions = {
                    // 视图切换
                    FilterChip(
                        selected = viewMode == "category",
                        onClick = { viewMode = "category" },
                        label = { Text("按类别") },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    FilterChip(
                        selected = viewMode == "date",
                        onClick = { viewMode = "date" },
                        label = { Text("按日期") },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    FilterChip(
                        selected = viewMode == "flat",
                        onClick = { viewMode = "flat" },
                        label = { Text("全部") }
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(
                    "用说的就能建：${"\""}每周五晚上七点提醒我运动${"\""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (reminders.isEmpty()) {
                item {
                    Text(
                        "还没有提醒。点首页录音，说一句${"\""}明天下午三点提醒我取快递${"\""}试试。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                when (viewMode) {
                    "category" -> {
                        val grouped = reminders.groupBy { it.category }
                        grouped.forEach { (category, items) ->
                            item {
                                GroupHeader("📁 $category（${items.size}）")
                            }
                            items(items, key = { it.id }) { r ->
                                ReminderCard(r,
                                    onToggle = { on ->
                                        scope.launch {
                                            container.repo.reminderDao.setEnabled(r.id, on)
                                            if (on) {
                                                val fresh = container.repo.reminderDao.byId(r.id)
                                                if (fresh != null) ReminderScheduler.schedule(container.appContext, fresh)
                                            } else {
                                                ReminderScheduler.cancel(container.appContext, r.id)
                                            }
                                        }
                                    },
                                    onDelete = { pendingDelete = r },
                                    onEdit = { editing = r },
                                    onOpenNote = { r.noteId?.let { nav.navigate("note/$it") } }
                                )
                            }
                        }
                    }
                    "date" -> {
                        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                        val todayStr = dateFmt.format(Date())
                        val grouped = reminders.groupBy { r ->
                            val d = dateFmt.format(Date(r.triggerAt))
                            when (d) {
                                todayStr -> "今天"
                                dateFmt.format(Date(System.currentTimeMillis() + 86400000)) -> "明天"
                                dateFmt.format(Date(System.currentTimeMillis() + 2 * 86400000)) -> "后天"
                                else -> d
                            }
                        }
                        grouped.forEach { (dateLabel, items) ->
                            item {
                                GroupHeader("📅 $dateLabel（${items.size}）")
                            }
                            items(items, key = { it.id }) { r ->
                                ReminderCard(r,
                                    onToggle = { on ->
                                        scope.launch {
                                            container.repo.reminderDao.setEnabled(r.id, on)
                                            if (on) {
                                                val fresh = container.repo.reminderDao.byId(r.id)
                                                if (fresh != null) ReminderScheduler.schedule(container.appContext, fresh)
                                            } else {
                                                ReminderScheduler.cancel(container.appContext, r.id)
                                            }
                                        }
                                    },
                                    onDelete = { pendingDelete = r },
                                    onEdit = { editing = r },
                                    onOpenNote = { r.noteId?.let { nav.navigate("note/$it") } }
                                )
                            }
                        }
                    }
                    else -> {
                        items(reminders, key = { it.id }) { r ->
                            ReminderCard(r,
                                onToggle = { on ->
                                    scope.launch {
                                        container.repo.reminderDao.setEnabled(r.id, on)
                                        if (on) {
                                            val fresh = container.repo.reminderDao.byId(r.id)
                                            if (fresh != null) ReminderScheduler.schedule(container.appContext, fresh)
                                        } else {
                                            ReminderScheduler.cancel(container.appContext, r.id)
                                        }
                                    }
                                },
                                onDelete = { pendingDelete = r },
                                onEdit = { editing = r },
                                onOpenNote = { r.noteId?.let { nav.navigate("note/$it") } }
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除提醒") },
            text = { Text("确认删除提醒「${r.title}」？\n删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        ReminderScheduler.cancel(container.appContext, r.id)
                        container.repo.reminderDao.delete(r)
                    }
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    // 编辑弹窗
    editing?.let { r ->
        ReminderEditDialog(
            initial = r,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch {
                    val next = ReminderScheduler.computeNext(updated, System.currentTimeMillis())
                    val final = if (next != null) updated.copy(triggerAt = next) else updated
                    container.repo.reminderDao.update(final)
                    if (final.enabled) ReminderScheduler.schedule(container.appContext, final)
                    editing = null
                }
            }
        )
    }
}

@Composable
private fun GroupHeader(text: String) {
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ReminderCard(
    r: ReminderEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onOpenNote: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (r.enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⏰ ${r.title}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenNote() }
                )
                Switch(checked = r.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ReminderScheduler.describe(r),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                if (r.enabled) {
                    Text(
                        "下次：${ReminderScheduler.formatTime(r.triggerAt)}（${ReminderScheduler.relative(r.triggerAt)}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("已停用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                // 分类标签
                Text(
                    r.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑", Modifier.padding(0.dp)) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", Modifier.padding(0.dp)) }
            }
        }
    }
}

@Composable
fun ReminderEditDialog(
    initial: ReminderEntity,
    onDismiss: () -> Unit,
    onSave: (ReminderEntity) -> Unit
) {
    var type by remember { mutableStateOf(initial.type) }
    var text by remember { mutableStateOf(initial.title) }
    var datetime by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(initial.triggerAt)))
    }
    var time by remember { mutableStateOf("%02d:%02d".format(initial.hour, initial.minute)) }
    var dow by remember { mutableStateOf(ReminderScheduler.dowName[initial.dayOfWeek ?: 1] ?: "一") }
    var dom by remember { mutableStateOf((initial.dayOfMonth ?: 1).toString()) }
    var category by remember { mutableStateOf(initial.category) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑提醒") },
        text = {
            Column {
                Row {
                    listOf("once" to "一次", "daily" to "每天", "weekly" to "每周", "monthly" to "每月").forEach { (t, label) ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("提醒内容") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("分类") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                when (type) {
                    "once" -> OutlinedTextField(value = datetime, onValueChange = { datetime = it }, label = { Text("时间 yyyy-MM-dd HH:mm") }, singleLine = true)
                    "weekly" -> {
                        Row {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEach { d ->
                                FilterChip(
                                    selected = dow == d,
                                    onClick = { dow = d },
                                    label = { Text(d) },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间 HH:mm") }, singleLine = true)
                    }
                    "monthly" -> {
                        OutlinedTextField(value = dom, onValueChange = { dom = it }, label = { Text("每月几号") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间 HH:mm") }, singleLine = true)
                    }
                    else -> OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("时间 HH:mm") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hm = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim())
                val updated = when (type) {
                    "once" -> {
                        val d = try {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).parse(datetime.trim())
                        } catch (_: Exception) { null }
                        if (d == null || d.time <= System.currentTimeMillis()) return@TextButton
                        initial.copy(type = "once", title = text, message = text, triggerAt = d.time, category = category)
                    }
                    "weekly" -> {
                        if (hm == null) return@TextButton
                        initial.copy(
                            type = "weekly", title = text, message = text, category = category,
                            hour = hm.groupValues[1].toInt(), minute = hm.groupValues[2].toInt(),
                            dayOfWeek = ReminderScheduler.parseDayOfWeek(dow) ?: 1
                        )
                    }
                    "monthly" -> {
                        if (hm == null) return@TextButton
                        initial.copy(
                            type = "monthly", title = text, message = text, category = category,
                            hour = hm.groupValues[1].toInt(), minute = hm.groupValues[2].toInt(),
                            dayOfMonth = dom.toIntOrNull()?.coerceIn(1, 31) ?: 1
                        )
                    }
                    else -> {
                        if (hm == null) return@TextButton
                        initial.copy(type = "daily", title = text, message = text, category = category, hour = hm.groupValues[1].toInt(), minute = hm.groupValues[2].toInt())
                    }
                }
                onSave(updated)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
