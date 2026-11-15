package com.voicepilot.core.contacts

/**
 * Converts locally formatted phone numbers to the international digits-only form
 * required by WhatsApp Click to Chat (e.g. "0803 123 4567" → "2348031234567"
 * when the default calling code is 234).
 */
object PhoneNumberNormalizer {

    fun toInternationalDigits(raw: String, defaultCountryCode: String): String? {
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val cc = defaultCountryCode.filter { it.isDigit() }
        digits = when {
            hasPlus -> digits
            digits.startsWith("00") -> digits.drop(2)
            cc.isNotEmpty() && digits.startsWith(cc) && digits.length > cc.length + 7 -> digits
            digits.startsWith("0") && cc.isNotEmpty() -> cc + digits.drop(1)
            cc.isNotEmpty() && digits.length in 7..10 -> cc + digits
            else -> digits
        }
        return digits.takeIf { it.length in 8..15 }
    }
}
