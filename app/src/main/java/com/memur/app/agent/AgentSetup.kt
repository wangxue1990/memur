package com.memur.app.agent

import android.content.Context
import com.memur.app.agent.tools.AskUserTool
import com.memur.app.agent.tools.CreateReminderTool
import com.memur.app.agent.tools.DeleteNoteTool
import com.memur.app.agent.tools.DeleteReminderTool
import com.memur.app.agent.tools.GetTimeTool
import com.memur.app.agent.tools.ListNotesTool
import com.memur.app.agent.tools.ListRemindersTool
import com.memur.app.agent.tools.ListTopicsTool
import com.memur.app.agent.tools.ReadNoteTool
import com.memur.app.agent.tools.SearchMemoryTool
import com.memur.app.agent.tools.SetReminderEnabledTool
import com.memur.app.agent.tools.ShowInfoTool
import com.memur.app.agent.tools.UpdateNoteTool
import com.memur.app.agent.tools.WriteNoteTool
import com.memur.app.data.LlmClient
import com.memur.app.data.MarkdownStore
import com.memur.app.data.MessageDao
import com.memur.app.data.Repository
import com.memur.app.data.SettingsStore

/**
 * 组装智能体：注入依赖并注册全部工具。
 * 扩展新能力时在此追加一行 register 即可。
 */
fun createAgent(
    appContext: Context,
    llm: LlmClient,
    repo: Repository,
    settings: SettingsStore,
    messageDao: MessageDao,
    markdown: MarkdownStore
): AgentLoop {
    val agent = AgentLoop(llm, settings, messageDao)
    agent.registry.apply {
        // 知识库：检索 / 读取 / 写入 / 修改 / 删除
        register(SearchMemoryTool(markdown))
        register(ReadNoteTool(repo))
        register(ListNotesTool(repo))
        register(ListTopicsTool(repo))
        register(WriteNoteTool(repo, agent))
        register(UpdateNoteTool(repo))
        register(DeleteNoteTool(repo))
        // 提醒
        register(CreateReminderTool(appContext, repo, settings))
        register(ListRemindersTool(repo))
        register(DeleteReminderTool(appContext, repo))
        register(SetReminderEnabledTool(appContext, repo))
        // 与用户交互
        register(ShowInfoTool(agent))
        register(AskUserTool(agent))
        register(GetTimeTool())
    }
    return agent
}
