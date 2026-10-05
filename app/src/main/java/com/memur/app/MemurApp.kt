package com.memur.app

import android.app.Application
import com.memur.app.agent.createAgent
import com.memur.app.data.AsrEngine
import com.memur.app.data.AppDatabase
import com.memur.app.data.LlmClient
import com.memur.app.data.MarkdownStore
import com.memur.app.data.Repository
import com.memur.app.data.SettingsStore
import com.memur.app.data.TtsController
import com.memur.app.data.VoiceIntake
import com.memur.app.data.reminder.Notifier

class MemurApp : Application() {

    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        Notifier.ensureChannels(this)
        TtsController.init(this)
    }

    /** 轻量依赖容器：录音 → VoiceIntake（本地识别）→ AgentLoop（智能体）→ 各工具 */
    class Container(app: MemurApp) {
        val settings = SettingsStore(app)
        val db by lazy { AppDatabase.build(app) }
        val markdown by lazy { MarkdownStore(app.filesDir) }
        val repo by lazy { Repository(db, markdown) }
        val llm by lazy { LlmClient(settings) }
        val asr by lazy { AsrEngine(app) }
        val agent by lazy { createAgent(app, llm, repo, settings, db.messageDao(), markdown) }

        /** 「先上屏确认」模式：语音转写文本经此通道送到对话页输入框（页面切换不丢失） */
        val draft = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        val voiceIntake by lazy {
            VoiceIntake(asr, settings, agent) { text -> draft.value = text }
        }
        val appContext = app
    }
}
