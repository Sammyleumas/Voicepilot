package com.voicepilot.core

import com.voicepilot.core.action.ActionExecutor
import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.action.BuiltInActions
import com.voicepilot.core.action.Params
import com.voicepilot.core.action.Preflight
import com.voicepilot.core.action.ValidationResult
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.FailureReason
import com.voicepilot.core.model.RiskLevel
import com.voicepilot.core.planner.PlanEvent
import com.voicepilot.core.planner.PlanRunner
import com.voicepilot.core.planner.PlanStep
import com.voicepilot.core.planner.TaskPlan
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.settings.ConfirmationMode
import com.voicepilot.core.settings.ConfirmationPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineTest {
    private val registry = ActionRegistry()

    @Test
    fun `every action id is registered exactly once`() {
        assertEquals(ActionId.entries.toSet(), BuiltInActions.all.map { it.id }.toSet())
        assertEquals(ActionId.entries.size, BuiltInActions.all.size)
    }

    @Test
    fun `wire names round trip`() {
        ActionId.entries.forEach { assertEquals(it, ActionId.fromWireName(it.wireName)) }
        assertEquals(null, ActionId.fromWireName("delete_all_files"))
    }

    @Test
    fun `validation normalizes and rejects`() {
        val alarm = registry.require(ActionId.SET_ALARM)
        val valid = assertIs<ValidationResult.Valid>(alarm.validate(ActionRequest(ActionId.SET_ALARM, mapOf(Params.TIME to "7:05"))))
        assertEquals("07:05", valid.request.params[Params.TIME])
        assertIs<ValidationResult.Invalid>(alarm.validate(ActionRequest(ActionId.SET_ALARM, mapOf(Params.TIME to "25:00"))))
        assertIs<ValidationResult.Invalid>(alarm.validate(ActionRequest(ActionId.SET_ALARM, mapOf(Params.TIME to "07:00", "shell" to "rm -rf /"))))
        val missing = assertIs<ValidationResult.MissingParams>(alarm.validate(ActionRequest(ActionId.SET_ALARM)))
        assertEquals(Params.TIME, missing.missing.single().name)

        val flash = registry.require(ActionId.SET_FLASHLIGHT)
        val on = assertIs<ValidationResult.Valid>(flash.validate(ActionRequest(ActionId.SET_FLASHLIGHT, mapOf(Params.ENABLED to "On"))))
        assertEquals("true", on.request.params[Params.ENABLED])

        val volume = registry.require(ActionId.SET_VOLUME)
        assertIs<ValidationResult.MissingParams>(volume.validate(ActionRequest(ActionId.SET_VOLUME)))
        assertIs<ValidationResult.Invalid>(volume.validate(ActionRequest(ActionId.SET_VOLUME, mapOf(Params.LEVEL to "150"))))

        val settings = registry.require(ActionId.OPEN_SETTINGS)
        assertIs<ValidationResult.Invalid>(settings.validate(ActionRequest(ActionId.OPEN_SETTINGS, mapOf(Params.PAGE to "developer_secret"))))
    }

    @Test
    fun `risk classification`() {
        assertEquals(RiskLevel.LOW, registry.require(ActionId.OPEN_APP).risk)
        assertEquals(RiskLevel.MEDIUM, registry.require(ActionId.PREPARE_WHATSAPP_MESSAGE).risk)
        assertEquals(RiskLevel.MEDIUM, registry.require(ActionId.CREATE_CALENDAR_EVENT).risk)
        assertTrue(ConfirmationPolicy.requiresConfirmation(RiskLevel.HIGH, ConfirmationMode.HIGH_ONLY))
        assertFalse(ConfirmationPolicy.requiresConfirmation(RiskLevel.MEDIUM, ConfirmationMode.HIGH_ONLY))
        assertTrue(ConfirmationPolicy.requiresConfirmation(RiskLevel.MEDIUM, ConfirmationMode.MEDIUM_AND_HIGH))
        assertFalse(ConfirmationPolicy.requiresConfirmation(RiskLevel.LOW, ConfirmationMode.MEDIUM_AND_HIGH))
        assertTrue(ConfirmationPolicy.requiresConfirmation(RiskLevel.LOW, ConfirmationMode.ALWAYS))
    }

    @Test
    fun `contacts capability is only needed when phone is unresolved`() {
        val def = registry.require(ActionId.PREPARE_WHATSAPP_MESSAGE)
        assertEquals(setOf(Capability.CONTACTS), def.requiredCapabilities(ActionRequest(ActionId.PREPARE_WHATSAPP_MESSAGE, mapOf(Params.RECIPIENT to "Ada"))))
        assertEquals(emptySet(), def.requiredCapabilities(ActionRequest(ActionId.PREPARE_WHATSAPP_MESSAGE, mapOf(Params.RECIPIENT to "Ada", Params.PHONE to "2348031234567"))))
    }

    @Test
    fun `executor converts exceptions and missing handlers into failures`() = runTest {
        val executor = ActionExecutor(
            registry,
            mapOf(
                ActionId.OPEN_APP to com.voicepilot.core.action.ActionHandler { throw IllegalStateException("boom") },
                ActionId.SET_FLASHLIGHT to com.voicepilot.core.action.ActionHandler { throw SecurityException("no") },
            ),
            { true },
            { AssistantSettings() },
        )
        val crash = assertIs<ActionResult.Failed>(executor.execute(ActionRequest(ActionId.OPEN_APP, mapOf(Params.APP to "x"))))
        assertEquals(FailureReason.UNKNOWN, crash.reason)
        val security = assertIs<ActionResult.Failed>(executor.execute(ActionRequest(ActionId.SET_FLASHLIGHT, mapOf(Params.ENABLED to "true"))))
        assertEquals(FailureReason.PERMISSION_MISSING, security.reason)
        val p = executor.preflight(ActionRequest(ActionId.SET_TIMER, mapOf(Params.SECONDS to "60")))
        assertEquals(FailureReason.UNSUPPORTED_ON_DEVICE, assertIs<Preflight.Rejected>(p).result.reason)
    }

    @Test
    fun `plan runner skips dependants of failed steps`() = runTest {
        val handlers = RecordingHandlers().apply { failOn = setOf(ActionId.OPEN_APP) }
        val executor = ActionExecutor(registry, handlers.handlers, { true }, { AssistantSettings() })
        val open = ActionRequest(ActionId.OPEN_APP, mapOf(Params.APP to "Calendar"))
        val timer = ActionRequest(ActionId.SET_TIMER, mapOf(Params.SECONDS to "60"))
        val note = ActionRequest(ActionId.SAVE_NOTE, mapOf(Params.TEXT to "x"))
        val plan = TaskPlan(
            "test",
            listOf(
                PlanStep(0, open, registry.require(ActionId.OPEN_APP)),
                PlanStep(1, timer, registry.require(ActionId.SET_TIMER), dependsOn = setOf(0)),
                PlanStep(2, note, registry.require(ActionId.SAVE_NOTE)),
            ),
        )
        val events = PlanRunner(executor).run(plan).toList()
        val report = assertIs<PlanEvent.Finished>(events.last()).report
        assertEquals(1, report.failed.size)
        assertEquals(1, report.skipped.size)
        assertEquals(1, report.succeeded.size)
        assertEquals(listOf(ActionId.OPEN_APP, ActionId.SAVE_NOTE), handlers.executed.map { it.action })
    }
}
