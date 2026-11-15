package com.voicepilot.core

import com.voicepilot.core.action.Params
import com.voicepilot.core.conversation.AssistantStatus
import com.voicepilot.core.history.HistoryStatus
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.Capability
import com.voicepilot.core.settings.ConfirmationMode
import com.voicepilot.core.subscription.Plan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AssistantSessionTest {

    @Test
    fun `brief conversation - asks for message, finds contact, confirms, prepares`() = runTest {
        val env = TestEnv(contacts = FakeContacts(contact("1", "Samuel Okafor", "0803 123 4567"), contact("2", "Ada Eze", "0805")))
        val s = env.session

        val r1 = s.submit("Send Samuel a WhatsApp message.")
        assertEquals("What would you like me to tell Samuel?", r1.text)
        assertIs<AssistantStatus.AwaitingInput>(r1.status)

        val r2 = s.submit("Tell him I'm on my way.")
        assertIs<AssistantStatus.AwaitingConfirmation>(r2.status)
        assertTrue(r2.text.startsWith("I found Samuel Okafor."), r2.text)
        assertTrue(r2.text.contains("“I'm on my way”"), r2.text)
        assertTrue(r2.text.contains("tap Send in WhatsApp"), r2.text)
        assertTrue(env.handlers.executed.isEmpty(), "Nothing may run before confirmation")

        val r3 = s.submit("Yes.")
        assertIs<AssistantStatus.Success>(r3.status)
        assertTrue(r3.text.contains("Tap Send"), "Result must not claim the message was sent: ${r3.text}")
        val executed = env.handlers.executed.single()
        assertEquals(ActionId.PREPARE_WHATSAPP_MESSAGE, executed.action)
        assertEquals("2348031234567", executed.params[Params.PHONE])
        assertEquals("I'm on my way", executed.params[Params.MESSAGE])
        assertEquals(HistoryStatus.PREPARED, env.history.entries.last().status)
    }

    @Test
    fun `sms resolves the contact and other apps skip contact lookup`() = runTest {
        val env = TestEnv()
        val sms = env.session.submit("send Ada an SMS saying I'm outside")
        assertIs<AssistantStatus.AwaitingConfirmation>(sms.status)
        assertTrue(sms.text.contains("tap Send in your messaging app"), sms.text)
        env.session.submit("yes")
        assertEquals("2348055550101", env.handlers.executed.single().params[Params.PHONE])

        val noContacts = TestEnv(granted = Capability.entries.toSet() - Capability.CONTACTS)
        val telegram = noContacts.session.submit("message Tunde on Telegram saying the meeting has moved")
        assertIs<AssistantStatus.AwaitingConfirmation>(telegram.status, "Telegram needs no contact lookup")
        assertTrue(telegram.text.contains("In Telegram, you'll pick the chat and tap Send"), telegram.text)
        noContacts.session.submit("yes")
        assertEquals(ActionId.SHARE_MESSAGE_TO_APP, noContacts.handlers.executed.single().action)
    }

    @Test
    fun `duplicate names ask the user to choose and never guess`() = runTest {
        val env = TestEnv()
        val r1 = env.session.submit("WhatsApp Samuel saying good morning")
        val choice = assertIs<AssistantStatus.AwaitingChoice>(r1.status)
        assertEquals(2, choice.options.size)
        assertTrue(env.handlers.executed.isEmpty())

        val r2 = env.session.submit("the second one")
        assertIs<AssistantStatus.AwaitingConfirmation>(r2.status)
        assertTrue(r2.text.contains("Samuel Adeyemi"), r2.text)
        env.session.submit("yes")
        assertEquals("2348099990000", env.handlers.executed.single().params[Params.PHONE])
    }

    @Test
    fun `unknown contact fails without executing`() = runTest {
        val env = TestEnv()
        val r = env.session.submit("send Tunde a whatsapp message saying hi")
        assertIs<AssistantStatus.Error>(r.status)
        assertTrue(r.text.contains("couldn't find"))
        assertTrue(env.handlers.executed.isEmpty())
    }

    @Test
    fun `alias resolves my brother`() = runTest {
        val env = TestEnv(aliases = mapOf("brother" to "Samuel Okafor"))
        val r = env.session.submit("Send my brother a WhatsApp message saying I will call him after work")
        assertIs<AssistantStatus.AwaitingConfirmation>(r.status)
        env.session.submit("go ahead")
        val req = env.handlers.executed.single()
        assertEquals("I will call you after work", req.params[Params.MESSAGE])
        assertEquals("Samuel Okafor", req.params[Params.RECIPIENT_NAME])
    }

    @Test
    fun `declining confirmation cancels`() = runTest {
        val env = TestEnv()
        env.session.submit("message Ada saying I'm outside")
        val r = env.session.submit("no")
        assertEquals("Okay, I won't do that.", r.text)
        assertTrue(env.handlers.executed.isEmpty())
        assertEquals(HistoryStatus.CANCELLED, env.history.entries.last().status)
    }

    @Test
    fun `low risk actions run immediately with default policy`() = runTest {
        val env = TestEnv()
        val r = env.session.submit("open whatsapp")
        assertIs<AssistantStatus.Success>(r.status)
        assertEquals(ActionId.OPEN_APP, env.handlers.executed.single().action)
    }

    @Test
    fun `always-confirm policy asks even for low risk`() = runTest {
        val env = TestEnv()
        env.settings = env.settings.copy(confirmationMode = ConfirmationMode.ALWAYS)
        val r = env.session.submit("turn on the flashlight")
        assertIs<AssistantStatus.AwaitingConfirmation>(r.status)
    }

    @Test
    fun `sleep flow asks wake time then confirms both steps`() = runTest {
        val env = TestEnv()
        val r1 = env.session.submit("I'm going to sleep")
        assertEquals("I can turn on Do Not Disturb and set an alarm. What time should I wake you?", r1.text)
        val r2 = env.session.submit("6:30")
        val confirm = assertIs<AssistantStatus.AwaitingConfirmation>(r2.status)
        assertEquals(2, confirm.plan.steps.size)
        env.session.submit("yes")
        assertEquals(listOf(ActionId.SET_DO_NOT_DISTURB, ActionId.SET_ALARM), env.handlers.executed.map { it.action })
        assertEquals("06:30", env.handlers.executed[1].params[Params.TIME])
    }

    @Test
    fun `missing permission pauses and retry resumes`() = runTest {
        val env = TestEnv(granted = emptySet())
        val r = env.session.submit("remind me to drink water in 30 minutes")
        val needs = assertIs<AssistantStatus.NeedsPermission>(r.status)
        assertEquals(setOf(Capability.POST_NOTIFICATIONS), needs.capabilities)
        env.grantedCaps = Capability.entries.toSet()
        val retry = env.session.retryAfterPermissions()
        assertIs<AssistantStatus.Success>(retry.status)
        assertEquals("2026-11-15T18:30", env.handlers.executed.single().params[Params.AT])
    }

    @Test
    fun `disabled integrations are blocked`() = runTest {
        val env = TestEnv()
        env.settings = env.settings.copy(disabledActions = setOf(ActionId.SET_FLASHLIGHT))
        val r = env.session.submit("turn on the torch")
        assertIs<AssistantStatus.Error>(r.status)
        assertTrue(env.handlers.executed.isEmpty())
    }

    @Test
    fun `partial failures are reported separately`() = runTest {
        val env = TestEnv()
        env.handlers.failOn = setOf(ActionId.SET_FLASHLIGHT)
        val r = env.session.submit("turn on the flashlight and set a timer for 5 minutes")
        assertIs<AssistantStatus.Error>(r.status)
        assertTrue(r.text.contains("Start a timer for 5 minutes"), r.text)
        assertTrue(r.text.contains("I couldn't turn on the flashlight"), r.text)
        assertEquals(2, env.handlers.executed.size)
    }

    @Test
    fun `free plan limits steps per request`() = runTest {
        val routine = Routine(
            name = "Work mode",
            triggerPhrases = listOf("prepare my phone for work"),
            steps = listOf(
                ActionRequest(ActionId.SET_VOLUME, mapOf(Params.LEVEL to "30")),
                ActionRequest(ActionId.OPEN_APP, mapOf(Params.APP to "Calendar")),
                ActionRequest(ActionId.SET_DO_NOT_DISTURB, mapOf(Params.ENABLED to "true")),
                ActionRequest(ActionId.OPEN_APP, mapOf(Params.APP to "Slack")),
            ),
        )
        val free = TestEnv(routines = listOf(routine), plan = Plan.FREE)
        assertIs<AssistantStatus.Error>(free.session.submit("Prepare my phone for work").status)

        val premium = TestEnv(routines = listOf(routine), plan = Plan.PREMIUM)
        val r = premium.session.submit("Prepare my phone for work")
        assertIs<AssistantStatus.AwaitingConfirmation>(r.status) // contains a medium-risk DND step
        premium.session.submit("yes")
        assertEquals(4, premium.handlers.executed.size)
    }

    @Test
    fun `cancel word drops a pending question`() = runTest {
        val env = TestEnv()
        env.session.submit("set an alarm")
        val r = env.session.submit("never mind")
        assertEquals("Okay, cancelled.", r.text)
        assertIs<AssistantStatus.Idle>(env.session.status.value)
    }

    @Test
    fun `wake phrase is stripped from typed or spoken input`() = runTest {
        val env = TestEnv()
        env.session.submit("Hey SL open the camera")
        assertEquals(ActionId.LAUNCH_CAMERA, env.handlers.executed.single().action)
    }

    @Test
    fun `history disabled stores nothing`() = runTest {
        val env = TestEnv()
        env.settings = env.settings.copy(historyEnabled = false)
        env.session.submit("open whatsapp")
        assertTrue(env.history.entries.isEmpty())
    }

    @Test
    fun `retry from history re-resolves contacts`() = runTest {
        val env = TestEnv()
        val request = ActionRequest(ActionId.PREPARE_WHATSAPP_MESSAGE, mapOf(Params.RECIPIENT to "Ada", Params.MESSAGE to "Hi"))
        val r = env.session.retry(request, "message Ada saying hi")
        assertIs<AssistantStatus.AwaitingConfirmation>(r.status)
        env.session.submit("yes")
        assertEquals("2348055550101", env.handlers.executed.single().params[Params.PHONE])
    }
}
