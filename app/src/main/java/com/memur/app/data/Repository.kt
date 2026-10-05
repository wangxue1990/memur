package com.memur.app.data

import kotlinx.coroutines.flow.Flow

class Repository(
    private val db: AppDatabase,
    private val markdown: MarkdownStore
) {
    val noteDao = db.noteDao()
    val topicDao = db.topicDao()
    val reminderDao = db.reminderDao()

    // ---------- 笔记 ----------

    fun recentNotes(limit: Int = 30): Flow<List<NoteEntity>> = noteDao.recent(limit)
    fun noteFlow(id: Long): Flow<NoteEntity?> = noteDao.observeById(id)
    fun notesOfTopic(topicId: Long): Flow<List<NoteEntity>> = noteDao.byTopic(topicId)

    suspend fun createNote(
        title: String,
        rawText: String,
        cleanedText: String,
        tags: List<String>,
        topicName: String,
        entitiesJson: String,
        audioPath: String?,
        mode: String
    ): NoteEntity {
        val topicId = resolveTopic(topicName)
        val note = NoteEntity(
            topicId = topicId,
            title = title.ifBlank { cleanedText.take(12) },
            rawText = rawText,
            cleanedText = cleanedText,
            tags = org.json.JSONArray(tags).toString(),
            entitiesJson = entitiesJson,
            audioPath = audioPath,
            mode = mode
        )
        val id = noteDao.insert(note)
        val saved = note.copy(id = id)
        markdown.write(saved, topicTitle(topicId))
        if (topicId != null) touchTopic(topicId)
        return saved
    }

    suspend fun updateNote(
        note: NoteEntity,
        title: String,
        cleanedText: String,
        tags: List<String>,
        topicName: String
    ) {
        val newTopicId = resolveTopic(topicName)
        val updated = note.copy(
            title = title,
            cleanedText = cleanedText,
            tags = org.json.JSONArray(tags).toString(),
            topicId = newTopicId,
            updatedAt = System.currentTimeMillis()
        )
        noteDao.update(updated)
        markdown.write(updated, topicTitle(newTopicId))
        if (newTopicId != null) touchTopic(newTopicId)
    }

    suspend fun deleteNote(note: NoteEntity) {
        noteDao.delete(note)
        markdown.delete(note.id)
        note.audioPath?.let { p ->
            val f = java.io.File(p)
            if (f.exists()) f.delete()
        }
    }

    suspend fun setImportant(note: NoteEntity, important: Boolean) {
        noteDao.setImportant(note.id, important, System.currentTimeMillis())
        val updated = noteDao.byId(note.id) ?: return
        markdown.write(updated, topicTitle(updated.topicId))
    }

    // ---------- 主题（类 Wiki） ----------

    fun topicsFlow(): Flow<List<TopicEntity>> = topicDao.observeAll()

    suspend fun allTopics(): List<TopicEntity> = topicDao.all()

    /** 归并：同名/近似主题直接复用，否则新建 */
    private suspend fun resolveTopic(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == "未分类") return null
        val norm = normalize(trimmed)
        val existing = topicDao.all()
        existing.firstOrNull { normalize(it.title) == norm }?.let { return it.id }
        return topicDao.insert(TopicEntity(title = trimmed))
    }

    private fun normalize(s: String) = s.trim().lowercase().replace(" ", "")

    suspend fun topicTitle(id: Long?): String? =
        id?.let { topicDao.byId(it)?.title }

    private suspend fun touchTopic(id: Long) {
        val t = topicDao.byId(id) ?: return
        topicDao.update(t.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun renameTopic(topic: TopicEntity, newTitle: String) {
        topicDao.update(topic.copy(title = newTitle.trim(), updatedAt = System.currentTimeMillis()))
        rewriteTopicNotes(topic.id)
    }

    suspend fun mergeTopic(from: TopicEntity, into: TopicEntity) {
        val notes = noteDao.all().filter { it.topicId == from.id }
        val now = System.currentTimeMillis()
        for (n in notes) noteDao.moveTopic(n.id, into.id, now)
        topicDao.delete(from)
        rewriteTopicNotes(into.id)
    }

    suspend fun deleteTopic(topic: TopicEntity) {
        val notes = noteDao.all().filter { it.topicId == topic.id }
        val now = System.currentTimeMillis()
        for (n in notes) noteDao.moveTopic(n.id, null, now)
        topicDao.delete(topic)
        for (n in notes) {
            val updated = noteDao.byId(n.id) ?: continue
            markdown.write(updated, null)
        }
    }

    private suspend fun rewriteTopicNotes(topicId: Long) {
        val title = topicTitle(topicId)
        for (n in noteDao.all().filter { it.topicId == topicId }) {
            val fresh = noteDao.byId(n.id) ?: continue
            markdown.write(fresh, title)
        }
    }

    // ---------- 提醒 ----------

    fun remindersFlow(): Flow<List<ReminderEntity>> = reminderDao.observeAll()

    val markdownStore: MarkdownStore get() = markdown
}
