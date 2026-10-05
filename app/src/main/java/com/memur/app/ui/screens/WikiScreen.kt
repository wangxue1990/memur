package com.memur.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MergeType
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
import com.memur.app.data.NoteEntity
import com.memur.app.data.TopicEntity
import kotlinx.coroutines.launch

/** 知识库：AI 自动生成的主题 Wiki 页面列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiScreen(container: MemurApp.Container, nav: NavHostController) {
    val topics by container.repo.topicsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("知识库 · 类Wiki") }) }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Text(
                    "AI 自动把相关碎念聚合成主题页面",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(topics, key = { it.id }) { topic ->
                TopicCard(topic) { nav.navigate("topic/${topic.id}") }
            }
            if (topics.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.AutoStories, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("多说几条，AI 就会自动生成主题页", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    // 主题操作（重命名/合并/删除）放在详情页
}

@Composable
private fun TopicCard(topic: TopicEntity, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📁", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(6.dp))
            Text(
                topic.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "更新于 ${Fmt.short(topic.updatedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 主题详情：该主题下所有碎念条目 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicDetailScreen(container: MemurApp.Container, nav: NavHostController, topicId: Long) {
    val topics by container.repo.topicsFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val topic = topics.firstOrNull { it.id == topicId }
    val notes by container.repo.notesOfTopic(topicId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topic?.title ?: "主题") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showRename = true }) {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "重命名")
                    }
                    IconButton(onClick = { showMerge = true }) {
                        Icon(Icons.Filled.MergeType, contentDescription = "合并")
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
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
            items(notes, key = { it.id }) { note ->
                NoteCard(note) { nav.navigate("note/${note.id}") }
            }
            if (notes.isEmpty()) {
                item {
                    Text(
                        "该主题下暂无条目",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }
        }
    }

    if (showRename && topic != null) {
        var name by remember { mutableStateOf(topic.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名主题") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.repo.renameTopic(topic, name)
                        showRename = false
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } }
        )
    }

    if (showMerge && topic != null) {
        val others = topics.filter { it.id != topicId }
        AlertDialog(
            onDismissRequest = { showMerge = false },
            title = { Text("把「${topic.title}」合并到…") },
            text = {
                if (others.isEmpty()) Text("没有其它主题可合并")
                else Column {
                    others.forEach { o ->
                        TextButton(onClick = {
                            scope.launch {
                                container.repo.mergeTopic(topic, o)
                                showMerge = false
                            }
                        }) { Text("→ ${o.title}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMerge = false }) { Text("取消") } }
        )
    }

    if (showDelete && topic != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除主题「${topic.title}」？") },
            text = { Text("主题下的笔记不会删除，只是变为未分类。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.repo.deleteTopic(topic)
                        showDelete = false
                        nav.popBackStack()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }
}

/** 笔记卡片（列表通用组件） */
@Composable
fun NoteCard(note: NoteEntity, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.important) {
                    Text("⭐", style = MaterialTheme.typography.labelMedium)
                }
                if (note.mode == "reminder") {
                    Text(" ⏰", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                note.cleanedText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Fmt.short(note.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                note.tagList().take(3).forEach { tag ->
                    Text(
                        "#$tag",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
        }
    }
}
