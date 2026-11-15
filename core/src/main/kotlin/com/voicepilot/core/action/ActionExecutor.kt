package com.voicepilot.core.action

import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.FailureReason
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.settings.ConfirmationPolicy
import kotlinx.coroutines.CancellationException

/** Executes one validated action on the platform. Implemented by the Android layer. */
fun interface ActionHandler {
    suspend fun execute(request: ActionRequest): ActionResult
}

/** Reports whether a platform capability is currently granted. */
fun interface CapabilityChecker {
    fun isGranted(capability: Capability): Boolean
}

/** Result of checking a request before execution. */
sealed interface Preflight {
    data class Ready(
        val request: ActionRequest,
        val definition: ActionDefinition,
        val needsConfirmation: Boolean,
    ) : Preflight

    data class NeedsInput(val request: ActionRequest, val definition: ActionDefinition, val missing: List<ParamSpec>) : Preflight
    data class NeedsCapabilities(val request: ActionRequest, val definition: ActionDefinition, val missing: Set<Capability>) : Preflight
    data class Rejected(val result: ActionResult.Failed) : Preflight
}

/**
 * The single gate every action passes through. It checks the allowlist, strict
 * parameter validation, user settings, capabilities and confirmation policy, and
 * converts handler exceptions into truthful [ActionResult]s.
 */
class ActionExecutor(
    private val registry: ActionRegistry,
    private val handlers: Map<ActionId, ActionHandler>,
    private val capabilities: CapabilityChecker,
    private val settings: () -> AssistantSettings,
) {
    fun definition(id: ActionId): ActionDefinition? = registry[id]

    fun preflight(request: ActionRequest): Preflight {
        val definition = registry[request.action]
            ?: return Preflight.Rejected(ActionResult.Failed("That action isn't supported.", FailureReason.BLOCKED_BY_POLICY))
        val current = settings()
        if (request.action in current.disabledActions) {
            return Preflight.Rejected(
                ActionResult.Failed(
                    "${definition.title} is turned off in your automation settings.",
                    FailureReason.BLOCKED_BY_POLICY,
                    recoverable = true,
                ),
            )
        }
        if (request.action !in handlers) {
            return Preflight.Rejected(
                ActionResult.Failed("${definition.title} isn't available on this device yet.", FailureReason.UNSUPPORTED_ON_DEVICE),
            )
        }
        val validated = when (val v = definition.validate(request)) {
            is ValidationResult.Invalid -> return Preflight.Rejected(
                ActionResult.Failed("I couldn't understand some details for ${definition.title.lowercase()}.", FailureReason.INVALID_PARAMETERS, true),
            )
            is ValidationResult.MissingParams -> return Preflight.NeedsInput(v.request, definition, v.missing)
            is ValidationResult.Valid -> v.request
        }
        val missingCaps = definition.requiredCapabilities(validated).filterNot(capabilities::isGranted).toSet()
        if (missingCaps.isNotEmpty()) return Preflight.NeedsCapabilities(validated, definition, missingCaps)
        val confirm = ConfirmationPolicy.requiresConfirmation(definition.risk, current.confirmationMode)
        return Preflight.Ready(validated, definition, confirm)
    }

    /**
     * Executes a request that already passed [preflight]. Re-validates defensively
     * so callers can never bypass the gate.
     */
    suspend fun execute(request: ActionRequest): ActionResult {
        val ready = when (val p = preflight(request)) {
            is Preflight.Ready -> p
            is Preflight.Rejected -> return p.result
            is Preflight.NeedsInput -> return ActionResult.Failed(
                "Missing details: ${p.missing.joinToString { it.name }}.", FailureReason.INVALID_PARAMETERS, true,
            )
            is Preflight.NeedsCapabilities -> return ActionResult.Failed(
                "VoicePilot needs ${p.missing.joinToString { it.displayName }} permission for this.",
                FailureReason.PERMISSION_MISSING,
                recoverable = true,
            )
        }
        val handler = handlers.getValue(ready.request.action)
        return try {
            handler.execute(ready.request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            ActionResult.Failed("Android blocked this action because a permission is missing.", FailureReason.PERMISSION_MISSING, true)
        } catch (e: Exception) {
            ActionResult.Failed("Something went wrong while running ${ready.definition.title.lowercase()}.", FailureReason.UNKNOWN, true)
        }
    }
}
