package com.voicepilot.ai

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicepilot.core.interpret.Interpretation
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.interpret.MessageRewriter
import com.voicepilot.core.time.Clock
import com.voicepilot.core.voice.WakePhraseMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Runs on a real Android runtime, whose regex engine (ICU) is stricter than the
 * JVM's. A pattern the JVM accepts but Android rejects crashes the app at start,
 * so every command rule is exercised here, and the app is launched end to end.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceSmokeTest {

    @Test
    fun appLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertTrue(!it.isFinishing) }
        }
    }

    @Test
    fun everyCommandRuleWorksOnAndroidRegex() {
        val interpreter = LocalRuleInterpreter(Clock.fixed(LocalDateTime.of(2026, 11, 15, 18, 0)))
        val phrases = listOf(
            "Hey SL, please open WhatsApp", "what time is it", "wetin be the time", "how you doing", "thank you",
            "what can you do", "I'm going to sleep", "I wan sleep",
            "Send Samuel a WhatsApp message saying I'm on my way", "Abeg send Tunde message say I dey come",
            "message Ada by SMS saying hi", "message Tunde on Telegram saying the meeting has moved",
            "tell Ada on WhatsApp that I'm late", "call Ada", "remember that Samuel Okafor is my brother",
            "wake me by 7", "set an alarm for 6:30 am", "set a timer for 10 minutes", "15 minute timer",
            "remind me to call mum tomorrow at 9am", "remind me say make I call mama by 5pm",
            "take a note that the gate code is 4512", "add buy fuel to my to-do list",
            "schedule a meeting with Ada on Monday at 10am", "turn on the flashlight", "off flash light",
            "turn on do not disturb", "set the volume to 50 percent", "add the sound", "mute", "max volume",
            "take a selfie", "record a video", "turn on bluetooth", "open wifi settings", "search for jollof rice",
            "check my airtime balance", "check my data balance", "buy data", "buy 500 naira airtime",
            "dial star 310 hash", "read my WhatsApp messages", "what did Samuel say", "who message me",
            "reply saying I'm coming", "reply: on my way", "answer am say I dey come",
            "pause the music", "next song", "previous", "play Burna Boy on Spotify",
            "take me to Ikeja City Mall", "carry me go Yaba by okada",
            "turn on the flashlight and set a timer for 5 minutes", "something completely unknown",
        )
        phrases.forEach { phrase -> interpreter.interpretText(phrase) } // must not throw
        assertTrue(interpreter.interpretText("dial star 310 hash") is Interpretation.Actions)
        assertEquals("I go call you after work", MessageRewriter.toDirectSpeech("tell am say I go call am after work", true))
        assertEquals("open whatsapp", WakePhraseMatcher("hey sl").match("Hey SL open WhatsApp")?.trailingCommand)
    }
}
