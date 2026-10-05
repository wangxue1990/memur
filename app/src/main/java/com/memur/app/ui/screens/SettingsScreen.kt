package com.memur.app.ui.screens

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memur.app.MemurApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: MemurApp.Container, nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = com.memur.app.data.SettingsStore.DEFAULT)

    var baseUrl by remember(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var apiKey by remember(settings.llmApiKey) { mutableStateOf(settings.llmApiKey) }
    var model by remember(settings.llmModel) { mutableStateOf(settings.llmModel) }
    var keyVisible by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    val cacheZip = File(context.cacheDir, "memur_export.zip")
                    val n = container.repo.markdownStore.exportZip(cacheZip)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        cacheZip.inputStream().use { it.copyTo(out) }
                    }
                    cacheZip.delete()
                    n
                }
                toast = "已导出 $count 条笔记"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
                        val dest = File(dir, "import_${System.currentTimeMillis()}.wav")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        } ?: return@withContext false
                        container.voiceIntake.submit(dest.absolutePath)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!ok) toast = "音频导入失败"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            toast?.let {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
            }

            SectionTitle("AI 模型（OpenAI 兼容接口，仅用于整理与总结）")
            if (settings.llmApiKey.isBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "⚠️ 尚未配置 API Key，AI 整理与查询暂不可用。\n填写任意 OpenAI 兼容服务的地址、Key 与模型名即可。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("接口地址（OpenAI 兼容 Base URL）") },
                placeholder = { Text("https://example.com/v1/") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (keyVisible) "隐藏" else "显示"
                        )
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("模型名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    container.settings.setLlmConfig(baseUrl, apiKey, model)
                    toast = if (apiKey.isBlank()) "已保存（API Key 为空，AI 功能暂不可用）" else "AI 配置已保存"
                }
            }) { Text("保存 AI 配置") }

            SectionTitle("录音与处理")
            Text(
                "语音识别（SenseVoice）在本地离线完成，音频不上传、不保存。\n转写完成后自动删除录音文件。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("语音转文字后", Modifier.weight(1f))
                FilterChip(
                    selected = settings.voiceSendMode != "confirm",
                    onClick = { scope.launch { container.settings.setVoiceSendMode("direct") } },
                    label = { Text("直接发送") }
                )
                Spacer(Modifier.padding(3.dp))
                FilterChip(
                    selected = settings.voiceSendMode == "confirm",
                    onClick = { scope.launch { container.settings.setVoiceSendMode("confirm") } },
                    label = { Text("先上屏确认") }
                )
            }
            Text(
                "「直接发送」：说完自动交给 AI 处理；\n" +
                    "「先上屏确认」：转写文字先填入输入框，可编辑后再点发送（后台录音同样会回到输入框等你确认）。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("语音播报")
            SettingSwitch("查询回答自动播报", "检索结果用本地语音读给你听", settings.autoSpeak) {
                scope.launch { container.settings.setAutoSpeak(it) }
            }
            Text(
                "使用手机系统自带的语音合成引擎（TextToSpeech）在本地完成，不调用任何外部 API、不联网。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("提醒")
            SettingSwitch("创建提醒时写入系统日历", "经系统日历应用确认后添加", settings.syncCalendar) {
                scope.launch { container.settings.setSyncCalendar(it) }
            }
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val exactOk = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
            if (!exactOk) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未授予精确闹钟权限，提醒时间可能有偏差",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 31) {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        }
                    }) { Text("去授权") }
                }
            }

            SectionTitle("外观")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("夜间模式", Modifier.weight(1f))
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (v, label) ->
                    FilterChip(
                        selected = settings.darkMode == v,
                        onClick = { scope.launch { container.settings.setDarkMode(v) } },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            SectionTitle("数据")
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("碎碎念笔记导出.zip") },
                    modifier = Modifier.weight(1f)
                ) { Text("导出笔记") }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("导入录音") }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("清除缓存", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "删除运行过程产生的临时文件（语音识别临时文件、导出用的临时压缩包等）。\n不影响笔记、提醒、聊天记录、录音和各项设置。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        context.cacheDir.listFiles()?.forEach { it.deleteRecursivelySafe() }
                        toast = "缓存已清理"
                    }
                }) { Text("清除") }
            }

            SectionTitle("隐私说明")
            Text(
                "· 录音在本地完成语音识别（SenseVoice，离线模型），音频文件仅保存在手机本地，永不上传。\n" +
                    "· 文字笔记全部保存在本地（数据库 + Markdown 文件），可随时导出。\n" +
                    "· 仅「AI 整理/查询总结」会把清洗后的文字发送给你配置的云端大模型 API；如需完全离线，可在 AI 配置中填入自建服务地址。\n" +
                    "· 应用不做任何埋点、不上传任何统计数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "碎碎念 Memur v2.1.1 · 随心口述，AI 帮你管好所有碎想法",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun File.deleteRecursivelySafe() {
    try {
        if (isDirectory) listFiles()?.forEach { it.deleteRecursivelySafe() }
        delete()
    } catch (_: Exception) {
    }
}
