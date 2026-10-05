package com.memur.app.data.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.memur.app.data.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 提醒触发时的全屏响铃界面。
 * - showWhenLocked + turnScreenOn：锁屏也能亮屏弹出；
 * - 铃声循环播放 + 振动；
 * - TTS 朗读提醒内容（语音优先，符合 App 定位）；
 * - "我知道了"停止响铃关闭界面；
 * - "稍后提醒"：N 分钟后重新触发闹钟。
 *
 * 不需要 App 常驻后台——由 AlarmReceiver startActivity 拉起。
 */
class FullScreenReminderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_MESSAGE = "reminder_message"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        const val DEFAULT_SNOOZE = 5
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ringLoop = object : Runnable {
        override fun run() {
            ringtone?.let {
                if (!it.isPlaying) {
                    try { it.play() } catch (_: Exception) {}
                }
            }
            // 振动模式：等 1s，振 1s，循环
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏亮屏
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 保持屏幕常亮直到用户操作
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val id = intent.getLongExtra(EXTRA_ID, -1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "提醒"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: title

        startRingtone()
        startVibration()
        speakContent(title, message)

        setContent {
            RingingScreen(
                title = title,
                message = message,
                onDismiss = { finish() },
                onSnooze = { minutes ->
                    lifecycleScope.launch {
                        ReminderScheduler.snooze(applicationContext, id, minutes)
                        finish()
                    }
                }
            )
        }
    }

    private fun startRingtone() {
        val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        ringtone = RingtoneManager.getRingtone(applicationContext, uri).apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        try { ringtone?.play() } catch (_: Exception) {}
        handler.postDelayed(ringLoop, 2000)
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 1000, 1000)
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(VibratorManager::class.java)
            vibrator = vm?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else if (Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Vibrator::class.java)
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    /** TTS 朗读：先读标题，停顿后读消息内容 */
    private fun speakContent(title: String, message: String) {
        TtsController.init(applicationContext)
        // 延迟 500ms 等铃声起来后再朗读，避免被铃声盖住第一句
        Handler(Looper.getMainLooper()).postDelayed({
            val text = if (message != title) "提醒你：$title。$message" else "提醒你：$title"
            TtsController.speak(text)
        }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ringLoop)
        try { ringtone?.stop() } catch (_: Exception) {}
        vibrator?.cancel()
        TtsController.stop()
    }
}

@Composable
private fun RingingScreen(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit
) {
    var snoozeMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Alarm,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.headlineSmall
            )
            if (message != title) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("我知道了", fontSize = 18.sp) }

            Spacer(Modifier.height(12.dp))
            if (snoozeMenu) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf(5 to "5分钟", 10 to "10分钟", 30 to "30分钟").forEach { (min, label) ->
                        OutlinedButton(
                            onClick = { onSnooze(min) },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(label) }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { snoozeMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("稍后提醒") }
            }
        }
    }
}
