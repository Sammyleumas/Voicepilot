package com.voicepilot.core

import com.voicepilot.core.action.Params
import com.voicepilot.core.interpret.Interpretation
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalRuleInterpreterTest {
    private val interpreter = LocalRuleInterpreter(testClock)

    private fun single(text: String): ActionRequest {
        val result = interpreter.interpretText(text)
        assertIs<Interpretation.Actions>(result, "Expected actions for '$text' but got $result")
        assertEquals(1, result.requests.size, "Expected one action for '$text': ${result.requests}")
        return result.requests.first()
    }

    @Test
    fun `opens apps with filler words`() {
        val r = single("Hey VoicePilot, please open WhatsApp")
        assertEquals(ActionId.OPEN_APP, r.action)
        assertEquals("WhatsApp", r.params[Params.APP])
        assertEquals("YouTube", single("could you launch the YouTube app for me").params[Params.APP])
    }

    @Test
    fun `whatsapp message from the product brief`() {
        val r = single("Open WhatsApp and send Samuel a message telling him I'll arrive in 20 minutes.")
        assertEquals(ActionId.PREPARE_WHATSAPP_MESSAGE, r.action)
        assertEquals("Samuel", r.params[Params.RECIPIENT])
        assertEquals("I'll arrive in 20 minutes", r.params[Params.MESSAGE])
    }

    @Test
    fun `whatsapp reported speech is rewritten to the recipient`() {
        val r = single("Send my brother a WhatsApp message saying I will call him after work")
        assertEquals("my brother", r.params[Params.RECIPIENT])
        assertEquals("I will call you after work", r.params[Params.MESSAGE])
    }

    @Test
    fun `whatsapp variants`() {
        assertEquals("Ada", single("WhatsApp Ada and tell her I'm outside").params[Params.RECIPIENT])
        assertEquals("I'm outside", single("WhatsApp Ada and tell her I'm outside").params[Params.MESSAGE])
        val toForm = single("send a whatsapp message to Chidi saying the meeting has moved")
        assertEquals("Chidi", toForm.params[Params.RECIPIENT])
        assertEquals("The meeting has moved", toForm.params[Params.MESSAGE])
        val noMessage = single("Send Samuel a WhatsApp message")
        assertEquals("Samuel", noMessage.params[Params.RECIPIENT])
        assertNull(noMessage.params[Params.MESSAGE])
    }

    @Test
    fun `messages go to the app the user names`() {
        val sms = single("send Ada an SMS saying I'm outside")
        assertEquals(ActionId.PREPARE_SMS, sms.action)
        assertEquals("Ada", sms.params[Params.RECIPIENT])
        assertEquals("I'm outside", sms.params[Params.MESSAGE])

        val textMessage = single("send a text message to Chidi saying call me back")
        assertEquals(ActionId.PREPARE_SMS, textMessage.action)
        assertEquals("Chidi", textMessage.params[Params.RECIPIENT])

        val telegram = single("message Tunde on Telegram saying the meeting has moved")
        assertEquals(ActionId.SHARE_MESSAGE_TO_APP, telegram.action)
        assertEquals("Telegram", telegram.params[Params.APP])
        assertEquals("Tunde", telegram.params[Params.RECIPIENT])
        assertEquals("The meeting has moved", telegram.params[Params.MESSAGE])

        val bySms = single("message Ada by SMS saying I'm outside")
        assertEquals(ActionId.PREPARE_SMS, bySms.action)
        assertEquals("Ada", bySms.params[Params.RECIPIENT])

        val messenger = single("send Ada a Messenger message saying happy birthday")
        assertEquals("Messenger", messenger.params[Params.APP])

        // No app named: the user's default channel decides.
        assertEquals(ActionId.PREPARE_WHATSAPP_MESSAGE, single("message Ada saying I'm outside").action)
        val smsDefault = LocalRuleInterpreter(testClock) { com.voicepilot.core.settings.MessagingChannel.SMS }
            .interpretText("message Ada saying I'm outside")
        assertEquals(ActionId.PREPARE_SMS, (smsDefault as Interpretation.Actions).requests.single().action)
    }

    @Test
    fun `alarms and timers`() {
        assertEquals("06:30", single("set an alarm for 6:30 am").params[Params.TIME])
        assertEquals("07:00", single("wake me up at 7").params[Params.TIME])
        assertEquals("19:30", single("set alarm for 7:30pm").params[Params.TIME])
        assertEquals("600", single("set a timer for 10 minutes").params[Params.SECONDS])
        assertEquals("900", single("start a 15 minute timer").params[Params.SECONDS])
        assertEquals("5400", single("set a timer for an hour and a half").params[Params.SECONDS])
        assertNull(single("set an alarm").params[Params.TIME])
    }

    @Test
    fun `reminders split task and time`() {
        val r = single("remind me to call mum tomorrow at 9am")
        assertEquals(ActionId.CREATE_REMINDER, r.action)
        assertEquals("Call mum", r.params[Params.TEXT])
        assertEquals("2026-11-16T09:00", r.params[Params.AT])

        val inMinutes = single("remind me to check the oven in 20 minutes")
        assertEquals("2026-11-15T18:20", inMinutes.params[Params.AT])

        val keepsWords = single("remind me to put clothes in the machine at 8pm")
        assertEquals("Put clothes in the machine", keepsWords.params[Params.TEXT])
        assertEquals("2026-11-15T20:00", keepsWords.params[Params.AT])

        val timeFirst = single("remind me at 5pm tomorrow to pay the electricity bill")
        assertEquals("Pay the electricity bill", timeFirst.params[Params.TEXT])
        assertEquals("2026-11-16T17:00", timeFirst.params[Params.AT])
    }

    @Test
    fun `notes tasks and calendar`() {
        assertEquals("Buy fuel on the way home", single("take a note that buy fuel on the way home").params[Params.TEXT])
        val task = single("add submit assignment to my to-do list")
        assertEquals(ActionId.CREATE_TASK, task.action)
        assertEquals("Submit assignment", task.params[Params.TITLE])
        val event = single("schedule a meeting with Ada on Monday at 10am")
        assertEquals(ActionId.CREATE_CALENDAR_EVENT, event.action)
        assertEquals("Meeting with Ada", event.params[Params.TITLE])
        assertEquals("2026-11-16T10:00", event.params[Params.START])
    }

    @Test
    fun `device controls`() {
        assertEquals("true", single("turn on the flashlight").params[Params.ENABLED])
        assertEquals("false", single("torch off").params[Params.ENABLED])
        assertEquals("50", single("set the volume to 50 percent").params[Params.LEVEL])
        assertEquals("up", single("turn the volume up").params[Params.DIRECTION])
        assertEquals("mute", single("mute").params[Params.DIRECTION])
        assertEquals(ActionId.LAUNCH_CAMERA, single("take a selfie").action)
        assertEquals("true", single("turn on do not disturb").params[Params.ENABLED])
        assertEquals("false", single("disable dnd").params[Params.ENABLED])
    }

    @Test
    fun `settings pages including toggles Android does not allow`() {
        assertEquals("wifi", single("open wifi settings").params[Params.PAGE])
        assertEquals("bluetooth", single("go to bluetooth settings").params[Params.PAGE])
        assertEquals("display", single("open the display settings").params[Params.PAGE])
        val toggle = interpreter.interpretText("turn on bluetooth")
        assertIs<Interpretation.Actions>(toggle)
        assertEquals("bluetooth", toggle.requests.single().params[Params.PAGE])
        assertTrue(toggle.preface!!.contains("settings"))
    }

    @Test
    fun `sleep routine asks for wake time`() {
        val r = interpreter.interpretText("I'm going to sleep")
        assertIs<Interpretation.Actions>(r)
        assertEquals(listOf(ActionId.SET_DO_NOT_DISTURB, ActionId.SET_ALARM), r.requests.map { it.action })
        assertEquals("What time should I wake you?", r.requests[1].prompts[Params.TIME])
    }

    @Test
    fun `compound commands are split`() {
        val r = interpreter.interpretText("turn on the flashlight and set a timer for 5 minutes")
        assertIs<Interpretation.Actions>(r)
        assertEquals(listOf(ActionId.SET_FLASHLIGHT, ActionId.SET_TIMER), r.requests.map { it.action })
    }

    @Test
    fun `calls memory and search`() {
        assertEquals(ActionId.DIAL_CONTACT, single("call Ada").action)
        val alias = single("remember that Samuel Okafor is my brother")
        assertEquals("brother", alias.params[Params.ALIAS])
        assertEquals("Samuel Okafor", alias.params[Params.CONTACT])
        assertEquals("jollof rice recipe", single("search for jollof rice recipe").params[Params.QUERY])
    }

    @Test
    fun `local answers and unsupported`() {
        val time = interpreter.interpretText("what time is it")
        assertIs<Interpretation.Answer>(time)
        assertEquals("It's 6:00 PM.", time.text)
        assertIs<Interpretation.Unsupported>(interpreter.interpretText("what is the capital of Kenya"))
    }
}
