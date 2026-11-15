package com.voicepilot.core.contacts

import com.voicepilot.core.text.NameMatcher
import com.voicepilot.core.text.TextNormalizer

data class PhoneNumber(val number: String, val label: String? = null, val isPrimary: Boolean = false)

data class Contact(val id: String, val displayName: String, val phones: List<PhoneNumber>)

/** Read access to the user's address book. Implemented by the Android layer. */
interface ContactDirectory {
    /** Returns contacts whose names may match [query]. May return the whole book for small address books. */
    suspend fun search(query: String): List<Contact>
}

/** One concrete (contact, number) pair the user can pick. */
data class ContactCandidate(val contact: Contact, val phone: PhoneNumber, val score: Double) {
    /** A privacy-preserving description, e.g. "Samuel Okafor (mobile ••• 4567)". */
    val description: String
        get() {
            val digits = phone.number.filter { it.isDigit() }
            val tail = if (digits.length >= 4) digits.takeLast(4) else digits
            val label = phone.label?.lowercase()?.let { "$it " } ?: ""
            return "${contact.displayName} (${label}••• $tail)"
        }
}

sealed interface ContactResolution {
    data class Resolved(val candidate: ContactCandidate) : ContactResolution
    /** Several plausible matches, or a single weak match that must be confirmed. */
    data class Ambiguous(val query: String, val candidates: List<ContactCandidate>) : ContactResolution
    data class NotFound(val query: String) : ContactResolution
}

/** Looks up user-defined aliases such as "my brother" → "Samuel Okafor". */
fun interface AliasLookup {
    suspend fun contactNameFor(alias: String): String?
}

/**
 * Resolves spoken recipient descriptions to a single phone number. It never
 * guesses: anything other than one clear match produces [ContactResolution.Ambiguous]
 * or [ContactResolution.NotFound] so the user can choose.
 */
class ContactResolver(
    private val directory: ContactDirectory,
    private val aliases: AliasLookup,
) {
    suspend fun resolve(spoken: String): ContactResolution {
        val query = cleanQuery(spoken)
        if (query.isEmpty()) return ContactResolution.NotFound(spoken)
        val aliasTarget = aliases.contactNameFor(query) ?: aliases.contactNameFor(stripPossessive(query))
        val effective = aliasTarget ?: stripPossessive(query)
        val contacts = directory.search(effective)
        val candidates = contacts.flatMap { c ->
            val score = NameMatcher.score(effective, c.displayName)
            val phones = choosePhones(c.phones)
            phones.map { ContactCandidate(c, it, score) }
        }.filter { it.score >= MIN_SCORE }
            .sortedByDescending { it.score }
            .distinctBy { it.contact.displayName.lowercase() to it.phone.number.filter(Char::isDigit) }
            .take(MAX_CANDIDATES)

        if (candidates.isEmpty()) return ContactResolution.NotFound(spoken)
        val top = candidates.first()
        val runnerUp = candidates.getOrNull(1)
        val clear = top.score >= STRONG_SCORE && (runnerUp == null || top.score - runnerUp.score >= MARGIN)
        return if (clear) ContactResolution.Resolved(top) else ContactResolution.Ambiguous(spoken, candidates)
    }

    /** Prefers the primary number; if none is primary, every number is a candidate. */
    private fun choosePhones(phones: List<PhoneNumber>): List<PhoneNumber> {
        val usable = phones.filter { p -> p.number.count { it.isDigit() } >= 7 }
        val distinct = usable.distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
        return distinct.filter { it.isPrimary }.ifEmpty { distinct }
    }

    companion object {
        const val MIN_SCORE = 0.6
        const val STRONG_SCORE = 0.85
        const val MARGIN = 0.1
        const val MAX_CANDIDATES = 5

        fun cleanQuery(spoken: String): String = TextNormalizer.normalize(spoken)
            .removePrefix("to ")
            .replace(Regex("\\b(on whatsapp|via whatsapp|on phone)\\b"), "")
            .trim()

        fun stripPossessive(q: String): String = q.removePrefix("my ").removePrefix("our ").trim()
    }
}

/**
 * Picks one candidate from a spoken reply such as "the second one", "2",
 * "Samuel Okafor" or "the one ending 4567". Returns null if unclear.
 */
object CandidateChooser {
    private val ORDINALS = listOf(
        "first" to 0, "1st" to 0, "one" to 0, "1" to 0,
        "second" to 1, "2nd" to 1, "two" to 1, "2" to 1,
        "third" to 2, "3rd" to 2, "three" to 2, "3" to 2,
        "fourth" to 3, "4th" to 3, "four" to 3, "4" to 3,
        "fifth" to 4, "5th" to 4, "five" to 4, "5" to 4,
    )

    private val CARDINAL_WORDS = setOf("one", "two", "three", "four", "five")

    fun choose(reply: String, candidates: List<ContactCandidate>): ContactCandidate? {
        val n = TextNormalizer.normalize(reply)
        if (n.isEmpty()) return null
        val digits = n.filter { it.isDigit() }
        if (digits.length >= 4) {
            candidates.filter { it.phone.number.filter(Char::isDigit).endsWith(digits) }.singleOrNull()?.let { return it }
        }
        val words = n.split(' ')
        // Ordinals and digits first, so "the second one" isn't read as "one".
        for (pass in listOf(ORDINALS.filter { it.first !in CARDINAL_WORDS }, ORDINALS.filter { it.first in CARDINAL_WORDS })) {
            for ((word, index) in pass) {
                if (word in words && index < candidates.size) return candidates[index]
            }
        }
        if (n in setOf("last", "the last one", "last one")) return candidates.lastOrNull()
        val scored = candidates.map { it to NameMatcher.score(n, it.contact.displayName) }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)
        if (best.second >= 0.85 && (second == null || best.second - second.second >= 0.1)) return best.first
        return null
    }
}
