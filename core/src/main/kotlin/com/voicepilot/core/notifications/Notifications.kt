package com.voicepilot.core.notifications

/** A notification the user allowed VoicePilot to see. Held in memory only by default. */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMillis: Long,
    val isMessaging: Boolean,
    val canReply: Boolean,
    val conversationTitle: String? = null,
)

object NotificationFilter {
    /** Packages known to post conversation notifications. */
    val MESSAGING_PACKAGES = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
        "com.samsung.android.messaging", "com.facebook.orca", "com.instagram.android", "com.google.android.gm",
        "com.slack", "com.microsoft.teams",
    )

    fun isAllowed(packageName: String, allowed: Set<String>): Boolean = packageName in allowed
}

/** Produces short spoken summaries without exposing more content than needed. */
object NotificationSummarizer {

    fun summarize(items: List<NotificationItem>, includeContent: Boolean = false): String {
        if (items.isEmpty()) return "You have no new notifications."
        val byApp = items.groupBy { it.appLabel }
        val parts = byApp.entries
            .sortedByDescending { it.value.size }
            .map { (app, list) ->
                val senders = list.map { it.conversationTitle ?: it.title }.filter { it.isNotBlank() }
                    .groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }
                val n = list.size
                val noun = if (list.any { it.isMessaging }) "message" else "notification"
                val head = "$n ${if (n == 1) noun else noun + "s"} from $app"
                if (senders.isEmpty()) head
                else head + ": " + senders.take(3).joinToString(", ") { (who, c) -> if (c > 1) "$c from $who" else who } +
                    if (senders.size > 3) " and others" else ""
            }
        val total = items.size
        val summary = "You have $total new ${if (total == 1) "notification" else "notifications"}. " + parts.joinToString(". ") + "."
        if (!includeContent) return summary
        val latest = items.maxByOrNull { it.postedAtMillis }!!
        return "$summary The latest, from ${latest.title}, says: ${latest.text.take(200)}"
    }

    /** One-line announcement for a newly arrived notification. */
    fun announce(item: NotificationItem, readContent: Boolean): String =
        if (readContent && item.text.isNotBlank()) "${item.appLabel}: ${item.title} says ${item.text.take(160)}"
        else "New ${item.appLabel} ${if (item.isMessaging) "message" else "notification"} from ${item.title}."
}
