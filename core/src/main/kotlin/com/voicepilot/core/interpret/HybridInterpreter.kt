package com.voicepilot.core.interpret

import com.voicepilot.core.llm.LlmErrorKind
import com.voicepilot.core.llm.LlmException
import com.voicepilot.core.llm.RateLimiter
import com.voicepilot.core.subscription.UsageTracker

/**
 * Local rules first (instant, offline, free), then the configured AI provider
 * for anything the rules don't understand. AI failures degrade gracefully to a
 * clear explanation; local commands keep working without internet.
 */
class HybridInterpreter(
    private val local: LocalRuleInterpreter,
    /** Returns the AI interpreter for the current settings, or null when AI is off or unconfigured. */
    private val ai: suspend () -> CommandInterpreter?,
    private val usage: UsageTracker,
    private val rateLimiter: RateLimiter,
) : CommandInterpreter {

    override suspend fun interpret(utterance: String, context: ConversationContext): Interpretation {
        val localResult = local.interpret(utterance, context)
        if (localResult !is Interpretation.Unsupported) return localResult

        val aiInterpreter = ai()
            ?: return Interpretation.Unsupported(OFFLINE_HINT, InterpretationSource.LOCAL_RULES)
        if (!rateLimiter.tryAcquire()) {
            return Interpretation.Unsupported(LlmException(LlmErrorKind.RATE_LIMITED, "client").userMessage, InterpretationSource.AI)
        }
        if (!usage.tryConsume()) {
            return Interpretation.Unsupported(
                "${LlmException(LlmErrorKind.QUOTA_EXCEEDED, "quota").userMessage} Offline commands still work.",
                InterpretationSource.AI,
            )
        }
        return try {
            aiInterpreter.interpret(utterance, context)
        } catch (e: LlmException) {
            Interpretation.Unsupported("${e.userMessage} Offline commands like \"open WhatsApp\" still work.", InterpretationSource.AI)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Interpretation.Unsupported("The AI request failed unexpectedly. Offline commands still work.", InterpretationSource.AI)
        }
    }

    companion object {
        const val OFFLINE_HINT =
            "I didn't understand that with on-device commands. Try something like \"set an alarm for 7am\", " +
                "or turn on an AI provider in Settings › AI for more flexible requests."
    }
}
