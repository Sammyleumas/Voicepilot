package com.voicepilot.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray
private fun JsonObject.int(name: String): Int? = (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

/** Maps HTTP status codes to provider error kinds. */
internal fun errorKindFor(code: Int): LlmErrorKind = when (code) {
    400 -> LlmErrorKind.INVALID_RESPONSE
    401, 403 -> LlmErrorKind.AUTH
    408 -> LlmErrorKind.TIMEOUT
    429 -> LlmErrorKind.RATE_LIMITED
    in 500..599 -> LlmErrorKind.SERVER
    else -> LlmErrorKind.SERVER
}

internal suspend fun HttpTransport.safePost(
    url: String,
    headers: Map<String, String>,
    body: String,
    timeoutMillis: Long,
): HttpResponse = try {
    post(url, headers, body, timeoutMillis)
} catch (e: CancellationException) {
    throw e
} catch (e: LlmException) {
    throw e
} catch (e: java.net.SocketTimeoutException) {
    throw LlmException(LlmErrorKind.TIMEOUT, "Request timed out", e)
} catch (e: java.io.IOException) {
    throw LlmException(LlmErrorKind.NETWORK, "Network error", e)
} catch (e: Exception) {
    throw LlmException(LlmErrorKind.NETWORK, "Transport error", e)
}

/**
 * Google Gemini via the public `generateContent` REST endpoint.
 *
 * The key comes from [apiKey] at call time (user-supplied, stored encrypted by
 * the app). For production distribution prefer [CloudProxyProvider] so no
 * provider key ever lives on the device.
 */
class GeminiProvider(
    private val transport: HttpTransport,
    private val apiKey: suspend () -> String?,
    private val model: () -> String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val timeoutMillis: Long = 20_000,
) : LlmProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"

    override suspend fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw LlmException(LlmErrorKind.NOT_CONFIGURED, "Gemini API key missing")
        val modelName = model()
        require(MODEL_NAME.matches(modelName)) { "Invalid model name" }
        val response = transport.safePost(
            url = "$baseUrl/models/$modelName:generateContent",
            headers = mapOf("Content-Type" to "application/json", "x-goog-api-key" to key),
            body = buildBody(request).toString(),
            timeoutMillis = timeoutMillis,
        )
        if (response.code !in 200..299) throw LlmException(errorKindFor(response.code), "Gemini HTTP ${response.code}")
        return parse(response.body, modelName)
    }

    internal fun buildBody(request: LlmRequest): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", request.systemPrompt) }) }
        }
        putJsonArray("contents") {
            request.messages.forEach { m ->
                add(
                    buildJsonObject {
                        put("role", if (m.role == LlmRole.USER) "user" else "model")
                        putJsonArray("parts") { add(buildJsonObject { put("text", m.text) }) }
                    },
                )
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", request.temperature)
            put("maxOutputTokens", request.maxOutputTokens)
            if (request.jsonOutput) put("responseMimeType", "application/json")
        }
    }

    internal fun parse(body: String, modelName: String): LlmResponse {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Malformed Gemini response", e)
        }
        root.obj("promptFeedback")?.get("blockReason")?.let {
            throw LlmException(LlmErrorKind.BLOCKED, "Prompt blocked")
        }
        val candidate = root.arr("candidates")?.firstOrNull()?.jsonObject
            ?: throw LlmException(LlmErrorKind.INVALID_RESPONSE, "No candidates")
        val text = candidate.obj("content")?.arr("parts")
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
            ?: run {
                val reason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
                throw LlmException(
                    if (reason == "SAFETY") LlmErrorKind.BLOCKED else LlmErrorKind.INVALID_RESPONSE,
                    "Empty candidate ($reason)",
                )
            }
        val usage = root.obj("usageMetadata")?.let {
            TokenUsage(
                promptTokens = it.int("promptTokenCount") ?: 0,
                outputTokens = it.int("candidatesTokenCount") ?: 0,
                totalTokens = it.int("totalTokenCount") ?: 0,
            )
        }
        return LlmResponse(text, usage, modelName)
    }

    private companion object {
        val MODEL_NAME = Regex("^[a-z0-9.\\-]{3,64}$")
    }
}

/**
 * VoicePilot backend proxy (see /backend). The device authenticates with a
 * short-lived user token; the provider API key stays on the server, which also
 * enforces quotas and rate limits.
 */
class CloudProxyProvider(
    private val transport: HttpTransport,
    private val baseUrl: () -> String,
    private val authToken: suspend () -> String?,
    private val model: () -> String,
    private val timeoutMillis: Long = 25_000,
) : LlmProvider {
    override val id = "voicepilot-cloud"
    override val displayName = "VoicePilot Cloud"

    override suspend fun isConfigured(): Boolean = baseUrl().startsWith("https://") && !authToken().isNullOrBlank()

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val url = baseUrl().trimEnd('/')
        if (!url.startsWith("https://")) throw LlmException(LlmErrorKind.NOT_CONFIGURED, "Cloud URL missing or not HTTPS")
        val token = authToken() ?: throw LlmException(LlmErrorKind.NOT_CONFIGURED, "Not signed in")
        val body = buildJsonObject {
            put("model", model())
            put("system", request.systemPrompt)
            put("json", request.jsonOutput)
            put("temperature", request.temperature)
            put("maxOutputTokens", request.maxOutputTokens)
            put(
                "messages",
                buildJsonArray {
                    request.messages.forEach { m ->
                        add(buildJsonObject { put("role", m.role.name.lowercase()); put("text", m.text) })
                    }
                },
            )
        }
        val response = transport.safePost(
            url = "$url/v1/generate",
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
            body = body.toString(),
            timeoutMillis = timeoutMillis,
        )
        if (response.code == 402) throw LlmException(LlmErrorKind.QUOTA_EXCEEDED, "Quota exceeded")
        if (response.code !in 200..299) throw LlmException(errorKindFor(response.code), "Cloud HTTP ${response.code}")
        val root = try {
            json.parseToJsonElement(response.body).jsonObject
        } catch (e: Exception) {
            throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Malformed cloud response", e)
        }
        val text = root["text"]?.jsonPrimitive?.contentOrNull
            ?: throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Missing text")
        val usage = root.obj("usage")?.let {
            TokenUsage(it.int("promptTokens") ?: 0, it.int("outputTokens") ?: 0, it.int("totalTokens") ?: 0)
        }
        return LlmResponse(text, usage, root["model"]?.jsonPrimitive?.contentOrNull)
    }
}
