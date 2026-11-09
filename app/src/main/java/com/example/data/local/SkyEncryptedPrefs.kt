package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.ui.components.SiriWakeStyle
import com.example.ui.theme.SkyThemeMode
import com.example.ui.theme.SkyThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SkySettingsState(
    val themePreset: SkyThemePreset = SkyThemePreset.SKY,
    val themeMode: SkyThemeMode = SkyThemeMode.SYSTEM,
    val siriWakeStyle: SiriWakeStyle = SiriWakeStyle.SIRI_GLOW,
    val vadSilenceMs: Int = 650,
    val autoFollowUpListening: Boolean = false,
    val ttsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.05f,
    val ttsPitch: Float = 1.0f,
    val wifiOnlyDownloads: Boolean = false,
    val llmTemperature: Float = 0.7f,
    val llmTopP: Float = 0.9f,
    val llmTopK: Int = 40,
    val llmMinP: Float = 0.05f,
    val llmRepeatPenalty: Float = 1.1f,
    val llmMaxTokens: Int = 512,
    val llmContextSize: Int = 4096,
    val llmThreads: Int = 4,
    val llmExperimentalGpu: Boolean = false,
    val systemPrompt: String = "You are Sky, a calm, concise, privacy-first on-device AI assistant running locally on Android.",
    val hfOAuthClientId: String = "",
    val hasHfToken: Boolean = false,
    val hfUsername: String = "",
    val setupWizardCompleted: Boolean = false,
    val disabledTools: Set<String> = emptySet(),
    val instantTier2Tools: Set<String> = emptySet() // Per-tool "Run instantly" override for Tier 2
)

/**
 * Hardware-backed Android Keystore AES-GCM encrypted storage for Hugging Face
 * OAuth / Personal Access Tokens and user preferences.
 * Never logs tokens or sensitive values.
 */
class SkyEncryptedPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sky_secure_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<SkySettingsState> = _settings.asStateFlow()

    private fun getOrCreateAesKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    private fun encryptSecret(plainText: String): String {
        if (plainText.isBlank()) return ""
        val key = getOrCreateAesKey() ?: return Base64.encodeToString(
            plainText.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    private fun decryptSecret(encoded: String): String {
        if (encoded.isBlank()) return ""
        val raw = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: Throwable) {
            return ""
        }
        val key = getOrCreateAesKey() ?: return String(raw, Charsets.UTF_8)
        return try {
            val ivLen = raw[0].toInt() and 0xFF
            if (ivLen <= 0 || raw.size <= 1 + ivLen) return String(raw, Charsets.UTF_8)
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val cipherBytes = raw.copyOfRange(1 + ivLen, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Throwable) {
            String(raw, Charsets.UTF_8)
        }
    }

    fun saveHfToken(token: String, username: String = "") {
        val trimmed = token.trim()
        prefs.edit()
            .putString(KEY_HF_TOKEN_ENC, if (trimmed.isEmpty()) "" else encryptSecret(trimmed))
            .putString(KEY_HF_USERNAME, username)
            .apply()
        refreshState()
    }

    fun getHfToken(): String {
        val enc = prefs.getString(KEY_HF_TOKEN_ENC, "") ?: ""
        return decryptSecret(enc)
    }

    fun clearHfToken() {
        prefs.edit()
            .remove(KEY_HF_TOKEN_ENC)
            .remove(KEY_HF_USERNAME)
            .apply()
        refreshState()
    }

    fun savePkceVerifier(verifier: String, state: String) {
        prefs.edit()
            .putString(KEY_PKCE_VERIFIER, encryptSecret(verifier))
            .putString(KEY_PKCE_STATE, state)
            .apply()
    }

    fun consumePkceVerifier(expectedState: String): String? {
        val savedState = prefs.getString(KEY_PKCE_STATE, null)
        val encVerifier = prefs.getString(KEY_PKCE_VERIFIER, null)
        prefs.edit().remove(KEY_PKCE_STATE).remove(KEY_PKCE_VERIFIER).apply()
        if (savedState == null || savedState != expectedState || encVerifier.isNullOrBlank()) {
            return null
        }
        return decryptSecret(encVerifier)
    }

    fun updateSettings(transform: (SkySettingsState) -> SkySettingsState) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putString("theme_preset", updated.themePreset.id)
            .putString("theme_mode", updated.themeMode.id)
            .putString("siri_wake_style", updated.siriWakeStyle.id)
            .putInt("vad_silence_ms", updated.vadSilenceMs)
            .putBoolean("auto_follow_up", updated.autoFollowUpListening)
            .putBoolean("tts_enabled", updated.ttsEnabled)
            .putFloat("tts_rate", updated.ttsSpeechRate)
            .putFloat("tts_pitch", updated.ttsPitch)
            .putBoolean("wifi_only_dl", updated.wifiOnlyDownloads)
            .putFloat("llm_temp", updated.llmTemperature)
            .putFloat("llm_top_p", updated.llmTopP)
            .putInt("llm_top_k", updated.llmTopK)
            .putFloat("llm_min_p", updated.llmMinP)
            .putFloat("llm_repeat_pen", updated.llmRepeatPenalty)
            .putInt("llm_max_tokens", updated.llmMaxTokens)
            .putInt("llm_ctx_size", updated.llmContextSize)
            .putInt("llm_threads", updated.llmThreads)
            .putBoolean("llm_gpu", updated.llmExperimentalGpu)
            .putString("system_prompt", updated.systemPrompt)
            .putString("hf_client_id", updated.hfOAuthClientId)
            .putBoolean("setup_completed", updated.setupWizardCompleted)
            .putStringSet("disabled_tools", updated.disabledTools)
            .putStringSet("instant_tier2_tools", updated.instantTier2Tools)
            .apply()
        _settings.value = updated
    }

    fun wipeAllPreferences() {
        prefs.edit().clear().apply()
        refreshState()
    }

    private fun refreshState() {
        _settings.value = loadSettings()
    }

    private fun loadSettings(): SkySettingsState {
        val presetId = prefs.getString("theme_preset", SkyThemePreset.SKY.id)
        val modeId = prefs.getString("theme_mode", SkyThemeMode.SYSTEM.id)
        val wakeId = prefs.getString("siri_wake_style", SiriWakeStyle.SIRI_GLOW.id)
        val tokenPresent = !prefs.getString(KEY_HF_TOKEN_ENC, "").isNullOrBlank()

        return SkySettingsState(
            themePreset = SkyThemePreset.entries.find { it.id == presetId } ?: SkyThemePreset.SKY,
            themeMode = SkyThemeMode.entries.find { it.id == modeId } ?: SkyThemeMode.SYSTEM,
            siriWakeStyle = SiriWakeStyle.entries.find { it.id == wakeId } ?: SiriWakeStyle.SIRI_GLOW,
            vadSilenceMs = prefs.getInt("vad_silence_ms", 650),
            autoFollowUpListening = prefs.getBoolean("auto_follow_up", false),
            ttsEnabled = prefs.getBoolean("tts_enabled", true),
            ttsSpeechRate = prefs.getFloat("tts_rate", 1.05f),
            ttsPitch = prefs.getFloat("tts_pitch", 1.0f),
            wifiOnlyDownloads = prefs.getBoolean("wifi_only_dl", false),
            llmTemperature = prefs.getFloat("llm_temp", 0.7f),
            llmTopP = prefs.getFloat("llm_top_p", 0.9f),
            llmTopK = prefs.getInt("llm_top_k", 40),
            llmMinP = prefs.getFloat("llm_min_p", 0.05f),
            llmRepeatPenalty = prefs.getFloat("llm_repeat_pen", 1.1f),
            llmMaxTokens = prefs.getInt("llm_max_tokens", 512),
            llmContextSize = prefs.getInt("llm_ctx_size", 4096),
            llmThreads = prefs.getInt("llm_threads", 4),
            llmExperimentalGpu = prefs.getBoolean("llm_gpu", false),
            systemPrompt = prefs.getString(
                "system_prompt",
                "You are Sky, a calm, concise, privacy-first on-device AI assistant running locally on Android."
            ) ?: "",
            hfOAuthClientId = prefs.getString("hf_client_id", "") ?: "",
            hasHfToken = tokenPresent,
            hfUsername = prefs.getString(KEY_HF_USERNAME, "") ?: "",
            setupWizardCompleted = prefs.getBoolean("setup_completed", false),
            disabledTools = prefs.getStringSet("disabled_tools", emptySet()) ?: emptySet(),
            instantTier2Tools = prefs.getStringSet("instant_tier2_tools", emptySet()) ?: emptySet()
        )
    }

    companion object {
        private const val KEY_ALIAS = "sky_hf_aes_master_key"
        private const val KEY_HF_TOKEN_ENC = "hf_token_enc"
        private const val KEY_HF_USERNAME = "hf_username"
        private const val KEY_PKCE_VERIFIER = "hf_pkce_verifier"
        private const val KEY_PKCE_STATE = "hf_pkce_state"
    }
}
