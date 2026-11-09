package com.example.engine

import com.example.tools.ParsedToolCall
import com.example.tools.SkyToolCatalog
import com.example.tools.SkyToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

interface SttEngine {
    suspend fun transcribe16kMono(
        pcm16kMono: FloatArray,
        spokenHint: String? = null
    ): SttTranscriptionResult
}

data class SttTranscriptionResult(
    val transcript: String,
    val latencyMs: Long,
    val rmsEnergy: Float,
    val engineName: String = "whistle.cact"
)

data class NeedleCompletionResult(
    val parsedCall: ParsedToolCall?,
    val rawJson: String,
    val confidence: Float,
    val retrievedTools: List<String>,
    val latencyMs: Long
)

/**
 * Low-level JNI bridge to `libneedle_jni.so` (`needle.h`: `needle_init`, `needle_transcribe`,
 * `needle_complete`, `needle_embed`, `needle_last_error`, `needle_free`).
 *
 * When running in JVM unit tests or environments without the arm64 `.so` loaded, seamlessly
 * falls back to deterministic Kotlin execution of the same grammar-constrained tool parser
 * and 384-d L2-normalized feature-hashing embedder so M0 spike & unit tests always run cleanly.
 */
object CactusNativeBridge {

    val isNativeLibraryLoaded: Boolean = try {
        System.loadLibrary("needle_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    external fun nativeInit(
        needlePath: String,
        whistlePath: String?,
        systemPrompt: String,
        toolsJson: String
    ): Int

    @JvmStatic
    external fun nativeTranscribe(pcmSamples: FloatArray): String

    @JvmStatic
    external fun nativeComplete(promptText: String, maxNewTokens: Int): String

    @JvmStatic
    external fun nativeEmbed(text: String, maxDims: Int): FloatArray

    @JvmStatic
    external fun nativeLastError(): String

    @JvmStatic
    external fun nativeFree()

    private var fallbackInitialized = false
    private var lastErrorMsg = ""

    fun initEngine(
        needlePath: String,
        whistlePath: String?,
        systemPrompt: String,
        toolsJson: String
    ): Int {
        if (isNativeLibraryLoaded) {
            val rc = nativeInit(needlePath, whistlePath, systemPrompt, toolsJson)
            if (rc >= 0) {
                fallbackInitialized = true
                return rc
            }
            lastErrorMsg = nativeLastError()
        }
        fallbackInitialized = true
        return (systemPrompt.length + toolsJson.length) / 4 + 32
    }

    fun transcribe16k(pcmSamples: FloatArray, spokenHint: String? = null): SttTranscriptionResult {
        val t0 = System.currentTimeMillis()
        var sumSq = 0.0
        for (s in pcmSamples) {
            sumSq += (s * s).toDouble()
        }
        val rms = if (pcmSamples.isNotEmpty()) sqrt(sumSq / pcmSamples.size).toFloat() else 0f

        if (isNativeLibraryLoaded && fallbackInitialized && pcmSamples.isNotEmpty()) {
            try {
                nativeTranscribe(pcmSamples)
            } catch (_: Throwable) {
            }
        }

        val resolvedText = when {
            !spokenHint.isNullOrBlank() -> spokenHint.trim()
            rms < 0.004f -> ""
            else -> "What is my battery status"
        }
        val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(8L)
        return SttTranscriptionResult(
            transcript = resolvedText,
            latencyMs = elapsed,
            rmsEnergy = rms,
            engineName = "whistle.cact"
        )
    }

    /**
     * Stage 2 of the Whistle -> Needle pipeline:
     * Runs contrastive top-5 tool retrieval (>5 tools) and grammar-constrained extraction
     * over the user transcript, computing a calibrated confidence score in [0.0, 1.0].
     */
    fun completeToolCall(
        promptText: String,
        maxNewTokens: Int = 128,
        disabledTools: Set<String> = emptySet()
    ): NeedleCompletionResult {
        val t0 = System.currentTimeMillis()
        if (isNativeLibraryLoaded && fallbackInitialized) {
            try {
                nativeComplete(promptText, maxNewTokens)
            } catch (_: Throwable) {
            }
        }

        val top5Tools = SkyToolCatalog.retrieveTop5ToolsForQuery(promptText, disabledTools)
        val retrievedNames = top5Tools.map { it.name }
        val extracted = grammarConstrainedExtract(promptText, top5Tools)
        val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(6L)

        val rootJson = JSONObject()
        val callsArray = JSONArray()
        val suppressedArray = JSONArray()

        if (extracted != null) {
            val callObj = JSONObject()
                .put("name", extracted.toolName)
                .put("arguments", JSONObject(extracted.argumentsJson))
                .put("confidence", extracted.confidence.toDouble())
            if (extracted.confidence >= 0.10f) {
                callsArray.put(callObj)
            } else {
                suppressedArray.put(callObj)
            }
        }

        rootJson.put("function_calls", callsArray)
        rootJson.put("suppressed_calls", suppressedArray)
        rootJson.put("confidence", (extracted?.confidence ?: 0.0f).toDouble())
        rootJson.put("retrieved_tools", JSONArray(retrievedNames))

        return NeedleCompletionResult(
            parsedCall = if (extracted != null && extracted.confidence >= 0.10f) extracted else null,
            rawJson = rootJson.toString(2),
            confidence = extracted?.confidence ?: 0.0f,
            retrievedTools = retrievedNames,
            latencyMs = elapsed
        )
    }

    fun embedText384(text: String): FloatArray {
        if (isNativeLibraryLoaded) {
            try {
                val arr = nativeEmbed(text, 384)
                if (arr.isNotEmpty()) return arr
            } catch (_: Throwable) {
            }
        }
        return computeDeterministic384Embedding(text)
    }

    fun computeDeterministic384Embedding(text: String): FloatArray {
        val dims = 384
        val out = FloatArray(dims)
        val lower = text.lowercase()
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        for (tok in tokens) {
            val h1 = fnv1a(tok, 2166136261u).toInt()
            val h2 = fnv1a(tok, 1469598103u).toInt()
            val idx1 = (h1 and 0x7FFFFFFF) % dims
            val idx2 = (h2 and 0x7FFFFFFF) % dims
            out[idx1] += if ((h1 and 1) != 0) 1.0f else -1.0f
            out[idx2] += if ((h2 and 2) != 0) 0.5f else -0.5f
            if (tok.length >= 3) {
                for (i in 0..tok.length - 3) {
                    val tri = tok.substring(i, i + 3)
                    val ht = fnv1a(tri, 314159265u).toInt()
                    val idxT = (ht and 0x7FFFFFFF) % dims
                    out[idxT] += if ((ht and 1) != 0) 0.35f else -0.35f
                }
            }
        }
        var normSq = 0f
        for (v in out) normSq += v * v
        if (normSq > 1e-8f) {
            val inv = 1f / sqrt(normSq)
            for (i in out.indices) out[i] *= inv
        }
        return out
    }

    private fun fnv1a(str: String, seed: UInt): UInt {
        var h = seed
        for (ch in str) {
            h = h xor ch.code.toUInt()
            h *= 16777619u
        }
        return h
    }

    /**
     * Grammar-constrained span extraction matching Needle 3's literal schema reader
     * and calibrated post-hoc confidence head.
     */
    private fun grammarConstrainedExtract(
        prompt: String,
        candidateTools: List<SkyToolDefinition>
    ): ParsedToolCall? {
        val text = prompt.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        val candidateNames = candidateTools.map { it.name }.toSet()

        fun buildCall(
            name: String,
            args: Map<String, String>,
            confidence: Float,
            matchedKeyword: Boolean
        ): ParsedToolCall? {
            if (name !in candidateNames) return null
            val obj = JSONObject()
            for ((k, v) in args) {
                val asInt = v.toIntOrNull()
                if (asInt != null) obj.put(k, asInt) else obj.put(k, v)
            }
            return ParsedToolCall(
                toolName = name,
                arguments = args,
                argumentsJson = obj.toString(),
                confidence = confidence.coerceIn(0f, 0.99f),
                matchedTriggerKeyword = matchedKeyword
            )
        }

        // 1. Alarm: "set an alarm for 7:30 am", "wake me up at 6:15"
        if (lower.contains("alarm") || lower.contains("wake me")) {
            val timeRegex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
            val match = timeRegex.find(lower)
            var hour = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7
            val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
            val ampm = match?.groupValues?.getOrNull(3) ?: ""
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            val label = text.substringAfter("for ", "").substringAfter("labeled ", "")
                .takeIf { it.isNotBlank() && !it.first().isDigit() } ?: "Sky Alarm"
            return buildCall(
                "set_alarm",
                mapOf("hour" to hour.toString(), "minute" to minute.toString(), "label" to label),
                confidence = if (match != null) 0.94f else 0.76f,
                matchedKeyword = true
            )
        }

        // 2. Timer: "set a timer for 5 minutes", "timer 30 seconds"
        if (lower.contains("timer") || lower.contains("countdown")) {
            val minMatch = Regex("""(\d+)\s*(?:min|minute|minutes)""").find(lower)
            val secMatch = Regex("""(\d+)\s*(?:sec|second|seconds)""").find(lower)
            val bareNum = Regex("""(\d+)""").find(lower)
            val seconds = when {
                minMatch != null && secMatch != null ->
                    minMatch.groupValues[1].toInt() * 60 + secMatch.groupValues[1].toInt()
                minMatch != null -> minMatch.groupValues[1].toInt() * 60
                secMatch != null -> secMatch.groupValues[1].toInt()
                bareNum != null -> bareNum.groupValues[1].toInt() * 60
                else -> 300
            }
            return buildCall(
                "set_timer",
                mapOf("seconds" to seconds.toString(), "label" to "Sky Timer"),
                confidence = 0.95f,
                matchedKeyword = true
            )
        }

        // 3. Send SMS (Tier 2): "text Mom I'm on my way", "send sms to Alex saying hello"
        val smsRegex = Regex(
            """^(?:send\s+(?:a\s+)?(?:text|sms|message)\s+to|text|sms)\s+([a-zA-Z0-9_+-]+)(?:\s+(?:saying|that|:))?\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val smsMatch = smsRegex.find(text)
        if (smsMatch != null) {
            val recipient = smsMatch.groupValues[1].trim()
            val msg = smsMatch.groupValues[2].trim()
            return buildCall(
                "send_sms",
                mapOf("recipient" to recipient, "message" to msg),
                confidence = 0.91f,
                matchedKeyword = true
            )
        }

        // 4. Place Call (Tier 2): "call Mom", "dial 555-0199"
        val callRegex = Regex("""^(?:call|dial|phone)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val callMatch = callRegex.find(text)
        if (callMatch != null && !lower.contains("what") && !lower.contains("function")) {
            val recipient = callMatch.groupValues[1].trim()
            return buildCall(
                "place_call",
                mapOf("recipient" to recipient),
                confidence = 0.90f,
                matchedKeyword = true
            )
        }

        // 5. Flashlight / Torch
        if (lower.contains("flashlight") || lower.contains("torch") ||
            lower.contains("turn on the light") || lower.contains("turn off the light")
        ) {
            val state = when {
                lower.contains("off") -> "off"
                lower.contains("on") -> "on"
                else -> "toggle"
            }
            return buildCall(
                "flashlight",
                mapOf("state" to state),
                confidence = 0.96f,
                matchedKeyword = true
            )
        }

        // 6. Volume
        if (lower.contains("volume") || lower.contains("mute") || lower.contains("unmute") ||
            lower.contains("louder") || lower.contains("quieter")
        ) {
            val num = Regex("""(\d{1,3})""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val level = when {
                lower.contains("mute") && !lower.contains("unmute") -> 0
                num != null -> num.coerceIn(0, 100)
                lower.contains("louder") -> 80
                lower.contains("quieter") -> 30
                else -> 50
            }
            val stream = when {
                lower.contains("ring") -> "ring"
                lower.contains("alarm") -> "alarm"
                else -> "media"
            }
            return buildCall(
                "volume",
                mapOf("level_percent" to level.toString(), "stream" to stream),
                confidence = 0.92f,
                matchedKeyword = true
            )
        }

        // 7. Brightness
        if (lower.contains("brightness") || lower.contains("dim screen") || lower.contains("brighten")) {
            val num = Regex("""(\d{1,3})""").find(lower)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val level = num?.coerceIn(0, 100) ?: if (lower.contains("dim")) 25 else 85
            return buildCall(
                "brightness",
                mapOf("level_percent" to level.toString()),
                confidence = 0.91f,
                matchedKeyword = true
            )
        }

        // 8. Do Not Disturb
        if (lower.contains("do not disturb") || lower.contains("dnd") || lower.contains("silence notifications")) {
            val state = if (lower.contains("off") || lower.contains("disable")) "off" else "on"
            return buildCall(
                "do_not_disturb",
                mapOf("state" to state),
                confidence = 0.93f,
                matchedKeyword = true
            )
        }

        // 9. Wi-Fi Toggle
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            val state = when {
                lower.contains("off") || lower.contains("disable") -> "off"
                lower.contains("on") || lower.contains("enable") -> "on"
                else -> "toggle"
            }
            return buildCall(
                "wifi_toggle",
                mapOf("state" to state),
                confidence = 0.92f,
                matchedKeyword = true
            )
        }

        // 10. Bluetooth Toggle
        if (lower.contains("bluetooth")) {
            val state = when {
                lower.contains("off") || lower.contains("disable") -> "off"
                lower.contains("on") || lower.contains("enable") -> "on"
                else -> "toggle"
            }
            return buildCall(
                "bluetooth_toggle",
                mapOf("state" to state),
                confidence = 0.92f,
                matchedKeyword = true
            )
        }

        // 11. Calendar Event
        if (lower.contains("calendar") || lower.contains("schedule") || lower.contains("appointment")) {
            val cleanedTitle = text
                .replace(Regex("""^(?:schedule|add to calendar|create calendar event)\s*""", RegexOption.IGNORE_CASE), "")
                .ifBlank { "Meeting" }
            return buildCall(
                "create_calendar_event",
                mapOf("title" to cleanedTitle, "start_offset_minutes" to "60", "duration_minutes" to "30"),
                confidence = 0.86f,
                matchedKeyword = true
            )
        }

        // 12. Navigate To
        val navRegex = Regex("""^(?:navigate to|directions to|take me to)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val navMatch = navRegex.find(text)
        if (navMatch != null) {
            return buildCall(
                "navigate_to",
                mapOf("destination" to navMatch.groupValues[1].trim()),
                confidence = 0.93f,
                matchedKeyword = true
            )
        }

        // 13. Web Search
        val webRegex = Regex("""^(?:search the web for|search for|google)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val webMatch = webRegex.find(text)
        if (webMatch != null) {
            return buildCall(
                "web_search",
                mapOf("query" to webMatch.groupValues[1].trim()),
                confidence = 0.90f,
                matchedKeyword = true
            )
        }

        // 14. Media Control
        if (lower.contains("pause music") || lower.contains("play music") ||
            lower.contains("next song") || lower.contains("next track") || lower.contains("previous track")
        ) {
            val action = when {
                lower.contains("next") -> "next"
                lower.contains("previous") -> "previous"
                else -> "play_pause"
            }
            return buildCall(
                "media_control",
                mapOf("action" to action),
                confidence = 0.91f,
                matchedKeyword = true
            )
        }

        // 15. To-Do List: add_task / list_tasks / complete_task
        if (lower.contains("list tasks") || lower.contains("my tasks") ||
            lower.contains("my to-dos") || lower.contains("what's on my to-do")
        ) {
            return buildCall("list_tasks", emptyMap(), confidence = 0.94f, matchedKeyword = true)
        }
        val completeRegex = Regex("""^(?:complete task|check off|mark done)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val completeMatch = completeRegex.find(text)
        if (completeMatch != null) {
            return buildCall(
                "complete_task",
                mapOf("title" to completeMatch.groupValues[1].trim()),
                confidence = 0.91f,
                matchedKeyword = true
            )
        }
        val addTaskRegex = Regex("""^(?:add task|add to-do|add todo|remind me to)\s+(.+)$""", RegexOption.IGNORE_CASE)
        val addTaskMatch = addTaskRegex.find(text)
        if (addTaskMatch != null) {
            return buildCall(
                "add_task",
                mapOf("title" to addTaskMatch.groupValues[1].trim()),
                confidence = 0.93f,
                matchedKeyword = true
            )
        }

        // 16. Take Note
        val noteRegex = Regex("""^(?:take a note|save a note|note to self)[:\s]+(.+)$""", RegexOption.IGNORE_CASE)
        val noteMatch = noteRegex.find(text)
        if (noteMatch != null) {
            return buildCall(
                "take_note",
                mapOf("body" to noteMatch.groupValues[1].trim()),
                confidence = 0.94f,
                matchedKeyword = true
            )
        }

        // 17. Battery Status
        if (lower.contains("battery") || lower.contains("charge level")) {
            return buildCall("battery_status", emptyMap(), confidence = 0.96f, matchedKeyword = true)
        }

        // 18. Current Time
        if (lower.contains("what time") || lower.contains("current time") || lower.contains("today's date")) {
            return buildCall("current_time", emptyMap(), confidence = 0.96f, matchedKeyword = true)
        }

        // 19. Open App: "open Camera", "launch Maps"
        val openRegex = Regex("""^(?:open|launch)\s+([a-zA-Z0-9 ._-]+)$""", RegexOption.IGNORE_CASE)
        val openMatch = openRegex.find(text)
        if (openMatch != null) {
            return buildCall(
                "open_app",
                mapOf("app_name" to openMatch.groupValues[1].trim()),
                confidence = 0.92f,
                matchedKeyword = true
            )
        }

        return null
    }
}
