package com.memur.app.agent

import org.json.JSONObject

/**
 * 工具注册表：集中注册、按名查找、生成 OpenAI 兼容的 tools 声明。
 *
 * 新增能力 = 实现一个 [Tool] 并在 AgentSetup 中注册，其余环节（提示词、
 * 循环、UI）无需改动。
 */
class ToolRegistry {

    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        require(tools.put(tool.name, tool) == null) { "工具重复注册：${tool.name}" }
    }

    fun find(name: String): Tool? = tools[name]

    /** 请求体中的 tools 数组元素 */
    fun specs(): List<JSONObject> = tools.values.map { tool ->
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", tool.parameters)
            )
    }

    fun activityOf(name: String): String = tools[name]?.activity ?: "处理中"
}
