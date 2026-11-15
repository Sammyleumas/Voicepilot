package com.voicepilot.core

import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.action.Params
import com.voicepilot.core.interpret.ConversationContext
import com.voicepilot.core.interpret.HybridInterpreter
import com.voicepilot.core.interpret.Interpretation
import com.voicepilot.core.interpret.InterpretationSource
import com.voicepilot.core.interpret.LlmCommandInterpreter
import com.voicepilot.core.interpret.LocalRuleInterpreter
import com.voicepilot.core.llm.CloudProxyProvider
import com.voicepilot.core.llm.GeminiProvider
import com.voicepilot.core.llm.HttpResponse
import com.voicepilot.core.llm.HttpTransport
import com.voicepilot.core.llm.LlmErrorKind
import com.voicepilot.core.llm.LlmException
import com.voicepilot.core.llm.LlmMessage
import com.voicepilot.core.llm.LlmRequest
import com.voicepilot.core.llm.LlmRole
import com.voicepilot.core.llm.RateLimiter
import com.voicepilot.core.llm.ScriptedLlmProvider
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.subscription.Entitlements
import com.voicepilot.core.subscription.InMemoryUsageStore
import com.voicepilot.core.subscription.Plan
import com.voicepilot.core.subscription.UsageTracker
import com.voicepilot.core.time.Clock
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiTest {
    private val registry = ActionRegistry()

    @Test
    fun `parses structured actions and drops unknown or resolver-only fields`() {
        val interpreter = LlmCommandInterpreter(ScriptedLlmProvider.of(), registry)
        val result = interpreter.parse(
            """```json
            {"type":"actions","intent":"send_message","actions":[
              {"action":"prepare_whatsapp_message","params":{"recipient":"my brother","message":"I will call you after work","phone":"2340000000","risk_level":"low"}},
              {"action":"format_disk","params":{}}
            ]}
            ```""",
        )
        val actions = assertIs<Interpretation.Actions>(result)
        assertEquals("send_message", actions.intent)
        val req = actions.requests.single()
        assertEquals(ActionId.PREPARE_WHATSAPP_MESSAGE, req.action)
        assertEquals("my brother", req.params[Params.RECIPIENT])
        assertNull(req.params[Params.PHONE], "The model must not be able to set the phone number")
        assertEquals(setOf(Params.RECIPIENT, Params.MESSAGE), req.params.keys)
    }

    @Test
    fun `parses clarify answer and garbage`() {
        val interpreter = LlmCommandInterpreter(ScriptedLlmProvider.of(), registry)
        assertIs<Interpretation.Clarify>(interpreter.parse("""{"type":"clarify","question":"Which Samuel?"}"""))
        assertEquals("Nairobi.", assertIs<Interpretation.Answer>(interpreter.parse("""{"type":"answer","answer":"Nairobi."}""")).text)
        assertIs<Interpretation.Unsupported>(interpreter.parse("""{"type":"actions","actions":[{"action":"steal"}]}"""))
        assertFailsWith<LlmException> { interpreter.parse("I think you should open WhatsApp") }
    }

    @Test
    fun `system prompt lists only allowlisted actions`() {
        val prompt = LlmCommandInterpreter(ScriptedLlmProvider.of(), registry).systemPrompt(ConversationContext(nowIso = "2026-11-15T18:00"))
        ActionId.entries.forEach { assertTrue(prompt.contains(it.wireName)) }
        assertFalse(prompt.contains("phone?: digits"), "Resolver-only params must not be offered to the model")
    }

    private fun hybrid(provider: ScriptedLlmProvider?, plan: Plan = Plan.FREE, store: InMemoryUsageStore = InMemoryUsageStore()) =
        HybridInterpreter(
            local = LocalRuleInterpreter(testClock),
            ai = { provider?.let { LlmCommandInterpreter(it, registry) } },
            usage = UsageTracker(store, testClock) { Entitlements.forPlan(plan) },
            rateLimiter = RateLimiter(100, 1000, testClock),
        )

    @Test
    fun `hybrid uses local rules first without spending AI quota`() = runTest {
        val provider = ScriptedLlmProvider.of()
        val store = InMemoryUsageStore()
        val r = hybrid(provider, store = store).interpret("open whatsapp", ConversationContext())
        assertEquals(InterpretationSource.LOCAL_RULES, r.source)
        assertTrue(provider.requests.isEmpty())
        assertEquals(0, store.count(TEST_NOW.toLocalDate()))
    }

    @Test
    fun `hybrid falls back to AI and degrades gracefully`() = runTest {
        val ok = ScriptedLlmProvider.of("""{"type":"answer","answer":"Nairobi is the capital of Kenya."}""")
        assertIs<Interpretation.Answer>(hybrid(ok).interpret("what is the capital of Kenya", ConversationContext()))

        val down = ScriptedLlmProvider.failing(LlmErrorKind.NETWORK)
        val r = assertIs<Interpretation.Unsupported>(hybrid(down).interpret("what is the capital of Kenya", ConversationContext()))
        assertTrue(r.reason.contains("internet"))

        val none = assertIs<Interpretation.Unsupported>(hybrid(null).interpret("what is the capital of Kenya", ConversationContext()))
        assertEquals(HybridInterpreter.OFFLINE_HINT, none.reason)
    }

    @Test
    fun `daily AI quota is enforced`() = runTest {
        val store = InMemoryUsageStore()
        repeat(25) { store.increment(TEST_NOW.toLocalDate()) }
        val provider = ScriptedLlmProvider.of("""{"type":"answer","answer":"x"}""")
        val r = assertIs<Interpretation.Unsupported>(hybrid(provider, store = store).interpret("tell me a story", ConversationContext()))
        assertTrue(r.reason.contains("limit"))
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `rate limiter refills over time`() {
        var now = Instant.parse("2026-11-15T17:00:00Z")
        val clock = object : Clock {
            override fun now() = now
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        val limiter = RateLimiter(2, 1000, clock)
        assertTrue(limiter.tryAcquire()); assertTrue(limiter.tryAcquire()); assertFalse(limiter.tryAcquire())
        now = now.plusMillis(1000)
        assertTrue(limiter.tryAcquire())
    }

    private class FakeTransport(private val response: HttpResponse) : HttpTransport {
        var url = ""; var headers = emptyMap<String, String>(); var body = ""
        override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMillis: Long): HttpResponse {
            this.url = url; this.headers = headers; this.body = body
            return response
        }
    }

    @Test
    fun `gemini request and response mapping`() = runTest {
        val transport = FakeTransport(
            HttpResponse(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"{\"type\":\"answer\",\"answer\":\"hi\"}"}]},"finishReason":"STOP"}],
                   "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":8,"totalTokenCount":128}}""",
            ),
        )
        val gemini = GeminiProvider(transport, { "test-key" }, { "gemini-2.5-flash" })
        val response = gemini.generate(LlmRequest("system", listOf(LlmMessage(LlmRole.USER, "hello"))))
        assertEquals("""{"type":"answer","answer":"hi"}""", response.text)
        assertEquals(128, response.usage!!.totalTokens)
        assertTrue(transport.url.endsWith("/models/gemini-2.5-flash:generateContent"))
        assertEquals("test-key", transport.headers["x-goog-api-key"])
        assertFalse(transport.url.contains("test-key"), "API key must not be placed in the URL")
        assertTrue(transport.body.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(transport.body.contains("\"systemInstruction\""))
    }

    @Test
    fun `gemini errors map to kinds`() = runTest {
        suspend fun kindFor(code: Int, key: String? = "k"): LlmErrorKind =
            assertFailsWith<LlmException> {
                GeminiProvider(FakeTransport(HttpResponse(code, "{}")), { key }, { "gemini-2.5-flash" })
                    .generate(LlmRequest("s", listOf(LlmMessage(LlmRole.USER, "u"))))
            }.kind
        assertEquals(LlmErrorKind.AUTH, kindFor(403))
        assertEquals(LlmErrorKind.RATE_LIMITED, kindFor(429))
        assertEquals(LlmErrorKind.SERVER, kindFor(503))
        assertEquals(LlmErrorKind.NOT_CONFIGURED, kindFor(200, key = null))
        assertEquals(LlmErrorKind.INVALID_RESPONSE, kindFor(200))
    }

    @Test
    fun `cloud proxy requires https and maps quota`() = runTest {
        val insecure = CloudProxyProvider(FakeTransport(HttpResponse(200, "{}")), { "http://example.com" }, { "t" }, { "m" })
        assertFalse(insecure.isConfigured())
        val quota = CloudProxyProvider(FakeTransport(HttpResponse(402, "{}")), { "https://api.example.com" }, { "t" }, { "m" })
        assertEquals(LlmErrorKind.QUOTA_EXCEEDED, assertFailsWith<LlmException> { quota.generate(LlmRequest("s", emptyList())) }.kind)
        val transport = FakeTransport(HttpResponse(200, """{"text":"{}","model":"gemini-2.5-flash","usage":{"totalTokens":9}}"""))
        val ok = CloudProxyProvider(transport, { "https://api.example.com/" }, { "user-token" }, { "m" })
        assertEquals(9, ok.generate(LlmRequest("s", emptyList())).usage!!.totalTokens)
        assertEquals("https://api.example.com/v1/generate", transport.url)
        assertEquals("Bearer user-token", transport.headers["Authorization"])
    }
}
