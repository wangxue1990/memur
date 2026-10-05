package com.memur.app.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memur.app.MemurApp
import kotlinx.coroutines.launch
import java.io.File

/** 单条笔记详情：清洗后文本 + 原始转写 + 回听录音 + 编辑/收藏/删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(container: MemurApp.Container, nav: NavHostController, noteId: Long) {
    val note by container.repo.noteFlow(noteId).collectAsStateWithLifecycle(initialValue = null)
    val topics by container.repo.topicsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var showEdit by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (_: Exception) {}
        }
    }

    val n = note
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(n?.title ?: "笔记", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        n?.let { scope.launch { container.repo.setImportant(it, !it.important) } }
                    }) {
                        Icon(
                            if (n?.important == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "收藏"
                        )
                    }
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            )
        }
    ) { padding ->
        if (n == null) {
            Column(Modifier.padding(padding)) { }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(n.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.full(n.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val topicTitle = topics.firstOrNull { it.id == n.topicId }?.title
                if (topicTitle != null) {
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text("📁 $topicTitle", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                n.tagList().forEach { tag ->
                    Spacer(Modifier.width(6.dp))
                    Text("#$tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(n.cleanedText, style = MaterialTheme.typography.bodyLarge)

            Spacer(Modifier.height(18.dp))
            // 原始转写
            TextButton(onClick = { showRaw = !showRaw }) {
                Text(if (showRaw) "收起原始转写" else "展开原始转写（AI 处理前）")
            }
            if (showRaw) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        n.rawText,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 回听录音
            val audioExists = n.audioPath != null && File(n.audioPath).exists()
            if (audioExists) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        try {
                            if (playing) {
                                player.stop(); player.reset(); playing = false
                            } else {
                                player.reset()
                                player.setDataSource(n.audioPath)
                                player.prepare()
                                player.setOnCompletionListener { playing = false }
                                player.start()
                                playing = true
                            }
                        } catch (_: Exception) { playing = false }
                    }) {
                        Icon(
                            if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = "回听",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(if (playing) "正在回听原始录音…" else "回听原始录音", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    // 编辑
    if (showEdit && n != null) {
        NoteEditDialog(
            initialTitle = n.title,
            initialBody = n.cleanedText,
            initialTopic = topics.firstOrNull { it.id == n.topicId }?.title ?: "",
            initialTags = n.tagList().joinToString("，"),
            onDismiss = { showEdit = false },
            onSave = { title, body, topic, tags ->
                scope.launch {
                    container.repo.updateNote(n, title, body, tags, topic)
                    showEdit = false
                }
            }
        )
    }

    if (showDelete && n != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这条碎碎念？") },
            text = { Text("「${n.title}」将被永久删除（含音频）。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.repo.deleteNote(n)
                        showDelete = false
                        nav.popBackStack()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
}

/** 笔记编辑对话框（改标题/正文/主题/标签） */
@Composable
fun NoteEditDialog(
    initialTitle: String,
    initialBody: String,
    initialTopic: String,
    initialTags: String,
    onDismiss: () -> Unit,
    onSave: (title: String, body: String, topic: String, tags: List<String>) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf(initialBody) }
    var topic by remember { mutableStateOf(initialTopic) }
    var tags by remember { mutableStateOf(initialTags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑笔记") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("正文") }, minLines = 3)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("主题") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("标签（，分隔）") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    title.trim(),
                    body.trim(),
                    topic.trim(),
                    tags.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
