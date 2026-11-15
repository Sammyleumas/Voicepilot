package com.voicepilot.core.interpret

import com.voicepilot.core.model.ActionRequest

enum class InterpretationSource { LOCAL_RULES, AI, ROUTINE }

/** What the assistant understood from one user utterance. */
sealed interface Interpretation {
    val source: InterpretationSource

    /**
     * One or more allowlisted actions. Requests may still lack parameters; the
     * conversation engine asks for those before anything runs.
     */
    data class Actions(
        val intent: String,
        val requests: List<ActionRequest>,
        override val source: InterpretationSource,
        /** Optional sentence to say before planning (e.g. the sleep routine suggestion). */
        val preface: String? = null,
    ) : Interpretation

    /** The model needs more information before it can choose actions. */
    data class Clarify(val question: String, override val source: InterpretationSource) : Interpretation

    /** A conversational answer with no device action. */
    data class Answer(val text: String, override val source: InterpretationSource) : Interpretation

    data class Unsupported(val reason: String, override val source: InterpretationSource) : Interpretation
}

/** Recent conversation turns given to AI interpreters for follow-up questions. */
data class ConversationContext(
    val recentTurns: List<Pair<String, String>> = emptyList(),
    val userName: String = "",
    val assistantName: String = "VoicePilot",
    val nowIso: String = "",
    val timeZone: String = "",
)

interface CommandInterpreter {
    suspend fun interpret(utterance: String, context: ConversationContext): Interpretation
}

/**
 * Converts reported speech into direct speech for the recipient:
 * "telling him I'll call him after work" → "I'll call you after work".
 */
object MessageRewriter {
    private val LEADING = Regex(
        "^(?:(?:and\\s+)?(?:tell|telling|saying|say|sey|ask|asking|that|to say|to tell (?:him|her|them|am))\\s+)?" +
            "(?:(?:him|her|them|am)\\s+)?(?:(?:that|say|sey)\\s+)?",
        RegexOption.IGNORE_CASE,
    )
    private val HER_OBJECT = Regex(
        "\\bher\\b(?=\\s*(?:$|[.,!?]|after|later|tomorrow|back|now|soon|at|in|on|when|tonight|today|first|again|later))",
        RegexOption.IGNORE_CASE,
    )

    fun toDirectSpeech(raw: String, thirdPerson: Boolean): String {
        var text = raw.trim().trim('"', '“', '”', '\'')
        text = text.replace(LEADING, "").trim()
        if (thirdPerson) {
            text = text
                .replace(Regex("\\bhimself\\b", RegexOption.IGNORE_CASE), "yourself")
                .replace(Regex("\\bhim\\b", RegexOption.IGNORE_CASE), "you")
                .replace(Regex("\\bhis\\b", RegexOption.IGNORE_CASE), "your")
                .replace(Regex("\\bthem\\b", RegexOption.IGNORE_CASE), "you")
                .replace(HER_OBJECT, "you")
                // Pidgin "am" (him/her), but not English "I am".
                .replace(Regex("(?<!\\bI )\\bam\\b(?!\\s+(?:a|an|the|not|going|coming|here|there|on|in|at|fine|ok|okay|late|outside|home)\\b)", RegexOption.IGNORE_CASE), "you")
        }
        text = text.replace(Regex("\\s+"), " ").trim().trimEnd(',')
        return text.replaceFirstChar { it.uppercaseChar() }
    }
}
