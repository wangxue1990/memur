package com.memur.app.agent

import com.memur.app.data.LlmClient
import com.memur.app.data.LlmMessage
import com.memur.app.data.MessageDao
import com.memur.app.data.MessageEntity
import com.memur.app.data.SettingsStore
import com.memur.app.data.TtsController
import com.memur.app.data.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 智能体运行状态（UI 内联展示，不弹窗不抽屉） */
sealed interface AgentStatus {
    data object Idle : AgentStatus
    data class Working(val activity: String) : AgentStatus
    data class WaitingUser(val question: String) : AgentStatus
}

/** 聊天流中的消息种类（持久化于 MessageEntity.kind） */
object MessageKind {
    const val USER = "user"      // 用户说的话（语音转写或打字）
    const val AGENT = "agent"    // 智能体的自然语言回复
    const val INFO = "info"      // show_info_to_user 的信息卡片
    const val ASK = "ask"        // ask_user 的问题卡片（payload 为选项 JSON）
    const val ANSWER = "answer"  // 用户对问题的回答
    const val ERROR = "error"    // 出错提示
}

/**
 * Agent harness：接收用户输入，驱动 LLM 多轮「思考 → 调用工具 → 回填结果」循环，
 * 直到模型给出最终回复或中途等待用户。
 *
 * 设计要点：
 * - 所有中间状态经 [status] 广播，由 UI 内联展示；
 * - ask_user 通过 [CompletableDeferred] 挂起整个循环，用户回答后无缝续跑；
 * - 聊天记录持久化到 Room（[transcript]），LLM 原始上下文仅在内存保留最近若干条；
 * - 忙碌期间的新输入自动排队，上一任务结束后续跑。
 */
class AgentLoop internal constructor(
    private val llm: LlmClient,
    private val settings: SettingsStore,
    private val messageDao: MessageDao
) {

    companion object {
        private const val MAX_STEPS = 12          // 单次任务最多工具调用轮数
        private const val MAX_HISTORY = 40        // LLM 上下文保留的消息条数
        private const val TOOL_RESULT_LIMIT = 4000
    }

    val registry = ToolRegistry()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status

    /** 聊天记录（持久化，UI 直接观察） */
    val transcript: Flow<List<MessageEntity>> = messageDao.observeRecent()

    /** LLM 上下文（内存，进程重启后重建） */
    private val history = ArrayDeque<LlmMessage>()

    private var runJob: Job? = null
    private var askDeferred: CompletableDeferred<String>? = null
    private var queued: Pair<String, String?>? = null

    /** 当前回合用户原话（write_note 存原始转写用） */
    @Volatile
    internal var currentUserText: String = ""
        private set

    /** 当前回合的录音文件路径（write_note 绑定到笔记后清空） */
    @Volatile
    internal var pendingAudioPath: String? = null
        private set

    // ---------- 对外入口 ----------

    /**
     * 用户输入（语音转写或打字）。
     * 若正等待提问回答，则自动视为回答；若忙碌则排队。
     */
    fun submit(text: String, audioPath: String? = null) {
        val t = text.trim()
        if (t.isEmpty()) return

        if (askDeferred != null) {
            answer(t)
            return
        }
        if (runJob?.isActive == true) {
            queued = t to audioPath
            return
        }
        launchRun(t, audioPath)
    }

    /** 回答 ask_user 的提问 */
    fun answer(text: String) {
        val deferred = askDeferred ?: return
        if (deferred.isCompleted) return
        scope.launch {
            persist(MessageKind.ANSWER, text)
            _status.value = AgentStatus.Working("思考中")
            deferred.complete(text)
        }
    }

    /** 取消当前任务（等待回答时也可取消） */
    fun cancel() {
        queued = null
        askDeferred?.cancel()
        askDeferred = null
        runJob?.cancel()
        _status.value = AgentStatus.Idle
    }

    /**
     * 清除对话：清空 LLM 短期上下文与聊天记录。
     * 笔记、主题、提醒属于知识库资产，不受影响。
     */
    fun clearConversation() {
        queued = null
        askDeferred?.cancel()
        askDeferred = null
        runJob?.cancel()
        runJob = null
        history.clear()
        currentUserText = ""
        pendingAudioPath = null
        _status.value = AgentStatus.Idle
        scope.launch {
            messageDao.clear()
            history.clear() // clear 与回灌并发时兜底再清一次
        }
    }

    /** 语音识别等前置环节借用状态条 */
    fun reportWorking(activity: String) {
        if (_status.value is AgentStatus.Idle) _status.value = AgentStatus.Working(activity)
    }

    /** 前置环节结束（如「先上屏确认」模式下识别完成）：仅在没有进行中任务时复位状态条 */
    fun reportIdle() {
        if (runJob?.isActive != true && _status.value is AgentStatus.Working) {
            _status.value = AgentStatus.Idle
        }
    }

    fun postError(message: String) {
        scope.launch {
            persist(MessageKind.ERROR, message)
            if (runJob?.isActive != true) _status.value = AgentStatus.Idle
        }
    }

    // ---------- 主循环 ----------

    private fun launchRun(userText: String, audioPath: String?) {
        currentUserText = userText
        pendingAudioPath = audioPath
        runJob = scope.launch {
            try {
                persist(MessageKind.USER, userText)
                history.addLast(LlmMessage.User(userText))
                trimHistory()

                for (step in 0 until MAX_STEPS) {
                    _status.value = AgentStatus.Working("思考中")
                    val reply = llm.chat(listOf(LlmMessage.System(AgentPrompts.system())) + history.toList(), registry.specs())

                    if (!reply.wantsTools) {
                        finish(reply.content)
                        return@launch
                    }
                    history.addLast(LlmMessage.Assistant(reply.content, reply.toolCalls))
                    for (call in reply.toolCalls) {
                        _status.value = AgentStatus.Working(registry.activityOf(call.name))
                        val output = executeTool(call)
                        history.addLast(LlmMessage.ToolResult(call.id, output))
                        trimHistory()
                    }
                }
                finish("这件事步骤有点多，我暂时没做完，换个说法再试一次？")
            } catch (_: CancellationException) {
                // 用户主动取消
            } catch (e: Exception) {
                persist(MessageKind.ERROR, "处理失败：${e.message ?: "未知错误"}")
            } finally {
                askDeferred = null
                pendingAudioPath = null
                _status.value = AgentStatus.Idle
                val next = queued
                queued = null
                if (next != null) launchRun(next.first, next.second)
            }
        }
    }

    private suspend fun finish(text: String) {
        if (text.isNotBlank()) {
            persist(MessageKind.AGENT, text)
            if (settings.current().autoSpeak) {
                withContext(Dispatchers.Main) { TtsController.speak(text) }
            }
        }
    }

    private suspend fun executeTool(call: ToolCall): String {
        val tool = registry.find(call.name) ?: return "未知工具：${call.name}"
        val args = try {
            JSONObject(call.arguments)
        } catch (_: Exception) {
            JSONObject()
        }
        return try {
            tool.execute(ToolArgs(args)).take(TOOL_RESULT_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "工具 ${call.name} 执行失败：${e.message ?: "未知错误"}"
        }
    }

    // ---------- 供工具调用的内部能力 ----------

    /** 挂起等待用户回答；已有未回答的问题时返回 null */
    internal suspend fun beginAsk(question: String, options: List<String>): CompletableDeferred<String>? {
        if (askDeferred != null) return null
        return CompletableDeferred<String>().also {
            askDeferred = it
            persist(MessageKind.ASK, question, JSONArray(options).toString())
            _status.value = AgentStatus.WaitingUser(question)
        }
    }

    /** 在聊天流中插入信息卡片 */
    internal suspend fun showInfo(title: String, content: String) {
        persist(MessageKind.INFO, title, content)
    }

    /** 取走本回合的录音文件路径（绑定到笔记） */
    internal fun takePendingAudioPath(): String? {
        val path = pendingAudioPath
        pendingAudioPath = null
        return path
    }

    // ---------- 辅助 ----------

    private suspend fun persist(kind: String, text: String, payload: String = "") {
        messageDao.insert(MessageEntity(kind = kind, text = text, payload = payload))
    }

    /** 从持久化记录重建 LLM 上下文：USER/ANSWER→user，AGENT→assistant，卡片类不回灌 */
    private suspend fun restoreHistory() {
        if (history.isNotEmpty() || runJob?.isActive == true) return
        val rows = messageDao.recent(300)
        for (m in rows) {
            when (m.kind) {
                MessageKind.USER, MessageKind.ANSWER -> history.addLast(LlmMessage.User(m.text))
                MessageKind.AGENT -> history.addLast(LlmMessage.Assistant(m.text))
                else -> Unit
            }
        }
        trimHistory()
    }

    /** 控制上下文长度，且保证头部不出现孤儿 tool result */
    private fun trimHistory() {
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
            while (history.firstOrNull() is LlmMessage.ToolResult) history.removeFirst()
        }
    }

    init {
        // 重启回灌：必须放在所有属性初始化之后启动——
        // 协程跑在 Default 线程池，若早于 history 字段赋值启动会读到 null 导致闪退（v2.0.2 启动 NPE 的根因）
        scope.launch { restoreHistory() }
    }
}
