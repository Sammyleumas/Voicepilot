package com.voicepilot.core.interpret

import com.voicepilot.core.action.Params
import com.voicepilot.core.model.ActionId
import com.voicepilot.core.model.ActionRequest
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

        // Sleep: suggest Do Not Disturb + alarm, then ask for the wake-up time.
        Rule("^(?:i'?m|i am) (?:going to (?:sleep|bed)|off to bed|heading to bed|about to sleep)$|^good ?night$|^bed ?time$") { _, _ ->
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

        // Messages: WhatsApp, SMS or another named app (Telegram, Messenger, Signal...)
        Rule(
            "^(?:open (?:$APPS) and\\s+)?(?:send|text|message|whatsapp|sms)\\s+(?<r>.+?)\\s+(?:an?\\s+)?" +
                "(?:(?:$APPS)(?: message| text| chat| dm)?|text message|message (?:on|via|through|in|by) (?:$APPS)|message|text|dm)" +
                "(?:\\s+(?<m>(?:saying|telling|to say|to tell|that|and tell|and say|and ask|asking|which says)\\b.*))?$",
        ) { m, text -> message(m.g("r"), m.g("m"), text) },
        Rule(
            "^(?:open (?:$APPS) and\\s+)?(?:send|write)\\s+(?:an?\\s+)?(?:(?:$APPS)(?: message)?|text message|message(?: (?:on|via|in|by) (?:$APPS))?|text|dm)\\s+to\\s+(?<r>.+?)" +
                "(?:\\s+(?:on|via|through|in|by) (?:$APPS))?" +
                "(?:\\s+(?<m>(?:saying|telling|to say|to tell|that|and tell|and say|and ask|asking|which says)\\b.*))?$",
        ) { m, text -> message(m.g("r"), m.g("m"), text) },
        Rule(
            "^(?:whatsapp|message|text|sms|dm)\\s+(?<r>.+?)(?:\\s+(?:on|via|through|in|by) (?:$APPS))?" +
                "(?:\\s+(?<m>(?:saying|telling|to say|that|and tell|and say|and ask|asking)\\b.*))?$",
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
        Rule("^(?:turn|switch|put)\\s+(?<s>on|off)\\s+(?:the\\s+|my\\s+)?(?:flash ?light|torch)$|^(?:turn|switch)\\s+(?:the\\s+|my\\s+)?(?:flash ?light|torch)\\s+(?<s2>on|off)$|^(?:flash ?light|torch)\\s+(?<s3>on|off)$") { m, _ ->
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
        Rule("^(?:turn|crank)\\s+(?:the\\s+)?(?:volume|music|sound|media|it)\\s+(?<d>up|down)$|^(?:turn|crank)\\s+(?<d2>up|down)\\s+(?:the\\s+)?(?:volume|music|sound|media)$|^volume (?<d3>up|down)$|^(?<i>increase|raise|decrease|lower|reduce) (?:the )?(?:media )?volume$|^(?:make it|it's too) (?<l>louder|quieter|softer|loud|quiet)$") { m, _ ->
            val d = m.g("d") ?: m.g("d2") ?: m.g("d3") ?: m.g("i") ?: m.g("l")
            val dir = if (d?.lowercase() in setOf("up", "increase", "raise", "louder", "quiet")) "up" else "down"
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
        val body = rest.replace(Regex("^(?:to|that|about)\\s+", RegexOption.IGNORE_CASE), "")
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
            "^(?:hey\\s+voice\\s*pilot|ok(?:ay)?\\s+voice\\s*pilot|voice\\s*pilot|hey|ok(?:ay)?|please|kindly|abeg|" +
                "can you|could you|would you|will you|i want you to|i'd like you to|i would like you to|help me(?: to)?)[,\\s]+",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_FILLER = Regex("[,\\s]+(?:please|for me|now|right now|abeg|thanks|thank you)$", RegexOption.IGNORE_CASE)
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
            "I can open apps and settings, set alarms, timers and reminders, save notes and tasks, prepare WhatsApp " +
                "messages, open the dialer, control the flashlight, volume and Do Not Disturb, and run your routines. " +
                "Try \"Set an alarm for 6:30\" or \"Send Samuel a WhatsApp message saying I'm on my way\"."
    }
}
