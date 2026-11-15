package com.voicepilot.core.action

import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.OutcomeKind
import com.voicepilot.core.model.RiskLevel
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** The type of a single action parameter. Values always travel as strings on the wire. */
sealed interface ParamType {
    data class Text(val maxLength: Int = 1000) : ParamType
    data class Integer(val min: Int, val max: Int) : ParamType
    data object Bool : ParamType
    /** Local time formatted as HH:mm. */
    data object Time : ParamType
    /** Local date-time formatted as yyyy-MM-ddTHH:mm. */
    data object DateTime : ParamType
    data class Choice(val values: Set<String>) : ParamType
    /** International phone number, digits only, no leading plus. */
    data object Phone : ParamType
    /** USSD code such as *310#: starts with a star or hash and ends with a hash. */
    data object Ussd : ParamType
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean,
    val description: String,
    /** Short question asked when a required value is missing. */
    val question: String = "What should I use for $name?",
)

sealed interface ValidationResult {
    /** All parameters are present and normalized. */
    data class Valid(val request: ActionRequest) : ValidationResult
    data class MissingParams(val request: ActionRequest, val missing: List<ParamSpec>) : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

/**
 * Describes one allowlisted action: parameters, required capabilities, risk and
 * whether it completes inside VoicePilot or only prepares work in another app.
 */
data class ActionDefinition(
    val id: ActionId,
    val title: String,
    val description: String,
    val params: List<ParamSpec>,
    val risk: RiskLevel,
    val outcome: OutcomeKind,
    val capabilities: Set<Capability> = emptySet(),
    /** Capabilities needed only when a given parameter is absent (e.g. contacts when no phone is supplied). */
    val conditionalCapabilities: Map<String, Set<Capability>> = emptyMap(),
    /** Display name of the app mainly involved, used in history. */
    val appLabel: String? = null,
    /** At least one of these (optional) parameters must be present. */
    val requireAnyOf: List<String> = emptyList(),
) {
    fun spec(name: String): ParamSpec? = params.firstOrNull { it.name == name }

    /** Capabilities required for this concrete request. */
    fun requiredCapabilities(request: ActionRequest): Set<Capability> =
        capabilities + conditionalCapabilities
            .filterKeys { request.param(it) == null }
            .values.flatten()

    /**
     * Strictly validates [request]: unknown parameters are rejected, values are
     * type-checked and normalized, and missing required values are reported.
     */
    fun validate(request: ActionRequest): ValidationResult {
        if (request.action != id) return ValidationResult.Invalid(listOf("Action mismatch: ${request.action} vs $id"))
        val errors = mutableListOf<String>()
        val normalized = linkedMapOf<String, String>()
        for ((name, raw) in request.params) {
            val spec = spec(name)
            if (spec == null) {
                errors += "Unknown parameter '$name' for ${id.wireName}"
                continue
            }
            if (raw.isBlank()) continue
            when (val value = normalize(spec.type, raw.trim())) {
                null -> errors += "Invalid value for '$name'"
                else -> normalized[name] = value
            }
        }
        if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)
        val clean = request.copy(params = normalized)
        val missing = params.filter { it.required && normalized[it.name] == null }.toMutableList()
        if (requireAnyOf.isNotEmpty() && requireAnyOf.none { normalized[it] != null }) {
            spec(requireAnyOf.first())?.let { missing += it }
        }
        return if (missing.isEmpty()) ValidationResult.Valid(clean) else ValidationResult.MissingParams(clean, missing)
    }

    companion object {
        private val USSD = Regex("^[*#][0-9*]{1,30}#$")
        private val TRUE_WORDS = setOf("true", "on", "yes", "enable", "enabled", "1")
        private val FALSE_WORDS = setOf("false", "off", "no", "disable", "disabled", "0")

        internal fun normalize(type: ParamType, raw: String): String? = when (type) {
            is ParamType.Text -> raw.takeIf { it.length <= type.maxLength }
            is ParamType.Integer -> raw.toIntOrNull()?.takeIf { it in type.min..type.max }?.toString()
            ParamType.Bool -> when (raw.lowercase()) {
                in TRUE_WORDS -> "true"
                in FALSE_WORDS -> "false"
                else -> null
            }
            ParamType.Time -> try {
                LocalTime.parse(if (raw.length == 4 && raw[1] == ':') "0$raw" else raw).withSecond(0).withNano(0).toString()
            } catch (_: DateTimeParseException) {
                null
            }
            ParamType.DateTime -> try {
                LocalDateTime.parse(raw).withSecond(0).withNano(0).toString()
            } catch (_: DateTimeParseException) {
                null
            }
            is ParamType.Choice -> raw.lowercase().takeIf { it in type.values }
            ParamType.Phone -> raw.filter { it.isDigit() }.takeIf { it.length in 7..15 }
            ParamType.Ussd -> raw.replace(" ", "").takeIf { USSD.matches(it) }
        }
    }
}
