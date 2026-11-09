package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

enum class ModelRoleSlot(val id: String, val displayName: String) {
    TASK("task", "Task Model (.cact)"),
    SPEECH("speech", "Speech STT (.cact)"),
    REPLY("reply", "Reply Chat Model (GGUF)")
}

enum class ToolRiskTier(val id: String, val displayName: String) {
    TIER_1("tier_1", "Tier 1 · Instant"),
    TIER_2("tier_2", "Tier 2 · 3s Countdown")
}

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    indices = [Index("sessionId")]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val role: String, // "user", "assistant", "tool"
    val content: String,
    val thinkContent: String? = null,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolResultSummary: String? = null,
    val taskLogId: Long? = null,
    val canUndo: Boolean = false,
    val isUndone: Boolean = false,
    val tokensPerSec: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Separate table for semantic embeddings produced by `needle_embed`
 * so vector indexes are decoupled from message rows and track embedding model ID/version.
 */
@Entity(
    tableName = "embeddings",
    indices = [Index(value = ["sourceType", "sourceId"], unique = true)]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceType: String, // "MESSAGE" or "NOTE"
    val sourceId: Long,
    val sessionId: Long? = null,
    val textPreview: String,
    val modelId: String = "needle3.cact",
    val modelVersion: Int = 3,
    val dimension: Int = 384,
    val vectorBlob: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toFloatArray(): FloatArray {
        val count = vectorBlob.size / 4
        val out = FloatArray(count)
        val buf = ByteBuffer.wrap(vectorBlob).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            out[i] = buf.float
        }
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingEntity) return false
        return id == other.id &&
            sourceType == other.sourceType &&
            sourceId == other.sourceId &&
            modelId == other.modelId &&
            modelVersion == other.modelVersion &&
            vectorBlob.contentEquals(other.vectorBlob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sourceType.hashCode()
        result = 31 * result + sourceId.hashCode()
        result = 31 * result + vectorBlob.contentHashCode()
        return result
    }

    companion object {
        fun fromFloatArray(
            sourceType: String,
            sourceId: Long,
            sessionId: Long?,
            textPreview: String,
            modelId: String,
            modelVersion: Int,
            vector: FloatArray
        ): EmbeddingEntity {
            val buf = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in vector) {
                buf.putFloat(v)
            }
            return EmbeddingEntity(
                sourceType = sourceType,
                sourceId = sourceId,
                sessionId = sessionId,
                textPreview = textPreview.take(240),
                modelId = modelId,
                modelVersion = modelVersion,
                dimension = vector.size,
                vectorBlob = buf.array()
            )
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            val n = minOf(a.size, b.size)
            if (n == 0) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in 0 until n) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            val denom = sqrt(na * nb)
            return if (denom > 1e-8f) (dot / denom) else 0f
        }
    }
}

@Entity(tableName = "todo_items")
data class TodoItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val dueNote: String? = null,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_execution_logs")
data class TaskExecutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transcript: String,
    val toolName: String,
    val argumentsJson: String,
    val riskTier: String, // "tier_1" or "tier_2"
    val confidence: Float,
    val resultSummary: String,
    val executionPathUsed: String, // e.g. "Direct API", "Accessibility QS", "Settings.ACTION_INTERNET_CONNECTIVITY"
    val sttLatencyMs: Long,
    val needleLatencyMs: Long,
    val executionLatencyMs: Long,
    val isReversible: Boolean,
    val isUndone: Boolean = false,
    val undoPayloadJson: String? = null,
    val shortcutActionLabel: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val modelId: String, // e.g. "Cactus-Compute/needle3:needle3.cact"
    val repoId: String,
    val fileName: String,
    val displayName: String,
    val roleSlot: String, // "task", "speech", "reply"
    val localFilePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val quantization: String,
    val architecture: String,
    val contextLength: Int,
    val chatTemplate: String,
    val isActiveDefault: Boolean = false,
    val installedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val taskId: String, // repoId + "/" + fileName
    val repoId: String,
    val fileName: String,
    val displayName: String,
    val roleSlot: String,
    val downloadUrl: String,
    val targetFilePath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val expectedSha256: String? = null,
    val status: String, // "QUEUED", "DOWNLOADING", "PAUSED", "VERIFYING", "COMPLETED", "FAILED", "GATED_403"
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
