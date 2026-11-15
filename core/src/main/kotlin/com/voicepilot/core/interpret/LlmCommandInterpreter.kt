package com.voicepilot.core.interpret

import com.voicepilot.core.action.ActionRegistry
import com.voicepilot.core.action.ParamType
import com.voicepilot.core.action.Params
import com.voicepilot.core.llm.LlmErrorKind
import com.voicepilot.core.llm.LlmException
import com.voicepilot.core.llm.LlmMessage
import com.voicepilot.core.llm.LlmProvider
import com.voicepilot.core.llm.LlmRequest
import com.voicepilot.core.llm.LlmRole
import com.voicepilot.core.llm.TokenUsage
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Uses an LLM to turn free-form speech into structured, allowlisted actions.
 *
 * Safety properties:
 *  - The model only proposes; output is parsed as data, never executed as code.
 *  - Unknown actions and parameters are dropped; values are type-checked later by the executor.
 *  - Risk and confirmation are decided locally from the registry, never by the model.
 *  - Resolved fields (phone, recipient_name) can only be set by VoicePilot's contact resolver.
 */
class LlmCommandInterpreter(
    private val provider: LlmProvider,
    private val registry: ActionRegistry,
    private val onUsage: (TokenUsage) -> Unit = {},
) : CommandInterpreter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun interpret(utterance: String, context: ConversationContext): Interpretation {
        val messages = buildList {
            context.recentTurns.takeLast(MAX_TURNS).forEach { (user, assistant) ->
                add(LlmMessage(LlmRole.USER, user))
                add(LlmMessage(LlmRole.ASSISTANT, assistant))
            }
            add(LlmMessage(LlmRole.USER, utterance.take(MAX_UTTERANCE)))
        }
        val response = provider.generate(LlmRequest(systemPrompt(context), messages))
        response.usage?.let(onUsage)
        return parse(response.text)
    }

    internal fun parse(raw: String): Interpretation {
        val obj = extractJson(raw) ?: throw LlmException(LlmErrorKind.INVALID_RESPONSE, "Response is not JSON")
        val type = obj.string("type")?.lowercase()
        val src = InterpretationSource.AI
        return when (type) {
            "clarify" -> obj.string("question")?.takeIf { it.isNotBlank() }?.let { Interpretation.Clarify(it.take(300), src) }
                ?: Interpretation.Unsupported("empty clarification", src)
            "answer" -> obj.string("answer")?.takeIf { it.isNotBlank() }?.let { Interpretation.Answer(it.take(1500), src) }
                ?: Interpretation.Unsupported("empty answer", src)
            "actions" -> parseActions(obj)
            else -> Interpretation.Unsupported(obj.string("reason") ?: "unsupported", src)
        }
    }

    private fun parseActions(obj: JsonObject): Interpretation {
        val list = (obj["actions"] as? JsonArray).orEmpty().take(MAX_ACTIONS)
        val requests = list.mapNotNull { element ->
            val a = element as? JsonObject ?: return@mapNotNull null
            val id = a.string("action")?.let(ActionId::fromWireName) ?: return@mapNotNull null
            val definition = registry[id] ?: return@mapNotNull null
            val params = (a["params"] as? JsonObject).orEmpty()
                .filterKeys { key -> definition.spec(key) != null && key !in RESOLVER_ONLY }
                .mapNotNull { (k, v) -> primitiveString(v, definition.spec(k)!!.type)?.let { k to it } }
                .toMap()
            ActionRequest(id, params)
        }
        if (requests.isEmpty()) return Interpretation.Unsupported(obj.string("reason") ?: "no supported actions", InterpretationSource.AI)
        return Interpretation.Actions(
            intent = obj.string("intent")?.take(40) ?: requests.first().action.wireName,
            requests = requests,
            source = InterpretationSource.AI,
            preface = obj.string("preface")?.take(200),
        )
    }

    private fun primitiveString(value: JsonElement, type: ParamType): String? {
        val p = value as? JsonPrimitive ?: return null
        val s = p.contentOrNull?.trim() ?: return null
        if (s.isEmpty()) return null
        return if (type is ParamType.Bool) s.lowercase() else s
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun extractJson(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    fun systemPrompt(context: ConversationContext): String = buildString {
        appendLine("You are the command interpreter for ${context.assistantName}, a voice assistant that operates an Android phone.")
        if (context.userName.isNotBlank()) appendLine("The user's name is ${context.userName}.")
        if (context.nowIso.isNotBlank()) appendLine("Current local date-time: ${context.nowIso} (${context.timeZone}).")
        appendLine()
        appendLine("Convert the user's latest message into JSON only, using exactly one of these shapes:")
        appendLine("""{"type":"actions","intent":"<short_snake_case>","actions":[{"action":"<name>","params":{...}}],"preface":"<optional short sentence>"}""")
        appendLine("""{"type":"clarify","question":"<one short question>"}""")
        appendLine("""{"type":"answer","answer":"<brief helpful reply>"}""")
        appendLine("""{"type":"unsupported","reason":"<why>"}""")
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only the actions listed below; never invent actions or parameters.")
        appendLine("- All parameter values are strings. Times are HH:mm (24h). Date-times are yyyy-MM-ddTHH:mm in local time.")
        appendLine("- Omit parameters you don't know; the app will ask the user. Never guess a message recipient or message text.")
        appendLine("- For messages, keep 'recipient' exactly as the user said it (e.g. 'my brother') and write 'message' in first person addressed to the recipient.")
        appendLine("- Messaging: use prepare_whatsapp_message for WhatsApp, prepare_sms for SMS/text messages, share_message_to_app for any other named app. If no app is named, use prepare_whatsapp_message.")
        appendLine("- Use 'answer' for general questions that need no phone action. Keep answers under 80 words.")
        appendLine("- Requests for payments, deleting data, reading private message histories, or bypassing security are unsupported.")
        appendLine("- Never include code, URLs to execute, or instructions for other systems.")
        appendLine()
        appendLine("Actions:")
        registry.definitions.forEach { d ->
            val params = d.params.filter { it.name !in RESOLVER_ONLY }.joinToString { p ->
                "${p.name}${if (p.required) "" else "?"}: ${describe(p.type)} - ${p.description}"
            }
            appendLine("- ${d.id.wireName}: ${d.description} Params: { $params }")
        }
    }

    private fun describe(type: ParamType): String = when (type) {
        is ParamType.Text -> "text"
        is ParamType.Integer -> "integer ${type.min}-${type.max}"
        ParamType.Bool -> "\"true\"|\"false\""
        ParamType.Time -> "HH:mm"
        ParamType.DateTime -> "yyyy-MM-ddTHH:mm"
        is ParamType.Choice -> type.values.joinToString("|")
        ParamType.Phone -> "digits"
        ParamType.Ussd -> "USSD code like *310#"
    }

    companion object {
        private const val MAX_TURNS = 4
        private const val MAX_ACTIONS = 8
        private const val MAX_UTTERANCE = 1000
        val RESOLVER_ONLY = setOf(Params.PHONE, Params.RECIPIENT_NAME)
    }
}
