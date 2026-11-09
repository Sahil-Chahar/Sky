package com.example.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Android TextToSpeech wrapper supporting sentence-by-sentence queued speech
 * during LLM token streaming, voice/rate configuration, and rhythmic amplitude
 * pulse output for Siri Glow / Siri Orb speaking animations.
 */
class SkyTtsEngine(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _ttsPulse = MutableStateFlow(0f)
    val ttsPulse: StateFlow<Float> = _ttsPulse.asStateFlow()

    private val sentenceBuffer = StringBuilder()

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isReady = true
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                            _ttsPulse.value = 0.75f
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            _ttsPulse.value = 0f
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            _ttsPulse.value = 0f
                        }
                    })
                }
            }
        } catch (_: Throwable) {
            isReady = false
        }
    }

    fun speakImmediate(
        text: String,
        speechRate: Float = 1.05f,
        pitch: Float = 1.0f,
        enabled: Boolean = true
    ) {
        if (!enabled || !isReady || text.isBlank()) return
        sentenceBuffer.clear()
        val clean = stripMarkdownForSpeech(text)
        tts?.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun feedStreamingToken(
        tokenDelta: String,
        speechRate: Float = 1.05f,
        pitch: Float = 1.0f,
        enabled: Boolean = true
    ) {
        if (!enabled || !isReady || tokenDelta.isEmpty()) return
        sentenceBuffer.append(tokenDelta)
        val buffered = sentenceBuffer.toString()
        val boundaryIdx = buffered.indexOfAny(charArrayOf('.', '!', '?', '\n'))
        if (boundaryIdx != -1 && boundaryIdx >= 12) {
            val sentence = buffered.substring(0, boundaryIdx + 1).trim()
            sentenceBuffer.delete(0, boundaryIdx + 1)
            val clean = stripMarkdownForSpeech(sentence)
            if (clean.isNotBlank()) {
                tts?.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
                tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
                tts?.speak(clean, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
            }
        }
    }

    fun flushStreamingSentences(
        speechRate: Float = 1.05f,
        pitch: Float = 1.0f,
        enabled: Boolean = true
    ) {
        if (!enabled || !isReady) {
            sentenceBuffer.clear()
            return
        }
        val rem = stripMarkdownForSpeech(sentenceBuffer.toString().trim())
        sentenceBuffer.clear()
        if (rem.isNotBlank()) {
            tts?.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
            tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
            tts?.speak(rem, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    fun stop() {
        sentenceBuffer.clear()
        _isSpeaking.value = false
        _ttsPulse.value = 0f
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
    }

    private fun stripMarkdownForSpeech(input: String): String {
        return input
            .replace(Regex("""```[\s\S]*?```"""), " code block ")
            .replace(Regex("""[*_`#~>-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
