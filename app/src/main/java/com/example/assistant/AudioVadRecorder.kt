package com.example.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 16 kHz Mono PCM AudioRecord + Energy-based Voice Activity Detection (VAD).
 *
 * - Default silence endpoint: 650 ms (configurable 600-1000 ms) after speech onset.
 * - Hard capture cap: 15 seconds.
 * - Emits live RMS energy in [0.0, 1.0] to drive Siri Glow stroke width / Siri Orb expansion.
 * - Plays subtle two-tone earcon on wake and finish.
 */
object AudioVadRecorder {

    const val SAMPLE_RATE_HZ = 16000
    const val MAX_CAPTURE_SECONDS = 15

    fun playEarcon(isWake: Boolean) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            if (isWake) {
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            } else {
                toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 110)
            }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun recordWithVad(
        context: Context,
        silenceEndpointMs: Int = 650,
        onRmsUpdate: (Float) -> Unit
    ): FloatArray = withContext(Dispatchers.IO) {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPerm) {
            // Smooth visual pulse if mic permission is not yet granted
            for (i in 0 until 12) {
                onRmsUpdate(0.18f + (i % 3) * 0.08f)
                delay(80L)
            }
            onRmsUpdate(0f)
            return@withContext FloatArray(0)
        }

        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufBytes * 2
            )
        } catch (_: Throwable) {
            return@withContext FloatArray(0)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext FloatArray(0)
        }

        val frameSamples = 800 // 50 ms frame at 16 kHz
        val shortBuf = ShortArray(frameSamples)
        val capturedFloats = FloatArray(SAMPLE_RATE_HZ * MAX_CAPTURE_SECONDS)
        var totalSamples = 0
        var speechDetected = false
        var trailingSilenceMs = 0
        var elapsedMs = 0
        val speechThresholdRms = 0.022f

        try {
            recorder.startRecording()
            while (elapsedMs < MAX_CAPTURE_SECONDS * 1000) {
                val read = recorder.read(shortBuf, 0, frameSamples)
                if (read <= 0) {
                    delay(25L)
                    elapsedMs += 25
                    continue
                }

                var sumSq = 0.0
                for (i in 0 until read) {
                    val norm = (shortBuf[i] / 32768.0f).coerceIn(-1f, 1f)
                    if (totalSamples < capturedFloats.size) {
                        capturedFloats[totalSamples++] = norm
                    }
                    sumSq += (norm * norm).toDouble()
                }
                val rms = sqrt(sumSq / read).toFloat()
                onRmsUpdate(rms)

                val frameMs = (read * 1000) / SAMPLE_RATE_HZ
                elapsedMs += frameMs

                if (rms >= speechThresholdRms) {
                    speechDetected = true
                    trailingSilenceMs = 0
                } else if (speechDetected) {
                    trailingSilenceMs += frameMs
                    if (trailingSilenceMs >= silenceEndpointMs) {
                        break
                    }
                } else if (elapsedMs >= 3200) {
                    // No speech started within 3.2s
                    break
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        } finally {
            onRmsUpdate(0f)
            try {
                recorder.stop()
            } catch (_: Throwable) {
            }
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
        }

        capturedFloats.copyOfRange(0, totalSamples)
    }
}
