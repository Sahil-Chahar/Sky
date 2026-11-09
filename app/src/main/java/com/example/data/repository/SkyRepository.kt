package com.example.data.repository

import com.example.data.local.ChatMessageEntity
import com.example.data.local.ChatSessionEntity
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.EmbeddingEntity
import com.example.data.local.InstalledModelEntity
import com.example.data.local.ModelRoleSlot
import com.example.data.local.NoteEntity
import com.example.data.local.SkyDao
import com.example.data.local.TaskExecutionLogEntity
import com.example.data.local.TodoItemEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

data class SemanticSearchResult(
    val sessionId: Long?,
    val sourceType: String,
    val sourceId: Long,
    val textPreview: String,
    val score: Float
)

/**
 * Central repository abstracting Room persistence and Ghost Mode privacy rules.
 * When Ghost Mode (`isGhostMode = true`) is active:
 *  - Chat sessions and messages are NOT persisted to Room.
 *  - Semantic embeddings (`EmbeddingEntity`) are NOT stored.
 *  - Task execution logs (`TaskExecutionLogEntity`) are skipped completely.
 */
class SkyRepository(
    private val dao: SkyDao,
    private val modelsDir: File
) {
    val sessionsFlow: Flow<List<ChatSessionEntity>> = dao.observeSessions()
    val todosFlow: Flow<List<TodoItemEntity>> = dao.observeTodos()
    val notesFlow: Flow<List<NoteEntity>> = dao.observeNotes()
    val taskLogsFlow: Flow<List<TaskExecutionLogEntity>> = dao.observeTaskLogs()
    val installedModelsFlow: Flow<List<InstalledModelEntity>> = dao.observeInstalledModels()
    val downloadTasksFlow: Flow<List<DownloadTaskEntity>> = dao.observeDownloadTasks()

    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        dao.observeMessages(sessionId)

    suspend fun getMessagesSnapshot(sessionId: Long): List<ChatMessageEntity> =
        dao.getMessagesSnapshot(sessionId)

    suspend fun createOrUpdateSession(
        sessionId: Long?,
        title: String,
        isGhostMode: Boolean
    ): Long {
        if (isGhostMode) return -1L
        val now = System.currentTimeMillis()
        if (sessionId != null && sessionId > 0L) {
            val existing = dao.getSessionById(sessionId)
            if (existing != null) {
                dao.updateSession(existing.copy(updatedAt = now))
                return existing.id
            }
        }
        return dao.insertSession(
            ChatSessionEntity(
                title = title.take(64).ifBlank { "New Conversation" },
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun saveMessage(
        message: ChatMessageEntity,
        isGhostMode: Boolean
    ): Long {
        if (isGhostMode || message.sessionId <= 0L) return -1L
        val id = dao.insertMessage(message)
        dao.getSessionById(message.sessionId)?.let { session ->
            dao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
        }
        return id
    }

    suspend fun updateMessage(message: ChatMessageEntity, isGhostMode: Boolean) {
        if (isGhostMode || message.id <= 0L) return
        dao.updateMessage(message)
    }

    suspend fun deleteMessage(messageId: Long) {
        dao.deleteMessageById(messageId)
        dao.deleteEmbeddingForSource("MESSAGE", messageId)
    }

    suspend fun renameSession(sessionId: Long, newTitle: String) {
        val existing = dao.getSessionById(sessionId) ?: return
        dao.updateSession(existing.copy(title = newTitle.trim().ifBlank { existing.title }))
    }

    suspend fun togglePinSession(sessionId: Long) {
        val existing = dao.getSessionById(sessionId) ?: return
        dao.updateSession(existing.copy(isPinned = !existing.isPinned))
    }

    suspend fun deleteSession(sessionId: Long) {
        dao.deleteMessagesForSession(sessionId)
        dao.deleteEmbeddingsForSession(sessionId)
        dao.deleteSession(sessionId)
    }

    // --- Semantic Embeddings (`needle_embed`) ---
    suspend fun saveEmbedding(
        sourceType: String,
        sourceId: Long,
        sessionId: Long?,
        text: String,
        vector: FloatArray,
        isGhostMode: Boolean
    ) {
        if (isGhostMode || sourceId <= 0L || vector.isEmpty()) return
        val entity = EmbeddingEntity.fromFloatArray(
            sourceType = sourceType,
            sourceId = sourceId,
            sessionId = sessionId,
            textPreview = text,
            modelId = "needle3.cact",
            modelVersion = 3,
            vector = vector
        )
        dao.upsertEmbedding(entity)
    }

    suspend fun semanticSearch(
        queryVector: FloatArray,
        queryText: String,
        topK: Int = 10
    ): List<SemanticSearchResult> {
        val all = dao.getAllEmbeddings()
        val lowerQuery = queryText.trim().lowercase()
        return all.map { emb ->
            val vec = emb.toFloatArray()
            val cos = EmbeddingEntity.cosineSimilarity(queryVector, vec)
            val keywordBoost = if (lowerQuery.isNotEmpty() &&
                emb.textPreview.lowercase().contains(lowerQuery)
            ) 0.25f else 0f
            SemanticSearchResult(
                sessionId = emb.sessionId,
                sourceType = emb.sourceType,
                sourceId = emb.sourceId,
                textPreview = emb.textPreview,
                score = (cos + keywordBoost).coerceAtMost(1.0f)
            )
        }
            .filter { it.score >= 0.18f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    // --- To-Dos & Notes ---
    suspend fun addTodo(title: String, dueNote: String? = null): Long {
        return dao.insertTodo(TodoItemEntity(title = title.trim(), dueNote = dueNote))
    }

    suspend fun getTodosSnapshot(): List<TodoItemEntity> = dao.getTodosSnapshot()

    suspend fun setTodoCompleted(todoId: Long, completed: Boolean) {
        val list = dao.getTodosSnapshot()
        val target = list.find { it.id == todoId } ?: return
        dao.updateTodo(
            target.copy(
                isCompleted = completed,
                completedAt = if (completed) System.currentTimeMillis() else null
            )
        )
    }

    suspend fun deleteTodo(todoId: Long) {
        dao.deleteTodoById(todoId)
    }

    suspend fun addNote(title: String, body: String): Long {
        return dao.insertNote(NoteEntity(title = title.trim(), body = body.trim()))
    }

    suspend fun deleteNote(noteId: Long) {
        dao.deleteNoteById(noteId)
        dao.deleteEmbeddingForSource("NOTE", noteId)
    }

    // --- Task Execution Logs (Skipped in Ghost Mode) ---
    suspend fun recordTaskLog(
        log: TaskExecutionLogEntity,
        isGhostMode: Boolean
    ): Long {
        if (isGhostMode) return -1L
        return dao.insertTaskLog(log)
    }

    suspend fun getTaskLogById(logId: Long): TaskExecutionLogEntity? =
        dao.getTaskLogById(logId)

    suspend fun markTaskLogUndone(logId: Long) {
        val existing = dao.getTaskLogById(logId) ?: return
        dao.updateTaskLog(existing.copy(isUndone = true))
    }

    // --- Models & Downloads ---
    suspend fun getInstalledModelsSnapshot(): List<InstalledModelEntity> =
        dao.getInstalledModelsSnapshot()

    suspend fun getActiveModelForRole(slot: ModelRoleSlot): InstalledModelEntity? =
        dao.getActiveModelForRole(slot.id)

    suspend fun registerInstalledModel(model: InstalledModelEntity, makeDefault: Boolean = true) {
        if (makeDefault) {
            dao.clearActiveDefaultForRole(model.roleSlot)
        }
        dao.upsertInstalledModel(model.copy(isActiveDefault = makeDefault))
    }

    suspend fun setDefaultModelForRole(modelId: String, slot: ModelRoleSlot) {
        dao.clearActiveDefaultForRole(slot.id)
        dao.setActiveDefaultModel(modelId)
    }

    suspend fun deleteInstalledModel(model: InstalledModelEntity) {
        try {
            val f = File(model.localFilePath)
            if (f.exists()) f.delete()
        } catch (_: Throwable) {
        }
        dao.deleteInstalledModel(model.modelId)
    }

    suspend fun upsertDownloadTask(task: DownloadTaskEntity) {
        dao.upsertDownloadTask(task)
    }

    suspend fun getDownloadTask(taskId: String): DownloadTaskEntity? =
        dao.getDownloadTask(taskId)

    suspend fun deleteDownloadTask(taskId: String) {
        dao.deleteDownloadTask(taskId)
    }

    suspend fun wipeAllData() {
        dao.wipeAllMessages()
        dao.wipeAllEmbeddings()
        dao.wipeAllSessions()
        dao.wipeAllTodos()
        dao.wipeAllNotes()
        dao.clearTaskLogs()
        dao.wipeAllDownloadTasks()
        dao.wipeAllInstalledModels()
        try {
            modelsDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Throwable) {
        }
    }
}
