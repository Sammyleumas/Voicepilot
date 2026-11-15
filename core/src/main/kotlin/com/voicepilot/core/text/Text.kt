package com.voicepilot.core.text

import java.text.Normalizer

object TextNormalizer {
    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9' ]+")
    private val SPACES = Regex("\\s+")

    /** Lowercase, strip accents and punctuation (keeps apostrophes), collapse whitespace. */
    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD).replace(DIACRITICS, "")
        return decomposed.lowercase()
            .replace('’', '\'')
            .replace(NON_ALNUM, " ")
            .replace(SPACES, " ")
            .trim()
    }

    fun tokens(input: String): List<String> = normalize(input).split(' ').filter { it.isNotEmpty() }

    /** Uppercases the first letter, leaving the rest untouched. */
    fun capitalizeFirst(text: String): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/**
 * Scores how well a spoken query matches a name (contact or app). Returns a value
 * in 0.0..1.0. Deterministic and allocation-light so it can rank thousands of
 * contacts on low-end phones.
 */
object NameMatcher {

    fun score(query: String, candidate: String): Double {
        val q = TextNormalizer.normalize(query)
        val c = TextNormalizer.normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        val qTokens = q.split(' ')
        val cTokens = c.split(' ')
        // Every query token equals a candidate token ("samuel" vs "samuel okafor").
        if (qTokens.all { it in cTokens }) {
            return 0.9 - 0.02 * (cTokens.size - qTokens.size).coerceAtMost(5)
        }
        // Every query token is a prefix of some candidate token ("sam" vs "samuel").
        if (qTokens.all { qt -> qt.length >= 3 && cTokens.any { it.startsWith(qt) } }) return 0.75
        // Fuzzy: best token-level similarity, averaged over query tokens.
        val fuzzy = qTokens.map { qt -> cTokens.maxOf { similarity(qt, it) } }.average()
        val whole = similarity(q, c)
        return maxOf(fuzzy * 0.85, whole * 0.85)
    }

    /** 1 - normalized Levenshtein distance. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}

enum class YesNo { YES, NO, UNKNOWN }

/** Understands short spoken confirmations and refusals. */
object YesNoParser {
    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go ahead", "do it", "send it", "send", "confirm",
        "confirmed", "correct", "that's right", "thats right", "please do", "proceed", "continue", "right",
        "of course", "absolutely", "yes please", "go on", "sounds good",
    )
    private val NO = setOf(
        "no", "nope", "nah", "cancel", "stop", "don't", "dont", "do not", "never mind", "nevermind",
        "abort", "wrong", "not now", "no thanks", "forget it",
    )

    fun parse(input: String): YesNo {
        val n = TextNormalizer.normalize(input).removeSuffix(" please").removePrefix("please ")
        if (n.isEmpty()) return YesNo.UNKNOWN
        if (n in YES) return YesNo.YES
        if (n in NO) return YesNo.NO
        val words = n.split(' ')
        if (words.size <= 4) {
            if (words.first() in setOf("no", "nope", "cancel", "stop", "don't", "dont")) return YesNo.NO
            if (words.first() in setOf("yes", "yeah", "yep", "sure", "ok", "okay")) return YesNo.YES
        }
        return YesNo.UNKNOWN
    }
}

/** Converts spoken English number words ("twenty five", "a", "half") to integers. */
object NumberWords {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "a" to 1, "an" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /** Parses a number written as digits or words. Returns null when not a number. */
    fun parse(text: String): Int? {
        val t = text.trim().lowercase().replace('-', ' ')
        t.toIntOrNull()?.let { return it }
        val words = t.split(' ').filter { it.isNotEmpty() && it != "and" }
        if (words.isEmpty()) return null
        var total = 0
        var current = 0
        for (w in words) {
            when {
                w in UNITS -> current += UNITS.getValue(w)
                w in TENS -> current += TENS.getValue(w)
                w == "hundred" -> current = (if (current == 0) 1 else current) * 100
                w == "thousand" -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                else -> return null
            }
        }
        return total + current
    }

    /** Regex fragment matching a number in digits or common words. */
    const val PATTERN =
        "(?:\\d+|(?:(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -](?:one|two|three|four|five|six|seven|eight|nine))?)" +
            "|zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen" +
            "|seventeen|eighteen|nineteen|an|a)"
}
