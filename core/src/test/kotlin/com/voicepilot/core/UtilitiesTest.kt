package com.voicepilot.core

import com.voicepilot.core.contacts.CandidateChooser
import com.voicepilot.core.contacts.ContactResolution
import com.voicepilot.core.contacts.ContactResolver
import com.voicepilot.core.contacts.PhoneNumberNormalizer
import com.voicepilot.core.integrations.AppCatalog
import com.voicepilot.core.integrations.AppResolver
import com.voicepilot.core.integrations.InstalledApp
import com.voicepilot.core.integrations.WhatsAppLinks
import com.voicepilot.core.interpret.MessageRewriter
import com.voicepilot.core.memory.InMemoryMemoryRepository
import com.voicepilot.core.memory.MemoryKind
import com.voicepilot.core.memory.RememberAliasHandler
import com.voicepilot.core.memory.Routine
import com.voicepilot.core.memory.RoutineMatcher
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.notifications.NotificationItem
import com.voicepilot.core.notifications.NotificationSummarizer
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.text.NameMatcher
import com.voicepilot.core.text.NumberWords
import com.voicepilot.core.text.YesNo
import com.voicepilot.core.text.YesNoParser
import com.voicepilot.core.time.TimeExpressionParser
import com.voicepilot.core.voice.WakePhraseMatcher
import kotlinx.coroutines.test.runTest
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilitiesTest {

    @Test
    fun `time of day parsing`() {
        val p = TimeExpressionParser(testClock) // now = 18:00
        assertEquals(LocalTime.of(6, 30), p.parseTimeOfDay("6:30 am"))
        assertEquals(LocalTime.of(18, 45), p.parseTimeOfDay("at 6:45pm"))
        assertEquals(LocalTime.of(19, 0), p.parseTimeOfDay("at 7"), "ambiguous hour picks next occurrence")
        assertEquals(LocalTime.of(7, 0), p.parseTimeOfDay("at 7", preferMorning = true))
        assertEquals(LocalTime.of(12, 0), p.parseTimeOfDay("noon"))
        assertEquals(LocalTime.of(7, 30), p.parseTimeOfDay("half past seven am"))
        assertEquals(LocalTime.of(6, 15), p.parseTimeOfDay("six fifteen am"))
        assertEquals(LocalTime.of(21, 0), p.parseTimeOfDay("9 pm"))
        assertNull(p.parseTimeOfDay("whenever"))
    }

    @Test
    fun `durations`() {
        val p = TimeExpressionParser(testClock)
        assertEquals(1800, p.parseDuration("half an hour")!!.seconds)
        assertEquals(5400, p.parseDuration("1 hour 30 minutes")!!.seconds)
        assertEquals(90, p.parseDuration("ninety seconds")!!.seconds)
        assertEquals(1500, p.parseDuration("twenty five minutes")!!.seconds)
    }

    @Test
    fun `date times`() {
        val p = TimeExpressionParser(testClock)
        assertEquals("2026-11-16T09:00", p.parseDateTime("tomorrow")!!.toString())
        assertEquals("2026-11-16T07:00", p.parseDateTime("tomorrow at 7")!!.toString())
        assertEquals("2026-11-15T20:00", p.parseDateTime("tonight")!!.toString())
        assertEquals("2026-11-20T15:00", p.parseDateTime("on friday at 3pm")!!.toString())
        assertEquals("2026-11-16T08:00", p.parseDateTime("at 8am")!!.toString(), "past time rolls to tomorrow")
        assertEquals("2026-11-15T20:00", p.parseDateTime("in 2 hours")!!.toString())
    }

    @Test
    fun `number words`() {
        assertEquals(25, NumberWords.parse("twenty five"))
        assertEquals(1, NumberWords.parse("an"))
        assertEquals(42, NumberWords.parse("42"))
        assertNull(NumberWords.parse("lots"))
    }

    @Test
    fun `name matching`() {
        assertEquals(1.0, NameMatcher.score("Ada Eze", "ada eze"))
        assertTrue(NameMatcher.score("Samuel", "Samuel Okafor") >= 0.85)
        assertTrue(NameMatcher.score("Samual", "Samuel Okafor") >= 0.6)
        assertTrue(NameMatcher.score("Chidi", "Samuel Okafor") < 0.6)
    }

    @Test
    fun `contact resolution`() = runTest {
        val resolver = ContactResolver(
            FakeContacts(contact("1", "Samuel Okafor", "08031234567"), contact("2", "Samuel Adeyemi", "08099990000"), contact("3", "Ada Eze", "0805")),
            FakeAliases(mapOf("sister" to "Ada Eze")),
        )
        assertIs<ContactResolution.Ambiguous>(resolver.resolve("Samuel"))
        assertEquals("Samuel Okafor", assertIs<ContactResolution.Resolved>(resolver.resolve("Samuel Okafor")).candidate.contact.displayName)
        assertIs<ContactResolution.NotFound>(resolver.resolve("Tunde"))
        assertIs<ContactResolution.NotFound>(resolver.resolve("my sister"), "Ada's number is too short to be usable")

        val single = ContactResolver(FakeContacts(contact("1", "Samuel Okafor", "08031234567")), FakeAliases())
        val weak = assertIs<ContactResolution.Ambiguous>(single.resolve("Samual"), "A weak match must be confirmed, not guessed")
        assertEquals(1, weak.candidates.size)
    }

    @Test
    fun `contact with two numbers asks which number`() = runTest {
        val resolver = ContactResolver(FakeContacts(contact("1", "Ada Eze", "08031111111", "08032222222")), FakeAliases())
        val r = assertIs<ContactResolution.Ambiguous>(resolver.resolve("Ada"))
        assertEquals(2, r.candidates.size)
        assertEquals(r.candidates[1], CandidateChooser.choose("the one ending 2222", r.candidates))
        assertEquals(r.candidates[0], CandidateChooser.choose("first", r.candidates))
        assertNull(CandidateChooser.choose("hmm", r.candidates))
        assertTrue(r.candidates[0].description.contains("••• 1111"))
    }

    @Test
    fun `phone normalization`() {
        assertEquals("2348031234567", PhoneNumberNormalizer.toInternationalDigits("0803 123 4567", "234"))
        assertEquals("2348031234567", PhoneNumberNormalizer.toInternationalDigits("+234 803 123 4567", "234"))
        assertEquals("447700900123", PhoneNumberNormalizer.toInternationalDigits("+44 7700 900123", "234"))
        assertEquals("447700900123", PhoneNumberNormalizer.toInternationalDigits("0044 7700 900123", "234"))
        assertEquals("2348031234567", PhoneNumberNormalizer.toInternationalDigits("2348031234567", "234"))
        assertNull(PhoneNumberNormalizer.toInternationalDigits("12", "234"))
    }

    @Test
    fun `whatsapp click to chat links`() {
        assertEquals("https://wa.me/2348031234567", WhatsAppLinks.clickToChat("2348031234567", null))
        assertEquals(
            "https://wa.me/2348031234567?text=I%27ll%20arrive%20in%2020%20minutes%20%26%20call",
            WhatsAppLinks.clickToChat("2348031234567", "I'll arrive in 20 minutes & call"),
        )
    }

    @Test
    fun `message rewriting`() {
        assertEquals("I'm on my way", MessageRewriter.toDirectSpeech("Tell him I'm on my way.", true).trimEnd('.'))
        assertEquals("I will call you after work", MessageRewriter.toDirectSpeech("saying I will call him after work", true))
        assertEquals("I'll see you later", MessageRewriter.toDirectSpeech("telling her I'll see her later", true))
        assertEquals("Happy birthday", MessageRewriter.toDirectSpeech("\"happy birthday\"", false))
    }

    @Test
    fun `yes no parsing`() {
        listOf("Yes.", "yeah", "go ahead", "send it", "ok please").forEach { assertEquals(YesNo.YES, YesNoParser.parse(it), it) }
        listOf("no", "cancel", "don't send it", "never mind").forEach { assertEquals(YesNo.NO, YesNoParser.parse(it), it) }
        assertEquals(YesNo.UNKNOWN, YesNoParser.parse("open youtube"))
    }

    @Test
    fun `wake phrase matching`() {
        val m = WakePhraseMatcher("hey voicepilot")
        assertEquals("open whatsapp", m.match("Hey VoicePilot open WhatsApp")!!.trailingCommand)
        assertNotNull(m.match("hey voice pilot"))
        assertNotNull(m.match("a voice pilot what time is it"))
        assertNull(m.match("open whatsapp"))
        assertNull(m.match("hey siri"))
        assertEquals("set a timer", m.stripWakePhrase("Hey voice pilot, set a timer"))
    }

    @Test
    fun `routine matching`() {
        val work = Routine(name = "Work mode", triggerPhrases = listOf("prepare my phone for work"), steps = listOf(ActionRequest(ActionId.OPEN_APP, mapOf("app" to "Slack"))))
        val off = work.copy(name = "Disabled", triggerPhrases = listOf("party time"), enabled = false)
        assertEquals(work, RoutineMatcher.match("Prepare my phone for work", listOf(work, off)))
        assertEquals(work, RoutineMatcher.match("run work mode", listOf(work)))
        assertNull(RoutineMatcher.match("party time", listOf(off)))
        assertNull(RoutineMatcher.match("open whatsapp", listOf(work)))
    }

    @Test
    fun `notification summaries`() {
        fun n(app: String, who: String, t: Long) = NotificationItem("$app$who$t", "pkg", app, who, "hello", t, isMessaging = true, canReply = true)
        val s = NotificationSummarizer.summarize(listOf(n("WhatsApp", "Samuel", 1), n("WhatsApp", "Samuel", 2), n("WhatsApp", "Ada", 3), n("Gmail", "Bank", 4)))
        assertEquals("You have 4 new notifications. 3 messages from WhatsApp: 2 from Samuel, Ada. 1 message from Gmail: Bank.", s)
        assertTrue(!s.contains("hello"), "Content is not read unless requested")
        assertEquals("You have no new notifications.", NotificationSummarizer.summarize(emptyList()))
    }

    @Test
    fun `app resolution`() = runTest {
        val resolver = AppResolver(object : AppCatalog {
            override suspend fun launchableApps() = listOf(
                InstalledApp("WhatsApp", "com.whatsapp"),
                InstalledApp("YouTube", "com.google.android.youtube"),
                InstalledApp("Opay", "team.opay.pay"),
            )
        })
        assertEquals("com.whatsapp", resolver.resolve("whatsapp")?.packageName)
        assertEquals("team.opay.pay", resolver.resolve("the opay app")?.packageName)
        assertEquals("com.google.android.youtube", resolver.resolve("you tube")?.packageName)
        assertNull(resolver.resolve("instagram"))
    }

    @Test
    fun `remember alias respects memory toggle and contacts`() = runTest {
        val memory = InMemoryMemoryRepository()
        var settings = AssistantSettings()
        val handler = RememberAliasHandler(memory, FakeContacts(contact("1", "Samuel Okafor", "0803")), { settings }, testClock)
        val ok = handler.execute(ActionRequest(ActionId.REMEMBER_ALIAS, mapOf("alias" to "brother", "contact" to "samuel okafor")))
        assertIs<ActionResult.Completed>(ok)
        assertEquals("Samuel Okafor", memory.find(MemoryKind.CONTACT_ALIAS, "brother")!!.value)
        assertIs<ActionResult.Failed>(handler.execute(ActionRequest(ActionId.REMEMBER_ALIAS, mapOf("alias" to "boss", "contact" to "Nobody"))))
        settings = settings.copy(memoryEnabled = false)
        assertIs<ActionResult.Failed>(handler.execute(ActionRequest(ActionId.REMEMBER_ALIAS, mapOf("alias" to "brother", "contact" to "Samuel Okafor"))))
    }
}
