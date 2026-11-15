package com.voicepilot.core

import com.voicepilot.core.action.Params
import com.voicepilot.core.conversation.AssistantStatus
import com.voicepilot.core.interpret.Interpretation
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.interpret.MessageRewriter
import com.voicepilot.core.messaging.UssdCodes
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.RiskLevel
import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.text.YesNo
import com.voicepilot.core.text.YesNoParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pidgin phrasing, reading/replying to messages, media, directions and USSD. */
class UpgradeTest {
    private val interpreter = LocalRuleInterpreter(testClock)

    private fun single(text: String): ActionRequest {
        val r = interpreter.interpretText(text)
        assertIs<Interpretation.Actions>(r, "Expected actions for '$text' but got $r")
        return r.requests.single()
    }

    // ------------------------------------------------------------ Pidgin

    @Test
    fun `pidgin messages`() {
        val r = single("Abeg send Tunde message say I dey come")
        assertEquals(ActionId.PREPARE_WHATSAPP_MESSAGE, r.action)
        assertEquals("Tunde", r.params[Params.RECIPIENT])
        assertEquals("I dey come", r.params[Params.MESSAGE])

        val give = single("send message give Ada say make she call me")
        assertEquals("Ada", give.params[Params.RECIPIENT])
        assertEquals("Make she call me", give.params[Params.MESSAGE])

        val tellAm = single("whatsapp Samuel tell am say I go call am after work")
        assertEquals("I go call you after work", tellAm.params[Params.MESSAGE])
    }

    @Test
    fun `pidgin commands`() {
        assertEquals("07:00", single("make you wake me by 7").params[Params.TIME])
        assertEquals("true", single("on the torch").params[Params.ENABLED])
        assertEquals("false", single("off light o").params[Params.ENABLED])
        assertEquals("up", single("add the sound").params[Params.DIRECTION])
        val remind = single("remind me say make I call mama by 5pm")
        assertEquals("Call mama", remind.params[Params.TEXT])
        assertEquals("2026-11-16T17:00", remind.params[Params.AT], "5pm already passed at 6pm, so tomorrow")
        val sleep = interpreter.interpretText("I wan sleep")
        assertIs<Interpretation.Actions>(sleep)
        assertEquals(ActionId.SET_DO_NOT_DISTURB, sleep.requests.first().action)
        assertIs<Interpretation.Answer>(interpreter.interpretText("wetin be the time"))
        assertIs<Interpretation.Answer>(interpreter.interpretText("wetin you fit do"))
    }

    @Test
    fun `pidgin confirmations`() {
        listOf("Oya", "ehen", "na so", "send am", "yes o").forEach { assertEquals(YesNo.YES, YesNoParser.parse(it), it) }
        listOf("no o", "leave am", "abeg no", "forget am").forEach { assertEquals(YesNo.NO, YesNoParser.parse(it), it) }
    }

    @Test
    fun `english am is not rewritten`() {
        assertEquals("I am outside", MessageRewriter.toDirectSpeech("saying I am outside", true))
        assertEquals("I go see you tomorrow", MessageRewriter.toDirectSpeech("say I go see am tomorrow", true))
    }

    // ------------------------------------------------------------ read and reply

    @Test
    fun `read messages phrasing`() {
        val all = single("read my messages")
        assertEquals(ActionId.READ_MESSAGES, all.action)
        assertTrue(all.params.isEmpty())
        val wa = single("read my new WhatsApp messages")
        assertEquals("WhatsApp", wa.params[Params.APP])
        assertEquals("Samuel", single("what did Samuel say").params[Params.SENDER])
        assertEquals("Ada", single("any new messages from Ada").params[Params.SENDER])
        assertEquals(ActionId.READ_MESSAGES, single("who message me").action)
    }

    @Test
    fun `reply phrasing`() {
        val r = single("reply saying I'm coming")
        assertEquals(ActionId.REPLY_TO_MESSAGE, r.action)
        assertNull(r.params[Params.SENDER])
        assertEquals("I'm coming", r.params[Params.MESSAGE])
        val to = single("reply to Ada saying see you soon")
        assertEquals("Ada", to.params[Params.SENDER])
        assertEquals("See you soon", to.params[Params.MESSAGE])
        assertEquals("I dey come", single("answer am say I dey come").params[Params.MESSAGE])
        assertEquals("On my way", single("reply: on my way").params[Params.MESSAGE])
    }

    @Test
    fun `reply always confirms and names the person`() = runTest {
        val env = TestEnv()
        env.settings = env.settings.copy(confirmationMode = com.voicepilot.core.settings.ConfirmationMode.HIGH_ONLY)
        assertEquals(RiskLevel.HIGH, ActionRegistry().require(ActionId.REPLY_TO_MESSAGE).risk)
        val r = env.session.submit("reply saying I'm coming")
        assertIs<AssistantStatus.AwaitingConfirmation>(r.status, "High risk asks even in high-only mode")
        assertTrue(r.text.contains("Reply to Samuel Okafor on WhatsApp: “I'm coming”"), r.text)
        assertTrue(env.handlers.executed.isEmpty())
        env.session.submit("oya")
        assertEquals("Samuel Okafor", env.handlers.executed.single().params[Params.SENDER])
    }

    @Test
    fun `reply without a message asks for it, and fails clearly with no inbox`() = runTest {
        val env = TestEnv()
        val q = env.session.submit("reply to Ada")
        assertEquals("What should I reply?", q.text)
        assertIs<AssistantStatus.AwaitingConfirmation>(env.session.submit("I'm on my way").status)

        val none = TestEnv(inbox = null)
        assertIs<AssistantStatus.Error>(none.session.submit("reply saying ok").status)

        val noAccess = TestEnv(granted = Capability.entries.toSet() - Capability.NOTIFICATION_LISTENER)
        assertIs<AssistantStatus.NeedsPermission>(noAccess.session.submit("read my messages").status)
    }

    // ------------------------------------------------------------ media, maps, USSD

    @Test
    fun `media`() {
        val play = single("play Burna Boy on Spotify")
        assertEquals(ActionId.PLAY_MEDIA, play.action)
        assertEquals("Burna Boy", play.params[Params.QUERY])
        assertEquals("spotify", play.params[Params.APP])
        assertNull(single("play some Asake").params[Params.APP])
        assertEquals("Asake", single("play some Asake").params[Params.QUERY])
        assertEquals("pause", single("pause the music").params[Params.COMMAND])
        assertEquals("next", single("next song").params[Params.COMMAND])
        assertEquals("next", single("change am").params[Params.COMMAND])
        assertEquals("play", single("resume").params[Params.COMMAND])
        assertEquals("previous", single("previous").params[Params.COMMAND])
    }

    @Test
    fun `directions`() {
        val nav = single("take me to Ikeja City Mall")
        assertEquals(ActionId.NAVIGATE, nav.action)
        assertEquals("Ikeja City Mall", nav.params[Params.DESTINATION])
        assertEquals("walking", single("how do I get to the bank by foot").params[Params.MODE])
        assertEquals("Lekki Phase 1", single("how I go reach Lekki Phase 1").params[Params.DESTINATION])
        assertEquals("motorcycle", single("carry me go Yaba by okada").params[Params.MODE])
    }

    @Test
    fun `airtime and data`() {
        assertEquals("*310#", single("check my airtime balance").params[Params.CODE])
        assertEquals("*310#", single("how much airtime I get").params[Params.CODE])
        assertEquals("*323#", single("check my data balance").params[Params.CODE])
        assertEquals("*312#", single("buy data").params[Params.CODE])
        assertEquals("*556#", single("dial star 556 hash").params[Params.CODE])
        assertEquals("*123*1#", single("dial star one two three star one hash").params[Params.CODE])
        val buy = interpreter.interpretText("buy 500 naira airtime")
        assertIs<Interpretation.Answer>(buy, "Payments are never automated")
        assertTrue(buy.text.contains("doesn't make payments"))
        assertNull(UssdCodes.fromSpeech("star hello hash"))
    }

    @Test
    fun `ussd confirms before opening the dialer`() = runTest {
        val env = TestEnv()
        val r = env.session.submit("check my airtime balance")
        assertIs<AssistantStatus.AwaitingConfirmation>(r.status)
        assertTrue(r.text.contains("Open the dialer with *310# (airtime balance)"), r.text)
        env.session.submit("yes")
        assertEquals(ActionId.DIAL_USSD, env.handlers.executed.single().action)
    }

    @Test
    fun `existing commands still win over new rules`() {
        assertEquals(ActionId.DIAL_CONTACT, single("call Ada").action)
        assertEquals(ActionId.OPEN_APP, single("open Spotify").action)
        assertIs<Interpretation.Actions>(interpreter.interpretText("I'm going to sleep"))
    }

    // ------------------------------------------------------------ approvals and small talk

    @Test
    fun `never ask mode runs messages without approval but replies still ask`() = runTest {
        val env = TestEnv()
        env.settings = env.settings.copy(confirmationMode = com.voicepilot.core.settings.ConfirmationMode.NEVER)
        val wa = env.session.submit("send Ada a WhatsApp message saying I'm outside")
        assertIs<AssistantStatus.Success>(wa.status, "Medium risk runs straight away in never-ask mode")
        assertEquals(ActionId.PREPARE_WHATSAPP_MESSAGE, env.handlers.executed.single().action)

        val reply = env.session.submit("reply saying ok")
        assertIs<AssistantStatus.AwaitingConfirmation>(reply.status, "Sending still asks unless separately allowed")
        env.session.submit("no")

        env.settings = env.settings.copy(sendWithoutAsking = true)
        assertIs<AssistantStatus.Success>(env.session.submit("reply saying ok").status)
        assertEquals(ActionId.REPLY_TO_MESSAGE, env.handlers.executed.last().action)
    }

    @Test
    fun `send without asking has no effect outside never-ask mode`() {
        val p = com.voicepilot.core.settings.ConfirmationPolicy
        assertTrue(p.requiresConfirmation(RiskLevel.HIGH, com.voicepilot.core.settings.ConfirmationMode.MEDIUM_AND_HIGH, sendWithoutAsking = true))
        assertTrue(p.requiresConfirmation(RiskLevel.HIGH, com.voicepilot.core.settings.ConfirmationMode.NEVER, sendWithoutAsking = false))
        kotlin.test.assertFalse(p.requiresConfirmation(RiskLevel.HIGH, com.voicepilot.core.settings.ConfirmationMode.NEVER, sendWithoutAsking = true))
        kotlin.test.assertFalse(p.requiresConfirmation(RiskLevel.MEDIUM, com.voicepilot.core.settings.ConfirmationMode.NEVER))
    }

    @Test
    fun `small talk is answered on the phone`() {
        listOf("how you doing", "how are you", "hello", "good evening", "how far", "thank you").forEach {
            assertIs<Interpretation.Answer>(interpreter.interpretText(it), it)
        }
        assertEquals("I dey kampe! Wetin you want make I do?", (interpreter.interpretText("how you dey") as Interpretation.Answer).text)
        assertEquals(ActionId.SET_FLASHLIGHT, single("off flash light").action)
    }
}
