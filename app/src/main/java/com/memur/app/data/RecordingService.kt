package com.memur.app.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.NotificationCompat
import com.memur.app.MainActivity
import com.memur.app.MemurApp
import com.memur.app.R
import com.memur.app.data.reminder.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 录音服务状态（顶层声明，供 UI/小组件引用） */
sealed class RecState {
    data object Idle : RecState()
    data class Recording(val startedAt: Long, val amplitude: Float = 0f) : RecState()
}

/**
 * 前台录音服务：16kHz 单声道 PCM → WAV，支持后台/锁屏录音。
 * 录音结束即交接给 [VoiceIntake]（本地识别 → 智能体），服务随即收工，状态回 Idle。
 */
class RecordingService : android.app.Service() {

    companion object {
        const val ACTION_START = "com.memur.app.recording.START"
        const val ACTION_STOP = "com.memur.app.recording.STOP"
        const val ACTION_TOGGLE = "com.memur.app.recording.TOGGLE"
        const val SAMPLE_RATE = 16000

        private val _state = MutableStateFlow<RecState>(RecState.Idle)
        val state: StateFlow<RecState> = _state

        fun start(context: Context) {
            val i = Intent(context, RecordingService::class.java).apply { action = ACTION_START }
            try {
                context.startForegroundService(i)
            } catch (_: Exception) {
                // 后台启动受限时退化为 startService（前台时可用）
                try {
                    context.startService(i)
                } catch (_: Exception) {
                }
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var recording = false
    private var pcmData = ByteArrayOutputStream()
    private var startedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!recording) startRecording()
            ACTION_STOP -> if (recording) stopRecording()
            ACTION_TOGGLE -> if (recording) stopRecording() else startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = RecState.Idle
            stopSelf()
            return
        }
        startAsForeground()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 8192)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: Exception) {
            _state.value = RecState.Idle
            stopSelf()
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.value = RecState.Idle
            stopSelf()
            return
        }
        audioRecord = record
        pcmData = ByteArrayOutputStream()
        recording = true
        startedAt = System.currentTimeMillis()
        _state.value = RecState.Recording(startedAt)
        record.startRecording()

        recordThread = Thread {
            val buf = ShortArray(2048)
            var lastUiUpdate = 0L
            while (recording) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    val bytes = ByteArray(n * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                        for (i in 0 until n) putShort(buf[i])
                    }
                    synchronized(pcmData) { pcmData.write(bytes) }
                }
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate > 150) {
                    lastUiUpdate = now
                    var peak = 0
                    for (s in buf) if (Math.abs(s.toInt()) > peak) peak = Math.abs(s.toInt())
                    _state.value = RecState.Recording(startedAt, (peak / 32768f).coerceIn(0f, 1f))
                }
                // 安全上限：单次最多 10 分钟
                if (now - startedAt > 10 * 60 * 1000) {
                    android.os.Handler(mainLooper).post { if (recording) stopRecording() }
                    break
                }
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        recording = false
        try {
            recordThread?.join(1500)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val wavPath: String? = synchronized(pcmData) {
            val pcm = pcmData.toByteArray()
            if (pcm.size > 1600) { // 至少 50ms
                val dir = File(filesDir, "recordings").apply { mkdirs() }
                val f = File(dir, "rec_${startedAt}.wav")
                writeWav(f, pcm)
                f.absolutePath
            } else null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        _state.value = RecState.Idle

        val app = application as MemurApp
        if (wavPath != null) {
            app.container.voiceIntake.submit(wavPath)
        } else {
            app.container.agent.postError("录音太短，多说两句吧")
        }
    }

    private fun writeWav(dest: File, pcm: ByteArray) {
        val header = ByteArray(44)
        val totalLen = pcm.size + 36
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(totalLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        dest.outputStream().use {
            it.write(header)
            it.write(pcm)
        }
    }

    private fun startAsForeground() {
        Notifier.ensureChannels(this)
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, Notifier.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle("正在录音 · 碎碎念")
            .setContentText("想到什么说什么，说完点停止")
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(0, "停止", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }
    }

    override fun onDestroy() {
        recording = false
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        if (_state.value is RecState.Recording) _state.value = RecState.Idle
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null
}
