package com.memur.app.agent.tools

import com.memur.app.agent.AgentLoop
import com.memur.app.agent.Tool
import com.memur.app.agent.ToolArgs
import com.memur.app.agent.parameters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 与用户交互的工具：展示信息卡片、提问等待回答、获取精确时间 */

class ShowInfoTool(private val agent: AgentLoop) : Tool {
    override val name = "show_info_to_user"
    override val description = "向用户展示一张结构化信息卡片（标题+正文），适合检索汇总、提醒清单等。"
    override val activity = "整理展示"
    override val parameters = parameters {
        string("title", "卡片标题", required = true)
        string("content", "卡片正文（纯文本）", required = true)
    }

    override suspend fun execute(args: ToolArgs): String {
        agent.showInfo(args.string("title"), args.string("content"))
        return "已向用户展示卡片「${args.string("title")}」"
    }
}

class AskUserTool(private val agent: AgentLoop) : Tool {
    override val name = "ask_user"
    override val description = "向用户提一个问题并等待回答（会挂起直到用户回答）。用于删除确认、缺失的关键信息等。"
    override val activity = "等待回答"
    override val parameters = parameters {
        string("question", "要问的问题", required = true)
        stringArray("options", "候选答案（可选，展示为快捷按钮）")
    }

    override suspend fun execute(args: ToolArgs): String {
        val deferred = agent.beginAsk(args.string("question"), args.stringList("options"))
            ?: return "当前已有未回答的问题，请基于已有信息继续"
        val answer = deferred.await()
        return "用户回答：$answer"
    }
}

class GetTimeTool : Tool {
    override val name = "get_time"
    override val description = "获取当前精确时间（推算「明天下午3点」这类相对时间时使用）。"
    override val activity = "查看时间"
    override val parameters = parameters { }

    override suspend fun execute(args: ToolArgs): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA).format(Date())
}
