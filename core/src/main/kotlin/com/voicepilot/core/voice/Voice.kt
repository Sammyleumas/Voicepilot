package com.voicepilot.core.voice

import com.voicepilot.core.text.NameMatcher
import com.voicepilot.core.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SpeechError(val userMessage: String) {
    NO_MATCH("I didn't catch that. Please try again."),
    SPEECH_TIMEOUT("I didn't hear anything."),
    PERMISSION_DENIED("Microphone permission is needed for voice commands. You can still type."),
    NETWORK("Speech recognition needs a network connection on this device."),
    BUSY("The speech recognizer is busy. Please try again."),
    UNAVAILABLE("Speech recognition isn't available on this device. You can type commands instead."),
    LANGUAGE_UNAVAILABLE("This language isn't available for speech recognition on this device."),
    AUDIO("There was a problem with the microphone."),
    CLIENT("Speech recognition stopped unexpectedly."),
    UNKNOWN("Speech recognition failed."),
}

sealed interface SpeechEvent {
    data object ReadyForSpeech : SpeechEvent
    data object SpeechStarted : SpeechEvent
    /** Normalised input level 0..1 for the waveform. */
    data class Level(val value: Float) : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data object SpeechEnded : SpeechEvent
    data class Final(val text: String, val confidence: Float?, val alternatives: List<String> = emptyList()) : SpeechEvent
    data class Error(val error: SpeechError) : SpeechEvent
}

data class RecognitionConfig(
    val languageTag: String = "en-NG",
    val partialResults: Boolean = true,
    val preferOffline: Boolean = false,
    val maxResults: Int = 3,
)

/** A replaceable speech-to-text provider. */
interface SpeechRecognitionEngine {
    val id: String
    fun isAvailable(): Boolean
    /** Whether on-device recognition is actually supported (never assumed). */
    fun supportsOffline(): Boolean

    /**
     * Listens for one utterance. The flow completes after [SpeechEvent.Final] or
     * [SpeechEvent.Error]; cancelling the collector stops the microphone.
     */
    fun listen(config: RecognitionConfig): Flow<SpeechEvent>
}

data class VoiceOption(val name: String, val locale: String, val requiresNetwork: Boolean)

/** A replaceable text-to-speech provider. */
interface SpeechSynthesizer {
    val isSpeaking: StateFlow<Boolean>
    suspend fun speak(text: String, rate: Float = 1.0f, voiceName: String? = null)
    fun stop()
    suspend fun voices(): List<VoiceOption>
}

enum class WakeWordMode(val label: String) {
    OFF("Off"),
    /** Listening loop while the app's foreground service runs; requires the app to be started by the user. */
    HANDS_FREE_SESSION("Hands-free session"),
    /** Invoked through Android's default digital assistant role (assist gesture / long-press). */
    SYSTEM_ASSISTANT("Default assistant"),
}

sealed interface WakeWordStatus {
    data object Disabled : WakeWordStatus
    data object Listening : WakeWordStatus
    data object Paused : WakeWordStatus
    data class Unavailable(val reason: String) : WakeWordStatus
}

/** A detected activation, optionally with a command spoken in the same breath. */
data class WakeDetection(val transcript: String, val trailingCommand: String?)

/** Pluggable wake-word detector (e.g. a keyword-spotting model or a recognizer loop). */
interface WakeWordEngine {
    val status: StateFlow<WakeWordStatus>
    fun detections(): Flow<WakeDetection>
    fun start()
    fun stop()
}

/**
 * Matches transcripts against the activation phrase, tolerating common
 * recognizer spellings ("hey voice pilot", "a voicepilot", "hey voice pirate").
 */
class WakePhraseMatcher(phrase: String = "hey sl") {
    private val target = TextNormalizer.normalize(phrase).replace(" ", "")
    private val nameOnly = target.removePrefix("hey").removePrefix("ok")

    /**
     * Short names like "SL" are matched exactly against the ways a recognizer
     * writes them ("sl", "s l", "es el", "ess ell"), each letter as its own word,
     * because fuzzy matching on two letters would trigger on ordinary words.
     */
    private val shortName: String? = nameOnly.takeIf { it.length in 1..3 && it.all(Char::isLetter) }

    /** Number of tokens the name occupies at [from], or 0 when it isn't there. */
    private fun nameLength(tokens: List<String>, from: Int, name: String): Int {
        if (tokens.getOrNull(from) == name) return 1
        if (from + name.length > tokens.size) return 0
        val spelled = name.indices.all { i -> tokens[from + i] in letterSpellings(name[i]) }
        return if (spelled) name.length else 0
    }

    private fun matchShort(tokens: List<String>, name: String): WakeDetection? {
        val hasPrefix = tokens.first() in WAKE_PREFIXES
        val start = if (hasPrefix) 1 else 0
        val len = nameLength(tokens, start, name)
        if (len == 0) return null
        val end = start + len
        if (!hasPrefix && end >= tokens.size) return null // a bare name only counts when a command follows
        val rest = tokens.drop(end).joinToString(" ").trim()
        return WakeDetection(tokens.joinToString(" "), rest.ifEmpty { null })
    }

    fun match(transcript: String): WakeDetection? {
        val tokens = TextNormalizer.tokens(transcript)
        if (tokens.isEmpty()) return null
        shortName?.let { return matchShort(tokens, it)?.copy(transcript = transcript) }
        // Score windows of 1..4 tokens at the start of the transcript and keep the best one.
        val best = (1..minOf(4, tokens.size)).map { len ->
            val head = tokens.take(len).joinToString("")
            val score = maxOf(
                NameMatcher.similarity(head, target),
                NameMatcher.similarity(head, "a$nameOnly"),
                if (len <= 2) NameMatcher.similarity(head, nameOnly) - 0.1 else 0.0,
            )
            len to score
        }.maxWith(compareBy<Pair<Int, Double>> { it.second }.thenBy { -it.first })
        if (best.second < THRESHOLD) return null
        val rest = tokens.drop(best.first).joinToString(" ").trim()
        return WakeDetection(transcript, rest.ifEmpty { null })
    }

    /** Removes a leading wake phrase, returning the command part (or the input unchanged). */
    fun stripWakePhrase(transcript: String): String {
        val detection = match(transcript) ?: return transcript
        return detection.trailingCommand ?: ""
    }

    private companion object {
        const val THRESHOLD = 0.8
        val WAKE_PREFIXES = listOf("hey", "hei", "hay", "ok", "okay", "hi", "a")

        fun letterSpellings(c: Char): List<String> = when (c) {
            'a' -> listOf("a", "ay")
            'b' -> listOf("b", "bee")
            'c' -> listOf("c", "see", "sea")
            'd' -> listOf("d", "dee")
            'e' -> listOf("e")
            'f' -> listOf("f", "ef", "eff")
            'g' -> listOf("g", "gee")
            'h' -> listOf("h", "aitch")
            'i' -> listOf("i", "eye")
            'j' -> listOf("j", "jay")
            'k' -> listOf("k", "kay")
            'l' -> listOf("l", "el", "ell")
            'm' -> listOf("m", "em")
            'n' -> listOf("n", "en")
            'o' -> listOf("o", "oh")
            'p' -> listOf("p", "pee")
            'q' -> listOf("q", "queue")
            'r' -> listOf("r", "are", "ar")
            's' -> listOf("s", "es", "ess")
            't' -> listOf("t", "tee", "tea")
            'u' -> listOf("u", "you")
            'v' -> listOf("v", "vee")
            'w' -> listOf("w")
            'x' -> listOf("x", "ex")
            'y' -> listOf("y", "why")
            'z' -> listOf("z", "zed", "zee")
            else -> listOf(c.toString())
        }
    }
}
