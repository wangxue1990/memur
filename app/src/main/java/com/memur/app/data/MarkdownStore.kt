package com.memur.app.data

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 笔记的 Markdown 落盘层：每条笔记一个 .md 文件，是检索（agentic grep）与导出的数据源。
 */
class MarkdownStore(private val filesDir: File) {

    val notesDir: File = File(filesDir, "notes").apply { mkdirs() }

    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun fileFor(noteId: Long): File = File(notesDir, "$noteId.md")

    fun write(note: NoteEntity, topicTitle: String?) {
        val md = buildString {
            appendLine("# ${note.title}")
            appendLine()
            appendLine("- 日期：${df.format(Date(note.createdAt))}")
            if (!topicTitle.isNullOrBlank()) appendLine("- 主题：$topicTitle")
            val tags = note.tagList()
            if (tags.isNotEmpty()) appendLine("- 标签：${tags.joinToString(" ") { "#$it" }}")
            if (note.important) appendLine("- ⭐ 重要")
            appendLine()
            appendLine(note.cleanedText)
            appendLine()
            appendLine("---")
            appendLine("## 原始转写")
            appendLine(note.rawText)
        }
        fileFor(note.id).writeText(md, Charsets.UTF_8)
    }

    fun delete(noteId: Long) {
        fileFor(noteId).delete()
    }

    data class GrepHit(
        val noteId: Long,
        val score: Int,
        val snippet: String,
        val title: String,
        val date: String
    )

    /** 对所有 md 文件做逐行关键词匹配（大小写不敏感），返回按得分排序的命中 */
    fun grep(keywords: List<String>, limit: Int = 12): List<GrepHit> {
        if (keywords.isEmpty()) return emptyList()
        val lowered = keywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val results = mutableListOf<GrepHit>()
        val files = notesDir.listFiles { f -> f.extension == "md" } ?: return emptyList()
        for (f in files) {
            val id = f.nameWithoutExtension.toLongOrNull() ?: continue
            val lines = try {
                f.readLines(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            var score = 0
            val hitLines = mutableSetOf<Int>()
            for ((idx, line) in lines.withIndex()) {
                val low = line.lowercase()
                for (kw in lowered) {
                    if (low.contains(kw)) {
                        score += 2
                        hitLines.add(idx)
                    }
                }
            }
            if (score > 0) {
                val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                    ?: f.nameWithoutExtension
                val date = lines.firstOrNull { it.startsWith("- 日期：") }?.removePrefix("- 日期：")?.trim() ?: ""
                val sb = StringBuilder()
                val sorted = hitLines.sorted()
                var prev = -10
                for (i in sorted) {
                    if (i - prev > 1 && sb.isNotEmpty()) sb.append(" … ")
                    sb.appendLine(lines[i].trim()).also { }
                    prev = i
                }
                results.add(GrepHit(id, score, sb.toString().trim().take(500), title, date))
            }
        }
        return results.sortedByDescending { it.score }.take(limit)
    }

    /** 导出全部笔记为 zip，返回写入的文件 */
    fun exportZip(dest: File): Int {
        var count = 0
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            val files = notesDir.listFiles { f -> f.extension == "md" } ?: emptyArray()
            for (f in files.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: 0L }) {
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    fun readNoteIds(): List<Long> =
        (notesDir.listFiles { f -> f.extension == "md" } ?: emptyArray())
            .mapNotNull { it.nameWithoutExtension.toLongOrNull() }
}
