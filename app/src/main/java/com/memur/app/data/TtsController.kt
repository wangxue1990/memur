package com.memur.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 系统本地 TTS 播报（TextToSpeech API，不联网、不调用任何外部 API）。
 * [speaking] 广播当前是否正在朗读，UI 据此显示"停止朗读"入口。
 */
object TtsController {

    private var tts: TextToSpeech? = null
    private var ready = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                try {
                    tts?.language = Locale.CHINA
                    // 跟踪朗读起止：onStart/onDone 可能晚于 speak 调用数十毫秒，
                    // 因此 speak() 里先乐观置 true，这里以引擎回调为准修正
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { _speaking.value = true }
                        override fun onDone(utteranceId: String?) { _speaking.value = false }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { _speaking.value = false }
                        override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false }
                    })
                } catch (_: Exception) {
                }
            }
        }
    }

    fun speak(text: String) {
        val t = tts ?: return
        if (!ready) return
        try {
            t.stop()
            _speaking.value = true
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "memur_utt")
        } catch (_: Exception) {
            _speaking.value = false
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        _speaking.value = false
    }
}
