package com.memur.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一次函数调用请求（OpenAI 兼容格式） */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * 对话消息。Assistant 可携带工具调用请求；ToolResult 回填工具执行结果。
 * 每种消息都知道如何序列化为请求体中的 JSON。
 */
sealed class LlmMessage {

    abstract fun toJson(): JSONObject

    data class System(val content: String) : LlmMessage() {
        override fun toJson() = JSONObject().put("role", "system").put("content", content)
    }

    data class User(val content: String) : LlmMessage() {
        override fun toJson() = JSONObject().put("role", "user").put("content", content)
    }

    data class Assistant(val content: String, val toolCalls: List<ToolCall> = emptyList()) : LlmMessage() {
        override fun toJson(): JSONObject = JSONObject()
            .put("role", "assistant")
            .apply { if (content.isNotEmpty()) put("content", content) }
            .apply {
                if (toolCalls.isNotEmpty()) put("tool_calls", JSONArray(toolCalls.map { call ->
                    JSONObject()
                        .put("id", call.id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                }))
            }
    }

    data class ToolResult(val toolCallId: String, val content: String) : LlmMessage() {
        override fun toJson() = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", toolCallId)
            .put("content", content)
    }
}

/** 模型的一次回复：纯文本、若干工具调用请求，或两者皆有 */
data class LlmReply(val content: String, val toolCalls: List<ToolCall>) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/**
 * OpenAI 兼容 Chat Completions 客户端（支持 function calling）。
 */
class LlmClient(private val settings: SettingsStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 多轮对话 + 工具声明，返回模型回复 */
    suspend fun chat(messages: List<LlmMessage>, tools: List<JSONObject>): LlmReply =
        withContext(Dispatchers.IO) {
            val cfg = settings.current()
            if (cfg.llmApiKey.isBlank()) {
                throw IllegalStateException("尚未配置 API Key：请到「设置 → AI 模型」填写 OpenAI 兼容的接口地址与 Key")
            }
            val base = if (cfg.llmBaseUrl.endsWith("/")) cfg.llmBaseUrl else cfg.llmBaseUrl + "/"

            val body = JSONObject().apply {
                put("model", cfg.llmModel)
                put("temperature", 0.3)
                put("messages", JSONArray(messages.map { it.toJson() }))
                if (tools.isNotEmpty()) put("tools", JSONArray(tools))
            }

            val req = Request.Builder()
                .url(base + "chat/completions")
                .header("Authorization", "Bearer ${cfg.llmApiKey}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw IOException("LLM API ${resp.code}: ${text.take(300)}")
                val message = JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                LlmReply(
                    content = message.optString("content") ?: "",
                    toolCalls = parseToolCalls(message.optJSONArray("tool_calls"))
                )
            }
        }

    private fun parseToolCalls(arr: JSONArray?): List<ToolCall> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val call = arr.optJSONObject(i) ?: return@mapNotNull null
            val fn = call.optJSONObject("function") ?: return@mapNotNull null
            ToolCall(
                id = call.optString("id").ifEmpty { "call_$i" },
                name = fn.optString("name"),
                arguments = fn.optString("arguments").ifEmpty { "{}" }
            )
        }.filter { it.name.isNotEmpty() }
    }
}
