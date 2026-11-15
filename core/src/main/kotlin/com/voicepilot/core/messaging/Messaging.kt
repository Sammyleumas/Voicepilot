package com.voicepilot.core.messaging

import com.voicepilot.core.text.NumberWords

/** A received message VoicePilot can reply to (from a notification's reply action). */
data class ReplyTarget(val sender: String, val appLabel: String)

/** Read-only view of received messages, implemented by the Android notification assistant. */
fun interface MessageInbox {
    /** The message a reply would go to: the given sender, else the last one read, else the newest. */
    fun replyTarget(sender: String?, app: String?): ReplyTarget?
}

/**
 * USSD helpers. Codes are only ever placed in the dialer; the user taps Call.
 * The common codes below are the ones harmonised across Nigerian networks by the NCC.
 */
object UssdCodes {
    const val AIRTIME_BALANCE = "*310#"
    const val DATA_BALANCE = "*323#"
    const val DATA_PLANS = "*312#"
    val KNOWN = mapOf(AIRTIME_BALANCE to "airtime balance", DATA_BALANCE to "data balance", DATA_PLANS to "data plans")

    private val CODE = Regex("^[*#][0-9*]{1,30}#$")

    /** "star 310 hash", "*310#", "star three one zero hash" → "*310#". Null if it isn't a code. */
    fun fromSpeech(spoken: String): String? {
        val direct = spoken.replace(" ", "")
        if (CODE.matches(direct)) return direct
        val out = StringBuilder()
        for (word in spoken.lowercase().replace("-", " ").split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            when {
                word in setOf("star", "asterisk", "*") -> out.append('*')
                word in setOf("hash", "pound", "harsh", "#", "hashtag") -> out.append('#')
                word.all { it.isDigit() || it == '*' || it == '#' } -> out.append(word)
                word in setOf("oh", "o") -> out.append('0')
                else -> {
                    val n = NumberWords.parse(word) ?: return null
                    if (n > 9) return null
                    out.append(n)
                }
            }
        }
        return out.toString().takeIf { CODE.matches(it) }
    }
}
