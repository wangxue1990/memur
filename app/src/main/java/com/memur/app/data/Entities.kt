package com.memur.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

@Entity(tableName = "topics")
data class TopicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes", indices = [Index("topicId"), Index("createdAt")])
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long? = null,
    val title: String,
    val rawText: String,
    val cleanedText: String,
    val tags: String = "[]",
    val entitiesJson: String = "{}",
    val audioPath: String? = null,
    val mode: String = "note",
    val important: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun tagList(): List<String> = try {
        val arr = JSONArray(tags)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}

/** 聊天流消息（agent harness 与用户的对话记录） */
@Entity(tableName = "messages", indices = [Index("createdAt")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 见 agent.MessageKind */
    val kind: String,
    val text: String,
    /** kind=info 时为卡片正文；kind=ask 时为选项 JSON 数组 */
    val payload: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders", indices = [Index("triggerAt")])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long? = null,
    val title: String,
    val message: String,
    /** once | daily | weekly | monthly */
    val type: String,
    /** 下一次触发的 epoch 毫秒 */
    val triggerAt: Long,
    val hour: Int,
    val minute: Int,
    /** 1=周一 ... 7=周日，weekly 用 */
    val dayOfWeek: Int? = null,
    /** 1..31，monthly 用 */
    val dayOfMonth: Int? = null,
    val enabled: Boolean = true,
    /** alarm：App 内闹钟响铃（短期 ≤24h） / calendar：写入系统日历日程（长期 >24h） */
    val mode: String = "alarm",
    /** 提醒分组类别（AI 自动判断，如：生活、工作、健康、理财、社交…） */
    val category: String = "通用",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isRepeat: Boolean get() = type != "once"
}
