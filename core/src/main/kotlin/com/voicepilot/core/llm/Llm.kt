package com.voicepilot.core.llm

import com.voicepilot.core.time.Clock

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val text: String)

data class LlmRequest(
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    /** Ask the provider for a JSON-only response. */
    val jsonOutput: Boolean = true,
    val temperature: Double = 0.1,
    val maxOutputTokens: Int = 1024,
)

data class TokenUsage(val promptTokens: Int, val outputTokens: Int, val totalTokens: Int)

data class LlmResponse(val text: String, val usage: TokenUsage? = null, val model: String? = null)

enum class LlmErrorKind {
    NOT_CONFIGURED, AUTH, RATE_LIMITED, QUOTA_EXCEEDED, TIMEOUT, NETWORK, SERVER, INVALID_RESPONSE, BLOCKED,
}

class LlmException(val kind: LlmErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Short, user-facing explanation. */
    val userMessage: String
        get() = when (kind) {
            LlmErrorKind.NOT_CONFIGURED -> "The AI provider isn't set up yet. You can add it in Settings › AI."
            LlmErrorKind.AUTH -> "The AI provider rejected the credentials. Check your API key in Settings › AI."
            LlmErrorKind.RATE_LIMITED -> "The AI service is busy right now. Please try again in a moment."
            LlmErrorKind.QUOTA_EXCEEDED -> "You've reached today's AI request limit."
            LlmErrorKind.TIMEOUT -> "The AI service took too long to respond."
            LlmErrorKind.NETWORK -> "I couldn't reach the AI service. Check your internet connection."
            LlmErrorKind.SERVER -> "The AI service had a problem. Please try again."
            LlmErrorKind.INVALID_RESPONSE -> "The AI service returned something I couldn't use."
            LlmErrorKind.BLOCKED -> "The AI service declined that request."
        }
}

/** A large language model provider. Implementations must be safe to call from any coroutine. */
interface LlmProvider {
    val id: String
    val displayName: String

    /** False when credentials or configuration are missing; callers then skip AI. */
    suspend fun isConfigured(): Boolean

    /** @throws LlmException on any failure. */
    suspend fun generate(request: LlmRequest): LlmResponse
}

/** Minimal HTTP abstraction so providers stay platform-independent and testable. */
interface HttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMillis: Long): HttpResponse
}

data class HttpResponse(val code: Int, val body: String)

/**
 * Client-side token bucket that stops runaway request loops before they reach
 * the network. Server-side limits still apply.
 */
class RateLimiter(
    private val capacity: Int,
    private val refillEveryMillis: Long,
    private val clock: Clock,
) {
    private var tokens = capacity.toDouble()
    private var last = clock.now().toEpochMilli()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.now().toEpochMilli()
        val refill = (now - last).toDouble() / refillEveryMillis
        tokens = (tokens + refill).coerceAtMost(capacity.toDouble())
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/** Deterministic provider for tests and offline demos of the AI pipeline. */
class ScriptedLlmProvider(
    private val responses: ArrayDeque<Result<LlmResponse>>,
    private val configured: Boolean = true,
) : LlmProvider {
    override val id = "scripted"
    override val displayName = "Scripted (test)"
    val requests = mutableListOf<LlmRequest>()

    override suspend fun isConfigured() = configured

    override suspend fun generate(request: LlmRequest): LlmResponse {
        requests += request
        val next = responses.removeFirstOrNull()
            ?: throw LlmException(LlmErrorKind.SERVER, "No scripted response left")
        return next.getOrThrow()
    }

    companion object {
        fun of(vararg texts: String) = ScriptedLlmProvider(ArrayDeque(texts.map { Result.success(LlmResponse(it)) }))
        fun failing(kind: LlmErrorKind) =
            ScriptedLlmProvider(ArrayDeque(listOf(Result.failure(LlmException(kind, kind.name)))))
    }
}
