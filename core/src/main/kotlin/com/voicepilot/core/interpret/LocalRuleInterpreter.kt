package com.voicepilot.core.interpret

import com.voicepilot.core.action.Params
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.messaging.UssdCodes
import com.voicepilot.core.settings.MessagingChannel
import com.voicepilot.core.text.NumberWords
import com.voicepilot.core.time.Clock
import com.voicepilot.core.time.TimeExpressionParser
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Offline, deterministic interpreter for common commands. It needs no network
 * or AI subscription and is tried before any AI provider. Returns
 * [Interpretation.Unsupported] when nothing matches so an AI interpreter can take over.
 */
class LocalRuleInterpreter(
    private val clock: Clock,
    /** Channel used when the user says "message X" without naming an app. */
    private val defaultChannel: () -> MessagingChannel = { MessagingChannel.WHATSAPP },
) : CommandInterpreter {

    private val time = TimeExpressionParser(clock)

    private class Rule(pattern: String, val build: (MatchResult, String) -> Interpretation?) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    override suspend fun interpret(utterance: String, context: ConversationContext): Interpretation =
        interpretText(utterance)

    fun interpretText(utterance: String): Interpretation {
        val text = clean(utterance)
        if (text.isEmpty()) return Interpretation.Unsupported("empty", InterpretationSource.LOCAL_RULES)
        matchSingle(text)?.let { return it }
        matchCompound(text)?.let { return it }
        return Interpretation.Unsupported("no local rule matched", InterpretationSource.LOCAL_RULES)
    }

    private fun matchSingle(text: String): Interpretation? {
        for (rule in rules) {
            val m = rule.regex.find(text) ?: continue
            rule.build(m, text)?.let { return it }
        }
        return null
    }

    /** "Turn on the flashlight and set a timer for 5 minutes" → two actions. */
    private fun matchCompound(text: String): Interpretation? {
        val parts = text.split(CONJUNCTION).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val actions = parts.map { part -> matchSingle(part) as? Interpretation.Actions ?: return null }
        return Interpretation.Actions(
            intent = "multi_step",
            requests = actions.flatMap { it.requests },
            source = InterpretationSource.LOCAL_RULES,
        )
    }

    private fun actions(intent: String, vararg requests: ActionRequest, preface: String? = null) =
        Interpretation.Actions(intent, requests.toList(), InterpretationSource.LOCAL_RULES, preface)

    private fun request(id: ActionId, vararg params: Pair<String, String?>): ActionRequest =
        ActionRequest(id, params.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap())

    private fun MatchResult.g(name: String): String? = try {
        groups[name]?.value?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------- rules

    private val rules: List<Rule> = listOf(
        // Local answers
        Rule("^what(?:'s| is) the time(?: now)?$|^what time is it(?: now)?$") { _, _ ->
            Interpretation.Answer("It's ${clock.localNow().format(TIME_FMT)}.", InterpretationSource.LOCAL_RULES)
        },
        Rule("^what(?:'s| is) (?:the date|today's date)(?: today)?$|^what day is (?:it|today)$") { _, _ ->
            Interpretation.Answer("Today is ${clock.localNow().format(DATE_FMT)}.", InterpretationSource.LOCAL_RULES)
        },
        Rule("^(?:what can you do|help|what do you do|how can you help(?: me)?)$") { _, _ ->
            Interpretation.Answer(HELP_TEXT, InterpretationSource.LOCAL_RULES)
        },

        Rule("^(?:hi|hello|hey|hey there|good (?:morning|afternoon|evening)|how are you(?: doing)?|how you doing|how're you|how you dey|how far|how body|wetin dey|you dey|are you there|what's up|whats up|sup)$") { m, _ ->
            val greeting = m.value.lowercase()
            val text = when {
                greeting.startsWith("good ") -> "${greeting.replaceFirstChar { it.uppercaseChar() }}! What can I do for you?"
                greeting.contains("dey") || greeting.contains("far") || greeting.contains("body") -> "I dey kampe! Wetin you want make I do?"
                greeting.startsWith("how") -> "I'm doing great, thanks for asking. What can I do for you?"
                greeting.contains("there") -> "I'm here. What would you like to do?"
                else -> "Hello! What can I do for you?"
            }
            Interpretation.Answer(text, InterpretationSource.LOCAL_RULES)
        },
        Rule("^(?:thank you|thanks|thank you so much|thanks a lot|nice one|well done|good job|e se|daalu|na gode|i hail)(?: voicepilot)?$") { _, _ ->
            Interpretation.Answer("You're welcome!", InterpretationSource.LOCAL_RULES)
        },
        Rule("^(?:wetin be the time|what time e be|what time (?:is it )?now|time now|which time (?:be this|e be)?)$") { _, _ ->
            Interpretation.Answer("It's ${clock.localNow().format(TIME_FMT)}.", InterpretationSource.LOCAL_RULES)
        },
        Rule("^(?:wetin|what) (?:you fit do|you sabi do|you sabi|can you help me with)$") { _, _ ->
            Interpretation.Answer(HELP_TEXT, InterpretationSource.LOCAL_RULES)
        },

        // Sleep: suggest Do Not Disturb + alarm, then ask for the wake-up time.
        Rule("^(?:i'?m|i am) (?:going to (?:sleep|bed)|off to bed|heading to bed|about to sleep)$|^good ?night$|^bed ?time$|^i (?:wan|want to|dey) (?:go )?sleep$|^make i (?:go )?sleep$|^i don tire$") { _, _ ->
            actions(
                "prepare_sleep",
                request(ActionId.SET_DO_NOT_DISTURB, Params.ENABLED to "true"),
                ActionRequest(
                    ActionId.SET_ALARM,
                    mapOf(Params.LABEL to "Wake up"),
                    prompts = mapOf(Params.TIME to "What time should I wake you?"),
                ),
                preface = "I can turn on Do Not Disturb and set an alarm.",
            )
        },

        // Airtime and data (USSD codes go to the dialer; the user taps Call)
        Rule("^(?:check|what is|what's|wetin be|how much is|show)\\s+(?:my\\s+)?(?:airtime|credit|call credit|account)(?:\\s+(?:balance|bal))?(?: remain)?$|^how much (?:airtime|credit) (?:i get|do i have|remain|i have)$|^(?:my )?airtime balance$|^check (?:my )?balance$") { _, _ ->
            actions("check_airtime", request(ActionId.DIAL_USSD, Params.CODE to UssdCodes.AIRTIME_BALANCE, Params.LABEL to "airtime balance"))
        },
        Rule("^(?:check|what is|what's|wetin be|how much is|show)\\s+(?:my\\s+)?data(?:\\s+(?:balance|bal))?(?: remain)?$|^how much data (?:i get|do i have|remain|i have|is left)$|^(?:my )?data balance$") { _, _ ->
            actions("check_data", request(ActionId.DIAL_USSD, Params.CODE to UssdCodes.DATA_BALANCE, Params.LABEL to "data balance"))
        },
        Rule("^(?:buy|subscribe(?: to)?|get|renew)\\s+(?:a\\s+|some\\s+|my\\s+)?data(?:\\s+(?:plan|bundle|subscription|sub))?$") { _, _ ->
            actions("buy_data", request(ActionId.DIAL_USSD, Params.CODE to UssdCodes.DATA_PLANS, Params.LABEL to "data plans"))
        },
        Rule("^(?:buy|recharge|load|send)\\s+(?:me\\s+)?(?:n|₦)?\\d*\\s*(?:naira\\s+)?(?:worth of\\s+)?(?:airtime|credit|recharge card|card)(?:\\s+.*)?$") { _, _ ->
            Interpretation.Answer(
                "Buying airtime goes through your bank's app or USSD code, and SL doesn't make payments for you. " +
                    "Open your bank app, or say “dial star” followed by your bank's code and I'll put it in the dialer for you to check.",
                InterpretationSource.LOCAL_RULES,
            )
        },
        Rule("^(?:dial|run|call|enter)\\s+(?<c>(?:star|\\*|hash|#|asterisk)\\b?.*)$") { m, _ ->
            val code = UssdCodes.fromSpeech(m.g("c")!!) ?: return@Rule null
            actions("dial_ussd", request(ActionId.DIAL_USSD, Params.CODE to code))
        },

        // Received messages: read and reply
        Rule("^(?:read|check|show)\\s+(?:me\\s+)?(?:my\\s+)?(?:new\\s+|latest\\s+|unread\\s+|recent\\s+)?(?:(?<app>$APPS)\\s+)?(?:messages?|texts?|chats?|notifications?|msgs?)(?:\\s+(?:from|by)\\s+(?<who>.+))?$|^(?:do i have|i get|any|have i got)\\s+(?:any\\s+)?(?:new\\s+)?(?:(?<app2>$APPS)\\s+)?(?:messages?|texts?|msgs?)(?:\\s+from\\s+(?<who2>.+))?$|^who (?:messaged|texted|message|don message|send) me$") { m, _ ->
            val app = (m.g("app") ?: m.g("app2"))?.let { appDisplay(it) }
            actions("read_messages", request(ActionId.READ_MESSAGES, Params.APP to app, Params.SENDER to (m.g("who") ?: m.g("who2"))))
        },
        Rule("^(?:what did|wetin)\\s+(?<who>.+?)\\s+(?:say|talk|send|write)(?: to me)?$") { m, _ ->
            actions("read_messages", request(ActionId.READ_MESSAGES, Params.SENDER to m.g("who")))
        },
        Rule("^(?:reply|respond|answer)(?:\\s+(?:to\\s+)?(?<who>[^:]+?))?\\s*(?:\\s(?:saying|say|sey|with|that|and say|and tell (?:him|her|them|am))\\s+|:\\s*)(?<m>.+)$|^(?:reply|respond)(?:\\s+(?:to\\s+)?(?<who2>[^:]+?))?$") { m, _ ->
            val who = (m.g("who") ?: m.g("who2"))?.takeIf { it.lowercase() !in setOf("him", "her", "them", "am", "it", "this", "the message", "that") }
            val text = m.g("m")?.let { MessageRewriter.toDirectSpeech(it, thirdPerson = true) }
            actions("reply", request(ActionId.REPLY_TO_MESSAGE, Params.SENDER to who, Params.MESSAGE to text))
        },

        // Media
        Rule("^(?<c>pause|hold on|hold am|pause am|stop the (?:music|song|video))(?:\\s+(?:the\\s+)?(?:music|song|video|playback))?$|^(?<c2>resume|continue|unpause|play)(?:\\s+(?:the\\s+)?(?:music|song|video|playback))?$|^(?<c3>next|skip|change)(?:\\s+(?:am|this|it|the))?(?:\\s+(?:song|track|music|one))?$|^(?<c4>previous|go back to the previous|last)(?:\\s+(?:song|track))$|^(?<c5>previous)$") { m, _ ->
            val command = when {
                m.g("c") != null -> "pause"
                m.g("c2") != null -> "play"
                m.g("c3") != null -> "next"
                else -> "previous"
            }
            actions("media_control", request(ActionId.MEDIA_CONTROL, Params.COMMAND to command))
        },
        Rule("^(?:play|put on|play me|abeg play|i wan hear|let me hear)\\s+(?<q>.+?)(?:\\s+(?:on|for|in|with|from)\\s+(?<app>spotify|youtube music|youtube|boomplay|audiomack|apple music|deezer))?$") { m, _ ->
            val query = m.g("q")!!.removePrefix("some ").removePrefix("the song ")
            actions("play_media", request(ActionId.PLAY_MEDIA, Params.QUERY to query, Params.APP to m.g("app")?.lowercase()))
        },

        // Directions
        Rule("^(?:take me to|navigate to|navigate me to|directions to|get directions to|give me directions to|drive me to|show me the way to|how (?:do )?i (?:get|go) to|how i go reach|how i go get to|carry me go|i wan go|i want to go to|let's go to|lets go to|route to)\\s+(?<d>.+?)(?:\\s+(?:by|on|using)\\s+(?<mode>foot|walking|walk|car|driving|bus|transit|bike|okada|keke|motorcycle))?$") { m, _ ->
            val mode = when (m.g("mode")?.lowercase()) {
                null -> null
                "foot", "walking", "walk" -> "walking"
                "bus", "transit", "keke" -> "transit"
                "bike", "okada", "motorcycle" -> "motorcycle"
                else -> "driving"
            }
            actions("navigate", request(ActionId.NAVIGATE, Params.DESTINATION to m.g("d"), Params.MODE to mode))
        },

        // Messages: WhatsApp, SMS or another named app (Telegram, Messenger, Signal...)
        Rule(
            "^(?:open (?:$APPS) and\\s+)?(?:send|text|message|whatsapp|sms)\\s+(?<r>.+?)\\s+(?:an?\\s+)?" +
                "(?:(?:$APPS)(?: message| text| chat| dm)?|text message|message (?:on|via|through|in|by) (?:$APPS)|message|text|dm)" +
                "(?:\\s+(?<m>(?:saying|say|sey|tell am say|tell am|telling|to say|to tell|that|and tell|and say|and ask|asking|which says)\\b.*))?$",
        ) { m, text -> message(m.g("r"), m.g("m"), text) },
        Rule(
            "^(?:open (?:$APPS) and\\s+)?(?:send|write)\\s+(?:an?\\s+)?(?:(?:$APPS)(?: message)?|text message|message(?: (?:on|via|in|by) (?:$APPS))?|text|msg|dm)\\s+(?:to|give|for)\\s+(?<r>.+?)" +
                "(?:\\s+(?:on|via|through|in|by) (?:$APPS))?" +
                "(?:\\s+(?<m>(?:saying|say|sey|tell am say|tell am|telling|to say|to tell|that|and tell|and say|and ask|asking|which says)\\b.*))?$",
        ) { m, text -> message(m.g("r"), m.g("m"), text) },
        Rule(
            "^(?:whatsapp|message|text|sms|dm)\\s+(?<r>.+?)(?:\\s+(?:on|via|through|in|by) (?:$APPS))?" +
                "(?:\\s+(?<m>(?:saying|say|sey|tell am say|tell am|telling|to say|that|and tell|and say|and ask|asking)\\b.*))?$",
        ) { m, text -> message(m.g("r"), m.g("m"), text) },
        Rule("^tell\\s+(?<r>.+?)\\s+(?:on|via|through|in|by) (?:$APPS)\\s+(?<m>.+)$") { m, text -> message(m.g("r"), m.g("m"), text) },

        // Calls (opens the dialer only)
        Rule("^(?:call|ring|phone|dial)\\s+(?!me\\b)(?<r>.+?)(?:\\s+on (?:the )?phone)?$") { m, _ ->
            actions("call", request(ActionId.DIAL_CONTACT, Params.RECIPIENT to m.g("r")))
        },

        // Memory
        Rule("^remember (?:that\\s+)?(?<c>.+?) is my (?<a>.+)$") { m, _ ->
            actions("remember", request(ActionId.REMEMBER_ALIAS, Params.ALIAS to m.g("a"), Params.CONTACT to m.g("c")))
        },

        // Alarms and timers
        Rule("^(?:wake me(?: up)?|get me up)(?:\\s+(?<t>.+))?$") { m, _ ->
            val t = m.g("t")?.let { time.parseTimeOfDay(it, preferMorning = true) }
            actions(
                "set_alarm",
                ActionRequest(
                    ActionId.SET_ALARM,
                    listOfNotNull(Params.LABEL to "Wake up", t?.let { Params.TIME to it.toString() }).toMap(),
                    prompts = mapOf(Params.TIME to "What time should I wake you?"),
                ),
            )
        },
        Rule("^(?:set|create|make|add|put)\\s+(?:an?\\s+|my\\s+)?alarm(?:\\s+(?<t>.+))?$") { m, _ ->
            val t = m.g("t")?.let { time.parseTimeOfDay(it) }
            actions("set_alarm", request(ActionId.SET_ALARM, Params.TIME to t?.toString()))
        },
        Rule("^(?:set|start|create|make|put)\\s+(?:an?\\s+)?(?:(?<d1>.+?)\\s+)?timer(?:\\s+(?:for|of)\\s+(?<d2>.+))?$") { m, _ ->
            timer(m.g("d1") ?: m.g("d2"))
        },
        Rule("^timer (?:for )?(?<d>.+)$|^(?<d2>.+?) timer$") { m, _ -> timer(m.g("d") ?: m.g("d2")) },

        // Reminders, notes, tasks, calendar
        Rule("^remind me(?:\\s+(?<rest>.+))?$") { m, _ -> reminder(m.g("rest")) },
        Rule("^(?:take|make|save|write|create|add)\\s+(?:a\\s+)?(?:new\\s+)?note(?:\\s+(?:that|saying|to say|of|about))?(?:[:\\s]+(?<t>.+))?$|^note(?: that|:| down)?\\s+(?<t2>.+)$") { m, _ ->
            actions("save_note", request(ActionId.SAVE_NOTE, Params.TEXT to (m.g("t") ?: m.g("t2"))?.let(::sentence)))
        },
        Rule("^add\\s+(?<t>.+?)\\s+to\\s+my\\s+(?:to-?do|todo|task|tasks)(?:\\s+list)?$") { m, _ -> task(m.g("t")) },
        Rule("^(?:create|add|make|new)\\s+(?:a\\s+)?(?:new\\s+)?(?:task|to-?do)(?:\\s+to)?(?:[:\\s]+(?<t>.+))?$") { m, _ -> task(m.g("t")) },
        Rule(
            "^(?:schedule|create|add|set up|book|put)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?(?<k>meeting|event|appointment|calendar event)" +
                "(?:\\s+(?<c>called|titled|named|for|about|with|on my calendar|in my calendar))?(?:\\s+(?<rest>.+))?$",
        ) { m, _ -> calendar(m.g("k")!!, m.g("c"), m.g("rest")) },

        // Device controls
        Rule("^(?:(?:turn|switch|put)\\s+)?(?<s>on|off)\\s+(?:the\\s+|my\\s+)?(?:flash ?light|torch|flash|light)$|^(?:turn|switch)\\s+(?:the\\s+|my\\s+)?(?:flash ?light|torch)\\s+(?<s2>on|off)$|^(?:flash ?light|torch)\\s+(?<s3>on|off)$") { m, _ ->
            val state = m.g("s") ?: m.g("s2") ?: m.g("s3")
            actions("flashlight", request(ActionId.SET_FLASHLIGHT, Params.ENABLED to if (state == "off") "false" else "true"))
        },
        Rule("^(?:turn|switch|put)\\s+(?<s>on|off)\\s+(?:the\\s+)?(?:do not disturb|dnd)(?: mode)?$|^(?:turn|switch|put)\\s+(?:do not disturb|dnd)(?: mode)?\\s+(?<s2>on|off)$|^(?<v>enable|activate|disable|deactivate|start|stop)\\s+(?:do not disturb|dnd)(?: mode)?$|^(?:do not disturb|dnd)(?: mode)?\\s+(?<s3>on|off)$|^(?:silence my phone|go silent)$") { m, _ ->
            val word = m.g("s") ?: m.g("s2") ?: m.g("s3") ?: m.g("v") ?: "on"
            val enabled = word.lowercase() !in setOf("off", "disable", "deactivate", "stop")
            actions("dnd", request(ActionId.SET_DO_NOT_DISTURB, Params.ENABLED to enabled.toString()))
        },
        Rule("^(?:set|turn|change|put)\\s+(?:the\\s+)?(?:media\\s+|music\\s+)?volume\\s+(?:to\\s+|at\\s+)?(?<n>${NumberWords.PATTERN}(?:\\s+\\w+)?)\\s*(?:%|percent)?$|^volume\\s+(?:to\\s+)?(?<n2>\\d{1,3})\\s*(?:%|percent)?$") { m, _ ->
            val raw = (m.g("n") ?: m.g("n2"))!!.removeSuffix("percent").trim()
            val level = NumberWords.parse(raw)?.coerceIn(0, 100) ?: return@Rule null
            actions("volume", request(ActionId.SET_VOLUME, Params.LEVEL to level.toString()))
        },
        Rule("^(?:max(?:imum)?|full) volume$|^(?:turn|set) (?:the )?volume to (?:the )?max(?:imum)?$") { _, _ ->
            actions("volume", request(ActionId.SET_VOLUME, Params.LEVEL to "100"))
        },
        Rule("^(?:turn|crank)\\s+(?:the\\s+)?(?:volume|music|sound|media|it)\\s+(?<d>up|down)$|^(?:turn|crank)\\s+(?<d2>up|down)\\s+(?:the\\s+)?(?:volume|music|sound|media)$|^volume (?<d3>up|down)$|^(?<i>increase|raise|decrease|lower|reduce|add|add to) (?:the )?(?:media )?(?:volume|sound)$|^(?:make it|it's too) (?<l>louder|quieter|softer|loud|quiet)$") { m, _ ->
            val d = m.g("d") ?: m.g("d2") ?: m.g("d3") ?: m.g("i") ?: m.g("l")
            val dir = if (d?.lowercase() in setOf("up", "increase", "raise", "louder", "quiet", "add", "add to")) "up" else "down"
            actions("volume", request(ActionId.SET_VOLUME, Params.DIRECTION to dir))
        },
        Rule("^(?<m>mute|unmute)(?:\\s+(?:the\\s+)?(?:volume|sound|media|music|phone))?$") { m, _ ->
            actions("volume", request(ActionId.SET_VOLUME, Params.DIRECTION to m.g("m")!!.lowercase()))
        },
        Rule("^(?:open|launch|start)\\s+(?:the\\s+|my\\s+)?camera(?: app)?$|^take\\s+(?:a\\s+)?(?:photo|picture|selfie|pic)$") { _, _ ->
            actions("camera", request(ActionId.LAUNCH_CAMERA, Params.MODE to "photo"))
        },
        Rule("^(?:record|take|shoot|start recording)\\s+(?:a\\s+)?video$") { _, _ ->
            actions("camera", request(ActionId.LAUNCH_CAMERA, Params.MODE to "video"))
        },
        Rule("^(?:turn|switch)\\s+(?<s>on|off)\\s+(?:the\\s+)?(?<p>wi-?fi|wifi|wi fi|bluetooth|airplane mode|flight mode|mobile data|data|location|gps)$|^(?:turn|switch)\\s+(?:the\\s+)?(?<p2>wi-?fi|wifi|bluetooth|airplane mode|flight mode|mobile data|location|gps)\\s+(?<s2>on|off)$") { m, _ ->
            val page = settingsPage(m.g("p") ?: m.g("p2")) ?: return@Rule null
            actions(
                "open_settings",
                request(ActionId.OPEN_SETTINGS, Params.PAGE to page),
                preface = "Android doesn't let apps switch that directly, so I'll open the right settings page for you.",
            )
        },
        Rule("^(?:open|show|go to|take me to|launch)\\s+(?:the\\s+|my\\s+)?(?<p>.+?)\\s+settings$|^(?:open|show|go to)\\s+(?:the\\s+)?settings(?:\\s+for\\s+(?<p2>.+))?$|^(?:open|show|launch)\\s+(?:the\\s+)?settings app$") { m, _ ->
            val spoken = m.g("p") ?: m.g("p2")
            val page = if (spoken == null) "general" else settingsPage(spoken) ?: return@Rule null
            actions("open_settings", request(ActionId.OPEN_SETTINGS, Params.PAGE to page))
        },
        Rule("^(?:search|google|look up|search the web|search online)(?:\\s+(?:the web|online|google))?\\s+(?:for\\s+)?(?<q>.+)$") { m, _ ->
            actions("web_search", request(ActionId.WEB_SEARCH, Params.QUERY to m.g("q")))
        },
        Rule("^(?:open|launch|start|run|go to)\\s+(?:the\\s+|my\\s+)?(?<a>.+?)(?:\\s+app(?:lication)?)?$") { m, _ ->
            actions("open_app", request(ActionId.OPEN_APP, Params.APP to m.g("a")))
        },
    )

    // ------------------------------------------------------------- builders

    /** Picks WhatsApp, SMS or another app from the words used, falling back to the user's default. */
    private fun message(recipientRaw: String?, messageRaw: String?, fullText: String): Interpretation? {
        val named = Regex("\\b($APPS|text message)\\b", RegexOption.IGNORE_CASE).find(fullText)?.value?.lowercase()
        val recipient = recipientRaw
            ?.replace(Regex("\\b(?:on|via|through|in|by) (?:$APPS)\\b", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("^to\\s+", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+(?:on|via|through|in|by|an?)$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.lowercase() !in setOf("him", "her", "them", "a", "an") && !Regex("^($APPS)$", RegexOption.IGNORE_CASE).matches(it) }
            ?: return null
        val message = messageRaw?.let {
            val quoted = it.contains('"') || it.contains('“')
            MessageRewriter.toDirectSpeech(it, thirdPerson = !quoted)
        }?.takeIf { it.isNotBlank() }
        val request = when {
            named == "whatsapp" -> request(ActionId.PREPARE_WHATSAPP_MESSAGE, Params.RECIPIENT to recipient, Params.MESSAGE to message)
            named == "sms" || named == "text message" -> request(ActionId.PREPARE_SMS, Params.RECIPIENT to recipient, Params.MESSAGE to message)
            named != null -> request(
                ActionId.SHARE_MESSAGE_TO_APP,
                Params.APP to APP_NAMES[named],
                Params.RECIPIENT to recipient,
                Params.MESSAGE to message,
            )
            defaultChannel() == MessagingChannel.SMS -> request(ActionId.PREPARE_SMS, Params.RECIPIENT to recipient, Params.MESSAGE to message)
            else -> request(ActionId.PREPARE_WHATSAPP_MESSAGE, Params.RECIPIENT to recipient, Params.MESSAGE to message)
        }
        return actions("send_message", request)
    }

    private fun timer(spoken: String?): Interpretation? {
        val seconds = spoken?.let { time.parseDuration(it) }?.seconds
        if (spoken != null && seconds == null) return null
        return actions("set_timer", request(ActionId.SET_TIMER, Params.SECONDS to seconds?.toString()))
    }

    private fun reminder(rest: String?): Interpretation {
        if (rest == null) return actions("create_reminder", request(ActionId.CREATE_REMINDER))
        // "at 5pm to call mum" (time first)
        Regex("^(?<w>(?:at|in|on|by|tomorrow|tonight|today|this|next)\\b.+?)\\s+to\\s+(?<t>.+)$", RegexOption.IGNORE_CASE)
            .find(rest)?.let { m ->
                val whenText = m.groups["w"]!!.value
                if (isPureTimeExpression(whenText)) {
                    val at = time.parseDateTime(whenText)
                    return actions(
                        "create_reminder",
                        request(ActionId.CREATE_REMINDER, Params.TEXT to sentence(m.groups["t"]!!.value), Params.AT to at?.toString()),
                    )
                }
            }
        val body = rest.replace(Regex("^(?:to|that|about|say|sey)\\s+(?:make\\s+i\\s+)?", RegexOption.IGNORE_CASE), "")
        val (task, whenText) = splitTrailingTime(body)
        val at = whenText?.let { time.parseDateTime(it) }
        return actions(
            "create_reminder",
            request(ActionId.CREATE_REMINDER, Params.TEXT to task.takeIf { it.isNotBlank() }?.let(::sentence), Params.AT to at?.toString()),
        )
    }

    private fun task(spoken: String?): Interpretation {
        if (spoken == null) return actions("create_task", request(ActionId.CREATE_TASK))
        val (title, whenText) = splitTrailingTime(spoken)
        val due = whenText?.let { time.parseDateTime(it) }
        return actions("create_task", request(ActionId.CREATE_TASK, Params.TITLE to sentence(title), Params.DUE to due?.toString()))
    }

    private fun calendar(kind: String, connector: String?, rest: String?): Interpretation {
        val (titlePart, whenText) = rest?.let { splitTrailingTime(it) } ?: ("" to null)
        val start = whenText?.let { time.parseDateTime(it) }
        val baseKind = kind.lowercase().removePrefix("calendar ").replaceFirstChar { it.uppercaseChar() }
        val title = when {
            titlePart.isBlank() -> if (start != null) baseKind else null
            connector?.lowercase() == "with" -> "$baseKind with ${titlePart.trim()}"
            connector?.lowercase() == "about" || connector?.lowercase() == "for" -> "$baseKind: ${titlePart.trim()}"
            else -> sentence(titlePart)
        }
        return actions(
            "create_event",
            request(ActionId.CREATE_CALENDAR_EVENT, Params.TITLE to title, Params.START to start?.toString()),
        )
    }

    /**
     * Splits "call mum tomorrow at 9" into ("call mum", "tomorrow at 9"), choosing the
     * earliest split whose tail consists only of time vocabulary.
     */
    internal fun splitTrailingTime(text: String): Pair<String, String?> {
        val starts = TIME_START.findAll(text).map { it.range.first }.toList()
        for (index in starts) {
            val tail = text.substring(index).trim()
            val head = text.substring(0, index).trim()
            if (head.isNotEmpty() && isPureTimeExpression(tail) && time.parseDateTime(tail) != null) {
                return head to tail
            }
        }
        return text.trim() to null
    }

    private fun isPureTimeExpression(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[,.](?!\\d)"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { w ->
            w in TIME_WORDS || w.matches(Regex("\\d{1,2}([:.]\\d{2})?(am|pm|a\\.m\\.|p\\.m\\.)?")) || NumberWords.parse(w) != null
        }
    }

    private fun appDisplay(name: String): String = APP_NAMES[name.lowercase()]
        ?: when (name.lowercase()) { "whatsapp" -> "WhatsApp"; "sms" -> "Messages"; else -> name.replaceFirstChar { it.uppercaseChar() } }

    private fun settingsPage(spoken: String?): String? {
        val s = spoken?.lowercase()?.trim() ?: return null
        return SETTINGS_SYNONYMS.entries.firstOrNull { (k, _) -> s == k || s.contains(k) }?.value
    }

    private fun sentence(text: String): String = text.trim().trimEnd('.').replaceFirstChar { it.uppercaseChar() }

    private fun clean(input: String): String {
        var t = input.trim().replace(Regex("\\s+"), " ")
        t = t.trimEnd('.', '!', '?', ',').trim()
        var previous: String
        do {
            previous = t
            t = t.replace(LEADING_FILLER, "").replace(TRAILING_FILLER, "").trim().trimEnd('.', '!', '?', ',')
        } while (t != previous)
        return t
    }

    companion object {
        /** Messaging apps recognised by name in commands. */
        private const val APPS = "whatsapp|sms|telegram|messenger|facebook messenger|signal|instagram|gmail|email|slack|teams|imo"
        private val APP_NAMES = mapOf(
            "telegram" to "Telegram", "messenger" to "Messenger", "facebook messenger" to "Messenger", "signal" to "Signal",
            "instagram" to "Instagram", "gmail" to "Gmail", "email" to "Gmail", "slack" to "Slack", "teams" to "Teams", "imo" to "imo",
        )
        private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
        private val CONJUNCTION = Regex("\\s*,?\\s+(?:and then|then|and also|and)\\s+|\\s*,\\s+then\\s+", RegexOption.IGNORE_CASE)
        private val LEADING_FILLER = Regex(
            "^(?:hey\\s+voice\\s*pilot|ok(?:ay)?\\s+voice\\s*pilot|voice\\s*pilot|(?:hey|ok(?:ay)?)\\s+(?:s\\s*l|es+\\s+el+)|hey|ok(?:ay)?|please|kindly|abeg|" +
                "can you|could you|would you|will you|i want you to|i'd like you to|i would like you to|help me(?: to)?|biko|pls|plz|make you|you fit|una fit|abeg make you)[,\\s]+",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_FILLER = Regex("[,\\s]+(?:please|for me|now|right now|abeg|thanks|thank you|o|sharp sharp|quick quick|abeg o|biko|jare)$", RegexOption.IGNORE_CASE)
        private val TIME_START = Regex(
            "\\b(?:at|in|on|by|tomorrow|tonight|today|this|next|day after|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val TIME_WORDS = setOf(
            "at", "in", "on", "by", "tomorrow", "tonight", "today", "this", "next", "morning", "afternoon", "evening",
            "night", "am", "pm", "a.m.", "p.m.", "noon", "midday", "midnight", "o'clock", "oclock", "minutes", "minute",
            "hours", "hour", "seconds", "second", "mins", "min", "hrs", "hr", "half", "an", "a", "and", "quarter", "past",
            "to", "day", "after", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "from", "now",
        )
        private val SETTINGS_SYNONYMS = linkedMapOf(
            "wi-fi" to "wifi", "wifi" to "wifi", "wi fi" to "wifi", "wireless" to "wifi", "internet" to "wifi",
            "bluetooth" to "bluetooth",
            "display" to "display", "screen" to "display", "brightness" to "display",
            "sound" to "sound", "audio" to "sound", "volume" to "sound", "ringtone" to "sound",
            "battery" to "battery", "power" to "battery",
            "location" to "location", "gps" to "location",
            "apps" to "apps", "application" to "apps",
            "notification" to "notifications",
            "accessibility" to "accessibility",
            "mobile data" to "network", "data" to "network", "network" to "network", "cellular" to "network",
            "airplane" to "airplane", "flight" to "airplane",
            "date" to "date", "time" to "date",
            "security" to "security", "lock screen" to "security", "privacy" to "security",
            "storage" to "storage",
            "general" to "general", "phone" to "general", "system" to "general",
        )
        const val HELP_TEXT =
            "I can open apps and settings, set alarms, timers and reminders, prepare WhatsApp, SMS and Telegram messages, " +
                "read your new messages and reply, play music, give directions, check your airtime and data balance, " +
                "control the flashlight, volume and Do Not Disturb, and run your routines. " +
                "Try \"Read my WhatsApp messages\", \"Play Burna Boy on Spotify\" or \"Take me to Ikeja City Mall\"."
    }
}
