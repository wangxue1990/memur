package com.memur.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: Long): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE topicId = :topicId ORDER BY createdAt DESC")
    fun byTopic(topicId: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun all(): List<NoteEntity>

    @Query("UPDATE notes SET topicId = :topicId, updatedAt = :now WHERE id = :id")
    suspend fun moveTopic(id: Long, topicId: Long?, now: Long)

    @Query("UPDATE notes SET important = :important, updatedAt = :now WHERE id = :id")
    suspend fun setImportant(id: Long, important: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM notes WHERE topicId = :topicId")
    suspend fun countByTopic(topicId: Long): Int
}

@Dao
interface TopicDao {
    @Insert
    suspend fun insert(topic: TopicEntity): Long

    @Update
    suspend fun update(topic: TopicEntity)

    @Delete
    suspend fun delete(topic: TopicEntity)

    @Query("SELECT * FROM topics ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics")
    suspend fun all(): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun byId(id: Long): TopicEntity?

    @Query("SELECT * FROM topics WHERE title = :title LIMIT 1")
    suspend fun byTitle(title: String): TopicEntity?
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    /** 最近 500 条，按时间正序（UI 聊天流直接观察） */
    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT 500) ORDER BY id ASC")
    fun observeRecent(): Flow<List<MessageEntity>>

    /** 一次性取最近 N 条（按时间正序），供重启后回灌 LLM 上下文 */
    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY id DESC LIMIT :limit) ORDER BY id ASC")
    suspend fun recent(limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders ORDER BY enabled DESC, triggerAt ASC")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE enabled = 1")
    suspend fun allEnabled(): List<ReminderEntity>

    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    suspend fun all(): List<ReminderEntity>

    @Query("UPDATE reminders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
