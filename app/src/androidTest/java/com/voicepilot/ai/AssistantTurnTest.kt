package com.voicepilot.ai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicepilot.ai.ui.assistant.AssistantTurn
import com.voicepilot.ai.ui.theme.VoicePilotTheme
import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.action.Params
import com.voicepilot.core.conversation.AssistantStatus
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.planner.PlanStep
import com.voicepilot.core.planner.TaskPlan
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantTurnTest {
    @get:Rule val compose = createComposeRule()

    private val request = ActionRequest(
        ActionId.PREPARE_WHATSAPP_MESSAGE,
        mapOf(Params.RECIPIENT to "Samuel", Params.RECIPIENT_NAME to "Samuel Okafor", Params.PHONE to "2348031234567", Params.MESSAGE to "I'm on my way"),
    )
    private val plan = TaskPlan("send_message", listOf(PlanStep(0, request, ActionRegistry().require(ActionId.PREPARE_WHATSAPP_MESSAGE), needsConfirmation = true)))

    @Test fun confirmationCardShowsPreviewAndReportsChoice() {
        var answer: Boolean? = null
        compose.setContent {
            VoicePilotTheme {
                AssistantTurn(AssistantStatus.AwaitingConfirmation(plan, "Shall I go ahead?"), onConfirm = { answer = it }, onChoose = {}, onRetryPermissions = {})
            }
        }
        compose.onNodeWithText("Open WhatsApp to Samuel Okafor with the message “I'm on my way”").assertIsDisplayed()
        compose.onNodeWithText("Go ahead").performClick()
        assertEquals(true, answer)
    }

    @Test fun choiceListOffersTouchAlternative() {
        var chosen = -1
        compose.setContent {
            VoicePilotTheme {
                AssistantTurn(AssistantStatus.AwaitingChoice("Which one?", listOf("Samuel Okafor (mobile ••• 4567)", "Samuel Adeyemi (mobile ••• 0000)")), onConfirm = {}, onChoose = { chosen = it }, onRetryPermissions = {})
            }
        }
        compose.onNodeWithText("2. Samuel Adeyemi (mobile ••• 0000)").performClick()
        assertEquals(1, chosen)
    }
}
