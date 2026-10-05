package com.memur.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memur.app.MemurApp
import com.memur.app.agent.AgentStatus
import com.memur.app.agent.MessageKind
import com.memur.app.data.MessageEntity
import com.memur.app.data.RecState
import com.memur.app.data.RecordingService
import com.memur.app.data.TtsController
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * 首页即对话：与智能体的聊天流。
 * 处理状态（识别中/思考中/检索中/等待回答）内联显示在输入区上方，不弹窗不抽屉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: MemurApp.Container, nav: NavHostController) {
    val context = LocalContext.current
    val messages by container.agent.transcript.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by container.agent.status.collectAsStateWithLifecycle()
    val recState by RecordingService.state.collectAsStateWithLifecycle()
    val speaking by TtsController.speaking.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 「先上屏确认」模式下，语音转写结果经 draft 通道填入输入框（页面切走再回来也不丢）
    LaunchedEffect(Unit) {
        container.draft.collect { d ->
            if (!d.isNullOrBlank()) {
                input = d
                container.draft.value = null
            }
        }
    }

    // 新消息自动滚到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            RecordingService.start(context)
        }
    }

    fun toggleRecord() {
        when (recState) {
            is RecState.Recording -> RecordingService.stop(context)
            else -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    RecordingService.start(context)
                } else {
                    permLauncher.launch(
                        if (android.os.Build.VERSION.SDK_INT >= 33)
                            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                        else arrayOf(Manifest.permission.RECORD_AUDIO)
                    )
                }
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        container.agent.submit(text)
    }

    // 清除对话确认（清除动作不可逆，需二次确认；笔记/提醒不受影响）
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除对话") },
            text = { Text("将清空聊天记录和 AI 的短期对话记忆，\n笔记、主题、提醒均不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    container.agent.clearConversation()
                    confirmClear = false
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("碎碎念", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Memur · 随心口述，AI 帮你管好碎想法",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("🧹 清除对话") },
                            onClick = { menuOpen = false; confirmClear = true }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (messages.isEmpty()) {
                    item { EmptyHint(onPick = { input = it }) }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                    val showDivider = index == 0 ||
                        !Fmt.sameDay(message.createdAt, messages[index - 1].createdAt)
                    Column {
                        if (showDivider) DayDivider(Fmt.dayLabel(message.createdAt))
                        MessageItem(message) { container.agent.answer(it) }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // 内联状态区（平滑显隐，不弹窗）
            StatusBar(status = status, recState = recState, onCancel = { container.agent.cancel() })

            // 朗读中状态条：随时可停止（同样内联，不弹窗）
            AnimatedVisibility(visible = speaking, enter = expandVertically(), exit = shrinkVertically()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🔊 正在朗读回答…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { TtsController.stop() }) {
                        Text("停止朗读", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // 输入区：文本框（内嵌发送）+ 录音
            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = { send() },
                recState = recState,
                onToggleRecord = { toggleRecord() }
            )
        }
    }
}

// ---------- 消息渲染 ----------

@Composable
private fun MessageItem(message: MessageEntity, onAnswer: (String) -> Unit) {
    when (message.kind) {
        MessageKind.USER -> Bubble(right = true) {
            BubbleText(message.text)
            BubbleTime(message.createdAt, right = true)
        }
        MessageKind.AGENT -> Bubble(right = false) {
            BubbleText(message.text)
            BubbleTime(message.createdAt, right = false)
        }
        MessageKind.ANSWER -> Bubble(right = true) {
            Text(
                "回答", style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.6f)
            )
            BubbleText(message.text)
        }
        MessageKind.INFO -> InfoCard(message)
        MessageKind.ASK -> AskCard(message, onAnswer)
        MessageKind.ERROR -> Bubble(right = false, error = true) {
            BubbleText("⚠️ ${message.text}")
        }
        else -> Bubble(right = false) { BubbleText(message.text) }
    }
}

@Composable
private fun Bubble(right: Boolean, error: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (right) 18.dp else 5.dp,
                bottomEnd = if (right) 5.dp else 18.dp
            ),
            color = when {
                error -> MaterialTheme.colorScheme.errorContainer
                right -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
            contentColor = when {
                error -> MaterialTheme.colorScheme.onErrorContainer
                right -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
        }
    }
}

@Composable
private fun BubbleText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun BubbleTime(time: Long, right: Boolean) {
    Spacer(Modifier.height(2.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (right) Arrangement.End else Arrangement.Start
    ) {
        Text(
            Fmt.short(time),
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = 0.5f)
        )
    }
}

/** 日期分隔条：今天 / 昨天 / M月d日 */
@Composable
private fun DayDivider(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Text(
            "  $label  ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

/** show_info_to_user 的信息卡片（全宽） */
@Composable
private fun InfoCard(message: MessageEntity) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("📄 ${message.text}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(8.dp))
            Text(message.payload, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                Fmt.short(message.createdAt), style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.5f)
            )
        }
    }
}

/** ask_user 的问题卡片，带可选的快捷回答按钮 */
@Composable
private fun AskCard(message: MessageEntity, onAnswer: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("❓ ${message.text}", style = MaterialTheme.typography.titleMedium)
            val options = remember(message.id) { parseOptions(message.payload) }
            if (options.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                options.forEach { option ->
                    AssistChip(
                        onClick = { onAnswer(option) },
                        label = { Text(option) },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private fun parseOptions(payload: String): List<String> = try {
    val arr = JSONArray(payload)
    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
} catch (_: Exception) {
    emptyList()
}

// ---------- 状态与输入 ----------

/** 内联状态：录音中 / 智能体工作中 / 等待回答，均可取消；平滑展开收起 */
@Composable
private fun StatusBar(status: AgentStatus, recState: RecState, onCancel: () -> Unit) {
    val recording = recState is RecState.Recording
    val waitingUser = status is AgentStatus.WaitingUser
    val working = status is AgentStatus.Working
    val visible = recording || waitingUser || working

    // 记住最近一次工作文案：AnimatedVisibility 收起动画期间（约 200ms）status 可能已回 Idle，
    // 此时内容仍会重组，不能强转 Working（v2.0.3 回答结束瞬间闪退的根因）
    var lastActivity by remember { mutableStateOf("处理中") }
    if (status is AgentStatus.Working) lastActivity = status.activity

    // 录音计时每秒自动跳动（修复此前只在重组时刷新的问题）
    val startedAt = (recState as? RecState.Recording)?.startedAt
    val tick by produceState(0L, startedAt) {
        if (startedAt == null) {
            value = 0L
        } else {
            while (true) {
                value = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                recording -> {
                    Text(
                        "● 录音中 ${Fmt.duration((tick - (startedAt ?: 0L)).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
                waitingUser -> {
                    Text(
                        "❓ 在等你回答（也可直接语音回答）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "  🔄 $lastActivity…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            TextButton(onClick = onCancel) {
                Text("取消", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    recState: RecState,
    onToggleRecord: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("说话或打字…") },
            modifier = Modifier.weight(1f),
            maxLines = 3,
            shape = RoundedCornerShape(26.dp),
            trailingIcon = {
                IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (value.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        )
        RecordButton(recState = recState, onToggle = onToggleRecord)
    }
}

@Composable
private fun RecordButton(recState: RecState, onToggle: () -> Unit) {
    val recording = recState is RecState.Recording
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(700)),
        label = "pulseScale"
    )
    val scale = if (recording) animateFloatAsState(pulse, label = "btnScale").value else 1f

    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 8.dp, bottom = 3.dp)
            .size(56.dp)
            .scale(scale)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (recording) "停止" else "录音",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/** 空状态引导 + 快捷说法示例（点击填入输入框） */
@Composable
private fun EmptyHint(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(88.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("🎙️", fontSize = 38.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "随心口述，AI 帮你管好所有碎想法",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "点下方麦克风开始碎碎念，也可以打字。\n记笔记、设提醒、查记忆，说句话就行。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(26.dp))
        val tips = listOf(
            "记一下：周末要买无谷猫粮",
            "每周五晚上七点提醒我运动",
            "我之前关于养猫都说过什么？",
            "这周有哪些提醒？"
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            tips.forEach { tip ->
                SuggestionChip(
                    onClick = { onPick(tip) },
                    label = { Text(tip, style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.padding(vertical = 3.dp)
                )
            }
        }
    }
}
