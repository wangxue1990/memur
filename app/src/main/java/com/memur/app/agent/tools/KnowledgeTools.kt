package com.memur.app.agent.tools

import com.memur.app.agent.AgentLoop
import com.memur.app.agent.Tool
import com.memur.app.agent.ToolArgs
import com.memur.app.agent.parameters
import com.memur.app.data.MarkdownStore
import com.memur.app.data.Repository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识库工具：grep 检索、读取、写入、修改、删除笔记与主题。
 * 检索面向 Markdown 落盘文件，与产品「无向量库、agentic grep」定位一致。
 */

class SearchMemoryTool(private val markdown: MarkdownStore) : Tool {
    override val name = "search_memory"
    override val description =
        "在全部历史笔记中做关键词检索（类似 grep）。返回命中笔记的编号、标题、日期与内容片段。可换多组关键词多次调用以提高召回。"
    override val activity = "检索记忆"
    override val parameters = parameters {
        stringArray("keywords", "检索关键词，建议 1-4 个", required = true)
        integer("limit", "最多返回条数，默认 8")
    }

    override suspend fun execute(args: ToolArgs): String {
        val keywords = args.stringList("keywords")
        if (keywords.isEmpty()) return "请提供至少一个关键词"
        val hits = markdown.grep(keywords, args.int("limit", 8))
        if (hits.isEmpty()) return "没有找到与「${keywords.joinToString("、")}」相关的笔记"
        return hits.joinToString("\n\n") {
            "[#${it.noteId}] ${it.title}（${it.date}）\n${it.snippet}"
        }
    }
}

class ReadNoteTool(private val repo: Repository) : Tool {
    override val name = "read_note"
    override val description = "按笔记编号读取完整笔记（含原始语音转写）。"
    override val activity = "读取笔记"
    override val parameters = parameters {
        integer("note_id", "笔记编号（如 #12 的数字部分）", required = true)
    }

    override suspend fun execute(args: ToolArgs): String {
        val note = repo.noteDao.byId(args.int("note_id", -1).toLong()) ?: return "笔记不存在"
        val topic = repo.topicTitle(note.topicId) ?: "未分类"
        return buildString {
            appendLine("标题：${note.title}")
            appendLine("主题：$topic　日期：${date(note.createdAt)}")
            if (note.tagList().isNotEmpty()) appendLine("标签：${note.tagList().joinToString("、")}")
            appendLine()
            appendLine(note.cleanedText)
            if (note.rawText.isNotBlank() && note.rawText != note.cleanedText) {
                appendLine()
                appendLine("—— 原始转写 ——")
                appendLine(note.rawText)
            }
        }.take(3000)
    }
}

class ListNotesTool(private val repo: Repository) : Tool {
    override val name = "list_notes"
    override val description = "列出最近的笔记（编号、标题、主题、日期）。"
    override val activity = "查看笔记列表"
    override val parameters = parameters {
        integer("limit", "返回条数，默认 10")
    }

    override suspend fun execute(args: ToolArgs): String {
        val notes = repo.noteDao.all().take(args.int("limit", 10).coerceIn(1, 50))
        if (notes.isEmpty()) return "还没有任何笔记"
        val topicNames = HashMap<Long?, String>()
        val lines = ArrayList<String>(notes.size)
        for (n in notes) {
            val topic = topicNames.getOrPut(n.topicId) { "" }
            val name = if (topic.isEmpty()) repo.topicTitle(n.topicId) ?: "未分类" else topic
            topicNames[n.topicId] = name
            lines.add("[#${n.id}] ${n.title}（$name，${date(n.createdAt)}）")
        }
        return lines.joinToString("\n")
    }
}

class ListTopicsTool(private val repo: Repository) : Tool {
    override val name = "list_topics"
    override val description = "列出全部主题（Wiki 页面）及各主题下的笔记数。"
    override val activity = "查看主题列表"
    override val parameters = parameters { }

    override suspend fun execute(args: ToolArgs): String {
        val topics = repo.allTopics()
        if (topics.isEmpty()) return "还没有任何主题"
        val lines = ArrayList<String>(topics.size)
        for (t in topics) {
            lines.add("[${t.id}] ${t.title}（${repo.noteDao.countByTopic(t.id)} 条笔记）")
        }
        return lines.joinToString("\n")
    }
}

class WriteNoteTool(private val repo: Repository, private val agent: AgentLoop) : Tool {
    override val name = "write_note"
    override val description = "保存一条新笔记（传入清洗整理后的内容）。返回笔记编号。"
    override val activity = "保存笔记"
    override val parameters = parameters {
        string("title", "标题，简洁概括", required = true)
        string("content", "正文（清洗口语废话后的要点内容）", required = true)
        stringArray("tags", "标签列表")
        string("topic", "所属主题（Wiki 页面名），留空则不归类")
    }

    override suspend fun execute(args: ToolArgs): String {
        val note = repo.createNote(
            title = args.string("title"),
            rawText = agent.currentUserText,
            cleanedText = args.string("content"),
            tags = args.stringList("tags"),
            topicName = args.string("topic"),
            entitiesJson = "{}",
            audioPath = agent.takePendingAudioPath(),
            mode = "note"
        )
        return "已保存笔记 #${note.id}《${note.title}》"
    }
}

class UpdateNoteTool(private val repo: Repository) : Tool {
    override val name = "update_note"
    override val description = "修改已有笔记的部分字段（标题/正文/标签/主题），未提供的字段保持不变。"
    override val activity = "修改笔记"
    override val parameters = parameters {
        integer("note_id", "笔记编号", required = true)
        string("title", "新标题")
        string("content", "新正文")
        stringArray("tags", "新标签列表（整体替换）")
        string("topic", "新主题（即移动到其他 Wiki 页面）")
    }

    override suspend fun execute(args: ToolArgs): String {
        val id = args.int("note_id", -1).toLong()
        val note = repo.noteDao.byId(id) ?: return "笔记不存在"
        val newTags = args.stringList("tags")
        repo.updateNote(
            note = note,
            title = args.optional("title") ?: note.title,
            cleanedText = args.optional("content") ?: note.cleanedText,
            tags = if (newTags.isEmpty()) note.tagList() else newTags,
            topicName = args.optional("topic") ?: (repo.topicTitle(note.topicId) ?: "")
        )
        return "已更新笔记 #$id"
    }
}

class DeleteNoteTool(private val repo: Repository) : Tool {
    override val name = "delete_note"
    override val description = "删除一条笔记（不可恢复，删除前务必用 ask_user 向用户确认）。"
    override val activity = "删除笔记"
    override val parameters = parameters {
        integer("note_id", "笔记编号", required = true)
    }

    override suspend fun execute(args: ToolArgs): String {
        val id = args.int("note_id", -1).toLong()
        val note = repo.noteDao.byId(id) ?: return "笔记不存在"
        repo.deleteNote(note)
        return "已删除笔记 #$id《${note.title}》"
    }
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private fun date(t: Long) = dateFmt.format(Date(t))
