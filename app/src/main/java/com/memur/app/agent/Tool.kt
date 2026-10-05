package com.memur.app.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 智能体可调用的工具。
 *
 * 实现方只需声明名称、描述与参数 schema，并在 [execute] 中完成副作用、
 * 返回「给模型看的文本结果」。对用户的 UI 副作用（展示卡片、提问等）
 * 通过 [AgentLoop] 落入聊天流，见 tools/InteractTools.kt。
 */
interface Tool {
    val name: String
    val description: String

    /** 参数 JSON Schema（object 类型） */
    val parameters: JSONObject get() = emptySchema()

    /** 执行期间展示给用户的活动名，如「检索记忆」 */
    val activity: String get() = "处理中"

    suspend fun execute(args: ToolArgs): String
}

private fun emptySchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put("properties", JSONObject())

/** 从模型返回的 JSON 参数中安全取值（模型输出不可信，一律兜底） */
class ToolArgs(private val json: JSONObject) {

    fun string(name: String): String = json.optString(name).trim()

    /** 值为空白时返回 null */
    fun optional(name: String): String? = string(name).ifEmpty { null }

    fun int(name: String, default: Int): Int =
        if (json.has(name) && !json.isNull(name)) json.optInt(name, default) else default

    fun bool(name: String): Boolean = json.optBoolean(name, false)

    fun stringList(name: String): List<String> {
        val arr = json.optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().ifEmpty { null } }
    }
}

/**
 * 轻量 JSON Schema DSL。
 *
 * ```
 * override val parameters = parameters {
 *     string("title", "笔记标题", required = true)
 *     stringArray("tags", "标签列表")
 * }
 * ```
 */
fun parameters(block: SchemaBuilder.() -> Unit): JSONObject = SchemaBuilder().apply(block).build()

class SchemaBuilder internal constructor() {

    private val properties = LinkedHashMap<String, JSONObject>()
    private val requiredNames = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) {
        properties[name] = JSONObject()
            .put("type", "string")
            .put("description", description)
            .apply { enum?.let { put("enum", JSONArray(it)) } }
        if (required) requiredNames += name
    }

    fun integer(name: String, description: String, required: Boolean = false) {
        properties[name] = JSONObject().put("type", "integer").put("description", description)
        if (required) requiredNames += name
    }

    fun boolean(name: String, description: String, required: Boolean = false) {
        properties[name] = JSONObject().put("type", "boolean").put("description", description)
        if (required) requiredNames += name
    }

    fun stringArray(name: String, description: String, required: Boolean = false) {
        properties[name] = JSONObject()
            .put("type", "array")
            .put("description", description)
            .put("items", JSONObject().put("type", "string"))
        if (required) requiredNames += name
    }

    fun build(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject(properties))
        .apply { if (requiredNames.isNotEmpty()) put("required", JSONArray(requiredNames)) }
}
