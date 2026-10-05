package com.memur.app.data

import com.memur.app.agent.AgentLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音入口：录音文件 → 本地离线识别（SenseVoice）→ 智能体。
 * 发送方式由设置 voiceSendMode 决定：
 * - direct（默认）：转写后直接交给智能体处理；
 * - confirm：转写结果先上屏到输入框（经 [offerDraft]），用户可编辑后手动发送。
 * 转写完成后立即删除原始音频文件——不做回放、不保存音频。
 */
class VoiceIntake(
    private val asr: AsrEngine,
    private val settings: SettingsStore,
    private val agent: AgentLoop,
    private val offerDraft: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 提交一段录音 WAV（录音服务停止 / 外部导入） */
    fun submit(wavPath: String) {
        scope.launch {
            try {
                agent.reportWorking("本地语音识别中")
                val text = withContext(Dispatchers.IO) { asr.transcribeFile(wavPath) }
                // 转写完成，立即删除原始音频
                File(wavPath).delete()
                if (text.isBlank()) {
                    agent.postError("没听清你说了什么，再试一次？")
                } else if (settings.current().voiceSendMode == "confirm") {
                    agent.reportIdle()
                    offerDraft(text)
                } else {
                    agent.submit(text, null)
                }
            } catch (e: Exception) {
                File(wavPath).delete()
                agent.postError("语音处理失败：${e.message ?: "未知错误"}")
            }
        }
    }
}
