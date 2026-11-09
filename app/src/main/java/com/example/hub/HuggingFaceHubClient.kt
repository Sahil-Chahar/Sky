package com.example.hub

import android.net.Uri
import android.util.Base64
import com.example.data.local.ModelRoleSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class HubModelFile(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val quantization: String,
    val downloadUrl: String,
    val splitIndex: Int? = null,
    val splitTotal: Int? = null
)

data class HubRepoItem(
    val repoId: String,
    val displayName: String,
    val author: String,
    val downloads: Int,
    val likes: Int,
    val isGated: Boolean,
    val roleSlot: ModelRoleSlot,
    val summary: String,
    val recommendedFile: HubModelFile? = null,
    val files: List<HubModelFile> = emptyList()
)

data class PkceAuthRequest(
    val authorizeUrl: String,
    val codeVerifier: String,
    val state: String
)

sealed class HubApiResult<out T> {
    data class Success<T>(val data: T) : HubApiResult<T>()
    data class GatedRepoError(val repoId: String, val licenseUrl: String) : HubApiResult<Nothing>()
    data class NetworkError(val message: String) : HubApiResult<Nothing>()
}

/**
 * Client for Hugging Face Hub REST API + OAuth 2.0 PKCE + Personal Access Token validation.
 * Only communicates with `huggingface.co`. Never logs tokens.
 */
class HuggingFaceHubClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Curated one-tap Starter Pack (Needle 3 + Whistle) and ungated Reply GGUF models
     * featuring SmolLM2 360M Instruct Q8_0 (~380 MB) as the primary recommended Reply model.
     */
    val curatedStarterAndRecommended: List<HubRepoItem> = listOf(
        HubRepoItem(
            repoId = "Cactus-Compute/needle3",
            displayName = "Needle 3 · Tool Calling & Embeddings",
            author = "Cactus-Compute",
            downloads = 48200,
            likes = 640,
            isGated = false,
            roleSlot = ModelRoleSlot.TASK,
            summary = "29 MB grammar-constrained tool calling, structured extraction, and 384-d semantic embeddings (.cact).",
            recommendedFile = HubModelFile(
                fileName = "needle3.cact",
                sizeBytes = 29_100_000L,
                sha256 = null,
                quantization = "CQ2 (Cactus)",
                downloadUrl = "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact"
            )
        ),
        HubRepoItem(
            repoId = "Cactus-Compute/whistle",
            displayName = "Whistle · 16 kHz Speech-to-Text",
            author = "Cactus-Compute",
            downloads = 39500,
            likes = 510,
            isGated = false,
            roleSlot = ModelRoleSlot.SPEECH,
            summary = "16.9 MB multilingual on-device speech recognition running on the Cactus CPU engine (.cact).",
            recommendedFile = HubModelFile(
                fileName = "whistle.cact",
                sizeBytes = 16_900_000L,
                sha256 = null,
                quantization = "CQ2 (Cactus)",
                downloadUrl = "https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact"
            )
        ),
        HubRepoItem(
            repoId = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            displayName = "SmolLM2 360M Instruct (Recommended)",
            author = "HuggingFaceTB",
            downloads = 185000,
            likes = 920,
            isGated = false,
            roleSlot = ModelRoleSlot.REPLY,
            summary = "Primary recommended Reply model (~386 MB Q8_0). Fast download, instant warmup, ungated.",
            recommendedFile = HubModelFile(
                fileName = "smollm2-360m-instruct-q8_0.gguf",
                sizeBytes = 386_400_000L,
                sha256 = null,
                quantization = "Q8_0",
                downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf"
            )
        ),
        HubRepoItem(
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            displayName = "Qwen 2.5 1.5B Instruct",
            author = "Qwen",
            downloads = 412000,
            likes = 1480,
            isGated = false,
            roleSlot = ModelRoleSlot.REPLY,
            summary = "Strong reasoning, coding, and summarization in ~1.0 GB Q4_K_M quantization (ungated).",
            recommendedFile = HubModelFile(
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeBytes = 1_040_000_000L,
                sha256 = null,
                quantization = "Q4_K_M",
                downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"
            )
        ),
        HubRepoItem(
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            displayName = "Llama 3.2 1B Instruct (Ungated Community)",
            author = "bartowski",
            downloads = 328000,
            likes = 1120,
            isGated = false,
            roleSlot = ModelRoleSlot.REPLY,
            summary = "Ungated community GGUF of Llama 3.2 1B Instruct (~808 MB Q4_K_M) for fast mobile chat.",
            recommendedFile = HubModelFile(
                fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                sizeBytes = 808_000_000L,
                sha256 = null,
                quantization = "Q4_K_M",
                downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
            )
        ),
        HubRepoItem(
            repoId = "bartowski/gemma-2-2b-it-GGUF",
            displayName = "Gemma 2 2B Instruct (Ungated Community)",
            author = "bartowski",
            downloads = 264000,
            likes = 890,
            isGated = false,
            roleSlot = ModelRoleSlot.REPLY,
            summary = "High-quality 2B conversational model (~1.6 GB Q4_K_M) for 6 GB+ RAM phones.",
            recommendedFile = HubModelFile(
                fileName = "gemma-2-2b-it-Q4_K_M.gguf",
                sizeBytes = 1_630_000_000L,
                sha256 = null,
                quantization = "Q4_K_M",
                downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf"
            )
        )
    )

    /**
     * Generates an RFC 7636 OAuth 2.0 PKCE authorization request for Hugging Face.
     */
    fun buildOAuthPkceRequest(
        clientId: String,
        redirectUri: String = REDIRECT_URI
    ): PkceAuthRequest {
        val random = SecureRandom()
        val verifierBytes = ByteArray(32)
        random.nextBytes(verifierBytes)
        val codeVerifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val stateBytes = ByteArray(16)
        random.nextBytes(stateBytes)
        val state = Base64.encodeToString(
            stateBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val url = Uri.parse("https://huggingface.co/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "openid profile read-repos")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()

        return PkceAuthRequest(
            authorizeUrl = url,
            codeVerifier = codeVerifier,
            state = state
        )
    }

    suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String = REDIRECT_URI
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId.trim())
                .add("code", code.trim())
                .add("redirect_uri", redirectUri)
                .add("code_verifier", codeVerifier)
                .build()

            val req = Request.Builder()
                .url("https://huggingface.co/oauth/token")
                .post(form)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("OAuth token exchange failed (HTTP ${resp.code})")
                    )
                }
                val json = JSONObject(bodyStr)
                val accessToken = json.optString("access_token", "")
                if (accessToken.isBlank()) {
                    Result.failure(IllegalStateException("Missing access_token in OAuth response"))
                } else {
                    Result.success(accessToken)
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun verifyTokenWhoAmI(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .header("Authorization", "Bearer ${token.trim()}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Invalid token (HTTP ${resp.code})")
                    )
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val name = json.optString("name", "hf_user")
                Result.success(name)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun searchHubModels(
        query: String,
        isTaskSpeechTab: Boolean,
        bearerToken: String? = null
    ): HubApiResult<List<HubRepoItem>> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = Uri.parse("https://huggingface.co/api/models")
                .buildUpon()
                .appendQueryParameter("sort", "downloads")
                .appendQueryParameter("direction", "-1")
                .appendQueryParameter("limit", "25")

            if (isTaskSpeechTab) {
                if (query.isBlank()) {
                    urlBuilder.appendQueryParameter("search", "Cactus-Compute")
                } else {
                    urlBuilder.appendQueryParameter("search", query.trim())
                }
            } else {
                urlBuilder.appendQueryParameter("filter", "gguf")
                if (query.isNotBlank()) {
                    urlBuilder.appendQueryParameter("search", query.trim())
                }
            }

            val reqBuilder = Request.Builder().url(urlBuilder.build().toString()).get()
            if (!bearerToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${bearerToken.trim()}")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext HubApiResult.GatedRepoError(
                        repoId = query,
                        licenseUrl = "https://huggingface.co/$query"
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext HubApiResult.NetworkError("Hub API returned HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: "[]"
                HubApiResult.Success(parseModelsJsonArray(body, isTaskSpeechTab))
            }
        } catch (e: Throwable) {
            // Return filtered curated list when offline
            val filtered = curatedStarterAndRecommended.filter { item ->
                val tabMatch = if (isTaskSpeechTab) {
                    item.roleSlot == ModelRoleSlot.TASK || item.roleSlot == ModelRoleSlot.SPEECH
                } else {
                    item.roleSlot == ModelRoleSlot.REPLY
                }
                tabMatch && (query.isBlank() ||
                    item.repoId.contains(query, ignoreCase = true) ||
                    item.displayName.contains(query, ignoreCase = true))
            }
            HubApiResult.Success(filtered)
        }
    }

    suspend fun fetchRepoFiles(
        repoId: String,
        bearerToken: String? = null
    ): HubApiResult<List<HubModelFile>> = withContext(Dispatchers.IO) {
        val cleanRepo = repoId.trim()
            .removePrefix("https://huggingface.co/")
            .removeSuffix("/")
        try {
            val url = "https://huggingface.co/api/models/$cleanRepo/tree/main?recursive=true"
            val reqBuilder = Request.Builder().url(url).get()
            if (!bearerToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${bearerToken.trim()}")
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext HubApiResult.GatedRepoError(
                        repoId = cleanRepo,
                        licenseUrl = "https://huggingface.co/$cleanRepo"
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext HubApiResult.NetworkError("HTTP ${resp.code} loading $cleanRepo")
                }
                val body = resp.body?.string() ?: "[]"
                val files = parseRepoTreeJson(cleanRepo, body)
                HubApiResult.Success(files)
            }
        } catch (e: Throwable) {
            val curated = curatedStarterAndRecommended.find {
                it.repoId.equals(cleanRepo, ignoreCase = true)
            }?.recommendedFile
            if (curated != null) {
                HubApiResult.Success(listOf(curated))
            } else {
                HubApiResult.NetworkError(e.message ?: "Failed to load repository files")
            }
        }
    }

    fun parseModelsJsonArray(jsonStr: String, isTaskSpeechTab: Boolean): List<HubRepoItem> {
        val arr = JSONArray(jsonStr)
        val out = mutableListOf<HubRepoItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", obj.optString("modelId", ""))
            if (id.isBlank()) continue
            val downloads = obj.optInt("downloads", 0)
            val likes = obj.optInt("likes", 0)
            val gatedVal = obj.opt("gated")
            val isGated = gatedVal == true || (gatedVal is String && gatedVal != "false")
            val author = id.substringBefore("/", "community")
            val shortName = id.substringAfter("/", id)
            val slot = when {
                id.contains("whistle", ignoreCase = true) -> ModelRoleSlot.SPEECH
                isTaskSpeechTab || id.contains("needle", ignoreCase = true) -> ModelRoleSlot.TASK
                else -> ModelRoleSlot.REPLY
            }
            out.add(
                HubRepoItem(
                    repoId = id,
                    displayName = shortName,
                    author = author,
                    downloads = downloads,
                    likes = likes,
                    isGated = isGated,
                    roleSlot = slot,
                    summary = if (slot == ModelRoleSlot.REPLY) {
                        "GGUF model repository by $author"
                    } else {
                        "Cactus .cact model repository by $author"
                    }
                )
            )
        }
        return out
    }

    fun parseRepoTreeJson(repoId: String, jsonStr: String): List<HubModelFile> {
        val arr = JSONArray(jsonStr)
        val out = mutableListOf<HubModelFile>()
        val splitRegex = Regex(""".*-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val path = obj.optString("path", "")
            if (!path.endsWith(".gguf", ignoreCase = true) &&
                !path.endsWith(".cact", ignoreCase = true)
            ) {
                continue
            }
            val lfs = obj.optJSONObject("lfs")
            val size = lfs?.optLong("size", 0L) ?: obj.optLong("size", 0L)
            val sha256 = lfs?.optString("oid", null)
            val match = splitRegex.matchEntire(path)
            val splitIdx = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val splitTot = match?.groupValues?.getOrNull(2)?.toIntOrNull()

            out.add(
                HubModelFile(
                    fileName = path,
                    sizeBytes = size,
                    sha256 = sha256,
                    quantization = ModelMetadataInspector.inferQuantizationFromName(path),
                    downloadUrl = "https://huggingface.co/$repoId/resolve/main/$path",
                    splitIndex = splitIdx,
                    splitTotal = splitTot
                )
            )
        }
        return out
    }

    companion object {
        const val REDIRECT_URI = "com.sky.assistant://oauth-callback"
    }
}
