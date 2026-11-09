package com.example.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import android.content.Context
import kotlinx.coroutines.flow.Flow

@Dao
interface SkyDao {

    // --- Chat Sessions & Messages ---
    @Query("SELECT * FROM chat_sessions ORDER BY isPinned DESC, updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE title LIKE '%' || :query || '%' ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun searchSessionsByKeyword(query: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesSnapshot(sessionId: Long): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    // --- Separate Embeddings Table (needle_embed) ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmbedding(embedding: EmbeddingEntity): Long

    @Query("SELECT * FROM embeddings ORDER BY createdAt DESC LIMIT 500")
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    @Query("DELETE FROM embeddings WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteEmbeddingForSource(sourceType: String, sourceId: Long)

    @Query("DELETE FROM embeddings WHERE sessionId = :sessionId")
    suspend fun deleteEmbeddingsForSession(sessionId: Long)

    // --- To-Do List ---
    @Query("SELECT * FROM todo_items ORDER BY isCompleted ASC, createdAt DESC")
    fun observeTodos(): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_items ORDER BY isCompleted ASC, createdAt DESC")
    suspend fun getTodosSnapshot(): List<TodoItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodo(todo: TodoItemEntity): Long

    @Update
    suspend fun updateTodo(todo: TodoItemEntity)

    @Query("DELETE FROM todo_items WHERE id = :todoId")
    suspend fun deleteTodoById(todoId: Long)

    // --- Notes ---
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun getNotesSnapshot(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: Long)

    // --- Task Execution History Logs ---
    @Query("SELECT * FROM task_execution_logs ORDER BY timestamp DESC LIMIT 200")
    fun observeTaskLogs(): Flow<List<TaskExecutionLogEntity>>

    @Query("SELECT * FROM task_execution_logs WHERE id = :logId LIMIT 1")
    suspend fun getTaskLogById(logId: Long): TaskExecutionLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTaskLog(log: TaskExecutionLogEntity): Long

    @Update
    suspend fun updateTaskLog(log: TaskExecutionLogEntity)

    @Query("DELETE FROM task_execution_logs")
    suspend fun clearTaskLogs()

    // --- Installed Models & Active Slots ---
    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    fun observeInstalledModels(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    suspend fun getInstalledModelsSnapshot(): List<InstalledModelEntity>

    @Query("SELECT * FROM installed_models WHERE roleSlot = :roleSlot AND isActiveDefault = 1 LIMIT 1")
    suspend fun getActiveModelForRole(roleSlot: String): InstalledModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstalledModel(model: InstalledModelEntity)

    @Query("UPDATE installed_models SET isActiveDefault = 0 WHERE roleSlot = :roleSlot")
    suspend fun clearActiveDefaultForRole(roleSlot: String)

    @Query("UPDATE installed_models SET isActiveDefault = 1 WHERE modelId = :modelId")
    suspend fun setActiveDefaultModel(modelId: String)

    @Query("DELETE FROM installed_models WHERE modelId = :modelId")
    suspend fun deleteInstalledModel(modelId: String)

    // --- Download Tasks ---
    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC")
    fun observeDownloadTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getDownloadTask(taskId: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownloadTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE taskId = :taskId")
    suspend fun deleteDownloadTask(taskId: String)

    // --- Wipe All Data ---
    @Query("DELETE FROM chat_sessions")
    suspend fun wipeAllSessions()

    @Query("DELETE FROM chat_messages")
    suspend fun wipeAllMessages()

    @Query("DELETE FROM embeddings")
    suspend fun wipeAllEmbeddings()

    @Query("DELETE FROM todo_items")
    suspend fun wipeAllTodos()

    @Query("DELETE FROM notes")
    suspend fun wipeAllNotes()

    @Query("DELETE FROM installed_models")
    suspend fun wipeAllInstalledModels()

    @Query("DELETE FROM download_tasks")
    suspend fun wipeAllDownloadTasks()
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        EmbeddingEntity::class,
        TodoItemEntity::class,
        NoteEntity::class,
        TaskExecutionLogEntity::class,
        InstalledModelEntity::class,
        DownloadTaskEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SkyDatabase : RoomDatabase() {
    abstract fun skyDao(): SkyDao

    companion object {
        @Volatile
        private var INSTANCE: SkyDatabase? = null

        fun getInstance(context: Context): SkyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SkyDatabase::class.java,
                    "sky_assistant.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
